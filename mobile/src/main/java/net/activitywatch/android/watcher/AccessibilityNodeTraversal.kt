package net.activitywatch.android.watcher

import android.view.accessibility.AccessibilityNodeInfo

// Every getChild() can be a binder call into the foreground app, so a walk over a large
// tree (a long page in a browser) costs seconds of CPU. Searches are capped at this many
// fetched nodes.
internal const val DEFAULT_MAX_NODES = 400

// Breadth-first search for the first node (including `node` itself) matching `predicate`,
// fetching at most `maxNodes` descendants. Breadth-first finds shallow chrome such as a
// browser toolbar before descending into the page. Every node fetched and rejected is
// recycled; the match is left un-recycled for the caller (who must not recycle it twice
// when it is `node` itself).
internal fun findNode(
    node: AccessibilityNodeInfo,
    maxNodes: Int = DEFAULT_MAX_NODES,
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    if (predicate(node)) return node
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    var budget = maxNodes
    var found: AccessibilityNodeInfo? = null
    var parent: AccessibilityNodeInfo? = node
    while (parent != null && found == null && budget > 0) {
        val count = parent.childCount
        for (i in 0 until count) {
            if (budget <= 0) break
            budget--
            val child = parent.getChild(i) ?: continue
            if (predicate(child)) {
                found = child
                break
            }
            queue.addLast(child)
        }
        if (parent !== node) parent.recycle()
        parent = queue.removeFirstOrNull()
    }
    if (parent != null && parent !== node) parent.recycle()
    while (queue.isNotEmpty()) queue.removeFirst().recycle()
    return found
}

// Depth-first visit of `node` and its descendants, at most `maxNodes` of them. `node`
// itself is left for the caller to recycle; every descendant is recycled once its own
// subtree has been visited.
internal fun forEachNode(
    node: AccessibilityNodeInfo,
    depth: Int = 0,
    budget: IntArray = intArrayOf(DEFAULT_MAX_NODES * 5),
    visit: (AccessibilityNodeInfo, Int) -> Unit
) {
    visit(node, depth)
    for (i in 0 until node.childCount) {
        if (budget[0] <= 0) return
        budget[0]--
        val child = node.getChild(i) ?: continue
        forEachNode(child, depth + 1, budget, visit)
        child.recycle()
    }
}
