package network.reticulum.interop.resource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.toHexString
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import network.reticulum.interop.toHex
import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.resource.ResourceConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.min

/**
 * Interoperability tests for Resource operations.
 *
 * Tests that Kotlin Resource implementation produces byte-perfect
 * compatible results with Python RNS Resource.
 */
@DisplayName("Resource Interop")
class ResourceInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("ResourceAdvertisement Serialization")
    inner class ResourceAdvertisementSerialization {

        @Test
        @DisplayName("Resource advertisement pack matches Python")
        fun resourceAdvertisementPackMatchesPython() {
            // Create sample advertisement data
            val transferSize = 102400  // 100KB
            val dataSize = 204800  // 200KB (before compression)
            val numParts = 50
            val resourceHash = crypto.randomBytes(16)
            val randomHash = crypto.randomBytes(4)
            val originalHash = crypto.randomBytes(16)
            val segmentIndex = 1
            val totalSegments = 2
            val flags = 0x06  // compressed + split

            // Create hashmap (4 bytes per part)
            val hashmap = ByteArray(numParts * 4) { it.toByte() }

            // Pack in Python first
            val pyPackResult = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to segmentIndex,
                "total_segments" to totalSegments,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 0
            )

            val pythonPacked = pyPackResult.getBytes("packed")

            // Unpack with Kotlin and repack
            val kotlinAdv = ResourceAdvertisement.unpack(pythonPacked)
            kotlinAdv shouldNotBe null

            val kotlinPacked = kotlinAdv!!.pack(segment = 0)

            assertBytesEqual(
                pythonPacked,
                kotlinPacked,
                "ResourceAdvertisement pack should match Python"
            )
        }

        @Test
        @DisplayName("Resource advertisement unpack matches Python")
        fun resourceAdvertisementUnpackMatchesPython() {
            // Create sample data to pack
            val transferSize = 51200
            val dataSize = 102400
            val numParts = 25
            val resourceHash = crypto.randomBytes(16)
            val randomHash = crypto.randomBytes(4)
            val originalHash = crypto.randomBytes(16)
            val segmentIndex = 1
            val totalSegments = 1
            val flags = 0x02  // compressed only
            val hashmap = ByteArray(numParts * 4) { (it % 256).toByte() }

            // Pack with Python
            val pyPackResult = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to segmentIndex,
                "total_segments" to totalSegments,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 0
            )

            val packed = pyPackResult.getBytes("packed")

            // Unpack with Kotlin
            val kotlinAdv = ResourceAdvertisement.unpack(packed)
            kotlinAdv shouldNotBe null

            // Unpack with Python
            val pyUnpackResult = python(
                "resource_adv_unpack",
                "packed" to packed
            )

            // Compare all fields
            kotlinAdv!!.transferSize shouldBe pyUnpackResult.getInt("transfer_size")
            kotlinAdv.dataSize shouldBe pyUnpackResult.getInt("data_size")
            kotlinAdv.numParts shouldBe pyUnpackResult.getInt("num_parts")
            kotlinAdv.hash.toHex() shouldBe pyUnpackResult.getString("resource_hash")
            kotlinAdv.randomHash.toHex() shouldBe pyUnpackResult.getString("random_hash")
            kotlinAdv.originalHash.toHex() shouldBe pyUnpackResult.getString("original_hash")
            kotlinAdv.segmentIndex shouldBe pyUnpackResult.getInt("segment_index")
            kotlinAdv.totalSegments shouldBe pyUnpackResult.getInt("total_segments")
            kotlinAdv.flags shouldBe pyUnpackResult.getInt("flags")
            kotlinAdv.compressed shouldBe pyUnpackResult.getBoolean("compressed")
        }

        @Test
        @DisplayName("Flag byte encoding matches Python")
        fun flagByteEncodingMatchesPython() {
            // Test all flag combinations
            val testCases = listOf(
                mapOf(
                    "encrypted" to true,
                    "compressed" to false,
                    "split" to false,
                    "is_request" to false,
                    "is_response" to false,
                    "has_metadata" to false
                ),
                mapOf(
                    "encrypted" to false,
                    "compressed" to true,
                    "split" to false,
                    "is_request" to false,
                    "is_response" to false,
                    "has_metadata" to false
                ),
                mapOf(
                    "encrypted" to true,
                    "compressed" to true,
                    "split" to true,
                    "is_request" to false,
                    "is_response" to false,
                    "has_metadata" to false
                ),
                mapOf(
                    "encrypted" to false,
                    "compressed" to false,
                    "split" to false,
                    "is_request" to true,
                    "is_response" to false,
                    "has_metadata" to false
                ),
                mapOf(
                    "encrypted" to false,
                    "compressed" to false,
                    "split" to false,
                    "is_request" to false,
                    "is_response" to true,
                    "has_metadata" to false
                ),
                mapOf(
                    "encrypted" to false,
                    "compressed" to false,
                    "split" to false,
                    "is_request" to false,
                    "is_response" to false,
                    "has_metadata" to true
                ),
                mapOf(
                    "encrypted" to true,
                    "compressed" to true,
                    "split" to true,
                    "is_request" to true,
                    "is_response" to true,
                    "has_metadata" to true
                )
            )

            for (testCase in testCases) {
                val encrypted = testCase["encrypted"] as Boolean
                val compressed = testCase["compressed"] as Boolean
                val split = testCase["split"] as Boolean
                val isRequest = testCase["is_request"] as Boolean
                val isResponse = testCase["is_response"] as Boolean
                val hasMetadata = testCase["has_metadata"] as Boolean

                // Build flags in Kotlin (using companion method)
                var kotlinFlags = 0x00
                if (encrypted) kotlinFlags = kotlinFlags or 0x01
                if (compressed) kotlinFlags = kotlinFlags or 0x02
                if (split) kotlinFlags = kotlinFlags or 0x04
                if (isRequest) kotlinFlags = kotlinFlags or 0x08
                if (isResponse) kotlinFlags = kotlinFlags or 0x10
                if (hasMetadata) kotlinFlags = kotlinFlags or 0x20

                // Build flags in Python
                val pyResult = python(
                    "resource_flags",
                    "mode" to "encode",
                    "encrypted" to encrypted,
                    "compressed" to compressed,
                    "split" to split,
                    "is_request" to isRequest,
                    "is_response" to isResponse,
                    "has_metadata" to hasMetadata
                )

                val pythonFlags = pyResult.getInt("flags")

                kotlinFlags shouldBe pythonFlags
            }
        }

        @Test
        @DisplayName("Flag byte decoding matches Python")
        fun flagByteDecodingMatchesPython() {
            val testFlags = listOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x3F)

            for (flags in testFlags) {
                // Decode in Kotlin
                val encrypted = (flags and 0x01) == 0x01
                val compressed = ((flags shr 1) and 0x01) == 0x01
                val split = ((flags shr 2) and 0x01) == 0x01
                val isRequest = ((flags shr 3) and 0x01) == 0x01
                val isResponse = ((flags shr 4) and 0x01) == 0x01
                val hasMetadata = ((flags shr 5) and 0x01) == 0x01

                // Decode in Python
                val pyResult = python(
                    "resource_flags",
                    "mode" to "decode",
                    "flags" to flags
                )

                encrypted shouldBe pyResult.getBoolean("encrypted")
                compressed shouldBe pyResult.getBoolean("compressed")
                split shouldBe pyResult.getBoolean("split")
                isRequest shouldBe pyResult.getBoolean("is_request")
                isResponse shouldBe pyResult.getBoolean("is_response")
                hasMetadata shouldBe pyResult.getBoolean("has_metadata")
            }
        }
    }

    @Nested
    @DisplayName("Resource Hashing")
    inner class ResourceHashing {

        @Test
        @DisplayName("Resource hash computation matches Python")
        fun resourceHashComputationMatchesPython() {
            // Create sample data
            val data = "Test resource data for hashing".toByteArray()
            val randomHash = crypto.randomBytes(4)

            // Compute hash in Kotlin (manual computation)
            val hashMaterial = randomHash + data
            val kotlinHash = crypto.sha256(hashMaterial).copyOfRange(0, 16)

            // Compute hash in Python
            val pyResult = python(
                "resource_hash",
                "data" to data,
                "random_hash" to randomHash
            )

            val pythonHash = pyResult.getBytes("hash")

            assertBytesEqual(
                pythonHash,
                kotlinHash,
                "Resource hash should match Python"
            )
        }
    }

    @Nested
    @DisplayName("Hashmap Operations")
    inner class HashmapOperations {

        @Test
        @DisplayName("Hashmap segment packing matches Python")
        fun hashmapSegmentPackingMatchesPython() {
            // Create sample parts
            val parts = listOf(
                "Part 0 data".toByteArray(),
                "Part 1 data".toByteArray(),
                "Part 2 data".toByteArray(),
                "Part 3 data".toByteArray()
            )

            // Pack hashmap in Kotlin
            val kotlinHashmap = ByteArray(parts.size * 4)
            for (i in parts.indices) {
                val partHash = crypto.sha256(parts[i]).copyOfRange(0, 4)
                partHash.copyInto(kotlinHashmap, i * 4)
            }

            // Pack hashmap in Python
            val pyResult = python(
                "hashmap_pack",
                "parts" to parts.map { it.toHex() },
                "start_index" to 0,
                "count" to parts.size
            )

            val pythonHashmap = pyResult.getBytes("hashmap")

            assertBytesEqual(
                pythonHashmap,
                kotlinHashmap,
                "Hashmap should match Python"
            )
        }

        @Test
        @DisplayName("Hashmap with max entries (56) matches Python")
        fun hashmapWithMaxEntriesMatchesPython() {
            // Create 56 parts (HASHMAP_MAX_LEN)
            val parts = (0 until 56).map { i ->
                "Part $i data with some content".toByteArray()
            }

            // Pack hashmap in Kotlin
            val kotlinHashmap = ByteArray(parts.size * 4)
            for (i in parts.indices) {
                val partHash = crypto.sha256(parts[i]).copyOfRange(0, 4)
                partHash.copyInto(kotlinHashmap, i * 4)
            }

            // Pack hashmap in Python
            val pyResult = python(
                "hashmap_pack",
                "parts" to parts.map { it.toHex() },
                "start_index" to 0,
                "count" to parts.size
            )

            val pythonHashmap = pyResult.getBytes("hashmap")

            assertBytesEqual(
                pythonHashmap,
                kotlinHashmap,
                "Hashmap with 56 entries should match Python"
            )
        }
    }

    @Nested
    @DisplayName("Multi-Segment Resources")
    inner class MultiSegmentResources {

        @Test
        @DisplayName("Multi-segment advertisement matches Python")
        fun multiSegmentAdvertisementMatchesPython() {
            // Create a large resource that requires multiple segments
            val transferSize = 2048000  // ~2MB
            val dataSize = 4096000  // ~4MB before compression
            val numParts = 200  // More than fits in one advertisement
            val resourceHash = crypto.randomBytes(16)
            val randomHash = crypto.randomBytes(4)
            val originalHash = crypto.randomBytes(16)
            val flags = 0x06  // compressed + split

            // Create hashmap
            val hashmap = ByteArray(numParts * 4) { (it % 256).toByte() }

            // Test first segment - pack with Python
            val pyResult1 = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to 1,
                "total_segments" to 4,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 0
            )

            val pythonPacked1 = pyResult1.getBytes("packed")

            // Unpack and repack with Kotlin
            val kotlinAdv1 = ResourceAdvertisement.unpack(pythonPacked1)
            kotlinAdv1 shouldNotBe null

            val kotlinPacked1 = kotlinAdv1!!.pack(segment = 0)

            assertBytesEqual(
                pythonPacked1,
                kotlinPacked1,
                "First segment should match Python"
            )

            // For second segment, we need to pack separately since Kotlin's unpack
            // only contains the hashmap slice from the first segment.
            // Instead, verify that packing segment 1 with the same data produces correct results.
            val pyResult2 = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to 1,
                "total_segments" to 4,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 1
            )

            val pythonPacked2 = pyResult2.getBytes("packed")

            // Unpack the second segment and verify it can be repacked
            val kotlinAdv2 = ResourceAdvertisement.unpack(pythonPacked2)
            kotlinAdv2 shouldNotBe null

            val kotlinPacked2 = kotlinAdv2!!.pack(segment = 0)

            assertBytesEqual(
                pythonPacked2,
                kotlinPacked2,
                "Second segment should match Python when repacked"
            )
        }

        @Test
        @DisplayName("Advertisement with metadata matches Python")
        fun advertisementWithMetadataMatchesPython() {
            val transferSize = 10240
            val dataSize = 20480
            val numParts = 10
            val resourceHash = crypto.randomBytes(16)
            val randomHash = crypto.randomBytes(4)
            val originalHash = crypto.randomBytes(16)
            val segmentIndex = 1
            val totalSegments = 1
            val flags = 0x22  // compressed + has_metadata
            val hashmap = ByteArray(numParts * 4) { it.toByte() }

            // Pack in Python
            val pyResult = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to segmentIndex,
                "total_segments" to totalSegments,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 0
            )

            val pythonPacked = pyResult.getBytes("packed")

            // Unpack and repack with Kotlin
            val kotlinAdv = ResourceAdvertisement.unpack(pythonPacked)
            kotlinAdv shouldNotBe null

            val kotlinPacked = kotlinAdv!!.pack(segment = 0)

            assertBytesEqual(
                pythonPacked,
                kotlinPacked,
                "Advertisement with metadata should match Python"
            )
        }
    }

    @Nested
    @DisplayName("Round-Trip Serialization")
    inner class RoundTripSerialization {

        @Test
        @DisplayName("Round-trip pack/unpack preserves all fields")
        fun roundTripPackUnpackPreservesAllFields() {
            // Create comprehensive test data
            val transferSize = 204800
            val dataSize = 409600
            val numParts = 100
            val resourceHash = crypto.randomBytes(16)
            val randomHash = crypto.randomBytes(4)
            val originalHash = crypto.randomBytes(16)
            val segmentIndex = 2
            val totalSegments = 3
            val requestId = crypto.randomBytes(16)
            val flags = 0x3F  // All flags set
            val hashmap = ByteArray(numParts * 4) { (it % 256).toByte() }

            // Pack with Python
            val pyPackResult = python(
                "resource_adv_pack",
                "transfer_size" to transferSize,
                "data_size" to dataSize,
                "num_parts" to numParts,
                "resource_hash" to resourceHash,
                "random_hash" to randomHash,
                "original_hash" to originalHash,
                "segment_index" to segmentIndex,
                "total_segments" to totalSegments,
                "request_id" to requestId,
                "flags" to flags,
                "hashmap" to hashmap,
                "segment" to 0
            )

            val packed = pyPackResult.getBytes("packed")

            // Unpack with Kotlin
            val unpackedAdv = ResourceAdvertisement.unpack(packed)
            unpackedAdv shouldNotBe null

            // Verify all fields match
            unpackedAdv!!.transferSize shouldBe transferSize
            unpackedAdv.dataSize shouldBe dataSize
            unpackedAdv.numParts shouldBe numParts
            unpackedAdv.hash.contentEquals(resourceHash) shouldBe true
            unpackedAdv.randomHash.contentEquals(randomHash) shouldBe true
            unpackedAdv.originalHash.contentEquals(originalHash) shouldBe true
            unpackedAdv.segmentIndex shouldBe segmentIndex
            unpackedAdv.totalSegments shouldBe totalSegments
            unpackedAdv.requestId!!.contentEquals(requestId) shouldBe true
            unpackedAdv.flags shouldBe flags
            unpackedAdv.encrypted shouldBe true
            unpackedAdv.compressed shouldBe true
            unpackedAdv.split shouldBe true
            unpackedAdv.isRequest shouldBe true
            unpackedAdv.isResponseFlag shouldBe true
            unpackedAdv.hasMetadata shouldBe true

            // Verify hashmap slice (first 56 entries max)
            val expectedHashmapLen = min(56 * 4, hashmap.size)
            unpackedAdv.hashmap.size shouldBe expectedHashmapLen
            unpackedAdv.hashmap.contentEquals(hashmap.copyOfRange(0, expectedHashmapLen)) shouldBe true

            // Re-pack and verify it matches
            val repacked = unpackedAdv.pack(segment = 0)
            repacked.contentEquals(packed) shouldBe true
        }
    }
}
