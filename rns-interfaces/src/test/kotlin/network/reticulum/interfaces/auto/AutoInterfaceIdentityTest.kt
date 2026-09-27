package network.reticulum.interfaces.auto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * An interface's identity must be a function of its configuration and nothing else.
 *
 * `Interface.getHash()` is the full hash of `toString()`, and Transport keys the path
 * table's `receivingInterfaceHash` on that. This interface used to render its live peer
 * count into the string, so its identity moved as multicast discovery found peers.
 * `InterfaceAdapter` captures the hash once at construction, which bounds the damage to a
 * restart — and a restart is exactly when it bites, because Columba's restartInterface
 * builds a fresh interface and adapter, capturing whatever count discovery had reached in
 * that window. Two restarts on one LAN could capture different counts, and every persisted
 * path learned over the interface would then name an interface that no longer exists.
 *
 * What is not tested here: the hash staying put *while peers join*, which is the invariant
 * in its purest form. `peers` and `addPeer` are private and there is no seam, and adding
 * one to observe a string would be a worse trade than the coverage is worth. The first
 * test below is the discriminator instead — reintroduce any runtime term into `toString()`
 * and it fails.
 *
 * Reference: `AutoInterface.__str__` is `f"AutoInterface[{self.name}]"`
 * (`AutoInterface.py:617`).
 */
class AutoInterfaceIdentityTest {

    @Test
    fun `the rendered form is the reference's, carrying only the name`() {
        val iface = AutoInterface(name = "Auto0")
        assertEquals("AutoInterface[Auto0]", iface.toString())
    }

    @Test
    fun `the rendered form carries no runtime state`() {
        // A digit-free name, so any digit in the output came from the interface rather
        // than from its configuration. A name may of course contain digits; the point is
        // that nothing else may contribute one.
        val rendered = AutoInterface(name = "Auto").toString()
        assertFalse(
            rendered.contains("peer", ignoreCase = true),
            "peer state belongs in the interface's stats, not its identity: $rendered",
        )
        assertFalse(
            rendered.any { it.isDigit() },
            "a digit here is a count or a port, and either moves at runtime: $rendered",
        )
    }

    @Test
    fun `two instances of the same configured interface share one identity`() {
        val first = AutoInterface(name = "Auto0")
        val second = AutoInterface(name = "Auto0")
        assertEquals(
            first.getHash().toList(),
            second.getHash().toList(),
            "a restart rebuilds the object; the path table's key must survive it",
        )
    }

    @Test
    fun `differently configured interfaces do not collide`() {
        assertFalse(
            AutoInterface(name = "Auto0").getHash()
                .contentEquals(AutoInterface(name = "Auto1").getHash()),
            "the name still has to distinguish them",
        )
    }
}
