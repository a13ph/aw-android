package net.activitywatch.android

import android.app.Application
import android.content.Intent
import android.util.Log

private const val TAG = "AWApplication"

class AWApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Android can kill the app (e.g. for a background ANR) and restart the process
        // only to rebind its watcher services, without MainActivity. MainActivity used to
        // be the only thing that started BackgroundService, so the server on :5600 stayed
        // down until the app was opened. Start it with every process instead; a second
        // start is harmless (startServerTask() is a no-op while the server runs).
        try {
            startForegroundService(Intent(this, BackgroundService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses foreground-service starts from the background
            // (ForegroundServiceStartNotAllowedException); MainActivity starts it later.
            Log.w(TAG, "Could not start BackgroundService on process start", e)
        }
    }
}
