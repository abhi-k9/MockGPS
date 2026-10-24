package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test

class TilePresentationTest {
    @Test fun forceRealReportsPolicyWithoutClaimingGenuineFix() {
        val tile = TilePresentation.from(PolicySnapshot(
            mode = OperatingMode.FORCE_REAL, service = ServiceState.STOPPED,
            providers = ProviderState.NONE, injection = InjectionState.NONE, result = "STOPPED"
        ))
        assertEquals("FORCE_REAL", tile.label)
        assertTrue(tile.active)
        assertTrue(tile.status.contains("genuine fix not verified"))
        assertEquals(TileAction.RETURN_AUTO, tile.action)
        assertTrue(tile.requiresUnlock)
    }

    @Test fun autoPendingNeverReportsInjectionAndKeepsSafetyStopAvailable() {
        val tile = TilePresentation.from(PolicySnapshot(
            mode = OperatingMode.AUTO, service = ServiceState.STOPPED,
            pendingAtElapsed = 120_000, result = "PENDING"
        ))
        assertEquals("AUTO", tile.label)
        assertFalse(tile.active)
        assertEquals("Waiting for automation delay", tile.status)
        assertEquals(TileAction.FORCE_REAL, tile.action)
        assertFalse(tile.requiresUnlock)
    }

    @Test fun confirmedManualInjectionIsDistinctFromForceRealSelection() {
        val tile = TilePresentation.from(PolicySnapshot(
            mode = OperatingMode.MANUAL_MOCK, desired = true,
            service = ServiceState.INJECTING, providers = ProviderState.READY,
            injection = InjectionState.CONFIRMED, result = "INJECTING"
        ))
        assertEquals("MANUAL_MOCK", tile.label)
        assertEquals("Injecting simulated location", tile.status)
        assertFalse(tile.active)
        assertEquals(TileAction.FORCE_REAL, tile.action)
    }

    @Test fun cleanupUncertaintySurvivesProjectionAndRetainsMode() {
        val tile = TilePresentation.from(PolicySnapshot(
            mode = OperatingMode.FORCE_REAL, service = ServiceState.ERROR,
            result = "CLEANUP_UNCERTAIN"
        ))
        assertEquals("FORCE_REAL", tile.label)
        assertTrue(tile.active)
        assertTrue(tile.status.contains("uncertain"))
        assertFalse(tile.status.contains("Injection stopped"))
    }

    @Test fun observationsRefreshWithoutChangingSelectedMode() {
        val starting = PolicySnapshot(mode = OperatingMode.AUTO, desired = true,
            service = ServiceState.STARTING, result = "STARTING")
        val a = TilePresentation.from(starting)
        val b = TilePresentation.from(starting.copy(service = ServiceState.INJECTING,
            providers = ProviderState.READY, injection = InjectionState.CONFIRMED))
        assertEquals(a.label, b.label)
        assertEquals(a.active, b.active)
        assertNotEquals(a.status, b.status)
        assertEquals(TileAction.FORCE_REAL, b.action)
    }

    @Test fun everyModeHasAnExplicitSafeAction() {
        for (mode in OperatingMode.entries) {
            val tile = TilePresentation.from(PolicySnapshot(mode = mode))
            assertEquals(mode.name, tile.label)
            assertEquals(mode == OperatingMode.FORCE_REAL, tile.requiresUnlock)
            if (mode != OperatingMode.FORCE_REAL) assertEquals(TileAction.FORCE_REAL, tile.action)
        }
    }
}
