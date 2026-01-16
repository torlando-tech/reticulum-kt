package network.reticulum.interop.resource

import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.toHex
import network.reticulum.resource.ResourceConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interoperability tests for Resource part operations.
 *
 * These tests verify byte-perfect compatibility between Kotlin and Python
 * for resource part hashing, hashmap construction, part identification,
 * and proof computation.
 *
 * Key formats tested:
 * - Map hash: SHA256(part_data + random_hash)[:4]
 * - Hashmap: Concatenation of 4-byte map hashes
 * - Resource hash: SHA256(data + random_hash) (full 32 bytes)
 * - Expected proof: SHA256(data + resource_hash)[:16]
 */
@DisplayName("Resource Part Interop")
class ResourcePartInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    companion object {
        // Test fixtures
        val RANDOM_HASH = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        const val SDU = 325  // Typical link SDU

        // Test data of various sizes
        val SMALL_DATA = ByteArray(100) { it.toByte() }
        val SDU_DATA = ByteArray(SDU) { (it % 256).toByte() }
        val MULTI_PART_DATA = ByteArray(SDU * 10) { (it % 256).toByte() }
        val LARGE_DATA = ByteArray(SDU * 100) { (it % 256).toByte() }
    }

    /**
     * Compute map hash matching Resource.getMapHash().
     * Map hash = SHA256(part_data + random_hash)[:4]
     */
    private fun getMapHash(partData: ByteArray, randomHash: ByteArray): ByteArray {
        return Hashes.fullHash(partData + randomHash).copyOf(ResourceConstants.MAPHASH_LEN)
    }

    /**
     * Build hashmap from list of parts.
     * Hashmap = concatenation of all part map hashes.
     */
    private fun buildHashmap(parts: List<ByteArray>, randomHash: ByteArray): ByteArray {
        val hashmap = ByteArray(parts.size * ResourceConstants.MAPHASH_LEN)
        parts.forEachIndexed { index, part ->
            val mapHash = getMapHash(part, randomHash)
            mapHash.copyInto(hashmap, index * ResourceConstants.MAPHASH_LEN)
        }
        return hashmap
    }

    /**
     * Split data into SDU-sized parts.
     */
    private fun splitIntoParts(data: ByteArray, sdu: Int = SDU): List<ByteArray> {
        val numParts = ceil(data.size.toDouble() / sdu).toInt()
        return (0 until numParts).map { i ->
            val start = i * sdu
            val end = minOf(start + sdu, data.size)
            data.copyOfRange(start, end)
        }
    }

    /**
     * Find part index by map hash in hashmap.
     */
    private fun findPartIndex(hashmap: ByteArray, mapHash: ByteArray): Int {
        for (i in 0 until hashmap.size / ResourceConstants.MAPHASH_LEN) {
            val start = i * ResourceConstants.MAPHASH_LEN
            val end = start + ResourceConstants.MAPHASH_LEN
            if (hashmap.copyOfRange(start, end).contentEquals(mapHash)) {
                return i
            }
        }
        return -1
    }

    @Nested
    @DisplayName("Map Hash Tests")
    inner class MapHashTests {

        @Test
        @DisplayName("Map hash computation matches Python")
        fun `map hash computation matches Python`() {
            val partData = ByteArray(100) { it.toByte() }
            val randomHash = RANDOM_HASH

            val kotlinMapHash = getMapHash(partData, randomHash)

            val pythonResult = python(
                "resource_map_hash",
                "part_data" to partData,
                "random_hash" to randomHash
            )

            assertBytesEqual(
                pythonResult.getBytes("map_hash"),
                kotlinMapHash,
                "Map hash should match Python"
            )
        }

        @Test
        @DisplayName("Map hash with various part sizes")
        fun `map hash with various part sizes`() {
            val testSizes = listOf(1, 16, 100, SDU, SDU + 1)
            val randomHash = RANDOM_HASH

            for (size in testSizes) {
                val partData = ByteArray(size) { (it % 256).toByte() }
                val kotlinMapHash = getMapHash(partData, randomHash)

                val pythonResult = python(
                    "resource_map_hash",
                    "part_data" to partData,
                    "random_hash" to randomHash
                )

                assertBytesEqual(
                    pythonResult.getBytes("map_hash"),
                    kotlinMapHash,
                    "Map hash for $size bytes should match Python"
                )
            }
        }

        @Test
        @DisplayName("Map hash is 4 bytes")
        fun `map hash is 4 bytes`() {
            val partData = SMALL_DATA
            val mapHash = getMapHash(partData, RANDOM_HASH)

            assertEquals(4, mapHash.size, "Map hash should be 4 bytes")
        }

        @Test
        @DisplayName("Map hash is deterministic with same random_hash")
        fun `map hash is deterministic`() {
            val partData = SMALL_DATA
            val randomHash = RANDOM_HASH

            val hash1 = getMapHash(partData, randomHash)
            val hash2 = getMapHash(partData, randomHash)

            assertBytesEqual(hash1, hash2, "Same inputs should produce same map hash")
        }

        @Test
        @DisplayName("Different random_hash produces different map hash")
        fun `different random hash produces different map hash`() {
            val partData = SMALL_DATA
            val randomHash1 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val randomHash2 = byteArrayOf(0x05, 0x06, 0x07, 0x08)

            val hash1 = getMapHash(partData, randomHash1)
            val hash2 = getMapHash(partData, randomHash2)

            assertFalse(hash1.contentEquals(hash2), "Different random_hash should produce different map hash")
        }
    }

    @Nested
    @DisplayName("Hashmap Tests")
    inner class HashmapTests {

        @Test
        @DisplayName("Hashmap from parts matches Python")
        fun `hashmap from parts matches Python`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH

            val kotlinHashmap = buildHashmap(parts, randomHash)

            val pythonResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )

            assertBytesEqual(
                pythonResult.getBytes("hashmap"),
                kotlinHashmap,
                "Hashmap should match Python"
            )

            assertEquals(
                parts.size,
                pythonResult.getInt("num_parts"),
                "Number of parts should match"
            )
        }

        @Test
        @DisplayName("Hashmap ordering preserved")
        fun `hashmap ordering preserved`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH

            val kotlinHashmap = buildHashmap(parts, randomHash)

            // Verify first part's hash is at offset 0
            val firstPartHash = getMapHash(parts[0], randomHash)
            val firstHashInMap = kotlinHashmap.copyOfRange(0, 4)
            assertBytesEqual(firstPartHash, firstHashInMap, "First part hash should be at offset 0")

            // Verify last part's hash is at the end
            val lastPartHash = getMapHash(parts.last(), randomHash)
            val lastHashInMap = kotlinHashmap.copyOfRange(
                kotlinHashmap.size - 4,
                kotlinHashmap.size
            )
            assertBytesEqual(lastPartHash, lastHashInMap, "Last part hash should be at end")
        }

        @Test
        @DisplayName("Hashmap with many parts")
        fun `hashmap with many parts`() {
            // 100+ parts
            val parts = splitIntoParts(LARGE_DATA)
            val randomHash = RANDOM_HASH

            val kotlinHashmap = buildHashmap(parts, randomHash)

            val pythonResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )

            assertBytesEqual(
                pythonResult.getBytes("hashmap"),
                kotlinHashmap,
                "Large hashmap should match Python"
            )

            assertEquals(
                parts.size * 4,
                kotlinHashmap.size,
                "Hashmap size should be numParts * 4"
            )
        }

        @Test
        @DisplayName("Single part hashmap")
        fun `single part hashmap`() {
            val parts = listOf(SMALL_DATA)
            val randomHash = RANDOM_HASH

            val kotlinHashmap = buildHashmap(parts, randomHash)

            val pythonResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )

            assertBytesEqual(
                pythonResult.getBytes("hashmap"),
                kotlinHashmap,
                "Single part hashmap should match Python"
            )

            assertEquals(4, kotlinHashmap.size, "Single part hashmap should be 4 bytes")
        }
    }

    @Nested
    @DisplayName("Part Identification Tests")
    inner class PartIdentificationTests {

        @Test
        @DisplayName("Find part by map hash")
        fun `find part by map hash`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH
            val hashmap = buildHashmap(parts, randomHash)

            // Find part at index 5
            val targetPart = parts[5]
            val targetHash = getMapHash(targetPart, randomHash)
            val foundIndex = findPartIndex(hashmap, targetHash)

            assertEquals(5, foundIndex, "Should find part at correct index")
        }

        @Test
        @DisplayName("Part not found returns -1")
        fun `part not found returns -1`() {
            val parts = splitIntoParts(SMALL_DATA)
            val randomHash = RANDOM_HASH
            val hashmap = buildHashmap(parts, randomHash)

            // Use a hash that doesn't exist
            val nonExistentHash = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val foundIndex = findPartIndex(hashmap, nonExistentHash)

            assertEquals(-1, foundIndex, "Non-existent part should return -1")
        }

        @Test
        @DisplayName("Kotlin finds Python-generated part")
        fun `kotlin finds python generated part`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH

            // Build hashmap in Python
            val pythonHashmapResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )
            val pythonHashmap = pythonHashmapResult.getBytes("hashmap")

            // Compute map hash for part 7 in Python
            val pythonHashResult = python(
                "resource_map_hash",
                "part_data" to parts[7],
                "random_hash" to randomHash
            )
            val pythonMapHash = pythonHashResult.getBytes("map_hash")

            // Find in Kotlin
            val foundIndex = findPartIndex(pythonHashmap, pythonMapHash)
            assertEquals(7, foundIndex, "Kotlin should find Python-generated part")
        }

        @Test
        @DisplayName("Python finds Kotlin-generated part")
        fun `python finds kotlin generated part`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH

            // Build hashmap in Kotlin
            val kotlinHashmap = buildHashmap(parts, randomHash)

            // Compute map hash for part 3 in Kotlin
            val kotlinMapHash = getMapHash(parts[3], randomHash)

            // Find in Python
            val pythonResult = python(
                "resource_find_part",
                "hashmap" to kotlinHashmap,
                "map_hash" to kotlinMapHash
            )

            assertTrue(pythonResult.getBoolean("found"), "Python should find Kotlin-generated part")
            assertEquals(3, pythonResult.getInt("index"), "Python should find correct index")
        }

        @Test
        @DisplayName("Cross-implementation part matching")
        fun `cross implementation part matching`() {
            val parts = splitIntoParts(MULTI_PART_DATA)
            val randomHash = RANDOM_HASH

            // For each part, verify Kotlin hash found in Python hashmap and vice versa
            val kotlinHashmap = buildHashmap(parts, randomHash)
            val pythonHashmapResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )
            val pythonHashmap = pythonHashmapResult.getBytes("hashmap")

            for (i in parts.indices) {
                val kotlinHash = getMapHash(parts[i], randomHash)
                val pythonHashResult = python(
                    "resource_map_hash",
                    "part_data" to parts[i],
                    "random_hash" to randomHash
                )
                val pythonHash = pythonHashResult.getBytes("map_hash")

                // Hashes should match
                assertBytesEqual(kotlinHash, pythonHash, "Hash for part $i should match")

                // Both should find in both hashmaps
                assertEquals(i, findPartIndex(kotlinHashmap, kotlinHash), "Part $i in Kotlin hashmap")
                assertEquals(i, findPartIndex(pythonHashmap, pythonHash), "Part $i in Python hashmap")
            }
        }
    }

    @Nested
    @DisplayName("Resource Hash Tests")
    inner class ResourceHashTests {

        @Test
        @DisplayName("Resource hash matches Python")
        fun `resource hash matches Python`() {
            val data = MULTI_PART_DATA
            val randomHash = RANDOM_HASH

            // Kotlin: full_hash(random_hash + data)[:16] for resource_hash command
            // (existing resource_hash uses random_hash + data order)
            val kotlinHash = Hashes.fullHash(randomHash + data).copyOf(16)

            val pythonResult = python(
                "resource_hash",
                "data" to data,
                "random_hash" to randomHash
            )

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinHash,
                "Resource hash should match Python"
            )
        }

        @Test
        @DisplayName("Resource hash is independent of chunking")
        fun `resource hash independent of chunking`() {
            val data = MULTI_PART_DATA
            val randomHash = RANDOM_HASH

            // Hash the full data
            val fullDataHash = Hashes.fullHash(randomHash + data).copyOf(16)

            // Chunk the data and reassemble
            val parts = splitIntoParts(data)
            val reassembled = parts.reduce { acc, part -> acc + part }
            val reassembledHash = Hashes.fullHash(randomHash + reassembled).copyOf(16)

            assertBytesEqual(fullDataHash, reassembledHash, "Hash should be same regardless of chunking")
        }

        @Test
        @DisplayName("Full hash is 32 bytes")
        fun `full hash is 32 bytes`() {
            val data = SMALL_DATA
            val randomHash = RANDOM_HASH

            val fullHash = Hashes.fullHash(randomHash + data)

            assertEquals(32, fullHash.size, "Full hash should be 32 bytes")

            val pythonResult = python(
                "resource_hash",
                "data" to data,
                "random_hash" to randomHash
            )

            assertEquals(32, pythonResult.getBytes("full_hash").size, "Python full hash should be 32 bytes")
        }
    }

    @Nested
    @DisplayName("Proof Tests")
    inner class ProofTests {

        @Test
        @DisplayName("Expected proof computation matches Python")
        fun `expected proof computation matches Python`() {
            val data = MULTI_PART_DATA
            val resourceHash = Hashes.fullHash(RANDOM_HASH + data)

            // Expected proof = SHA256(data + resource_hash)[:16]
            val kotlinProof = Hashes.fullHash(data + resourceHash).copyOf(16)

            val pythonResult = python(
                "resource_proof",
                "data" to data,
                "resource_hash" to resourceHash
            )

            assertBytesEqual(
                pythonResult.getBytes("proof"),
                kotlinProof,
                "Expected proof should match Python"
            )
        }

        @Test
        @DisplayName("Proof is 16 bytes")
        fun `proof is 16 bytes`() {
            val data = SMALL_DATA
            val resourceHash = Hashes.fullHash(RANDOM_HASH + data)

            val proof = Hashes.fullHash(data + resourceHash).copyOf(16)

            assertEquals(16, proof.size, "Proof should be 16 bytes")
        }

        @Test
        @DisplayName("Proof validation round-trip - Kotlin generates, Python validates")
        fun `proof validation kotlin to python`() {
            val data = MULTI_PART_DATA
            val resourceHash = Hashes.fullHash(RANDOM_HASH + data)

            // Generate proof in Kotlin
            val kotlinProof = Hashes.fullHash(data + resourceHash).copyOf(16)

            // Python computes expected proof
            val pythonResult = python(
                "resource_proof",
                "data" to data,
                "resource_hash" to resourceHash
            )
            val pythonExpectedProof = pythonResult.getBytes("proof")

            assertBytesEqual(kotlinProof, pythonExpectedProof, "Kotlin proof should match Python expected")
        }

        @Test
        @DisplayName("Proof validation round-trip - Python generates, Kotlin validates")
        fun `proof validation python to kotlin`() {
            val data = MULTI_PART_DATA
            val resourceHash = Hashes.fullHash(RANDOM_HASH + data)

            // Generate proof in Python
            val pythonResult = python(
                "resource_proof",
                "data" to data,
                "resource_hash" to resourceHash
            )
            val pythonProof = pythonResult.getBytes("proof")

            // Kotlin computes expected proof
            val kotlinExpectedProof = Hashes.fullHash(data + resourceHash).copyOf(16)

            assertBytesEqual(pythonProof, kotlinExpectedProof, "Python proof should match Kotlin expected")
        }

        @Test
        @DisplayName("Different data produces different proof")
        fun `different data produces different proof`() {
            val data1 = ByteArray(100) { it.toByte() }
            val data2 = ByteArray(100) { (it + 1).toByte() }
            val resourceHash = Hashes.fullHash(RANDOM_HASH + data1)

            val proof1 = Hashes.fullHash(data1 + resourceHash).copyOf(16)
            val proof2 = Hashes.fullHash(data2 + resourceHash).copyOf(16)

            assertFalse(proof1.contentEquals(proof2), "Different data should produce different proof")
        }
    }

    @Nested
    @DisplayName("Transfer Simulation Tests")
    inner class TransferSimulationTests {

        @Test
        @DisplayName("Simulated transfer Kotlin to Python")
        fun `simulated transfer kotlin to python`() {
            val originalData = MULTI_PART_DATA
            val randomHash = RANDOM_HASH

            // Kotlin sender: split into parts and build hashmap
            val parts = splitIntoParts(originalData)
            val kotlinHashmap = buildHashmap(parts, randomHash)

            // Python receiver: receive hashmap
            // For each "received" part, verify it matches using Python
            for ((index, part) in parts.withIndex()) {
                // Kotlin computes map hash
                val kotlinMapHash = getMapHash(part, randomHash)

                // Python finds the part in the hashmap
                val findResult = python(
                    "resource_find_part",
                    "hashmap" to kotlinHashmap,
                    "map_hash" to kotlinMapHash
                )

                assertTrue(findResult.getBoolean("found"), "Python should find part $index")
                assertEquals(index, findResult.getInt("index"), "Python should find correct index for part $index")

                // Python independently computes map hash
                val pyHashResult = python(
                    "resource_map_hash",
                    "part_data" to part,
                    "random_hash" to randomHash
                )
                assertBytesEqual(
                    kotlinMapHash,
                    pyHashResult.getBytes("map_hash"),
                    "Map hash for part $index should match"
                )
            }
        }

        @Test
        @DisplayName("Simulated transfer Python to Kotlin")
        fun `simulated transfer python to kotlin`() {
            val originalData = MULTI_PART_DATA
            val randomHash = RANDOM_HASH

            // Python sender: build hashmap
            val parts = splitIntoParts(originalData)
            val pythonHashmapResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )
            val pythonHashmap = pythonHashmapResult.getBytes("hashmap")

            // Kotlin receiver: receive hashmap
            // For each "received" part, verify it matches using Kotlin
            for ((index, part) in parts.withIndex()) {
                // Python computes map hash
                val pyHashResult = python(
                    "resource_map_hash",
                    "part_data" to part,
                    "random_hash" to randomHash
                )
                val pythonMapHash = pyHashResult.getBytes("map_hash")

                // Kotlin finds the part in the hashmap
                val foundIndex = findPartIndex(pythonHashmap, pythonMapHash)
                assertEquals(index, foundIndex, "Kotlin should find correct index for part $index")

                // Kotlin independently computes map hash
                val kotlinMapHash = getMapHash(part, randomHash)
                assertBytesEqual(
                    pythonMapHash,
                    kotlinMapHash,
                    "Map hash for part $index should match"
                )
            }
        }

        @Test
        @DisplayName("Multi-segment resource with more than 56 parts")
        fun `multi segment resource`() {
            // HASHMAP_MAX_LEN is typically around 56 entries per segment
            // Create resource with more parts to test segmentation
            val largeData = ByteArray(SDU * 100) { (it % 256).toByte() }
            val parts = splitIntoParts(largeData)
            val randomHash = RANDOM_HASH

            assertTrue(parts.size > 56, "Should have more than 56 parts for segment test")

            // Build full hashmap
            val kotlinHashmap = buildHashmap(parts, randomHash)
            val pythonResult = python(
                "resource_build_hashmap",
                "parts" to parts.map { it.toHex() },
                "random_hash" to randomHash
            )
            val pythonHashmap = pythonResult.getBytes("hashmap")

            assertBytesEqual(
                pythonHashmap,
                kotlinHashmap,
                "Full hashmap should match for multi-segment resource"
            )

            // Verify segment slicing works
            val segmentSize = 56 * ResourceConstants.MAPHASH_LEN
            val segment0 = kotlinHashmap.copyOfRange(0, minOf(segmentSize, kotlinHashmap.size))
            val segment1Start = segmentSize
            val segment1End = minOf(segmentSize * 2, kotlinHashmap.size)

            if (segment1Start < kotlinHashmap.size) {
                val segment1 = kotlinHashmap.copyOfRange(segment1Start, segment1End)

                // Verify parts in each segment can be found
                val part0Hash = getMapHash(parts[0], randomHash)
                assertEquals(0, findPartIndex(segment0, part0Hash), "Part 0 should be in segment 0")

                if (parts.size > 56) {
                    val part56Hash = getMapHash(parts[56], randomHash)
                    assertEquals(0, findPartIndex(segment1, part56Hash), "Part 56 should be at index 0 in segment 1")
                }
            }
        }

        @Test
        @DisplayName("Complete transfer with proof verification")
        fun `complete transfer with proof verification`() {
            val originalData = MULTI_PART_DATA
            val randomHash = RANDOM_HASH

            // Sender computes resource hash
            val resourceHash = Hashes.fullHash(randomHash + originalData)

            // Split and build hashmap
            val parts = splitIntoParts(originalData)
            val hashmap = buildHashmap(parts, randomHash)

            // Receiver reassembles
            val reassembled = parts.reduce { acc, part -> acc + part }
            assertBytesEqual(originalData, reassembled, "Reassembled data should match original")

            // Receiver computes proof
            val proof = Hashes.fullHash(reassembled + resourceHash).copyOf(16)

            // Sender verifies proof
            val expectedProof = Hashes.fullHash(originalData + resourceHash).copyOf(16)
            assertBytesEqual(expectedProof, proof, "Proof should match expected")

            // Cross-verify with Python
            val pythonProofResult = python(
                "resource_proof",
                "data" to originalData,
                "resource_hash" to resourceHash
            )
            assertBytesEqual(
                pythonProofResult.getBytes("proof"),
                proof,
                "Proof should match Python"
            )
        }
    }
}
