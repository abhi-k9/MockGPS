package dev.burixon.mockgps

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.burixon.mockgps.core.OperatingMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Runs real SystemUI tile binding/clicks on a disposable, unlocked emulator; never injects. */
@RunWith(AndroidJUnit4::class)
class TileBoundaryInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
    private fun mode(): OperatingMode {
        val result = AtomicReference<OperatingMode>()
        instrumentation.runOnMainSync { result.set(ControlCenter.snapshot(context).mode) }
        return result.get()
    }
    private fun awaitMode(expected: OperatingMode) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end && mode() != expected) Thread.sleep(100)
        assertEquals(expected, mode())
    }
    @Test fun realSystemUiClicksSwitchModesAndRebindingNeverStartsSimulation() {
        val original = shell("settings get secure sysui_qs_tiles")
        val component = ComponentName(context, ModeTileService::class.java).flattenToString()
        try {
            instrumentation.runOnMainSync { ControlCenter.forceReal(context) }
            shell("wm dismiss-keyguard")
            shell("cmd statusbar add-tile $component")
            shell("cmd statusbar expand-settings")
            // Allow asynchronous SystemUI binding before asking it to dispatch the click.
            Thread.sleep(1000)
            shell("cmd statusbar click-tile $component")
            awaitMode(OperatingMode.AUTO)
            shell("cmd statusbar click-tile $component")
            awaitMode(OperatingMode.FORCE_REAL)
            shell("cmd statusbar collapse")
            shell("cmd statusbar expand-settings")
            Thread.sleep(500)
            shell("cmd statusbar click-tile $component")
            awaitMode(OperatingMode.AUTO)
            instrumentation.runOnMainSync {
                val s = ControlCenter.snapshot(context)
                assertFalse(s.desired)
                assertFalse(s.needsConfirmation)
                ControlCenter.forceReal(context) // Works without Tasker being installed/running.
            }
            awaitMode(OperatingMode.FORCE_REAL)
        } finally {
            instrumentation.runOnMainSync { ControlCenter.forceReal(context) }
            shell("cmd statusbar remove-tile $component")
            if (original == "null") shell("settings delete secure sysui_qs_tiles")
            else shell("settings put secure sysui_qs_tiles '" + original.replace("'", "'\\''") + "'")
            shell("cmd statusbar collapse")
        }
    }
}
