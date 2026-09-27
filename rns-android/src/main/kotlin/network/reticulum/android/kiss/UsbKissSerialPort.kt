package network.reticulum.android.kiss

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import network.reticulum.interfaces.kiss.KissSerialPort

/**
 * USB-serial (CDC / FTDI / CP210x / CH34x) backend for a KISS TNC — the platform
 * side of parity items 3/5. Implements the [KissSerialPort] seam over Android's
 * [UsbManager] using the usb-serial-for-android chip drivers, so [KissInterface]
 * can drive a USB-attached KISS TNC (e.g. a TNC4).
 *
 * Per-chip support comes from the library's driver probe table (FTDI 0x0403,
 * Silicon Labs CP210x 0x10C4, WCH CH34x 0x1A86, and generic CDC-ACM). One
 * override is added to match the Python fork's note that some CH34x TNCs (the
 * TNC4, VID 0x1A86 / PID 0x55D4) enumerate as CDC-ACM rather than the vendor
 * CH34x protocol.
 *
 * The app must have been granted USB permission for the device before [open]
 * (requested via `UsbManager.requestPermission` at the app layer); [open] fails
 * cleanly if permission is missing. Android USB isn't JVM-unit-testable, so this
 * is verified by compiling `rns-android` and, ultimately, on a device.
 */
class UsbKissSerialPort(
    private val usbManager: UsbManager,
    private val baudRate: Int = DEFAULT_BAUD,
    private val deviceFilter: (UsbDevice) -> Boolean = { true },
) : KissSerialPort {

    companion object {
        private const val TAG = "UsbKissSerialPort"
        const val DEFAULT_BAUD = 115_200
        private const val READ_BUF = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 2_000

        // TNC4 and similar CH34x devices that speak CDC-ACM, not the CH34x protocol.
        private const val CH34X_VENDOR = 0x1A86
        private const val TNC4_PRODUCT = 0x55D4
    }

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var openFlag = false

    override val isOpen: Boolean
        get() = openFlag && port != null

    private val prober: UsbSerialProber by lazy {
        val table = UsbSerialProber.getDefaultProbeTable()
        table.addProduct(CH34X_VENDOR, TNC4_PRODUCT, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

    override suspend fun open(): Boolean {
        val driver = usbManager.deviceList.values.asSequence()
            .filter(deviceFilter)
            .mapNotNull { prober.probeDevice(it) }
            .firstOrNull { usbManager.hasPermission(it.device) }
            ?: run { Log.w(TAG, "No permitted, probeable USB serial device found"); return false }

        val serialPort = driver.ports.firstOrNull()
            ?: run { Log.w(TAG, "USB serial driver has no ports"); return false }
        val conn = usbManager.openDevice(driver.device)
            ?: run { Log.w(TAG, "openDevice returned null (permission?)"); return false }

        return try {
            serialPort.open(conn)
            serialPort.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            port = serialPort
            connection = conn
            openFlag = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open USB serial port", e)
            try { conn.close() } catch (_: Exception) {}
            false
        }
    }

    override fun read(): ByteArray {
        val p = port ?: return ByteArray(0)
        return try {
            val buf = ByteArray(READ_BUF)
            val n = p.read(buf, READ_TIMEOUT_MS)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } catch (e: Exception) {
            // Fires at most once per session: clearing openFlag ends KissInterface's
            // `while (port.isOpen)` read loop, which returns normally — so the session
            // tears down and reconnects with no record anywhere of why the TNC dropped.
            // The open path logs its failures; this is the same fault, mid-session.
            openFlag = false
            Log.w(TAG, "USB serial read failed; marking port closed", e)
            ByteArray(0)
        }
    }

    override fun write(bytes: ByteArray): Int {
        val p = port ?: return -1
        return try {
            p.write(bytes, WRITE_TIMEOUT_MS)
            bytes.size
        } catch (e: Exception) {
            openFlag = false
            -1
        }
    }

    override fun close() {
        openFlag = false
        try { port?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        port = null
        connection = null
    }
}
