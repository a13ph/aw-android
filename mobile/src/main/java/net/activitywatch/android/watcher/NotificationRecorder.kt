package net.activitywatch.android.watcher

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import net.activitywatch.android.RustInterface
import net.activitywatch.android.deviceHostname
import org.json.JSONObject
import org.threeten.bp.Instant
import java.io.File

/**
 * Records every posted notification as one event in `aw-watcher-notifications_<host>`,
 * fed by MediaWatcher's NotificationListenerService callbacks.
 *
 * Event data: package, app, title, category, channel, ongoing, post time (as the
 * event timestamp). The body text is NOT stored unless STORE_BODY is true: it can hold
 * 2FA codes and message content.
 *
 * An update to a notification already recorded (a progress bar, a ticking timer) is
 * skipped unless its title, category or ongoing flag changed. Group summaries are skipped:
 * they repeat their children.
 *
 * While the file `notif-test` exists in the external files dir, events go to
 * `aw-watcher-notifications-test_<host>` instead, which ProbookSync skips.
 */
class NotificationRecorder(private val context: Context) {

    companion object {
        private const val TAG = "NotificationRecorder"
        private const val BUCKET_TYPE = "notification"
        private const val CLIENT = "aw-android-notifications"
        private const val STORE_BODY = false
    }

    private val host = deviceHostname(context)
    private val bucket = "aw-watcher-notifications_$host"
    private val testBucket = "aw-watcher-notifications-test_$host"
    private val testMarker = File(context.getExternalFilesDir(null) ?: context.filesDir, "notif-test")
    private val created = HashSet<String>()
    private val lastByKey = HashMap<String, String>()
    private val labels = HashMap<String, String>()

    /** Call on a background thread: RustInterface blocks on JNI. */
    fun posted(ri: RustInterface, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val ongoing = sbn.isOngoing
        val category = n.category ?: ""

        val sig = "$title|$category|$ongoing"
        if (lastByKey[sbn.key] == sig) return
        lastByKey[sbn.key] = sig

        val data = JSONObject().apply {
            put("package", sbn.packageName)
            put("app", label(sbn.packageName))
            put("title", title)
            put("category", category)
            put("channel", n.channelId ?: "")
            put("ongoing", ongoing)
            if (STORE_BODY) put("text", extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
        }
        val b = if (testMarker.exists()) testBucket else bucket
        try {
            if (b !in created) {
                ri.createBucketHelper(b, BUCKET_TYPE, CLIENT)
                created.add(b)
            }
            ri.insertEvent(b, Instant.ofEpochMilli(sbn.postTime), 0.0, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record notification from ${sbn.packageName}", e)
        }
    }

    fun removed(sbn: StatusBarNotification) {
        lastByKey.remove(sbn.key)
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
