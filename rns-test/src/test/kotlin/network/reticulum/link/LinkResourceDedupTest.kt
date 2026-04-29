package network.reticulum.link

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the resource-advertisement dedup at the Link layer.
 *
 * Background: Transport's packet hashlist intentionally skips LINK-destined
 * packets (Transport.processInbound's `rememberHash` calculation marks links
 * `false`). When a sender retransmits a `RESOURCE_ADV` — because its watchdog
 * fired or the receiver's request was lost — the duplicate reaches the
 * receiver-side `Link.processResourceAdv` in raw form. Without a hash-based
 * dedup the receiver builds two `Resource` instances for the same
 * `advertisement.hash`, both fill from the same incoming parts, both
 * `assemble()`, and the user delivery callback fires twice.
 *
 * Symptom from the cross-impl conformance suite: `Inbox sizes [N, N]` —
 * the same N-byte message landing in the inbox twice. Surfaced once
 * `Transport.transmit()` was changed to release `jobsLock` around blocking
 * I/O, which loosened timing enough to expose this latent race.
 *
 * Mirrors python `RNS.Link.has_incoming_resource` (Link.py:1311) +
 * `Resource.accept`'s `not link.has_incoming_resource(resource)` guard
 * (Resource.py:223).
 */
@DisplayName("Link Resource Dedup Tests")
class LinkResourceDedupTest {

    @BeforeEach
    fun setup() {
        Transport.stop()
        Thread.sleep(100)
        Transport.start(enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        Transport.stop()
        Thread.sleep(100)
    }

    @Test
    @DisplayName("hasIncomingResource returns false on a fresh link")
    @Timeout(5)
    fun `hasIncomingResource returns false on a fresh link`() {
        val link = freshLink()
        val anyHash = ByteArray(16) { 0xAB.toByte() }
        assertFalse(
            link.hasIncomingResource(anyHash),
            "Fresh link should have no incoming resources",
        )
    }

    @Test
    @DisplayName("hasIncomingResource returns true after registering a resource with the same hash")
    @Timeout(5)
    fun `hasIncomingResource returns true after registering a resource`() {
        val link = freshLink()
        val advHash = ByteArray(16) { 0x55.toByte() }
        val adv = ResourceAdvertisement.unpack(buildAdvertisementBytes(hash = advHash))
        assertNotNull(adv, "Advertisement should unpack")

        // Use accept(), then register manually — accept() builds a Resource
        // initialized to the advertisement's hash. The internal requestNext()
        // call may throw because the link isn't ACTIVE, but accept catches
        // and returns null. Bypass by using the simpler reflection path:
        // construct a Resource via reflection, set hash field, register.
        val resource = makeResourceWithHash(link, advHash)

        link.registerIncomingResource(resource)

        assertTrue(
            link.hasIncomingResource(advHash),
            "hasIncomingResource should return true for a registered hash",
        )
        assertFalse(
            link.hasIncomingResource(ByteArray(16) { 0x99.toByte() }),
            "hasIncomingResource should return false for a different hash",
        )
        assertEquals(
            1,
            link.incomingResourceHashesForTest().size,
            "Exactly one resource should be registered",
        )
    }

    @Test
    @DisplayName("registerIncomingResource is idempotent for the same Resource instance")
    @Timeout(5)
    fun `registerIncomingResource is idempotent for the same instance`() {
        val link = freshLink()
        val advHash = ByteArray(16) { 0x77.toByte() }
        val resource = makeResourceWithHash(link, advHash)

        link.registerIncomingResource(resource)
        link.registerIncomingResource(resource)
        link.registerIncomingResource(resource)

        assertEquals(
            1,
            link.incomingResourceHashesForTest().size,
            "Re-registering the same Resource instance should be a no-op",
        )
    }

    @Test
    @DisplayName("hasIncomingResource flags duplicate-hash registrations from a fresh ADV")
    @Timeout(5)
    fun `hasIncomingResource flags duplicate-hash registrations from a fresh ADV`() {
        // The actual regression: a sender retransmit produces a SECOND
        // ResourceAdvertisement that decodes to a fresh Resource INSTANCE
        // sharing the same hash with one we've already accepted.
        // hasIncomingResource must catch that. The production fix in
        // Link.processResourceAdv consults this method before accepting.
        val link = freshLink()
        val advHash = ByteArray(16) { 0x42.toByte() }

        // First "accept": register a Resource for advHash.
        val firstResource = makeResourceWithHash(link, advHash)
        link.registerIncomingResource(firstResource)

        // Simulate the retransmit producing a SEPARATE Resource instance
        // with the same advertisement hash (different object identity,
        // identical hash bytes — exactly what Resource.accept would build
        // from a duplicate ADV).
        val secondResource = makeResourceWithHash(link, advHash.copyOf())
        assertFalse(
            firstResource === secondResource,
            "Test sanity: the two Resource instances must be distinct objects",
        )

        // The dedup check must catch the duplicate.
        assertTrue(
            link.hasIncomingResource(secondResource.hash),
            "hasIncomingResource must catch duplicate-hash from a fresh Resource instance " +
                "(production: Resource.accept consults this — covers all four call sites)",
        )
    }

    @Test
    @DisplayName("Resource.accept returns null on duplicate-hash advertisement (covers all 4 call sites)")
    @Timeout(5)
    fun `Resource accept returns null on duplicate-hash advertisement`() {
        // Greptile P1 on PR #64: the dedup must live inside Resource.accept,
        // not just at the general-strategy branch of Link.processResourceAdv.
        // Otherwise the isRequest, isResponse, and ACCEPT_APP branches —
        // which all call Resource.accept directly — bypass the check and
        // double-deliver. Mirror python `Resource.py:223` exactly: the
        // `not link.has_incoming_resource(resource)` guard sits inside
        // accept(), so every call site benefits uniformly.
        val link = freshLink()
        val advHash = ByteArray(16) { 0xCD.toByte() }

        // Seed the link's incomingResources with a Resource for advHash.
        link.registerIncomingResource(makeResourceWithHash(link, advHash))
        assertEquals(1, link.incomingResourceHashesForTest().size)

        // Now build an advertisement whose `h` matches advHash — this is
        // exactly what a sender retransmit produces. Resource.accept must
        // detect the duplicate and return null without registering a
        // second Resource.
        val adv = ResourceAdvertisement.unpack(buildAdvertisementBytes(hash = advHash))
        assertNotNull(adv, "Test sanity: advertisement should unpack")

        val accepted = Resource.accept(advertisement = adv, link = link)
        assertNull(
            accepted,
            "Resource.accept must return null when the advertisement hash is already incoming — " +
                "the dedup guard inside accept() is what protects the isRequest/isResponse/ACCEPT_APP " +
                "branches that bypass the Link-layer check",
        )
        assertEquals(
            1,
            link.incomingResourceHashesForTest().size,
            "No second Resource should be registered — the duplicate ADV must be dropped " +
                "before any setup work runs",
        )
    }

    // -------------------- Helpers --------------------

    private fun freshLink(): Link {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("dedup", "test"),
        )
        return Link.create(destination)
    }

    /**
     * Build a Resource whose `hash` field is set to [advHash]. Resource has a
     * private constructor, so this uses reflection — the alternative is
     * standing up a fully active Link + valid encrypted advertisement, which
     * is far heavier than what's needed to test the dedup invariant.
     */
    private fun makeResourceWithHash(link: Link, advHash: ByteArray): network.reticulum.resource.Resource {
        val ctor = network.reticulum.resource.Resource::class.java
            .getDeclaredConstructor(Link::class.java, Boolean::class.javaPrimitiveType)
        ctor.isAccessible = true
        val resource = ctor.newInstance(link, false) as network.reticulum.resource.Resource

        val hashField = network.reticulum.resource.Resource::class.java.getDeclaredField("hash")
        hashField.isAccessible = true
        hashField.set(resource, advHash)
        return resource
    }

    /**
     * Mirrors `ResourceIntegrationTest.createMockAdvertisement()` — just the
     * msgpack-packed advertisement bytes; the hash field is parameterized so
     * we can build two advertisements with matching hashes.
     */
    @Suppress("LongMethod")
    private fun buildAdvertisementBytes(hash: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(output)

        packer.packMapHeader(11)

        packer.packString("t"); packer.packInt(1000)  // transfer size
        packer.packString("d"); packer.packInt(1000)  // data size
        packer.packString("n"); packer.packInt(5)     // num parts

        packer.packString("h")
        packer.packBinaryHeader(hash.size); packer.writePayload(hash)

        packer.packString("r")
        val randomHash = ByteArray(4) { (it + 100).toByte() }
        packer.packBinaryHeader(randomHash.size); packer.writePayload(randomHash)

        packer.packString("o")
        packer.packBinaryHeader(hash.size); packer.writePayload(hash)

        packer.packString("i"); packer.packInt(1)  // segment index
        packer.packString("l"); packer.packInt(1)  // total segments
        packer.packString("q"); packer.packNil()   // request ID
        packer.packString("f"); packer.packInt(0)  // flags

        packer.packString("m")
        val hashmap = ByteArray(20)
        packer.packBinaryHeader(hashmap.size); packer.writePayload(hashmap)

        packer.close()
        return output.toByteArray()
    }
}
