package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleWatcherParseTest {

    @Test
    fun readsLastUserActivityAndWakefulness() {
        val dump = sequenceOf(
            "POWER MANAGER (dumpsys power)",
            "",
            "Power Manager State:",
            "  mDirty=0x0",
            "  mWakefulness=Awake",
            "  mLastUserActivityTimeNoChangeLights=0 (167674630 ms ago)",
            "  mLastUserActivityTime=167663949 (10681 ms ago)",
        )
        assertEquals(PowerSample(10681L, "Awake"), parsePowerDump(dump))
    }

    @Test
    fun asleepIsReadToo() {
        val dump = sequenceOf("  mLastUserActivityTime=5 (0 ms ago)", "  mWakefulness=Asleep")
        assertEquals(PowerSample(0L, "Asleep"), parsePowerDump(dump))
    }

    @Test
    fun permissionDenialGivesNothing() {
        val dump = sequenceOf(
            "Permission Denial: can't dump PowerManagerService from from pid=1, uid=10325" +
                " due to missing android.permission.DUMP permission"
        )
        assertEquals(PowerSample(null, null), parsePowerDump(dump))
    }
}
