package tech.torlando.reticulumkt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.ui.theme.PresetTheme
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel
import tech.torlando.reticulumkt.viewmodel.SharedInstanceStatus

enum class DarkModeOption(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ReticulumViewModel,
) {
    val selectedTheme by viewModel.theme.collectAsState()
    val darkModeOption by viewModel.darkMode.collectAsState()
    val autoStartOnBoot by viewModel.autoStart.collectAsState()
    val showNotification by viewModel.showNotification.collectAsState()
    val shareInstance by viewModel.shareInstance.collectAsState()
    val sharedInstancePort by viewModel.sharedInstancePort.collectAsState()
    val sharedInstanceStatus by viewModel.sharedInstanceStatus.collectAsState()

    val isDarkTheme = when (darkModeOption) {
        DarkModeOption.SYSTEM -> isSystemInDarkTheme()
        DarkModeOption.LIGHT -> false
        DarkModeOption.DARK -> true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
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
            // Theme Section
            SettingsSection(title = "Theme", icon = Icons.Filled.Palette) {
                // Theme Selector
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(PresetTheme.entries.toList()) { theme ->
                        ThemePreviewCard(
                            theme = theme,
                            isSelected = selectedTheme == theme,
                            isDarkTheme = isDarkTheme,
                            onClick = { viewModel.setTheme(theme) }
                        )
                    }
                }
            }

            // Dark Mode Section
            SettingsSection(title = "Dark Mode", icon = Icons.Filled.DarkMode) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DarkModeOption.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = darkModeOption == option,
                            onClick = { viewModel.setDarkMode(option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DarkModeOption.entries.size
                            )
                        ) {
                            Text(option.displayName)
                        }
                    }
                }
            }

            // General Settings
            SettingsSection(title = "General", icon = null) {
                SettingsToggleRow(
                    title = "Show Notification",
                    description = "Show persistent notification when service is running",
                    checked = showNotification,
                    onCheckedChange = { viewModel.setShowNotification(it) }
                )
                SettingsToggleRow(
                    title = "Auto-start on Boot",
                    description = "Start Reticulum service when device boots",
                    checked = autoStartOnBoot,
                    onCheckedChange = { viewModel.setAutoStart(it) }
                )
            }

            // Shared Instance Section
            SettingsSection(title = "Shared Instance", icon = Icons.Filled.Share) {
                SettingsToggleRow(
                    title = "Share with Other Apps",
                    description = "Allow other apps to use this Reticulum instance",
                    checked = shareInstance,
                    onCheckedChange = { viewModel.setShareInstance(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Port", style = MaterialTheme.typography.bodyMedium)
                    Text(sharedInstancePort.toString(), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (sharedInstanceStatus) {
                            is SharedInstanceStatus.Stopped -> "Stopped"
                            is SharedInstanceStatus.Starting -> "Starting..."
                            is SharedInstanceStatus.Running -> "Running (${(sharedInstanceStatus as SharedInstanceStatus.Running).clientCount} clients)"
                            is SharedInstanceStatus.ConnectedToExisting -> "Connected to port ${(sharedInstanceStatus as SharedInstanceStatus.ConnectedToExisting).port}"
                            is SharedInstanceStatus.Error -> "Error"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (sharedInstanceStatus) {
                            is SharedInstanceStatus.Running -> MaterialTheme.colorScheme.primary
                            is SharedInstanceStatus.ConnectedToExisting -> MaterialTheme.colorScheme.tertiary
                            is SharedInstanceStatus.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            // About Section
            SettingsSection(title = "About", icon = Icons.Filled.Info) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Version", style = MaterialTheme.typography.bodyMedium)
                    Text("1.0.0", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Build", style = MaterialTheme.typography.bodyMedium)
                    Text("Debug", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Bottom padding for navigation bar
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
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
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
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
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ThemePreviewCard(
    theme: PresetTheme,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
) {
    val previewColors = theme.getPreviewColors(isDarkTheme)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Color preview circles
            Row {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(previewColors.first)
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(previewColors.second)
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(previewColors.third)
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}
