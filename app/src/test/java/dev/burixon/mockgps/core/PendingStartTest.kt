package dev.burixon.mockgps.core

import dev.burixon.mockgps.engine.*
import org.junit.Assert.*
import org.junit.Test

class PendingStartTest {
    private class Providers : ProviderPort {
        val owned = mutableSetOf<MockProvider>()
        val active = mutableSetOf<MockProvider>()
        val injected = mutableListOf<MockProvider>()
        var failCleanup = false
        override fun ownedProviders(): Set<MockProvider> = owned.toSet()
        override fun markOwned(provider: MockProvider, owned: Boolean) {
            if (owned) this.owned += provider else this.owned -= provider
        }
        override fun add(provider: MockProvider) { active += provider }
        override fun enable(provider: MockProvider) { check(provider in active) }
        override fun inject(provider: MockProvider, fix: MockFix) {
            check(provider in active)
            injected += provider
        }
        override fun disable(provider: MockProvider) = Unit
        override fun remove(provider: MockProvider) {
            check(!failCleanup)
            active -= provider
        }
    }

    /** Real policy, persistence/deadline orchestration and provider engine; clock/alarm/platform are fake. */
    private class Session {
        val providers = Providers()
        val engine = ProviderEngine(providers)
        var state = PolicySnapshot(mode = OperatingMode.AUTO, unattendedApproval = true,
            service = ServiceState.STOPPED, providers = ProviderState.NONE, injection = InjectionState.NONE,
            delaySeconds = 5)
        var sources = SourceAgreement(shortcutsEnabled = true)
        var now = 10_000L
        var alarm: Pair<Long, Long>? = null
        var failSave = false
        var afterSave: (() -> Unit)? = null
        val registrations = mutableListOf<Pair<Long, Long>>()

        fun command(command: AutomationCommand): Boolean {
            sources = if (command == AutomationCommand.REQUEST_SIMULATION) sources.simulationRequest(true)
                else sources.shortcuts(requireNotNull(command.readinessState))
            val next = Policy.reduce(state, PolicyEvent.SourceReadiness(sources.readiness), now)
            return PendingStart.commit(next, persistAndStop = {
                alarm = null
                if (failSave) return@commit false
                state = it.copy(desired = false)
                val cleanup = engine.stop()
                state = state.copy(service = if (cleanup.success) ServiceState.STOPPED else ServiceState.ERROR,
                    providers = if (cleanup.success) ProviderState.NONE else ProviderState.UNKNOWN,
                    injection = if (cleanup.success) InjectionState.NONE else InjectionState.UNKNOWN)
                afterSave?.invoke()
                cleanup.success
            }, current = { state }, schedule = {
                // Registration must observe the committed state, never an unpersisted proposal.
                assertEquals(state, it)
                assertFalse(state.desired)
                val entry = it.generation to requireNotNull(it.pendingAtElapsed)
                alarm = entry
                registrations += entry
            })
        }

        fun advanceTo(time: Long) {
            now = time
            val due = alarm ?: return
            if (now >= due.second) {
                alarm = null
                deliver(due.first)
            }
        }

        fun deliver(generation: Long) {
            val next = Policy.reduce(state, PolicyEvent.Deadline(generation), now)
            if (next == state) return
            state = next
            if (state.desired) {
                val point = Coordinates(0.01, 0.01)
                assertTrue(engine.start(point).success)
                assertTrue(engine.publish(MockFix(point, 1_000_000 + now, now * 1_000_000)).success)
                state = state.copy(service = ServiceState.INJECTING, providers = ProviderState.READY,
                    injection = InjectionState.CONFIRMED)
            }
        }
    }

    @Test fun pauseRequestAlarmAndInjectionWorkForTwoCompleteCycles() {
        val s = Session()
        repeat(2) { cycle ->
            assertTrue(s.command(AutomationCommand.PAUSE))
            assertTrue(s.providers.active.isEmpty())
            assertNull(s.alarm)
            assertTrue(s.command(AutomationCommand.REQUEST_SIMULATION))
            val deadline = requireNotNull(s.alarm) { "Request must register an alarm, not only save PENDING" }
            assertEquals(s.now + 5_000, deadline.second)
            assertEquals(cycle * 2, s.providers.injected.size)
            s.advanceTo(deadline.second - 1)
            assertFalse(s.state.desired)
            assertTrue(s.providers.active.isEmpty())
            s.advanceTo(deadline.second)
            assertEquals(InjectionState.CONFIRMED, s.state.injection)
            assertEquals(MockProvider.entries.toSet(), s.providers.active)
            assertEquals(listOf(MockProvider.GPS, MockProvider.NETWORK), s.providers.injected.takeLast(2))
            assertEquals((cycle + 1) * 2, s.providers.injected.size)
        }
        assertEquals(2, s.registrations.size)
        assertTrue(s.registrations[1].first > s.registrations[0].first)
    }

    @Test fun failedPersistenceCannotRegisterAnAlarmOrInject() {
        val s = Session().apply { failSave = true }
        assertFalse(s.command(AutomationCommand.REQUEST_SIMULATION))
        assertTrue(s.registrations.isEmpty())
        s.advanceTo(99_000)
        assertTrue(s.providers.injected.isEmpty())
    }

    @Test fun cleanupFailureCannotRegisterAReplacementAlarm() {
        val s = Session()
        assertTrue(s.engine.start(Coordinates(0.01, 0.01)).success)
        s.providers.failCleanup = true
        assertFalse(s.command(AutomationCommand.REQUEST_SIMULATION))
        assertTrue(s.registrations.isEmpty())
        assertEquals(ServiceState.ERROR, s.state.service)
    }

    @Test fun forceRealDuringCommitPreventsRegistrationAndStaleAlarmInjection() {
        val s = Session()
        s.afterSave = { s.state = Policy.reduce(s.state, PolicyEvent.SelectMode(OperatingMode.FORCE_REAL), s.now) }
        assertTrue(s.command(AutomationCommand.REQUEST_SIMULATION))
        assertTrue(s.registrations.isEmpty())
        s.deliver(s.state.generation - 1)
        assertFalse(s.state.desired)
        assertTrue(s.providers.injected.isEmpty())
    }

    @Test fun invalidatedCommittedStateCannotRegisterAnAlarmEvenWithTheSameGeneration() {
        val changes: List<(PolicySnapshot) -> PolicySnapshot> = listOf(
            { it.copy(pendingAtElapsed = null) },
            { it.copy(service = ServiceState.ERROR) },
            { it.copy(readiness = ReadinessState.BLOCKED) },
        )
        for (change in changes) {
            val s = Session()
            s.afterSave = { s.state = change(s.state) }
            assertTrue(s.command(AutomationCommand.REQUEST_SIMULATION))
            assertTrue(s.registrations.isEmpty())
        }
    }

    @Test fun cancelledDelayDoesNotRestartInjectionAndNextRequestCanComplete() {
        val s = Session()
        assertTrue(s.command(AutomationCommand.REQUEST_SIMULATION))
        val cancelled = requireNotNull(s.alarm)
        assertTrue(s.command(AutomationCommand.PAUSE))
        s.advanceTo(cancelled.second)
        s.deliver(cancelled.first) // a callback already queued before cancellation is still harmless
        assertTrue(s.providers.injected.isEmpty())
        assertTrue(s.command(AutomationCommand.REQUEST_SIMULATION))
        val retry = requireNotNull(s.alarm)
        s.advanceTo(retry.second)
        assertEquals(InjectionState.CONFIRMED, s.state.injection)
    }
}
