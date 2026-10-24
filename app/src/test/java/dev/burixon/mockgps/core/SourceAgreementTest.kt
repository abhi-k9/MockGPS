package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class SourceAgreementTest {
    private class DurableAuthorityStore : ShortcutAuthorityStore {
        var values = emptyMap<String, String>()
        var unavailable = false
        override fun read(): Map<String, String> {
            check(!unavailable)
            return values
        }
        override fun write(commands: Map<String, String>) {
            check(!unavailable)
            values = commands.toMap()
        }
    }

    @Test fun bothSourcesMustAgreeAndSafetyInvalidatesOldAgreement() {
        var s = SourceAgreement(true, true).external(ReadinessState.READY)
        assertEquals(ReadinessState.UNKNOWN, s.readiness)
        s = s.shortcuts(ReadinessState.READY)
        assertEquals(ReadinessState.READY, s.readiness)
        s = s.external(ReadinessState.BLOCKED)
        assertEquals(ReadinessState.BLOCKED, s.readiness)
        assertEquals(ReadinessState.UNKNOWN, s.shortcuts)
        assertEquals(ReadinessState.UNKNOWN, s.external(ReadinessState.READY).readiness)
    }
    @Test fun shortcutSafetyCancelsExternalAgreementAndRecoveryIsUnknown() {
        val s = SourceAgreement(true, true, ReadinessState.READY, ReadinessState.READY)
        for (v in listOf(ReadinessState.UNKNOWN, ReadinessState.BLOCKED)) {
            val next = s.shortcuts(v)
            assertEquals(ReadinessState.UNKNOWN, next.external)
            assertNotEquals(ReadinessState.READY, next.readiness)
        }
        assertEquals(ReadinessState.UNKNOWN, SourceAgreement(true, true).readiness)
        assertEquals(ReadinessState.READY, SourceAgreement(false, true).shortcuts(ReadinessState.READY).readiness)
    }
    @Test fun anotherSourcesUnknownOrRequestRequestCannotEraseBlocked() {
        val paused = SourceAgreement(true, true, ReadinessState.BLOCKED, ReadinessState.BLOCKED)
        assertEquals(ReadinessState.BLOCKED, paused.external(ReadinessState.UNKNOWN).readiness)
        assertEquals(ReadinessState.BLOCKED, paused.shortcuts(ReadinessState.UNKNOWN).readiness)
        assertEquals(ReadinessState.BLOCKED, paused.simulationRequest().readiness)
        assertEquals(ReadinessState.BLOCKED, paused.simulationRequest().shortcuts)
        val externalOnlyBlocked = paused.copy(shortcuts = ReadinessState.UNKNOWN)
        assertEquals(ReadinessState.BLOCKED, externalOnlyBlocked.simulationRequest().external)
        // Only this source's own fresh report/review can clear its paused latch.
        assertEquals(ReadinessState.READY, paused.external(ReadinessState.READY).shortcuts(ReadinessState.READY).readiness)
    }
    @Test fun hostAuthorityIsStrictSingleUseAndRotationRevokes() {
        val a = ShortcutAuthority()
        a.publish(mapOf("stop" to "one", "request_simulation" to "two"))
        assertFalse(a.consume("stop", 1))
        assertFalse(a.consume("other", "one"))
        assertFalse(a.consume("stop", "wrong"))
        assertTrue(a.consume("stop", "one"))
        assertFalse(a.consume("stop", "one"))
        assertFalse(ShortcutAuthority().consume("stop", "one")) // recovered process has no retained authority
        a.publish(mapOf("request_simulation" to "new"))
        assertFalse(a.consume("request_simulation", "two"))
        a.publish(emptyMap())
        assertFalse(a.consume("request_simulation", "new"))
    }

    @Test fun publishedAuthoritySurvivesProcessReconstructionButConsumedNonceDoesNot() {
        val store = DurableAuthorityStore()
        ShortcutAuthority(store).publish(mapOf("stop" to "one", "request_simulation" to "two"))
        assertTrue(ShortcutAuthority(store).consume("stop", "one"))
        assertFalse(ShortcutAuthority(store).consume("stop", "one"))
        assertTrue(ShortcutAuthority(store).consume("request_simulation", "two"))
        assertTrue(store.values.isEmpty())
    }

    @Test fun persistedRotationAndRevocationRejectOldCapabilitiesInAnotherProcess() {
        val store = DurableAuthorityStore()
        val first = ShortcutAuthority(store)
        first.publish(mapOf("stop" to "one"))
        ShortcutAuthority(store).publish(mapOf("stop" to "new"))
        assertFalse(first.consume("stop", "one"))
        first.publish(emptyMap())
        assertFalse(ShortcutAuthority(store).consume("stop", "new"))
    }

    @Test fun unavailableAuthorityStoreCannotAuthorizeACommand() {
        val store = DurableAuthorityStore()
        val authority = ShortcutAuthority(store)
        authority.publish(mapOf("stop" to "one"))
        store.unavailable = true
        assertTrue(runCatching { authority.consume("stop", "one") }.isFailure)
        store.unavailable = false
        assertTrue(authority.consume("stop", "one"))
        assertFalse(ShortcutAuthority(store).consume("stop", "one"))
    }
    @Test fun sourceSafetyCancelsManualDelayAndConfirmationWithoutChangingExternalCounters() {
        val manual = PolicySnapshot(mode = OperatingMode.MANUAL_MOCK, desired = true, lastSequence = 9, lastEventTime = 100)
        for (v in listOf(ReadinessState.UNKNOWN, ReadinessState.BLOCKED)) {
            val safe = Policy.reduce(manual, PolicyEvent.SourceReadiness(v), 0)
            assertFalse(safe.desired)
            assertEquals(9L, safe.lastSequence)
            assertEquals(100L, safe.lastEventTime)
            assertNull(safe.pendingAtElapsed)
        }
        val pending = Policy.reduce(PolicySnapshot(mode = OperatingMode.AUTO, delaySeconds = 2), PolicyEvent.SourceReadiness(ReadinessState.READY), 0)
        assertEquals(2000L, pending.pendingAtElapsed)
        val ready = Policy.reduce(pending, PolicyEvent.Deadline(pending.generation), 2000)
        assertFalse(ready.desired)
        assertTrue(ready.needsConfirmation)
        val cancelled = Policy.reduce(ready, PolicyEvent.SourceReadiness(ReadinessState.BLOCKED), 2001)
        assertFalse(Policy.reduce(cancelled, PolicyEvent.Confirm(ready.generation), 2002).desired)
        val forced = PolicySnapshot(mode = OperatingMode.FORCE_REAL)
        assertNull(Policy.reduce(forced, PolicyEvent.SourceReadiness(ReadinessState.READY), 0).pendingAtElapsed)
    }
}
