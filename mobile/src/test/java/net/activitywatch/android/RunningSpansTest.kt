package net.activitywatch.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningSpansTest {
    private val now = RunningSpans.parseMs("2026-09-25T10:00:00+00:00")!!

    private fun ev(ts: String, vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().put("timestamp", ts).put("duration", 0.0)
            .put("data", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })

    private fun arr(vararg e: JSONObject) = JSONArray().apply { e.forEach { put(it) } }

    @Test
    fun bucketKinds() {
        assertTrue(RunningSpans.isSpanBucket("aw-watcher-activity_oneplus_5"))
        assertTrue(RunningSpans.isSpanBucket("aw-watcher-sleep-test_oneplus_5"))
        assertTrue(RunningSpans.isSpanBucket("aw-stopwatch"))
        assertFalse(RunningSpans.isSpanBucket("aw-watcher-activity_probook-nix"))
        assertFalse(RunningSpans.isSpanBucket("aw-watcher-afk_oneplus_5"))
        assertTrue(RunningSpans.isPartsBucket("aw-watcher-break-parts_oneplus_5"))
        assertFalse(RunningSpans.isPartsBucket("aw-watcher-break_probook-nix"))
    }

    @Test
    fun spans_onlyRunningOnes() {
        val items = RunningSpans.fromSpans(
            "aw-watcher-sleep_oneplus_5",
            arr(
                ev("2026-09-25T09:00:00.000+00:00", "stage" to "in-bed", "running" to true),
                ev("2026-09-25T08:00:00+00:00", "stage" to "sleep", "running" to false),
            ),
        )
        assertEquals(listOf("in bed"), items.map { it.label })
        assertEquals(now - 3600_000L, items[0].startMs)
    }

    @Test
    fun parts_overlayOpenUntilItsEnd() {
        val b = "aw-watcher-break-parts_oneplus_5"
        val open = arr(
            ev("2026-09-25T09:50:00Z", "mark" to "overlay-start", "reason" to "talk w/ cuprum"),
            ev("2026-09-25T09:40:00Z", "mark" to "start", "reason" to "cooking"),
        )
        assertEquals(
            listOf("cooking", "+talk w/ cuprum"),
            RunningSpans.collect(listOf(b), { open }, now).map { it.label },
        )
        val ended = arr(
            ev("2026-09-25T09:55:00Z", "mark" to "overlay-end", "reason" to "talk w/ cuprum"),
            ev("2026-09-25T09:55:00Z", "mark" to "start", "reason" to "dishes"),
            ev("2026-09-25T09:50:00Z", "mark" to "overlay-start", "reason" to "talk w/ cuprum"),
        )
        assertEquals(listOf("dishes"), RunningSpans.fromParts(b, ended, now).map { it.label })
    }

    @Test
    fun parts_stopEndsThePart_andTheCapLapsesIt() {
        val b = "aw-watcher-break-parts-test_oneplus_5"
        val stopped = arr(
            ev("2026-09-25T09:58:00Z", "mark" to "stop", "reason" to "cooking"),
            ev("2026-09-25T09:40:00Z", "mark" to "start", "reason" to "cooking"),
        )
        assertTrue(RunningSpans.fromParts(b, stopped, now).isEmpty())
        val old = arr(ev("2026-09-25T07:30:00Z", "mark" to "start", "reason" to "bench"))
        assertTrue(RunningSpans.fromParts(b, old, now).isEmpty())
        val fresh = arr(ev("2026-09-25T09:30:00Z", "mark" to "start", "reason" to "bench"))
        assertEquals(listOf("bench (test)"), RunningSpans.fromParts(b, fresh, now).map { it.label })
    }
}
