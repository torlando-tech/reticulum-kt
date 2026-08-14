package network.reticulum.cli.config

import network.reticulum.interfaces.tcp.TCPClientInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class InterfaceConfigFactoryTest {
    @TempDir
    lateinit var tempDir: Path

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

    @Test
    fun `TCP client accepts quoted integer reconnect limit like Python ConfigObj`() {
        val configFile = tempDir.resolve("config")
        configFile.writeText(
            """
            [interfaces]
              [[QuotedTcpClient]]
                type = TCPClientInterface
                enabled = yes
                target_host = 127.0.0.1
                target_port = 4242
                max_reconnect_tries = "7"
            """.trimIndent(),
        )

        val config = ConfigParser.parse(configFile.toFile()).interfaces.getValue("QuotedTcpClient")
        assertEquals(7, config.maxReconnectTries)
    }

    @Test
    fun `TCP client accepts supplementary Unicode decimal digits like Python int`() {
        val config = InterfaceConfig(
            name = "UnicodeTcpClient",
            type = "TCPClientInterface",
            options = mapOf("max_reconnect_tries" to "𑁦"),
        )

        assertEquals(0, config.maxReconnectTries)
    }

    @Test
    fun `TCP client rejects reconnect limits Python ConfigObj rejects`() {
        listOf(7.5, "malformed", "", Int.MAX_VALUE.toLong() + 1).forEach { invalidValue ->
            val config = InterfaceConfig(
                name = "InvalidTcpClient",
                type = "TCPClientInterface",
                options = mapOf("max_reconnect_tries" to invalidValue),
            )

            assertThrows(IllegalArgumentException::class.java) {
                config.maxReconnectTries
            }
        }
    }

    private fun TCPClientInterface.configuredMaxReconnectAttempts(): Int? {
        val field = TCPClientInterface::class.java.getDeclaredField("maxReconnectAttempts")
        field.isAccessible = true
        return field.get(this) as Int?
    }
}
