package tech.torlando.reticulumkt.ui.screens.dev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: ReticulumViewModel,
) {
    val context = LocalContext.current
    val monitorState by viewModel.monitorState.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val interfaces by viewModel.interfaces.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = {
                        val text = viewModel.getDiagnosticsText()
                        copyToClipboard(context, text)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy All")
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
            // System Info Section
            DiagnosticsSection(
                title = "System Info",
                icon = Icons.Filled.Android
            ) {
                DiagnosticsRow("Android Version", Build.VERSION.RELEASE)
                DiagnosticsRow("API Level", Build.VERSION.SDK_INT.toString())
                DiagnosticsRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagnosticsRow("App Version", "1.0.0")
                DiagnosticsRow("Build Type", "Debug")
            }

            // Memory Section
            DiagnosticsSection(
                title = "Memory",
                icon = Icons.Filled.Memory
            ) {
                DiagnosticsRow("Heap Used", "${monitorState.heapUsedMb} MB")
                DiagnosticsRow("Heap Max", "${monitorState.heapMaxMb} MB")
                DiagnosticsRow("ByteArray Pool", "${String.format("%.1f", monitorState.byteArrayPoolMb)} MB")

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.forceGc() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Force Garbage Collection")
                }
            }

            // Transport Stats Section
            DiagnosticsSection(
                title = "Service Stats",
                icon = Icons.Filled.Router
            ) {
                DiagnosticsRow("Status", if (serviceState.isRunning) "Running" else "Stopped")
                DiagnosticsRow("Transport", if (serviceState.enableTransport) "Enabled" else "Disabled")
                DiagnosticsRow("Configured Interfaces", "${interfaces.size}")
                DiagnosticsRow("Active Interfaces", "${serviceState.activeInterfaces}")
                DiagnosticsRow("Known Peers", "${serviceState.knownPeers}")
                DiagnosticsRow("Active Links", "${serviceState.activeLinks}")
                DiagnosticsRow("Known Paths", "${serviceState.knownPaths}")
            }

            // Interface Stats Section
            if (interfaces.isNotEmpty()) {
                DiagnosticsSection(
                    title = "Interface Stats",
                    icon = Icons.Filled.Router
                ) {
                    interfaces.forEach { iface ->
                        Text(
                            text = iface.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        DiagnosticsRow("Type", iface.type)
                        DiagnosticsRow("Enabled", if (iface.enabled) "Yes" else "No")
                        when (iface.type) {
                            "TCP_CLIENT" -> {
                                DiagnosticsRow("Host", iface.host ?: "N/A")
                                DiagnosticsRow("Port", iface.port?.toString() ?: "N/A")
                            }
                            "UDP" -> {
                                DiagnosticsRow("Bind Port", iface.port?.toString() ?: "N/A")
                            }
                            "AUTO" -> {
                                DiagnosticsRow("Group ID", iface.groupId ?: "default")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Actions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Button(
                        onClick = {
                            val text = viewModel.getDiagnosticsText()
                            copyToClipboard(context, text)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Diagnostics to Clipboard")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

@Composable
private fun DiagnosticsSection(
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
                Spacer(modifier = Modifier.width(12.dp))
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
private fun DiagnosticsRow(label: String, value: String) {
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
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
