package network.reticulum.interop.crypto

import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * HKDF interoperability tests with Python RNS.
 */
@DisplayName("HKDF Interop")
class HkdfInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Test
    @DisplayName("HKDF-SHA256 matches Python with salt and info")
    fun `hkdf matches Python with salt and info`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(32) { (it + 100).toByte() }
        val info = "context".toByteArray()
        val length = 64

        val kotlinDerived = crypto.hkdf(length, ikm, salt, info)
        val pythonResult = python(
            "hkdf",
            "length" to length,
            "ikm" to ikm,
            "salt" to salt,
            "info" to info
        )

        assertBytesEqual(
            pythonResult.getBytes("derived_key"),
            kotlinDerived,
            "HKDF with salt and info"
        )
    }

    @Test
    @DisplayName("HKDF-SHA256 matches Python with null salt")
    fun `hkdf matches Python with null salt`() {
        val ikm = ByteArray(32) { (it * 2).toByte() }
        val info = "test".toByteArray()
        val length = 64

        // Null salt should use 32 zero bytes
        val kotlinDerived = crypto.hkdf(length, ikm, null, info)
        val pythonResult = python(
            "hkdf",
            "length" to length,
            "ikm" to ikm,
            "info" to info
            // No salt parameter = None in Python
        )

        assertBytesEqual(
            pythonResult.getBytes("derived_key"),
            kotlinDerived,
            "HKDF with null salt"
        )
    }

    @Test
    @DisplayName("HKDF-SHA256 matches Python with null info")
    fun `hkdf matches Python with null info`() {
        val ikm = ByteArray(32) { (it * 3).toByte() }
        val salt = ByteArray(32) { (it + 50).toByte() }
        val length = 32

        val kotlinDerived = crypto.hkdf(length, ikm, salt, null)
        val pythonResult = python(
            "hkdf",
            "length" to length,
            "ikm" to ikm,
            "salt" to salt
            // No info parameter = None in Python
        )

        assertBytesEqual(
            pythonResult.getBytes("derived_key"),
            kotlinDerived,
            "HKDF with null info"
        )
    }

    @Test
    @DisplayName("HKDF-SHA256 derives 64 bytes (Identity.DERIVED_KEY_LENGTH)")
    fun `hkdf derives 64 bytes for identity`() {
        // This is the typical usage in Identity encryption
        val sharedSecret = ByteArray(32) { (it * 5).toByte() }
        val identityHash = ByteArray(16) { (it * 7).toByte() }  // Truncated hash
        val length = 64  // Identity.DERIVED_KEY_LENGTH

        val kotlinDerived = crypto.hkdf(length, sharedSecret, identityHash, null)
        val pythonResult = python(
            "hkdf",
            "length" to length,
            "ikm" to sharedSecret,
            "salt" to identityHash
        )

        assert(kotlinDerived.size == 64) { "Derived key should be 64 bytes" }

        assertBytesEqual(
            pythonResult.getBytes("derived_key"),
            kotlinDerived,
            "HKDF for Identity encryption (64 bytes)"
        )
    }

    @Test
    @DisplayName("HKDF with various output lengths")
    fun `hkdf with various output lengths`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(32) { (it + 10).toByte() }
        val info = "test".toByteArray()

        val lengths = listOf(16, 32, 48, 64, 96, 128)

        for (length in lengths) {
            val kotlinDerived = crypto.hkdf(length, ikm, salt, info)
            val pythonResult = python(
                "hkdf",
                "length" to length,
                "ikm" to ikm,
                "salt" to salt,
                "info" to info
            )

            assert(kotlinDerived.size == length) { "Derived key should be $length bytes" }

            assertBytesEqual(
                pythonResult.getBytes("derived_key"),
                kotlinDerived,
                "HKDF with length $length"
            )
        }
    }
}
