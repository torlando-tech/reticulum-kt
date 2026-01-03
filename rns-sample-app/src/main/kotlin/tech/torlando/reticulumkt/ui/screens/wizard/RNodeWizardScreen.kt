package tech.torlando.reticulumkt.ui.screens.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
import tech.torlando.reticulumkt.ui.components.InterfaceTypeCard
import tech.torlando.reticulumkt.ui.components.WizardBottomBar
import tech.torlando.reticulumkt.ui.screens.InterfaceType
import java.util.UUID

enum class RNodeConnectionType {
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    TCP_WIFI,
}

enum class RNodeWizardStep {
    CONNECTION_TYPE,
    DEVICE_CONFIG,
    RADIO_CONFIG,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RNodeWizardScreen(
    onNavigateBack: () -> Unit,
    onSave: (StoredInterfaceConfig) -> Unit,
    editingInterface: StoredInterfaceConfig? = null,
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var isForward by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    // State
    var connectionType by remember { mutableStateOf(RNodeConnectionType.BLUETOOTH_LE) }
    var deviceName by remember { mutableStateOf(editingInterface?.name ?: "") }
    var deviceAddress by remember { mutableStateOf(editingInterface?.host ?: "") }
    var tcpPort by remember { mutableStateOf(editingInterface?.port?.toString() ?: "4242") }

    // Radio config (simplified)
    var frequency by remember { mutableStateOf("868.0") }
    var bandwidth by remember { mutableStateOf("125") }
    var spreadingFactor by remember { mutableStateOf("8") }
    var txPower by remember { mutableStateOf("17") }

    val steps = RNodeWizardStep.entries
    val canProceed = when (steps[currentStep]) {
        RNodeWizardStep.CONNECTION_TYPE -> true
        RNodeWizardStep.DEVICE_CONFIG -> deviceName.isNotBlank() && when (connectionType) {
            RNodeConnectionType.TCP_WIFI -> deviceAddress.isNotBlank() && tcpPort.toIntOrNull() != null
            else -> deviceAddress.isNotBlank()
        }
        RNodeWizardStep.RADIO_CONFIG -> frequency.toDoubleOrNull() != null &&
            bandwidth.toIntOrNull() != null &&
            spreadingFactor.toIntOrNull() != null &&
            txPower.toIntOrNull() != null
    }

    fun goBack() {
        if (currentStep > 0) {
            isForward = false
            currentStep--
        } else {
            onNavigateBack()
        }
    }

    fun goNext() {
        if (currentStep < steps.size - 1) {
            isForward = true
            currentStep++
        } else {
            // Save
            isSaving = true
            val config = StoredInterfaceConfig(
                id = editingInterface?.id ?: UUID.randomUUID().toString(),
                name = deviceName,
                type = InterfaceType.RNODE.name,
                host = deviceAddress,
                port = if (connectionType == RNodeConnectionType.TCP_WIFI) tcpPort.toIntOrNull() else null,
            )
            onSave(config)
        }
    }

    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (editingInterface != null) "Edit RNode" else "Add RNode")
                },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            WizardBottomBar(
                currentStepIndex = currentStep,
                totalSteps = steps.size,
                buttonText = if (currentStep == steps.size - 1) "Save" else "Next",
                canProceed = canProceed,
                isSaving = isSaving,
                onButtonClick = { goNext() }
            )
        }
    ) { padding ->
        when (steps[currentStep]) {
            RNodeWizardStep.CONNECTION_TYPE -> ConnectionTypeStep(
                selectedType = connectionType,
                onTypeSelected = { connectionType = it },
                modifier = Modifier.padding(padding)
            )
            RNodeWizardStep.DEVICE_CONFIG -> DeviceConfigStep(
                connectionType = connectionType,
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                tcpPort = tcpPort,
                onNameChange = { deviceName = it },
                onAddressChange = { deviceAddress = it },
                onPortChange = { tcpPort = it },
                modifier = Modifier.padding(padding)
            )
            RNodeWizardStep.RADIO_CONFIG -> RadioConfigStep(
                frequency = frequency,
                bandwidth = bandwidth,
                spreadingFactor = spreadingFactor,
                txPower = txPower,
                onFrequencyChange = { frequency = it },
                onBandwidthChange = { bandwidth = it },
                onSpreadingFactorChange = { spreadingFactor = it },
                onTxPowerChange = { txPower = it },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ConnectionTypeStep(
    selectedType: RNodeConnectionType,
    onTypeSelected: (RNodeConnectionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Connection Type",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Select how you will connect to your RNode device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        InterfaceTypeCard(
            icon = Icons.Filled.BluetoothSearching,
            title = "Bluetooth LE",
            description = "Connect via Bluetooth Low Energy. Best for newer RNode devices and lower power consumption.",
            isSelected = selectedType == RNodeConnectionType.BLUETOOTH_LE,
            onClick = { onTypeSelected(RNodeConnectionType.BLUETOOTH_LE) }
        )

        InterfaceTypeCard(
            icon = Icons.Filled.Bluetooth,
            title = "Bluetooth Classic",
            description = "Connect via classic Bluetooth serial. Compatible with all RNode devices.",
            isSelected = selectedType == RNodeConnectionType.BLUETOOTH_CLASSIC,
            onClick = { onTypeSelected(RNodeConnectionType.BLUETOOTH_CLASSIC) }
        )

        InterfaceTypeCard(
            icon = Icons.Filled.Wifi,
            title = "WiFi / TCP",
            description = "Connect via TCP over WiFi. Use for RNodes with network connectivity.",
            isSelected = selectedType == RNodeConnectionType.TCP_WIFI,
            onClick = { onTypeSelected(RNodeConnectionType.TCP_WIFI) }
        )
    }
}

@Composable
private fun DeviceConfigStep(
    connectionType: RNodeConnectionType,
    deviceName: String,
    deviceAddress: String,
    tcpPort: String,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Device Configuration",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = when (connectionType) {
                RNodeConnectionType.BLUETOOTH_LE -> "Enter your RNode's Bluetooth LE details."
                RNodeConnectionType.BLUETOOTH_CLASSIC -> "Enter your RNode's Bluetooth address."
                RNodeConnectionType.TCP_WIFI -> "Enter your RNode's network address."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (connectionType) {
                        RNodeConnectionType.BLUETOOTH_LE -> Icons.Filled.BluetoothSearching
                        RNodeConnectionType.BLUETOOTH_CLASSIC -> Icons.Filled.Bluetooth
                        RNodeConnectionType.TCP_WIFI -> Icons.Filled.Wifi
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "RNode Interface",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = when (connectionType) {
                            RNodeConnectionType.BLUETOOTH_LE -> "Bluetooth Low Energy"
                            RNodeConnectionType.BLUETOOTH_CLASSIC -> "Bluetooth Classic"
                            RNodeConnectionType.TCP_WIFI -> "TCP/WiFi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = deviceName,
            onValueChange = onNameChange,
            label = { Text("Interface Name") },
            placeholder = { Text("e.g., My RNode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = deviceAddress,
            onValueChange = onAddressChange,
            label = { Text(
                when (connectionType) {
                    RNodeConnectionType.TCP_WIFI -> "IP Address or Hostname"
                    else -> "Bluetooth Address"
                }
            ) },
            placeholder = { Text(
                when (connectionType) {
                    RNodeConnectionType.TCP_WIFI -> "192.168.1.100"
                    else -> "AA:BB:CC:DD:EE:FF"
                }
            ) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (connectionType == RNodeConnectionType.TCP_WIFI) {
            OutlinedTextField(
                value = tcpPort,
                onValueChange = { if (it.all { c -> c.isDigit() }) onPortChange(it) },
                label = { Text("Port") },
                placeholder = { Text("4242") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioConfigStep(
    frequency: String,
    bandwidth: String,
    spreadingFactor: String,
    txPower: String,
    onFrequencyChange: (String) -> Unit,
    onBandwidthChange: (String) -> Unit,
    onSpreadingFactorChange: (String) -> Unit,
    onTxPowerChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Radio Configuration",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Configure the LoRa radio parameters for your RNode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Radio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Ensure all RNodes in your network use the same radio settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bandwidth presets
        Text(
            text = "Bandwidth",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("125", "250", "500").forEach { bw ->
                FilterChip(
                    selected = bandwidth == bw,
                    onClick = { onBandwidthChange(bw) },
                    label = { Text("${bw}kHz") }
                )
            }
        }

        // Spreading Factor presets
        Text(
            text = "Spreading Factor",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("7", "8", "9", "10", "11", "12").forEach { sf ->
                FilterChip(
                    selected = spreadingFactor == sf,
                    onClick = { onSpreadingFactorChange(sf) },
                    label = { Text("SF$sf") }
                )
            }
        }

        OutlinedTextField(
            value = frequency,
            onValueChange = { onFrequencyChange(it) },
            label = { Text("Frequency (MHz)") },
            placeholder = { Text("868.0") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = txPower,
            onValueChange = { if (it.all { c -> c.isDigit() || c == '-' }) onTxPowerChange(it) },
            label = { Text("TX Power (dBm)") },
            placeholder = { Text("17") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
