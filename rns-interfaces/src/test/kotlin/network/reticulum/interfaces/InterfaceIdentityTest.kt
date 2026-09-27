package network.reticulum.interfaces

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import network.reticulum.interfaces.i2p.I2PInterface
import network.reticulum.interfaces.nearby.ConnectedEndpoint
import network.reticulum.interfaces.nearby.DiscoveredEndpoint
import network.reticulum.interfaces.nearby.NearbyDriver
import network.reticulum.interfaces.nearby.NearbyInterface
import network.reticulum.interfaces.nearby.ReceivedData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * An interface's identity must be a function of its configuration and nothing else.
 *
 * `Interface.getHash()` is the full hash of `toString()`, and Transport keys every
 * path-table row's receiving interface on that hash. A rendering that reads mutable state
 * therefore moves the interface's identity while it runs, and every path learned over it
 * comes to name an interface that no longer exists and is culled.
 *
 * Three interfaces did this. AutoInterface rendered its peer count and is covered by its
 * own test; the two here are the ones variant analysis turned up afterwards, found by
 * asking what else feeds `getHash()` rather than by any tool.
 *
 * The check differs per interface because what is reachable from a test differs. Where the
 * runtime value can be set, it is set and the hash compared across it, which is the
 * invariant stated directly. Where it cannot, the rendering is pinned instead.
 */
class InterfaceIdentityTest {

    /**
     * The strongest form of the check, available here because [I2PInterface.b32] is
     * public and assigned when the tunnel comes up. Before the fix this interface changed
     * identity on every start, once the tunnel established — a certainty rather than a
     * race.
     */
    @Test
    fun `an I2P interface keeps its identity when its tunnel address arrives`(
        @TempDir tempDir: Path,
    ) {
        val iface = I2PInterface(name = "I2P", storagePath = tempDir.toString())
        val before = iface.getHash().toList()
        val renderedBefore = iface.toString()

        iface.b32 = "ukeu3k5oycgaauneqgtnvselmt4yemvoilkln7jpvamvfx7dnkdq"

        assertEquals(
            before,
            iface.getHash().toList(),
            "the tunnel address must not change the interface's identity",
        )
        assertEquals(renderedBefore, iface.toString(), "nor its rendered form")
    }

    /**
     * [NearbyInterface] keeps its peers in a private map with no seam, so the invariant is
     * pinned at the rendering instead: a digit-free name must render digit-free, so any
     * digit can only have come from runtime state.
     */
    @Test
    fun `a nearby interface renders no peer state`() {
        val rendered = nearby("Nearby").toString()
        assertEquals("NearbyInterface[Nearby]", rendered)
        assertFalse(
            rendered.contains("peer", ignoreCase = true),
            "peer state belongs in the interface's own state, not its identity: $rendered",
        )
        assertFalse(
            rendered.any { it.isDigit() },
            "a digit here came from runtime state, not from the name: $rendered",
        )
    }

    @Test
    fun `two instances of one configured interface share an identity`(@TempDir tempDir: Path) {
        assertEquals(
            nearby("Nearby").getHash().toList(),
            nearby("Nearby").getHash().toList(),
            "a restart rebuilds the object; the path table's key must survive it",
        )
        val storage = tempDir.toString()
        assertEquals(
            I2PInterface(name = "I2P", storagePath = storage).getHash().toList(),
            I2PInterface(name = "I2P", storagePath = storage).getHash().toList(),
            "a restart rebuilds the object; the path table's key must survive it",
        )
    }

    private fun nearby(name: String) =
        NearbyInterface(name = name, driver = InertDriver(), localEndpointName = "local")

    /**
     * Enough of a driver to construct the interface, and nothing more. The interface is
     * never started here; these tests only ask what it calls itself.
     */
    private class InertDriver : NearbyDriver {
        override suspend fun start(endpointName: String, maxConnections: Int) = Unit
        override suspend fun stop() = Unit
        override fun send(endpointId: String, data: ByteArray) = Unit
        override fun broadcast(data: ByteArray) = Unit
        override fun disconnect(endpointId: String) = Unit
        override fun shutdown() = Unit
        override val discoveredEndpoints: SharedFlow<DiscoveredEndpoint> = MutableSharedFlow()
        override val connectedEndpoints: SharedFlow<ConnectedEndpoint> = MutableSharedFlow()
        override val connectionLost: SharedFlow<String> = MutableSharedFlow()
        override val dataReceived: SharedFlow<ReceivedData> = MutableSharedFlow()
        override val isRunning: Boolean = false
        override val connectedCount: Int = 0
    }
}
