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

    @Nested
    @DisplayName("InvalidStampRejection")
    inner class InvalidStampRejection {

        @Test
        fun `wrong difficulty rejected by both implementations`() {
            println("\n=== Test: wrong difficulty rejected by both implementations ===")

            // Generate 4-bit stamp, try to validate as 8-bit
            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)

            println("  Generating 4-bit stamp...")
            val stamp = runBlocking { LXStamper.generateStamp(workblock, 4) }.stamp!!

            println("  Validating as 8-bit (should fail)...")

            // Kotlin rejects
            val kotlinValid = LXStamper.isStampValid(stamp, 8, workblock)
            println("  [Kotlin] isStampValid at cost=8: $kotlinValid")
            kotlinValid shouldBe false

            // Python also rejects
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to stamp.toHex(),
                "target_cost" to 8,
                "workblock" to workblock.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid at cost=8: $pythonValid")
            pythonValid shouldBe false

            println("  SUCCESS: Both implementations reject under-qualified stamp")
        }

        @Test
        fun `corrupted stamp rejected by both implementations`() {
            println("\n=== Test: corrupted stamp rejected by both implementations ===")

            // Generate valid stamp, corrupt one byte
            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)

            println("  Generating valid 4-bit stamp...")
            val validStamp = runBlocking { LXStamper.generateStamp(workblock, 4) }.stamp!!
            val originalValue = LXStamper.stampValue(workblock, validStamp)
            println("  Original stamp value: $originalValue")

            val corruptedStamp = validStamp.copyOf()
            corruptedStamp[0] = (corruptedStamp[0].toInt() xor 0xFF).toByte()
            val corruptedValue = LXStamper.stampValue(workblock, corruptedStamp)
            println("  Corrupted stamp (byte 0 XOR 0xFF), new value: $corruptedValue")

            // Kotlin rejects
            val kotlinValid = LXStamper.isStampValid(corruptedStamp, 4, workblock)
            println("  [Kotlin] isStampValid: $kotlinValid")
            kotlinValid shouldBe false

            // Python also rejects
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to corruptedStamp.toHex(),
                "target_cost" to 4,
                "workblock" to workblock.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid: $pythonValid")
            pythonValid shouldBe false

            println("  SUCCESS: Both implementations reject corrupted stamp")
        }

        @Test
        fun `wrong workblock (message hash) rejected by both implementations`() {
            println("\n=== Test: wrong workblock (message hash) rejected by both ===")

            // Generate stamp for message A, validate against workblock for message B
            val messageIdA = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val messageIdB = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblockA = LXStamper.generateWorkblock(messageIdA, 25)
            val workblockB = LXStamper.generateWorkblock(messageIdB, 25)

            println("  Message A: ${messageIdA.toHex().take(16)}...")
            println("  Message B: ${messageIdB.toHex().take(16)}...")

            println("  Generating stamp for message A...")
            val stampForA = runBlocking { LXStamper.generateStamp(workblockA, 4) }.stamp!!
            val valueOnA = LXStamper.stampValue(workblockA, stampForA)
            val valueOnB = LXStamper.stampValue(workblockB, stampForA)
            println("  Stamp value on workblock A: $valueOnA")
            println("  Stamp value on workblock B: $valueOnB")

            // Kotlin rejects with wrong workblock
            val kotlinValid = LXStamper.isStampValid(stampForA, 4, workblockB)
            println("  [Kotlin] isStampValid with wrong workblock: $kotlinValid")
            kotlinValid shouldBe false

            // Python also rejects with wrong workblock
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to stampForA.toHex(),
                "target_cost" to 4,
                "workblock" to workblockB.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid with wrong workblock: $pythonValid")
            pythonValid shouldBe false

            println("  SUCCESS: Both reject stamp validated against wrong workblock")
        }

        @Test
        fun `truncated stamp rejected by Kotlin`() {
            println("\n=== Test: truncated stamp rejected by Kotlin ===")

            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val truncatedStamp = ByteArray(16) // Should be 32

            println("  Stamp size: ${truncatedStamp.size} bytes (expected: 32)")

            // validateStamp checks size first
            val valid = LXStamper.validateStamp(truncatedStamp, messageId, 4, 25)
            println("  [Kotlin] validateStamp: $valid")
            valid shouldBe false

            println("  SUCCESS: Truncated stamp rejected")
        }

        @Test
        fun `empty stamp rejected by Kotlin`() {
            println("\n=== Test: empty stamp rejected by Kotlin ===")

            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val emptyStamp = ByteArray(0)

            println("  Stamp size: ${emptyStamp.size} bytes")

            val valid = LXStamper.validateStamp(emptyStamp, messageId, 4, 25)
            println("  [Kotlin] validateStamp: $valid")
            valid shouldBe false

            println("  SUCCESS: Empty stamp rejected")
        }

        @Test
        fun `random bytes rejected as stamp by both implementations`() {
            println("\n=== Test: random bytes rejected as stamp by both ===")

            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)
            val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }

            val value = LXStamper.stampValue(workblock, randomBytes)
            println("  Random bytes stamp value: $value (need >=8 to pass)")

            // Random bytes very unlikely to be valid at cost >= 1
            // (probability 2^-cost that random bytes pass)
            val kotlinValid = LXStamper.isStampValid(randomBytes, 8, workblock)
            println("  [Kotlin] isStampValid at cost=8: $kotlinValid")
            kotlinValid shouldBe false

            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to randomBytes.toHex(),
                "target_cost" to 8,
                "workblock" to workblock.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid at cost=8: $pythonValid")
            pythonValid shouldBe false

            println("  SUCCESS: Random bytes rejected as stamp by both implementations")
        }
    }

    @Nested
    @DisplayName("EdgeCases")
    inner class EdgeCases {

        @Test
        fun `over-qualified stamp (higher value than required) accepted by both`() {
            println("\n=== Test: over-qualified stamp accepted by both ===")

            // Generate 8-bit stamp, validate at 4-bit cost (should pass)
            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)

            println("  Generating 8-bit stamp...")
            val result = runBlocking { LXStamper.generateStamp(workblock, 8) }

            println("  Generated stamp with value=${result.value}, testing at cost=4")
            assertTrue(result.value >= 8, "Stamp value should be at least 8")

            // Kotlin accepts at lower cost
            val kotlinValid = LXStamper.isStampValid(result.stamp!!, 4, workblock)
            println("  [Kotlin] isStampValid at cost=4: $kotlinValid")
            kotlinValid shouldBe true

            // Python also accepts at lower cost
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to result.stamp!!.toHex(),
                "target_cost" to 4,
                "workblock" to workblock.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid at cost=4: $pythonValid")
            pythonValid shouldBe true

            println("  SUCCESS: Over-qualified stamp (8-bit) accepted at 4-bit requirement")
        }

        @Test
        fun `difficulty 0 stamp is trivially valid`() {
            println("\n=== Test: difficulty 0 stamp is trivially valid ===")

            // Any stamp should be valid at cost 0
            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)
            val randomStamp = ByteArray(32).also { SecureRandom().nextBytes(it) }

            val value = LXStamper.stampValue(workblock, randomStamp)
            println("  Random stamp value: $value")
            println("  Testing at cost=0 (any stamp should pass)")

            // Kotlin accepts at cost 0
            val kotlinValid = LXStamper.isStampValid(randomStamp, 0, workblock)
            println("  [Kotlin] isStampValid at cost=0: $kotlinValid")
            kotlinValid shouldBe true

            // Python also accepts at cost 0
            val pythonResult = python(
                "lxmf_stamp_valid",
                "stamp" to randomStamp.toHex(),
                "target_cost" to 0,
                "workblock" to workblock.toHex()
            )
            val pythonValid = pythonResult.getBoolean("valid")
            println("  [Python] valid at cost=0: $pythonValid")
            pythonValid shouldBe true

            println("  SUCCESS: Any stamp is trivially valid at cost=0")
        }

        @Test
        fun `stamp value is consistent across multiple validations`() {
            println("\n=== Test: stamp value is consistent across multiple validations ===")

            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val workblock = LXStamper.generateWorkblock(messageId, 25)
            val result = runBlocking { LXStamper.generateStamp(workblock, 4) }

            println("  Computing stamp value 3 times...")

            // Multiple calls should return same value
            val value1 = LXStamper.stampValue(workblock, result.stamp!!)
            val value2 = LXStamper.stampValue(workblock, result.stamp!!)
            val value3 = LXStamper.stampValue(workblock, result.stamp!!)

            println("  Value 1: $value1")
            println("  Value 2: $value2")
            println("  Value 3: $value3")

            value1 shouldBe value2
            value2 shouldBe value3

            println("  SUCCESS: Stamp value consistently computed as $value1")
        }

        @Test
        fun `different expand rounds produce different workblocks`() {
            println("\n=== Test: different expand rounds produce different workblocks ===")

            val messageId = ByteArray(32).also { SecureRandom().nextBytes(it) }

            println("  Generating workblocks with 25 and 50 expand rounds...")
            val workblock25 = LXStamper.generateWorkblock(messageId, 25)
            val workblock50 = LXStamper.generateWorkblock(messageId, 50)

            println("  Workblock 25 size: ${workblock25.size} bytes (expected: ${25 * 256})")
            println("  Workblock 50 size: ${workblock50.size} bytes (expected: ${50 * 256})")

            workblock25.size shouldBe 25 * 256
            workblock50.size shouldBe 50 * 256

            // First 25*256 bytes should match (same material, same first 25 rounds)
            val first25Rounds = workblock50.take(25 * 256)
            val workblock25List = workblock25.toList()

            val match = workblock25List == first25Rounds
            println("  First 25*256 bytes of workblock50 match workblock25: $match")

            workblock25List shouldBe first25Rounds

            println("  SUCCESS: Workblock expansion is deterministic and additive")
        }
    }
}
