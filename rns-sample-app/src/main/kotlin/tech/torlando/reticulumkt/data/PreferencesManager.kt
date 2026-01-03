package tech.torlando.reticulumkt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.torlando.reticulumkt.ui.screens.BatteryMode
import tech.torlando.reticulumkt.ui.screens.DarkModeOption
import tech.torlando.reticulumkt.ui.screens.InterfaceType
import tech.torlando.reticulumkt.ui.theme.PresetTheme

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class StoredInterfaceConfig(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    // TCP Client
    val host: String? = null,
    val port: Int? = null,
    // UDP
    val bindIp: String? = null,
    val bindPort: Int? = null,
    val forwardIp: String? = null,
    val forwardPort: Int? = null,
    // Auto Interface
    val groupId: String? = null,
    val discoveryPort: Int? = null,
)

class PreferencesManager(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
        val ENABLE_TRANSPORT = booleanPreferencesKey("enable_transport")
        val BATTERY_MODE = stringPreferencesKey("battery_mode")
        val MAX_HASHLIST_SIZE = stringPreferencesKey("max_hashlist_size")
        val MAX_QUEUED_ANNOUNCES = stringPreferencesKey("max_queued_announces")
        val INTERFACES = stringPreferencesKey("interfaces")
        val SHARE_INSTANCE = booleanPreferencesKey("share_instance")
        val SHARED_INSTANCE_PORT = stringPreferencesKey("shared_instance_port")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Theme
    val theme: Flow<PresetTheme> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME]?.let {
            try { PresetTheme.valueOf(it) } catch (e: Exception) { PresetTheme.VIBRANT }
        } ?: PresetTheme.VIBRANT
    }

    suspend fun setTheme(theme: PresetTheme) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }

    // Dark Mode
    val darkMode: Flow<DarkModeOption> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE]?.let {
            try { DarkModeOption.valueOf(it) } catch (e: Exception) { DarkModeOption.SYSTEM }
        } ?: DarkModeOption.SYSTEM
    }

    suspend fun setDarkMode(mode: DarkModeOption) {
        context.dataStore.edit { it[Keys.DARK_MODE] = mode.name }
    }

    // Developer Mode
    val developerMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVELOPER_MODE] ?: false
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEVELOPER_MODE] = enabled }
    }

    // Auto Start
    val autoStart: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_START] ?: false
    }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    // Show Notification
    val showNotification: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_NOTIFICATION] ?: true
    }

    suspend fun setShowNotification(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_NOTIFICATION] = enabled }
    }

    // Enable Transport
    val enableTransport: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ENABLE_TRANSPORT] ?: false
    }

    suspend fun setEnableTransport(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_TRANSPORT] = enabled }
    }

    // Battery Mode
    val batteryMode: Flow<BatteryMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.BATTERY_MODE]?.let {
            try { BatteryMode.valueOf(it) } catch (e: Exception) { BatteryMode.BALANCED }
        } ?: BatteryMode.BALANCED
    }

    suspend fun setBatteryMode(mode: BatteryMode) {
        context.dataStore.edit { it[Keys.BATTERY_MODE] = mode.name }
    }

    // Max Hashlist Size (in thousands)
    val maxHashlistSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAX_HASHLIST_SIZE]?.toIntOrNull() ?: 50
    }

    suspend fun setMaxHashlistSize(size: Int) {
        context.dataStore.edit { it[Keys.MAX_HASHLIST_SIZE] = size.toString() }
    }

    // Max Queued Announces
    val maxQueuedAnnounces: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAX_QUEUED_ANNOUNCES]?.toIntOrNull() ?: 100
    }

    suspend fun setMaxQueuedAnnounces(count: Int) {
        context.dataStore.edit { it[Keys.MAX_QUEUED_ANNOUNCES] = count.toString() }
    }

    // Interfaces
    val interfaces: Flow<List<StoredInterfaceConfig>> = context.dataStore.data.map { prefs ->
        prefs[Keys.INTERFACES]?.let {
            try { json.decodeFromString<List<StoredInterfaceConfig>>(it) } catch (e: Exception) { emptyList() }
        } ?: getDefaultInterfaces()
    }

    suspend fun setInterfaces(interfaces: List<StoredInterfaceConfig>) {
        context.dataStore.edit { it[Keys.INTERFACES] = json.encodeToString(interfaces) }
    }

    suspend fun addInterface(config: StoredInterfaceConfig) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.INTERFACES]?.let {
                try { json.decodeFromString<List<StoredInterfaceConfig>>(it) } catch (e: Exception) { emptyList() }
            } ?: getDefaultInterfaces()
            prefs[Keys.INTERFACES] = json.encodeToString(current + config)
        }
    }

    suspend fun removeInterface(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.INTERFACES]?.let {
                try { json.decodeFromString<List<StoredInterfaceConfig>>(it) } catch (e: Exception) { emptyList() }
            } ?: getDefaultInterfaces()
            prefs[Keys.INTERFACES] = json.encodeToString(current.filter { it.id != id })
        }
    }

    suspend fun updateInterface(config: StoredInterfaceConfig) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.INTERFACES]?.let {
                try { json.decodeFromString<List<StoredInterfaceConfig>>(it) } catch (e: Exception) { emptyList() }
            } ?: getDefaultInterfaces()
            prefs[Keys.INTERFACES] = json.encodeToString(current.map { if (it.id == config.id) config else it })
        }
    }

    private fun getDefaultInterfaces(): List<StoredInterfaceConfig> = listOf(
        StoredInterfaceConfig(
            id = "default_tcp",
            name = "Beleth RNS Hub",
            type = InterfaceType.TCP_CLIENT.name,
            host = "rns.beleth.net",
            port = 4242,
        )
    )

    // Share Instance - allows other apps to connect to this instance
    val shareInstance: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHARE_INSTANCE] ?: true // Default to true for Android
    }

    suspend fun setShareInstance(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHARE_INSTANCE] = enabled }
    }

    // Shared Instance Port
    val sharedInstancePort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHARED_INSTANCE_PORT]?.toIntOrNull() ?: 37428
    }

    suspend fun setSharedInstancePort(port: Int) {
        context.dataStore.edit { it[Keys.SHARED_INSTANCE_PORT] = port.toString() }
    }
}
