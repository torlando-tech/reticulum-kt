package network.reticulum.transport

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for columba#1222: the packet dedup windows must stay inside
 * the platform budget and rotate without copying one window into the other.
 *
 * The reported Android OOM was `ConcurrentHashMap$KeySetView.addAll` inside
 * `Transport.addPacketHash`, on a phone whose whole heap was 201 MB: the flat
 * 1M-entry budget ignored `Platform.recommendedHashlistSize` (50k on Android)
 * and rotation copied a full window at exactly the wrong moment.
 */
@DisplayName("Transport packet hashlist budget (columba#1222)")
class TransportHashlistBudgetTest {
    @AfterEach
    fun teardown() {
        Transport.setHashlistLimitForTest(null)
        Transport.clearPacketHashlist()
    }

    private fun hash(index: Int): ByteArray =
        ByteArray(32).also { bytes ->
            bytes[0] = (index and 0xFF).toByte()
            bytes[1] = ((index ushr 8) and 0xFF).toByte()
            bytes[2] = ((index ushr 16) and 0xFF).toByte()
            bytes[3] = ((index ushr 24) and 0xFF).toByte()
        }

    @Test
    @DisplayName("both dedup windows stay within twice the configured budget")
    fun windowsStayWithinBudget() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)

        repeat(BUDGET * 3) { Transport.addPacketHashForTest(hash(it)) }

        assertTrue(
            Transport.hashlistTotalSizeForTest() <= BUDGET * 2,
            "retained ${Transport.hashlistTotalSizeForTest()} entries must fit two ${BUDGET}-entry windows",
        )
    }

    @Test
    @DisplayName("rotation keeps the previous window for dedup and drops the window before it")
    fun rotationKeepsOnePreviousWindow() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)

        // Window 1.
        val firstHash = hash(0)
        repeat(BUDGET) { Transport.addPacketHashForTest(hash(it)) }
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "first window must be deduped")

        // Window 2 starts with a fresh set and the filled window becomes previous.
        Transport.addPacketHashForTest(hash(BUDGET))
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "previous window still dedups")

        // Window 3 rotates the older window out entirely.
        repeat(BUDGET) { Transport.addPacketHashForTest(hash(BUDGET * 2 + it)) }
        Transport.addPacketHashForTest(hash(BUDGET * 3))
        assertFalse(
            Transport.packetHashlistContainsForTest(firstHash),
            "the window two rotations old must be forgotten",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= BUDGET * 2,
            "only two windows are retained",
        )
    }

    private companion object {
        const val BUDGET = 1_000
    }
}
