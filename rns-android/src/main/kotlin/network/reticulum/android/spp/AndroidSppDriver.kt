package network.reticulum.android.spp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.spp.SppConnection
import network.reticulum.interfaces.spp.SppDevice
import network.reticulum.interfaces.spp.SppDriver
import network.reticulum.interfaces.spp.SppInterface
import java.util.UUID

/**
 * Android implementation of [SppDriver] using Bluetooth Classic RFCOMM sockets.
 *
 * SPP (Serial Port Profile) provides a simple stream socket over Bluetooth Classic.
 * Unlike BLE, there's no GATT, no fragmentation, no MTU negotiation — just a plain
 * InputStream/OutputStream backed by an RFCOMM channel.
 *
 * Requirements:
 * - Device must have Bluetooth Classic hardware (virtually all Android phones)
 * - BLUETOOTH_CONNECT runtime permission must be granted before use
 * - Remote device must be paired (bonded) via Android Settings before connecting
 *
 * @param context Application context for accessing BluetoothManager
 */
class AndroidSppDriver(
    private val context: Context,
) : SppDriver {

    companion object {
        private const val TAG = "AndroidSppDriver"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var isShutdown = false

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String, secure: Boolean): SppConnection {
        check(!isShutdown) { "Driver has been shut down" }

        return withContext(Dispatchers.IO) {
            val mode = if (secure) "secure" else "insecure"
            Log.d(TAG, "Connecting to SPP device $address ($mode)...")
            val device = bluetoothAdapter.getRemoteDevice(address)
            val socket = if (secure) {
                device.createRfcommSocketToServiceRecord(SppInterface.SPP_UUID)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(SppInterface.SPP_UUID)
            }

            // connect() is blocking — can take 1-12 seconds
            socket.connect()
            Log.d(TAG, "SPP connection established with ${device.name ?: address} ($mode)")

            SppConnection(
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                remoteAddress = address,
                remoteName = device.name,
                close = { closeSocket(socket) },
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun accept(serviceName: String, uuid: UUID, secure: Boolean): SppConnection {
        check(!isShutdown) { "Driver has been shut down" }

        return withContext(Dispatchers.IO) {
            val mode = if (secure) "secure" else "insecure"
            Log.d(TAG, "Listening for incoming SPP connection (service=$serviceName, $mode)...")
            val server = if (secure) {
                bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
            } else {
                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
            }
            serverSocket = server

            try {
                // accept() blocks until a client connects or the server socket is closed
                val socket = server.accept()
                Log.d(TAG, "Accepted SPP connection from ${socket.remoteDevice.name ?: socket.remoteDevice.address}")

                SppConnection(
                    inputStream = socket.inputStream,
                    outputStream = socket.outputStream,
                    remoteAddress = socket.remoteDevice.address,
                    remoteName = socket.remoteDevice.name,
                    close = { closeSocket(socket) },
                )
            } finally {
                // Close the server socket — we only accept one connection at a time.
                // SppInterface will call accept() again in the read loop's reconnect
                // path if it needs to accept another connection.
                closeServerSocket(server)
            }
        }
    }

    override fun cancelAccept() {
        val server = serverSocket
        serverSocket = null
        closeServerSocket(server)
    }

    @SuppressLint("MissingPermission")
    override fun listPairedDevices(): List<SppDevice> {
        return bluetoothAdapter.bondedDevices.map { device ->
            SppDevice(
                address = device.address,
                name = device.name,
                bonded = true,
            )
        }
    }

    override fun shutdown() {
        isShutdown = true
        cancelAccept()
        Log.d(TAG, "AndroidSppDriver shut down")
    }

    private fun closeSocket(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing BluetoothSocket: ${e.message}")
        }
    }

    private fun closeServerSocket(server: BluetoothServerSocket?) {
        try {
            server?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
    }
}
