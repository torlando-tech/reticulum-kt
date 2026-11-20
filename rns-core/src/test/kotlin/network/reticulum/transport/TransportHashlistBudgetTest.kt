package network.reticulum.transport

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.Platform
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.storage.InMemoryPathStore
import network.reticulum.storage.PacketHashStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for columba#1222: the packet dedup windows must stay inside
 * the platform budget and rotate without copying one window into the other.
 *
 * The budget is the TOTAL for both dedup windows, matching the reference
 * (`RNS/Transport.py:831-834@1.5.5` rotates once `hashlist_maxsize // 2` is
 * exceeded, retaining two windows of at most 500 001 entries), not a per-window
 * cap. Rotation therefore runs at `budget / 2`.
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
        Transport.pathStore = null
        Transport.packetHashStore = null
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
    @DisplayName("both dedup windows together stay within the total budget")
    fun windowsStayWithinBudget() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)

        // Two windows of at most BUDGET/2 entries each, plus the entry that
        // triggers a rotation: BUDGET + 2 is the tight upper bound. Sampling
        // after every add catches the phase where the previous window is full
        // and the current one is filling — the phase a single end-of-loop
        // assertion misses entirely.
        repeat(BUDGET * 3) { index ->
            Transport.addPacketHashForTest(hash(index))
            val total = Transport.hashlistTotalSizeForTest()
            assertTrue(
                total <= BUDGET + 2,
                "after add #${index + 1} the retained $total entries exceed the " +
                    "$BUDGET-entry total budget",
            )
            assertTrue(
                Transport.packetHashlistSizeForTest() <= BUDGET / 2 + 1,
                "after add #${index + 1} the current window holds " +
                    "${Transport.packetHashlistSizeForTest()} entries, more than half the budget",
            )
        }
    }

    @Test
    @DisplayName("rotation keeps the previous window for dedup and drops the window before it")
    fun rotationKeepsOnePreviousWindow() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)
        // Rotation runs at half the total budget, so one window holds BUDGET/2.
        val window = BUDGET / 2

        // Window 1 fills exactly one window; rotation has not happened yet.
        val firstHash = hash(0)
        repeat(window) { Transport.addPacketHashForTest(hash(it)) }
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "first window must be deduped")

        // The add past one window's share rotates: window 1 becomes the previous
        // window and the current window starts empty.
        Transport.addPacketHashForTest(hash(window))
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "previous window still dedups")

        // Fill window 2 and rotate once more: window 1 is forgotten entirely.
        // The triggering add above rotated hash(window) into the previous window
        // and left the current window empty, so a full window is `window` adds,
        // and the add after that rotates again.
        repeat(window) { Transport.addPacketHashForTest(hash(window + 1 + it)) }
        Transport.addPacketHashForTest(hash(window * 2 + 1))
        assertFalse(
            Transport.packetHashlistContainsForTest(firstHash),
            "the window two rotations old must be forgotten",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= BUDGET + 2,
            "only two windows are retained (${Transport.hashlistTotalSizeForTest()} entries)",
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
        // Rotation runs at half the total budget, so one window holds budget/2.
        val window = budget / 2
        // Guard the guard: if the simulated platform budget ever matched the flat
        // constant, this test could no longer tell the two sources apart.
        assertTrue(
            budget < TransportConstants.HASHLIST_MAXSIZE,
            "the platform budget ($budget) must differ from the flat constant " +
                "(${TransportConstants.HASHLIST_MAXSIZE}) for this test to discriminate",
        )

        // Fill one window's share of the platform budget: no rotation yet.
        val firstHash = hash(0)
        repeat(window) { Transport.addPacketHashForTest(hash(it)) }
        assertTrue(Transport.packetHashlistContainsForTest(firstHash), "window 1 must be deduped")

        // One more hash exceeds budget/2, so window 1 becomes the previous window.
        Transport.addPacketHashForTest(hash(window))
        assertTrue(
            Transport.packetHashlistContainsForTest(firstHash),
            "rotation must happen at $window entries, so the previous window still dedups",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= window + 2,
            "one full window plus the triggering entry fits one window (" +
                "${Transport.hashlistTotalSizeForTest()} entries)",
        )

        // Fill and exceed the second window: the first one is forgotten. The
        // earlier rotation left the current window empty, so a full window is
        // `window` adds. With the flat 1M fallback nothing rotates inside ~50k
        // hashes, so this assertion fails.
        repeat(window) { Transport.addPacketHashForTest(hash(window + 1 + it)) }
        Transport.addPacketHashForTest(hash(window * 2 + 1))
        assertFalse(
            Transport.packetHashlistContainsForTest(firstHash),
            "the window two rotations old must be forgotten after 2 x ($window + 1) hashes",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= budget + 2,
            "only the newest window is retained, not the 1M-entry flat constant " +
                "(${Transport.hashlistTotalSizeForTest()} entries)",
        )
    }

    /**
     * A hashlist persisted by a pre-fix build used the flat 1M-entry constant and
     * had no load-time cap, so the file can be far larger than the platform
     * budget. Loading it verbatim would re-inflate the heap that columba#1222
     * exhausted.
     */
    @Test
    @DisplayName("loading an oversized persisted hashlist is trimmed to the budget")
    fun loadCapsOversizedPersistedHashlist(@TempDir tempDir: File) {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)

        val file = File(tempDir, "packet_hashlist")
        file.writeText((0 until BUDGET * 3).joinToString("\n") { hash(it).toHexString() })

        Transport.loadPacketHashlist(file)

        assertTrue(
            Transport.packetHashlistSizeForTest() <= BUDGET / 2,
            "a loaded generation must be trimmed to one window " +
                "(${Transport.packetHashlistSizeForTest()} entries)",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= BUDGET + 2,
            "loading must respect the total budget " +
                "(${Transport.hashlistTotalSizeForTest()} entries)",
        )
    }

    /**
     * The store branch must ask for a bounded load: copying both generations out
     * of `loadAll()` and trimming afterwards would hold a pre-fix overhang in
     * memory first — the spike that OOMed columba#1222. This asserts the store is
     * asked with one window's share and that the live sets end up capped.
     */
    @Test
    @DisplayName("loading from a store caps each generation without calling loadAll")
    fun loadCapsStoreGenerations() {
        Transport.clearPacketHashlist()
        Transport.setHashlistLimitForTest(BUDGET)

        val store = RecordingHashStore(
            current = (0 until BUDGET * 3).mapTo(HashSet()) { hash(it).toKey() },
            previous = (0 until BUDGET * 3).mapTo(HashSet()) { hash(it + BUDGET * 3).toKey() },
        )
        Transport.pathStore = InMemoryPathStore()
        Transport.packetHashStore = store
        try {
            Transport.loadPersistedDataForTest()
        } finally {
            Transport.packetHashStore = null
            Transport.pathStore = null
        }

        assertTrue(store.loadAllCalls == 0, "the load path must ask for a bounded load, not loadAll()")
        assertTrue(
            store.requestedLimit == BUDGET / 2,
            "the store must be asked for one window's share, got ${store.requestedLimit}",
        )
        assertTrue(
            Transport.packetHashlistSizeForTest() <= BUDGET / 2,
            "a loaded generation must be capped to one window " +
                "(${Transport.packetHashlistSizeForTest()} entries)",
        )
        assertTrue(
            Transport.hashlistTotalSizeForTest() <= BUDGET + 2,
            "loading must respect the total budget " +
                "(${Transport.hashlistTotalSizeForTest()} entries)",
        )
    }

    /** Records which load entry point the transport uses and how it caps it. */
    private class RecordingHashStore(
        private val current: Set<ByteArrayKey>,
        private val previous: Set<ByteArrayKey>,
    ) : PacketHashStore {
        var loadAllCalls = 0
        var requestedLimit: Int? = null

        override fun saveAll(hashes: Set<ByteArrayKey>, generation: Int) = Unit

        override fun loadAll(): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>> {
            loadAllCalls++
            return current to previous
        }

        override fun loadCapped(limitPerGeneration: Int): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>> {
            requestedLimit = limitPerGeneration
            return current.take(limitPerGeneration).toSet() to
                previous.take(limitPerGeneration).toSet()
        }

        override fun clear() = Unit
    }

    private companion object {
        const val BUDGET = 1_000
    }
}
