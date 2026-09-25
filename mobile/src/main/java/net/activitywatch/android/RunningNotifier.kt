package net.activitywatch.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RunningNotifier"

/**
 * An ongoing notification listing what al's tracking buttons have running and since
 * when ([RunningSpans]), cleared when nothing runs. Refreshed when the app starts, on
 * every [ProbookSync.ACTION_KICK] broadcast (each button sends one after its write), on
 * screen-on, and every [EVERY_S] while the screen is on (a break part lapses at the
 * fold's 2 h cap with no press).
 *
 * The same lines go to `aw-running.status` in the external files dir, so a check over
 * adb reads what the notification says.
 */
class RunningNotifier private constructor(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "aw_running_spans"
        private const val NOTIFICATION_ID = 2
        private const val EVERY_S = 60L
        private const val LIMIT = 100

        private var instance: RunningNotifier? = null

        @Synchronized
        fun start(context: Context) {
            if (instance == null) {
                instance = RunningNotifier(context.applicationContext).also { it.begin() }
            }
        }

        fun refresh(context: Context) {
            start(context)
            instance?.refresh()
        }
    }

    private val thread = HandlerThread("aw-running", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private var ri: RustInterface? = null
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val statusFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "aw-running.status")
    private val hhmm = SimpleDateFormat("HH:mm", Locale.US)
    private var shown = ""

    private val tick = Runnable { update() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            refresh()
        }
    }

    private fun begin() {
        thread.start()
        handler = Handler(thread.looper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Running spans", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "What the tracking buttons have running, and since when"
                    setShowBadge(false)
                }
            )
        }
        ContextCompat.registerReceiver(
            context, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), null, null,
            ContextCompat.RECEIVER_EXPORTED
        )
        refresh()
    }

    fun refresh() {
        handler.post { update() }
    }

    private fun update() {
        handler.removeCallbacks(tick)
        try {
            val rust = ri ?: RustInterface(context).also { ri = it }
            val ids = JSONObject(rust.getBuckets()).keys().asSequence().toList()
            val items = RunningSpans.collect(ids, { rust.getEventsJSON(it, LIMIT) }, System.currentTimeMillis())
            show(items)
        } catch (t: Throwable) {
            Log.w(TAG, "update failed", t)
            writeStatus("fail ${t.toString().take(200)}")
        } finally {
            if (power.isInteractive) handler.postDelayed(tick, EVERY_S * 1000)
        }
    }

    private fun line(i: RunningSpans.Item): String = "${i.label} since ${hhmm.format(Date(i.startMs))}"

    private fun show(items: List<RunningSpans.Item>) {
        val lines = items.map(::line)
        val key = lines.joinToString("\n")
        writeStatus(if (lines.isEmpty()) "none" else key)
        if (key == shown) return
        shown = key
        if (items.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (items.size == 1) "running: ${items[0].label}" else "running: ${items.size}"
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setWhen(items.last().startMs)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun writeStatus(text: String) {
        try {
            statusFile.writeText("${System.currentTimeMillis() / 1000} $text\n")
        } catch (e: Exception) {
            Log.w(TAG, "status write failed", e)
        }
    }
}
