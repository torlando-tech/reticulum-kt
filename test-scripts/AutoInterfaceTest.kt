import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.auto.AutoInterfaceConstants

fun main() {
    println("=== AutoInterface Test ===")
    println("Default discovery port: ${AutoInterfaceConstants.DEFAULT_DISCOVERY_PORT}")
    println("Default data port: ${AutoInterfaceConstants.DEFAULT_DATA_PORT}")
    println("Default group ID: ${String(AutoInterfaceConstants.DEFAULT_GROUP_ID)}")
    println()

    val autoInterface = AutoInterface(
        name = "TestAutoInterface"
    )

    println("Starting AutoInterface...")
    autoInterface.start()

    println("Waiting for peer discovery...")
    println("Press Ctrl+C to stop")
    println()

    var lastPeerCount = -1
    while (true) {
        val peerCount = autoInterface.peerCount()
        if (peerCount != lastPeerCount) {
            println("Peer count: $peerCount")
            lastPeerCount = peerCount
        }
        Thread.sleep(1000)
    }
}
