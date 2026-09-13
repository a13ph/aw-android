package net.activitywatch.android.watcher

import org.threeten.bp.Duration
import org.threeten.bp.Instant

internal data class CompletedBrowserSession(
    val url: String,
    val browser: String,
    val title: String,
    val start: Instant,
    val duration: Duration,
    val incognito: Boolean = false
)

// Pure session/state-machine logic extracted out of WebWatcher so it can be unit-tested
// (mobile/src/test) without an AccessibilityService or device/emulator.
internal class BrowserSessionTracker(
    private val now: () -> Instant = { Instant.ofEpochMilli(System.currentTimeMillis()) }
) {
    private var lastUrlTimestamp: Instant? = null
    private var lastUrl: String? = null
    private var lastBrowser: String? = null
    private var lastWindowTitle: String? = null
    private var lastIncognito = false

    // Returns the just-completed session (previous url/browser/title) when the url,
    // browser or private mode changes, so the caller can log it. We wait for the url to
    // change before logging so we have a chance to receive the page title, which often
    // only arrives after the page loads and/or the user interacts with it.
    // `incognito` null means the lookup could not tell: the mode last seen in the same
    // browser is kept, since the mode does not change without the browser in front.
    fun handleUrl(newUrl: String?, newBrowser: String?, incognito: Boolean? = null): CompletedBrowserSession? {
        val newIncognito = incognito ?: (lastIncognito && newBrowser == lastBrowser)
        if (newUrl == lastUrl && newBrowser == lastBrowser && newIncognito == lastIncognito) return null

        val completed = lastUrl?.let { url ->
            lastBrowser?.let { browser ->
                val start = lastUrlTimestamp!!
                CompletedBrowserSession(
                    url = url,
                    browser = browser,
                    title = lastWindowTitle ?: "",
                    start = start,
                    // Clock can step backward (NTP sync, manual change) between `start` and now;
                    // don't report a negative duration in that case.
                    duration = Duration.between(start, now()).coerceAtLeast(Duration.ZERO),
                    incognito = lastIncognito
                )
            }
        }

        lastUrlTimestamp = now()
        lastUrl = newUrl
        lastBrowser = newBrowser
        lastWindowTitle = null
        lastIncognito = newIncognito
        return completed
    }

    // Returns true when the title actually changed (so the caller can log it).
    fun handleWindowTitle(newWindowTitle: String): Boolean {
        if (newWindowTitle == lastWindowTitle) return false
        lastWindowTitle = newWindowTitle
        return true
    }
}
