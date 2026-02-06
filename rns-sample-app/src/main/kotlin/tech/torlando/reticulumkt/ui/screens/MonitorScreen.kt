package tech.torlando.reticulumkt.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import network.reticulum.android.BatteryStatsTracker
import tech.torlando.reticulumkt.ui.theme.StatusConnected
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MonitorScreen(
    viewModel: ReticulumViewModel,
) {
    val monitorState by viewModel.monitorState.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val interfaces by viewModel.interfaces.collectAsState()
    val interfaceStatuses by viewModel.interfaceStatuses.collectAsState()
    val batteryStats by viewModel.batteryStats.collectAsState()
    val serviceEvents by viewModel.serviceEvents.collectAsState()
    val showExemptionSheet by viewModel.showExemptionSheet.collectAsState()
    val context = LocalContext.current

    // Request location permission for WiFi SSID access
    val locationPermissionState = rememberPermissionState(
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    LaunchedEffect(Unit) {
        if (!locationPermissionState.status.isGranted) {
            locationPermissionState.launchPermissionRequest()
        }
        // Refresh monitor data when screen loads
        viewModel.refreshMonitor()
    }

    // Battery Exemption Bottom Sheet
    if (showExemptionSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissExemptionSheet() },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Battery Optimization",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Battery optimization restricts background processes. Exempt this app for persistent mesh connectivity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Impact bullet points
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    ImpactBullet("Dropped connections during Doze mode")
                    ImpactBullet("Missed messages when screen is off")
                    ImpactBullet("Delayed routing for mesh traffic")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Button(
                    onClick = {
                        val intent = viewModel.requestBatteryExemption()
                        context.startActivity(intent)
                        viewModel.dismissExemptionSheet()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exempt from Battery Optimization")
                }

                TextButton(
                    onClick = { viewModel.dismissExemptionSheet() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Not Now")
                }
            }
        }
    }

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

            // Enhanced Battery Status with drain rate and chart
            MonitorCard(
                title = "Battery",
                icon = Icons.Filled.Battery4Bar
            ) {
                StatusRow("Level", "${monitorState.batteryLevel}%", null)
                StatusRow("Status", monitorState.batteryStatus, null)
                StatusRow(
                    "Drain Rate",
                    if (batteryStats.isCharging) "Charging"
                    else "${String.format("%.1f", batteryStats.drainRatePerHour)}%/hr",
                    null
                )
                StatusRow(
                    "Session Drain",
                    "${String.format("%.1f", batteryStats.sessionDrainRate)}%/hr",
                    null
                )
                StatusRow("Power Save", if (monitorState.powerSaveMode) "On" else "Off", null)

                Spacer(modifier = Modifier.height(8.dp))

                // Battery History Chart
                BatteryHistoryChart(
                    samples = batteryStats.samples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }

            // Optimization Impact Warning (between Battery and Doze cards)
            if (serviceEvents.hasActiveWarning && !monitorState.batteryExempt) {
                OptimizationWarningCard(
                    killCount = serviceEvents.killCountToday,
                    onFixClick = { viewModel.showExemptionSheet() },
                    onDismissClick = { viewModel.dismissOptimizationWarning() }
                )
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

            // Bottom padding for navigation bar
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun BatteryHistoryChart(
    samples: List<BatteryStatsTracker.BatterySample>,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) {
        Text(
            text = "Collecting data...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val fillColor = primaryColor.copy(alpha = 0.1f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val padding = 4.dp.toPx()

        val chartWidth = canvasWidth - padding * 2
        val chartHeight = canvasHeight - padding * 2

        // Draw horizontal gridlines at 25%, 50%, 75%
        for (pct in listOf(25, 50, 75)) {
            val y = padding + chartHeight * (1f - pct / 100f)
            drawLine(
                color = gridColor,
                start = Offset(padding, y),
                end = Offset(padding + chartWidth, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Determine time range: last 4 hours or session, whichever is shorter
        val latestTime = samples.last().timestamp
        val fourHoursMs = 4L * 60 * 60 * 1000
        val earliestTime = maxOf(samples.first().timestamp, latestTime - fourHoursMs)
        val timeRange = (latestTime - earliestTime).toFloat().coerceAtLeast(1f)

        // Filter samples within time range
        val visibleSamples = samples.filter { it.timestamp >= earliestTime }
        if (visibleSamples.size < 2) return@Canvas

        // Build line path
        val linePath = Path()
        val fillPath = Path()

        visibleSamples.forEachIndexed { index, sample ->
            val x = padding + ((sample.timestamp - earliestTime) / timeRange) * chartWidth
            val y = padding + chartHeight * (1f - sample.level / 100f)

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, chartHeight + padding) // Start fill at bottom
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill path along bottom
        val lastX = padding + ((visibleSamples.last().timestamp - earliestTime) / timeRange) * chartWidth
        fillPath.lineTo(lastX, chartHeight + padding)
        fillPath.close()

        // Draw fill
        drawPath(path = fillPath, color = fillColor)

        // Draw line
        drawPath(
            path = linePath,
            color = primaryColor,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun OptimizationWarningCard(
    killCount: Int,
    onFixClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Battery Optimization Impact",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Service killed $killCount time(s) today. Battery optimization may be restricting background processes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onFixClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Fix")
                }
                TextButton(
                    onClick = onDismissClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ImpactBullet(text: String) {
    Row {
        Text(
            text = "\u2022 ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
