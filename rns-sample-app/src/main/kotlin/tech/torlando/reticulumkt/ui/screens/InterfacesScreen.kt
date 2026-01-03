package tech.torlando.reticulumkt.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
import tech.torlando.reticulumkt.ui.components.InterfaceTypeCard
import tech.torlando.reticulumkt.ui.theme.StatusConnected
import tech.torlando.reticulumkt.ui.theme.StatusOffline
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

enum class InterfaceType(
    val displayName: String,
    val description: String,
    val icon: ImageVector,
) {
    AUTO("Auto Discovery", "Automatically discover peers on the local network", Icons.Filled.Sensors),
    TCP_CLIENT("TCP Client", "Connect to a remote Reticulum transport node", Icons.Filled.Cloud),
    BLE("Bluetooth LE", "Direct connection to nearby devices via Bluetooth", Icons.Filled.Bluetooth),
    RNODE("RNode LoRa", "Connect to RNode hardware for long-range LoRa", Icons.Filled.Radio),
    UDP("UDP Interface", "Direct UDP communication on a specific port", Icons.Filled.Wifi),
}

sealed class InterfaceNavigation {
    object None : InterfaceNavigation()
    object TypeSelector : InterfaceNavigation()
    object TcpWizard : InterfaceNavigation()
    object RNodeWizard : InterfaceNavigation()
    data class SimpleAdd(val type: InterfaceType) : InterfaceNavigation()
    data class Edit(val config: StoredInterfaceConfig) : InterfaceNavigation()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfacesScreen(
    viewModel: ReticulumViewModel,
    onNavigateToTcpWizard: () -> Unit = {},
    onNavigateToRNodeWizard: () -> Unit = {},
) {
    val interfaces by viewModel.interfaces.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val interfaceStatuses by viewModel.interfaceStatuses.collectAsState()
    var navigation by remember { mutableStateOf<InterfaceNavigation>(InterfaceNavigation.None) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interfaces") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navigation = InterfaceNavigation.TypeSelector }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Interface")
            }
        }
    ) { padding ->
        if (interfaces.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No interfaces configured",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tap + to add an interface",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(interfaces, key = { it.id }) { iface ->
                    // Get actual online status from running interface, not just service state
                    val actualStatus = interfaceStatuses[iface.id]
                    val isActuallyOnline = actualStatus?.isOnline ?: false

                    InterfaceCard(
                        config = iface,
                        isOnline = isActuallyOnline,
                        onDelete = { viewModel.removeInterface(iface.id) },
                        onEdit = {
                            val type = try {
                                InterfaceType.valueOf(iface.type)
                            } catch (e: Exception) {
                                InterfaceType.TCP_CLIENT
                            }
                            when (type) {
                                InterfaceType.TCP_CLIENT -> onNavigateToTcpWizard()
                                InterfaceType.RNODE -> onNavigateToRNodeWizard()
                                else -> navigation = InterfaceNavigation.Edit(iface)
                            }
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // Dialogs
    when (val nav = navigation) {
        InterfaceNavigation.TypeSelector -> {
            InterfaceTypeSelectorDialog(
                onDismiss = { navigation = InterfaceNavigation.None },
                onTypeSelected = { type ->
                    navigation = InterfaceNavigation.None
                    when (type) {
                        InterfaceType.TCP_CLIENT -> onNavigateToTcpWizard()
                        InterfaceType.RNODE -> onNavigateToRNodeWizard()
                        else -> navigation = InterfaceNavigation.SimpleAdd(type)
                    }
                }
            )
        }
        is InterfaceNavigation.SimpleAdd -> {
            SimpleAddInterfaceDialog(
                type = nav.type,
                onDismiss = { navigation = InterfaceNavigation.None },
                onAdd = { config ->
                    viewModel.addInterface(config)
                    navigation = InterfaceNavigation.None
                }
            )
        }
        is InterfaceNavigation.Edit -> {
            // For simple types, show edit dialog
            // For complex types (TCP, RNode), navigate to wizard with editing config
            SimpleAddInterfaceDialog(
                type = try { InterfaceType.valueOf(nav.config.type) } catch (e: Exception) { InterfaceType.AUTO },
                existingConfig = nav.config,
                onDismiss = { navigation = InterfaceNavigation.None },
                onAdd = { config ->
                    viewModel.updateInterface(config)
                    navigation = InterfaceNavigation.None
                }
            )
        }
        else -> { /* No dialog */ }
    }
}

@Composable
private fun InterfaceTypeSelectorDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (InterfaceType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Interface") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Select the type of interface to add:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                InterfaceTypeCard(
                    icon = InterfaceType.AUTO.icon,
                    title = InterfaceType.AUTO.displayName,
                    description = InterfaceType.AUTO.description,
                    onClick = { onTypeSelected(InterfaceType.AUTO) }
                )

                InterfaceTypeCard(
                    icon = InterfaceType.TCP_CLIENT.icon,
                    title = InterfaceType.TCP_CLIENT.displayName,
                    description = InterfaceType.TCP_CLIENT.description,
                    onClick = { onTypeSelected(InterfaceType.TCP_CLIENT) }
                )

                InterfaceTypeCard(
                    icon = InterfaceType.BLE.icon,
                    title = InterfaceType.BLE.displayName,
                    description = InterfaceType.BLE.description,
                    onClick = { onTypeSelected(InterfaceType.BLE) }
                )

                InterfaceTypeCard(
                    icon = InterfaceType.RNODE.icon,
                    title = InterfaceType.RNODE.displayName,
                    description = InterfaceType.RNODE.description,
                    onClick = { onTypeSelected(InterfaceType.RNODE) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SimpleAddInterfaceDialog(
    type: InterfaceType,
    existingConfig: StoredInterfaceConfig? = null,
    onDismiss: () -> Unit,
    onAdd: (StoredInterfaceConfig) -> Unit,
) {
    var name by remember { mutableStateOf(existingConfig?.name ?: "") }
    var host by remember { mutableStateOf(existingConfig?.host ?: "") }
    var port by remember { mutableStateOf(existingConfig?.port?.toString() ?: "") }

    val isEditing = existingConfig != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit ${type.displayName}" else "Add ${type.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text(type.displayName) },
                    modifier = Modifier.fillMaxWidth()
                )

                when (type) {
                    InterfaceType.AUTO -> {
                        androidx.compose.material3.OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Group ID (optional)") },
                            placeholder = { Text("reticulum") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    InterfaceType.UDP -> {
                        androidx.compose.material3.OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Bind IP") },
                            placeholder = { Text("0.0.0.0") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Bind Port") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    InterfaceType.BLE -> {
                        Text(
                            text = "Bluetooth LE interface will automatically discover nearby devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        // TCP_CLIENT and RNODE should use wizards, not this dialog
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = StoredInterfaceConfig(
                        id = existingConfig?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.ifBlank { type.displayName },
                        type = type.name,
                        host = host.ifBlank { null },
                        port = port.toIntOrNull(),
                        groupId = if (type == InterfaceType.AUTO) host.ifBlank { null } else null,
                    )
                    onAdd(config)
                },
                enabled = name.isNotBlank() || type == InterfaceType.AUTO || type == InterfaceType.BLE
            ) {
                Text(if (isEditing) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InterfaceCard(
    config: StoredInterfaceConfig,
    isOnline: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
) {
    val type = try {
        InterfaceType.valueOf(config.type)
    } catch (e: Exception) {
        InterfaceType.TCP_CLIENT
    }

    val target = when (type) {
        InterfaceType.TCP_CLIENT -> "${config.host ?: "unknown"}:${config.port ?: 4965}"
        InterfaceType.UDP -> "UDP :${config.port ?: 0}"
        InterfaceType.AUTO -> config.groupId ?: "Auto Discovery"
        InterfaceType.BLE -> "Bluetooth LE"
        InterfaceType.RNODE -> config.host ?: "RNode"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (type != InterfaceType.AUTO && type != InterfaceType.BLE) {
                    modifier.clickable { onEdit() }
                } else modifier
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = type.icon,
                contentDescription = null,
                tint = if (isOnline && config.enabled) StatusConnected else StatusOffline,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = type.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = target,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isOnline && config.enabled) "Online" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnline && config.enabled) StatusConnected else StatusOffline
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
