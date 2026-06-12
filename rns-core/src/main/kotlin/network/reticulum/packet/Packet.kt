package network.reticulum.packet

import network.reticulum.common.ContextFlag
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.link.Link
import network.reticulum.transport.Transport

/**
 * Represents a packet in the Reticulum network.
 *
 * Wire format for HEADER_1:
 * ```
 * [flags: 1] [hops: 1] [dest_hash: 16] [context: 1] [data: variable]
 * ```
 *
 * Wire format for HEADER_2 (transport):
 * ```
 * [flags: 1] [hops: 1] [transport_id: 16] [dest_hash: 16] [context: 1] [data: variable]
 * ```
 *
 * Flags byte layout:
 * ```
 * [7:6] header_type
 * [5]   context_flag
 * [4]   transport_type bit 0
 * [3:2] destination_type
 * [1:0] packet_type
 * ```
 */
class Packet private constructor(
    /** The packet type (DATA, ANNOUNCE, LINKREQUEST, PROOF). */
    val packetType: PacketType,
    /** The header type (HEADER_1 or HEADER_2). */
    val headerType: HeaderType,
    /** The transport type. */
    val transportType: TransportType,
    /** The destination type. */
    val destinationType: DestinationType,
    /** The context flag. */
    val contextFlag: ContextFlag,
    /** The packet context. */
    val context: PacketContext,
    /** Number of hops this packet has traveled. */
    var hops: Int,
    /** The destination hash (16 bytes). */
    val destinationHash: ByteArray,
    /** The transport ID for HEADER_2 packets (16 bytes), null for HEADER_1. */
    var transportId: ByteArray?,
    /** The packet data (ciphertext for encrypted, plaintext for announce/linkrequest). */
    var data: ByteArray,
    /** Whether to create a receipt when sending this packet. */
    val createReceipt: Boolean = true,
    /** MTU for this packet. Python sets per-packet from destination.mtu; defaults to global MTU. */
    val mtu: Int = RnsConstants.MTU
) {
    /**
     * The actual context byte on the wire. python carries context as a raw
     * int (any value parses; unknown values match no handler), so this is
     * the pack/unpack source of truth — [context] is the named view and is
     * [PacketContext.UNKNOWN] when the byte has no named code point.
     */
    var contextRaw: Int = context.value

    /** The destination this packet is addressed to (null if created from raw). */
    internal var destination: Destination? = null

    /** The link this packet is associated with (null if not a link packet). */
    internal var link: Link? = null

    /** If set, Transport will only send this packet on this specific interface. */
    var attachedInterface: network.reticulum.transport.InterfaceRef? = null

    /** Hash of the interface this packet was received on (set during inbound processing). */
    var receivingInterfaceHash: ByteArray? = null
        internal set

    /**
     * Conformance test seam: stamp the receiving-interface hash on a crafted
     * inbound packet, the kotlin equivalent of the reference setting
     * `rx.receiving_interface = <iface>` before feeding a hand-built packet to
     * Link.validateRequest / link.receive (reticulum-conformance reference/
     * wire_tcp.py). The setter is internal so the separate-module bridge needs
     * this seam. No port logic — just exposes the existing field.
     */
    fun setReceivingInterfaceHashForTest(hash: ByteArray?) {
        receivingInterfaceHash = hash
    }

    /** Physical layer signal stats from receiving interface. */
    var rssi: Int? = null
        internal set
    var snr: Float? = null
        internal set
    var q: Float? = null
        internal set

    /**
     * Whether this packet has been sent. Public-settable, as python's
     * `Packet.sent` attribute is (resend()'s precondition; the conformance
     * bridge sets it exactly as the python reference bridge does).
     */
    var sent: Boolean = false

    /** The timestamp when the packet was sent. */
    var sentAt: Long? = null
        private set

    /** The receipt for tracking delivery (null if createReceipt=false). */
    var receipt: PacketReceipt? = null
        internal set
    /**
     * The raw packed bytes of this packet.
     * Populated after pack() is called.
     */
    var raw: ByteArray? = null
        internal set

    /**
     * The full hash of the packet's hashable portion.
     */
    val packetHash: ByteArray by lazy {
        val hashable = getHashablePart()
        Hashes.fullHash(hashable)
    }

    /**
     * The truncated hash of the packet.
     */
    val truncatedHash: ByteArray by lazy {
        Hashes.truncatedHash(getHashablePart())
    }

    /**
     * The hex-encoded packet hash.
     */
    val hexHash: String by lazy { packetHash.toHexString() }

    /**
     * Get the packed flags byte.
     */
    fun getPackedFlags(): Int {
        return (headerType.value shl 6) or
               (contextFlag.value shl 5) or
               (transportType.value shl 4) or
               (destinationType.value shl 2) or
               packetType.value
    }

    /**
     * Pack the packet into raw bytes, mirroring python Packet.pack
     * (Packet.py:186-238).
     *
     * With a [destination] attached (builder path, [create]): the ciphertext
     * is produced HERE, fresh on every pack — python encrypts inside pack()
     * precisely so resend() re-packs with new ephemeral key material/IV —
     * via python's exact branch table of unencrypted packet classes; and a
     * HEADER_2 header only assembles for ANNOUNCE packets (any other type
     * never assigns ciphertext and pack fails, as python's AttributeError
     * path does).
     *
     * Without a destination ([createRaw] path — transport re-wraps, proofs,
     * pre-encrypted link traffic): [data] IS the final payload and is passed
     * through untouched, matching how python transport code splices raw
     * bytes rather than re-packing.
     */
    fun pack(): ByteArray {
        var header = byteArrayOf(getPackedFlags().toByte(), hops.toByte())
        var ciphertext: ByteArray? = null

        when (headerType) {
            HeaderType.HEADER_1 -> {
                header += destinationHash
                val dest = destination
                ciphertext = if (dest == null) {
                    data
                } else when {
                    // python's unencrypted classes (Packet.py:189-212)
                    packetType == PacketType.ANNOUNCE -> data
                    packetType == PacketType.LINKREQUEST -> data
                    packetType == PacketType.PROOF && context == PacketContext.RESOURCE_PRF -> data
                    packetType == PacketType.PROOF && dest.type == DestinationType.LINK -> data
                    context == PacketContext.RESOURCE -> data
                    context == PacketContext.KEEPALIVE -> data
                    context == PacketContext.CACHE_REQUEST -> data
                    else -> dest.encrypt(data)
                }
            }
            HeaderType.HEADER_2 -> {
                val tid = transportId ?: throw IllegalStateException(
                    "Packet with header type 2 must have a transport ID"
                )
                header += tid
                header += destinationHash
                ciphertext = if (destination == null) {
                    data
                } else if (packetType == PacketType.ANNOUNCE) {
                    // Announce packets are not encrypted (Packet.py:224-226);
                    // python assigns ciphertext ONLY for announces here.
                    data
                } else {
                    null
                }
            }
        }

        // python: a destination-built non-ANNOUNCE HEADER_2 never assigns
        // self.ciphertext, so raw assembly raises (AttributeError upstream).
        val body = ciphertext ?: throw IllegalStateException(
            "Packet with header type 2 can only be assembled for announces"
        )

        header += byteArrayOf(contextRaw.toByte())
        val packed = header + body
        raw = packed

        // python: IOError("Packet size of X exceeds MTU of Y bytes")
        if (packed.size > mtu) {
            throw IllegalStateException(
                "Packet size of ${packed.size} exceeds MTU of ${mtu} bytes"
            )
        }

        return packed
    }

    /**
     * Get the hashable portion of the packet.
     *
     * For packet hash calculation, the transport_id is excluded for HEADER_2 packets,
     * and only the lower 4 bits of the flags byte are used (masking header_type and context_flag).
     */
    /**
     * The packet hash: full SHA-256 of the hashable part, exactly python
     * Packet.get_hash (Packet.py:356-358) — stable across hops/transport_id.
     */
    fun getHash(): ByteArray = Hashes.fullHash(getHashablePart())

    fun getHashablePart(): ByteArray {
        val packed = raw ?: pack()

        // Mask the flags byte to only keep lower 4 bits
        val maskedFlags = (packed[0].toInt() and 0b00001111).toByte()

        return when (headerType) {
            HeaderType.HEADER_1 -> {
                // [masked_flags] + [hops onwards]
                byteArrayOf(maskedFlags) + packed.copyOfRange(2, packed.size)
            }
            HeaderType.HEADER_2 -> {
                // Skip transport_id (16 bytes after hops)
                // [masked_flags] + [after transport_id]
                byteArrayOf(maskedFlags) + packed.copyOfRange(
                    2 + RnsConstants.TRUNCATED_HASH_BYTES,
                    packed.size
                )
            }
        }
    }

    companion object {
        /**
         * Create a new packet for a destination.
         */
        fun create(
            destination: Destination,
            data: ByteArray,
            packetType: PacketType = PacketType.DATA,
            context: PacketContext = PacketContext.NONE,
            transportType: TransportType = TransportType.BROADCAST,
            headerType: HeaderType = HeaderType.HEADER_1,
            transportId: ByteArray? = null,
            contextFlag: ContextFlag = ContextFlag.UNSET,
            createReceipt: Boolean = true
        ): Packet {
            // python encrypts inside pack(), NOT at construction — so that
            // resend()'s re-pack produces fresh ephemeral key material/IV
            // (Packet.py:305-323). data stays plaintext here; pack() applies
            // python's unencrypted-class branch table.
            return Packet(
                packetType = packetType,
                headerType = headerType,
                transportType = transportType,
                destinationType = destination.type,
                contextFlag = contextFlag,
                context = context,
                hops = 0,
                destinationHash = destination.hash.copyOf(),
                transportId = transportId?.copyOf(),
                data = data,
                createReceipt = createReceipt
            ).also {
                it.destination = destination
            }
        }

        /**
         * Create a raw packet from destination hash and data without a Destination object.
         */
        fun createRaw(
            destinationHash: ByteArray,
            data: ByteArray,
            packetType: PacketType = PacketType.DATA,
            destinationType: DestinationType = DestinationType.SINGLE,
            context: PacketContext = PacketContext.NONE,
            transportType: TransportType = TransportType.BROADCAST,
            headerType: HeaderType = HeaderType.HEADER_1,
            transportId: ByteArray? = null,
            contextFlag: ContextFlag = ContextFlag.UNSET,
            createReceipt: Boolean = true,
            mtu: Int = RnsConstants.MTU
        ): Packet {
            require(destinationHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
                "Destination hash must be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
            }

            return Packet(
                packetType = packetType,
                headerType = headerType,
                transportType = transportType,
                destinationType = destinationType,
                contextFlag = contextFlag,
                context = context,
                hops = 0,
                destinationHash = destinationHash.copyOf(),
                transportId = transportId?.copyOf(),
                data = data,
                createReceipt = createReceipt,
                mtu = mtu
            )
        }

        /**
         * Unpack raw bytes into a Packet.
         *
         * @param raw The raw packet bytes
         * @return The unpacked Packet, or null if the packet is malformed
         */
        fun unpack(raw: ByteArray): Packet? {
            return try {
                if (raw.size < RnsConstants.HEADER_MIN_SIZE) {
                    return null
                }

                val flags = raw[0].toInt() and 0xFF
                val hops = raw[1].toInt() and 0xFF

                // Parse flags
                val headerType = HeaderType.fromValue((flags and 0b01000000) shr 6)
                val contextFlag = ContextFlag.fromValue((flags and 0b00100000) shr 5)
                val transportType = TransportType.fromValue((flags and 0b00010000) shr 4)
                val destinationType = DestinationType.fromValue((flags and 0b00001100) shr 2)
                val packetType = PacketType.fromValue(flags and 0b00000011)

                val dstLen = RnsConstants.TRUNCATED_HASH_BYTES

                val transportId: ByteArray?
                val destinationHash: ByteArray
                val context: PacketContext
                val data: ByteArray

                val contextByte: Int
                when (headerType) {
                    HeaderType.HEADER_1 -> {
                        transportId = null
                        destinationHash = raw.copyOfRange(2, 2 + dstLen)
                        contextByte = raw[2 + dstLen].toInt() and 0xFF
                        data = raw.copyOfRange(3 + dstLen, raw.size)
                    }
                    HeaderType.HEADER_2 -> {
                        if (raw.size < RnsConstants.HEADER_MAX_SIZE) {
                            return null
                        }
                        transportId = raw.copyOfRange(2, 2 + dstLen)
                        destinationHash = raw.copyOfRange(2 + dstLen, 2 + 2 * dstLen)
                        contextByte = raw[2 + 2 * dstLen].toInt() and 0xFF
                        data = raw.copyOfRange(3 + 2 * dstLen, raw.size)
                    }
                }
                // python keeps context as a raw int — an unknown code point
                // parses fine and matches no dispatch branch. UNKNOWN +
                // contextRaw preserve that (forward compatibility).
                context = PacketContext.entries.find { it.value == contextByte }
                    ?: PacketContext.UNKNOWN

                Packet(
                    packetType = packetType,
                    headerType = headerType,
                    transportType = transportType,
                    destinationType = destinationType,
                    contextFlag = contextFlag,
                    context = context,
                    hops = hops,
                    destinationHash = destinationHash,
                    transportId = transportId,
                    data = data
                ).also {
                    it.contextRaw = contextByte
                    it.raw = raw.copyOf()
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Extract just the flags from a raw packet.
         */
        fun parseFlags(flags: Int): PacketFlags {
            return PacketFlags(
                headerType = HeaderType.fromValue((flags and 0b01000000) shr 6),
                contextFlag = ContextFlag.fromValue((flags and 0b00100000) shr 5),
                transportType = TransportType.fromValue((flags and 0b00010000) shr 4),
                destinationType = DestinationType.fromValue((flags and 0b00001100) shr 2),
                packetType = PacketType.fromValue(flags and 0b00000011)
            )
        }

        /**
         * Create an announce packet for a destination.
         *
         * This method delegates to Destination.announce() to ensure consistency
         * with the full announce implementation including ratchet support.
         *
         * @param destination The destination to announce
         * @param appData Optional application data to include
         * @param pathResponse Whether this is a path response
         * @return The announce packet, or null if the destination cannot create announces
         */
        fun createAnnounce(
            destination: Destination,
            appData: ByteArray? = null,
            pathResponse: Boolean = false,
            attachedInterface: network.reticulum.transport.InterfaceRef? = null
        ): Packet? {
            return try {
                destination.announce(appData = appData, pathResponse = pathResponse, attachedInterface = attachedInterface, send = false)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Send this packet.
     *
     * @return The PacketReceipt if createReceipt=true, null if createReceipt=false, or null if send failed
     */
    fun send(): PacketReceipt? {
        if (sent) {
            throw IllegalStateException("Packet was already sent")
        }

        // For link destinations, check if link is active
        val dest = destination
        if (dest != null && dest.type == DestinationType.LINK) {
            // Would check link.status == Link.CLOSED in full implementation
            // For now, allow sending
        }

        // Pack the packet if not already packed
        if (raw == null) {
            pack()
        }

        // Send via Transport (receipt creation handled by Transport.processOutbound
        // with proper guards matching Python Transport.py:947-956)
        val success = Transport.outbound(this)

        if (success) {
            sent = true
            sentAt = System.currentTimeMillis()
            return receipt
        } else {
            // Send failed
            sent = false
            receipt = null
            return null
        }
    }

    /**
     * Re-send this packet.
     *
     * @return The PacketReceipt if createReceipt=true, null if createReceipt=false, or null if send failed
     */
    fun resend(): PacketReceipt? {
        if (!sent) {
            throw IllegalStateException("Packet was not sent yet")
        }

        // Re-pack to get new ciphertext for encrypted destinations
        pack()

        // Create new receipt if requested
        if (createReceipt) {
            receipt = PacketReceipt(this)
        }

        // Send via Transport
        val success = Transport.outbound(this)

        if (success) {
            sentAt = System.currentTimeMillis()
            return receipt
        } else {
            // Send failed
            receipt = null
            return null
        }
    }

    /**
     * Generate a proof for this packet.
     *
     * @param destination Optional destination to send the proof to (used for generating ProofDestination)
     */
    fun prove(destination: Destination? = null) {
        val dest = this.destination
        val link = this.link

        when {
            // Proof for destination
            dest != null && dest.identity?.hasPrivateKey == true -> {
                dest.identity.prove(this, destination)
            }
            // Proof for link
            link != null -> {
                link.provePacket(this)
            }
            else -> {
                // Cannot prove packet
                throw IllegalStateException("Could not prove packet associated with neither a destination nor a link")
            }
        }
    }

    /**
     * Generate a proof destination that allows Reticulum to direct the proof
     * back to the proved packet's sender.
     */
    fun generateProofDestination(): ProofDestination {
        return ProofDestination(this)
    }

    /**
     * Validate a proof packet against this packet's receipt.
     */
    fun validateProofPacket(proofPacket: Packet): Boolean {
        return receipt?.validateProofPacket(proofPacket) ?: false
    }

    /**
     * Validate a raw proof against this packet's receipt.
     */
    fun validateProof(proof: ByteArray): Boolean {
        return receipt?.validateProof(proof) ?: false
    }

    override fun toString(): String =
        "Packet(type=$packetType, dest=${destinationHash.toHexString()}, size=${data.size})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return packetHash.contentEquals(other.packetHash)
    }

    override fun hashCode(): Int = packetHash.contentHashCode()
}

/**
 * A special destination that allows proofs to be sent back to the packet sender.
 * This uses the packet hash as the destination hash.
 */
class ProofDestination(packet: Packet) {
    /** The destination hash (truncated packet hash). */
    val hash: ByteArray = packet.truncatedHash

    /** The destination type (always SINGLE). */
    val type: DestinationType = DestinationType.SINGLE

    /**
     * Encrypt data (no-op for proof destinations).
     */
    fun encrypt(plaintext: ByteArray): ByteArray = plaintext
}

/**
 * Parsed flags from a packet.
 */
data class PacketFlags(
    val headerType: HeaderType,
    val contextFlag: ContextFlag,
    val transportType: TransportType,
    val destinationType: DestinationType,
    val packetType: PacketType
)
