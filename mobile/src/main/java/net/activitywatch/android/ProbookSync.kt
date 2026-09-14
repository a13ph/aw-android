package net.activitywatch.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.OffsetDateTime
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "ProbookSync"

/**
 * Pushes every ActivityWatch bucket on the phone to probook, through break-relay's
 * `/sync` over the tailnet. The phone's own server stays the first copy: a tap or a
 * heartbeat is stored here whether probook is reachable or not, and this sends it on.
 *
 * Per bucket it keeps, in `files/probook-sync/<bucket>.json`, the fingerprint of every
 * event probook has acknowledged. A run sends the events whose fingerprint changed (new
 * ones, grown heartbeats, a stopwatch replaced with its final duration) and the ids that
 * disappeared, and records only what probook answered for. So a run that fails half-way,
 * or a probook that is off, loses nothing: the next run sends the rest.
 *
 * Runs every 2 min while the screen is on, on every screen-on, from an idle-allowed
 * alarm every 10 min, and when a [ACTION_KICK] broadcast arrives (the break buttons send
 * one after a tap). Does nothing until `files/probook-sync.json` holds `{"url", "key"}`.
 */
class ProbookSync private constructor(private val context: Context) {

    companion object {
        const val ACTION_KICK = "net.activitywatch.android.PROBOOK_SYNC"
        private const val SCREEN_ON_EVERY_S = 120L
        private const val ALARM_EVERY_S = 600L
        private const val KICK_GAP_S = 10L
        private const val FULL_EVERY_S = 6 * 3600L
        private const val RECENT = 2000
        private const val BATCH = 300
        private const val CONNECT_MS = 5000
        private const val READ_MS = 60000
        private const val LOG_MAX = 300_000L
        private val ACKED = setOf("same", "new", "updated", "adopted", "adopted-updated")

        private var instance: ProbookSync? = null

        @Synchronized
        fun start(context: Context) {
            if (instance == null) {
                instance = ProbookSync(context.applicationContext).also { it.begin() }
            }
        }

        fun kick(context: Context) {
            start(context)
            instance?.kick()
        }
    }

    private data class Config(val url: String, val key: String)

    private val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val logFile = File(dir, "aw-probook-sync.log")
    private val statusFile = File(dir, "aw-probook-sync.status")
    private val configFile = File(context.filesDir, "probook-sync.json")
    private val stateDir = File(context.filesDir, "probook-sync")
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    // A field, so the lock is not finalized (and released) while the kick still needs it.
    private val kickWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aw:probook-sync-kick")
        .apply { setReferenceCounted(false) }
    private val thread = HandlerThread("aw-probook-sync", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private var ri: RustInterface? = null
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Touched on the sync thread only.
    private var lastRun = 0L
    private var lastFailure: String? = null
    private val lastRefusal = HashMap<String, String>()

    private val tick = Runnable { runOnce() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            kick()
        }
    }

    private fun begin() {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            stateDir.mkdirs()
            // Only a protected system broadcast matches this filter, so exporting is moot.
            ContextCompat.registerReceiver(
                context, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), null, handler,
                ContextCompat.RECEIVER_EXPORTED
            )
            log("start", "pid=${Process.myPid()} config=${configFile.exists()}")
            runOnce()
        }
    }

    fun kick() {
        // The broadcast that called this returns at once; hold the CPU until the run takes over.
        kickWake.acquire(60_000L)
        handler.post {
            val wait = maxOf(0L, lastRun + KICK_GAP_S - now())
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, wait * 1000)
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun runOnce() {
        handler.removeCallbacks(tick)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aw:probook-sync")
        wake.acquire(5 * 60_000L)
        try {
            lastRun = now()
            val started = SystemClock.elapsedRealtime()
            val cfg = readConfig()
            if (cfg != null) {
                val sent = syncAll(cfg)
                status("ok sent=$sent ms=${SystemClock.elapsedRealtime() - started}")
                if (lastFailure != null) log("recovered", "after: $lastFailure")
                lastFailure = null
                if (sent > 0) log("sent", "$sent event(s)")
            } else {
                status("no config at ${configFile.name}")
            }
        } catch (t: Throwable) {
            val why = t.toString().take(300)
            status("fail $why")
            if (why != lastFailure) log("fail", why)
            lastFailure = why
        } finally {
            if (wake.isHeld) wake.release()
            armAlarm()
            if (power.isInteractive) handler.postDelayed(tick, SCREEN_ON_EVERY_S * 1000)
        }
    }

    private fun readConfig(): Config? {
        if (!configFile.exists()) return null
        val j = JSONObject(configFile.readText())
        val url = j.optString("url")
        val key = j.optString("key")
        return if (url.isNotEmpty() && key.length >= 16) Config(url, key) else null
    }

    private fun syncAll(cfg: Config): Int {
        val rust = ri ?: RustInterface(context).also { ri = it }
        val buckets = JSONObject(rust.getBuckets())
        var sent = 0
        val failed = ArrayList<String>()
        for (id in buckets.keys()) {
            // Throwaway buckets from proof scripts stay on the phone.
            if (id.contains("-test")) continue
            // One bucket failing (probook unreachable, a readback refused) must not
            // keep the buckets after it from syncing; the run still reports it.
            try {
                sent += syncBucket(cfg, rust, id, buckets.getJSONObject(id))
            } catch (e: Exception) {
                failed.add("$id: ${e.toString().take(120)}")
                // probook itself is unreachable: every later bucket would wait out the
                // same connect timeout while holding the wake lock.
                if (isUnreachable(e)) break
            }
        }
        if (failed.isNotEmpty()) throw IOException("sent=$sent, ${failed.size} failed: ${failed.joinToString("; ")}")
        return sent
    }

    private fun isUnreachable(e: Exception): Boolean =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
            e is java.net.SocketTimeoutException || e is java.net.UnknownHostException

    private fun syncBucket(cfg: Config, rust: RustInterface, id: String, meta: JSONObject): Int {
        val stateFile = File(stateDir, "$id.json")
        val state = try {
            JSONObject(stateFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        val acked = state.optJSONObject("acked") ?: JSONObject()
        val full = now() - state.optLong("full", 0L) >= FULL_EVERY_S
        // Newest first by start time; a full pass reads the whole bucket.
        val events = JSONArray(rust.getEvents(id, if (full) -1 else RECENT))
        val complete = full || events.length() < RECENT

        val seen = HashSet<String>()
        var oldest = Long.MAX_VALUE
        val upserts = ArrayList<JSONObject>()
        val fresh = HashMap<String, JSONArray>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val pid = e.getLong("id").toString()
            val timestamp = e.getString("timestamp")
            val start = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
            val duration = e.optDouble("duration", 0.0)
            val data = e.optJSONObject("data") ?: JSONObject()
            if (start < oldest) oldest = start
            seen.add(pid)
            val fp = sha1("$timestamp|$duration|$data")
            if (acked.optJSONArray(pid)?.optString(0) != fp) {
                upserts.add(
                    JSONObject().put("pid", e.getLong("id")).put("timestamp", timestamp)
                        .put("duration", duration).put("data", data)
                )
                fresh[pid] = JSONArray().put(fp).put(start)
            }
        }
        // An acked id missing from the read was deleted on the phone - but when only the
        // newest RECENT were read, only ids newer than the oldest of them can tell.
        val deletes = ArrayList<Long>()
        for (pid in acked.keys()) {
            if (pid in seen) continue
            if (complete || acked.getJSONArray(pid).optLong(1) > oldest) deletes.add(pid.toLong())
        }

        // A bucket with no events yet is still sent once, so probook has it before
        // the first event: the waybar pills and the timeline list buckets, not events.
        val announce = !state.optBoolean("announced")
        if (upserts.isNotEmpty() || deletes.isNotEmpty() || announce) {
            val bucket = JSONObject().put("id", id).put("client", meta.optString("client"))
                .put("type", meta.optString("type")).put("hostname", meta.optString("hostname"))
            upserts.sortBy { OffsetDateTime.parse(it.getString("timestamp")).toInstant() }
            val chunks: List<List<JSONObject>> =
                if (upserts.isEmpty()) listOf(emptyList()) else upserts.chunked(BATCH)
            for ((n, chunk) in chunks.withIndex()) {
                val body = JSONObject().put("bucket", bucket).put("upserts", JSONArray(chunk))
                if (n == 0) body.put("deletes", JSONArray(deletes))
                val reply = post(cfg, id, body) ?: return 0
                val results = reply.getJSONObject("results")
                for (pid in results.keys()) {
                    val st = results.getString(pid)
                    if (st == "deleted") acked.remove(pid)
                    else if (st in ACKED) fresh[pid]?.let { acked.put(pid, it) }
                }
                state.put("acked", acked)
                save(stateFile, state)
            }
            state.put("announced", true)
            save(stateFile, state)
        }
        if (full) {
            state.put("acked", acked).put("full", now())
            save(stateFile, state)
        }
        return upserts.size + deletes.size
    }

    /** The reply, or null when probook refused this bucket (other buckets still go). */
    private fun post(cfg: Config, bucketId: String, body: JSONObject): JSONObject? {
        val conn = URL(cfg.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + cfg.key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 409) {
                if (lastRefusal[bucketId] != text) log("refused", "$bucketId: ${text.take(200)}")
                lastRefusal[bucketId] = text
                return null
            }
            if (code !in 200..299) throw IOException("HTTP $code for $bucketId: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun armAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_KICK).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ALARM_EVERY_S * 1000, pi
        )
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun save(f: File, j: JSONObject) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(f)) throw IOException("could not replace ${f.name}")
    }

    private fun status(text: String) {
        try {
            statusFile.writeText("${isoFormat.format(Date())} $text\n")
        } catch (e: Exception) {
            Log.w(TAG, "status write failed", e)
        }
    }

    private fun log(event: String, details: String) {
        try {
            if (logFile.length() > LOG_MAX) {
                logFile.renameTo(File(dir, "aw-probook-sync.log.1"))
            }
            logFile.appendText("${isoFormat.format(Date())} $event $details\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }
}

/** The alarm, the break buttons and any `am broadcast` start a sync run through this. */
class ProbookSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProbookSync.kick(context)
    }
}
