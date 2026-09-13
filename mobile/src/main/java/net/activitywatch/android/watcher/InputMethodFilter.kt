package net.activitywatch.android.watcher

// A keyboard is its own accessibility window with its own package. When it pops up over
// a browser's address bar or a page's text field, its events look like the user left the
// browser, which ended the visit and split it: a DuckDuckGo search in Fennec was stored as
// a 0.1 s visit (measured 2026-09-13). The browser is still in front under the keyboard,
// so events from an input method are not a window change at all.
internal fun isInputMethodEvent(packageName: CharSequence?, inputMethodPackages: Set<String>): Boolean =
    packageName != null && packageName.toString() in inputMethodPackages

// The package names behind a list of input method ids ("com.example.ime/.Service").
internal fun inputMethodPackagesOf(inputMethodIds: Iterable<String>): Set<String> =
    inputMethodIds.mapNotNull { id -> id.substringBefore('/').takeIf { it.isNotBlank() } }.toSet()
