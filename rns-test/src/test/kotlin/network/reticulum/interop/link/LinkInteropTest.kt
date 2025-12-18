package network.reticulum.interop.link

import network.reticulum.common.DestinationType
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Link interoperability tests with Python RNS.
 *
 * Tests the Link protocol components to ensure Kotlin and Python
 * implementations are byte-compatible.
 */
@DisplayName("Link Interop")
class LinkInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Signalling Bytes")
    inner class SignallingBytesTests {

        @Test
        @DisplayName("Signalling bytes encoding matches Python")
        fun `signalling bytes encoding matches Python`() {
            val testCases = listOf(
                // (mtu, mode)
                Pair(500, LinkConstants.MODE_AES256_CBC),
                Pair(1000, LinkConstants.MODE_AES256_CBC),
                Pair(250, LinkConstants.MODE_AES256_CBC),
                Pair(RnsConstants.MTU, LinkConstants.MODE_AES256_CBC),
                Pair(0x1FFFFF, LinkConstants.MODE_AES256_CBC),  // Max MTU
            )

            for ((mtu, mode) in testCases) {
                val kotlinBytes = Link.signallingBytes(mtu, mode)

                val pythonResult = python(
                    "link_signalling_bytes",
                    "mtu" to mtu.toString(),
                    "mode" to mode.toString()
                )

                assertBytesEqual(
                    pythonResult.getBytes("signalling_bytes"),
                    kotlinBytes,
                    "Signalling bytes for MTU=$mtu, mode=$mode"
                )
            }
        }

        @Test
        @DisplayName("Signalling bytes decoding matches Python")
        fun `signalling bytes decoding matches Python`() {
            val testCases = listOf(
                byteArrayOf(0x20.toByte(), 0x01.toByte(), 0xF4.toByte()),  // MTU=500, mode=1
                byteArrayOf(0x20.toByte(), 0x03.toByte(), 0xE8.toByte()),  // MTU=1000, mode=1
                byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte()),  // MTU=0, mode=0
                byteArrayOf(0x3F.toByte(), 0xFF.toByte(), 0xFF.toByte()),  // Max values for mode=1
            )

            for (bytes in testCases) {
                val pythonResult = python(
                    "link_parse_signalling",
                    "signalling_bytes" to bytes
                )

                val pythonMtu = pythonResult.getString("mtu").toInt()
                val pythonMode = pythonResult.getString("mode").toInt()

                // Parse in Kotlin
                val value = ((bytes[0].toInt() and 0xFF) shl 16) or
                           ((bytes[1].toInt() and 0xFF) shl 8) or
                           (bytes[2].toInt() and 0xFF)
                val kotlinMtu = value and LinkConstants.MTU_BYTEMASK
                val kotlinMode = (bytes[0].toInt() and LinkConstants.MODE_BYTEMASK) shr 5

                assertEquals(pythonMtu, kotlinMtu, "MTU decoding for ${bytes.toHex()}")
                assertEquals(pythonMode, kotlinMode, "Mode decoding for ${bytes.toHex()}")
            }
        }

        @Test
        @DisplayName("Round-trip signalling bytes")
        fun `round trip signalling bytes`() {
            val testCases = listOf(500, 250, 1000, RnsConstants.MTU)

            for (mtu in testCases) {
                val mode = LinkConstants.MODE_AES256_CBC
                val encoded = Link.signallingBytes(mtu, mode)

                val pythonResult = python(
                    "link_parse_signalling",
                    "signalling_bytes" to encoded
                )

                val decodedMtu = pythonResult.getString("mtu").toInt()
                val decodedMode = pythonResult.getString("mode").toInt()

                assertEquals(mtu, decodedMtu, "Round-trip MTU for $mtu")
                assertEquals(mode, decodedMode, "Round-trip mode for $mtu")
            }
        }
    }

    @Nested
    @DisplayName("Key Derivation")
    inner class KeyDerivationTests {

        @Test
        @DisplayName("Link key derivation matches Python (AES-256)")
        fun `link key derivation matches Python aes256`() {
            // Simulated shared key from ECDH
            val sharedKey = ByteArray(32) { it.toByte() }
            // Simulated link ID (truncated hash)
            val linkId = ByteArray(16) { (it + 100).toByte() }

            // Derive in Kotlin
            val derivedKeyLength = LinkConstants.derivedKeyLength(LinkConstants.MODE_AES256_CBC)
            val kotlinDerived = crypto.hkdf(
                length = derivedKeyLength,
                ikm = sharedKey,
                salt = linkId,
                info = null
            )

            // Derive in Python
            val pythonResult = python(
                "link_derive_key",
                "shared_key" to sharedKey,
                "link_id" to linkId,
                "mode" to "AES_256_CBC"
            )

            assertBytesEqual(
                pythonResult.getBytes("derived_key"),
                kotlinDerived,
                "Link key derivation (AES-256)"
            )

            // Also verify encryption/signing key split
            val encryptionKey = kotlinDerived.copyOfRange(0, 32)
            val signingKey = kotlinDerived.copyOfRange(32, 64)

            assertBytesEqual(
                pythonResult.getBytes("encryption_key"),
                encryptionKey,
                "Encryption key split"
            )
            assertBytesEqual(
                pythonResult.getBytes("signing_key"),
                signingKey,
                "Signing key split"
            )
        }

        @Test
        @DisplayName("Key derivation with different link IDs produces different keys")
        fun `key derivation with different link ids produces different keys`() {
            val sharedKey = ByteArray(32) { (it * 3).toByte() }
            val linkId1 = ByteArray(16) { it.toByte() }
            val linkId2 = ByteArray(16) { (it + 1).toByte() }

            val key1 = crypto.hkdf(64, sharedKey, linkId1, null)
            val key2 = crypto.hkdf(64, sharedKey, linkId2, null)

            assertTrue(!key1.contentEquals(key2), "Different link IDs should produce different keys")
        }

        @Test
        @DisplayName("Key derivation with different shared keys produces different keys")
        fun `key derivation with different shared keys produces different keys`() {
            val sharedKey1 = ByteArray(32) { it.toByte() }
            val sharedKey2 = ByteArray(32) { (it + 1).toByte() }
            val linkId = ByteArray(16) { (it * 2).toByte() }

            val key1 = crypto.hkdf(64, sharedKey1, linkId, null)
            val key2 = crypto.hkdf(64, sharedKey2, linkId, null)

            assertTrue(!key1.contentEquals(key2), "Different shared keys should produce different derived keys")
        }
    }

    @Nested
    @DisplayName("Link Encryption")
    inner class LinkEncryptionTests {

        @Test
        @DisplayName("Link encryption matches Python")
        fun `link encryption matches Python`() {
            // 64-byte derived key for AES-256
            val derivedKey = ByteArray(64) { it.toByte() }
            val plaintext = "Hello over link!".toByteArray()
            val fixedIv = ByteArray(16) { (it + 50).toByte() }

            // Encrypt in Kotlin
            val kotlinCiphertext = Token(derivedKey, crypto).encryptWithIv(plaintext, fixedIv)

            // Encrypt in Python
            val pythonResult = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext,
                "iv" to fixedIv
            )

            assertBytesEqual(
                pythonResult.getBytes("ciphertext"),
                kotlinCiphertext,
                "Link encryption with fixed IV"
            )
        }

        @Test
        @DisplayName("Kotlin can decrypt Python link ciphertext")
        fun `kotlin can decrypt Python link ciphertext`() {
            val derivedKey = ByteArray(64) { (it * 2).toByte() }
            val plaintext = "Secret link message from Python".toByteArray()

            // Encrypt in Python
            val pythonResult = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext
            )
            val pythonCiphertext = pythonResult.getBytes("ciphertext")

            // Decrypt in Kotlin
            val decrypted = Token(derivedKey, crypto).decrypt(pythonCiphertext)

            assertBytesEqual(plaintext, decrypted, "Kotlin decrypting Python link ciphertext")
        }

        @Test
        @DisplayName("Python can decrypt Kotlin link ciphertext")
        fun `python can decrypt Kotlin link ciphertext`() {
            val derivedKey = ByteArray(64) { (it * 3).toByte() }
            val plaintext = "Secret link message from Kotlin".toByteArray()

            // Encrypt in Kotlin
            val kotlinCiphertext = Token(derivedKey, crypto).encrypt(plaintext)

            // Decrypt in Python
            val pythonResult = python(
                "link_decrypt",
                "derived_key" to derivedKey,
                "ciphertext" to kotlinCiphertext
            )

            assertBytesEqual(plaintext, pythonResult.getBytes("plaintext"), "Python decrypting Kotlin link ciphertext")
        }

        @Test
        @DisplayName("Bidirectional link encryption round-trip")
        fun `bidirectional link encryption round trip`() {
            val derivedKey = ByteArray(64) { (it * 5).toByte() }

            val testMessages = listOf(
                ByteArray(0),
                "Short".toByteArray(),
                "A medium-length message for testing".toByteArray(),
                ByteArray(256) { it.toByte() },
                ByteArray(500) { (it % 256).toByte() }
            )

            for (plaintext in testMessages) {
                // Kotlin -> Python -> Kotlin
                val kotlinEncrypted = Token(derivedKey, crypto).encrypt(plaintext)
                val pythonDecrypted = python(
                    "link_decrypt",
                    "derived_key" to derivedKey,
                    "ciphertext" to kotlinEncrypted
                ).getBytes("plaintext")
                assertBytesEqual(plaintext, pythonDecrypted, "Kotlin->Python for ${plaintext.size} bytes")

                // Python -> Kotlin -> Python
                val pythonEncrypted = python(
                    "link_encrypt",
                    "derived_key" to derivedKey,
                    "plaintext" to plaintext
                ).getBytes("ciphertext")
                val kotlinDecrypted = Token(derivedKey, crypto).decrypt(pythonEncrypted)
                assertBytesEqual(plaintext, kotlinDecrypted, "Python->Kotlin for ${plaintext.size} bytes")
            }
        }
    }

    @Nested
    @DisplayName("Link Proof")
    inner class LinkProofTests {

        @Test
        @DisplayName("Link proof signature matches Python")
        fun `link proof signature matches Python`() {
            // Generate identity keypair
            val seed = ByteArray(64) { it.toByte() }
            val x25519Seed = seed.copyOfRange(0, 32)
            val ed25519Seed = seed.copyOfRange(32, 64)

            val x25519Pair = crypto.x25519KeyPairFromSeed(x25519Seed)
            val ed25519Pair = crypto.ed25519KeyPairFromSeed(ed25519Seed)
            val identityPrivate = x25519Pair.privateKey + ed25519Pair.privateKey

            // Link components
            val linkId = ByteArray(16) { (it + 10).toByte() }
            val receiverPub = ByteArray(32) { (it + 20).toByte() }
            val receiverSigPub = ByteArray(32) { (it + 30).toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)

            // Build signed data
            val signedData = linkId + receiverPub + receiverSigPub + signallingBytes

            // Sign in Kotlin
            val kotlinSignature = crypto.ed25519Sign(ed25519Pair.privateKey, signedData)

            // Sign in Python
            val pythonResult = python(
                "link_prove",
                "identity_private" to identityPrivate,
                "link_id" to linkId,
                "receiver_pub" to receiverPub,
                "receiver_sig_pub" to receiverSigPub,
                "signalling_bytes" to signallingBytes
            )

            assertBytesEqual(
                pythonResult.getBytes("signature"),
                kotlinSignature,
                "Link proof signature"
            )
        }

        @Test
        @DisplayName("Python can verify Kotlin link proof")
        fun `python can verify Kotlin link proof`() {
            // Generate identity
            val seed = ByteArray(64) { (it * 2).toByte() }
            val x25519Seed = seed.copyOfRange(0, 32)
            val ed25519Seed = seed.copyOfRange(32, 64)

            val x25519Pair = crypto.x25519KeyPairFromSeed(x25519Seed)
            val ed25519Pair = crypto.ed25519KeyPairFromSeed(ed25519Seed)
            val identityPublic = x25519Pair.publicKey + ed25519Pair.publicKey

            // Link components
            val linkId = ByteArray(16) { (it * 3).toByte() }
            val receiverPub = ByteArray(32) { (it * 4).toByte() }
            val receiverSigPub = ByteArray(32) { (it * 5).toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)

            // Build and sign in Kotlin
            val signedData = linkId + receiverPub + receiverSigPub + signallingBytes
            val signature = crypto.ed25519Sign(ed25519Pair.privateKey, signedData)

            // Verify in Python
            val pythonResult = python(
                "link_verify_proof",
                "identity_public" to identityPublic,
                "link_id" to linkId,
                "receiver_pub" to receiverPub,
                "receiver_sig_pub" to receiverSigPub,
                "signalling_bytes" to signallingBytes,
                "signature" to signature
            )

            assertTrue(pythonResult.getBoolean("valid"), "Python should verify Kotlin link proof")
        }

        @Test
        @DisplayName("Kotlin can verify Python link proof")
        fun `kotlin can verify Python link proof`() {
            // Generate identity
            val seed = ByteArray(64) { (it * 7).toByte() }
            val x25519Seed = seed.copyOfRange(0, 32)
            val ed25519Seed = seed.copyOfRange(32, 64)

            val x25519Pair = crypto.x25519KeyPairFromSeed(x25519Seed)
            val ed25519Pair = crypto.ed25519KeyPairFromSeed(ed25519Seed)
            val identityPrivate = x25519Pair.privateKey + ed25519Pair.privateKey
            val identityPublic = x25519Pair.publicKey + ed25519Pair.publicKey

            // Link components
            val linkId = ByteArray(16) { (it * 8).toByte() }
            val receiverPub = ByteArray(32) { (it * 9).toByte() }
            val receiverSigPub = ByteArray(32) { (it * 10).toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)

            // Sign in Python
            val pythonResult = python(
                "link_prove",
                "identity_private" to identityPrivate,
                "link_id" to linkId,
                "receiver_pub" to receiverPub,
                "receiver_sig_pub" to receiverSigPub,
                "signalling_bytes" to signallingBytes
            )
            val pythonSignature = pythonResult.getBytes("signature")

            // Verify in Kotlin
            val signedData = linkId + receiverPub + receiverSigPub + signallingBytes
            val ed25519Pub = identityPublic.copyOfRange(32, 64)
            val isValid = try {
                crypto.ed25519Verify(ed25519Pub, signedData, pythonSignature)
                true
            } catch (e: Exception) {
                false
            }

            assertTrue(isValid, "Kotlin should verify Python link proof")
        }

        @Test
        @DisplayName("Invalid proof signature is rejected")
        fun `invalid proof signature is rejected`() {
            val seed = ByteArray(64) { (it + 50).toByte() }
            val ed25519Seed = seed.copyOfRange(32, 64)
            val ed25519Pair = crypto.ed25519KeyPairFromSeed(ed25519Seed)

            val linkId = ByteArray(16) { it.toByte() }
            val receiverPub = ByteArray(32) { (it + 1).toByte() }
            val receiverSigPub = ByteArray(32) { (it + 2).toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)

            val signedData = linkId + receiverPub + receiverSigPub + signallingBytes
            val signature = crypto.ed25519Sign(ed25519Pair.privateKey, signedData)

            // Corrupt signature
            val corruptSignature = signature.copyOf()
            corruptSignature[0] = (corruptSignature[0].toInt() xor 0xFF).toByte()

            // ed25519Verify returns a Boolean - invalid signatures return false
            val isValid = crypto.ed25519Verify(ed25519Pair.publicKey, signedData, corruptSignature)

            assertTrue(!isValid, "Corrupted signature should be rejected")
        }
    }

    @Nested
    @DisplayName("Link ID Computation")
    inner class LinkIdTests {

        @Test
        @DisplayName("Link ID computation matches Python")
        fun `link id computation matches Python`() {
            // Create a link request packet
            val destHash = ByteArray(16) { it.toByte() }
            val peerPub = ByteArray(32) { (it + 100).toByte() }
            val peerSigPub = ByteArray(32) { (it + 200).toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)
            val requestData = peerPub + peerSigPub + signallingBytes

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = requestData,
                packetType = PacketType.LINKREQUEST,
                destinationType = DestinationType.SINGLE,
                transportType = TransportType.BROADCAST
            )
            packet.pack()

            // Compute link ID in Kotlin
            val kotlinLinkId = Link.linkIdFromLrPacket(packet)

            // Compute link ID in Python
            val pythonResult = python(
                "link_id_from_packet",
                "raw" to packet.raw!!
            )

            assertBytesEqual(
                pythonResult.getBytes("link_id"),
                kotlinLinkId,
                "Link ID from link request packet"
            )
        }

        @Test
        @DisplayName("Link ID without signalling bytes matches Python")
        fun `link id without signalling bytes matches Python`() {
            // Create a link request packet without signalling bytes (legacy format)
            val destHash = ByteArray(16) { (it * 2).toByte() }
            val peerPub = ByteArray(32) { (it + 50).toByte() }
            val peerSigPub = ByteArray(32) { (it + 150).toByte() }
            val requestData = peerPub + peerSigPub  // No signalling bytes

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = requestData,
                packetType = PacketType.LINKREQUEST,
                destinationType = DestinationType.SINGLE,
                transportType = TransportType.BROADCAST
            )
            packet.pack()

            // Compute link ID in Kotlin
            val kotlinLinkId = Link.linkIdFromLrPacket(packet)

            // Compute link ID in Python
            val pythonResult = python(
                "link_id_from_packet",
                "raw" to packet.raw!!
            )

            assertBytesEqual(
                pythonResult.getBytes("link_id"),
                kotlinLinkId,
                "Link ID from link request packet (no signalling)"
            )
        }

        @Test
        @DisplayName("Different packets produce different link IDs")
        fun `different packets produce different link ids`() {
            val destHash = ByteArray(16) { it.toByte() }
            val signallingBytes = Link.signallingBytes(500, LinkConstants.MODE_AES256_CBC)

            // Create two packets with different peer keys
            val peerPub1 = ByteArray(32) { it.toByte() }
            val peerSigPub1 = ByteArray(32) { (it + 32).toByte() }
            val data1 = peerPub1 + peerSigPub1 + signallingBytes

            val peerPub2 = ByteArray(32) { (it + 1).toByte() }
            val peerSigPub2 = ByteArray(32) { (it + 33).toByte() }
            val data2 = peerPub2 + peerSigPub2 + signallingBytes

            val packet1 = Packet.createRaw(
                destinationHash = destHash,
                data = data1,
                packetType = PacketType.LINKREQUEST,
                destinationType = DestinationType.SINGLE,
                transportType = TransportType.BROADCAST
            ).also { it.pack() }

            val packet2 = Packet.createRaw(
                destinationHash = destHash,
                data = data2,
                packetType = PacketType.LINKREQUEST,
                destinationType = DestinationType.SINGLE,
                transportType = TransportType.BROADCAST
            ).also { it.pack() }

            val linkId1 = Link.linkIdFromLrPacket(packet1)
            val linkId2 = Link.linkIdFromLrPacket(packet2)

            assertTrue(!linkId1.contentEquals(linkId2), "Different packets should produce different link IDs")
        }

        @Test
        @DisplayName("Link ID is 16 bytes (truncated hash)")
        fun `link id is 16 bytes`() {
            val destHash = ByteArray(16) { it.toByte() }
            val peerPub = ByteArray(32) { it.toByte() }
            val peerSigPub = ByteArray(32) { (it + 32).toByte() }
            val data = peerPub + peerSigPub

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.LINKREQUEST,
                destinationType = DestinationType.SINGLE,
                transportType = TransportType.BROADCAST
            ).also { it.pack() }

            val linkId = Link.linkIdFromLrPacket(packet)

            assertEquals(16, linkId.size, "Link ID should be 16 bytes (truncated hash)")
        }
    }

    @Nested
    @DisplayName("Complete Link Handshake")
    inner class CompleteHandshakeTests {

        @Test
        @DisplayName("End-to-end ECDH produces same shared secret")
        fun `end to end ecdh produces same shared secret`() {
            // Initiator keys
            val initiatorSeed = ByteArray(32) { it.toByte() }
            val initiatorKeyPair = crypto.x25519KeyPairFromSeed(initiatorSeed)

            // Receiver keys
            val receiverSeed = ByteArray(32) { (it + 100).toByte() }
            val receiverKeyPair = crypto.x25519KeyPairFromSeed(receiverSeed)

            // Initiator computes shared secret
            val initiatorShared = crypto.x25519Exchange(
                initiatorKeyPair.privateKey,
                receiverKeyPair.publicKey
            )

            // Receiver computes shared secret
            val receiverShared = crypto.x25519Exchange(
                receiverKeyPair.privateKey,
                initiatorKeyPair.publicKey
            )

            assertBytesEqual(initiatorShared, receiverShared, "ECDH shared secrets should match")

            // Verify against Python
            val pythonResult = python(
                "x25519_exchange",
                "private_key" to initiatorKeyPair.privateKey,
                "peer_public_key" to receiverKeyPair.publicKey
            )

            assertBytesEqual(
                pythonResult.getBytes("shared_secret"),
                initiatorShared,
                "Kotlin ECDH matches Python"
            )
        }

        @Test
        @DisplayName("Complete handshake produces compatible encryption")
        fun `complete handshake produces compatible encryption`() {
            // Simulate complete handshake
            val initiatorX25519 = crypto.x25519KeyPairFromSeed(ByteArray(32) { it.toByte() })
            val receiverX25519 = crypto.x25519KeyPairFromSeed(ByteArray(32) { (it + 50).toByte() })

            // ECDH exchange
            val sharedKey = crypto.x25519Exchange(initiatorX25519.privateKey, receiverX25519.publicKey)

            // Link ID (normally from packet hash)
            val linkId = ByteArray(16) { (it * 3).toByte() }

            // Derive keys
            val derivedKey = crypto.hkdf(64, sharedKey, linkId, null)

            // Test bidirectional encryption
            val initiatorMessage = "Hello from initiator".toByteArray()
            val receiverMessage = "Hello from receiver".toByteArray()

            // Initiator encrypts, receiver decrypts
            val initiatorCiphertext = Token(derivedKey, crypto).encrypt(initiatorMessage)
            val pythonDecrypted1 = python(
                "link_decrypt",
                "derived_key" to derivedKey,
                "ciphertext" to initiatorCiphertext
            ).getBytes("plaintext")
            assertBytesEqual(initiatorMessage, pythonDecrypted1, "Receiver should decrypt initiator message")

            // Receiver encrypts, initiator decrypts
            val receiverCiphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to receiverMessage
            ).getBytes("ciphertext")
            val kotlinDecrypted = Token(derivedKey, crypto).decrypt(receiverCiphertext)
            assertBytesEqual(receiverMessage, kotlinDecrypted, "Initiator should decrypt receiver message")
        }
    }
}
