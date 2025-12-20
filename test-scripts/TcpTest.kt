import java.net.Socket
import java.net.InetSocketAddress

fun main() {
    println("Connecting to 127.0.0.1:14242...")
    val sock = Socket()
    sock.connect(InetSocketAddress("127.0.0.1", 14242), 5000)
    println("Connected: ${sock.isConnected}")
    println("Closed: ${sock.isClosed}")
    println("InputShutdown: ${sock.isInputShutdown}")

    sock.tcpNoDelay = true
    sock.keepAlive = true
    sock.soTimeout = 5000  // 5 second read timeout

    val inputStream = sock.getInputStream()
    println("Input stream: $inputStream")
    println("Available: ${inputStream.available()}")

    println("Calling read() with 5s timeout...")
    try {
        val b = inputStream.read()
        println("Read returned: $b")
    } catch (e: Exception) {
        println("Read exception: ${e.javaClass.name}: ${e.message}")
    }

    println("After read - Connected: ${sock.isConnected}")
    println("After read - Closed: ${sock.isClosed}")
    println("After read - InputShutdown: ${sock.isInputShutdown}")

    sock.close()
    println("Done")
}
