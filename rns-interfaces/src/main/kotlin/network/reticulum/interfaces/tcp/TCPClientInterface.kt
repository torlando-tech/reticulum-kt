package network.reticulum.interfaces.tcp

import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.KISS
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * TCP client interface for Reticulum.
 *
 * Connects to a remote TCP server and exchanges packets using
 * HDLC or KISS framing.
 */
class TCPClientInterface(
    name: String,
    private val targetHost: String,
    private val targetPort: Int,
    private val useKissFraming: Boolean = false,
    private val connectTimeoutMs: Int = INITIAL_CONNECT_TIMEOUT,
    private val maxReconnectAttempts: Int? = null
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 262144
        const val INITIAL_CONNECT_TIMEOUT = 5000 // 5 seconds
        const val RECONNECT_WAIT_MS = 5000L // 5 seconds
    }

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU

    private var socket: Socket? = null
    private val reconnecting = AtomicBoolean(false)
    private val neverConnected = AtomicBoolean(true)
    private val writing = AtomicBoolean(false)
    private var readThread: Thread? = null

    private val hdlcDeframer = HDLC.createDeframer { data ->
        processIncoming(data)
    }

    private val kissDeframer = KISS.createDeframer { _, data ->
        processIncoming(data)
    }

    override fun start() {
        thread(name = "TCPClient-$name-connect", isDaemon = true) {
            if (!connect(initial = true)) {
                reconnect()
            }
        }
    }

    private fun connect(initial: Boolean = false): Boolean {
        return try {
            if (initial) {
                log("Establishing TCP connection to $targetHost:$targetPort...")
            }

            val sock = Socket()
            sock.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 0 // Block on read

            // Get input stream immediately while we have the socket reference
            val inputStream = sock.getInputStream()

            socket = sock
            online.set(true)
            neverConnected.set(false)

            if (initial) {
                log("TCP connection established")
            }

            // Send an empty HDLC frame to "activate" the connection
            // This triggers Python RNS's read_loop recv() to return, which ensures
            // the handler thread is fully blocked and won't close the socket
            try {
                val keepaliveFrame = HDLC.frame(ByteArray(0))
                sock.getOutputStream().write(keepaliveFrame)
                sock.getOutputStream().flush()
            } catch (e: Exception) {
                // Keepalive failure is not critical
            }

            // Small delay for Python to process
            Thread.sleep(50)

            // Pass socket and stream directly to avoid race conditions
            startReadLoop(sock, inputStream)
            true
        } catch (e: Exception) {
            if (initial) {
                log("Initial connection failed: ${e.message}")
                log("Will retry in ${RECONNECT_WAIT_MS / 1000} seconds")
            }
            false
        }
    }

    private fun reconnect() {
        if (reconnecting.getAndSet(true)) return

        var attempts = 0
        while (!online.get() && !detached.get()) {
            Thread.sleep(RECONNECT_WAIT_MS)
            attempts++

            if (maxReconnectAttempts != null && attempts > maxReconnectAttempts) {
                log("Max reconnection attempts ($maxReconnectAttempts) reached")
                detach()
                break
            }

            try {
                if (connect()) {
                    if (!neverConnected.get()) {
                        log("Reconnected successfully")
                    }
                    break
                }
            } catch (e: Exception) {
                log("Reconnection attempt $attempts failed: ${e.message}")
            }
        }

        reconnecting.set(false)
    }

    private fun startReadLoop(sock: Socket, inputStream: InputStream) {
        readThread = thread(name = "TCPClient-$name-read", isDaemon = true) {
            val buffer = ByteArray(4096)

            try {
                while (online.get() && !detached.get()) {
                    if (sock.isClosed || !sock.isConnected || sock.isInputShutdown) {
                        break
                    }

                    val bytesRead = inputStream.read(buffer)

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        if (useKissFraming) {
                            kissDeframer.process(data)
                        } else {
                            hdlcDeframer.process(data)
                        }
                    } else if (bytesRead == -1) {
                        // Connection closed
                        online.set(false)
                        break
                    }
                }
            } catch (e: IOException) {
                if (!detached.get()) {
                    online.set(false)
                }
            }

            // Connection lost, try to reconnect
            if (!detached.get()) {
                log("Connection lost, attempting to reconnect...")
                thread(name = "TCPClient-$name-reconnect", isDaemon = true) {
                    reconnect()
                }
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        val sock = socket ?: throw IllegalStateException("Socket is null")

        // Wait for any pending write to complete
        while (writing.get()) {
            Thread.sleep(10)
        }

        try {
            writing.set(true)

            val framedData = if (useKissFraming) {
                KISS.frame(data)
            } else {
                HDLC.frame(data)
            }

            sock.getOutputStream().write(framedData)
            sock.getOutputStream().flush()

            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            log("Write error: ${e.message}")
            teardown()
            throw e
        } finally {
            writing.set(false)
        }
    }

    override fun detach() {
        super.detach()
        closeSocket()
    }

    private fun teardown() {
        online.set(false)
        closeSocket()

        if (!detached.get()) {
            thread(name = "TCPClient-$name-reconnect", isDaemon = true) {
                reconnect()
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String = "TCPClientInterface[$name -> $targetHost:$targetPort]"
}
