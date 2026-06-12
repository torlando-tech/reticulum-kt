package network.reticulum.crypto

/**
 * PKCS7 padding, ported byte-for-byte from python RNS/Cryptography/PKCS7.py.
 *
 * Note the python reference's unpad is intentionally LAX: it strips
 * `data[-1]` bytes and only rejects a count greater than the block size —
 * it never validates that the stripped bytes equal the pad value, and a
 * trailing 0x00 strips nothing. Conformant implementations must preserve
 * this (a strict content-checking unpad diverges from the reference on
 * wire-reachable inputs, e.g. inside Token.decrypt).
 */
object PKCS7 {
    const val BLOCKSIZE = 16

    /** python: `n = bs - l % bs; return data + bytes([n]) * n` */
    fun pad(data: ByteArray, bs: Int = BLOCKSIZE): ByteArray {
        val n = bs - data.size % bs
        return data + ByteArray(n) { n.toByte() }
    }

    /**
     * python: `n = data[-1]; if n > bs: raise ValueError(...); return data[:l-n]`
     *
     * The `data[:l-n]` slice is mirrored exactly, including python's
     * negative-stop semantics for the (degenerate, sub-block) case where
     * n exceeds the data length: a negative stop counts from the end.
     */
    fun unpad(data: ByteArray, bs: Int = BLOCKSIZE): ByteArray {
        require(data.isNotEmpty()) { "Cannot unpad empty data" }
        val n = data.last().toInt() and 0xFF
        if (n > bs) {
            throw IllegalArgumentException("Cannot unpad, invalid padding length of $n bytes")
        }
        var stop = data.size - n
        if (stop < 0) stop += data.size  // python negative-slice-stop semantics
        if (stop < 0) stop = 0
        return data.copyOfRange(0, stop)
    }
}
