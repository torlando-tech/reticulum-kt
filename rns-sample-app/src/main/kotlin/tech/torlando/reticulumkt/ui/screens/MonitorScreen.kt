package tech.torlando.reticulumkt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.ui.theme.StatusConnected
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    viewModel: ReticulumViewModel,
) {
    val monitorState by viewModel.monitorState.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val interfaces by viewModel.interfaces.collectAsState()
    val interfaceStatuses by viewModel.interfaceStatuses.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitor") },
                actions = {
                    IconButton(onClick = { viewModel.refreshMonitor() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status
            val onlineCount = interfaceStatuses.values.count { it.isOnline }
            MonitorCard(
                title = "Service",
                icon = Icons.Filled.Router
            ) {
                StatusRow("Status", if (serviceState.isRunning) "Running" else "Stopped",
                    if (serviceState.isRunning) StatusConnected else null)
                StatusRow("Transport", if (serviceState.enableTransport) "Enabled" else "Disabled", null)
                StatusRow("Interfaces", "$onlineCount online / ${interfaces.size} configured",
                    if (onlineCount > 0) StatusConnected else null)
                StatusRow("Known Peers", "${serviceState.knownPeers}", null)
                StatusRow("Active Links", "${serviceState.activeLinks}", null)
                StatusRow("Known Paths", "${serviceState.knownPaths}", null)
            }

            // Network Status
            MonitorCard(
                title = "Network",
                icon = Icons.Filled.NetworkCheck
            ) {
                StatusRow("Availability", if (monitorState.networkAvailable) "Available" else "Unavailable",
                    if (monitorState.networkAvailable) StatusConnected else null)
                StatusRow("Connection", monitorState.connectionType, null)
                StatusRow("Metered", if (monitorState.isMetered) "Yes" else "No", null)
            }

            // WiFi Info
            MonitorCard(
                title = "WiFi",
                icon = Icons.Filled.SignalWifi4Bar
            ) {
                StatusRow("SSID", monitorState.wifiSsid, null)
                StatusRow("Signal Strength", monitorState.wifiSignalStrength, null)
                StatusRow("Link Speed", monitorState.wifiLinkSpeed, null)
            }

            // Cellular Info
            MonitorCard(
                title = "Cellular",
                icon = Icons.Filled.SignalCellular4Bar
            ) {
                StatusRow("Status", monitorState.cellularStatus, null)
                StatusRow("Network Type", monitorState.cellularType, null)
                StatusRow("Signal", monitorState.cellularSignal, null)
            }

            // Battery Status
            MonitorCard(
                title = "Battery",
                icon = Icons.Filled.Battery4Bar
            ) {
                StatusRow("Level", "${monitorState.batteryLevel}%", null)
                StatusRow("Status", monitorState.batteryStatus, null)
                StatusRow("Power Save", if (monitorState.powerSaveMode) "On" else "Off", null)
            }

            // Doze Status
            MonitorCard(
                title = "Doze Mode",
                icon = Icons.Filled.BatteryChargingFull
            ) {
                StatusRow("In Doze", if (monitorState.inDoze) "Yes" else "No",
                    if (!monitorState.inDoze) StatusConnected else null)
                StatusRow("Battery Exempt", if (monitorState.batteryExempt) "Yes" else "No",
                    if (monitorState.batteryExempt) StatusConnected else null)
            }

            // Memory Stats
            MonitorCard(
                title = "Memory",
                icon = Icons.Filled.Memory
            ) {
                StatusRow("Heap Used", "${monitorState.heapUsedMb} MB", null)
                StatusRow("Heap Max", "${monitorState.heapMaxMb} MB", null)
                StatusRow("ByteArray Pool", "${String.format("%.1f", monitorState.byteArrayPoolMb)} MB", null)
            }
        }
    }
}

@Composable
private fun MonitorCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    statusColor: Color?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
