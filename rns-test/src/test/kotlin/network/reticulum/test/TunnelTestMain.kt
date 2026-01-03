package network.reticulum.test

import network.reticulum.Reticulum
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.transport.Transport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    println("==============================================")
    println("Kotlin Tunnel Test Client")
    println("==============================================")

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

    println("Starting Reticulum...")
    Reticulum.start(
        configDir = configFile.absolutePath,
        enableTransport = true
    )

    println("Creating TCP client interface...")
    val tcpClient = TCPClientInterface(
        name = "Kotlin Client",
        targetHost = "127.0.0.1",
        targetPort = 4244
    )

    // Register interface with Transport
    val clientRef = tcpClient.toRef()
    Transport.registerInterface(clientRef)

    // Hook up client to transport
    tcpClient.onPacketReceived = { data, iface ->
        Transport.inbound(data, iface.toRef())
    }

    val announceLatch = CountDownLatch(1)

    // Register announce handler
    Transport.registerAnnounceHandler { destHash, identity, appData ->
        val destHex = destHash.joinToString("") { "%02x".format(it) }
        println("\n✓ Received announce for destination: $destHex")
        announceLatch.countDown()
        true
    }

    println("Connecting to Python server at 127.0.0.1:4244...")
    tcpClient.start()

    // Wait for tunnel to be established
    println("Waiting for tunnel synthesis...")
    Thread.sleep(3000)

    if (!tcpClient.online.get()) {
        println("✗ Failed to connect to server")
        return
    }

    println("✓ Connected to server")

    // Check tunnel status
    val tunnels = Transport.getTunnels()
    println("\nTunnel Status:")
    println("  Active tunnels: ${tunnels.size}")

    if (tunnels.isNotEmpty()) {
        for ((tunnelId, tunnelInfo) in tunnels) {
            val tunnelIdHex = tunnelId.bytes.joinToString("") { "%02x".format(it) }
            println("  Tunnel: ${tunnelIdHex.take(16)}...")
            println("    Interface: ${tunnelInfo.interface_?.name}")
            println("    Paths: ${tunnelInfo.paths.size}")
        }
        println("\n✓ Tunnel successfully established!")
    } else {
        println("\n✗ No tunnels established")
    }

    // Wait for announces from Python server
    println("\nWaiting for announces from Python server (30 seconds)...")
    val received = announceLatch.await(30, TimeUnit.SECONDS)

    if (received) {
        println("\n✓ Successfully received announce through tunnel!")

        // Check final tunnel state
        val finalTunnels = Transport.getTunnels()
        println("\nFinal Tunnel State:")
        for ((tunnelId, tunnelInfo) in finalTunnels) {
            val tunnelIdHex = tunnelId.bytes.joinToString("") { "%02x".format(it) }
            println("  Tunnel: ${tunnelIdHex.take(16)}...")
            println("    Paths stored: ${tunnelInfo.paths.size}")

            if (tunnelInfo.paths.isNotEmpty()) {
                println("    Stored announce paths:")
                tunnelInfo.paths.forEach { (destHash, pathEntry) ->
                    val destHex = destHash.bytes.joinToString("") { "%02x".format(it) }
                    println("      - $destHex: ${pathEntry.hops} hops")
                }
            }
        }

        println("\n✓ All tests passed!")
    } else {
        println("\n✗ Did not receive announce within timeout")
    }

    // Shutdown
    println("\nShutting down...")
    Reticulum.stop()
    println("Done!")
}
