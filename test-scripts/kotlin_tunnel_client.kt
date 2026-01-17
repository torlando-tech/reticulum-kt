import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.transport.Transport
import java.io.File
import kotlin.concurrent.thread

fun main() {
    println("============================================================")
    println("Kotlin Tunnel Test Client")
    println("============================================================")

    // Create temp config directory
    val configDir = File("/tmp/tunnel_test_kotlin")
    if (configDir.exists()) {
        configDir.deleteRecursively()
    }
    configDir.mkdirs()

    // Create minimal config
    val configFile = File(configDir, "config")
    configFile.writeText("""
        [reticulum]
        enable_transport = yes
        share_instance = no
        loglevel = 7
    """.trimIndent())

    println("Starting Reticulum with config at ${configDir.absolutePath}")

    // Start Reticulum
    val reticulum = Reticulum(configFile.absolutePath)

    println("Reticulum started, creating TCP client interface...")

    // Create TCP client interface
    val tcpClient = TCPClientInterface.create(
        owner = null,
        name = "Kotlin Client",
        targetIp = "127.0.0.1",
        targetPort = 4244
    )

    println("Connecting to Python server at 127.0.0.1:4244...")
    tcpClient.connect()

    // Wait for tunnel to be established
    Thread.sleep(2000)

    // Check tunnel status
    val tunnels = Transport.getTunnels()
    println("\nTunnel Status:")
    println("Active tunnels: ${tunnels.size}")

    if (tunnels.isNotEmpty()) {
        for ((tunnelId, tunnelInfo) in tunnels) {
            val tunnelIdHex = tunnelId.toHexString()
            println("Tunnel $tunnelIdHex:")
            println("  Interface: ${tunnelInfo.interface.name}")
            println("  Paths: ${tunnelInfo.paths.size}")
        }
        println("\n✓ Tunnel successfully established!")
    } else {
        println("\n✗ No tunnels established")
    }

    // Keep running for a bit to allow announce packets to flow
    println("\nWaiting for announce packets to flow through tunnel...")
    Thread.sleep(5000)

    // Check again
    val tunnelsAfter = Transport.getTunnels()
    println("\nFinal Tunnel Status:")
    println("Active tunnels: ${tunnelsAfter.size}")

    for ((tunnelId, tunnelInfo) in tunnelsAfter) {
        val tunnelIdHex = tunnelId.toHexString()
        println("Tunnel $tunnelIdHex:")
        println("  Interface: ${tunnelInfo.interface.name}")
        println("  Paths: ${tunnelInfo.paths.size}")

        if (tunnelInfo.paths.isNotEmpty()) {
            println("  Stored paths:")
            tunnelInfo.paths.forEach { (destHash, pathEntry) ->
                println("    - ${destHash.toHexString()}: ${pathEntry.hops} hops")
            }
        }
    }

    // Shutdown
    println("\nShutting down...")
    reticulum.exitHandler()
    println("Done!")
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
