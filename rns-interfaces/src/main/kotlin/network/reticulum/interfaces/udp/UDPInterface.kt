package network.reticulum.interfaces.udp

import network.reticulum.interfaces.Interface
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * UDP interface for Reticulum.
 *
 * Provides UDP datagram communication with support for:
 * - Unicast communication
 * - Broadcast mode
 * - Multicast support
 *
 * Unlike TCP, UDP is message-oriented so no framing is needed - each
 * datagram corresponds to one packet.
 *
 * @param name Human-readable name for this interface
 * @param bindIp Local IP address to bind to (null for wildcard)
 * @param bindPort Local port to bind to
 * @param forwardIp Target IP address for outgoing packets (can be broadcast or multicast)
 * @param forwardPort Target port for outgoing packets
 * @param broadcast Enable broadcast mode (sets SO_BROADCAST)
 * @param multicast Enable multicast mode (joins multicast group)
 * @param multicastTtl Multicast TTL hop count (default 1 for local subnet)
 */
class UDPInterface(
    name: String,
    private val bindIp: String? = null,
    private val bindPort: Int,
    private val forwardIp: String? = null,
    private val forwardPort: Int,
    private val broadcast: Boolean = false,
    private val multicast: Boolean = false,
    private val multicastTtl: Int = 1
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 1500 // Standard Ethernet MTU
        const val DEFAULT_BUFFER_SIZE = 4096

        /**
         * Check if an IP address is a multicast address.
         */
        fun isMulticast(ip: String): Boolean {
            return try {
                val addr = InetAddress.getByName(ip)
                addr.isMulticastAddress
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Check if an IP address is a broadcast address.
         */
        fun isBroadcast(ip: String): Boolean {
            // Broadcast addresses end in .255 or are 255.255.255.255
            return ip.endsWith(".255") || ip == "255.255.255.255"
        }
    }

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU
    override val canReceive: Boolean = bindPort > 0
    override val canSend: Boolean = forwardIp != null && forwardPort > 0

    private var receiveChannel: DatagramChannel? = null
    private var sendSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var readThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun start() {
        if (running.getAndSet(true)) {
            log("Already started")
            return
        }

        try {
            // Set up receiving channel if needed
            if (canReceive) {
                setupReceiver()
            }

            // Set up sending socket if needed
            if (canSend) {
                setupSender()
            }

            online.set(true)
            log("Interface started successfully")

        } catch (e: Exception) {
            log("Failed to start: ${e.message}")
            running.set(false)
            cleanup()
            throw e
        }
    }

    private fun setupReceiver() {
        if (multicast && forwardIp != null && isMulticast(forwardIp)) {
            // Use MulticastSocket for multicast
            setupMulticastReceiver()
        } else {
            // Use DatagramChannel for standard UDP
            setupStandardReceiver()
        }
    }

    private fun setupStandardReceiver() {
        val channel = DatagramChannel.open()
        channel.configureBlocking(true)

        // Bind to address
        val bindAddress = if (bindIp != null) {
            InetSocketAddress(bindIp, bindPort)
        } else {
            InetSocketAddress(bindPort)
        }
        channel.bind(bindAddress)

        // Enable broadcast if needed
        if (broadcast) {
            channel.socket().broadcast = true
        }

        receiveChannel = channel
        log("Bound to ${bindAddress}")

        // Start read thread
        startReadLoop()
    }

    private fun setupMulticastReceiver() {
        val mSocket = MulticastSocket(bindPort)
        mSocket.timeToLive = multicastTtl
        mSocket.soTimeout = 0 // Block indefinitely

        // Join multicast group
        if (forwardIp != null) {
            val group = InetAddress.getByName(forwardIp)

            // Use NetworkInterface if bindIp is specified
            if (bindIp != null) {
                val networkInterface = NetworkInterface.getByInetAddress(
                    InetAddress.getByName(bindIp)
                )
                if (networkInterface != null) {
                    mSocket.joinGroup(
                        InetSocketAddress(group, bindPort),
                        networkInterface
                    )
                    log("Joined multicast group $forwardIp on interface $bindIp")
                } else {
                    mSocket.joinGroup(group)
                    log("Joined multicast group $forwardIp")
                }
            } else {
                mSocket.joinGroup(group)
                log("Joined multicast group $forwardIp")
            }
        }

        multicastSocket = mSocket
        log("Multicast socket bound to port $bindPort")

        // Start read thread
        startMulticastReadLoop()
    }

    private fun setupSender() {
        if (multicast && forwardIp != null && isMulticast(forwardIp)) {
            // Sending will use the multicast socket
            if (multicastSocket != null) {
                log("Using multicast socket for sending")
            } else {
                // Create a multicast socket just for sending
                val mSocket = MulticastSocket()
                mSocket.timeToLive = multicastTtl
                multicastSocket = mSocket
                log("Created multicast socket for sending")
            }
        } else {
            // Create standard datagram socket for sending
            val socket = DatagramSocket()
            if (broadcast) {
                socket.broadcast = true
            }
            sendSocket = socket
            log("Created datagram socket for sending")
        }
    }

    private fun startReadLoop() {
        readThread = thread(name = "UDP-$name-read", isDaemon = true) {
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)

            try {
                while (running.get() && !detached.get()) {
                    buffer.clear()
                    val channel = receiveChannel ?: break

                    val sourceAddress = channel.receive(buffer)
                    if (sourceAddress != null) {
                        buffer.flip()
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)

                        // Process the received datagram
                        processIncoming(data)
                    }
                }
            } catch (e: IOException) {
                if (running.get() && !detached.get()) {
                    log("Read error: ${e.message}")
                }
            }

            log("Read loop stopped")
        }
    }

    private fun startMulticastReadLoop() {
        readThread = thread(name = "UDP-$name-multicast-read", isDaemon = true) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            try {
                while (running.get() && !detached.get()) {
                    val mSocket = multicastSocket ?: break

                    mSocket.receive(packet)
                    val data = packet.data.copyOf(packet.length)

                    // Process the received datagram
                    processIncoming(data)
                }
            } catch (e: IOException) {
                if (running.get() && !detached.get()) {
                    log("Multicast read error: ${e.message}")
                }
            }

            log("Multicast read loop stopped")
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        if (!canSend || forwardIp == null) {
            throw IllegalStateException("Interface is not configured for sending")
        }

        try {
            val targetAddress = InetSocketAddress(forwardIp, forwardPort)

            if (multicast && isMulticast(forwardIp)) {
                // Send via multicast socket
                val mSocket = multicastSocket ?: throw IllegalStateException("Multicast socket not initialized")
                val packet = DatagramPacket(data, data.size, targetAddress)
                mSocket.send(packet)
            } else {
                // Send via standard socket
                val socket = sendSocket ?: throw IllegalStateException("Send socket not initialized")
                val packet = DatagramPacket(data, data.size, targetAddress)
                socket.send(packet)
            }

            txBytes.addAndGet(data.size.toLong())
            parentInterface?.txBytes?.addAndGet(data.size.toLong())

        } catch (e: IOException) {
            log("Send error: ${e.message}")
            throw e
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return

        log("Detaching interface")
        running.set(false)
        online.set(false)

        cleanup()
    }

    private fun cleanup() {
        // Close receive channel
        try {
            receiveChannel?.close()
        } catch (e: Exception) {
            // Ignore
        }
        receiveChannel = null

        // Leave multicast group and close socket
        try {
            if (multicast && forwardIp != null && isMulticast(forwardIp)) {
                val group = InetAddress.getByName(forwardIp)
                multicastSocket?.leaveGroup(group)
            }
            multicastSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        multicastSocket = null

        // Close send socket
        try {
            sendSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        sendSocket = null

        // Wait for read thread to finish
        readThread?.join(1000)
        readThread = null
    }

    private fun log(message: String) {
        println("[$name] $message")
    }

    override fun toString(): String {
        val bindStr = if (bindIp != null) "$bindIp:$bindPort" else "*:$bindPort"
        val forwardStr = if (forwardIp != null) "$forwardIp:$forwardPort" else "none"
        val mode = when {
            multicast -> "multicast"
            broadcast -> "broadcast"
            else -> "unicast"
        }
        return "UDPInterface[$name, bind=$bindStr, forward=$forwardStr, mode=$mode]"
    }
}
