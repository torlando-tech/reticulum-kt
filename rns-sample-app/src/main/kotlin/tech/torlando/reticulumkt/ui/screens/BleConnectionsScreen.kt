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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.ui.theme.StatusConnected
import tech.torlando.reticulumkt.ui.theme.StatusOffline
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel
import tech.torlando.reticulumkt.viewmodel.SpawnedPeerInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectionsScreen(
    viewModel: ReticulumViewModel,
    configId: String,
    onNavigateBack: () -> Unit,
) {
    val allSpawned by viewModel.spawnedPeersByConfig.collectAsState()
    val peers = allSpawned[configId] ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Connections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Summary card
            item {
                SummaryCard(peers = peers)
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No active BLE connections",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = "Active Connections",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                items(peers, key = { it.name }) { peer ->
                    ConnectionCard(peer = peer)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(peers: List<SpawnedPeerInfo>) {
    val onlineCount = peers.count { it.isOnline }
    val totalRx = peers.sumOf { it.rxBytes }
    val totalTx = peers.sumOf { it.txBytes }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Connection Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem(label = "Total", value = "${peers.size}")
                SummaryItem(label = "Online", value = "$onlineCount")
                SummaryItem(label = "RX", value = formatBytes(totalRx))
                SummaryItem(label = "TX", value = formatBytes(totalTx))
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ConnectionCard(peer: SpawnedPeerInfo) {
    val signalQuality = getSignalQuality(peer.discoveryRssi ?: -100)

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
            // Header row: identity + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.BluetoothConnected,
                    contentDescription = null,
                    tint = if (peer.isOnline) StatusConnected else StatusOffline,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.peerIdentityHex?.let {
                            if (it.length > 16) "${it.take(8)}...${it.takeLast(8)}" else it
                        } ?: peer.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Text(
                    text = if (peer.isOnline) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (peer.isOnline) StatusConnected else StatusOffline
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Detail rows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Signal quality
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = signalQuality.icon,
                        contentDescription = null,
                        tint = signalQuality.color(),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${signalQuality.label} (${peer.discoveryRssi ?: "?"} dBm)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // MTU
                peer.peerMtu?.let { mtu ->
                    Text(
                        text = "MTU: $mtu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // MAC address (last 8 chars)
                peer.peerAddress?.let { addr ->
                    Text(
                        text = "MAC: ...${addr.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Connection duration
                peer.connectedSince?.let { since ->
                    Text(
                        text = formatDuration(System.currentTimeMillis() - since),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // RX/TX row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = "RX: ${formatBytes(peer.rxBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "TX: ${formatBytes(peer.txBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

private data class SignalQuality(
    val label: String,
    val icon: ImageVector,
    val colorName: String,
) {
    @Composable
    fun color() = when (colorName) {
        "connected" -> StatusConnected
        "warning" -> MaterialTheme.colorScheme.tertiary
        "error" -> StatusOffline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun getSignalQuality(rssi: Int): SignalQuality {
    return when {
        rssi > -50 -> SignalQuality("Excellent", Icons.Filled.SignalCellular4Bar, "connected")
        rssi > -70 -> SignalQuality("Good", Icons.Filled.SignalCellularAlt, "connected")
        rssi > -85 -> SignalQuality("Fair", Icons.Filled.SignalCellularAlt2Bar, "warning")
        else -> SignalQuality("Poor", Icons.Filled.SignalCellularAlt1Bar, "error")
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
