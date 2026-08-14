package network.reticulum.cli.config

import network.reticulum.interfaces.tcp.TCPClientInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InterfaceConfigFactoryTest {
    @Test
    fun `TCP client forwards configured Python reconnect limit`() {
        val config = InterfaceConfig(
            name = "ConfiguredTcpClient",
            type = "TCPClientInterface",
            options = mapOf(
                "target_host" to "127.0.0.1",
                "target_port" to 4242,
                "max_reconnect_tries" to 7,
            ),
        )

        assertEquals(7, config.maxReconnectTries)

        val iface = InterfaceConfigFactory.createTcpClient(config) as TCPClientInterface
        assertEquals(7, iface.configuredMaxReconnectAttempts())
    }

    @Test
    fun `TCP client preserves Python unlimited reconnect default`() {
        val config = InterfaceConfig(
            name = "UnlimitedTcpClient",
            type = "TCPClientInterface",
            options = mapOf(
                "target_host" to "127.0.0.1",
                "target_port" to 4242,
            ),
        )

        assertEquals(null, config.maxReconnectTries)

        val iface = InterfaceConfigFactory.createTcpClient(config) as TCPClientInterface
        assertEquals(null, iface.configuredMaxReconnectAttempts())
    }

    private fun TCPClientInterface.configuredMaxReconnectAttempts(): Int? {
        val field = TCPClientInterface::class.java.getDeclaredField("maxReconnectAttempts")
        field.isAccessible = true
        return field.get(this) as Int?
    }
}
