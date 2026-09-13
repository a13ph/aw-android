package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicReference
import net.activitywatch.android.RustInterface
import org.json.JSONObject

private fun extractTextByViewId(event: AccessibilityEvent, viewId: String): String? {
    event.source?.let { source ->
        val nodes = source.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return processExtractedText(nodes.firstOrNull()?.text?.toString())
        } finally {
            nodes.forEach { it.recycle() }
            source.recycle()
        }
    }
    return null
}

// A browser page fires content-changed events many times a second. Within one window
// the URL is looked up at most this often (trailing, so the last change is still seen);
// a window change is looked up at once.
private const val SAME_WINDOW_INTERVAL_MS = 2_000L
private const val STATS_EVERY_MS = 60_000L
private const val INPUT_METHODS_EVERY_MS = 600_000L

private val FIREFOX_PACKAGES = setOf("org.mozilla.firefox", "org.mozilla.fennec_fdroid")

class WebWatcher : AccessibilityService() {

    // The toolbar is a sibling of the content area, so we search from the window root.
    // findAccessibilityNodeInfosByViewId requires "package:id/name" format and silently
    // rejects bare testTag names, so we traverse manually - breadth-first and capped (see
    // findNode), because the toolbar is shallow and the page below it can be huge.
    // The tab counter sits in the same toolbar and names the browsing mode (see
    // parseTabCounterIncognito); its search runs only once a URL was found.
    private fun extractFirefox(browser: String): FirefoxState? {
        val root = rootInActiveWindow ?: return null
        try {
            val urlBox = findNode(root) { it.viewIdResourceName == "ADDRESSBAR_URL_BOX" }
            val url = parseFirefoxAddressBarContentDescription(urlBox?.contentDescription?.toString())
            if (urlBox !== root) urlBox?.recycle()
            if (url == null) return null
            val labels = tabCounterLabels(browser)
            val counter = findNode(root) {
                parseTabCounterIncognito(it.contentDescription?.toString(), labels) != null
            }
            val counterText = counter?.contentDescription?.toString()
            if (counter !== root) counter?.recycle()
            if (counterText != lastTabCounter) {
                Log.i(TAG, "Tab counter: $counterText")
                lastTabCounter = counterText
            }
            return FirefoxState(url, parseTabCounterIncognito(counterText, labels))
        } finally {
            root.recycle()
        }
    }

    private class FirefoxState(val url: String, val incognito: Boolean?)

    // Worker thread only.
    private var lastTabCounter: String? = null
    private val tabCounterLabelsByBrowser = mutableMapOf<String, TabCounterLabels>()

    // The browser's own tab counter strings in the phone's language, plus the English ones.
    private fun tabCounterLabels(browser: String): TabCounterLabels =
        tabCounterLabelsByBrowser.getOrPut(browser) {
            val own = try {
                val res = packageManager.getResourcesForApplication(browser)
                fun prefix(name: String): String? =
                    res.getIdentifier(name, "string", browser).takeIf { it != 0 }
                        ?.let { tabCounterLabelPrefix(res.getString(it)) }
                TabCounterLabels(
                    listOfNotNull(prefix("mozac_tab_counter_private")),
                    listOfNotNull(prefix("mozac_tab_counter_open_tab_tray"), prefix("mozac_open_tab_counter_tab_tray"))
                )
            } catch (ex: Exception) {
                Log.w(TAG, "No tab counter strings from $browser: ${ex.message}")
                null
            }
            val labels = own?.let { it + ENGLISH_TAB_COUNTER_LABELS } ?: ENGLISH_TAB_COUNTER_LABELS
            Log.i(TAG, "Tab counter labels for $browser: $labels")
            labels
        }

    private val TAG = "WebWatcher"
    private val bucket_id = "aw-watcher-android-web"
    private val lastDiagnosticDump = mutableMapOf<String, Long>()

    private var ri : RustInterface? = null
    // Written on the worker thread, also read on the main thread to pick the throttle.
    @Volatile private var lastWindowId: Int? = null
    private val sessionTracker = BrowserSessionTracker()

    // Lookups walk another app's view tree over binder, so they run on this thread and
    // never on the main thread: a walk on the main thread blocked the app's other
    // components long enough for Android to kill it for an ANR.
    private val worker = HandlerThread("aw-web", Process.THREAD_PRIORITY_BACKGROUND)
    private var workerHandler: Handler? = null
    // Newest event not yet looked up; older ones are dropped, only the latest state matters.
    private val pending = AtomicReference<AccessibilityEvent?>(null)
    @Volatile private var lastLookupAt = 0L
    private val lookup = Runnable { lookUpPending() }

    // Enabled keyboards' packages (see isInputMethodEvent). Read on the worker thread, since
    // the list is a binder call; the main thread only checks membership.
    @Volatile private var inputMethodPackages: Set<String> = emptySet()
    private var inputMethodsReadAt = 0L

    // Written on the worker thread only.
    private var statsSince = 0L
    private var statsLookups = 0
    private var statsMs = 0L
    private var statsMaxMs = 0L

    // Applies stripProtocol uniformly to whatever extractor matched, so the logged url is
    // formatted identically no matter which browser/view-variant produced it.
    private fun extractUrl(packageName: String, event: AccessibilityEvent): String? = when (packageName) {
        "com.android.chrome" -> extractTextByViewId(event, "com.android.chrome:id/url_bar")
        "org.mozilla.firefox", "org.mozilla.fennec_fdroid" ->
            // View-based toolbar (older Firefox versions); the Compose toolbar is extractFirefox
            extractTextByViewId(event, "org.mozilla.firefox:id/url_bar_title")
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        "com.sec.android.app.sbrowser" ->
            extractTextByViewId(event, "com.sec.android.app.sbrowser:id/location_bar_edit_text")
                ?: extractTextByViewId(event, "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text")
        "com.opera.browser" ->
            extractTextByViewId(event, "com.opera.browser:id/url_field")
                ?: extractTextByViewId(event, "com.opera.browser:id/address_field")
        "com.microsoft.emmx" -> extractTextByViewId(event, "com.microsoft.emmx:id/url_bar")
        else -> null
    }?.let(stripProtocol)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating WebWatcher")
        worker.start()
        workerHandler = Handler(worker.looper)
        workerHandler?.post {
            try {
                ri = RustInterface(applicationContext).also { it.createBucketHelper(bucket_id, "web.tab.current") }
            } catch (ex: Throwable) {
                // Catch Throwable (not just Exception) because System.loadLibrary() throws
                // UnsatisfiedLinkError (an Error subclass) when the native library is missing.
                Log.e(TAG, "Failed to initialize RustInterface: ${ex.message}")
            }
            readInputMethods()
        }
    }

    // Worker thread.
    private fun readInputMethods() {
        inputMethodsReadAt = SystemClock.uptimeMillis()
        try {
            val ids = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.id }
            inputMethodPackages = inputMethodPackagesOf(ids.orEmpty())
            Log.i(TAG, "Input methods: $inputMethodPackages")
        } catch (ex: Exception) {
            Log.w(TAG, "Could not list input methods: ${ex.message}")
        }
    }

    override fun onDestroy() {
        workerHandler?.removeCallbacksAndMessages(null)
        worker.quitSafely()
        pending.getAndSet(null)?.recycle()
        super.onDestroy()
    }

    // Main thread: copy the event and hand it to the worker. No binder calls here.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldIgnoreEvent(event)) {
            return
        }
        val handler = workerHandler ?: return
        // The framework recycles `event` when this returns; the copy keeps the source
        // node's ids, so the worker can still fetch it.
        pending.getAndSet(AccessibilityEvent.obtain(event))?.recycle()
        val sameWindow = event.windowId == lastWindowId
        val wait = if (sameWindow) {
            (lastLookupAt + SAME_WINDOW_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        } else {
            0L
        }
        handler.removeCallbacks(lookup)
        handler.postDelayed(lookup, wait)
    }

    private fun lookUpPending() {
        val event = pending.getAndSet(null) ?: return
        val started = SystemClock.uptimeMillis()
        lastLookupAt = started
        // A keyboard installed or enabled since the last read is picked up within this long.
        if (started - inputMethodsReadAt >= INPUT_METHODS_EVERY_MS) readInputMethods()
        try {
            handleEvent(event)
        } catch (ex: Exception) {
            Log.e(TAG, ex.message ?: ex.toString())
        } finally {
            event.recycle()
        }
        recordLookup(SystemClock.uptimeMillis() - started)
    }

    // One info line a minute: how many lookups ran and what they cost.
    private fun recordLookup(ms: Long) {
        val now = SystemClock.uptimeMillis()
        if (statsSince == 0L) statsSince = now
        statsLookups++
        statsMs += ms
        if (ms > statsMaxMs) statsMaxMs = ms
        if (now - statsSince >= STATS_EVERY_MS) {
            Log.i(TAG, "lookups=$statsLookups total_ms=$statsMs max_ms=$statsMaxMs in ${(now - statsSince) / 1000}s")
            statsSince = now
            statsLookups = 0
            statsMs = 0
            statsMaxMs = 0
        }
    }

    // Worker thread.
    private fun handleEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val isKnownBrowser = packageName != null && packageName in KNOWN_BROWSER_PACKAGES

        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        if (!isKnownBrowser) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (windowChanged) {
                Log.i(TAG, "Window changed away from a tracked browser (new package: $packageName); ending session")
                handleUrl(null, newBrowser = null)
            }

            return
        }

        val browser = packageName!!
        val firefox = if (browser in FIREFOX_PACKAGES) extractFirefox(browser) else null
        val newUrl = firefox?.url?.let(stripProtocol) ?: extractUrl(browser, event)

        if (newUrl == null) {
            maybeDumpTree(browser, "URL extraction failed")
        } else {
            if (browser in FIREFOX_PACKAGES && firefox?.incognito == null) {
                maybeDumpTree(browser, "No tab counter found")
            }
            handleUrl(newUrl, newBrowser = browser, incognito = firefox?.incognito)
        }
        if (browser in FIREFOX_PACKAGES) {
            // Firefox's page content is not a descendant of the event source (see
            // findWebView), so the search could only ever walk the page and fail.
            return
        }
        event.source?.let { source ->
            try {
                findWebView(source)?.let { webView ->
                    handleWindowTitle(webView.text.toString())
                    if (webView !== source) webView.recycle()
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun windowChanged(windowId: Int): Boolean = windowId != lastWindowId

    // Dropped before they reach the worker, so they neither end a visit nor count as the
    // window in front for the throttle.
    private fun shouldIgnoreEvent(event: AccessibilityEvent) =
        event.packageName == "com.android.systemui" ||
            isInputMethodEvent(event.packageName, inputMethodPackages)

    // TODO(maintainer): this never finds a match for Firefox, so its page title is never
    // captured (logged events show title:""). Confirmed live on-device (2026-07-01, Fenix,
    // GeckoView content): dumping the full accessibility tree of rootInActiveWindow while a
    // real page was loaded showed `browserLayout` has exactly 3 children - a ComposeView
    // (whose only child had zero further descendants), an unexplained childless node with a
    // null className, and the toolbar (composable_toolbar, which is where ADDRESSBAR_URL_BOX
    // lives - see extractFirefoxUrl above). The page content is not a descendant of this
    // window's root at all, so no restructuring of findWebView's search root will find it.
    // GeckoView most likely exposes its content as a *separate* accessibility window rather
    // than as nodes within this one. Fixing this needs AccessibilityService#getWindows() to
    // be enumerated to find the window that hosts GeckoView's content (if any - it's also
    // possible the title isn't exposed as an accessibility node at all, e.g. only via a
    // window title property), which is a bigger change than this function's shape allows for.
    private fun findWebView(info: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(info) { it.className == "android.webkit.WebView" && it.text != null }

    // Dumps the accessibility tree to logcat at debug level, rate-limited to once per minute
    // per browser. Helps diagnose URL or tab counter extraction failures when adding support
    // for new browsers or browser versions that have changed their view hierarchy. Enable with:
    //   adb shell setprop log.tag.WebWatcher DEBUG
    private fun maybeDumpTree(packageName: String, why: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val now = System.currentTimeMillis()
        if (now - (lastDiagnosticDump[packageName] ?: 0L) < 60_000L) return
        lastDiagnosticDump[packageName] = now
        val root = rootInActiveWindow ?: return
        Log.d(TAG, "$why for $packageName — accessibility tree:")
        try {
            forEachNode(root) { node, depth ->
                val id = node.viewIdResourceName ?: ""
                val cd = node.contentDescription?.toString()?.take(120) ?: ""
                val text = node.text?.toString()?.take(120) ?: ""
                if (id.isNotEmpty() || cd.isNotEmpty() || text.isNotEmpty()) {
                    Log.d(TAG, "${"  ".repeat(depth)}class=${node.className} id=$id cd=\"$cd\" text=\"$text\"")
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleUrl(newUrl : String?, newBrowser: String?, incognito: Boolean? = null) {
        newUrl?.let { Log.i(TAG, "Url: $it, browser: $newBrowser, incognito: $incognito") }
        sessionTracker.handleUrl(newUrl, newBrowser, incognito)?.let { logBrowserEvent(it) }
    }

    private fun handleWindowTitle(newWindowTitle: String) {
        if (sessionTracker.handleWindowTitle(newWindowTitle)) {
            Log.i(TAG, "Title: $newWindowTitle")
        }
    }

    private fun logBrowserEvent(session: CompletedBrowserSession) {
        val data = JSONObject()
            .put("url", session.url)
            .put("browser", session.browser)
            .put("title", session.title)
            .put("audible", false) // TODO
            .put("incognito", session.incognito)

        Log.i(TAG, "Registered event: $data")
        ri?.heartbeatHelper(bucket_id, session.start, session.duration.seconds.toDouble(), data, 1.0)
    }

    override fun onInterrupt() {}

    companion object {
        internal val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.fennec_fdroid",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx"
        )
    }
}
