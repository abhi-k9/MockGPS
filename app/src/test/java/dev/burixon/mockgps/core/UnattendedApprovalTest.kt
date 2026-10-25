package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class UnattendedApprovalTest {
    private fun pending(unattended: Boolean = true): PolicySnapshot {
        val base = PolicySnapshot(mode = OperatingMode.AUTO, unattendedApproval = unattended, delaySeconds = 5)
        return Policy.reduce(base, PolicyEvent.Readiness(ReadinessState.READY, 100, 1), 1_000)
    }
    private fun due(s: PolicySnapshot) = Policy.reduce(s, PolicyEvent.Deadline(s.generation), 6_000)

    @Test fun manualApprovalIsTheDefaultAndDoesNotStartAtDeadline() {
        assertFalse(PolicySnapshot().unattendedApproval)
        val result = due(pending(false))
        assertTrue(result.needsConfirmation)
        assertFalse(result.desired)
    }

    @Test fun optedInRequestStartsOnlyAtCurrentDeadlineWithoutClaimingInjection() {
        val p = pending()
        assertEquals(p, Policy.reduce(p, PolicyEvent.Deadline(p.generation), 5_999))
        assertEquals(p, Policy.reduce(p, PolicyEvent.Deadline(p.generation - 1), 6_000))
        val result = due(p)
        assertTrue(result.desired)
        assertEquals(ServiceState.STARTING, result.service)
        assertEquals(InjectionState.UNKNOWN, result.injection)
        assertFalse(result.needsConfirmation)
        assertNull(result.pendingAtElapsed)
        assertEquals(result, due(result)) // a duplicate alarm cannot request a second start
    }

    @Test fun forceRealOverridesOptInNewRequestsAndEveryOldActivation() {
        val p = pending()
        val stopped = Policy.reduce(p, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 2_000)
        val request = Policy.reduce(stopped, PolicyEvent.Readiness(ReadinessState.READY, 200, 2), 3_000)
        assertEquals(OperatingMode.FORCE_REAL, request.mode)
        assertFalse(request.desired)
        assertNull(request.pendingAtElapsed)
        assertEquals(request, Policy.reduce(request, PolicyEvent.Deadline(p.generation), 9_000))
        assertEquals(request, Policy.reduce(request, PolicyEvent.Confirm(p.generation), 9_000))
        assertEquals(request, Policy.reduce(request, PolicyEvent.ManualStart, 9_000))
    }

    @Test fun pauseAndUnknownCancelPendingAndActiveUnattendedSessions() {
        val p = pending()
        for (before in listOf(p, due(p))) for (readiness in listOf(ReadinessState.BLOCKED, ReadinessState.UNKNOWN)) {
            val safe = Policy.reduce(before, PolicyEvent.Readiness(readiness, 200, 2), 7_000)
            assertFalse(safe.desired)
            assertNull(safe.pendingAtElapsed)
            assertTrue(safe.generation > before.generation)
            assertEquals(safe, Policy.reduce(safe, PolicyEvent.Deadline(before.generation), 10_000))
        }
    }

    @Test fun changingConsentInvalidatesExistingRequestsAndPreservesOverrideAndReplayCounters() {
        for (enabled in listOf(false, true)) {
            val before = pending(!enabled)
            val changed = Policy.reduce(before, PolicyEvent.UnattendedApproval(enabled), 2_000)
            assertEquals(enabled, changed.unattendedApproval)
            assertFalse(changed.desired)
            assertNull(changed.pendingAtElapsed)
            assertEquals(ReadinessState.UNKNOWN, changed.readiness)
            assertEquals(before.lastSequence, changed.lastSequence)
            assertEquals(before.lastEventTime, changed.lastEventTime)
            assertEquals(changed, Policy.reduce(changed, PolicyEvent.Deadline(before.generation), 20_000))
            val forced = before.copy(mode = OperatingMode.FORCE_REAL)
            assertEquals(OperatingMode.FORCE_REAL,
                Policy.reduce(forced, PolicyEvent.UnattendedApproval(enabled), 2_000).mode)
        }
    }

    @Test fun disablingUnattendedApprovalStopsAnAlreadyRequestedStart() {
        val starting = due(pending())
        val stopped = Policy.reduce(starting, PolicyEvent.UnattendedApproval(false), 7_000)
        assertFalse(stopped.desired)
        assertFalse(stopped.unattendedApproval)
        assertTrue(stopped.generation > starting.generation)
        assertEquals(stopped, Policy.reduce(stopped, PolicyEvent.Deadline(starting.generation), 10_000))
    }

    @Test fun restartRetainsConsentButNeverRestoresReadinessOrInjection() {
        val recovered = Policy.reduce(due(pending()), PolicyEvent.Recover, 0)
        assertTrue(recovered.unattendedApproval)
        assertFalse(recovered.desired)
        assertEquals(ReadinessState.UNKNOWN, recovered.readiness)
        assertNull(recovered.pendingAtElapsed)
        assertEquals(InjectionState.UNKNOWN, recovered.injection)
        assertEquals(recovered, due(recovered))
    }

    @Test fun unavailableKeeperFallsBackToExplicitConfirmationWithoutClaimingStart() {
        val starting = due(pending())
        val fallback = Policy.reduce(starting, PolicyEvent.AutomationUnavailable(starting.generation), 7_000)
        assertFalse(fallback.desired)
        assertEquals(ServiceState.STOPPED, fallback.service)
        assertTrue(fallback.needsConfirmation)
        assertFalse(fallback.displayStatus().contains("Injecting"))
        val manual = Policy.reduce(fallback, PolicyEvent.Confirm(fallback.generation), 8_000)
        assertTrue(manual.desired)
        val forced = Policy.reduce(fallback, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), 8_000)
        assertEquals(forced, Policy.reduce(forced, PolicyEvent.AutomationUnavailable(starting.generation), 9_000))
        assertEquals(forced, Policy.reduce(forced, PolicyEvent.Confirm(fallback.generation), 9_000))
    }

    @Test fun freshRequestAfterUnavailableSessionCanRetryWhileActiveDuplicatesCannotRestartIt() {
        val starting = due(pending())
        val fallback = Policy.reduce(starting, PolicyEvent.AutomationUnavailable(starting.generation), 7_000)
        val retry = Policy.reduce(fallback, PolicyEvent.Readiness(ReadinessState.READY, 200, 2), 8_000)
        assertFalse(retry.desired)
        assertEquals(13_000L, retry.pendingAtElapsed)
        assertTrue(retry.generation > fallback.generation)
        assertFalse(retry.needsConfirmation)
        val active = starting.copy(service = ServiceState.INJECTING, injection = InjectionState.CONFIRMED)
        val repeated = Policy.reduce(active, PolicyEvent.Readiness(ReadinessState.READY, 200, 2), 8_000)
        assertEquals(active.generation, repeated.generation)
        assertTrue(repeated.desired)
        assertNull(repeated.pendingAtElapsed)
    }

    @Test fun shortcutOptInApprovesOnlyItsOwnSourceWithoutOverridingOtherSourceVetoes() {
        val blocked = SourceAgreement(true, true, ReadinessState.BLOCKED, ReadinessState.BLOCKED)
        val manual = blocked.simulationRequest()
        assertEquals(ReadinessState.BLOCKED, manual.shortcuts)
        val auto = blocked.simulationRequest(true)
        assertEquals(ReadinessState.READY, auto.shortcuts)
        assertEquals(ReadinessState.BLOCKED, auto.readiness)
        assertEquals(ReadinessState.UNKNOWN, auto.external(ReadinessState.UNKNOWN).readiness)
        assertEquals(ReadinessState.READY, auto.external(ReadinessState.READY).readiness)
    }
}
