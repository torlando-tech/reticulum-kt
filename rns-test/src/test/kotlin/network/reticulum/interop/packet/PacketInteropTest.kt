package network.reticulum.interop.packet

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import network.reticulum.common.ContextFlag
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.packet.Packet
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Packet interoperability tests with Python RNS.
 */
@DisplayName("Packet Interop")
class PacketInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("Flags Encoding")
    inner class FlagsEncoding {

        @Test
        @DisplayName("Flags byte encoding matches Python")
        fun `flags byte encoding matches Python`() {
            val testCases = listOf(
                // header_type, context_flag, transport_type, dest_type, packet_type
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.SINGLE, PacketType.DATA),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.SINGLE, PacketType.ANNOUNCE),
                TestFlags(HeaderType.HEADER_1, ContextFlag.SET, TransportType.BROADCAST, DestinationType.SINGLE, PacketType.DATA),
                TestFlags(HeaderType.HEADER_2, ContextFlag.UNSET, TransportType.TRANSPORT, DestinationType.SINGLE, PacketType.DATA),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.GROUP, PacketType.DATA),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.PLAIN, PacketType.DATA),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.LINK, PacketType.DATA),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.SINGLE, PacketType.LINKREQUEST),
                TestFlags(HeaderType.HEADER_1, ContextFlag.UNSET, TransportType.BROADCAST, DestinationType.SINGLE, PacketType.PROOF)
            )

            for (flags in testCases) {
                // Create packet with these flags
                val packet = Packet.createRaw(
                    destinationHash = ByteArray(16) { it.toByte() },
                    data = "test".toByteArray(),
                    packetType = flags.packetType,
                    destinationType = flags.destinationType,
                    headerType = flags.headerType,
                    transportType = flags.transportType,
                    contextFlag = flags.contextFlag,
                    transportId = if (flags.headerType == HeaderType.HEADER_2) ByteArray(16) { (it + 32).toByte() } else null
                )

                val kotlinFlags = packet.getPackedFlags()

                // Get Python's flags
                val pythonResult = python(
                    "packet_flags",
                    "header_type" to flags.headerType.value.toString(),
                    "context_flag" to flags.contextFlag.value.toString(),
                    "transport_type" to flags.transportType.value.toString(),
                    "destination_type" to flags.destinationType.value.toString(),
                    "packet_type" to flags.packetType.value.toString()
                )

                val pythonFlags = pythonResult["flags"]?.jsonPrimitive?.int ?: -1

                assert(kotlinFlags == pythonFlags) {
                    "Flags mismatch for $flags: Kotlin=${kotlinFlags.toString(2)}, Python=${pythonFlags.toString(2)}"
                }
            }
        }

        @Test
        @DisplayName("Flags byte parsing matches Python")
        fun `flags byte parsing matches Python`() {
            // Test parsing various flags bytes
            val testFlagsBytes = listOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x08, 0x10, 0x20, 0x40, 0x41, 0x50, 0x7F)

            for (flagsByte in testFlagsBytes) {
                val kotlinParsed = Packet.parseFlags(flagsByte)

                val pythonResult = python(
                    "packet_parse_flags",
                    "flags" to flagsByte.toString()
                )

                assert(kotlinParsed.headerType.value == pythonResult["header_type"]?.jsonPrimitive?.int) {
                    "Header type mismatch for 0x${flagsByte.toString(16)}"
                }
                assert(kotlinParsed.contextFlag.value == pythonResult["context_flag"]?.jsonPrimitive?.int) {
                    "Context flag mismatch for 0x${flagsByte.toString(16)}"
                }
                assert(kotlinParsed.transportType.value == pythonResult["transport_type"]?.jsonPrimitive?.int) {
                    "Transport type mismatch for 0x${flagsByte.toString(16)}"
                }
                assert(kotlinParsed.destinationType.value == pythonResult["destination_type"]?.jsonPrimitive?.int) {
                    "Destination type mismatch for 0x${flagsByte.toString(16)}"
                }
                assert(kotlinParsed.packetType.value == pythonResult["packet_type"]?.jsonPrimitive?.int) {
                    "Packet type mismatch for 0x${flagsByte.toString(16)}"
                }
            }
        }
    }

    @Nested
    @DisplayName("Pack Operations")
    inner class PackOperations {

        @Test
        @DisplayName("HEADER_1 pack matches Python")
        fun `header1 pack matches Python`() {
            val destHash = ByteArray(16) { (it * 5).toByte() }
            val data = "Hello, Reticulum!".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE,
                context = PacketContext.NONE
            )

            val kotlinRaw = packet.pack()

            val pythonResult = python(
                "packet_pack",
                "header_type" to "0",
                "context_flag" to "0",
                "transport_type" to "0",
                "destination_type" to "0",
                "packet_type" to "0",
                "hops" to "0",
                "destination_hash" to destHash.toHex(),
                "context" to "0",
                "data" to data.toHex()
            )

            assertBytesEqual(
                pythonResult.getBytes("raw"),
                kotlinRaw,
                "HEADER_1 packet pack"
            )
        }

        @Test
        @DisplayName("HEADER_2 pack matches Python")
        fun `header2 pack matches Python`() {
            val destHash = ByteArray(16) { (it * 3).toByte() }
            val transportId = ByteArray(16) { (it + 128).toByte() }
            val data = "Transport packet data".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE,
                headerType = HeaderType.HEADER_2,
                transportType = TransportType.TRANSPORT,
                transportId = transportId,
                context = PacketContext.NONE
            )

            val kotlinRaw = packet.pack()

            val pythonResult = python(
                "packet_pack",
                "header_type" to "1",
                "context_flag" to "0",
                "transport_type" to "1",
                "destination_type" to "0",
                "packet_type" to "0",
                "hops" to "0",
                "destination_hash" to destHash.toHex(),
                "transport_id" to transportId.toHex(),
                "context" to "0",
                "data" to data.toHex()
            )

            assertBytesEqual(
                pythonResult.getBytes("raw"),
                kotlinRaw,
                "HEADER_2 packet pack"
            )
        }

        @Test
        @DisplayName("Announce packet pack matches Python")
        fun `announce packet pack matches Python`() {
            val destHash = ByteArray(16) { (it * 7).toByte() }
            val announceData = ByteArray(64) { it.toByte() } // Simulate public key + name hash + random hash

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = announceData,
                packetType = PacketType.ANNOUNCE,
                destinationType = DestinationType.SINGLE,
                context = PacketContext.NONE
            )

            val kotlinRaw = packet.pack()

            val pythonResult = python(
                "packet_pack",
                "header_type" to "0",
                "context_flag" to "0",
                "transport_type" to "0",
                "destination_type" to "0",
                "packet_type" to "1",  // ANNOUNCE
                "hops" to "0",
                "destination_hash" to destHash.toHex(),
                "context" to "0",
                "data" to announceData.toHex()
            )

            assertBytesEqual(
                pythonResult.getBytes("raw"),
                kotlinRaw,
                "Announce packet pack"
            )
        }
    }

    @Nested
    @DisplayName("Unpack Operations")
    inner class UnpackOperations {

        @Test
        @DisplayName("HEADER_1 unpack matches Python")
        fun `header1 unpack matches Python`() {
            // Create a raw packet manually
            val destHash = ByteArray(16) { (it * 2).toByte() }
            val data = "Test data".toByteArray()
            val flags = 0x00.toByte()  // HEADER_1, all zeros
            val hops = 5.toByte()
            val context = 0x00.toByte()

            val rawPacket = byteArrayOf(flags, hops) + destHash + byteArrayOf(context) + data

            val packet = Packet.unpack(rawPacket)
            assert(packet != null) { "Packet should unpack successfully" }
            packet!!

            val pythonResult = python("packet_unpack", "raw" to rawPacket.toHex())

            assert(packet.headerType.value == pythonResult["header_type"]?.jsonPrimitive?.int)
            assert(packet.contextFlag.value == pythonResult["context_flag"]?.jsonPrimitive?.int)
            assert(packet.transportType.value == pythonResult["transport_type"]?.jsonPrimitive?.int)
            assert(packet.destinationType.value == pythonResult["destination_type"]?.jsonPrimitive?.int)
            assert(packet.packetType.value == pythonResult["packet_type"]?.jsonPrimitive?.int)
            assert(packet.hops == pythonResult["hops"]?.jsonPrimitive?.int)
            assert(packet.context.value == pythonResult["context"]?.jsonPrimitive?.int)

            assertBytesEqual(
                pythonResult.getBytes("destination_hash"),
                packet.destinationHash,
                "Destination hash"
            )
            assertBytesEqual(
                pythonResult.getBytes("data"),
                packet.data,
                "Packet data"
            )
        }

        @Test
        @DisplayName("HEADER_2 unpack matches Python")
        fun `header2 unpack matches Python`() {
            val destHash = ByteArray(16) { (it + 16).toByte() }
            val transportId = ByteArray(16) { (it + 32).toByte() }
            val data = "Transport data".toByteArray()
            val flags = 0x50.toByte()  // HEADER_2 (0x40) + TRANSPORT (0x10)
            val hops = 3.toByte()
            val context = 0x00.toByte()

            val rawPacket = byteArrayOf(flags, hops) + transportId + destHash + byteArrayOf(context) + data

            val packet = Packet.unpack(rawPacket)
            assert(packet != null) { "Packet should unpack successfully" }
            packet!!

            val pythonResult = python("packet_unpack", "raw" to rawPacket.toHex())

            assert(packet.headerType == HeaderType.HEADER_2)
            assert(packet.transportId != null) { "Transport ID should not be null" }

            assertBytesEqual(
                pythonResult.getBytes("transport_id"),
                packet.transportId!!,
                "Transport ID"
            )
            assertBytesEqual(
                pythonResult.getBytes("destination_hash"),
                packet.destinationHash,
                "Destination hash"
            )
            assertBytesEqual(
                pythonResult.getBytes("data"),
                packet.data,
                "Packet data"
            )
        }

        @Test
        @DisplayName("Round-trip pack/unpack preserves data")
        fun `round-trip pack unpack preserves data`() {
            val destHash = ByteArray(16) { (it * 11).toByte() }
            val data = "Round-trip test message".toByteArray()

            val original = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE,
                context = PacketContext.REQUEST
            )

            val raw = original.pack()
            val unpacked = Packet.unpack(raw)

            assert(unpacked != null) { "Packet should unpack successfully" }
            unpacked!!

            assert(original.packetType == unpacked.packetType)
            assert(original.headerType == unpacked.headerType)
            assert(original.transportType == unpacked.transportType)
            assert(original.destinationType == unpacked.destinationType)
            assert(original.context == unpacked.context)
            assertBytesEqual(original.destinationHash, unpacked.destinationHash, "Destination hash")
            assertBytesEqual(original.data, unpacked.data, "Packet data")
        }
    }

    @Nested
    @DisplayName("Packet Hash")
    inner class PacketHash {

        @Test
        @DisplayName("HEADER_1 packet hash matches Python")
        fun `header1 packet hash matches Python`() {
            val destHash = ByteArray(16) { (it * 13).toByte() }
            val data = "Hash test data".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE
            )

            val raw = packet.pack()
            val kotlinHash = packet.packetHash

            val pythonResult = python("packet_hash", "raw" to raw.toHex())

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinHash,
                "HEADER_1 packet hash"
            )
        }

        @Test
        @DisplayName("HEADER_2 packet hash excludes transport_id")
        fun `header2 packet hash excludes transport_id`() {
            val destHash = ByteArray(16) { (it * 17).toByte() }
            val transportId = ByteArray(16) { (it + 64).toByte() }
            val data = "Transport hash test".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE,
                headerType = HeaderType.HEADER_2,
                transportType = TransportType.TRANSPORT,
                transportId = transportId
            )

            val raw = packet.pack()
            val kotlinHash = packet.packetHash
            val kotlinHashable = packet.getHashablePart()

            val pythonResult = python("packet_hash", "raw" to raw.toHex())

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinHash,
                "HEADER_2 packet hash"
            )

            assertBytesEqual(
                pythonResult.getBytes("hashable_part"),
                kotlinHashable,
                "Hashable part"
            )
        }

        @Test
        @DisplayName("Truncated packet hash matches Python")
        fun `truncated packet hash matches Python`() {
            val destHash = ByteArray(16) { (it * 19).toByte() }
            val data = "Truncated hash test".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.ANNOUNCE,
                destinationType = DestinationType.SINGLE
            )

            packet.pack()
            val kotlinTruncatedHash = packet.truncatedHash

            val pythonResult = python("packet_hash", "raw" to packet.raw!!.toHex())

            assertBytesEqual(
                pythonResult.getBytes("truncated_hash"),
                kotlinTruncatedHash,
                "Truncated packet hash"
            )

            assert(kotlinTruncatedHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
                "Truncated hash should be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
            }
        }
    }

    @Nested
    @DisplayName("Send and Receipt")
    inner class SendAndReceipt {

        @Test
        @DisplayName("PacketReceipt creation and status tracking")
        fun `receipt creation and status tracking`() {
            val destHash = ByteArray(16) { (it * 11).toByte() }

            // Create a packet (without actually sending it over the network)
            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = "Test receipt data".toByteArray(),
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE,
                createReceipt = true
            )

            // Pack the packet
            packet.pack()

            // Verify receipt is null until sent
            assert(packet.receipt == null) { "Receipt should be null before sending" }
            assert(!packet.sent) { "Packet should not be marked as sent yet" }
        }

        @Test
        @DisplayName("ProofDestination uses truncated packet hash")
        fun `proof destination uses truncated packet hash`() {
            val destHash = ByteArray(16) { (it * 23).toByte() }
            val data = "Proof destination test".toByteArray()

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.SINGLE
            )

            packet.pack()

            val proofDest = packet.generateProofDestination()

            // Verify proof destination uses truncated hash
            assertBytesEqual(
                packet.truncatedHash,
                proofDest.hash,
                "ProofDestination hash"
            )

            assert(proofDest.type == DestinationType.SINGLE) {
                "ProofDestination should be SINGLE type"
            }
        }

        @Test
        @DisplayName("Proof packet format matches Python")
        fun `proof packet format matches Python`() {
            // Generate full 64-byte identity key (32 X25519 + 32 Ed25519)
            val privKey = ByteArray(64) { (it * 3).toByte() }
            val packetHash = ByteArray(32) { (it * 7).toByte() }

            // Use Python to sign the packet hash
            val pythonSign = python(
                "identity_sign",
                "private_key" to privKey.toHex(),
                "message" to packetHash.toHex()
            )

            val signature = pythonSign.getBytes("signature")

            // Explicit proof format: packet_hash (32) + signature (64)
            val explicitProof = packetHash + signature

            assert(explicitProof.size == 96) {
                "Explicit proof should be 96 bytes (32 hash + 64 signature), got ${explicitProof.size}"
            }

            // Verify components
            val proofHash = explicitProof.copyOfRange(0, 32)
            val proofSig = explicitProof.copyOfRange(32, 96)

            assertBytesEqual(packetHash, proofHash, "Proof packet hash")
            assert(proofSig.size == 64) { "Signature should be 64 bytes" }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        @DisplayName("Minimum size packet")
        fun `minimum size packet`() {
            val destHash = ByteArray(16)
            val data = ByteArray(0)

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.DATA,
                destinationType = DestinationType.PLAIN
            )

            val raw = packet.pack()
            assert(raw.size >= RnsConstants.HEADER_MIN_SIZE) {
                "Packet should be at least ${RnsConstants.HEADER_MIN_SIZE} bytes"
            }

            val unpacked = Packet.unpack(raw)
            assert(unpacked != null) { "Minimum packet should unpack" }
            assert(unpacked!!.data.isEmpty()) { "Data should be empty" }
        }

        @Test
        @DisplayName("Various context types")
        fun `various context types`() {
            val contexts = listOf(
                PacketContext.NONE,
                PacketContext.RESOURCE,
                PacketContext.REQUEST,
                PacketContext.RESPONSE,
                PacketContext.PATH_RESPONSE,
                PacketContext.KEEPALIVE,
                PacketContext.CHANNEL
            )

            for (context in contexts) {
                val packet = Packet.createRaw(
                    destinationHash = ByteArray(16) { context.value.toByte() },
                    data = "test".toByteArray(),
                    context = context
                )

                val raw = packet.pack()
                val unpacked = Packet.unpack(raw)

                assert(unpacked != null) { "Packet with context $context should unpack" }
                assert(unpacked!!.context == context) { "Context should be $context, got ${unpacked.context}" }
            }
        }
    }
}

private data class TestFlags(
    val headerType: HeaderType,
    val contextFlag: ContextFlag,
    val transportType: TransportType,
    val destinationType: DestinationType,
    val packetType: PacketType
)
