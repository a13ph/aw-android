package net.activitywatch.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/**
 * The phone's buttons and probook's pills show the same running break, meal and drink,
 * from the phone's side. Pure logic, no Android: [ProbookSync] feeds it this phone's
 * break-parts bucket, the pills probook's break-relay reports running (`/pull`'s
 * "pills") and the closed spans it pulled, and writes back what [plan] asks for.
 *
 * Everything it writes is a mark with `via: probook` in this phone's own break-parts
 * bucket, and [ProbookSync] never sends those to probook: probook already holds what
 * they echo. So the running-spans notification, «end last», «@pc», the part buttons
 * and +eat / +drink read one state, whichever side started or ended it.
 *
 * - **A part probook ended**: the newest start/end/stop mark is a `start` (pressed here
 *   or echoed) and a closed probook break covers it -> an `end` mark at the end of that
 *   break (of its whole outing, when the fold stored it one event per part).
 * - **A pill started on probook** (not `by: phone`), newer than the newest part mark
 *   here -> a `start` mark at the pill's start, reason its first reason or «break».
 *   Its reason follows the pill's while it runs. A press here after it goes to probook
 *   as usual: a part adds its reason, «end last» and «@pc» end the pill.
 * - **A pill cancelled on probook**: an echoed start whose pill no longer runs, with no
 *   closed break covering it, is deleted.
 * - **Meals and drinks** the same way with overlay-start / overlay-end marks, reason
 *   «eating» or «drink», matched to the probook span starting within [TOL_MS].
 *
 * Offline: a pill state probook could not read (null), or no pull at all, changes
 * nothing; the next pull that answers acts on the state then. The echoes are never
 * synced, so nothing is doubled on probook.
 */
object PillMirror {
    const val VIA = "probook"
    const val SLACK_BEFORE_MS = 180_000L
    const val TOL_MS = 2_000L
    const val BREAKS = "aw-watcher-break_probook-nix"
    private val PART_MARKS = setOf("start", "end", "stop")

    /** pill name in `/pull`'s "pills" -> overlay reason and probook bucket */
    val MEALS = linkedMapOf(
        "food" to ("eating" to "aw-watcher-food_probook-nix"),
        "drink" to ("drink" to "aw-watcher-drink_probook-nix"),
    )

    sealed class Action {
        data class Post(val event: JSONObject) : Action()
        data class Replace(val id: Long, val event: JSONObject) : Action()
        data class Delete(val id: Long) : Action()
    }

    private data class Mark(val id: Long, val t: Long, val mark: String, val reason: String, val echo: Boolean)

    private fun objects(events: JSONArray?): List<JSONObject> =
        if (events == null) emptyList() else (0 until events.length()).mapNotNull { events.optJSONObject(it) }

    private fun marks(parts: JSONArray): List<Mark> = objects(parts).mapNotNull { e ->
        val d = e.optJSONObject("data") ?: return@mapNotNull null
        val t = RunningSpans.parseMs(e.optString("timestamp")) ?: return@mapNotNull null
        Mark(e.optLong("id", -1L), t, d.optString("mark", ""), d.optString("reason", ""), d.optString("via") == VIA)
    }.sortedBy { it.t }

    private data class Span(val start: Long, val end: Long, val outing: String?)

    private fun closed(events: JSONArray?): List<Span> = objects(events).mapNotNull { e ->
        val d = e.optJSONObject("data") ?: JSONObject()
        if (d.optBoolean("live", false) || d.optBoolean("running", false)) return@mapNotNull null
        val t = RunningSpans.parseMs(e.optString("timestamp")) ?: return@mapNotNull null
        Span(t, t + (e.optDouble("duration", 0.0) * 1000).toLong(), d.optString("outing").ifEmpty { null })
    }

    /** A pill's state: null when unknown (probook could not read it), else its start ms or 0 when none runs. */
    private fun pillStart(pills: JSONObject?, name: String): Long? {
        if (pills == null || !pills.has(name) || pills.isNull(name)) return null
        val p = pills.optJSONObject(name) ?: return null
        return p.optLong("start", 0L) * 1000
    }

    fun mark(atMs: Long, mark: String, reason: String?, item: String? = null): JSONObject {
        val d = JSONObject().put("mark", mark).put("source", "probook").put("via", VIA)
        if (reason != null) d.put("reason", reason)
        if (item != null) d.put("item", item)
        return JSONObject().put("timestamp", Instant.ofEpochMilli(atMs).toString()).put("duration", 0.0).put("data", d)
    }

    /** What to write into the phone's break-parts bucket so it shows what probook runs. */
    fun plan(parts: JSONArray, pills: JSONObject?, pulled: Map<String, JSONArray>, nowMs: Long): List<Action> {
        val ms = marks(parts)
        val out = ArrayList<Action>()
        planBreak(ms, pills, closed(pulled[BREAKS]), nowMs, out)
        for ((name, rb) in MEALS) {
            planMeal(ms, name, rb.first, pills, closed(pulled[rb.second]), nowMs, out)
        }
        return out
    }

    private fun planBreak(ms: List<Mark>, pills: JSONObject?, breaks: List<Span>, nowMs: Long, out: MutableList<Action>) {
        val newest = ms.lastOrNull { it.mark in PART_MARKS }
        val start = pillStart(pills, "break")
        val pill = pills?.optJSONObject("break")
        val reason = pill?.optJSONArray("reasons")?.optString(0)?.ifEmpty { null } ?: "break"
        if (newest != null && newest.mark == "start" && nowMs - newest.t < RunningSpans.PART_CAP_MS) {
            val cover = breaks.filter { it.start - SLACK_BEFORE_MS <= newest.t && newest.t < it.end && it.end <= nowMs }
                .minByOrNull { it.end }
            if (cover != null) {
                val end = if (cover.outing == null) cover.end
                else breaks.filter { it.outing == cover.outing }.maxOf { it.end }
                out.add(Action.Post(mark(end, "end", null)))
                return
            }
            if (newest.echo && start != null) {
                if (start == 0L || abs(start - newest.t) > TOL_MS) {
                    out.add(Action.Delete(newest.id))                      // cancelled on probook
                    return
                }
                if (newest.reason != reason && newest.id >= 0) {
                    out.add(Action.Replace(newest.id, mark(newest.t, "start", reason)))
                }
                return
            }
        }
        if (start != null && start > 0 && pill?.optString("by") != "phone"
            && (newest == null || newest.t < start - TOL_MS) && nowMs - start < RunningSpans.PART_CAP_MS) {
            out.add(Action.Post(mark(start, "start", reason)))
        }
    }

    private fun planMeal(
        ms: List<Mark>, name: String, reason: String, pills: JSONObject?, spans: List<Span>, nowMs: Long,
        out: MutableList<Action>,
    ) {
        val newest = ms.lastOrNull { (it.mark == "overlay-start" || it.mark == "overlay-end") && it.reason == reason }
        val start = pillStart(pills, name)
        val pill = pills?.optJSONObject(name)
        if (newest != null && newest.mark == "overlay-start" && nowMs - newest.t < RunningSpans.OVERLAY_CAP_MS) {
            val done = spans.filter { abs(it.start - newest.t) <= TOL_MS && it.end <= nowMs }.minByOrNull { it.end }
            if (done != null) {
                out.add(Action.Post(mark(done.end, "overlay-end", reason)))
                return
            }
            if (newest.echo && start != null && (start == 0L || abs(start - newest.t) > TOL_MS)) {
                out.add(Action.Delete(newest.id))
                return
            }
            if (newest.echo || start == null) return
        }
        if (start != null && start > 0 && pill?.optString("by") != "phone"
            && (newest == null || newest.t < start - TOL_MS) && nowMs - start < RunningSpans.OVERLAY_CAP_MS) {
            out.add(Action.Post(mark(start, "overlay-start", reason, pill?.optString("item")?.ifEmpty { null })))
        }
    }
}
