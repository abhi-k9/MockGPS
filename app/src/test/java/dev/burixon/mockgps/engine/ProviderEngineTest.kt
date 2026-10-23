package dev.burixon.mockgps.engine

import org.junit.Assert.*
import org.junit.Test

class ProviderEngineTest {
    private val point = Coordinates(50.06143, 19.93658)
    private val fix = MockFix(point, 1_000L, 1_000_000L)

    private class FakePort : ProviderPort {
        val owned = mutableSetOf<MockProvider>()
        val added = mutableSetOf<MockProvider>()
        val enabled = mutableSetOf<MockProvider>()
        val fixes = mutableMapOf<MockProvider, MockFix>()
        val events = mutableListOf<String>()
        var failAdd: MockProvider? = null
        var failEnable: MockProvider? = null
        var failInject: MockProvider? = null
        var failRemove: MockProvider? = null
        var failDisable: MockProvider? = null
        var failJournal = false
        override fun ownedProviders() = owned.toSet()
        override fun markOwned(provider: MockProvider, owned: Boolean) {
            if (failJournal) throw IllegalStateException()
            events += "journal:${provider.name}:$owned"
            if (owned) this.owned += provider else this.owned -= provider
        }
        override fun add(provider: MockProvider) {
            events += "add:${provider.name}"
            check(provider in owned)
            if (failAdd == provider) throw SecurityException()
            added += provider
        }
        override fun enable(provider: MockProvider) {
            if (failEnable == provider) throw SecurityException()
            enabled += provider
        }
        override fun inject(provider: MockProvider, fix: MockFix) {
            if (failInject == provider) throw SecurityException()
            check(provider in enabled)
            fixes[provider] = fix
        }
        override fun disable(provider: MockProvider) {
            events += "disable:${provider.name}"
            if (failDisable == provider) throw SecurityException()
            enabled -= provider
        }
        override fun remove(provider: MockProvider) {
            events += "remove:${provider.name}"
            if (failRemove == provider) throw SecurityException()
            enabled -= provider
            added -= provider
        }
    }

    @Test fun bothProvidersAreReadyBeforeInjectionIsReported() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        val ready = engine.start(point)
        assertTrue(ready.success)
        assertEquals(2, ready.providersReady)
        assertEquals(0, ready.providersInjected)
        val injected = engine.publish(fix)
        assertEquals(2, injected.providersInjected)
        assertEquals(fix, port.fixes[MockProvider.GPS])
        assertEquals(fix, port.fixes[MockProvider.NETWORK])
        assertTrue(engine.stop().success)
        assertTrue(port.owned.isEmpty())
    }

    @Test fun coordinatesRejectNonFiniteAndOutOfRange() {
        val bad = listOf(Coordinates(Double.NaN, 0.0), Coordinates(0.0, Double.POSITIVE_INFINITY),
            Coordinates(90.0001, 0.0), Coordinates(0.0, -180.0001))
        for (coordinates in bad) {
            val port = FakePort()
            assertFalse(ProviderEngine(port).start(coordinates).success)
            assertTrue(port.events.isEmpty())
        }
        assertTrue(Coordinates(0.0, 0.0).valid)
    }

    @Test fun invalidRestartRemovesPreviousProvidersRatherThanLeavingAnOldSessionActive() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        assertTrue(engine.start(point).success)
        assertTrue(engine.publish(fix).success)
        assertEquals("INVALID_COORDINATES", engine.start(Coordinates(Double.NaN, 0.0)).code)
        assertTrue(port.owned.isEmpty())
        assertTrue(port.added.isEmpty())
        assertFalse(engine.publish(fix).success)
    }

    @Test fun creationFailureRollsBackPartiallyCreatedProviders() {
        val port = FakePort().apply { failAdd = MockProvider.NETWORK }
        val result = ProviderEngine(port).start(point)
        assertEquals("PROVIDER_CREATE_FAILED_NETWORK", result.code)
        assertFalse(result.cleanupUncertain)
        assertTrue(port.owned.isEmpty())
        assertTrue(port.added.isEmpty())
    }

    @Test fun enableFailureRollsBackTheNewProvider() {
        val port = FakePort().apply { failEnable = MockProvider.GPS }
        assertFalse(ProviderEngine(port).start(point).success)
        assertTrue(port.owned.isEmpty())
        assertTrue(port.added.isEmpty())
    }

    @Test fun ownershipIsDurableBeforeProviderCreation() {
        val port = FakePort()
        ProviderEngine(port).start(point)
        assertTrue(port.events.indexOf("journal:GPS:true") < port.events.indexOf("add:GPS"))
        assertTrue(port.events.indexOf("journal:NETWORK:true") < port.events.indexOf("add:NETWORK"))
    }

    @Test fun persistenceFailureDoesNotAddProvider() {
        val port = FakePort().apply { failJournal = true }
        assertFalse(ProviderEngine(port).start(point).success)
        assertTrue(port.added.isEmpty())
    }

    @Test fun injectionFailureReportsPartialAcceptanceAndCleansUp() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        engine.start(point)
        port.failInject = MockProvider.NETWORK
        val result = engine.publish(fix)
        assertFalse(result.success)
        assertEquals(1, result.providersInjected)
        assertEquals("PROVIDER_INJECTION_FAILED_NETWORK", result.code)
        assertTrue(port.owned.isEmpty())
    }

    @Test fun injectionAndCleanupFailureReportsUncertainty() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        engine.start(point)
        port.failInject = MockProvider.GPS
        port.failRemove = MockProvider.GPS
        assertTrue(engine.publish(fix).cleanupUncertain)
        assertEquals(setOf(MockProvider.GPS), port.owned)
    }

    @Test fun processRecoveryCleansOnlyJournaledProviders() {
        val port = FakePort().apply { owned += MockProvider.GPS; added += MockProvider.GPS }
        assertTrue(ProviderEngine(port).stop().success)
        assertEquals(listOf("disable:GPS", "remove:GPS", "journal:GPS:false"), port.events)
    }

    @Test fun disableFailureStillAttemptsRemoval() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        engine.start(point)
        port.failDisable = MockProvider.GPS
        assertTrue(engine.stop().success)
        assertTrue(port.owned.isEmpty())
    }

    @Test fun removalFailureRetainsOwnershipUntilUserRetries() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        engine.start(point)
        port.failRemove = MockProvider.GPS
        assertTrue(engine.stop().cleanupUncertain)
        assertEquals(setOf(MockProvider.GPS), port.owned)
        port.failRemove = null
        assertTrue(engine.stop().success)
        assertTrue(port.owned.isEmpty())
    }

    @Test fun uncertainRecoveryPreventsRecreation() {
        val port = FakePort().apply { owned += MockProvider.GPS; failRemove = MockProvider.GPS }
        assertEquals("RECOVERY_CLEANUP_FAILED", ProviderEngine(port).start(point).code)
        assertFalse(port.events.any { it.startsWith("add:") })
    }

    @Test fun rapidStartStopAndRepeatedStopAreIdempotent() {
        val port = FakePort()
        val engine = ProviderEngine(port)
        repeat(10) {
            assertTrue(engine.start(point).success)
            assertTrue(engine.publish(fix).success)
            assertTrue(engine.stop().success)
            assertTrue(engine.stop().success)
        }
        assertTrue(port.owned.isEmpty())
        assertFalse(engine.publish(fix).success)
    }

    @Test fun invalidTimestampOrAccuracyCannotBeInjected() {
        val bad = listOf(fix.copy(wallTimeMillis = 0), fix.copy(elapsedRealtimeNanos = 0),
            fix.copy(accuracyMeters = Float.NaN), fix.copy(accuracyMeters = 0f))
        for (value in bad) {
            val port = FakePort()
            val engine = ProviderEngine(port)
            engine.start(point)
            assertEquals("INVALID_LOCATION_METADATA", engine.publish(value).code)
            assertTrue(port.fixes.isEmpty())
            assertTrue(port.owned.isEmpty())
        }
    }

    @Test fun driftRemainsFiniteAtPolesAndAntimeridian() {
        val bases = listOf(Coordinates(90.0, 180.0), Coordinates(-90.0, -180.0),
            Coordinates(0.0, 180.0), Coordinates(89.999999, -179.999999))
        for (base in bases) {
            for (angle in 0..359) {
                val result = base.offset(15.0, Math.toRadians(angle.toDouble()))
                assertTrue("$base -> $result", result.valid)
            }
        }
    }
}
