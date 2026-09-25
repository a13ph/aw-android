package net.activitywatch.android

import net.activitywatch.android.PillMirror.Action
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillMirrorTest {
    private val now = RunningSpans.parseMs("2026-09-25T10:00:00+00:00")!!
    private val min = 60_000L
    private var nextId = 1L

    private fun at(ago: Long) = java.time.Instant.ofEpochMilli(now - ago).toString()

    private fun mark(ago: Long, mark: String, reason: String? = null, echo: Boolean = false) =
        JSONObject().put("id", nextId++).put("timestamp", at(ago)).put("duration", 0.0)
            .put("data", JSONObject().put("mark", mark).apply {
                if (reason != null) put("reason", reason)
                if (echo) put("via", "probook")
            })

    private fun span(ago: Long, durMin: Long, vararg kv: Pair<String, Any>) =
        JSONObject().put("timestamp", at(ago)).put("duration", durMin * 60.0)
            .put("data", JSONObject().put("status", "afk").apply { kv.forEach { (k, v) -> put(k, v) } })

    private fun arr(vararg e: JSONObject) = JSONArray().apply { e.forEach { put(it) } }

    private fun pills(brk: Any? = JSONObject(), food: Any? = JSONObject(), drink: Any? = JSONObject()) =
        JSONObject().put("break", brk ?: JSONObject.NULL).put("food", food ?: JSONObject.NULL)
            .put("drink", drink ?: JSONObject.NULL)

    private fun pill(ago: Long, vararg kv: Pair<String, Any>) =
        JSONObject().put("start", (now - ago) / 1000).apply { kv.forEach { (k, v) -> put(k, v) } }

    private fun plan(parts: JSONArray, p: JSONObject?, breaks: JSONArray = JSONArray(),
                     food: JSONArray = JSONArray(), drink: JSONArray = JSONArray()) =
        PillMirror.plan(parts, p, mapOf(PillMirror.BREAKS to breaks, "aw-watcher-food_probook-nix" to food,
                                        "aw-watcher-drink_probook-nix" to drink), now)

    private fun posted(a: List<Action>): List<Triple<String, String, Long>> = a.map {
        val e = (it as Action.Post).event
        val d = e.getJSONObject("data")
        assertEquals("probook", d.getString("via"))
        Triple(d.getString("mark"), d.optString("reason"), RunningSpans.parseMs(e.getString("timestamp"))!!)
    }

    // ---- the four ways a break starts and ends, as the phone sees them

    @Test
    fun phoneStart_phoneEnd_nothingToWrite() {
        // cooking pressed and @pc pressed here; the pill mirrored it (by phone)
        val parts = arr(mark(20 * min, "start", "cooking"), mark(5 * min, "end"))
        assertEquals(emptyList<Action>(), plan(parts, pills(), arr(span(20 * min, 15, "source" to "manual"))))
    }

    @Test
    fun phoneStart_pcEnd_endsThePartHere() {
        val parts = arr(mark(20 * min, "start", "cooking"))
        val running = plan(parts, pills(pill(20 * min, "reasons" to JSONArray().put("cooking"), "by" to "phone")))
        assertEquals("the pill still runs: nothing", emptyList<Action>(), running)
        val ended = plan(parts, pills(), arr(span(20 * min, 17, "source" to "manual")))
        assertEquals(listOf(Triple("end", "", now - 3 * min)), posted(ended))
    }

    @Test
    fun pcStart_showsHere_thenPhoneEnds() {
        val p = pills(pill(10 * min, "reasons" to JSONArray().put("cooking")))
        val shown = plan(arr(mark(60 * min, "end")), p)
        assertEquals(listOf(Triple("start", "cooking", now - 10 * min)), posted(shown))
        // with the echo in place, and after al's «end last» here, nothing more is written
        val echoed = arr(mark(60 * min, "end"), mark(10 * min, "start", "cooking", echo = true))
        assertEquals(emptyList<Action>(), plan(echoed, p))
        echoed.put(mark(2 * min, "stop", "cooking"))
        assertEquals(emptyList<Action>(), plan(echoed, p))
    }

    @Test
    fun pcStart_pcEnd_theEchoEnds() {
        val parts = arr(mark(10 * min, "start", "break", echo = true))
        val a = plan(parts, pills(), arr(span(10 * min, 8, "source" to "manual")))
        assertEquals(listOf(Triple("end", "", now - 2 * min)), posted(a))
    }

    // ---- the echo follows the pill

    @Test
    fun aPillWithNoReasonShowsAsBreak_andTakesItsReasonLater() {
        assertEquals(listOf(Triple("start", "break", now - 5 * min)),
                     posted(plan(JSONArray(), pills(pill(5 * min, "reasons" to JSONArray())))))
        val echo = mark(5 * min, "start", "break", echo = true)
        val a = plan(arr(echo), pills(pill(5 * min, "reasons" to JSONArray().put("dishes"))))
        assertEquals(1, a.size)
        val r = a[0] as Action.Replace
        assertEquals(echo.getLong("id"), r.id)
        assertEquals("dishes", r.event.getJSONObject("data").getString("reason"))
    }

    @Test
    fun aPillCancelledOnProbook_theEchoGoes() {
        val echo = mark(5 * min, "start", "cooking", echo = true)
        assertEquals(listOf<Action>(Action.Delete(echo.getLong("id"))), plan(arr(echo), pills()))
    }

    @Test
    fun aPhoneStartedPillIsNeverEchoed() {
        assertEquals(emptyList<Action>(), plan(JSONArray(), pills(pill(5 * min, "by" to "phone"))))
    }

    @Test
    fun aPressAfterThePillStartedIsNotOverwritten() {
        // al pressed dishes here after the pill started: the fold adds it to the pill
        val parts = arr(mark(3 * min, "start", "dishes"))
        assertEquals(emptyList<Action>(), plan(parts, pills(pill(5 * min, "reasons" to JSONArray().put("cooking")))))
    }

    @Test
    fun aSplitBreakEndsAtItsWholeOuting() {
        val parts = arr(mark(20 * min, "start", "cooking"), mark(12 * min, "start", "dishes"))
        val o = "2026-09-25T09:40:00Z"
        val breaks = arr(span(20 * min, 8, "outing" to o), span(12 * min, 7, "outing" to o))
        assertEquals(listOf(Triple("end", "", now - 5 * min)), posted(plan(parts, pills(), breaks)))
    }

    // ---- offline: an unknown pill changes nothing; late answers act on their own times

    @Test
    fun unknownPillStateChangesNothing() {
        val echo = arr(mark(5 * min, "start", "cooking", echo = true), mark(5 * min, "overlay-start", "eating", echo = true))
        assertEquals(emptyList<Action>(), plan(echo, pills(null, null, null)))
        assertEquals(emptyList<Action>(), plan(echo, null))
        assertEquals(emptyList<Action>(), plan(JSONArray(), null))
    }

    @Test
    fun aPillStartedAndEndedWhileThePhoneWasAway_writesNothing() {
        val parts = arr(mark(90 * min, "start", "cooking"), mark(80 * min, "end"))
        assertEquals(emptyList<Action>(), plan(parts, pills(), arr(span(40 * min, 20, "source" to "manual"))))
    }

    @Test
    fun aStaleEchoPastTheCapIsLeftAlone() {
        val parts = arr(mark(3 * 60 * min, "start", "cooking", echo = true))
        assertEquals(emptyList<Action>(), plan(parts, pills()))
    }

    // ---- meals and drinks

    @Test
    fun phoneMeal_endedOnProbook_endsTheOverlay() {
        val parts = arr(mark(20 * min, "overlay-start", "eating"))
        val food = arr(span(20 * min, 15, "item" to "noodles"))
        assertEquals(listOf(Triple("overlay-end", "eating", now - 5 * min)), posted(plan(parts, pills(), food = food)))
    }

    @Test
    fun pcDrink_showsHere_andEndsWhenProbookEndsIt() {
        val p = pills(drink = pill(4 * min, "item" to "tea"))
        val shown = plan(JSONArray(), p)
        assertEquals(listOf(Triple("overlay-start", "drink", now - 4 * min)), posted(shown))
        assertEquals("tea", (shown[0] as Action.Post).event.getJSONObject("data").getString("item"))
        val echo = arr(mark(4 * min, "overlay-start", "drink", echo = true))
        assertEquals(emptyList<Action>(), plan(echo, p))
        val ended = plan(echo, pills(), drink = arr(span(4 * min, 3, "item" to "tea")))
        assertEquals(listOf(Triple("overlay-end", "drink", now - 1 * min)), posted(ended))
    }

    @Test
    fun pcMeal_endedHere_nothingMore() {
        val parts = arr(mark(10 * min, "overlay-start", "eating", echo = true), mark(2 * min, "overlay-end", "eating"))
        assertEquals(emptyList<Action>(), plan(parts, pills(food = pill(10 * min, "item" to "soup"))))
    }

    @Test
    fun aMealEchoCancelledOnProbookGoes() {
        val echo = mark(10 * min, "overlay-start", "eating", echo = true)
        assertEquals(listOf<Action>(Action.Delete(echo.getLong("id"))), plan(arr(echo), pills()))
    }

    @Test
    fun aMealRunsBesideABreak_eachOnItsOwn() {
        val p = pills(pill(10 * min, "reasons" to JSONArray().put("cooking")), food = pill(8 * min, "item" to "soup"))
        val got = posted(plan(JSONArray(), p)).toSet()
        assertEquals(setOf(Triple("start", "cooking", now - 10 * min), Triple("overlay-start", "eating", now - 8 * min)), got)
    }

    @Test
    fun cuTalkIsNeverTouched() {
        val parts = arr(mark(10 * min, "overlay-start", "talk w/ cuprum"))
        val got = plan(parts, pills(), arr(span(10 * min, 5, "source" to "manual")))
        assertTrue(got.isEmpty())
    }
}
