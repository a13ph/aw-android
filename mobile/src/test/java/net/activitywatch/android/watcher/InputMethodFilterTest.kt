package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodFilterTest {

    private val gboard = setOf("com.google.android.inputmethod.latin")

    @Test
    fun `an event from an enabled keyboard is an input method event`() {
        assertTrue(isInputMethodEvent("com.google.android.inputmethod.latin", gboard))
    }

    @Test
    fun `browser, launcher and null packages are not input method events`() {
        assertFalse(isInputMethodEvent("org.mozilla.fennec_fdroid", gboard))
        assertFalse(isInputMethodEvent("net.oneplus.launcher", gboard))
        assertFalse(isInputMethodEvent(null, gboard))
    }

    @Test
    fun `no known keyboards means no event is filtered`() {
        assertFalse(isInputMethodEvent("com.google.android.inputmethod.latin", emptySet()))
    }

    @Test
    fun `input method ids map to their packages`() {
        assertEquals(
            setOf("com.google.android.inputmethod.latin", "org.futo.inputmethod.latin"),
            inputMethodPackagesOf(listOf(
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
                "org.futo.inputmethod.latin/.LatinIME",
                "com.google.android.inputmethod.latin/.Other",
                ""
            ))
        )
    }

    @Test
    fun `keyboard events in the middle of a visit do not split it`() {
        // Mirrors WebWatcher: an input method event is dropped before it reaches the
        // tracker, so the browser's next lookup continues the same visit.
        val tracker = BrowserSessionTracker()
        val events = listOf(
            "org.mozilla.fennec_fdroid" to "duckduckgo.com",
            "com.google.android.inputmethod.latin" to null,
            "org.mozilla.fennec_fdroid" to "duckduckgo.com"
        )
        val completed = events
            .filterNot { (pkg, _) -> isInputMethodEvent(pkg, gboard) }
            .mapNotNull { (pkg, url) -> tracker.handleUrl(url, pkg, incognito = true) }

        assertTrue(completed.isEmpty())
    }
}
