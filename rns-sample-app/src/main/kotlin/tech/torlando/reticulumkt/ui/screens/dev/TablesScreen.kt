package tech.torlando.reticulumkt.ui.screens.dev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

data class PathEntry(
    val destinationHash: String,
    val nextHop: String,
    val interfaceName: String,
    val hops: Int,
    val expiresIn: String,
)

data class LinkEntry(
    val linkId: String,
    val status: String,
    val rtt: String,
    val txBytes: Long,
    val rxBytes: Long,
)

data class TunnelEntry(
    val tunnelId: String,
    val interfaceCount: Int,
    val pathCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    viewModel: ReticulumViewModel,
) {
    val serviceState by viewModel.serviceState.collectAsState()
    val context = LocalContext.current

    // Sample data - in real implementation, this would come from the service
    val paths = remember {
        if (serviceState.isRunning) listOf(
            PathEntry("a1b2c3d4e5f6....", "Interface0", "TCP:Amsterdam", 2, "5m 30s"),
            PathEntry("f6e5d4c3b2a1....", "Interface1", "Auto", 1, "10m 15s"),
        ) else emptyList()
    }

    val links = remember {
        if (serviceState.isRunning) listOf(
            LinkEntry("link_001", "Active", "45ms", 1024, 2048),
        ) else emptyList()
    }

    val tunnels = remember {
        emptyList<TunnelEntry>()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transport Tables") },
                actions = {
                    IconButton(onClick = { viewModel.refreshMonitor() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Path Table Section
            item {
                ExpandableTableSection(
                    title = "Path Table",
                    count = paths.size
                ) {
                    if (paths.isEmpty()) {
                        Text(
                            text = "No paths (service not running)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        paths.forEach { path ->
                            PathTableRow(path, context)
                        }
                    }
                }
            }

            // Link Table Section
            item {
                ExpandableTableSection(
                    title = "Link Table",
                    count = links.size
                ) {
                    if (links.isEmpty()) {
                        Text(
                            text = "No active links",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        links.forEach { link ->
                            LinkTableRow(link, context)
                        }
                    }
                }
            }

            // Tunnel Table Section
            item {
                ExpandableTableSection(
                    title = "Tunnel Table",
                    count = tunnels.size
                ) {
                    if (tunnels.isEmpty()) {
                        Text(
                            text = "No tunnels",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tunnels.forEach { tunnel ->
                            TunnelTableRow(tunnel, context)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ExpandableTableSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = " ($count)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            // Content
            if (expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

@Composable
private fun PathTableRow(path: PathEntry, context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = path.destinationHash,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
                IconButton(
                    onClick = { copyToClipboard(context, path.destinationHash, "Hash") },
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
            TableDataRow("Next Hop", path.nextHop)
            TableDataRow("Interface", path.interfaceName)
            TableDataRow("Hops", path.hops.toString())
            TableDataRow("Expires", path.expiresIn)
        }
    }
}

@Composable
private fun LinkTableRow(link: LinkEntry, context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = link.linkId,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
                IconButton(onClick = { copyToClipboard(context, link.linkId, "Link ID") }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                }
            }
            TableDataRow("Status", link.status)
            TableDataRow("RTT", link.rtt)
            TableDataRow("TX", "${link.txBytes} bytes")
            TableDataRow("RX", "${link.rxBytes} bytes")
        }
    }
}

@Composable
private fun TunnelTableRow(tunnel: TunnelEntry, context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tunnel.tunnelId,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
                IconButton(onClick = { copyToClipboard(context, tunnel.tunnelId, "Tunnel ID") }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                }
            }
            TableDataRow("Interfaces", tunnel.interfaceCount.toString())
            TableDataRow("Paths", tunnel.pathCount.toString())
        }
    }
}

@Composable
private fun TableDataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
