package network.reticulum.interop.crypto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * End-to-end test for propagated LXMF message decryption.
 *
 * Simulates the exact data flow:
 * 1. Python encrypts packed[DEST_LEN:] with Destination.encrypt()
 * 2. Python builds lxmf_data = dest_hash + encrypted_data
 * 3. Kotlin extracts encrypted_data and decrypts with Destination.decrypt()
 *
 * This tests the EXACT same code path as real propagation delivery.
 */
@DisplayName("Propagation E2E Decrypt")
class PropagationE2EDecryptTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Test
    @DisplayName("Kotlin Destination.decrypt works for Python propagation-encrypted data")
    fun kotlinDestinationDecryptsPythonPropagationData() {
        // Step 1: Create Kotlin identity and destination (the recipient)
        val recipientIdentity = Identity.create(crypto)
        val recipientDestination = Destination.create(
            identity = recipientIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        println("Kotlin recipient dest hash: ${recipientDestination.hexHash}")
        println("Kotlin recipient identity hash: ${recipientIdentity.hexHash}")
        println("Kotlin recipient public key: ${recipientIdentity.getPublicKey().toHexString()}")

        // Step 2: Have Python create the exact propagation-encrypted data
        // Python will: create an OUT destination for this identity,
        // encrypt plaintext using Destination.encrypt(), then return
        // the full lxmf_data (dest_hash + encrypted)
        val plaintext = "Hello from Python propagation via Destination.encrypt!"
        val pyResult = python(
            "propagation_encrypt_for_recipient",
            "recipient_public_key" to recipientIdentity.getPublicKey(),
            "plaintext" to plaintext.toByteArray()
        )

        val pythonDestHash = pyResult.getBytes("dest_hash")
        val encryptedData = pyResult.getBytes("encrypted_data")
        val pythonIdentityHash = pyResult.getString("identity_hash")
        val pythonUsedRatchet = pyResult.getBoolean("used_ratchet")
        
        println("Python dest hash: ${pythonDestHash.toHexString()}")
        println("Python identity hash: $pythonIdentityHash")
        println("Python used ratchet: $pythonUsedRatchet")
        println("Encrypted data size: ${encryptedData.size}")

        // Verify destination hashes match
        pythonDestHash.toHexString() shouldBe recipientDestination.hexHash
        
        // Verify identity hashes match
        pythonIdentityHash shouldBe recipientIdentity.hexHash
        
        // Step 3: Decrypt using Kotlin Destination.decrypt() 
        // This is the EXACT same path as processInboundDelivery
        val decryptedData = recipientDestination.decrypt(encryptedData)
        
        decryptedData shouldNotBe null
        println("Kotlin decrypted: ${String(decryptedData!!)}")
        String(decryptedData) shouldBe plaintext
    }

    @Test
    @DisplayName("Full lxmf_data format: dest_hash + encrypted works")
    fun fullLxmfDataFormatWorks() {
        // Simulates the complete format that arrives from a propagation node
        val recipientIdentity = Identity.create(crypto)
        val recipientDestination = Destination.create(
            identity = recipientIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val plaintext = "Full format test"
        val pyResult = python(
            "propagation_encrypt_for_recipient",
            "recipient_public_key" to recipientIdentity.getPublicKey(),
            "plaintext" to plaintext.toByteArray()
        )

        val encryptedData = pyResult.getBytes("encrypted_data")
        val destHash = pyResult.getBytes("dest_hash")

        // Build the full lxmf_data as it would come from the prop node
        val lxmfData = destHash + encryptedData
        
        // Process like processInboundDelivery does
        val extractedDestHash = lxmfData.copyOfRange(0, 16)
        val extractedEncrypted = lxmfData.copyOfRange(16, lxmfData.size)
        
        extractedDestHash.toHexString() shouldBe recipientDestination.hexHash
        
        val decrypted = recipientDestination.decrypt(extractedEncrypted)
        decrypted shouldNotBe null
        String(decrypted!!) shouldBe plaintext
    }
}
