package network.reticulum.interfaces.ble

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression tests for the 2026-04-21 BLE incoming-handshake storm.
 *
 * Observed on a Columba `.71` phone: the same MAC spawned multiple parallel
 * `Identity handshake timeout for incoming <MAC>, disconnecting` events at
 * the same timestamp, each incrementing the blacklist failure counter
 * (producing log lines for `failure #2`, `#3`, `#4` at the same instant).
 *
 * Three root causes in [BLEInterface] that together produce the storm:
 *   1. `collectIncomingConnections()` spawns a new 30s handshake coroutine
 *      for every incoming [BLEPeerConnection] event on the same MAC; no
 *      single-flight guard.
 *   2. `handleIncomingConnection` does NOT check the blacklist, so a
 *      blacklisted peer re-connecting still burns a 30s handshake slot.
 *   3. `addToBlacklist` is a non-atomic read-modify-write on a
 *      ConcurrentHashMap, so N concurrent callers may race and produce
 *      fewer than N increments (or in the worst case, N duplicate
 *      increments as observed in the field).
 *
 * Each test below isolates one of those three failure modes.
 */
@DisplayName("BLEInterface incoming handshake concurrency")
class BLEInterfaceIncomingHandshakeTest {

    private val liveInterfaces = mutableListOf<BLEInterface>()

    @AfterEach
    fun cleanup() {
        liveInterfaces.forEach { runCatching { it.detach() } }
        liveInterfaces.clear()
    }

    @Test
    fun `two rapid incoming connections for same MAC start only one handshake`() {
        runBlocking {
            val driver = StubBLEDriver()
            val iface = newInterface(driver)

            iface.start()
            // Allow collectIncomingConnections() to subscribe to driver.incomingConnections
            // before we emit anything (replay=0, so emissions before subscribe are lost).
            waitForCollector(driver)

            val connA = FakeBLEPeerConnection(address = DUP_MAC)
            val connB = FakeBLEPeerConnection(address = DUP_MAC)

            driver.incomingConnections.emit(connA)
            driver.incomingConnections.emit(connB)

            // Deterministic waits instead of a fixed quiescence delay (flaky on
            // slow CI): wait until the first handshake subscribes AND the
            // duplicate is rejected via driver.disconnect.
            waitUntil("first handshake subscribes to receivedFragments") {
                connA.receivedFragmentsSubscribers +
                    connB.receivedFragmentsSubscribers >= 1
            }
            waitUntil("duplicate is rejected via driver.disconnect") {
                driver.disconnectCalls.contains(DUP_MAC)
            }

            // Exactly one handshake must have subscribed to receivedFragments.
            // With the concurrency bug, BOTH connections subscribe and each
            // burns its own 30s handshake-timeout coroutine.
            val subscribers = connA.receivedFragmentsSubscribers +
                connB.receivedFragmentsSubscribers
            subscribers shouldBe 1

            // The duplicate must be proactively disconnected via the driver
            // (not left to time out after 30s).
            driver.disconnectCalls shouldContain DUP_MAC
        }
    }

    @Test
    fun `incoming connection from blacklisted MAC is rejected without starting handshake`() {
        runBlocking {
            val driver = StubBLEDriver()
            val iface = newInterface(driver)

            // Seed a live blacklist entry for the address via the same code
            // path a real failure would take. Driving this through
            // `addToBlacklist` keeps the test narrow (one accessor exposed
            // @VisibleForTesting) while exercising the production blacklist
            // structure end-to-end.
            iface.addToBlacklistForTest(BLACKLISTED_MAC)

            iface.start()
            waitForCollector(driver)

            val conn = FakeBLEPeerConnection(address = BLACKLISTED_MAC)
            driver.incomingConnections.emit(conn)

            // Deterministic wait on the observable outcome: the pre-flight
            // blacklist check must have called driver.disconnect.
            waitUntil("blacklisted MAC rejected via driver.disconnect") {
                driver.disconnectCalls.contains(BLACKLISTED_MAC)
            }

            // No handshake subscription — we never read receivedFragments.
            // With the current bug, handleIncomingConnection proceeds into
            // performHandshakeAsPeripheral() regardless of blacklist status,
            // subscribing to receivedFragments and burning a 30s timeout.
            conn.receivedFragmentsSubscribers shouldBe 0

            // Driver was told to drop the connection.
            driver.disconnectCalls shouldContain BLACKLISTED_MAC
        }
    }

    @Test
    fun `concurrent addToBlacklist calls increment failureCount exactly once per call`() {
        val driver = StubBLEDriver()
        val iface = newInterface(driver)
        val address = "11:22:33:44:55:66"
        val concurrentCallers = 64

        // Fire many addToBlacklist calls that are ALL released at the same
        // instant via a CountDownLatch, forcing maximum contention on the
        // read-modify-write sequence inside `addToBlacklist`. With the
        // non-atomic bug, multiple callers read the same `existing`
        // entry, each compute the same `existing.failureCount + 1`, and
        // then each overwrite each other's put -- so the final count
        // ends up strictly less than N.
        //
        // Running on the JVM thread pool (not coroutine dispatcher) to
        // defeat any scheduler-level serialization: we want real threads
        // hammering the ConcurrentHashMap entry.
        val latch = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(concurrentCallers)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(concurrentCallers)
        try {
            repeat(concurrentCallers) {
                pool.execute {
                    try {
                        latch.await()
                        iface.addToBlacklistForTest(address)
                    } finally {
                        done.countDown()
                    }
                }
            }
            latch.countDown() // release the kraken
            check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "addToBlacklistForTest callers did not finish"
            }
        } finally {
            pool.shutdownNow()
        }

        val entry = iface.getBlacklistEntryForTest(address)
        checkNotNull(entry) { "blacklist entry missing for $address" }

        // Every caller must have contributed exactly one increment. Without
        // the atomic `compute {}` fix, this assertion fails because concurrent
        // read-modify-write on the ConcurrentHashMap entry loses increments
        // -- the same #2/#3/#4 storm seen in the wild, where multiple
        // concurrent handshake timeouts race to bump the counter.
        entry.failureCount shouldBe concurrentCallers

        // Defensive: catch the reverse bug where a future `compute` refactor
        // could double-count; final count must never exceed the caller count.
        entry.failureCount shouldBeLessThanOrEqual concurrentCallers
    }

    // ---- Helpers ----

    private fun newInterface(driver: StubBLEDriver): BLEInterface {
        val iface = BLEInterface(
            name = "BLE-test",
            driver = driver,
            transportIdentity = ByteArray(16),
        )
        liveInterfaces += iface
        return iface
    }

    /**
     * Give `collectIncomingConnections()` time to subscribe to the driver's
     * SharedFlow. Without this, `emit()` is dropped (replay=0, no subscribers).
     */
    private suspend fun waitForCollector(driver: StubBLEDriver) {
        withTimeout(2_000) {
            while (driver.incomingConnections.subscriptionCount.value < 1) {
                delay(10)
            }
        }
    }

    /**
     * Poll [condition] until it holds or a 2-second deadline elapses. Preferred
     * over a fixed [delay] because a slow CI scheduler can postpone launched
     * coroutines past a hard-coded wait window; polling a deterministic
     * observable is robust to that. Throws [kotlinx.coroutines.TimeoutCancellationException]
     * with [description] included on timeout to give a legible failure message.
     */
    private suspend fun waitUntil(description: String, condition: () -> Boolean) {
        try {
            withTimeout(2_000) {
                while (!condition()) {
                    delay(10)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("waitUntil: $description never became true within 2s")
        }
    }

    companion object {
        private const val DUP_MAC = "AA:BB:CC:DD:EE:01"
        private const val BLACKLISTED_MAC = "AA:BB:CC:DD:EE:02"
    }

    /**
     * Minimal [BLEPeerConnection] fake. Exposes subscriber count on the
     * receivedFragments flow so tests can tell whether the peripheral-side
     * identity handshake actually started (which subscribes via `first { }`).
     */
    private class FakeBLEPeerConnection(
        override val address: String,
    ) : BLEPeerConnection {
        override val mtu: Int = 185
        override val identity: ByteArray? = null
        private val _receivedFragments = MutableSharedFlow<ByteArray>(replay = 0)
        override val receivedFragments: SharedFlow<ByteArray> =
            _receivedFragments.asSharedFlow()
        val receivedFragmentsSubscribers: Int
            get() = _receivedFragments.subscriptionCount.value
        override suspend fun sendFragment(data: ByteArray) = Unit
        override suspend fun readIdentity(): ByteArray = ByteArray(16)
        override suspend fun writeIdentity(identity: ByteArray) = Unit
        override suspend fun readRemoteRssi(): Int = -70
        override fun close() = Unit
    }

    /**
     * [BLEDriver] stub that records disconnect() calls and exposes a
     * MutableSharedFlow for incoming connections so tests can drive emission
     * timing deterministically.
     */
    private class StubBLEDriver : BLEDriver {
        override suspend fun startAdvertising() = Unit
        override suspend fun stopAdvertising() = Unit
        override suspend fun startScanning() = Unit
        override suspend fun stopScanning() = Unit
        override suspend fun connect(address: String): BLEPeerConnection =
            error("unused in incoming-handshake tests")

        override suspend fun disconnect(address: String) {
            disconnectCalls += address
        }

        override fun shutdown() = Unit
        override val discoveredPeers: SharedFlow<DiscoveredPeer> = MutableSharedFlow()
        override val incomingConnections = MutableSharedFlow<BLEPeerConnection>(replay = 0)
        override val connectionLost: SharedFlow<String> = MutableSharedFlow()
        override val localAddress: String? = null
        override val isRunning: Boolean = false

        val disconnectCalls: MutableList<String> = CopyOnWriteArrayList()
    }
}
