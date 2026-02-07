package network.reticulum.transport

import network.reticulum.common.InterfaceMode

/**
 * Determines whether an announce should be forwarded to a given interface,
 * based on the Python reference implementation (Transport.py:1040-1084).
 */
object AnnounceFilter {

    /**
     * Check whether an announce should be forwarded to an interface with
     * [outgoingMode].
     *
     * @param outgoingMode The mode of the interface we'd send the announce on
     * @param isLocalDestination Whether the destination is hosted on this instance
     * @param sourceMode The mode of the next-hop interface where the announce
     *   was originally received (null if unknown)
     * @return true if the announce should be forwarded
     */
    fun shouldForward(
        outgoingMode: InterfaceMode,
        isLocalDestination: Boolean,
        sourceMode: InterfaceMode?
    ): Boolean {
        return when (outgoingMode) {
            // AP mode: never broadcast announces
            InterfaceMode.ACCESS_POINT -> false

            // ROAMING mode: allow local destinations; block if source is ROAMING or BOUNDARY
            InterfaceMode.ROAMING -> {
                if (isLocalDestination) true
                else when (sourceMode) {
                    InterfaceMode.ROAMING, InterfaceMode.BOUNDARY -> false
                    null -> false
                    else -> true
                }
            }

            // BOUNDARY mode: allow local destinations; block if source is ROAMING
            InterfaceMode.BOUNDARY -> {
                if (isLocalDestination) true
                else when (sourceMode) {
                    InterfaceMode.ROAMING -> false
                    null -> false
                    else -> true
                }
            }

            // FULL, POINT_TO_POINT, GATEWAY: no mode-based restrictions
            else -> true
        }
    }

    /**
     * Get the path expiry duration for a given interface mode.
     * Matches Python Transport.py:1730-1735.
     */
    fun pathExpiryForMode(mode: InterfaceMode): Long {
        return when (mode) {
            InterfaceMode.ACCESS_POINT -> TransportConstants.AP_PATH_TIME
            InterfaceMode.ROAMING -> TransportConstants.ROAMING_PATH_TIME
            else -> TransportConstants.PATHFINDER_E
        }
    }
}
