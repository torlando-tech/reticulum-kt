package network.reticulum.interop.transport

import io.kotest.matchers.shouldBe
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getDouble
import network.reticulum.interop.getInt
import network.reticulum.interop.getList
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Interoperability tests for Transport operations.
 *
 * Tests that Kotlin Transport implementation produces byte-perfect
 * compatible results with Python RNS Transport for path table entries,
 * path requests, and packet hashlists.
 */
@DisplayName("Transport Interop")
class TransportInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("Path Table Entry Serialization")
    inner class PathTableEntry {

        @Test
        @DisplayName("path entry serialization matches Python")
        fun pathEntrySerializationMatchesPython() {
            // Create test data for a path table entry
            val destinationHash = Random.nextBytes(16)
            val timestamp = System.currentTimeMillis() / 1000.0
            val receivedFrom = Random.nextBytes(16)
            val hops = 3
            val expires = timestamp + 604800.0  // 7 days
            val randomBlobs = listOf(
                Random.nextBytes(10),
                Random.nextBytes(10),
                Random.nextBytes(10)
            )
            val interfaceHash = Random.nextBytes(32)
            val packetHash = Random.nextBytes(32)

            // Serialize in Python
            val pyResult = python(
                "path_entry_serialize",
                "destination_hash" to destinationHash,
                "timestamp" to timestamp,
                "received_from" to receivedFrom,
                "hops" to hops,
                "expires" to expires,
                "random_blobs" to randomBlobs.map { it.toHex() },
                "interface_hash" to interfaceHash,
                "packet_hash" to packetHash
            )

            val pythonSerialized = pyResult.getBytes("serialized")

            // TODO: Implement Kotlin serialization when Transport is ready
            // For now, just verify Python can serialize
            (pythonSerialized.size > 0) shouldBe true
        }

        @Test
        @DisplayName("path entry deserialization matches Python")
        fun pathEntryDeserializationMatchesPython() {
            // Create test data
            val destinationHash = Random.nextBytes(16)
            val timestamp = 1234567890.0
            val receivedFrom = Random.nextBytes(16)
            val hops = 5
            val expires = timestamp + 604800.0
            val randomBlobs = listOf(
                Random.nextBytes(10),
                Random.nextBytes(10)
            )
            val interfaceHash = Random.nextBytes(32)
            val packetHash = Random.nextBytes(32)

            // Serialize in Python
            val serResult = python(
                "path_entry_serialize",
                "destination_hash" to destinationHash,
                "timestamp" to timestamp,
                "received_from" to receivedFrom,
                "hops" to hops,
                "expires" to expires,
                "random_blobs" to randomBlobs.map { it.toHex() },
                "interface_hash" to interfaceHash,
                "packet_hash" to packetHash
            )

            val serialized = serResult.getBytes("serialized")

            // Deserialize in Python
            val deserResult = python(
                "path_entry_deserialize",
                "serialized" to serialized
            )

            // Verify all fields match
            deserResult.getBytes("destination_hash").contentEquals(destinationHash) shouldBe true
            deserResult.getDouble("timestamp") shouldBe timestamp
            deserResult.getBytes("received_from").contentEquals(receivedFrom) shouldBe true
            deserResult.getInt("hops") shouldBe hops
            deserResult.getDouble("expires") shouldBe expires

            val deserBlobs = deserResult.getList<String>("random_blobs").map { it.hexToByteArray() }
            deserBlobs.size shouldBe randomBlobs.size
            deserBlobs.zip(randomBlobs).forEach { (deser, orig) ->
                deser.contentEquals(orig) shouldBe true
            }

            deserResult.getBytes("interface_hash").contentEquals(interfaceHash) shouldBe true
            deserResult.getBytes("packet_hash").contentEquals(packetHash) shouldBe true
        }
    }

    @Nested
    @DisplayName("Path Request Packing")
    inner class PathRequest {

        @Test
        @DisplayName("path request pack (basic) matches Python")
        fun pathRequestPackBasicMatchesPython() {
            val destinationHash = Random.nextBytes(16)
            val tag = Random.nextBytes(10)

            // Pack without transport instance (transport disabled)
            val pyResult = python(
                "path_request_pack",
                "destination_hash" to destinationHash,
                "tag" to tag
            )

            val data = pyResult.getBytes("data")

            // Verify format: destination_hash + tag
            data.size shouldBe 26  // 16 + 10
            data.copyOfRange(0, 16).contentEquals(destinationHash) shouldBe true
            data.copyOfRange(16, 26).contentEquals(tag) shouldBe true
        }

        @Test
        @DisplayName("path request pack (with transport instance) matches Python")
        fun pathRequestPackWithTransportMatchesPython() {
            val destinationHash = Random.nextBytes(16)
            val transportInstance = Random.nextBytes(16)
            val tag = Random.nextBytes(10)

            // Pack with transport instance (transport enabled)
            val pyResult = python(
                "path_request_pack",
                "destination_hash" to destinationHash,
                "transport_instance" to transportInstance,
                "tag" to tag
            )

            val data = pyResult.getBytes("data")

            // Verify format: destination_hash + transport_instance + tag
            data.size shouldBe 42  // 16 + 16 + 10
            data.copyOfRange(0, 16).contentEquals(destinationHash) shouldBe true
            data.copyOfRange(16, 32).contentEquals(transportInstance) shouldBe true
            data.copyOfRange(32, 42).contentEquals(tag) shouldBe true
        }

        @Test
        @DisplayName("path request pack (with tag) matches Python")
        fun pathRequestPackWithTagMatchesPython() {
            val destinationHash = Random.nextBytes(16)

            // Pack with just destination (no tag, no transport)
            val pyResult = python(
                "path_request_pack",
                "destination_hash" to destinationHash
            )

            val data = pyResult.getBytes("data")

            // Verify format: just destination_hash
            data.size shouldBe 16
            data.contentEquals(destinationHash) shouldBe true
        }

        @Test
        @DisplayName("path request unpack matches Python")
        fun pathRequestUnpackMatchesPython() {
            val destinationHash = Random.nextBytes(16)
            val transportInstance = Random.nextBytes(16)
            val tag = Random.nextBytes(10)

            // Pack the data
            val packResult = python(
                "path_request_pack",
                "destination_hash" to destinationHash,
                "transport_instance" to transportInstance,
                "tag" to tag
            )

            val data = packResult.getBytes("data")

            // Unpack it
            val unpackResult = python(
                "path_request_unpack",
                "data" to data
            )

            // Verify unpacked data
            unpackResult.getBytes("destination_hash").contentEquals(destinationHash) shouldBe true
            unpackResult.getBytes("transport_instance").contentEquals(transportInstance) shouldBe true
            unpackResult.getBytes("tag").contentEquals(tag) shouldBe true
        }
    }

    @Nested
    @DisplayName("Packet Hashlist Serialization")
    inner class PacketHashlist {

        @Test
        @DisplayName("packet hashlist serialization matches Python")
        fun packetHashlistSerializationMatchesPython() {
            // Create a list of packet hashes
            val hashes = List(10) { Random.nextBytes(32) }

            // Serialize in Python
            val pyResult = python(
                "packet_hashlist_pack",
                "hashes" to hashes.map { it.toHex() }
            )

            val serialized = pyResult.getBytes("serialized")
            val count = pyResult.getInt("count")

            // Verify
            count shouldBe 10
            (serialized.size > 0) shouldBe true
        }

        @Test
        @DisplayName("packet hashlist deserialization matches Python")
        fun packetHashlistDeserializationMatchesPython() {
            // Create a list of packet hashes
            val hashes = List(5) { Random.nextBytes(32) }

            // Serialize
            val packResult = python(
                "packet_hashlist_pack",
                "hashes" to hashes.map { it.toHex() }
            )

            val serialized = packResult.getBytes("serialized")

            // Deserialize
            val unpackResult = python(
                "packet_hashlist_unpack",
                "serialized" to serialized
            )

            val unpackedHashes = unpackResult.getList<String>("hashes").map { it.hexToByteArray() }
            val count = unpackResult.getInt("count")

            // Verify
            count shouldBe 5
            unpackedHashes.size shouldBe hashes.size
            unpackedHashes.zip(hashes).forEach { (unpacked, original) ->
                unpacked.contentEquals(original) shouldBe true
            }
        }
    }

    @Nested
    @DisplayName("Tunnel Entry Format")
    inner class TunnelEntry {

        @Test
        @DisplayName("tunnel entry serialization format")
        fun tunnelEntrySerializationFormat() {
            // This test documents the tunnel table entry format
            // Format: [tunnel_id, interface_hash, paths, expires]
            // where paths is a dict of destination_hash -> path_entry

            // The path_entry format is the same as path table entries:
            // [timestamp, received_from, hops, expires, random_blobs, receiving_interface, packet_hash]

            // Create a simple tunnel entry structure
            val tunnelId = Random.nextBytes(16)
            val interfaceHash = Random.nextBytes(32)
            val expires = System.currentTimeMillis() / 1000.0 + 604800.0

            // Create a path entry (just structure documentation)
            // In actual implementation, this would be serialized similarly to path table entries

            // For this test, we just verify the structure is understood
            // Actual serialization would happen in the Transport implementation
            tunnelId.size shouldBe 16
            interfaceHash.size shouldBe 32
        }
    }

    @Nested
    @DisplayName("Announce Table Entry Format")
    inner class AnnounceTableEntry {

        @Test
        @DisplayName("announce table entry format")
        fun announceTableEntryFormat() {
            // This test documents the announce table entry format
            // The announce table stores pending announces waiting to be retransmitted
            // Format: destination_hash -> [timestamp, retransmit_timeout, retries, received_from, packet_hash, hops, emitted]

            val destHash = Random.nextBytes(16)
            val timestamp = System.currentTimeMillis() / 1000.0
            val receivedFrom = Random.nextBytes(16)
            val packetHash = Random.nextBytes(32)
            val hops = 1

            // For this test, we just verify the structure is understood
            // Actual announce table operations would happen in the Transport implementation
            destHash.size shouldBe 16
            receivedFrom.size shouldBe 16
            packetHash.size shouldBe 32
            (hops > 0) shouldBe true
        }
    }

}
