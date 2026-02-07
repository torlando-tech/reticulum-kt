package tech.torlando.reticulumkt.ui.screens.wizard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.util.UUID

private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private const val SCAN_DURATION_MS = 10_000L

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val isPaired: Boolean = false,
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BleDevicePicker(
    selectedAddress: String,
    onDeviceSelected: (name: String, address: String) -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blePermissions = rememberBluetoothPermissions()

    Column(modifier = modifier) {
        if (!blePermissions.allPermissionsGranted) {
            PermissionGate(blePermissions)
        } else {
            DeviceScanner(
                selectedAddress = selectedAddress,
                onDeviceSelected = onDeviceSelected,
                onManualEntry = onManualEntry,
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun rememberBluetoothPermissions(): MultiplePermissionsState {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        listOf(
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
    return rememberMultiplePermissionsState(permissions)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(permissionsState: MultiplePermissionsState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Bluetooth Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = "To scan for nearby RNode devices, this app needs Bluetooth permissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = { permissionsState.launchMultiplePermissionRequest() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant Permissions")
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceScanner(
    selectedAddress: String,
    onDeviceSelected: (name: String, address: String) -> Unit,
    onManualEntry: () -> Unit,
) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val bluetoothAdapter: BluetoothAdapter? = remember { bluetoothManager.adapter }

    val devices = remember { mutableStateListOf<DiscoveredDevice>() }
    var isScanning by remember { mutableStateOf(false) }
    var scanTrigger by remember { mutableStateOf(0) }

    // Collect bonded devices and start scan
    DisposableEffect(scanTrigger) {
        val adapter = bluetoothAdapter ?: return@DisposableEffect onDispose { }
        val scanner = adapter.bluetoothLeScanner ?: return@DisposableEffect onDispose { }

        devices.clear()

        // Add bonded devices whose name starts with "RNode"
        try {
            adapter.bondedDevices
                ?.filter { it.name?.startsWith("RNode", ignoreCase = true) == true }
                ?.forEach { device ->
                    devices.add(
                        DiscoveredDevice(
                            name = device.name ?: "Unknown",
                            address = device.address,
                            isPaired = true,
                        )
                    )
                }
        } catch (_: SecurityException) {
            // Missing BLUETOOTH_CONNECT — bonded list unavailable
        }

        isScanning = true

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown RNode"
                val address = device.address

                // Update existing or add new
                val existingIndex = devices.indexOfFirst { it.address == address }
                if (existingIndex >= 0) {
                    // Update RSSI for bonded devices, or refresh discovered ones
                    devices[existingIndex] = devices[existingIndex].copy(rssi = result.rssi)
                } else {
                    devices.add(
                        DiscoveredDevice(
                            name = name,
                            address = address,
                            rssi = result.rssi,
                            isPaired = false,
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (_: SecurityException) {
            isScanning = false
        }

        onDispose {
            isScanning = false
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Already cleaned up or permission revoked
            }
        }
    }

    // Auto-stop after scan duration
    LaunchedEffect(scanTrigger) {
        delay(SCAN_DURATION_MS)
        isScanning = false
    }

    // Sort: paired first, then by RSSI descending
    val sortedDevices = remember(devices.toList()) {
        devices.sortedWith(
            compareByDescending<DiscoveredDevice> { it.isPaired }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "Scanning for RNode devices...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sortedDevices.isEmpty() && !isScanning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "No RNode devices found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Make sure your RNode is powered on and in range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Using LazyColumn with a fixed height so the outer scroll doesn't conflict
        if (sortedDevices.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((sortedDevices.size.coerceAtMost(5) * 80).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortedDevices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        isSelected = device.address == selectedAddress,
                        onClick = { onDeviceSelected(device.name, device.address) },
                    )
                }
            }
        }

        if (!isScanning) {
            TextButton(
                onClick = { scanTrigger++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Again")
            }
        }

        // Manual entry fallback
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManualEntry),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enter address manually",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Type a Bluetooth MAC address directly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (device.isPaired) {
                        Text(
                            text = "Paired",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    if (device.rssi != null) {
                        Text(
                            text = "${device.rssi} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
