package network.reticulum

import io.kotest.matchers.shouldBe
import network.reticulum.common.RnsConstants
import network.reticulum.identity.Identity
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for issue #71: in Reticulum::tryConnectToSharedInstance the
 * client interface was started (TCP connect plus read loop) BEFORE the
 * interface registrar wired onPacketReceived. A frame arriving from the
 * daemon in that gap hit a null onPacketReceived and was silently dropped.
 *
 * The real LocalClientInterface read loop is async and the race window is
 * small (rnsd typically does not push a frame the instant a client connects),
 * so a direct deterministic reproduction is not feasible. Instead this test
 * uses a fake client interface whose start() models the moment the read loop
 * goes live: it counts a frame that arrives immediately after start() as
 * dropped whenever onPacketReceived is still null, and it records whether the
 * registrar had already run by then. With the buggy ordering both assertions
 * fail (the frame is dropped); with the fixed ordering they pass.
 */
@DisplayName("Shared instance startup ordering")
class ReticulumSharedInstanceStartupOrderTest {

    /**
     * Minimal stand-in for LocalClientInterface. Reticulum invokes start() on
     * it via reflection when connecting to a shared instance; that is the
     * point where the real interface opens its socket and launches the read
     * loop.
     */
    inner class FakeClientInterface {
        /** Mirrors Interface.onPacketReceived; the registrar wires it. */
        var onPacketReceived: ((ByteArray, FakeClientInterface) -> Unit)? = null

        /** When true, start() throws to model a failed connect (port dropped). */
        val startThrows = AtomicBoolean(false)

        /** Set when the registrar had already run by the time start() fired. */
        val registrarAppliedAtStart = AtomicBoolean(false)

        /** Frames that would have hit a null onPacketReceived. */
        val droppedFrameCount = AtomicInteger(0)

        /** Models the read loop: a frame arriving the instant start() runs. */
        fun start() {
            if (startThrows.get()) {
                throw RuntimeException("simulated shared-instance connect failure")
            }
            registrarAppliedAtStart.set(registrarApplied.get())
            if (onPacketReceived == null) {
                droppedFrameCount.incrementAndGet()
            }
        }

        fun detach() {}
    }

    /**
     * Minimal real InterfaceRef. The fake registrar/deregistrar use it to do
     * actual Transport.registerInterface/deregisterInterface work, so the
     * rollback test can assert the dead client is really removed from Transport
     * (not just that a callback lambda ran) - a no-op production callback would
     * leave the ref registered and fail the assertion.
     */
    private class FakeRef(override val name: String, hashBytes: ByteArray) : InterfaceRef {
        override val hash: ByteArray = hashBytes
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false
        override fun send(data: ByteArray) { /* no-op: the test only registers/deregisters this ref */ }
    }

    private val tempDir = Files.createTempDirectory("rns-shared-instance-order").toFile()
    private lateinit var serverSocket: ServerSocket
    private lateinit var fakeClient: FakeClientInterface
    private val registrarApplied = AtomicBoolean(false)
    private val receivedFrames = mutableListOf<ByteArray>()

    /** A listening socket makes isSharedInstanceRunning(port) return true. */
    private val port: Int
        get() = serverSocket.localPort

    @BeforeEach
    fun setup() {
        serverSocket = ServerSocket(0)
        fakeClient = FakeClientInterface()
    }

    @AfterEach
    fun teardown() {
        Reticulum.stop()
        Reticulum.clearPendingFactories()
        // The client path leaves Transport running by design (python parity),
        // so stop it here to keep the suite's global Transport state clean.
        Transport.stop()
        serverSocket.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `registrar wires onPacketReceived before the client interface starts`() {
        Reticulum.setLocalClientFactory { _, _ -> fakeClient }
        Reticulum.setInterfaceRegistrar { iface ->
            (iface as FakeClientInterface).onPacketReceived = { data, _ ->
                receivedFrames.add(data)
            }
            registrarApplied.set(true)
        }

        Reticulum.start(
            configDir = tempDir.absolutePath,
            connectToSharedInstance = true,
            sharedInstancePort = port,
            transportIdentity = Identity.create(),
        )

        Reticulum.getInstance().isConnectedToSharedInstance shouldBe true
        // The registrar must have run before start() so a frame arriving the
        // instant the read loop goes live is not dropped (issue #71).
        fakeClient.registrarAppliedAtStart.get() shouldBe true
        fakeClient.droppedFrameCount.get() shouldBe 0
    }

    @Test
    fun `a failed start after registration removes the dead client from Transport`() {
        // PR review P1: because issue #71's fix registers the client BEFORE
        // start(), a start() that throws leaves a registered-but-dead client in
        // Transport. Standalone startup would then run with that dead client
        // still registered. The deregistrar (symmetric to the registrar) must be
        // invoked with the dead client so it is rolled back.
        //
        // The fake registrar/deregistrar do REAL Transport work (this mirrors
        // the production callbacks: InterfaceAdapter.getOrCreate + Transport
        // register/deregisterInterface). So the assertion below proves the
        // dead client is actually gone from Transport - a no-op production
        // deregistrar would leave the ref registered and fail it.
        val clientHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x5E }
        // One shared ref instance: deregisterInterface removes by reference
        // (List.remove), so the registrar and deregistrar must use the SAME
        // instance - mirroring InterfaceAdapter.getOrCreate, which caches and
        // returns the same ref for the same interface.
        val deadRef = FakeRef("dead-client", clientHash)
        val registered = AtomicBoolean(false)
        val deregistered = AtomicBoolean(false)
        fakeClient.startThrows.set(true)

        Reticulum.setLocalClientFactory { _, _ -> fakeClient }
        Reticulum.setInterfaceRegistrar { iface ->
            (iface as FakeClientInterface).onPacketReceived = { _, _ -> }
            registrarApplied.set(true)
            Transport.registerInterface(deadRef)
            registered.set(true)
        }
        Reticulum.setInterfaceDeregistrar { dead ->
            @Suppress("UNUSED_EXPRESSION") dead
            Transport.deregisterInterface(deadRef)
            deregistered.set(true)
        }

        Reticulum.start(
            configDir = tempDir.absolutePath,
            connectToSharedInstance = true,
            sharedInstancePort = port,
            transportIdentity = Identity.create(),
        )

        // The connection failed (start() threw) ...
        Reticulum.getInstance().isConnectedToSharedInstance shouldBe false
        // ... the registrar really registered the client, ...
        registered.get() shouldBe true
        // ... and the rollback really removed it from Transport (not just that
        // a callback lambda ran).
        deregistered.get() shouldBe true
        Transport.findInterfaceByHashForTest(clientHash) shouldBe null
    }
}
