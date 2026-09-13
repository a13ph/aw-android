package net.activitywatch.android.watcher

// Firefox (Compose toolbar): URL is in content-desc of ADDRESSBAR_URL_BOX as
// " {url}. Search or enter address". Greedy so we split at the LAST ". " (URLs can
// themselves contain ". "), and we don't assume the hint text starts with an ASCII
// capital letter since it's localized and may start with a non-Latin character.
private val FIREFOX_SUFFIX_PATTERN = Regex("""^\s*(.+)\.\s+\S""")

// Pure parsing logic, unit-testable (mobile/src/test) without any Android/Accessibility
// framework dependency.
internal fun parseFirefoxAddressBarContentDescription(contentDescription: String?): String? =
    contentDescription
        ?.let { FIREFOX_SUFFIX_PATTERN.find(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() && !it.equals("Search or enter address", ignoreCase = true) }

// Firefox's tab counter button names the browsing mode in its content description.
// Fennec 149 (en-US): "Private Tabs Open: 1. Tap to switch tabs." in private mode
// (string mozac_tab_counter_private), "Tabs Open: 3. Tap to switch tabs."
// (mozac_tab_counter_open_tab_tray) or "Non-private Tabs Open: 3. ..."
// (mozac_open_tab_counter_tab_tray) in normal mode. Prefixes are what comes before the
// count, read from the browser's own resources so the phone's language matches.
internal data class TabCounterLabels(
    val privatePrefixes: List<String>,
    val normalPrefixes: List<String>
) {
    operator fun plus(other: TabCounterLabels) = TabCounterLabels(
        (privatePrefixes + other.privatePrefixes).distinct(),
        (normalPrefixes + other.normalPrefixes).distinct()
    )
}

internal val ENGLISH_TAB_COUNTER_LABELS = TabCounterLabels(
    privatePrefixes = listOf("Private Tabs Open: "),
    normalPrefixes = listOf("Tabs Open: ", "Non-private Tabs Open: ")
)

// "Private Tabs Open: %1$s. Tap to switch tabs." -> "Private Tabs Open: ". A format with
// no text before the count (possible in some languages) gives no usable prefix.
internal fun tabCounterLabelPrefix(format: String): String? =
    format.substringBefore("%1\$s", "").takeIf { it.isNotBlank() }

// true for a private-mode tab counter, false for a normal-mode one, null when the
// description is not a tab counter's.
internal fun parseTabCounterIncognito(contentDescription: String?, labels: TabCounterLabels): Boolean? {
    if (contentDescription == null) return null
    if (labels.privatePrefixes.any { contentDescription.startsWith(it) }) return true
    if (labels.normalPrefixes.any { contentDescription.startsWith(it) }) return false
    return null
}

// Strips the URI scheme so the same page is represented identically regardless of which
// browser/UI-variant's view happened to include it (e.g. Samsung Internet's regular vs.
// custom-tab toolbar previously disagreed on this, splitting one continuous visit in two).
internal val stripProtocol: (String) -> String = { url ->
    url.removePrefix("http://").removePrefix("https://")
}

// Pure post-processing of text read off an accessibility node: blank text (e.g. a
// momentarily-cleared address bar) is treated as "no url", not as a real value.
internal fun processExtractedText(rawText: String?): String? =
    rawText?.takeIf { it.isNotBlank() }
