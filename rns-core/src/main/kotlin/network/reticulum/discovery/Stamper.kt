package network.reticulum.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import network.reticulum.crypto.Hashes
import org.msgpack.core.MessagePack
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof-of-Work stamp generation and validation.
 *
 * Used by both interface discovery (with 20 expansion rounds) and LXMF messages
 * (with 3000/1000 rounds). The algorithm:
 * 1. Generate a workblock by HKDF-expanding the material for N rounds (256 bytes each)
 * 2. Find a random 32-byte stamp where SHA256(workblock + stamp) has enough leading zero bits
 *
 * The workblock expansion makes validation expensive enough to discourage spam,
 * with the number of rounds controlling the cost.
 */
object Stamper {
    const val STAMP_SIZE = 32
    const val HKDF_OUTPUT_LENGTH = 256

    data class StampResult(
        val stamp: ByteArray?,
        val value: Int,
        val rounds: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StampResult) return false
            if (stamp != null) {
                if (other.stamp == null) return false
                if (!stamp.contentEquals(other.stamp)) return false
            } else if (other.stamp != null) return false
            return value == other.value && rounds == other.rounds
        }

        override fun hashCode(): Int {
            var result = stamp?.contentHashCode() ?: 0
            result = 31 * result + value
            result = 31 * result + rounds.hashCode()
            return result
        }
    }

    /**
     * Generate a workblock for stamp validation.
     *
     * Each round: workblock += HKDF(ikm=material, salt=SHA256(material + msgpack(n)), length=256)
     *
     * @param material The material to derive workblock from (typically a hash of the payload)
     * @param expandRounds Number of HKDF expansion rounds
     * @return The generated workblock
     */
    fun generateWorkblock(material: ByteArray, expandRounds: Int): ByteArray {
        val output = ByteArray(expandRounds * HKDF_OUTPUT_LENGTH)
        var offset = 0

        for (n in 0 until expandRounds) {
            val msgpackN = packInt(n)
            val salt = Hashes.fullHash(material + msgpackN)
            val expanded = hkdfExpand(
                ikm = material,
                salt = salt,
                info = ByteArray(0),
                length = HKDF_OUTPUT_LENGTH
            )
            System.arraycopy(expanded, 0, output, offset, HKDF_OUTPUT_LENGTH)
            offset += HKDF_OUTPUT_LENGTH
        }

        return output
    }

    /**
     * Generate a valid stamp using parallel coroutine workers.
     *
     * @param workblock The pre-generated workblock
     * @param stampCost Required number of leading zero bits
     * @return StampResult with the found stamp
     */
    suspend fun generateStamp(
        workblock: ByteArray,
        stampCost: Int
    ): StampResult = coroutineScope {
        val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val found = AtomicReference<ByteArray?>(null)
        val roundCounters = LongArray(numWorkers)

        val jobs = (0 until numWorkers).map { workerId ->
            async(Dispatchers.Default) {
                val localRandom = SecureRandom()
                var localRounds = 0L
                val stamp = ByteArray(STAMP_SIZE)

                while (found.get() == null && isActive) {
                    localRandom.nextBytes(stamp)
                    localRounds++

                    if (stampValid(stamp, stampCost, workblock)) {
                        found.compareAndSet(null, stamp.copyOf())
                        break
                    }

                    if (localRounds % 1000 == 0L) yield()
                }

                roundCounters[workerId] = localRounds
                localRounds
            }
        }

        jobs.awaitAll()

        val totalRounds = roundCounters.sum()
        val resultStamp = found.get()
        val value = if (resultStamp != null) stampValue(workblock, resultStamp) else 0

        StampResult(resultStamp, value, totalRounds)
    }

    /**
     * Check if a stamp meets the required cost.
     *
     * target = 1 << (256 - targetCost)
     * valid = BigInteger(SHA256(workblock + stamp)) <= target
     */
    fun stampValid(stamp: ByteArray, targetCost: Int, workblock: ByteArray): Boolean {
        val target = BigInteger.ONE.shiftLeft(256 - targetCost)
        val hash = Hashes.fullHash(workblock + stamp)
        return BigInteger(1, hash) <= target
    }

    /**
     * Calculate the value of a stamp (number of leading zero bits in SHA256(workblock + stamp)).
     */
    fun stampValue(workblock: ByteArray, stamp: ByteArray): Int {
        val hash = Hashes.fullHash(workblock + stamp)
        return 256 - BigInteger(1, hash).bitLength()
    }

    // ==================== Primitives ====================

    /**
     * HKDF extract + expand (RFC 5869) with HMAC-SHA256.
     */
    internal fun hkdfExpand(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        val output = ByteArray(length)
        var outputOffset = 0
        var t = ByteArray(0)

        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - outputOffset)
            System.arraycopy(t, 0, output, outputOffset, copyLen)
            outputOffset += copyLen
        }

        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Pack an integer using MessagePack format, matching Python's msgpack.packb(n).
     */
    internal fun packInt(n: Int): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packInt(n)
        return packer.toByteArray()
    }
}
