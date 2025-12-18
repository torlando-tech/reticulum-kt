package network.reticulum.common

/**
 * Base exception for all Reticulum errors.
 */
sealed class RnsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Cryptographic operation failed.
 */
class CryptoException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Packet encoding/decoding failed.
 */
class PacketException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Identity operation failed.
 */
class IdentityException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Destination operation failed.
 */
class DestinationException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Link operation failed.
 */
class LinkException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Interface operation failed.
 */
class InterfaceException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Transport/routing operation failed.
 */
class TransportException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)

/**
 * Configuration error.
 */
class ConfigException(
    message: String,
    cause: Throwable? = null
) : RnsException(message, cause)
