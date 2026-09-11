package net.activitywatch.android.watcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.DropBoxManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import net.activitywatch.android.RustInterface
import net.activitywatch.android.deviceHostname
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.Instant

private const val TAG = "aw-idle"

/** What `dumpsys power` says: ms since the last user activity (touch, key), and wakefulness. */
internal data class PowerSample(val lastActivityAgoMs: Long?, val wakefulness: String?)

private val AGO_RE = Regex("""^\s*mLastUserActivityTime=\d+ \((\d+) ms ago\)""")
private val WAKE_RE = Regex("""^\s*mWakefulness=([A-Za-z]+)""")

/** Reads the two fields from `dumpsys power` output, stopping as soon as both are seen. */
internal fun parsePowerDump(lines: Sequence<String>): PowerSample {
    var ago: Long? = null
    var wake: String? = null
    for (line in lines) {
        if (ago == null) {
            val m = AGO_RE.find(line)
            if (m != null) ago = m.groupValues[1].toLong()
        }
        if (wake == null) {
            val m = WAKE_RE.find(line)
            if (m != null) wake = m.groupValues[1]
        }
        if (ago != null && wake != null) break
    }
    return PowerSample(ago, wake)
}

// Log lines that explain a process death: init stopping services, USB state, app kills,
// ANRs and crashes, low-memory kills.
private val KILL_RE = Regex(
    """Sending signal \d+ to service|init: Service .* received signal|init: processing action \(sys\.usb\.config|USB_STATE=|am_kill|am_proc_died|am_anr|am_crash|lowmemorykiller|lmkd"""
)
private val ROUTINE_TRIM_RE = Regex("""empty for \d+s""")

private fun File.readLongOrNull(): Long? =
    try { readText().trim().toLongOrNull() } catch (e: Exception) { null }

/**
 * AFK watcher for the phone, inside the app, so it lives and restarts with the app's
 * process (no adb shell process, which Android kills whenever it restarts adbd).
 *
 * While the screen is on, every [INTERVAL_S] s it reads the last user-activity time from
 * `dumpsys power` (needs android.permission.DUMP, granted with `pm grant`) and heartbeats
 * `aw-watcher-afk_<host>` (type afkstatus) like the desktop aw-watcher-afk: not-afk while
 * the last touch is newer than [THRESH_S] s and the screen is awake, afk otherwise, with
 * the afk span backdated to the last touch. With the screen off it does nothing until the
 * screen comes on; the afk span is then extended over the gap.
 *
 * Files, in the app's external files dir (readable over adb without root):
 * - aw-idle-taps.log: every new touch time seen, `<epoch> <wakefulness> [cc]`
 * - aw-idle-life.log: `<epoch> <utc> <event> <details>` for start, alive (every
 *   [ALIVE_S] s while polling), screen-on, screen-off, errors; also in logcat, tag aw-idle
 * - aw-idle-kills.log: log lines explaining kills (needs READ_LOGS)
 * - dropbox/: this app's ANR and crash reports, copied before the system rotates them
 * - cc-driving: while it exists (line 1 = start epoch, line 2 = end epoch once closed),
 *   touches are an automated driver's, not the user's: logged with "cc", afk with by=cc,
 *   and the window recorded as a span in `aw-watcher-cc-phone_<host>`; cc-driving.log
 *   is the ledger of closed windows.
 */
class IdleWatcher private constructor(private val context: Context) {

    companion object {
        private const val INTERVAL_S = 5L
        private const val THRESH_S = 120L
        private const val ALIVE_S = 120L
        private const val NO_DUMP_RETRY_S = 60L
        private const val CC_STALE_S = 7200L
        private const val CLIENT = "aw-phone-idle-watcher"
        private const val TAPS_MAX = 2_000_000L
        private const val LIFE_MAX = 200_000L
        private const val KILLS_MAX = 300_000L
        private const val DROPBOX_KEEP = 40
        private val DROPBOX_TAGS = listOf("data_app_anr", "data_app_crash", "data_app_native_crash")

        private var instance: IdleWatcher? = null

        @Synchronized
        fun start(context: Context) {
            if (instance == null) {
                instance = IdleWatcher(context.applicationContext).also { it.begin() }
            }
        }
    }

    private val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val tapsLog = File(dir, "aw-idle-taps.log")
    private val lifeLog = File(dir, "aw-idle-life.log")
    private val killsLog = File(dir, "aw-idle-kills.log")
    private val killsSince = File(dir, "aw-idle-capture.since")
    private val dropboxDir = File(dir, "dropbox")
    private val dropboxSince = File(dir, "aw-idle-dropbox.since")
    private val ccFlag = File(dir, "cc-driving")
    private val ccLedger = File(dir, "cc-driving.log")

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val host = deviceHostname(context)
    private val bucket = "aw-watcher-afk_$host"
    private val ccBucket = "aw-watcher-cc-phone_$host"
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val thread = HandlerThread("aw-idle", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private var ri: RustInterface? = null

    // All of the state below is touched on the watcher thread only.
    private var state: String? = null
    private var lastLogged = 0L
    private var lastAlive = 0L
    private var lastBeat = 0L
    private var alTouch = 0L
    private var ccSeen = 0L
    private var ccDone = 0L
    private var fails = 0
    private var dumpOk: Boolean? = null

    private val tick = Runnable { safeTick() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            life(if (intent.action == Intent.ACTION_SCREEN_ON) "screen-on" else "screen-off", "")
            handler.removeCallbacks(tick)
            safeTick()
        }
    }

    private fun begin() {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            dir.mkdirs()
            life(
                "start",
                "pid=${Process.myPid()} uid=${Process.myUid()} host=$host" +
                    " dump=${granted(Manifest.permission.DUMP)} logs=${granted(Manifest.permission.READ_LOGS)}" +
                    " alive_every=${ALIVE_S}s dir=$dir"
            )
            try {
                ri = RustInterface(context)
            } catch (t: Throwable) {
                life("error", "RustInterface: $t")
            }
            lastLogged = lastEpochIn(tapsLog)
            lastAlive = now()
            ensureBuckets()
            capture()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Only protected system broadcasts match this filter, so exporting is moot.
            ContextCompat.registerReceiver(
                context, screenReceiver, filter, null, handler, ContextCompat.RECEIVER_EXPORTED
            )
            safeTick()
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun iso(epoch: Long): String = isoFormat.format(Date(epoch * 1000))

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun safeTick() {
        var next = INTERVAL_S
        try {
            next = doTick()
        } catch (t: Throwable) {
            life("error", t.toString().take(300))
        }
        handler.removeCallbacks(tick)
        // With the screen off nobody can touch it: stop until SCREEN_ON.
        if (power.isInteractive) handler.postDelayed(tick, next * 1000)
    }

    private fun doTick(): Long {
        val now = now()
        if (now - lastAlive >= ALIVE_S) {
            life("alive", "fails=$fails")
            fails = 0
            lastAlive = now
            ensureBuckets()
            capture()
        }
        if (!granted(Manifest.permission.DUMP)) {
            if (dumpOk != false) life("no-dump", "android.permission.DUMP not granted (pm grant)")
            dumpOk = false
            return NO_DUMP_RETRY_S
        }

        var ccS = 0L
        var ccE: Long? = null
        if (ccFlag.exists()) {
            val lines = try { ccFlag.readLines() } catch (e: Exception) { emptyList() }
            ccS = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            ccE = lines.getOrNull(1)?.trim()?.toLongOrNull()
            if (ccE == null && ccS > 0 && now - ccS > CC_STALE_S) {
                life("cc-stale", "window opened $ccS never closed - dropped")
                ccFlag.delete()
                ccS = 0L
            }
        }
        val driving = ccS > 0 && ccE == null
        if (driving) {
            if (ccSeen != ccS) {
                ccBeat(ccS)
                ccSeen = ccS
            }
            ccBeat(now)
        } else if (ccS > 0 && ccE != null && ccDone != ccS) {
            if (ccSeen == ccS) {
                ccBeat(ccE)   // extends the span the live beats built
            } else {          // opened and closed between two looks: one event
                post(ccBucket, ccS, (ccE - ccS).toDouble(), JSONObject().put("by", "cc"), 0.0)
            }
            ccDone = ccS
            append(ccLedger, "$ccS $ccE\n", LIFE_MAX)
            life("cc-window", "${iso(ccS)} to ${iso(ccE)}")
        }

        val sample = samplePower()
        val ago = sample.lastActivityAgoMs
        if (ago == null) {
            if (dumpOk != false) life("no-dump", "dumpsys power gave no mLastUserActivityTime")
            dumpOk = false
            return INTERVAL_S
        }
        if (dumpOk == false) life("dump-ok", "")
        dumpOk = true
        val wake = sample.wakefulness ?: ""
        val touch = now - ago / 1000
        var by: String? = null
        if (ccS > 0 && touch >= ccS - 1) {
            if (driving || (ccE != null && touch <= ccE + 1)) by = "cc"
        }
        if (by == null) alTouch = touch
        // a closed window is dropped once the user touches again or his idle is decided
        if (ccE != null && (by == null || now - ccE > THRESH_S)) ccFlag.delete()
        if (touch > lastLogged + 1) {
            append(tapsLog, "$touch $wake ${by ?: ""}\n", TAPS_MAX)
            lastLogged = touch
        }
        val newState = if (by == null && wake == "Awake" && ago / 1000 < THRESH_S) "not-afk" else "afk"
        if (newState != state) {
            if (newState == "afk") {
                // idle began at the user's last touch: one event covering [then, now];
                // the heartbeats below extend it. Sent as a heartbeat, so after a
                // restart it merges into the afk event the previous process left,
                // which starts at that same touch, instead of duplicating it; after a
                // not-afk event the data differs and it is inserted as a new event
                val from = if (alTouch > 0) alTouch else touch
                post(bucket, from, (now - from).toDouble(), JSONObject().put("status", "afk"), (INTERVAL_S * 3).toDouble())
            } else {
                // stretch the idle span up to the touch that ended it (the screen may
                // have been off for hours), then start use at that touch
                if (state == "afk" && touch > lastBeat) beat(touch, "afk", null)
                beat(touch, "not-afk", null)
            }
        }
        beat(now, newState, by)
        state = newState
        return INTERVAL_S
    }

    private fun samplePower(): PowerSample {
        val p = ProcessBuilder("/system/bin/dumpsys", "power").redirectErrorStream(true).start()
        try {
            return p.inputStream.bufferedReader().use { parsePowerDump(it.lineSequence()) }
        } finally {
            p.destroy()
            p.waitFor()
        }
    }

    // Beats merge into the last event when within pulsetime; after a gap with no beats
    // (screen off) the pulsetime spans the gap, so an unchanged state is extended over it.
    private fun pulseFor(t: Long): Double {
        val base = INTERVAL_S * 3
        return if (lastBeat > 0 && t - lastBeat > base) (t - lastBeat + base).toDouble() else base.toDouble()
    }

    private fun beat(t: Long, status: String, by: String?) {
        val data = JSONObject().put("status", status)
        if (by != null) data.put("by", by)
        post(bucket, t, 0.0, data, pulseFor(t))
        if (t > lastBeat) lastBeat = t
    }

    private fun ccBeat(t: Long) {
        post(ccBucket, t, 0.0, JSONObject().put("by", "cc"), (INTERVAL_S * 3).toDouble())
    }

    private fun post(bucketId: String, t: Long, duration: Double, data: JSONObject, pulsetime: Double) {
        val rust = ri
        if (rust == null) {
            fails++
            return
        }
        try {
            val event = Event(Instant.ofEpochSecond(t), duration, data)
            val reply = rust.heartbeat(bucketId, event.toString(), pulsetime)
            if (reply.contains("error", ignoreCase = true)) {
                fails++
                Log.w(TAG, "heartbeat to $bucketId: ${reply.take(200)}")
            }
        } catch (e: Exception) {
            fails++
            Log.w(TAG, "heartbeat to $bucketId failed", e)
        }
    }

    private fun ensureBuckets() {
        try {
            ri?.createBucketHelper(bucket, "afkstatus", CLIENT)
            ri?.createBucketHelper(ccBucket, "cc-driving", CLIENT)
        } catch (e: Exception) {
            fails++
            Log.w(TAG, "bucket creation failed", e)
        }
    }

    // Copies, before the buffers roll, the log lines that explain kills (since the last
    // copy), and this app's new dropbox ANR and crash reports.
    private fun capture() {
        if (!granted(Manifest.permission.READ_LOGS)) return
        try {
            val since = killsSince.readLongOrNull() ?: (now() - 86_400)
            val next = now()
            val out = StringBuilder()
            for (buffers in listOf("kernel", "events,system,main")) {
                runLines(listOf("logcat", "-d", "-b", buffers, "-v", "epoch", "-t", "$since.000")) { line ->
                    if (KILL_RE.containsMatchIn(line) && !ROUTINE_TRIM_RE.containsMatchIn(line)) {
                        out.append(line).append('\n')
                    }
                }
            }
            if (out.isNotEmpty()) append(killsLog, out.toString(), KILLS_MAX)
            killsSince.writeText("$next\n")
        } catch (e: Exception) {
            life("error", "log capture: $e")
        }
        try {
            captureDropbox()
        } catch (e: Exception) {
            life("error", "dropbox capture: $e")
        }
    }

    private fun captureDropbox() {
        val dbm = context.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager ?: return
        val since = dropboxSince.readLongOrNull() ?: (System.currentTimeMillis() - 7 * 86_400_000L)
        var newest = since
        val pkg = context.packageName
        for (tag in DROPBOX_TAGS) {
            var t = since
            while (true) {
                val entry = dbm.getNextEntry(tag, t) ?: break
                try {
                    t = entry.timeMillis
                    if (t > newest) newest = t
                    val text = entry.getText(1024 * 1024) ?: continue
                    val ours = text.lineSequence().take(12).any { line ->
                        line == "Process: $pkg" || line.startsWith("Package: $pkg ")
                    }
                    if (ours) {
                        dropboxDir.mkdirs()
                        File(dropboxDir, "$tag-$t.txt").writeText(text)
                        val subject = text.lineSequence().firstOrNull { it.startsWith("Subject: ") }
                        life("dropbox", "$tag at ${iso(t / 1000)} ${subject?.take(160) ?: ""}")
                    }
                } finally {
                    entry.close()
                }
            }
        }
        dropboxSince.writeText("$newest\n")
        val saved = dropboxDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        saved.dropLast(DROPBOX_KEEP).forEach { it.delete() }
    }

    private fun runLines(cmd: List<String>, each: (String) -> Unit) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        try {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach(each) }
        } finally {
            p.destroy()
            p.waitFor()
        }
    }

    private fun life(event: String, details: String) {
        val t = now()
        append(lifeLog, "$t ${iso(t)} $event $details\n", LIFE_MAX)
        Log.i(TAG, "$event $details")
    }

    // Appends, first moving the file to <name>.1 once it is over `max` bytes.
    private fun append(f: File, text: String, max: Long) {
        try {
            if (f.length() > max) f.renameTo(File(f.path + ".1"))
            f.appendText(text)
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${f.name}", e)
        }
    }

    // The epoch at the start of the file's last line, 0 when there is none.
    private fun lastEpochIn(f: File): Long {
        if (!f.exists() || f.length() == 0L) return 0L
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val n = minOf(256L, raf.length())
                raf.seek(raf.length() - n)
                val buf = ByteArray(n.toInt())
                raf.readFully(buf)
                String(buf).trimEnd('\n').substringAfterLast('\n').substringBefore(' ').toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
