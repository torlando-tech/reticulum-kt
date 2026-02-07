package tech.torlando.reticulumkt.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.torlando.reticulumkt.ui.theme.StatusConnected
import tech.torlando.reticulumkt.ui.theme.StatusOffline
import tech.torlando.reticulumkt.ui.theme.StatusWarning
import tech.torlando.reticulumkt.viewmodel.PeerHistoryPoint
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
    val allHistory by viewModel.peerHistory.collectAsState()
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
                    val history = peer.peerIdentityHex?.let { allHistory[it] } ?: emptyList()
                    ConnectionCard(peer = peer, history = history)
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
private fun ConnectionCard(peer: SpawnedPeerInfo, history: List<PeerHistoryPoint>) {
    val targetRssi = peer.currentRssi?.takeIf { it != -100 } ?: peer.discoveryRssi ?: -100
    val animatedRssi by animateIntAsState(
        targetValue = targetRssi,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "rssi",
    )
    val signalQuality = getSignalQuality(animatedRssi)

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
            // Header row: identity + role badge + status
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
                Spacer(modifier = Modifier.width(8.dp))

                // Role badge: Central or Peripheral
                peer.isOutgoing?.let { outgoing ->
                    RoleBadge(isOutgoing = outgoing)
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = if (peer.isOnline) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (peer.isOnline) StatusConnected else StatusOffline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Full identity hash (monospace, no truncation)
            peer.peerIdentityHex?.let { hex ->
                Text(
                    text = hex,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Detail rows: MAC + Signal + MTU
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Full MAC address
                peer.peerAddress?.let { addr ->
                    Text(
                        text = addr,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // MTU
                peer.peerMtu?.let { mtu ->
                    Text(
                        text = "MTU $mtu",
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
                // Signal quality with live RSSI
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = signalQuality.icon,
                        contentDescription = null,
                        tint = signalQuality.color(),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val rssiText = "${signalQuality.label} ($animatedRssi dBm)"
                    Text(
                        text = rssiText,
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

            // Traffic speed chart (only show if we have at least 2 points for delta)
            if (history.size >= 2) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Traffic Speed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TrafficSpeedChart(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                )
            }

            // RSSI chart (only for central connections with RSSI data)
            val rssiPoints = history.mapNotNull { it.rssi?.takeIf { r -> r != -100 } }
            if (rssiPoints.size >= 2) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Signal Strength",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                RssiChart(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                )
            }
        }
    }
}

/**
 * Central / Peripheral role badge.
 */
@Composable
private fun RoleBadge(isOutgoing: Boolean) {
    val label = if (isOutgoing) "Central" else "Peripheral"
    val color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ---- Charts ----

/**
 * Traffic speed chart showing RX (primary) and TX (tertiary) speed in bytes/sec.
 * Computes speed from consecutive PeerHistoryPoint deltas. Smoothly animates
 * between data updates using a progress-based lerp on Y values and scale.
 */
@Composable
private fun TrafficSpeedChart(
    history: List<PeerHistoryPoint>,
    modifier: Modifier = Modifier,
) {
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    // Compute speeds from deltas
    val speeds = remember(history) {
        val result = mutableListOf<Triple<Long, Float, Float>>()
        for (i in 1 until history.size) {
            val dt = (history[i].timestamp - history[i - 1].timestamp) / 1000f
            if (dt <= 0f) continue
            val rxSpeed = (history[i].rxBytes - history[i - 1].rxBytes) / dt
            val txSpeed = (history[i].txBytes - history[i - 1].txBytes) / dt
            result.add(Triple(history[i].timestamp, rxSpeed.coerceAtLeast(0f), txSpeed.coerceAtLeast(0f)))
        }
        result
    }

    if (speeds.isEmpty()) return

    val rxSpeeds = speeds.map { it.second }
    val txSpeeds = speeds.map { it.third }
    val maxSpeed = (speeds.maxOf { maxOf(it.second, it.third) }).coerceAtLeast(100f)

    // Animate Y-axis scale smoothly
    val animatedMax by animateFloatAsState(
        targetValue = maxSpeed,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "maxSpeed",
    )

    // Animate data transition: lerp from previous chart state to new
    var startRx by remember { mutableStateOf(emptyList<Float>()) }
    var endRx by remember { mutableStateOf(emptyList<Float>()) }
    var startTx by remember { mutableStateOf(emptyList<Float>()) }
    var endTx by remember { mutableStateOf(emptyList<Float>()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(speeds) {
        // Capture current interpolated position as new start (handles interrupted animations)
        startRx = lerpList(startRx, endRx, progress.value)
        startTx = lerpList(startTx, endTx, progress.value)
        endRx = rxSpeeds
        endTx = txSpeeds
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }

    val displayRx = lerpList(startRx, endRx, progress.value)
    val displayTx = lerpList(startTx, endTx, progress.value)

    Canvas(modifier = modifier) {
        val leftPad = 45f
        val chartWidth = size.width - leftPad
        val chartHeight = size.height

        // Y-axis labels
        drawText(
            textMeasurer = textMeasurer,
            text = formatSpeed(animatedMax),
            topLeft = Offset(0f, 0f),
            style = labelStyle,
        )
        drawText(
            textMeasurer = textMeasurer,
            text = "0",
            topLeft = Offset(0f, chartHeight - 12f),
            style = labelStyle,
        )

        // Draw RX line (animated)
        drawSpeedLine(displayRx, animatedMax, rxColor, leftPad, chartWidth, chartHeight)
        // Draw TX line (animated)
        drawSpeedLine(displayTx, animatedMax, txColor, leftPad, chartWidth, chartHeight)
    }
}

private fun DrawScope.drawSpeedLine(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    leftPad: Float,
    chartWidth: Float,
    chartHeight: Float,
) {
    if (values.size < 2) return

    val path = Path()
    val fillPath = Path()
    val step = chartWidth / (values.size - 1).coerceAtLeast(1)

    for (i in values.indices) {
        val x = leftPad + i * step
        val y = chartHeight - (values[i] / maxValue) * chartHeight

        if (i == 0) {
            path.moveTo(x, y)
            fillPath.moveTo(x, chartHeight)
            fillPath.lineTo(x, y)
        } else {
            path.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
    }

    // Fill gradient below line
    fillPath.lineTo(leftPad + (values.size - 1) * step, chartHeight)
    fillPath.close()

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.2f), color.copy(alpha = 0.02f)),
        ),
    )

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * RSSI-over-time chart with color-coded signal quality.
 * Green > -50, Yellow > -70, Orange > -85, Red below.
 * Smoothly animates between data updates.
 */
@Composable
private fun RssiChart(
    history: List<PeerHistoryPoint>,
    modifier: Modifier = Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    // Filter to points with RSSI data
    val rssiPoints = remember(history) {
        history.filter { it.rssi != null && it.rssi != -100 }
    }
    if (rssiPoints.size < 2) return

    val rssiValues = rssiPoints.map { it.rssi!!.toFloat() }

    // Animate data transition
    var startValues by remember { mutableStateOf(emptyList<Float>()) }
    var endValues by remember { mutableStateOf(emptyList<Float>()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(rssiValues) {
        startValues = lerpList(startValues, endValues, progress.value)
        endValues = rssiValues
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }

    val displayValues = lerpList(startValues, endValues, progress.value)

    Canvas(modifier = modifier) {
        val leftPad = 45f
        val chartWidth = size.width - leftPad
        val chartHeight = size.height

        val yMin = -100f
        val yMax = -30f
        val yRange = yMax - yMin

        // Y-axis labels
        drawText(
            textMeasurer = textMeasurer,
            text = "-30",
            topLeft = Offset(0f, 0f),
            style = labelStyle,
        )
        drawText(
            textMeasurer = textMeasurer,
            text = "-100",
            topLeft = Offset(0f, chartHeight - 12f),
            style = labelStyle,
        )

        if (displayValues.size < 2) return@Canvas

        // Draw RSSI line with color segments
        val step = chartWidth / (displayValues.size - 1).coerceAtLeast(1)

        for (i in 1 until displayValues.size) {
            val rssiPrev = displayValues[i - 1]
            val rssiCurr = displayValues[i]

            val x0 = leftPad + (i - 1) * step
            val y0 = chartHeight - ((rssiPrev - yMin) / yRange) * chartHeight
            val x1 = leftPad + i * step
            val y1 = chartHeight - ((rssiCurr - yMin) / yRange) * chartHeight

            val avgRssi = ((rssiPrev + rssiCurr) / 2).toInt()
            val segColor = rssiColor(avgRssi)

            drawLine(
                color = segColor,
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
        }

        // Draw dots at each point
        for (i in displayValues.indices) {
            val rssi = displayValues[i]
            val x = leftPad + i * step
            val y = chartHeight - ((rssi - yMin) / yRange) * chartHeight
            drawCircle(
                color = rssiColor(rssi.toInt()),
                radius = 3f,
                center = Offset(x, y),
            )
        }
    }
}

/**
 * Interpolate between two float lists for smooth chart transitions.
 * Handles different list sizes by clamping to the last known value.
 */
private fun lerpList(from: List<Float>, to: List<Float>, progress: Float): List<Float> {
    if (from.isEmpty()) return to
    if (to.isEmpty()) return from
    return to.mapIndexed { i, target ->
        val source = if (i < from.size) from[i] else (from.lastOrNull() ?: target)
        source + (target - source) * progress
    }
}

private fun rssiColor(rssi: Int): Color = when {
    rssi > -50 -> Color(0xFF4CAF50)  // Green - Excellent
    rssi > -70 -> Color(0xFFFFC107)  // Yellow - Good
    rssi > -85 -> Color(0xFFFF9800)  // Orange - Fair
    else -> Color(0xFFF44336)         // Red - Poor
}

// ---- Helpers ----

private data class SignalQuality(
    val label: String,
    val icon: ImageVector,
    val colorName: String,
) {
    @Composable
    fun color() = when (colorName) {
        "connected" -> StatusConnected
        "warning" -> StatusWarning
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

private fun formatSpeed(bytesPerSec: Float): String {
    return when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.0f B/s".format(bytesPerSec)
    }
}
