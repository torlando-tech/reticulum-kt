package network.reticulum.interop.crypto

import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Hash function interoperability tests with Python RNS.
 */
@DisplayName("Hash Interop")
class HashInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Test
    @DisplayName("SHA-256 matches Python")
    fun `sha256 matches Python`() {
        val testCases = listOf(
            ByteArray(0),
            "Hello, Reticulum!".toByteArray(),
            ByteArray(32) { it.toByte() },
            ByteArray(1000) { (it % 256).toByte() }
        )

        for (data in testCases) {
            val kotlinHash = crypto.sha256(data)
            val pythonResult = python("sha256", "data" to data)

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinHash,
                "SHA-256 for ${data.size} bytes"
            )
        }
    }

    @Test
    @DisplayName("SHA-512 matches Python")
    fun `sha512 matches Python`() {
        val testCases = listOf(
            ByteArray(0),
            "Test message".toByteArray(),
            ByteArray(64) { it.toByte() }
        )

        for (data in testCases) {
            val kotlinHash = crypto.sha512(data)
            val pythonResult = python("sha512", "data" to data)

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinHash,
                "SHA-512 for ${data.size} bytes"
            )
        }
    }

    @Test
    @DisplayName("HMAC-SHA256 matches Python")
    fun `hmac sha256 matches Python`() {
        val testCases = listOf(
            Pair(ByteArray(16) { it.toByte() }, "message".toByteArray()),
            Pair(ByteArray(32) { (it * 2).toByte() }, ByteArray(100) { it.toByte() }),
            Pair(ByteArray(64) { it.toByte() }, ByteArray(0)),  // Empty message
            Pair(ByteArray(128) { it.toByte() }, "test".toByteArray())  // Key > 64 bytes
        )

        for ((key, message) in testCases) {
            val kotlinHmac = crypto.hmacSha256(key, message)
            val pythonResult = python(
                "hmac_sha256",
                "key" to key,
                "message" to message
            )

            assertBytesEqual(
                pythonResult.getBytes("hmac"),
                kotlinHmac,
                "HMAC-SHA256 with ${key.size}-byte key, ${message.size}-byte message"
            )
        }
    }

    @Test
    @DisplayName("Truncated hash matches Python")
    fun `truncated hash matches Python`() {
        val testCases = listOf(
            ByteArray(32) { it.toByte() },
            "test destination".toByteArray(),
            ByteArray(64) { (it * 3).toByte() }
        )

        for (data in testCases) {
            val kotlinTruncated = Hashes.truncatedHash(data)
            val pythonResult = python("truncated_hash", "data" to data)

            // Truncated hash should be first 16 bytes
            assert(kotlinTruncated.size == 16) { "Truncated hash should be 16 bytes" }

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinTruncated,
                "Truncated hash for ${data.size} bytes"
            )
        }
    }

    @Test
    @DisplayName("Name hash matches Python")
    fun `name hash matches Python`() {
        val testNames = listOf(
            "lxmf.delivery",
            "nomadnetwork.node",
            "example.test.aspect",
            "single"
        )

        for (name in testNames) {
            val kotlinNameHash = Hashes.nameHash(name)
            val pythonResult = python("name_hash", "name" to name)

            // Name hash should be first 10 bytes
            assert(kotlinNameHash.size == 10) { "Name hash should be 10 bytes" }

            assertBytesEqual(
                pythonResult.getBytes("hash"),
                kotlinNameHash,
                "Name hash for '$name'"
            )
        }
    }
}
