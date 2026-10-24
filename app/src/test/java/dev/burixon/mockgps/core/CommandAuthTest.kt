package dev.burixon.mockgps.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CommandAuthTest {
    private val token = "0123456789abcdef".repeat(4)
    private val now = 1_700_000_100_000L
    private fun request(command: String? = "vehicle_connected", time: Long = now) =
        ExternalRequest(2, token, command, time)
    private fun authorize(r: ExternalRequest = request(), enabled: Boolean = true,
        expected: String? = token, time: Long = now, last: Long = -1) =
        CommandAuth.authorize(enabled, expected, r, time, last)
    private fun assertRejected(code: String, result: AuthResult) {
        assertFalse(result.accepted)
        assertEquals(code, result.code)
    }

    @Test fun everyDocumentedCommandCanBeAuthenticated() {
        for (command in listOf("vehicle_connected", "vehicle_disconnected", "vehicle_unknown", "stop", "status")) {
            assertEquals(AuthResult(true, "ACCEPTED"), authorize(request(command)))
        }
    }

    @Test fun disabledIntegrationRejectsEvenValidCredential() {
        assertRejected("DISABLED", authorize(enabled = false))
        assertRejected("DISABLED", authorize(request().copy(token = null), enabled = false))
    }

    @Test fun unsupportedProtocolVersionsAreRejected() {
        for (api in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertRejected("UNSUPPORTED_API", authorize(request().copy(api = api)))
        }
    }

    @Test fun missingOrRevokedCredentialCannotAuthorize() {
        assertRejected("UNAUTHORIZED", authorize(request().copy(token = null)))
        assertRejected("UNAUTHORIZED", authorize(expected = null))
        assertRejected("UNAUTHORIZED", authorize(expected = "f".repeat(64)))
    }

    @Test fun malformedTokensAreRejected() {
        for (supplied in listOf("", "0".repeat(63), "0".repeat(65), "g".repeat(64),
            token.uppercase(), " ".repeat(64), "é".repeat(64), "0".repeat(63) + "\n")) {
            assertRejected("UNAUTHORIZED", authorize(request().copy(token = supplied)))
        }
    }

    @Test fun wrongTokenAtAnyPositionCannotAuthorize() {
        for (index in token.indices) {
            val chars = token.toCharArray()
            chars[index] = if (chars[index] == '0') '1' else '0'
            assertRejected("UNAUTHORIZED", authorize(request().copy(token = chars.concatToString())))
        }
    }

    @Test fun malformedStoredTokenCannotAuthorize() {
        for (expected in listOf("", "0".repeat(63), "0".repeat(65), "z".repeat(64), token.uppercase())) {
            assertRejected("UNAUTHORIZED", authorize(expected = expected))
        }
    }

    @Test fun unknownCommandsAndLegacyStartCannotEscalateMode() {
        for (command in listOf(null, "", "on", "set", "drift", "boot", "force_mock", "manual_start",
            "AUTO", "vehicle_connected ", "VEHICLE_CONNECTED", "stop\n")) {
            assertRejected("UNSUPPORTED_COMMAND", authorize(request(command)))
        }
    }

    @Test fun credentialIsRequiredForStatusAndStopToo() {
        for (command in listOf("status", "stop")) {
            assertRejected("UNAUTHORIZED", authorize(request(command).copy(token = "0".repeat(64))))
        }
    }

    @Test fun freshnessBoundaryAllowsSixtySecondsAndRejectsOlder() {
        assertTrue(authorize(request(time = now - 60_000)).accepted)
        assertRejected("EXPIRED_EVENT", authorize(request(time = now - 60_001)))
        assertRejected("EXPIRED_EVENT", authorize(request(time = 0)))
    }

    @Test fun futureEventsAreRejectedToPreventTimestampPoisoning() {
        assertRejected("EXPIRED_EVENT", authorize(request(time = now + 1)))
        assertRejected("EXPIRED_EVENT", authorize(request(time = Long.MAX_VALUE)))
    }

    @Test fun negativeAndOverflowTimestampsCannotAppearFresh() {
        for (eventTime in listOf(Long.MIN_VALUE, -1L)) {
            assertRejected("EXPIRED_EVENT", authorize(request(time = eventTime)))
        }
        assertRejected("EXPIRED_EVENT", authorize(request(time = 0), time = Long.MAX_VALUE))
        assertRejected("EXPIRED_EVENT", authorize(request(time = Long.MAX_VALUE - 60_001), time = Long.MAX_VALUE))
        assertTrue(authorize(request(time = Long.MAX_VALUE - 60_000), time = Long.MAX_VALUE).accepted)
        assertRejected("EXPIRED_EVENT", authorize(request(time = 0), time = Long.MIN_VALUE))
    }

    @Test fun duplicatesAndOutOfOrderMutationsAreRejected() {
        for (command in listOf("vehicle_connected", "vehicle_disconnected", "vehicle_unknown", "stop")) {
            assertRejected("REPLAYED_EVENT", authorize(request(command).copy(sequence = 8), last = 8))
            assertRejected("REPLAYED_EVENT", authorize(request(command).copy(sequence = 7), last = 8))
            assertTrue(authorize(request(command).copy(sequence = 9), last = 8).accepted)
        }
    }

    @Test fun readOnlyStatusCannotConsumeOrPoisonMutationOrdering() {
        assertTrue(authorize(request("status").copy(sequence = 0), last = 100).accepted)
        assertTrue(authorize(request("vehicle_connected").copy(sequence = 101), last = 100).accepted)
    }

    @Test fun simultaneousEventsUseSequenceRatherThanWallClockOrdering() {
        val first = request("vehicle_disconnected").copy(sequence = 1)
        val reconnect = request("vehicle_connected").copy(sequence = 2)
        assertTrue(authorize(first).accepted)
        assertTrue(authorize(reconnect, last = first.sequence).accepted)
        assertRejected("REPLAYED_EVENT", authorize(first, last = reconnect.sequence))
    }

    @Test fun clockRollbackDoesNotBlockFreshHigherSequence() {
        assertTrue(authorize(request(time = now - 100_000).copy(sequence = 2),
            time = now - 100_000, last = 1).accepted)
    }

    @Test fun invalidSequencesDoNotWrapOrPoisonEpoch() {
        for (sequence in listOf(Long.MIN_VALUE, -1, Long.MAX_VALUE)) {
            assertRejected("INVALID_SEQUENCE", authorize(request().copy(sequence = sequence)))
        }
        assertTrue(authorize(request().copy(sequence = Long.MAX_VALUE - 1)).accepted)
    }

    @Test fun parserRequiresExactKeysAndAndroidPrimitiveTypes() {
        val valid = mapOf("api" to 2, "token" to token, "command" to "status", "event_time" to now, "seq" to 1L)
        assertEquals(ExternalRequest(2, token, "status", now, 1), CommandAuth.parse(valid))
        for (key in valid.keys) assertNull(CommandAuth.parse(valid - key))
        assertNull(CommandAuth.parse(valid + ("boot" to "on")))
        for ((key, value) in listOf("api" to "2", "api" to 2L, "seq" to "1", "seq" to 1,
            "event_time" to now.toString(), "token" to 1, "command" to null)) {
            assertNull(CommandAuth.parse(valid + (key to value)))
        }
    }

    @Test fun concurrentPureAuthorizationHasNoSharedMutableState() {
        // Acceptance/sequence persistence belongs to the serialized controller; this checks only the pure validator.
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 100).map { index -> Callable {
                if (index % 2 == 0) authorize() else authorize(request().copy(token = null))
            } }
            val results = executor.invokeAll(jobs).map { it.get() }
            assertEquals(50, results.count { it.accepted })
            assertEquals(50, results.count { it.code == "UNAUTHORIZED" })
        } finally {
            executor.shutdownNow()
        }
    }
}
