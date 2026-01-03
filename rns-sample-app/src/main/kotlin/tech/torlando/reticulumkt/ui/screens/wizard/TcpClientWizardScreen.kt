package tech.torlando.reticulumkt.ui.screens.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
import tech.torlando.reticulumkt.ui.components.CustomSettingsCard
import tech.torlando.reticulumkt.ui.components.ServerCard
import tech.torlando.reticulumkt.ui.components.WizardBottomBar
import tech.torlando.reticulumkt.ui.screens.InterfaceType
import java.util.UUID

/**
 * Pre-configured community Reticulum servers.
 */
data class CommunityServer(
    val name: String,
    val host: String,
    val port: Int,
)

private val communityServers = listOf(
    CommunityServer("Beleth RNS Hub", "rns.beleth.net", 4242),
    CommunityServer("FireZen", "firezen.com", 4242),
    CommunityServer("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969),
    CommunityServer("interloper node", "intr.cx", 4242),
    CommunityServer("Jon's Node", "rns.jlamothe.net", 4242),
    CommunityServer("noDNS1", "202.61.243.41", 4965),
    CommunityServer("noDNS2", "193.26.158.230", 4965),
    CommunityServer("NomadNode SEAsia TCP", "rns.jaykayenn.net", 4242),
    CommunityServer("0rbit-Net", "93.95.227.8", 49952),
    CommunityServer("Quad4 TCP Node 1", "rns.quad4.io", 4242),
    CommunityServer("Quad4 TCP Node 2", "rns2.quad4.io", 4242),
    CommunityServer("Quortal TCP Node", "reticulum.qortal.link", 4242),
    CommunityServer("R-Net TCP", "istanbul.reserve.network", 9034),
    CommunityServer("RNS bnZ-NODE01", "node01.rns.bnz.se", 4242),
    CommunityServer("RNS COMSEC-RD", "80.78.23.249", 4242),
    CommunityServer("RNS HAM RADIO", "135.125.238.229", 4242),
    CommunityServer("RNS Testnet StoppedCold", "rns.stoppedcold.com", 4242),
    CommunityServer("RNS_Transport_US-East", "45.77.109.86", 4965),
    CommunityServer("SparkN0de", "aspark.uber.space", 44860),
    CommunityServer("Tidudanka.com", "reticulum.tidudanka.com", 37500),
)

enum class TcpWizardStep {
    SERVER_SELECTION,
    REVIEW_CONFIGURE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcpClientWizardScreen(
    onNavigateBack: () -> Unit,
    onSave: (StoredInterfaceConfig) -> Unit,
    editingInterface: StoredInterfaceConfig? = null,
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var isForward by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    // State
    var selectedServer by remember { mutableStateOf<CommunityServer?>(null) }
    var isCustom by remember { mutableStateOf(editingInterface != null) }
    var interfaceName by remember { mutableStateOf(editingInterface?.name ?: "") }
    var targetHost by remember { mutableStateOf(editingInterface?.host ?: "") }
    var targetPort by remember { mutableStateOf(editingInterface?.port?.toString() ?: "4965") }

    // Initialize from editing interface
    if (editingInterface != null && interfaceName.isEmpty()) {
        interfaceName = editingInterface.name
        targetHost = editingInterface.host ?: ""
        targetPort = editingInterface.port?.toString() ?: "4965"
        isCustom = true
        currentStep = 1 // Skip to review/configure
    }

    val steps = TcpWizardStep.entries
    val canProceed = when (steps[currentStep]) {
        TcpWizardStep.SERVER_SELECTION -> selectedServer != null || isCustom
        TcpWizardStep.REVIEW_CONFIGURE -> interfaceName.isNotBlank() &&
            targetHost.isNotBlank() &&
            targetPort.toIntOrNull() != null
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
            // If server selected, populate fields
            if (currentStep == 0 && selectedServer != null) {
                interfaceName = selectedServer!!.name
                targetHost = selectedServer!!.host
                targetPort = selectedServer!!.port.toString()
            }
            isForward = true
            currentStep++
        } else {
            // Save
            isSaving = true
            val config = StoredInterfaceConfig(
                id = editingInterface?.id ?: UUID.randomUUID().toString(),
                name = interfaceName,
                type = InterfaceType.TCP_CLIENT.name,
                host = targetHost,
                port = targetPort.toIntOrNull() ?: 4965,
            )
            onSave(config)
        }
    }

    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (editingInterface != null) "Edit TCP Interface" else "Add TCP Interface")
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
            TcpWizardStep.SERVER_SELECTION -> ServerSelectionStep(
                selectedServer = selectedServer,
                isCustom = isCustom,
                onServerSelected = { server ->
                    selectedServer = server
                    isCustom = false
                },
                onCustomSelected = {
                    selectedServer = null
                    isCustom = true
                },
                modifier = Modifier.padding(padding)
            )
            TcpWizardStep.REVIEW_CONFIGURE -> ReviewConfigureStep(
                interfaceName = interfaceName,
                targetHost = targetHost,
                targetPort = targetPort,
                onNameChange = { interfaceName = it },
                onHostChange = { targetHost = it },
                onPortChange = { targetPort = it },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ServerSelectionStep(
    selectedServer: CommunityServer?,
    isCustom: Boolean,
    onServerSelected: (CommunityServer) -> Unit,
    onCustomSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Select a Server",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose a community server to connect to, or configure a custom server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Text(
                text = "Community Servers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(communityServers) { server ->
            ServerCard(
                icon = Icons.Filled.Public,
                name = server.name,
                hostPort = "${server.host}:${server.port}",
                isSelected = selectedServer == server,
                onClick = { onServerSelected(server) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Other Options",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            CustomSettingsCard(
                title = "Custom Server",
                description = "Enter your own server address and port",
                isSelected = isCustom,
                onClick = onCustomSelected
            )
        }
    }
}

@Composable
private fun ReviewConfigureStep(
    interfaceName: String,
    targetHost: String,
    targetPort: String,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
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
            text = "Configure Interface",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Review and customize the interface settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Summary card
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
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TCP Client Interface",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Connects to a remote Reticulum transport node",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = interfaceName,
            onValueChange = onNameChange,
            label = { Text("Interface Name") },
            placeholder = { Text("e.g., My Server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = targetHost,
            onValueChange = onHostChange,
            label = { Text("Target Host") },
            placeholder = { Text("hostname or IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = targetPort,
            onValueChange = { if (it.all { c -> c.isDigit() }) onPortChange(it) },
            label = { Text("Target Port") },
            placeholder = { Text("4965") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
