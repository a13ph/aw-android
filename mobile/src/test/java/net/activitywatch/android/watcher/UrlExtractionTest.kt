package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractionTest {

    @Test
    fun `extracts url from typical content description`() {
        val cd = " example.com/page. Search or enter address"
        assertEquals("example.com/page", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `returns null for empty address bar placeholder`() {
        val cd = ". Search or enter address"
        assertNull(parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `returns null for null content description`() {
        assertNull(parseFirefoxAddressBarContentDescription(null))
    }

    @Test
    fun `returns null when content description has no separator at all`() {
        assertNull(parseFirefoxAddressBarContentDescription("Search or enter address"))
    }

    @Test
    fun `does not truncate a url that itself contains a period-space sequence`() {
        // Regression test: a non-greedy regex would previously split at the FIRST ". "
        // it found, truncating urls whose own content contains ". ".
        val cd = " example.com/search?q=World. Cup. Search or enter address"
        assertEquals("example.com/search?q=World. Cup", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `does not require the hint text to start with an ascii capital letter`() {
        // Regression test: locales where the hint text isn't Latin-script (or doesn't
        // capitalize) must not disable Firefox tracking entirely.
        val cd = " example.com. 搜索或输入网址"
        assertEquals("example.com", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `rejects a bare placeholder that parses as the hint text itself`() {
        assertNull(parseFirefoxAddressBarContentDescription("Search or enter address. Search or enter address"))
    }

    @Test
    fun `private tab counter is incognito`() {
        assertEquals(true, parseTabCounterIncognito("Private Tabs Open: 1. Tap to switch tabs.", ENGLISH_TAB_COUNTER_LABELS))
    }

    @Test
    fun `normal and non-private tab counters are not incognito`() {
        assertEquals(false, parseTabCounterIncognito("Tabs Open: 12. Tap to switch tabs.", ENGLISH_TAB_COUNTER_LABELS))
        assertEquals(false, parseTabCounterIncognito("Non-private Tabs Open: 3. Tap to switch tabs.", ENGLISH_TAB_COUNTER_LABELS))
    }

    @Test
    fun `other descriptions say nothing about the mode`() {
        assertNull(parseTabCounterIncognito(null, ENGLISH_TAB_COUNTER_LABELS))
        assertNull(parseTabCounterIncognito("The tab counter toolbar button.", ENGLISH_TAB_COUNTER_LABELS))
        assertNull(parseTabCounterIncognito(" example.com. Search or enter address", ENGLISH_TAB_COUNTER_LABELS))
    }

    @Test
    fun `label prefix is the text before the count`() {
        assertEquals("Private Tabs Open: ", tabCounterLabelPrefix("Private Tabs Open: %1\$s. Tap to switch tabs."))
        assertNull(tabCounterLabelPrefix("%1\$s open tabs"))
        assertNull(tabCounterLabelPrefix("no count here"))
    }

    @Test
    fun `localized labels are matched alongside the english ones`() {
        val labels = ENGLISH_TAB_COUNTER_LABELS + TabCounterLabels(listOf("Private Tabs geöffnet: "), listOf("Tabs geöffnet: "))
        assertEquals(true, parseTabCounterIncognito("Private Tabs geöffnet: 2", labels))
        assertEquals(false, parseTabCounterIncognito("Tabs geöffnet: 2", labels))
    }

    @Test
    fun `processExtractedText filters blank text`() {
        assertNull(processExtractedText(""))
        assertNull(processExtractedText("   "))
        assertNull(processExtractedText(null))
        assertEquals("example.com", processExtractedText("example.com"))
    }

    @Test
    fun `stripProtocol removes http and https prefixes only`() {
        assertEquals("example.com", stripProtocol("http://example.com"))
        assertEquals("example.com", stripProtocol("https://example.com"))
        assertEquals("example.com", stripProtocol("example.com"))
    }
}
