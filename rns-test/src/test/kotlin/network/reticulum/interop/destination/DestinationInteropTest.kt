package network.reticulum.interop.destination

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Destination interoperability tests with Python RNS.
 */
@DisplayName("Destination Interop")
class DestinationInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("Hash Computation")
    inner class HashComputation {

        @Test
        @DisplayName("Name hash matches Python")
        fun `name hash matches Python`() {
            val testCases = listOf(
                Pair("lxmf", listOf("delivery")),
                Pair("nomadnetwork", listOf("node")),
                Pair("myapp", listOf("aspect1", "aspect2")),
                Pair("single", emptyList())
            )

            for ((appName, aspects) in testCases) {
                val kotlinNameHash = Destination.computeNameHash(appName, aspects)
                val fullName = listOf(appName).plus(aspects).joinToString(".")

                val pythonResult = python("name_hash", "name" to fullName)

                assertBytesEqual(
                    pythonResult.getBytes("hash"),
                    kotlinNameHash,
                    "Name hash for '$fullName'"
                )
            }
        }

        @Test
        @DisplayName("Destination hash matches Python without identity")
        fun `destination hash matches Python without identity`() {
            val testCases = listOf(
                Pair("lxmf", listOf("delivery")),
                Pair("nomadnetwork", listOf("node")),
                Pair("myapp", listOf("test", "endpoint"))
            )

            for ((appName, aspects) in testCases) {
                // Create a PLAIN destination (no identity)
                val destination = Destination.create(
                    identity = null,
                    direction = DestinationDirection.IN,
                    type = DestinationType.PLAIN,
                    appName = appName,
                    aspects = *aspects.toTypedArray()
                )

                // Python side - use empty identity hash for PLAIN
                val nameHash = Destination.computeNameHash(appName, aspects)
                val kotlinDestHash = Hashes.truncatedHash(nameHash)

                assertBytesEqual(
                    kotlinDestHash,
                    destination.hash,
                    "Destination hash for PLAIN '$appName'"
                )
            }
        }

        @Test
        @DisplayName("Destination hash matches Python with identity")
        fun `destination hash matches Python with identity`() {
            // Create a test identity with known private key
            val privateKey = ByteArray(64) { (it * 7).toByte() }
            val identity = Identity.fromPrivateKey(privateKey)

            val testCases = listOf(
                Pair("lxmf", listOf("delivery")),
                Pair("nomadnetwork", listOf("node")),
                Pair("myapp", listOf("aspect1", "aspect2"))
            )

            for ((appName, aspects) in testCases) {
                // Create a SINGLE destination with identity
                val destination = Destination.create(
                    identity = identity,
                    direction = DestinationDirection.OUT,
                    type = DestinationType.SINGLE,
                    appName = appName,
                    aspects = *aspects.toTypedArray()
                )

                // Get Python's hash
                val pythonResult = python(
                    "destination_hash",
                    "identity_hash" to identity.hash,
                    "app_name" to appName,
                    "aspects" to aspects.joinToString(",")
                )

                assertBytesEqual(
                    pythonResult.getBytes("destination_hash"),
                    destination.hash,
                    "Destination hash for '$appName' with identity"
                )

                // Also verify name hash matches
                assertBytesEqual(
                    pythonResult.getBytes("name_hash"),
                    destination.nameHash,
                    "Name hash for '$appName'"
                )
            }
        }

        @Test
        @DisplayName("Destination hash computation is deterministic")
        fun `destination hash is deterministic`() {
            val privateKey = ByteArray(64) { it.toByte() }
            val identity = Identity.fromPrivateKey(privateKey)

            val dest1 = Destination.create(
                identity = identity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = arrayOf("app")
            )

            val dest2 = Destination.create(
                identity = identity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = arrayOf("app")
            )

            assertBytesEqual(dest1.hash, dest2.hash, "Destination hashes should be deterministic")
        }
    }

    @Nested
    @DisplayName("Name Formatting")
    inner class NameFormatting {

        @Test
        @DisplayName("Destination name includes identity hexhash")
        fun `destination name includes identity hexhash`() {
            val privateKey = ByteArray(64) { (it * 3).toByte() }
            val identity = Identity.fromPrivateKey(privateKey)

            val destination = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "myapp",
                aspects = arrayOf("endpoint")
            )

            // Name should be: myapp.endpoint.{identity_hexhash}
            assert(destination.name.startsWith("myapp.endpoint.")) {
                "Name should start with 'myapp.endpoint.', got: ${destination.name}"
            }
            assert(destination.name.endsWith(identity.hexHash)) {
                "Name should end with identity hexhash"
            }
        }

        @Test
        @DisplayName("PLAIN destination name has no identity")
        fun `plain destination name has no identity`() {
            val destination = Destination.create(
                identity = null,
                direction = DestinationDirection.IN,
                type = DestinationType.PLAIN,
                appName = "broadcast",
                aspects = arrayOf("channel")
            )

            assert(destination.name == "broadcast.channel") {
                "PLAIN destination name should be 'broadcast.channel', got: ${destination.name}"
            }
        }

        @Test
        @DisplayName("Parse full name")
        fun `parse full name`() {
            val testCases = listOf(
                Pair("app.aspect1.aspect2", Pair("app", listOf("aspect1", "aspect2"))),
                Pair("single", Pair("single", emptyList())),
                Pair("lxmf.delivery.abcd1234", Pair("lxmf", listOf("delivery", "abcd1234")))
            )

            for ((fullName, expected) in testCases) {
                val (appName, aspects) = Destination.parseFullName(fullName)
                assert(appName == expected.first) { "App name should be '${expected.first}', got: $appName" }
                assert(aspects == expected.second) { "Aspects should be ${expected.second}, got: $aspects" }
            }
        }
    }

    @Nested
    @DisplayName("Static Hash Computation")
    inner class StaticHashComputation {

        @Test
        @DisplayName("Static hash computation matches Python")
        fun `static hash computation matches Python`() {
            val privateKey = ByteArray(64) { (it * 11).toByte() }
            val identity = Identity.fromPrivateKey(privateKey)

            val appName = "testapp"
            val aspects = listOf("v1", "endpoint")

            // Compute using static method
            val staticHash = Destination.computeHash(appName, aspects, identity.hash)

            // Compute using destination instance
            val destination = Destination.create(
                identity = identity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = appName,
                aspects = *aspects.toTypedArray()
            )

            assertBytesEqual(staticHash, destination.hash, "Static hash should match instance hash")

            // Verify against Python
            val pythonResult = python(
                "destination_hash",
                "identity_hash" to identity.hash,
                "app_name" to appName,
                "aspects" to aspects.joinToString(",")
            )

            assertBytesEqual(
                pythonResult.getBytes("destination_hash"),
                staticHash,
                "Static hash should match Python"
            )
        }
    }
}
