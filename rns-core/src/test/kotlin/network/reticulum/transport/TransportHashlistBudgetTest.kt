package network.reticulum.transport

import network.reticulum.common.Platform
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
        Platform.isAndroidOverrideForTest = null
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

    /**
     * The budget must come from [Platform.recommendedHashlistSize], not the flat
     * desktop [TransportConstants.HASHLIST_MAXSIZE].
     *
     * This is the only test that leaves [Transport.setHashlistLimitForTest]
     * unset, so it pins the production fallback: reverting the fallback to
     * [TransportConstants.HASHLIST_MAXSIZE] (the columba#1222 bug) makes
     * rotation stop happening at the platform budget and fails the assertions
     * below. On a JVM the two values are both 1,000,000 and therefore
     * indistinguishable, so the Android branch is simulated through
     * [Platform.isAndroidOverrideForTest] — which is exactly the branch the
     * Android OOM came from.
     */
    @Test
    @DisplayName("unset override derives the budget from Platform.recommendedHashlistSize")
    fun unsetOverrideUsesPlatformRecommendation() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(null)
        Platform.isAndroidOverrideForTest = true

        val budget = Platform.recommendedHashlistSize
        // Guard the guard: if the simulated platform budget ever matched the flat
        // constant, this test could no longer tell the two sources apart.
        assertTrue(
            budget < TransportConstants.HASHLIST_MAXSIZE,
            "the platform budget ($budget) must differ from the flat constant " +
                "(${TransportConstants.HASHLIST_MAXSIZE}) for this test to discriminate",
        )

        // Fill the first window exactly to the platform budget: no rotation yet.
        val firstHash = hash(0)
        repeat(budget) { Transport.addPacketHashForTest(hash(it)) }
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "window 1 must be deduped")

        // One more hash exceeds the budget, so window 1 becomes the previous window.
        Transport.addPacketHashForTest(hash(budget))
        assertTrue(
            Transport.packetHashlistContainsForTest(firstHash),
            "rotation must happen at $budget entries, so the previous window still dedups",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= budget + 1,
            "one full window plus the triggering entry fits one budget (" +
                "${Transport.hashlistTotalSizeForTest()} entries)",
        )

        // Fill and exceed the second window: the first one is forgotten. With the
        // flat 1M fallback nothing rotates inside ~100k hashes, so both of these
        // assertions fail.
        repeat(budget + 1) { Transport.addPacketHashForTest(hash(budget * 2 + it)) }
        assertFalse(
            Transport.packetHashlistContainsForTest(firstHash),
            "the window two rotations old must be forgotten after 2 x ($budget + 1) hashes",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= budget + 1,
            "only the newest window is retained, not the 1M-entry flat constant " +
                "(${Transport.hashlistTotalSizeForTest()} entries)",
        )
    }

    private companion object {
        const val BUDGET = 1_000
    }
}
