package network.reticulum.lxmf.interop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import network.reticulum.interop.getBytes
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getInt
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXStamper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Tests for LXMF stamp generation and validation interoperability between Kotlin and Python.
 *
 * LXMF stamps are proof-of-work tokens requiring computational effort to generate.
 * The stamp algorithm:
 * 1. Generate workblock using HKDF expansion of message ID
 * 2. Find 32 random bytes where SHA256(workblock + stamp) has enough leading zeros
 * 3. Validation checks that SHA256(workblock + stamp) <= 2^(256-cost)
 *
 * These tests verify:
 * - Workblock generation matches byte-for-byte between implementations
 * - Stamps generated in Kotlin validate correctly in Python
 * - Stamps generated in Python validate correctly in Kotlin
 * - Stamp value computation matches between implementations
 */
class StampInteropTest : LXMFInteropTestBase() {

    private val secureRandom = SecureRandom()

    /** Helper infix for readable assertions */
    private infix fun Int.shouldBeAtLeast(minimum: Int) {
        assertTrue(this >= minimum, "Expected $this >= $minimum")
    }

    /** Generate a random 32-byte message ID */
    private fun randomMessageId(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

    @Nested
    @DisplayName("WorkblockGeneration")
    inner class WorkblockGeneration {

        @Test
        fun `workblock matches Python for small expand rounds`() {
            println("\n=== Test: workblock matches Python for small expand rounds ===")

            val messageId = randomMessageId()
            val expandRounds = 2  // Small for fast test

            println("  Message ID: ${messageId.toHex()}")
            println("  Expand rounds: $expandRounds")

            // Generate in Kotlin
            val kotlinStart = System.currentTimeMillis()
            val kotlinWorkblock = LXStamper.generateWorkblock(messageId, expandRounds)
            val kotlinTime = System.currentTimeMillis() - kotlinStart
            println("  [Kotlin] Generated workblock in ${kotlinTime}ms, size=${kotlinWorkblock.size} bytes")

            // Generate in Python via bridge
            val pythonResult = python(
                "lxmf_stamp_workblock",
                "message_id" to messageId.toHex(),
                "expand_rounds" to expandRounds
            )
            val pythonWorkblock = pythonResult.getBytes("workblock")
            println("  [Python] Generated workblock, size=${pythonWorkblock.size} bytes")

            // Compare
            assertBytesEqual(pythonWorkblock, kotlinWorkblock, "Workblock")

            println("  SUCCESS: Workblock matches Python byte-for-byte")
        }

        @Test
        fun `workblock size matches expected`() {
            println("\n=== Test: workblock size matches expected ===")

            val messageId = randomMessageId()
            val expandRounds = 5

            println("  Expand rounds: $expandRounds")
            println("  Expected size: ${expandRounds * 256} bytes")

            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            workblock.size shouldBe expandRounds * 256

            println("  Actual size: ${workblock.size} bytes")
            println("  SUCCESS: Workblock size is expandRounds * 256")
        }

        @Test
        fun `workblock matches Python for larger expand rounds`() {
            println("\n=== Test: workblock matches Python for larger expand rounds ===")

            val messageId = randomMessageId()
            val expandRounds = 25  // Standard for peering

            println("  Message ID: ${messageId.toHex()}")
            println("  Expand rounds: $expandRounds")

            // Generate in Kotlin
            val kotlinStart = System.currentTimeMillis()
            val kotlinWorkblock = LXStamper.generateWorkblock(messageId, expandRounds)
            val kotlinTime = System.currentTimeMillis() - kotlinStart
            println("  [Kotlin] Generated workblock in ${kotlinTime}ms, size=${kotlinWorkblock.size} bytes")

            // Generate in Python via bridge
            val pythonResult = python(
                "lxmf_stamp_workblock",
                "message_id" to messageId.toHex(),
                "expand_rounds" to expandRounds
            )
            val pythonWorkblock = pythonResult.getBytes("workblock")
            println("  [Python] Generated workblock, size=${pythonWorkblock.size} bytes")

            // Compare
            assertBytesEqual(pythonWorkblock, kotlinWorkblock, "Workblock")

            println("  SUCCESS: Workblock matches Python for 25 rounds")
        }
    }

    @Nested
    @DisplayName("KotlinGeneratesPythonValidates")
    inner class KotlinGeneratesPythonValidates {

        @Test
        fun `Kotlin 4-bit stamp validates in Python`() {
            println("\n=== Test: Kotlin 4-bit stamp validates in Python ===")

            val messageId = randomMessageId()
            val stampCost = 4
            val expandRounds = 25

            println("  Message ID: ${messageId.toHex()}")
            println("  Stamp cost: $stampCost bits")
            println("  Expand rounds: $expandRounds")

            // Generate workblock
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)
            println("  [Kotlin] Workblock size: ${workblock.size} bytes")

            // Generate stamp
            val stampStart = System.currentTimeMillis()
            val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }
            val stampTime = System.currentTimeMillis() - stampStart
            println("  [Kotlin] Generated stamp in ${stampTime}ms, value=${result.value}, rounds=${result.rounds}")

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast stampCost

            // Validate in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to stampCost,
                "workblock" to workblock.toHex()
            )

            val valid = pythonResult.getBoolean("valid")
            val pythonValue = pythonResult.getInt("value")
            println("  [Python] valid=$valid, value=$pythonValue")

            valid shouldBe true
            pythonValue shouldBe result.value

            println("  SUCCESS: Kotlin 4-bit stamp validates in Python")
        }

        @Test
        fun `Kotlin 8-bit stamp validates in Python`() {
            println("\n=== Test: Kotlin 8-bit stamp validates in Python ===")

            val messageId = randomMessageId()
            val stampCost = 8
            val expandRounds = 25

            println("  Message ID: ${messageId.toHex()}")
            println("  Stamp cost: $stampCost bits")
            println("  Expand rounds: $expandRounds")

            // Generate workblock
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)
            println("  [Kotlin] Workblock size: ${workblock.size} bytes")

            // Generate stamp
            val stampStart = System.currentTimeMillis()
            val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }
            val stampTime = System.currentTimeMillis() - stampStart
            println("  [Kotlin] Generated stamp in ${stampTime}ms, value=${result.value}, rounds=${result.rounds}")

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast stampCost

            // Validate in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to stampCost,
                "workblock" to workblock.toHex()
            )

            val valid = pythonResult.getBoolean("valid")
            val pythonValue = pythonResult.getInt("value")
            println("  [Python] valid=$valid, value=$pythonValue")

            valid shouldBe true
            pythonValue shouldBe result.value

            println("  SUCCESS: Kotlin 8-bit stamp validates in Python")
        }
    }

    @Nested
    @DisplayName("PythonGeneratesKotlinValidates")
    inner class PythonGeneratesKotlinValidates {

        @Test
        fun `Python 4-bit stamp validates in Kotlin`() {
            println("\n=== Test: Python 4-bit stamp validates in Kotlin ===")

            val messageId = randomMessageId()
            val stampCost = 4
            val expandRounds = 25

            println("  Message ID: ${messageId.toHex()}")
            println("  Stamp cost: $stampCost bits")
            println("  Expand rounds: $expandRounds")

            // Generate stamp in Python
            val pythonResult = python(
                "lxmf_stamp_generate",
                "message_id" to messageId.toHex(),
                "stamp_cost" to stampCost,
                "expand_rounds" to expandRounds
            )

            val stamp = pythonResult.getBytes("stamp")
            val pythonValue = pythonResult.getInt("value")
            println("  [Python] Generated stamp, value=$pythonValue")
            println("  [Python] Stamp: ${stamp.toHex()}")

            // Generate workblock in Kotlin (should match Python)
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            // Validate in Kotlin
            val valid = LXStamper.isStampValid(stamp, stampCost, workblock)
            val kotlinValue = LXStamper.stampValue(workblock, stamp)
            println("  [Kotlin] valid=$valid, value=$kotlinValue")

            valid shouldBe true
            kotlinValue shouldBe pythonValue

            println("  SUCCESS: Python 4-bit stamp validates in Kotlin")
        }

        @Test
        fun `Python 8-bit stamp validates in Kotlin`() {
            println("\n=== Test: Python 8-bit stamp validates in Kotlin ===")

            val messageId = randomMessageId()
            val stampCost = 8
            val expandRounds = 25

            println("  Message ID: ${messageId.toHex()}")
            println("  Stamp cost: $stampCost bits")
            println("  Expand rounds: $expandRounds")

            // Generate stamp in Python
            val pythonResult = python(
                "lxmf_stamp_generate",
                "message_id" to messageId.toHex(),
                "stamp_cost" to stampCost,
                "expand_rounds" to expandRounds
            )

            val stamp = pythonResult.getBytes("stamp")
            val pythonValue = pythonResult.getInt("value")
            println("  [Python] Generated stamp, value=$pythonValue")
            println("  [Python] Stamp: ${stamp.toHex()}")

            // Generate workblock in Kotlin (should match Python)
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            // Validate in Kotlin
            val valid = LXStamper.isStampValid(stamp, stampCost, workblock)
            val kotlinValue = LXStamper.stampValue(workblock, stamp)
            println("  [Kotlin] valid=$valid, value=$kotlinValue")

            valid shouldBe true
            kotlinValue shouldBe pythonValue

            println("  SUCCESS: Python 8-bit stamp validates in Kotlin")
        }
    }

    @Nested
    @DisplayName("DifficultyLevels")
    inner class DifficultyLevels {

        @ParameterizedTest
        @ValueSource(ints = [0, 1, 4, 8])
        fun `stamp at difficulty N validates in Python`(cost: Int) {
            println("\n=== Test: stamp at difficulty $cost validates in Python ===")

            val messageId = randomMessageId()
            val expandRounds = 25

            println("  Stamp cost: $cost bits")

            // Generate workblock
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            // Generate stamp in Kotlin
            val stampStart = System.currentTimeMillis()
            val result = runBlocking { LXStamper.generateStamp(workblock, cost) }
            val stampTime = System.currentTimeMillis() - stampStart
            println("  [Kotlin] Generated stamp in ${stampTime}ms, value=${result.value}, rounds=${result.rounds}")

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast cost

            // Validate in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to cost,
                "workblock" to workblock.toHex()
            )

            pythonResult.getBoolean("valid") shouldBe true
            pythonResult.getInt("value") shouldBe result.value

            println("  SUCCESS: $cost-bit stamp validates in Python")
        }

        @Test
        @Tag("slow")
        @Timeout(60, unit = TimeUnit.SECONDS)
        fun `12-bit stamp validates in Python`() {
            println("\n=== Test: 12-bit stamp validates in Python ===")

            val messageId = randomMessageId()
            val stampCost = 12
            val expandRounds = 25

            println("  Stamp cost: $stampCost bits (slow test)")

            // Generate workblock
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            // Generate stamp in Kotlin
            val stampStart = System.currentTimeMillis()
            val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }
            val stampTime = System.currentTimeMillis() - stampStart
            println("  [Kotlin] Generated stamp in ${stampTime}ms, value=${result.value}, rounds=${result.rounds}")

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast stampCost

            // Validate in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to stampCost,
                "workblock" to workblock.toHex()
            )

            pythonResult.getBoolean("valid") shouldBe true

            println("  SUCCESS: 12-bit stamp validates in Python")
        }

        @Test
        @Tag("slow")
        @Timeout(300, unit = TimeUnit.SECONDS)
        fun `16-bit stamp validates in Python`() {
            println("\n=== Test: 16-bit stamp validates in Python ===")

            val messageId = randomMessageId()
            val stampCost = 16
            val expandRounds = 25

            println("  Stamp cost: $stampCost bits (slow test)")

            // Generate workblock
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

            // Generate stamp in Kotlin
            val stampStart = System.currentTimeMillis()
            val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }
            val stampTime = System.currentTimeMillis() - stampStart
            println("  [Kotlin] Generated stamp in ${stampTime}ms, value=${result.value}, rounds=${result.rounds}")

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast stampCost

            // Validate in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to stampCost,
                "workblock" to workblock.toHex()
            )

            pythonResult.getBoolean("valid") shouldBe true

            println("  SUCCESS: 16-bit stamp validates in Python")
        }

        @Test
        fun `stamp value matches Python computation`() {
            println("\n=== Test: stamp value matches Python computation ===")

            val messageId = randomMessageId()
            val stampCost = 4
            val expandRounds = 25

            println("  Message ID: ${messageId.toHex()}")
            println("  Stamp cost: $stampCost bits")

            // Generate workblock and stamp
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)
            val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }

            result.stamp shouldNotBe null
            println("  [Kotlin] Stamp: ${result.stamp!!.toHex()}")

            // Compute value in Kotlin
            val kotlinValue = LXStamper.stampValue(workblock, result.stamp!!)
            println("  [Kotlin] value=$kotlinValue")

            // Validate in Python to get value
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to 0,  // Any cost, we just want the value
                "workblock" to workblock.toHex()
            )

            val pythonValue = pythonResult.getInt("value")
            println("  [Python] value=$pythonValue")

            kotlinValue shouldBe pythonValue

            println("  SUCCESS: Stamp value matches Python computation")
        }

        @Test
        fun `over-qualified stamp validates at lower cost`() {
            println("\n=== Test: over-qualified stamp validates at lower cost ===")

            val messageId = randomMessageId()
            val actualCost = 8
            val requiredCost = 4
            val expandRounds = 25

            println("  Actual cost: $actualCost bits, Required cost: $requiredCost bits")

            // Generate 8-bit stamp
            val workblock = LXStamper.generateWorkblock(messageId, expandRounds)
            val result = runBlocking { LXStamper.generateStamp(workblock, actualCost) }

            result.stamp shouldNotBe null
            result.value shouldBeAtLeast actualCost
            println("  [Kotlin] Generated stamp with value=${result.value}")

            // Validate at lower cost in Kotlin
            val validAtLowerCost = LXStamper.isStampValid(result.stamp!!, requiredCost, workblock)
            println("  [Kotlin] Valid at $requiredCost-bit cost: $validAtLowerCost")

            // Validate at lower cost in Python
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to requiredCost,
                "workblock" to workblock.toHex()
            )

            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] Valid at $requiredCost-bit cost: $pythonValid")

            validAtLowerCost shouldBe true
            pythonValid shouldBe true

            println("  SUCCESS: Over-qualified stamp validates at lower cost")
        }
    }
}
