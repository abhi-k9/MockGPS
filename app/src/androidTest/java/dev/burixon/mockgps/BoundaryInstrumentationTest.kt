package dev.burixon.mockgps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.burixon.mockgps.core.InjectionState
import dev.burixon.mockgps.core.OperatingMode
import dev.burixon.mockgps.core.Policy
import dev.burixon.mockgps.core.PolicyEvent
import dev.burixon.mockgps.core.PolicySnapshot
import dev.burixon.mockgps.core.ProviderState
import dev.burixon.mockgps.core.ServiceState
import dev.burixon.mockgps.core.VehicleState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android boundaries/storage, without starting an FGS or injecting location. */
@RunWith(AndroidJUnit4::class)
class BoundaryInstrumentationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: PackageManager get() = context.packageManager

    @Test
    fun mergedManifestKeepsInternalComponentsPrivateAndTileSystemProtected() {
        @Suppress("DEPRECATION")
        val service = manager.getServiceInfo(ComponentName(context, MockLocationService::class.java), 0)
        assertFalse(service.exported)
        @Suppress("DEPRECATION")
        val tile = manager.getServiceInfo(ComponentName(context, ModeTileService::class.java), 0)
        assertTrue(tile.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        for (receiver in listOf(BootReceiver::class.java, DelayReceiver::class.java)) {
            @Suppress("DEPRECATION")
            val info = manager.getReceiverInfo(ComponentName(context, receiver), 0)
            assertFalse(info.exported)
        }
        @Suppress("DEPRECATION")
        val control = manager.getReceiverInfo(ComponentName(context, MockGpsReceiver::class.java), 0)
        assertTrue(control.exported) // Separately signed Tasker authenticates with an opt-in capability.
    }

    @Test
    fun mergedManifestDisablesBackupCleartextAndUnneededPrivileges() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        @Suppress("DEPRECATION")
        val permissions = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
        assertFalse("android.permission.ACCESS_WIFI_STATE" in permissions)
        assertFalse("android.permission.BIND_ACCESSIBILITY_SERVICE" in permissions)
        assertTrue("android.permission.ACCESS_MOCK_LOCATION" in permissions)
    }

    @Test
    fun migrationRetainsSelectedMockButErasesGenuineLocationAndOpenControls() {
        val config = preferences("mock_configuration")
        val legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("mockgps", Context.MODE_PRIVATE)
        restoring(config, legacy) {
            assertTrue(config.edit().clear().commit())
            assertTrue(legacy.edit().clear().putString("lat", "51.5").putString("lon", "-0.12")
                .putString("real_lat", "12.3").putString("real_lon", "45.6")
                .putBoolean("broadcast_enabled", true).putBoolean("auto_start", true)
                .putBoolean("desired_enabled", true).putBoolean("service_running", true).commit())
            State.migrate(context)
            assertEquals(51.5, State.getLat(context)!!, 0.0)
            assertEquals(-0.12, State.getLon(context)!!, 0.0)
            assertTrue(legacy.all.isEmpty())
            assertTrue(State.legacyProviderStateUnverified(context))
            assertFalse(config.contains("real_lat"))
            assertFalse(config.contains("auto_start"))
            assertFalse(config.contains("broadcast_enabled"))
            assertFalse(config.contains("desired_enabled"))
            // Migration is idempotent and cannot overwrite a subsequent explicit selection.
            assertTrue(config.edit().putString("lat", "48.0").commit())
            State.migrate(context)
            assertEquals(48.0, State.getLat(context)!!, 0.0)
            // A previously failed legacy purge is retried without overwriting the new selection.
            assertTrue(legacy.edit().putString("real_lat", "12.3").commit())
            State.migrate(context)
            assertTrue(legacy.all.isEmpty())
            assertEquals(48.0, State.getLat(context)!!, 0.0)
        }
    }

    @Test
    fun migrationRequiresReselectionForLegacyZeroFallbackOrInvalidCoordinates() {
        val config = preferences("mock_configuration")
        val legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("mockgps", Context.MODE_PRIVATE)
        restoring(config, legacy) {
            for ((lat, lon) in listOf("0.0" to "0.0", "NaN" to "1.0", "91.0" to "1.0")) {
                assertTrue(config.edit().clear().commit())
                assertTrue(legacy.edit().clear().putString("lat", lat).putString("lon", lon).commit())
                State.migrate(context)
                assertNull(State.getLat(context))
                assertNull(State.getLon(context))
            }
        }
    }

    @Test
    fun policyReloadDoesNotMistakePersistedLiveFlagsForRunningService() {
        val prefs = preferences("control_policy")
        restoring(prefs) {
            assertTrue(prefs.edit().clear().commit())
            val store = PolicyStore(context)
            store.save(PolicySnapshot(mode = OperatingMode.AUTO, desired = true,
                service = ServiceState.INJECTING, providers = ProviderState.READY,
                injection = InjectionState.CONFIRMED, result = "INJECTING", generation = 7,
                pendingAtElapsed = 200_000, lastSequence = 13, vehicle = VehicleState.DISCONNECTED,
                lastEventTime = 1234, delaySeconds = 300))
            val loaded = PolicyStore(context).load()
            assertEquals(OperatingMode.AUTO, loaded.mode)
            assertEquals(300L, loaded.delaySeconds)
            assertEquals(1234L, loaded.lastEventTime)
            assertEquals(13L, loaded.lastSequence)
            assertFalse(loaded.desired)
            assertEquals(ServiceState.UNKNOWN, loaded.service)
            assertEquals(ProviderState.UNKNOWN, loaded.providers)
            assertEquals(InjectionState.UNKNOWN, loaded.injection)
            assertEquals(VehicleState.UNKNOWN, loaded.vehicle)
            assertNull(loaded.pendingAtElapsed)
            val recovered = Policy.reduce(loaded, PolicyEvent.Recover, 1000)
            assertEquals(8L, recovered.generation)
            assertFalse(recovered.desired)
        }
    }

    @Test
    fun privateCapabilityIsOptInAndRotationInvalidatesPreviousCredential() {
        val prefs = preferences("automation_access")
        restoring(prefs) {
            assertTrue(prefs.edit().clear().commit())
            assertFalse(AutomationAccess.isEnabled(context))
            assertNull(AutomationAccess.token(context))
            AutomationAccess.setEnabled(context, true)
            val previous = AutomationAccess.token(context)
            assertEquals(64, previous!!.length)
            AutomationAccess.setEnabled(context, false)
            assertNull(AutomationAccess.token(context))
            assertFalse(prefs.contains("token"))
            AutomationAccess.setEnabled(context, true)
            assertNotEquals(previous, AutomationAccess.token(context))
        }
    }

    @Test
    fun actualOrderedReceiverRejectsDisabledUnauthorizedAndMalformedControl() {
        val access = preferences("automation_access")
        val config = preferences("mock_configuration")
        val policy = preferences("control_policy")
        val legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("mockgps", Context.MODE_PRIVATE)
        restoring(access, config, policy, legacy) {
            assertTrue(access.edit().clear().commit())
            val disabled = response(controlIntent("0".repeat(64)))
            assertEquals(1, disabled.first)
            assertEquals("DISABLED", disabled.second)
            AutomationAccess.setEnabled(context, true)
            val unauthorized = response(controlIntent("invalid"))
            assertEquals(1, unauthorized.first)
            assertEquals("UNAUTHORIZED", unauthorized.second)
            val malformed = response(controlIntent(AutomationAccess.token(context)!!).putExtra("boot", "on"))
            assertEquals(1, malformed.first)
            assertEquals("MALFORMED_REQUEST", malformed.second)
        }
    }

    @Test
    fun actualReceiverOrdersSameMillisecondEventsAndStatusCannotConsumeSequence() {
        val access = preferences("automation_access")
        val policy = preferences("control_policy")
        restoring(access, policy) {
            try {
                main { ControlCenter.setIntegrationEnabled(context, false) }
                main { ControlCenter.selectAuto(context); ControlCenter.setIntegrationEnabled(context, true) }
                val token = AutomationAccess.token(context)!!
                val time = System.currentTimeMillis()
                fun event(command: String, seq: Long) = controlIntent(token).putExtra("command", command)
                    .putExtra("event_time", time).putExtra("seq", seq)
                assertEquals(0, response(event("vehicle_disconnected", 1), true).first)
                assertNotNull(main { ControlCenter.snapshot(context) }.pendingAtElapsed)
                assertEquals(0, response(event("status", 999), true).first)
                assertEquals(1L, main { ControlCenter.snapshot(context) }.lastSequence)
                assertEquals(0, response(event("vehicle_connected", 2), true).first)
                val connected = main { ControlCenter.snapshot(context) }
                assertEquals(VehicleState.CONNECTED, connected.vehicle)
                assertNull(connected.pendingAtElapsed)
                assertFalse(connected.desired)
                assertEquals("REPLAYED_EVENT", response(event("vehicle_disconnected", 1)).second)
                assertEquals(0, response(event("stop", 3), true).first)
                assertEquals(OperatingMode.FORCE_REAL, main { ControlCenter.snapshot(context) }.mode)
            } finally { main { ControlCenter.setIntegrationEnabled(context, false); ControlCenter.forceReal(context) } }
        }
    }

    @Test
    fun serviceCallbacksCannotErasePersistedLegacyCleanupUncertainty() {
        val config = preferences("mock_configuration")
        val policy = preferences("control_policy")
        restoring(config, policy) {
            try {
                assertTrue(config.edit().putBoolean("legacy_unverified", true).commit())
                val generation = main { ControlCenter.snapshot(context) }.generation
                main { ControlCenter.serviceFailed(context, generation, "FOREGROUND_START_REJECTED", false) }
                assertEquals("CLEANUP_UNCERTAIN", main { ControlCenter.snapshot(context) }.result)
                main { ControlCenter.serviceStopped(context, generation, false) }
                val stopped = main { ControlCenter.snapshot(context) }
                assertEquals(ProviderState.UNKNOWN, stopped.providers)
                assertEquals(InjectionState.UNKNOWN, stopped.injection)
                assertEquals("CLEANUP_UNCERTAIN", stopped.result)
            } finally { assertTrue(config.edit().putBoolean("legacy_unverified", false).commit()); main { ControlCenter.forceReal(context) } }
        }
    }

    @Test
    fun unchangedCredentialDoesNotResetOrderingButRotationDoes() {
        val access = preferences("automation_access")
        val policy = preferences("control_policy")
        restoring(access, policy) {
            try {
                main { ControlCenter.setIntegrationEnabled(context, false); ControlCenter.setIntegrationEnabled(context, true) }
                val old = AutomationAccess.token(context)!!
                val time = System.currentTimeMillis()
                fun stop(token: String, seq: Long) = controlIntent(token).putExtra("command", "stop")
                    .putExtra("event_time", time).putExtra("seq", seq)
                assertEquals(0, response(stop(old, 100), true).first)
                main { ControlCenter.setIntegrationEnabled(context, true) }
                assertEquals("REPLAYED_EVENT", response(stop(old, 1)).second)
                main { ControlCenter.setIntegrationEnabled(context, false); ControlCenter.setIntegrationEnabled(context, true) }
                val fresh = AutomationAccess.token(context)!!
                assertNotEquals(old, fresh)
                assertEquals("UNAUTHORIZED", response(stop(old, 101)).second)
                assertEquals(0, response(stop(fresh, 1), true).first)
            } finally { main { ControlCenter.setIntegrationEnabled(context, false); ControlCenter.forceReal(context) } }
        }
    }

    private fun <T> main(block: () -> T): T {
        val value = java.util.concurrent.atomic.AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun controlIntent(token: String) = Intent(MockGpsReceiver.ACTION)
        .setComponent(ComponentName(context, MockGpsReceiver::class.java))
        .putExtra("api", 2).putExtra("token", token).putExtra("command", "status")
        .putExtra("event_time", System.currentTimeMillis()).putExtra("seq", 1L)

    private fun response(intent: Intent, accepted: Boolean = false): Pair<Int, String?> {
        val delivered = CountDownLatch(1)
        var result: Pair<Int, String?>? = null
        var hasPrivateExtras = false
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result = resultCode to resultData
                hasPrivateExtras = getResultExtras(false) != null
                delivered.countDown()
            }
        }, null, 99, null, null)
        assertTrue("Explicit ordered control broadcast did not complete", delivered.await(10, TimeUnit.SECONDS))
        assertEquals("Only authenticated calls return sanitized state", accepted, hasPrivateExtras)
        return requireNotNull(result)
    }

    private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Preserve any pre-existing settings even if a device test fails; never delete arbitrary app data. */
    private fun restoring(vararg preferences: SharedPreferences, block: () -> Unit) {
        val saved = preferences.map { it.all.toMap() }
        try { block() } finally {
            preferences.zip(saved).forEach { (prefs, values) ->
                val editor = prefs.edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                check(editor.commit())
            }
        }
    }
}
