package network.reticulum.discovery

/**
 * Address/name validation helpers for interface discovery, ported from
 * python RNS/Discovery.py:769-790 (is_ip_address, is_ygg_ipv6, is_hostname,
 * san_map).
 */
object DiscoveryUtil {

    /** python is_ip_address: ipaddress.ip_address(s) parses (v4 or v6). */
    fun isIpAddress(address: String): Boolean =
        org.bouncycastle.util.IPAddress.isValid(address)

    /** python is_ygg_ipv6: address in IPv6Network("200::/7"). */
    fun isYggIpv6(address: String): Boolean {
        if (!org.bouncycastle.util.IPAddress.isValidIPv6(address)) return false
        return try {
            val bytes = java.net.InetAddress.getByName(address).address
            // 200::/7 — the top 7 bits equal 0b0000001
            bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0x02
        } catch (e: Exception) {
            false
        }
    }

    private val hostnameLabel = Regex("(?!-)[a-zA-Z0-9-]{1,63}(?<!-)$")

    /** python is_hostname (Discovery.py:779-785), label-for-label. */
    fun isHostname(hostname: String): Boolean {
        if (hostname.isEmpty()) return false
        var h = hostname
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.length > 253) return false
        val components = h.split(".")
        if (components.last().matches(Regex("[0-9]+$"))) return false
        return components.all { hostnameLabel.matches(it) }
    }

    /** python san_map: 0-9, A-Z, a-z (Discovery.py:787-790). */
    private const val SAN_MAP =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * python InterfaceAnnounceHandler.sanitize_name (Discovery.py:205-212):
     * ascii-only, strip, collapse 5/3/2-space runs, trim leading chars not in
     * san_map and trailing chars not in san_map+")"; null/empty -> null.
     */
    fun sanitizeName(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        var n = name.filter { it.code in 0..127 }.trim()
        for (i in intArrayOf(5, 3, 2)) n = n.replace(" ".repeat(i), " ")
        while (n.isNotEmpty() && n[0] !in SAN_MAP) n = n.substring(1)
        while (n.isNotEmpty() && n.last() !in SAN_MAP && n.last() != ')') n = n.dropLast(1)
        return n
    }

    /**
     * python InterfaceAnnouncer.sanitize (Discovery.py:89-94): newline/CR
     * strip + trim; null -> null.
     */
    fun sanitizeAnnouncerString(value: String?): String? {
        if (value == null) return null
        return value.replace("\n", "").replace("\r", "").trim()
    }
}
