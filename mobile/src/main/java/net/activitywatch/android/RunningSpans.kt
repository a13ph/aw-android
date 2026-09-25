package net.activitywatch.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * What al's tracking buttons have running right now, read from this phone's own buckets.
 * Pure logic, no Android: [RunningNotifier] feeds it the buckets and shows the result.
 *
 * - span buckets (`aw-watcher-sleep_*`, `aw-watcher-activity_*`, the app's `aw-stopwatch`):
 *   an event with `data.running == true` runs, since its timestamp
 * - the break-parts bucket: the part runs while the newest start/end/stop mark is a
 *   `start` younger than [PART_CAP_MS] (the fold's cap); an overlay (`+cu talk`) runs
 *   while its newest overlay-start/overlay-end mark is an `overlay-start` younger than
 *   [OVERLAY_CAP_MS]; one [PillMirror] echoed from a probook pill is labelled «(pc)»
 * - probook's mirrored buckets (`*_probook-nix`) are never read; a `-test` bucket is read
 *   and its items are marked `(test)`, so a test build of the buttons shows here too
 */
object RunningSpans {
    const val PART_CAP_MS = 2 * 3600 * 1000L
    const val OVERLAY_CAP_MS = 12 * 3600 * 1000L
    private const val PULLED_SUFFIX = "_probook-nix"
    private val SPAN_PREFIXES = listOf("aw-watcher-sleep", "aw-watcher-activity", "aw-stopwatch")
    private const val PARTS_PREFIX = "aw-watcher-break-parts"

    data class Item(val label: String, val startMs: Long, val kind: String)

    fun isSpanBucket(id: String): Boolean =
        !id.endsWith(PULLED_SUFFIX) && SPAN_PREFIXES.any { id == it || id.startsWith("$it-") || id.startsWith("${it}_") }

    fun isPartsBucket(id: String): Boolean =
        !id.endsWith(PULLED_SUFFIX) && (id.startsWith("${PARTS_PREFIX}_") || id.startsWith("$PARTS_PREFIX-"))

    private fun testMark(bucketId: String) = if (bucketId.contains("-test")) " (test)" else ""

    fun parseMs(ts: String): Long? =
        try {
            OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }

    private fun objects(events: JSONArray): List<JSONObject> =
        (0 until events.length()).mapNotNull { events.optJSONObject(it) }

    /** Running events of a span bucket. */
    fun fromSpans(bucketId: String, events: JSONArray): List<Item> =
        objects(events).mapNotNull { e ->
            val d = e.optJSONObject("data") ?: return@mapNotNull null
            if (!d.optBoolean("running", false)) return@mapNotNull null
            val start = parseMs(e.optString("timestamp")) ?: return@mapNotNull null
            val name = listOf("stage", "activity", "label")
                .map { d.optString(it, "") }.firstOrNull { it.isNotEmpty() } ?: "?"
            Item(name.replace('-', ' ') + testMark(bucketId), start, "span")
        }

    /** The running break part and the open overlays of the break-parts bucket. */
    fun fromParts(bucketId: String, events: JSONArray, nowMs: Long): List<Item> {
        // a mark PillMirror echoed from probook's pill says so: «(pc)»
        data class M(val t: Long, val mark: String, val reason: String, val pc: String)
        val marks = objects(events).mapNotNull { e ->
            val d = e.optJSONObject("data") ?: return@mapNotNull null
            val t = parseMs(e.optString("timestamp")) ?: return@mapNotNull null
            M(t, d.optString("mark", ""), d.optString("reason", ""), if (d.optString("via") == "probook") " (pc)" else "")
        }.sortedByDescending { it.t }
        val out = ArrayList<Item>()
        marks.firstOrNull { it.mark in setOf("start", "end", "stop") }?.let { m ->
            if (m.mark == "start" && nowMs - m.t < PART_CAP_MS) {
                out.add(Item(m.reason.ifEmpty { "break part" } + m.pc + testMark(bucketId), m.t, "part"))
            }
        }
        val seen = HashSet<String>()
        for (m in marks) {
            if (m.mark != "overlay-start" && m.mark != "overlay-end") continue
            if (!seen.add(m.reason)) continue
            if (m.mark == "overlay-start" && nowMs - m.t < OVERLAY_CAP_MS) {
                out.add(Item("+" + m.reason.ifEmpty { "overlay" } + m.pc + testMark(bucketId), m.t, "overlay"))
            }
        }
        return out
    }

    /** Every running item over the phone's buckets, oldest first. */
    fun collect(bucketIds: Collection<String>, events: (String) -> JSONArray, nowMs: Long): List<Item> {
        val out = ArrayList<Item>()
        for (id in bucketIds) {
            when {
                isSpanBucket(id) -> out.addAll(fromSpans(id, events(id)))
                isPartsBucket(id) -> out.addAll(fromParts(id, events(id), nowMs))
            }
        }
        return out.sortedBy { it.startMs }
    }
}
