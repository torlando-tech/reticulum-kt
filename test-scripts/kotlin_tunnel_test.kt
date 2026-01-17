/**
 * Kotlin Tunnel Test - Connects to Python tunnel test server
 *
 * To run:
 * 1. Start Python server: python3 test-scripts/python_tunnel_test.py
 * 2. Run this test: ./gradlew :rns-test:test --tests "*TunnelTest*"
 */

import network.reticulum.identity.Identity
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.transport.Transport

fun main() {
    println("=" .repeat(60))
    println("Kotlin Tunnel Test Client")
    println("=" .repeat(60))

    // Start transport with transport enabled (needed for tunnels)
    println("Starting Transport...")
    Transport.start(enableTransport = true)

    // Create TCP client to Python server
    val client = TCPClientInterface(
        name = "test-tcp-client",
        targetHost = "127.0.0.1",
        targetPort = 4244  // Match Python test server
    )

    // Convert to InterfaceRef and register
    val clientRef = client.toRef()
    Transport.registerInterface(clientRef)

    // Start the client
    println("Connecting to Python server at 127.0.0.1:4244...")
    client.start()

    // Wait for connection
    Thread.sleep(1000)

    if (client.online.get()) {
        println("Connected! Waiting for tunnel synthesis...")

        // Check wantsTunnel flag
        println("Interface wantsTunnel: ${clientRef.wantsTunnel}")

        // Wait for tunnel synthesis
        Thread.sleep(5000)

        // Check tunnel status
        val tunnels = Transport.getTunnels()
        println("Active tunnels: ${tunnels.size}")

        for ((tunnelId, tunnel) in tunnels) {
            println("  Tunnel: ${tunnelId.toString().take(16)}")
            println("    Interface: ${tunnel.interface_?.name}")
            println("    Paths: ${tunnel.paths.size}")
        }

        // Keep running to receive announces
        println("\nWaiting for announces (Ctrl+C to exit)...")

        while (true) {
            Thread.sleep(5000)

            println("Status:")
            println("  Online: ${client.online.get()}")
            println("  WantsTunnel: ${clientRef.wantsTunnel}")
            println("  TunnelId: ${clientRef.tunnelId?.let { it.take(8).joinToString("") { "%02x".format(it) } }}")
            println("  Active tunnels: ${Transport.getTunnels().size}")
            println("  Path table size: ${Transport.pathTable.size}")
        }
    } else {
        println("Failed to connect to Python server")
    }

    client.detach()
    Transport.stop()
}
