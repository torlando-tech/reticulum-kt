package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.transport.TunnelInfo
import network.reticulum.transport.TunnelPathEntry

/**
 * Persistent storage for the tunnel table and associated tunnel paths.
 *
 * Tunnels maintain routing paths across network disruptions. Each tunnel
 * contains a set of paths discovered through that tunnel's interface.
 */
interface TunnelStore {
    /** Insert or update a tunnel entry. */
    fun upsertTunnel(tunnelId: ByteArray, interfaceHash: ByteArray?, expires: Long)

    /** Insert or update a path within a tunnel. */
    fun upsertTunnelPath(tunnelId: ByteArray, destHash: ByteArray, path: TunnelPathEntry)

    /** Remove a tunnel and all its paths. */
    fun removeTunnel(tunnelId: ByteArray)

    /** Load all tunnels with their paths (called once at startup). */
    fun loadAllTunnels(): Map<ByteArrayKey, TunnelInfo>

    /** Remove all tunnels with expires before the given timestamp. */
    fun removeExpiredBefore(timestampMs: Long)
}
