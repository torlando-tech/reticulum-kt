package network.reticulum.storage

/**
 * Persistent storage for a destination's inbound ratchet state.
 *
 * Stores the signed msgpack blob (`{signature, ratchets}`) that
 * `Destination.persistRatchets` produces, keyed by destination hash.
 * Implementations keep the bytes opaque — serialization, signing, and
 * signature verification all live in `Destination`.
 *
 * Implementations must be thread-safe. `save`/`delete` are called from
 * whichever thread rotates ratchets (typically the destination's own
 * message-handling scope); `load` is called on `Destination.enableRatchets`
 * which runs on the caller's thread at router startup.
 */
interface DestinationRatchetStore {
    /**
     * Persist the signed ratchet blob for [destHash].
     * The caller passes the already-packed and signed bytes; the store
     * should not try to interpret them.
     */
    fun save(destHash: ByteArray, data: ByteArray)

    /** Load the signed ratchet blob for [destHash], or null if none. */
    fun load(destHash: ByteArray): ByteArray?

    /** Remove the entry for [destHash]. No-op if not present. */
    fun delete(destHash: ByteArray)
}
