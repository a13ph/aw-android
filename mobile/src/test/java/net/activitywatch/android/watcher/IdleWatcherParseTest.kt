package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun shellInputRunIsACommand() {
        val line = "         1789151502.181 shell 27646 27646 D AndroidRuntime: " +
            "Calling main entry com.android.commands.input.Input"
        assertEquals(ShellCommand(1789151502181L, "input", null), parseShellLine(line))
    }

    @Test
    fun shellLaunchIsAStart() {
        val line = "         1789150676.568  1000  1636  3145 I ActivityTaskManager: START u0 " +
            "{act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] " +
            "flg=0x10200000 cmp=com.example.app/.MainActivity} from uid 2000 pid 24045"
        assertEquals(ShellCommand(1789150676568L, "start", "com.example.app"), parseShellLine(line))
    }

    @Test
    fun launchesByOthersAndAppRuntimesAreNot() {
        val home = "         1789151505.129  1000  1636  2524 I ActivityTaskManager: START u0 " +
            "{act=android.intent.action.MAIN cat=[android.intent.category.HOME] " +
            "cmp=com.example.launcher/.Launcher (has extras)} from uid 0 pid 0"
        val appUid = "         1789151502.181 u0_a100 27646 27646 D AndroidRuntime: " +
            "Calling main entry com.android.commands.input.Input"
        val tapped = "         1789151505.129  1000  1636  2524 I ActivityTaskManager: START u0 " +
            "{cmp=com.example.app/.Main} from uid 10117 pid 4935"
        assertNull(parseShellLine(home))
        assertNull(parseShellLine(appUid))
        assertNull(parseShellLine(tapped))
    }
}
