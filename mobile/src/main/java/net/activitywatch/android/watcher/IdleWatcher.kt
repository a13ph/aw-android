package net.activitywatch.android.watcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
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
import java.util.concurrent.ConcurrentLinkedDeque
import net.activitywatch.android.RustInterface
import net.activitywatch.android.deviceHostname
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime

private const val TAG = "aw-idle"

/** What `dumpsys power` says: ms since the last user activity (touch, key), and wakefulness. */
internal data class PowerSample(val lastActivityAgoMs: Long?, val wakefulness: String?)

private val AGO_RE = Regex("""^\s*m?[lL]astUserActivityTime=\d+ \((\d+) ms ago\)""")
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

/** A command the adb shell (uid 2000) ran, seen in logcat: `cmd` is the java command
 * (input, monkey, uiautomator, ...) or "start" for an activity it launched (`app`). */
internal data class ShellCommand(val epochMs: Long, val cmd: String, val app: String?)

private val LOG_RE = Regex("""^\s*(\d+)\.(\d{3})\s+(\S+)\s+\d+\s+\d+\s+\w\s+(\S+?)\s*: (.*)$""")
private val ENTRY_RE = Regex("""^Calling main entry com\.android\.commands\.(\w+)\.""")
private val START_RE = Regex("""^START u\d+ \{.*?\bcmp=([^/ }]+).*\} from uid 2000\b""")

/** Reads one `logcat -v epoch,uid` line; null unless it is a shell command or launch. */
internal fun parseShellLine(line: String): ShellCommand? {
    val m = LOG_RE.find(line) ?: return null
    val (sec, ms, uid, tag, msg) = m.destructured
    val t = sec.toLong() * 1000 + ms.toLong()
    if (tag == "AndroidRuntime" && (uid == "shell" || uid == "2000")) {
        val e = ENTRY_RE.find(msg) ?: return null
        return ShellCommand(t, e.groupValues[1], null)
    }
    if (tag == "ActivityTaskManager" || tag == "ActivityManager") {
        val s = START_RE.find(msg) ?: return null
        return ShellCommand(t, "start", s.groupValues[1])
    }
    return null
}

/** The cc-driving marker (line 1 `<start epoch>`, line 2 `<end epoch>` once closed) with
 * who drove, from cc-driving.who `<start epoch> <session> <agent>`. The who line counts
 * only when its epoch is the marker's start; otherwise both read as "unknown". */
internal data class CcMarker(val start: Long, val end: Long?, val session: String, val agent: String)

private val WS_RE = Regex("""\s+""")

internal fun parseCcMarker(lines: List<String>, who: String?): CcMarker? {
    val start = lines.getOrNull(0)?.trim()?.split(WS_RE)?.getOrNull(0)?.toLongOrNull() ?: return null
    val end = lines.getOrNull(1)?.trim()?.split(WS_RE)?.getOrNull(0)?.toLongOrNull()
    val w = who?.trim()?.split(WS_RE)?.takeIf { it.getOrNull(0)?.toLongOrNull() == start }
    return CcMarker(start, end, w?.getOrNull(1) ?: "unknown", w?.getOrNull(2) ?: "unknown")
}

/** Who made a touch: "cc" for a driver's inside its window, "adb" for an injected one
 * outside any window, null for the user's own finger. A touch is injected when an input
 * or monkey run started within [-1, +injectS] s of it. Inside a window a touch with no
 * run near it is the user's finger - the user can take the phone mid-window - but only
 * while logcat is being followed (`shellLive`); without it the window's claim stands. */
internal fun classifyTouch(
    touch: Long, winStart: Long, winEnd: Long?, injections: Iterable<Long>, shellLive: Boolean,
    injectS: Long
): String? {
    val injected = injections.any { touch >= it - 1 && touch <= it + injectS }
    val inWindow = winStart > 0 && touch >= winStart - 1 && (winEnd == null || touch <= winEnd + 1)
    return when {
        inWindow && (injected || !shellLive) -> "cc"
        injected -> "adb"
        else -> null
    }
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
 * - cc-driving: while it exists (line 1 = start epoch, line 2 = end epoch once closed;
 *   cc-driving.who = `<start epoch> <session> <agent>` names the driver), touches near an
 *   input run are the driver's, and others the user's finger ([classifyTouch]): logged
 *   with "cc <session short>", and the window recorded as a span
 *   `{by: cc, session, session_short, agent}` in `aw-watcher-cc-phone_<host>`;
 *   cc-driving.log is the ledger of closed windows, `<start> <end> <session> <agent>`.
 * - aw-idle-shell.log: `<epoch.ms> <cmd> [app]` for every command the adb shell ran,
 *   followed live from logcat (needs READ_LOGS). Each is also a span
 *   `{by: adb, cmd[, app], session, session_short, agent}` in `aw-watcher-adb_<host>`,
 *   the session and agent being the open window's, or "unknown" outside one (its own
 *   bucket: heartbeats
 *   merge only into the latest event, so mixing it with the windows would split them),
 *   and a touch within [INJECT_S] s of an `input`, `monkey` or `bu` run is injected: logged
 *   with "adb".
 *
 * Touches logged "cc" or "adb" never make the user not-afk; the afk bucket holds only
 * the user's own state.
 *
 * `aw-watcher-screen_<host>` (type screen-state) holds the screen's state as spans
 * `{screen: on|off}`, one per state, so its latest event is the state now and its
 * timestamp is since when. A change at t first extends the old state's span to t, then
 * starts the new one at t; the on span grows with every tick. At start the current state
 * is written with pulsetime 0, so a span never bridges time the app did not see.
 *
 * `aw-watcher-power_<host>` (type power-state) holds the charger the same way, as spans
 * `{plugged: ac|usb|wireless|dock|none}`, from EXTRA_PLUGGED of the sticky
 * ACTION_BATTERY_CHANGED, read on every battery change and on POWER_CONNECTED /
 * POWER_DISCONNECTED: a plug-in at the bedside charger is a bedtime signal. The open span
 * grows with each battery change of the same state.
 */
class IdleWatcher private constructor(private val context: Context) {

    companion object {
        private const val INTERVAL_S = 5L
        private const val THRESH_S = 120L
        private const val ALIVE_S = 120L
        private const val NO_DUMP_RETRY_S = 60L
        private const val CC_STALE_S = 7200L
        private const val INJECT_S = 5L
        private const val SHELL_SPAN_S = 3.0
        private const val SHELL_PULSE_S = 10.0
        // a screen span may be extended over a whole off period, days long
        private const val SCREEN_PULSE_S = 14.0 * 86_400
        // bu (adb backup) is followed by a touch within a second, too soon for a finger
        private val INJECTORS = setOf("input", "monkey", "bu")
        private const val SHELL_FILTER =
            "Calling main entry com\\.android\\.commands\\.|START u[0-9]+ .* from uid 2000"
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
    private val sentLog = File(dir, "cc-sent.log")
    private var sentPos = 0L
    private val ccFlag = File(dir, "cc-driving")
    private val ccWho = File(dir, "cc-driving.who")
    private val ccLedger = File(dir, "cc-driving.log")
    private val shellLog = File(dir, "aw-idle-shell.log")
    private val shellSince = File(dir, "aw-idle-shell.since")

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val host = deviceHostname(context)
    private val bucket = "aw-watcher-afk_$host"
    private val ccBucket = "aw-watcher-cc-phone_$host"
    private val adbBucket = "aw-watcher-adb_$host"
    private val screenBucket = "aw-watcher-screen_$host"
    private val powerBucket = "aw-watcher-power_$host"
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
    private var screen: String? = null
    private var plugged: String? = null

    // Epoch s of recent input/monkey runs. Written by the logcat thread the moment it
    // reads the line, which comes before the injection, so a tick sees it in time.
    private val injections = ConcurrentLinkedDeque<Long>()
    @Volatile private var shellThread: Thread? = null

    private val tick = Runnable { safeTick() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            life(if (intent.action == Intent.ACTION_SCREEN_ON) "screen-on" else "screen-off", "")
            handler.removeCallbacks(tick)
            safeTick()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            try {
                val battery = if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent
                else context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                powerBeat(now(), pluggedName(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1))
            } catch (t: Throwable) {
                life("error", "power: ${t.toString().take(300)}")
            }
        }
    }

    private fun pluggedName(p: Int): String = when (p) {
        0 -> "none"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        8 -> "dock" // BATTERY_PLUGGED_DOCK, API 33
        -1 -> "unknown"
        else -> "other-$p"
    }

    // Writes the charger's state at `t` into the power bucket (class comment).
    private fun powerBeat(t: Long, cur: String) {
        val prev = plugged
        if (prev == null) {
            post(powerBucket, t, 0.0, JSONObject().put("plugged", cur), 0.0)
        } else {
            if (prev != cur) post(powerBucket, t, 0.0, JSONObject().put("plugged", prev), SCREEN_PULSE_S)
            post(powerBucket, t, 0.0, JSONObject().put("plugged", cur), SCREEN_PULSE_S)
        }
        if (prev != cur) life("power-state", cur)
        plugged = cur
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
            // Sticky: the current state arrives at once, and is written with pulsetime 0.
            val powerFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(
                context, powerReceiver, powerFilter, null, handler, ContextCompat.RECEIVER_EXPORTED
            )
            ensureShellWatch()
            safeTick()
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun iso(epoch: Long): String = isoFormat.format(Date(epoch * 1000))

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // Writes the screen's state at `t` into the screen bucket (class comment).
    private fun screenBeat(t: Long, on: Boolean) {
        val cur = if (on) "on" else "off"
        val prev = screen
        if (prev == null) {
            post(screenBucket, t, 0.0, JSONObject().put("screen", cur), 0.0)
        } else {
            if (prev != cur) post(screenBucket, t, 0.0, JSONObject().put("screen", prev), SCREEN_PULSE_S)
            post(screenBucket, t, 0.0, JSONObject().put("screen", cur), SCREEN_PULSE_S)
        }
        if (prev != cur) life("screen-state", cur)
        screen = cur
    }

    private fun safeTick() {
        var next = INTERVAL_S
        try {
            screenBeat(now(), power.isInteractive)
        } catch (t: Throwable) {
            life("error", "screen: ${t.toString().take(300)}")
        }
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
        ensureShellWatch()
        if (!granted(Manifest.permission.DUMP)) {
            if (dumpOk != false) life("no-dump", "android.permission.DUMP not granted (pm grant)")
            dumpOk = false
            return NO_DUMP_RETRY_S
        }

        var ccS = 0L
        var ccE: Long? = null
        val marker = readMarker()
        val ccData = whoData(JSONObject().put("by", "cc"), marker)
        if (marker != null) {
            ccS = marker.start
            ccE = marker.end
            if (ccE == null && ccS > 0 && now - ccS > CC_STALE_S) {
                life("cc-stale", "window opened $ccS never closed - dropped")
                ccFlag.delete()
                ccS = 0L
            }
        }
        val driving = ccS > 0 && ccE == null
        if (driving) {
            if (ccSeen != ccS) {
                ccBeat(ccS, ccData)
                ccSeen = ccS
            }
            ccBeat(now, ccData)
        } else if (ccS > 0 && ccE != null && ccDone != ccS) {
            if (ccSeen == ccS) {
                ccBeat(ccE, ccData)   // extends the span the live beats built
            } else {                  // opened and closed between two looks: one event
                post(ccBucket, ccS, (ccE - ccS).toDouble(), ccData, 0.0)
            }
            ccDone = ccS
            append(ccLedger, "$ccS $ccE ${marker?.session} ${marker?.agent}\n", LIFE_MAX)
            life("cc-window", "${iso(ccS)} to ${iso(ccE)}")
        }

        val sample = samplePower()
        val ago = sample.lastActivityAgoMs
        if (ago == null) {
            if (dumpOk != false) life("no-dump", "dumpsys power gave no (m)LastUserActivityTime")
            dumpOk = false
            return INTERVAL_S
        }
        if (dumpOk == false) life("dump-ok", "")
        dumpOk = true
        val wake = sample.wakefulness ?: ""
        val touch = now - ago / 1000
        readSentLog()
        while ((injections.peekFirst() ?: now) < now - 600) injections.pollFirst()
        val by = classifyTouch(touch, ccS, ccE, injections, shellThread?.isAlive == true, INJECT_S)
        if (by == null) alTouch = touch
        // a closed window is dropped once the user touches again or his idle is decided
        if (ccE != null && (by == null || now - ccE > THRESH_S)) ccFlag.delete()
        if (touch > lastLogged + 1) {
            val tag = if (by == "cc") "cc ${ccData.optString("session_short")}" else by ?: ""
            append(tapsLog, "$touch $wake $tag\n", TAPS_MAX)
            lastLogged = touch
        }
        val newState = if (by == null && wake == "Awake" && ago / 1000 < THRESH_S) "not-afk" else "afk"
        if (newState != state) {
            if (newState == "afk") {
                // idle began at the user's last touch: one event covering [then, now];
                // the heartbeats below extend it. After a restart the bucket may
                // already hold part of this idle span, left by the previous process,
                // so the new span starts where that one ends: it then merges into it
                // or continues it, never lying over it. (Merging into "the latest
                // event" is not enough: an afk span and the not-afk span before it
                // start at the same touch, and either can come back as the latest.)
                val from = if (alTouch > 0) alTouch else touch
                val start = (afkCoveredUntil(from) ?: from).coerceAtMost(now)
                post(bucket, start, (now - start).toDouble(), JSONObject().put("status", "afk"), (INTERVAL_S * 3).toDouble())
            } else {
                // stretch the idle span up to the touch that ended it (the screen may
                // have been off for hours), then start use at that touch
                if (state == "afk" && touch > lastBeat) beat(touch, "afk")
                beat(touch, "not-afk")
            }
        }
        beat(now, newState)
        state = newState
        return INTERVAL_S
    }

    // New lines of cc-sent.log since the last tick: the step script appends
    // `<epoch> <session> <agent> <kind> <cmd>` before each command it sends. A tap,
    // key or `input`/`monkey` run there is an injection even when logcat shows nothing,
    // which on Android 15 is every `input` call (`/system/bin/input` = `cmd input`).
    private fun readSentLog() {
        if (!sentLog.exists()) return
        val len = sentLog.length()
        if (len < sentPos) sentPos = 0
        if (len == sentPos) return
        try {
            RandomAccessFile(sentLog, "r").use { f ->
                f.seek(sentPos)
                val buf = ByteArray((len - sentPos).toInt())
                f.readFully(buf)
                sentPos = len
                for (line in String(buf).lines()) {
                    val p = line.trim().split(Regex("\\s+"), limit = 5)
                    if (p.size < 5) continue
                    val epoch = p[0].toLongOrNull() ?: continue
                    val kind = p[3]
                    val cmd = p[4]
                    if (kind == "tap" || kind == "key" || (kind == "run" && INJECTORS.any { cmd.startsWith(it) })) {
                        if (injections.peekLast() != epoch) injections.addLast(epoch)
                    }
                }
            }
        } catch (t: Throwable) {
            life("error", "sent log: $t")
        }
    }

    // Follows logcat for what the adb shell (uid 2000) runs: java commands such as
    // input, monkey and uiautomator, and activities it launches. The tick restarts it
    // when it has died, from the last line it handled.
    private fun ensureShellWatch() {
        if (shellThread?.isAlive == true) return
        if (!granted(Manifest.permission.READ_LOGS)) return
        val since = shellSince.readLongOrNull() ?: (System.currentTimeMillis() - 60_000)
        shellThread = Thread({ followShell(since) }, "aw-idle-shell").apply {
            isDaemon = true
            start()
        }
    }

    private fun followShell(since: Long) {
        try {
            val from = String.format(Locale.US, "%d.%03d", since / 1000, since % 1000)
            val p = ProcessBuilder(
                "logcat", "-b", "main,system", "-v", "epoch,uid", "-T", from, "-e", SHELL_FILTER
            ).redirectErrorStream(true).start()
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val c = parseShellLine(line)
                    if (c != null && c.epochMs > since) {
                        if (c.cmd in INJECTORS) injections.addLast(c.epochMs / 1000)
                        handler.post { onShell(c) }
                    }
                }
            } finally {
                p.destroy()
            }
            handler.post { life("shell-watch-end", "logcat exited") }
        } catch (t: Throwable) {
            handler.post { life("error", "shell watch: $t") }
        }
    }

    private fun onShell(c: ShellCommand) {
        val t = c.epochMs / 1000
        val stamp = String.format(Locale.US, "%d.%03d", t, c.epochMs % 1000)
        append(shellLog, "$stamp ${c.cmd} ${c.app ?: ""}\n", LIFE_MAX)
        try { shellSince.writeText("${c.epochMs}\n") } catch (e: Exception) { }
        val data = JSONObject().put("by", "adb").put("cmd", c.cmd)
        if (c.app != null) data.put("app", c.app)
        // read the marker now, not the tick's copy: a window opened a second ago counts
        val m = readMarker()?.takeIf { t >= it.start - 1 && (it.end == null || t <= it.end + 1) }
        post(adbBucket, t, SHELL_SPAN_S, whoData(data, m), SHELL_PULSE_S)
    }

    private fun readMarker(): CcMarker? =
        if (!ccFlag.exists()) null
        else try {
            val who = if (ccWho.exists()) ccWho.readText() else null
            parseCcMarker(ccFlag.readLines(), who)
        } catch (e: Exception) { null }

    // Adds who drove: the window's session and agent, or "unknown" with no window.
    private fun whoData(data: JSONObject, m: CcMarker?): JSONObject {
        val session = m?.session ?: "unknown"
        return data.put("session", session)
            .put("session_short", if (session == "unknown") session else session.take(8))
            .put("agent", m?.agent ?: "unknown")
    }

    // End (epoch s) of the newest-ending plain afk event among the bucket's recent
    // ones, when it ends after `from`; else null. In a normal not-afk -> afk change
    // every afk event ended before the last touch, so this only fires after a restart.
    private fun afkCoveredUntil(from: Long): Long? {
        val rust = ri ?: return null
        return try {
            val events = rust.getEventsJSON(bucket, 10)
            var latestEnd: Long? = null
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val data = e.getJSONObject("data")
                if (data.length() != 1 || data.optString("status") != "afk") continue
                val start = OffsetDateTime.parse(e.getString("timestamp")).toEpochSecond()
                val end = start + e.getDouble("duration").toLong()
                if (latestEnd == null || end > latestEnd) latestEnd = end
            }
            latestEnd?.takeIf { it > from }
        } catch (ex: Exception) {
            Log.w(TAG, "could not read the recent afk events", ex)
            null
        }
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

    private fun beat(t: Long, status: String) {
        val data = JSONObject().put("status", status)
        post(bucket, t, 0.0, data, pulseFor(t))
        if (t > lastBeat) lastBeat = t
    }

    private fun ccBeat(t: Long, data: JSONObject) {
        post(ccBucket, t, 0.0, data, (INTERVAL_S * 3).toDouble())
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
            ri?.createBucketHelper(adbBucket, "adb-shell", CLIENT)
            ri?.createBucketHelper(screenBucket, "screen-state", CLIENT)
            ri?.createBucketHelper(powerBucket, "power-state", CLIENT)
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
