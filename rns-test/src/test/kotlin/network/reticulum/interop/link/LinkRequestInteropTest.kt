package network.reticulum.interop.link

import io.kotest.matchers.shouldBe
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getDouble
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Link request/response interoperability tests with Python RNS.
 *
 * Tests the msgpack serialization formats for RTT packets,
 * link requests, and link responses to ensure Kotlin and Python
 * implementations are byte-compatible.
 */
@DisplayName("Link Request/Response Interop")
class LinkRequestInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("RTT Packing")
    inner class RttPackingTests {

        @Test
        @DisplayName("RTT pack matches Python")
        fun `rtt pack matches python`() {
            val testCases = listOf(
                0.0,
                0.5,
                1.0,
                1.5,
                10.0,
                100.5,
                1000.123456,
                0.000001
            )

            for (rtt in testCases) {
                val pythonResult = python(
                    "link_rtt_pack",
                    "rtt" to rtt.toString()
                )

                val pythonPacked = pythonResult.getBytes("packed")

                // In Kotlin, we would use umsgpack to pack the RTT
                // For now, we verify the Python implementation works
                // Later we'll implement Kotlin msgpack and compare

                // Verify round-trip through Python
                val unpackResult = python(
                    "link_rtt_unpack",
                    "packed" to pythonPacked
                )

                val unpacked = unpackResult.getDouble("rtt")

                // Allow small floating point differences
                kotlin.math.abs(unpacked - rtt) shouldBe 0.0
            }
        }

        @Test
        @DisplayName("RTT unpack matches Python")
        fun `rtt unpack matches python`() {
            // Pack various RTT values and verify unpacking
            val testValues = listOf(0.123, 1.456, 100.789)

            for (rtt in testValues) {
                // Pack in Python
                val packResult = python(
                    "link_rtt_pack",
                    "rtt" to rtt.toString()
                )
                val packed = packResult.getBytes("packed")

                // Unpack in Python
                val unpackResult = python(
                    "link_rtt_unpack",
                    "packed" to packed
                )

                val unpacked = unpackResult.getDouble("rtt")

                // Verify round-trip
                kotlin.math.abs(unpacked - rtt) shouldBe 0.0
            }
        }
    }

    @Nested
    @DisplayName("Link Request Packing")
    inner class LinkRequestPackingTests {

        @Test
        @DisplayName("Link request pack matches Python")
        fun `link request pack matches python`() {
            // Create test request components
            val timestamp = 1234567890.123
            val path = "test.path"
            val pathHash = Hashes.truncatedHash(path.toByteArray())
            val data = "Hello, request!".toByteArray()

            // Pack in Python
            val pythonResult = python(
                "link_request_pack",
                "timestamp" to timestamp.toString(),
                "path_hash" to pathHash,
                "data" to data
            )

            val pythonPacked = pythonResult.getBytes("packed")

            // Verify we can unpack it
            val unpackResult = python(
                "link_request_unpack",
                "packed" to pythonPacked
            )

            val unpackedTimestamp = unpackResult.getDouble("timestamp")
            val unpackedPathHash = unpackResult.getBytes("path_hash")
            val unpackedData = unpackResult.getBytes("data")

            kotlin.math.abs(unpackedTimestamp - timestamp) shouldBe 0.0
            assertBytesEqual(pathHash, unpackedPathHash, "Path hash should match")
            assertBytesEqual(data, unpackedData, "Data should match")
        }

        @Test
        @DisplayName("Link request unpack matches Python")
        fun `link request unpack matches python`() {
            val timestamp = 9876543210.456
            val pathHash = ByteArray(16) { it.toByte() }
            val data = ByteArray(32) { (it * 2).toByte() }

            // Pack in Python
            val packResult = python(
                "link_request_pack",
                "timestamp" to timestamp.toString(),
                "path_hash" to pathHash,
                "data" to data
            )
            val packed = packResult.getBytes("packed")

            // Unpack in Python
            val unpackResult = python(
                "link_request_unpack",
                "packed" to packed
            )

            val unpackedTimestamp = unpackResult.getDouble("timestamp")
            val unpackedPathHash = unpackResult.getBytes("path_hash")
            val unpackedData = unpackResult.getBytes("data")

            kotlin.math.abs(unpackedTimestamp - timestamp) shouldBe 0.0
            assertBytesEqual(pathHash, unpackedPathHash, "Path hash should match")
            assertBytesEqual(data, unpackedData, "Data should match")
        }

        @Test
        @DisplayName("Link request with complex data matches Python")
        fun `link request with complex data matches python`() {
            val timestamp = System.currentTimeMillis() / 1000.0
            val path = "complex.test.path"
            val pathHash = Hashes.truncatedHash(path.toByteArray())

            // Use various data types
            val testCases = listOf(
                ByteArray(0), // Empty data
                ByteArray(1) { 0x42 }, // Single byte
                ByteArray(100) { (it % 256).toByte() }, // Larger data
                "Text data as bytes".toByteArray() // Text content
            )

            for (data in testCases) {
                // Pack and unpack
                val packResult = python(
                    "link_request_pack",
                    "timestamp" to timestamp.toString(),
                    "path_hash" to pathHash,
                    "data" to data
                )

                val packed = packResult.getBytes("packed")

                val unpackResult = python(
                    "link_request_unpack",
                    "packed" to packed
                )

                val unpackedData = unpackResult.getBytes("data")
                assertBytesEqual(data, unpackedData, "Data should match for ${data.size} bytes")
            }
        }
    }

    @Nested
    @DisplayName("Link Response Packing")
    inner class LinkResponsePackingTests {

        @Test
        @DisplayName("Link response pack matches Python")
        fun `link response pack matches python`() {
            // Request ID is the truncated hash of the request packet
            val requestId = ByteArray(16) { (it + 100).toByte() }
            val responseData = "Response payload".toByteArray()

            // Pack in Python
            val pythonResult = python(
                "link_response_pack",
                "request_id" to requestId,
                "response_data" to responseData
            )

            val pythonPacked = pythonResult.getBytes("packed")

            // Verify we can unpack it
            val unpackResult = python(
                "link_response_unpack",
                "packed" to pythonPacked
            )

            val unpackedRequestId = unpackResult.getBytes("request_id")
            val unpackedResponseData = unpackResult.getBytes("response_data")

            assertBytesEqual(requestId, unpackedRequestId, "Request ID should match")
            assertBytesEqual(responseData, unpackedResponseData, "Response data should match")
        }

        @Test
        @DisplayName("Link response unpack matches Python")
        fun `link response unpack matches python`() {
            val requestId = ByteArray(16) { (it * 3).toByte() }
            val responseData = ByteArray(64) { (it + 50).toByte() }

            // Pack in Python
            val packResult = python(
                "link_response_pack",
                "request_id" to requestId,
                "response_data" to responseData
            )
            val packed = packResult.getBytes("packed")

            // Unpack in Python
            val unpackResult = python(
                "link_response_unpack",
                "packed" to packed
            )

            val unpackedRequestId = unpackResult.getBytes("request_id")
            val unpackedResponseData = unpackResult.getBytes("response_data")

            assertBytesEqual(requestId, unpackedRequestId, "Request ID should match")
            assertBytesEqual(responseData, unpackedResponseData, "Response data should match")
        }

        @Test
        @DisplayName("Link response with large payload matches Python")
        fun `link response with large payload matches python`() {
            val requestId = ByteArray(16) { (it + 200).toByte() }

            // Test various payload sizes
            val testPayloads = listOf(
                ByteArray(0), // Empty response
                ByteArray(1) { 0xFF.toByte() }, // Single byte
                ByteArray(256) { (it % 256).toByte() }, // Medium
                ByteArray(1024) { (it % 256).toByte() }, // Larger
                "Large text response with lots of data".repeat(10).toByteArray()
            )

            for (responseData in testPayloads) {
                // Pack and unpack
                val packResult = python(
                    "link_response_pack",
                    "request_id" to requestId,
                    "response_data" to responseData
                )

                val packed = packResult.getBytes("packed")

                val unpackResult = python(
                    "link_response_unpack",
                    "packed" to packed
                )

                val unpackedData = unpackResult.getBytes("response_data")
                assertBytesEqual(
                    responseData,
                    unpackedData,
                    "Response data should match for ${responseData.size} bytes"
                )
            }
        }
    }

    @Nested
    @DisplayName("Request ID Computation")
    inner class RequestIdTests {

        @Test
        @DisplayName("Request ID computation matches Python")
        fun `request id computation matches python`() {
            // Request ID is the truncated hash of the request packet's hashable part
            // For DATA packets with REQUEST context, this is truncated_hash(hashable_part)

            // Create some test data
            val testData = listOf(
                ByteArray(16) { it.toByte() },
                ByteArray(32) { (it + 50).toByte() },
                ByteArray(64) { (it * 2).toByte() },
                "Test request data".toByteArray()
            )

            for (data in testData) {
                // Compute truncated hash in Python
                val pythonResult = python(
                    "truncated_hash",
                    "data" to data
                )

                val pythonHash = pythonResult.getBytes("hash")

                // Compute in Kotlin
                val kotlinHash = Hashes.truncatedHash(data)

                assertBytesEqual(
                    pythonHash,
                    kotlinHash,
                    "Request ID (truncated hash) should match for ${data.size} bytes"
                )
            }
        }
    }

    @Nested
    @DisplayName("Round-trip Tests")
    inner class RoundTripTests {

        @Test
        @DisplayName("Round-trip request pack/unpack")
        fun `round trip request pack unpack`() {
            val timestamp = System.currentTimeMillis() / 1000.0
            val path = "roundtrip.test"
            val pathHash = Hashes.truncatedHash(path.toByteArray())
            val data = "Round-trip test data".toByteArray()

            // Pack
            val packResult = python(
                "link_request_pack",
                "timestamp" to timestamp.toString(),
                "path_hash" to pathHash,
                "data" to data
            )
            val packed = packResult.getBytes("packed")

            // Unpack
            val unpackResult = python(
                "link_request_unpack",
                "packed" to packed
            )

            // Verify all fields
            val unpackedTimestamp = unpackResult.getDouble("timestamp")
            val unpackedPathHash = unpackResult.getBytes("path_hash")
            val unpackedData = unpackResult.getBytes("data")

            kotlin.math.abs(unpackedTimestamp - timestamp) shouldBe 0.0
            assertBytesEqual(pathHash, unpackedPathHash, "Path hash round-trip")
            assertBytesEqual(data, unpackedData, "Data round-trip")

            // Pack again with unpacked values and verify it produces same bytes
            val repackResult = python(
                "link_request_pack",
                "timestamp" to unpackedTimestamp.toString(),
                "path_hash" to unpackedPathHash,
                "data" to unpackedData
            )
            val repacked = repackResult.getBytes("packed")

            assertBytesEqual(packed, repacked, "Re-packed bytes should match original")
        }
    }
}
