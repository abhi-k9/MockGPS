package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.burixon.mockgps.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual FGS/provider cleanup on a disposable emulator, never on a user's phone.
 * Shell appops only provisions test mock authorization; it proves no External FGS exemption.
 */
@RunWith(AndroidJUnit4::class)
class ReadinessSafetyInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var sequence = 0L
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun snapshot() = main { ControlCenter.snapshot(context) }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end && !condition()) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun request(command: String) = ExternalRequest(AutomationCommand.API_VERSION, AutomationAccess.token(context)!!,
        command, System.currentTimeMillis(), ++sequence)
    private fun command(command: String) = main { ControlCenter.external(context, request(command)) }

    @Test fun newerSequenceCannotMakeStaleRequestOverrideBlocked() {
        main {
            val paused = request("pause")
            assertTrue(ControlCenter.external(context, paused).accepted)
            val safe = ControlCenter.snapshot(context)
            for (time in listOf(paused.eventTime - 1, paused.eventTime)) {
                val stale = request("request_simulation").copy(eventTime = time)
                assertEquals("STALE_OBSERVATION", ControlCenter.external(context, stale).code)
                assertEquals(safe, ControlCenter.snapshot(context))
            }
        }
    }

    @Test fun externalRevocationInvalidatesPendingWorkAndShortcutIsIndependent() {
        scenario!!.onActivity {
            val shortcuts = it.findViewById<android.widget.Switch>(R.id.shortcutIntegrationSwitch)
            assertTrue(shortcuts.isEnabled)
            assertFalse(shortcuts.isChecked)
        }
        main {
            val oldToken = AutomationAccess.token(context)
            assertTrue(ControlCenter.external(context, request("request_simulation")).accepted)
            val pending = ControlCenter.snapshot(context)
            assertNotNull(pending.pendingAtElapsed)
            assertTrue(ControlCenter.setIntegrationEnabled(context, false))
            assertNull(AutomationAccess.token(context))
            assertNull(ControlCenter.snapshot(context).pendingAtElapsed)
            assertFalse(ControlCenter.snapshot(context).desired)
            ControlCenter.deadline(context, pending.generation)
            assertFalse(ControlCenter.snapshot(context).needsConfirmation)
            assertTrue(ControlCenter.setIntegrationEnabled(context, true))
            assertNotEquals(oldToken, AutomationAccess.token(context))
            assertEquals("UNAUTHORIZED", ControlCenter.external(context,
                ExternalRequest(AutomationCommand.API_VERSION, oldToken, "request_simulation", System.currentTimeMillis(), ++sequence)).code)
            assertEquals(ReadinessState.UNKNOWN, ControlCenter.snapshot(context).readiness)
        }
    }

    @Test fun shortcutPrivateShortcutAuthorityAndRevocation() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, AutomationShortcutActivity::class.java), 0)
        assertFalse(info.exported)
        try {
            scenario!!.onActivity {
                ControlCenter.setShortcutsEnabled(it, true)
                assertTrue(AutomationShortcuts.enabled(it))
                val shortcuts = it.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts
                val paused = shortcuts.first { s -> s.id == "automation_pause" }.intent!!
                val before = AutomationAccess.token(it)
                assertTrue(AutomationShortcuts.dispatch(it, paused))
                assertEquals(ReadinessState.BLOCKED, ControlCenter.snapshot(it).readiness)
                assertFalse(AutomationShortcuts.dispatch(it, paused))
                assertEquals(before, AutomationAccess.token(it))
                ControlCenter.setShortcutsEnabled(it, false)
                assertFalse(AutomationShortcuts.dispatch(it, paused))
                assertEquals(before, AutomationAccess.token(it))
                assertTrue(AutomationAccess.isEnabled(it))
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    @Test fun shortcutRequestNeedsLocalReviewAndPauseCancelsIt() {
        try {
            scenario!!.onActivity {
                ControlCenter.setShortcutsEnabled(it, true)
                ControlCenter.selectAuto(it)
                val manager = it.getSystemService(android.content.pm.ShortcutManager::class.java)
                val shortcuts = manager.dynamicShortcuts
                val simulationRequest = shortcuts.first { s -> s.id == "automation_request_simulation" }.intent!!
                val paused = shortcuts.first { s -> s.id == "automation_pause" }.intent!!
                assertTrue(AutomationShortcuts.dispatch(it, simulationRequest))
                val old = ControlCenter.snapshot(it).generation
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                assertTrue(ControlCenter.shortcutReviewPending(it))
                assertTrue(AutomationShortcuts.dispatch(it, paused))
                ControlCenter.reviewShortcutRequest(it, old)
                assertFalse(ControlCenter.shortcutReviewPending(it))
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                assertFalse(ControlCenter.snapshot(it).desired)
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    @Test fun shortcutRegistrationStableRefreshDisableAndReenable() {
        try {
            scenario!!.onActivity {
                ControlCenter.setShortcutsEnabled(it, true)
                val manager = it.getSystemService(android.content.pm.ShortcutManager::class.java)
                val first = manager.dynamicShortcuts.associateBy { s -> s.id }
                assertTrue(first.keys.containsAll(listOf("automation_pause", "automation_request_simulation", "automation_unknown", "automation_stop")))
                assertTrue(first.values.all { s -> s.isEnabled && !s.shortLabel.isNullOrBlank() })
                val saved = first.getValue("automation_stop").intent!!
                assertTrue(AutomationShortcuts.publish(it))
                assertEquals(saved.getStringExtra("nonce"), manager.dynamicShortcuts.first { s -> s.id == "automation_stop" }.intent!!.getStringExtra("nonce"))
                ControlCenter.setShortcutsEnabled(it, false)
                assertTrue(manager.dynamicShortcuts.none { s -> s.id.startsWith("automation_") })
                assertFalse(AutomationShortcuts.dispatch(it, saved))
                ControlCenter.setShortcutsEnabled(it, true)
                assertFalse(AutomationShortcuts.dispatch(it, saved))
                val refreshed = manager.dynamicShortcuts.first { s -> s.id == "automation_stop" }.intent!!
                assertTrue(AutomationShortcuts.dispatch(it, refreshed))
                assertEquals(OperatingMode.FORCE_REAL, ControlCenter.snapshot(it).mode)
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    @Test fun shortcutCannotBeStartedByShellAndReviewCannotEraseExternalBlocked() {
        val pipes = instrumentation.uiAutomation.executeShellCommandRwe(
            "am start -W -n ${context.packageName}/dev.burixon.mockgps.AutomationShortcutActivity")
        pipes[1].close()
        val output = listOf(pipes[0], pipes[2]).joinToString("\n") { pipe ->
            ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
        }
        assertTrue(output.contains("Permission Denial") || output.contains("not exported"))
        try {
            scenario!!.onActivity {
                ControlCenter.setShortcutsEnabled(it, true)
                ControlCenter.selectAuto(it)
                assertTrue(ControlCenter.external(it, request("pause")).accepted)
                val simulationRequest = it.getSystemService(android.content.pm.ShortcutManager::class.java)
                    .dynamicShortcuts.first { s -> s.id == "automation_request_simulation" }.intent!!
                assertTrue(AutomationShortcuts.dispatch(it, simulationRequest))
                val snapshot = ControlCenter.snapshot(it)
                assertEquals(ReadinessState.BLOCKED, snapshot.readiness)
                ControlCenter.reviewShortcutRequest(it, snapshot.generation)
                assertEquals(ReadinessState.BLOCKED, ControlCenter.snapshot(it).readiness)
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                assertFalse(ControlCenter.snapshot(it).desired)
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    private fun shortcutAction(activity: MainActivity, action: String) {
        assertTrue(AutomationShortcuts.publish(activity))
        val intent = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
            .dynamicShortcuts.first { it.id == "automation_$action" }.intent!!
        assertTrue(AutomationShortcuts.dispatch(activity, intent))
    }

    @Test fun dualSourceUnknownBlocksDelayAndFreshAgreementCanBeCancelled() {
        try {
            scenario!!.onActivity {
                ControlCenter.setDelay(it, 120)
                ControlCenter.setShortcutsEnabled(it, true)
                ControlCenter.selectAuto(it)
                shortcutAction(it, "request_simulation")
                val oldReview = ControlCenter.snapshot(it).generation
                ControlCenter.reviewShortcutRequest(it, oldReview)
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                assertEquals(ReadinessState.UNKNOWN, ControlCenter.snapshot(it).readiness)
                assertTrue(ControlCenter.external(it, request("unknown").copy(eventTime = System.currentTimeMillis() - 1000)).accepted)
                ControlCenter.reviewShortcutRequest(it, oldReview)
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                shortcutAction(it, "request_simulation")
                ControlCenter.reviewShortcutRequest(it, ControlCenter.snapshot(it).generation)
                val ready = request("request_simulation")
                assertTrue(ControlCenter.external(it, ready).accepted)
                val pending = ControlCenter.snapshot(it)
                assertNotNull(pending.pendingAtElapsed)
                assertFalse(pending.desired)
                ControlCenter.deadline(it, pending.generation) // minimum delay has not elapsed
                assertFalse(ControlCenter.snapshot(it).needsConfirmation)
                shortcutAction(it, "unknown")
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                assertFalse(ControlCenter.external(it, ready).accepted) // consumed external sequence
                ControlCenter.deadline(it, pending.generation)
                ControlCenter.confirmPending(it, pending.generation)
                assertFalse(ControlCenter.snapshot(it).desired)
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    @Test fun shortcutDelayOnlyRequestsConfirmationAndSafetyRemovesRealProviders() {
        try {
            scenario!!.onActivity {
                ControlCenter.setIntegrationEnabled(it, false) // isolated emulator fixture only
                ControlCenter.setDelay(it, 0)
                ControlCenter.setShortcutsEnabled(it, true)
                ControlCenter.selectAuto(it)
                shortcutAction(it, "request_simulation")
                assertNull(ControlCenter.snapshot(it).pendingAtElapsed)
                ControlCenter.reviewShortcutRequest(it, ControlCenter.snapshot(it).generation)
                val pending = ControlCenter.snapshot(it)
                assertNotNull(pending.pendingAtElapsed)
                ControlCenter.deadline(it, pending.generation)
                assertTrue(ControlCenter.snapshot(it).needsConfirmation)
                assertFalse(ControlCenter.snapshot(it).desired)
                shortcutAction(it, "stop")
                ControlCenter.confirmPending(it, pending.generation)
                assertFalse(ControlCenter.snapshot(it).desired)
                assertEquals(OperatingMode.FORCE_REAL, ControlCenter.snapshot(it).mode)
                ControlCenter.selectAuto(it)
            }
            for (action in listOf("pause", "unknown")) {
                startManualAndAwaitInjection()
                scenario!!.onActivity { shortcutAction(it, action) }
                assertFalse(snapshot().desired)
                assertNull(snapshot().pendingAtElapsed)
                assertFalse(main { MockLocationService.hasOwnedProviders(context) })
                assertEquals(ServiceState.STOPPED, snapshot().service)
                scenario!!.onActivity { ControlCenter.selectAuto(it) }
            }
        } finally { main { ControlCenter.setShortcutsEnabled(context, false) } }
    }

    @Before fun prepareDisposableEmulator() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Provider tests require a disposable emulator" }
        shell("appops set ${context.packageName} android:mock_location allow")
        for (permission in listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS")) {
            shell("pm grant ${context.packageName} android.permission.$permission")
        }
        main {
            ControlCenter.setShortcutsEnabled(context, false)
            ControlCenter.setIntegrationEnabled(context, false)
            ControlCenter.forceReal(context)
            context.getSharedPreferences("mock_configuration", Context.MODE_PRIVATE).edit()
                .putBoolean("legacy_unverified", false).commit().also { assertTrue(it) }
            State.setLocation(context, 0.01, 0.01) // Synthetic test point, never a genuine fix.
            State.setDrifting(context, false)
            ControlCenter.selectAuto(context)
            assertTrue(ControlCenter.setIntegrationEnabled(context, true))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitCondition("Visible Activity must have focus before an explicit manual start") {
            var focused = false
            scenario!!.onActivity { focused = it.hasWindowFocus() }
            focused
        }
    }

    @After fun cleanup() {
        if (Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        shell("appops set ${context.packageName} android:mock_location allow")
        main { ControlCenter.setIntegrationEnabled(context, false); ControlCenter.forceReal(context) }
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
        scenario?.close()
        shell("appops set ${context.packageName} android:mock_location default")
    }

    private fun startManualAndAwaitInjection(): Long {
        scenario!!.onActivity { ControlCenter.manualStart(it) }
        awaitCondition("Android must accept both provider updates before testing shutdown") {
            snapshot().injection == InjectionState.CONFIRMED && snapshot().mode == OperatingMode.MANUAL_MOCK
        }
        assertTrue(main { MockLocationService.hasOwnedProviders(context) })
        return snapshot().generation
    }

    @Test fun authenticatedBlockedBroadcastStopsRealManualServiceAndRejectsLateCallbacks() {
        val oldGeneration = startManualAndAwaitInjection()
        val request = request("pause")
        val delivered = CountDownLatch(1)
        var code = -1
        context.sendOrderedBroadcast(Intent(MockGpsReceiver.ACTION)
            .setComponent(ComponentName(context, MockGpsReceiver::class.java))
            .putExtra("api", request.api).putExtra("token", request.token).putExtra("command", request.command)
            .putExtra("event_time", request.eventTime).putExtra("seq", request.sequence), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { code = resultCode; delivered.countDown() }
            }, null, -1, null, null)
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertEquals(0, code)
        val stopped = snapshot()
        assertEquals(OperatingMode.AUTO, stopped.mode)
        assertFalse(stopped.desired)
        assertEquals(ServiceState.STOPPED, stopped.service)
        assertEquals(ProviderState.NONE, stopped.providers)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
        main {
            assertFalse(ControlCenter.canRun(context, oldGeneration))
            ControlCenter.serviceStarted(context, oldGeneration)
            ControlCenter.serviceInjected(context, oldGeneration)
            ControlCenter.serviceStopped(context, oldGeneration, true)
            ControlCenter.serviceFailed(context, oldGeneration, "LATE_CALLBACK", true)
        }
        assertEquals(stopped, snapshot())
        assertTrue(command("request_simulation").accepted)
        assertEquals(OperatingMode.AUTO, snapshot().mode)
        assertFalse(snapshot().desired)
        assertNotNull(snapshot().pendingAtElapsed)
        // Higher-sequence duplicates retry cleanup; replayed commands cannot mutate it.
        assertTrue(command("pause").accepted)
        assertTrue(command("pause").accepted)
        assertNull(snapshot().pendingAtElapsed)
        assertFalse(snapshot().desired)
    }

    @Test fun unknownStopsRealManualInjectionAndCancelsAnAsynchronousStart() {
        startManualAndAwaitInjection()
        assertTrue(command("unknown").accepted)
        assertFalse(snapshot().desired)
        assertEquals(OperatingMode.AUTO, snapshot().mode)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
        // Stop arrives on the main looper before the earlier start-service intent is delivered.
        var startGeneration = -1L
        scenario!!.onActivity {
            ControlCenter.manualStart(it)
            val starting = ControlCenter.snapshot(it)
            assertTrue("The race must cancel an admitted start, not a rejected request", starting.desired)
            assertEquals(ServiceState.STARTING, starting.service)
            startGeneration = starting.generation
            assertTrue(ControlCenter.external(it, request("pause")).accepted)
        }
        instrumentation.waitForIdleSync()
        assertFalse(main { ControlCenter.canRun(context, startGeneration) })
        assertFalse(snapshot().desired)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
        val loaded = main { PolicyStore(context).load() }
        assertEquals(OperatingMode.AUTO, loaded.mode)
        assertFalse(Policy.reduce(loaded, PolicyEvent.Recover, 0).desired)
    }

    @Test fun failedPlatformRemovalIsVisibleAndRetriedAfterAuthorizationRestored() {
        val generation = startManualAndAwaitInjection()
        main {
            // Block the publisher too, avoiding a timer race before the authenticated stop.
            shell("appops set ${context.packageName} android:mock_location deny")
            assertTrue(ControlCenter.external(context, request("pause")).accepted)
        }
        val failed = snapshot()
        assertFalse(failed.desired)
        assertEquals(OperatingMode.AUTO, failed.mode)
        assertEquals("CLEANUP_UNCERTAIN", failed.result)
        assertEquals(ProviderState.UNKNOWN, failed.providers)
        assertEquals(InjectionState.UNKNOWN, failed.injection)
        assertTrue(main { MockLocationService.hasOwnedProviders(context) })
        main { ControlCenter.serviceInjected(context, generation); ControlCenter.serviceStopped(context, generation, false) }
        assertEquals(failed, snapshot())
        shell("appops set ${context.packageName} android:mock_location allow")
        assertTrue(command("pause").accepted)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
        assertEquals(ServiceState.STOPPED, snapshot().service)
        // Successful cleanup is still not evidence of a fresh genuine fix.
        assertFalse(snapshot().displayStatus().contains("restored"))
    }

    @Test fun unknownInvalidatesActualPendingConfirmationAndForceRealWins() {
        main { ControlCenter.setDelay(context, 0) }
        assertTrue(command("request_simulation").accepted)
        val pending = snapshot()
        main { ControlCenter.deadline(context, pending.generation) }
        assertTrue(snapshot().needsConfirmation)
        assertTrue(command("unknown").accepted)
        scenario!!.onActivity { ControlCenter.confirmPending(it, pending.generation) }
        assertFalse(snapshot().desired)
        assertFalse(snapshot().needsConfirmation)
        startManualAndAwaitInjection()
        val originalTiles = shell("settings get secure sysui_qs_tiles")
        val component = ComponentName(context, ModeTileService::class.java).flattenToString()
        try {
            shell("cmd statusbar add-tile $component")
            shell("cmd statusbar expand-settings")
            Thread.sleep(1000) // SystemUI binds asynchronously.
            shell("cmd statusbar click-tile $component")
            awaitCondition("The real native tile must stop active manual injection") {
                snapshot().mode == OperatingMode.FORCE_REAL && !snapshot().desired
            }
        } finally {
            shell("cmd statusbar remove-tile $component")
            if (originalTiles == "null") shell("settings delete secure sysui_qs_tiles")
            else shell("settings put secure sysui_qs_tiles '" + originalTiles.replace("'", "'\\''") + "'")
            shell("cmd statusbar collapse")
        }
        assertTrue(command("request_simulation").accepted)
        assertEquals(OperatingMode.FORCE_REAL, snapshot().mode)
        assertFalse(snapshot().desired)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
    }
    @Test fun repeatedManualStartKeepsTheRealSessionAndMalformedIpcCannotStopIt() {
        val generation = startManualAndAwaitInjection()
        val before = snapshot()
        scenario!!.onActivity { ControlCenter.manualStart(it) }
        assertEquals(before, snapshot())
        val bad = request("pause")
        val delivered = CountDownLatch(1)
        var code = -1
        context.sendOrderedBroadcast(Intent(MockGpsReceiver.ACTION)
            .setComponent(ComponentName(context, MockGpsReceiver::class.java))
            .putExtra("api", bad.api).putExtra("token", bad.token).putExtra("command", bad.command)
            .putExtra("event_time", bad.eventTime).putExtra("seq", bad.sequence.toString()), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { code = resultCode; delivered.countDown() }
            }, null, -1, null, null)
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertEquals(1, code)
        assertEquals(before, snapshot())
        assertTrue(main { MockLocationService.hasOwnedProviders(context) })
        assertTrue(main { ControlCenter.canRun(context, generation) })
        assertEquals("MALFORMED_REQUEST", main { ControlCenter.commandDiagnostic().lastRejected })
    }

    @Test fun externalIntentUriRecipePreservesTheFiveExactTypes() {
        startManualAndAwaitInjection()
        val token = AutomationAccess.token(context)!!
        val time = System.currentTimeMillis()
        val intent = Intent.parseUri("intent:#Intent;action=" + MockGpsReceiver.ACTION +
            ";component=dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver;" +
            "i.api=${AutomationCommand.API_VERSION};S.token=$token;S.command=pause;l.event_time=$time;l.seq=${++sequence};end",
            Intent.URI_INTENT_SCHEME)
        @Suppress("DEPRECATION")
        val raw = intent.extras!!.keySet().associateWith { intent.extras!!.get(it) }
        assertNotNull(CommandAuth.parse(raw))
        val delivered = CountDownLatch(1)
        var code = -1
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { code = resultCode; delivered.countDown() }
        }, null, -1, null, null)
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertEquals(0, code)
        assertFalse(snapshot().desired)
        assertFalse(main { MockLocationService.hasOwnedProviders(context) })
    }

}
