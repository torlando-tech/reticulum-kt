package tech.torlando.reticulumkt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

enum class BatteryMode(val displayName: String, val description: String) {
    MAXIMUM_BATTERY("Maximum Battery", "Longest intervals, minimal network activity"),
    BALANCED("Balanced", "Good battery life with reasonable responsiveness"),
    PERFORMANCE("Performance", "Shortest intervals, most responsive"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    viewModel: ReticulumViewModel,
    onNavigateBack: () -> Unit = {},
) {
    val batteryMode by viewModel.batteryMode.collectAsState()
    val maxHashlistSize by viewModel.maxHashlistSize.collectAsState()
    val maxQueuedAnnounces by viewModel.maxQueuedAnnounces.collectAsState()
    val monitorState by viewModel.monitorState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Battery Mode Section
            Text(
                text = "Battery Mode",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    BatteryMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = batteryMode == mode,
                                    onClick = { viewModel.setBatteryMode(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = batteryMode == mode,
                                onClick = null
                            )
                            Column(
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .weight(1f)
                            ) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Memory Section
            Text(
                text = "Memory Management",
                style = MaterialTheme.typography.titleMedium
            )

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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Current Memory Usage
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Memory Usage",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${monitorState.heapUsedMb} / ${monitorState.heapMaxMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (monitorState.heapMaxMb > 0) {
                                    monitorState.heapUsedMb.toFloat() / monitorState.heapMaxMb.toFloat()
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Max Hashlist Size Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Max Hashlist Size",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${maxHashlistSize}K",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = maxHashlistSize.toFloat(),
                            onValueChange = { viewModel.setMaxHashlistSize(it.toInt()) },
                            valueRange = 10f..200f,
                            steps = 18
                        )
                    }

                    // Max Queued Announces Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Max Queued Announces",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "$maxQueuedAnnounces",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = maxQueuedAnnounces.toFloat(),
                            onValueChange = { viewModel.setMaxQueuedAnnounces(it.toInt()) },
                            valueRange = 50f..500f,
                            steps = 8
                        )
                    }

                    // Trim Memory Button
                    Button(
                        onClick = { viewModel.trimMemory() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Trim Memory")
                    }
                }
            }
        }
    }
}
