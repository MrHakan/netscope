package com.netscope.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netscope.core.network.SpeedTestResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolkitDataStore by preferencesDataStore(name = "netscope_toolkit")

data class ManualNetwork(
    val id: String,
    val name: String,
    val cidr: String?,
    val gateway: String?,
)

data class ManualDevice(
    val id: String,
    val networkId: String?,
    val name: String,
    val address: String,
    val mac: String?,
)

data class ToolkitPersistentState(
    val speedHistory: List<SpeedTestResult> = emptyList(),
    val manualNetworks: List<ManualNetwork> = emptyList(),
    val manualDevices: List<ManualDevice> = emptyList(),
    val favoriteTargets: List<String> = emptyList(),
    val monitors: List<HostMonitorTarget> = emptyList(),
)

/**
 * Persistent user-created toolkit data.
 *
 * This stays separate from scan history so importing a backup can never silently mutate
 * observed network evidence. The backup is a transparent JSON document.
 */
@Singleton
class ToolkitRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val state: Flow<ToolkitPersistentState> = context.toolkitDataStore.data.map { preferences ->
        parse(preferences[STATE_JSON])
    }

    suspend fun addSpeedResult(result: SpeedTestResult) = mutate { current ->
        current.copy(speedHistory = (listOf(result) + current.speedHistory).take(MAX_SPEED_HISTORY))
    }

    suspend fun addNetwork(name: String, cidr: String?, gateway: String?) = mutate { current ->
        val network = ManualNetwork(
            id = UUID.randomUUID().toString(),
            name = clean(name, 96),
            cidr = cidr?.let { clean(it, 64) }?.ifBlank { null },
            gateway = gateway?.let { clean(it, 64) }?.ifBlank { null },
        )
        current.copy(manualNetworks = current.manualNetworks + network)
    }

    suspend fun removeNetwork(id: String) = mutate { current ->
        current.copy(
            manualNetworks = current.manualNetworks.filterNot { it.id == id },
            manualDevices = current.manualDevices.map {
                if (it.networkId == id) it.copy(networkId = null) else it
            },
        )
    }

    suspend fun addDevice(
        networkId: String?,
        name: String,
        address: String,
        mac: String?,
    ) = mutate { current ->
        val device = ManualDevice(
            id = UUID.randomUUID().toString(),
            networkId = networkId,
            name = clean(name, 96),
            address = clean(address, 128),
            mac = mac?.let { clean(it, 32) }?.ifBlank { null },
        )
        current.copy(manualDevices = current.manualDevices + device)
    }

    suspend fun removeDevice(id: String) = mutate { current ->
        current.copy(manualDevices = current.manualDevices.filterNot { it.id == id })
    }

    suspend fun toggleFavorite(target: String) = mutate { current ->
        val cleanTarget = clean(target, 256)
        val values = current.favoriteTargets.toMutableList()
        if (!values.remove(cleanTarget)) values.add(0, cleanTarget)
        current.copy(favoriteTargets = values.distinct().take(MAX_FAVORITES))
    }

    suspend fun addMonitor(
        label: String,
        host: String,
        port: Int,
        intervalMinutes: Long,
    ): HostMonitorTarget {
        val target = HostMonitorTarget(
            id = UUID.randomUUID().toString(),
            label = clean(label, 96),
            host = clean(host, 253),
            port = port,
            intervalMinutes = intervalMinutes,
        )
        mutate { current ->
            current.copy(monitors = (current.monitors + target).take(MAX_MONITORS))
        }
        return target
    }

    suspend fun removeMonitor(id: String) = mutate { current ->
        current.copy(monitors = current.monitors.filterNot { it.id == id })
    }

    suspend fun exportBackup(): String {
        val current = state.first()
        return JSONObject()
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("exportedAtEpochMillis", System.currentTimeMillis())
            .put("speedHistory", JSONArray().apply {
                current.speedHistory.forEach { result ->
                    put(JSONObject()
                        .put("latencyMillis", result.latencyMillis)
                        .put("jitterMillis", result.jitterMillis)
                        .put("downloadMbps", result.downloadMbps)
                        .put("uploadMbps", result.uploadMbps)
                        .put("downloadedBytes", result.downloadedBytes)
                        .put("uploadedBytes", result.uploadedBytes)
                        .put("testedAtEpochMillis", result.testedAtEpochMillis)
                        .put("endpoint", result.endpoint))
                }
            })
            .put("manualNetworks", JSONArray().apply {
                current.manualNetworks.forEach { network ->
                    put(JSONObject()
                        .put("id", network.id)
                        .put("name", network.name)
                        .put("cidr", network.cidr)
                        .put("gateway", network.gateway))
                }
            })
            .put("manualDevices", JSONArray().apply {
                current.manualDevices.forEach { device ->
                    put(JSONObject()
                        .put("id", device.id)
                        .put("networkId", device.networkId)
                        .put("name", device.name)
                        .put("address", device.address)
                        .put("mac", device.mac))
                }
            })
            .put("favoriteTargets", JSONArray(current.favoriteTargets))
            .put("monitors", JSONArray().apply {
                current.monitors.forEach { target ->
                    put(JSONObject()
                        .put("id", target.id)
                        .put("label", target.label)
                        .put("host", target.host)
                        .put("port", target.port)
                        .put("intervalMinutes", target.intervalMinutes))
                }
            })
            .toString(2)
    }

    suspend fun importBackup(jsonText: String): Result<Unit> = runCatching {
        require(jsonText.length <= MAX_BACKUP_CHARS) { "Backup file is too large." }
        val root = JSONObject(jsonText)
        require(root.optInt("schemaVersion") == BACKUP_SCHEMA_VERSION) {
            "Unsupported backup schema."
        }
        val parsed = parseRoot(root)
        context.toolkitDataStore.edit { it[STATE_JSON] = serialize(parsed) }
    }

    private suspend fun mutate(transform: (ToolkitPersistentState) -> ToolkitPersistentState) {
        context.toolkitDataStore.edit { preferences ->
            val next = transform(parse(preferences[STATE_JSON]))
            preferences[STATE_JSON] = serialize(next)
        }
    }

    private fun parse(raw: String?): ToolkitPersistentState {
        if (raw.isNullOrBlank()) return ToolkitPersistentState()
        return runCatching { parseRoot(JSONObject(raw)) }.getOrDefault(ToolkitPersistentState())
    }

    private fun parseRoot(root: JSONObject): ToolkitPersistentState {
        val speed = root.optJSONArray("speedHistory").objects(MAX_SPEED_HISTORY).mapNotNull { item ->
            runCatching {
                SpeedTestResult(
                    latencyMillis = item.getDouble("latencyMillis"),
                    jitterMillis = if (item.isNull("jitterMillis")) null else item.optDouble("jitterMillis"),
                    downloadMbps = item.getDouble("downloadMbps"),
                    uploadMbps = item.getDouble("uploadMbps"),
                    downloadedBytes = item.optLong("downloadedBytes"),
                    uploadedBytes = item.optLong("uploadedBytes"),
                    testedAtEpochMillis = item.getLong("testedAtEpochMillis"),
                    endpoint = clean(item.optString("endpoint", "speed.cloudflare.com"), 128),
                )
            }.getOrNull()
        }

        val networks = root.optJSONArray("manualNetworks").objects(MAX_MANUAL_NETWORKS).mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ManualNetwork(
                id = clean(id, 64),
                name = clean(name, 96),
                cidr = item.stringOrNull("cidr", 64),
                gateway = item.stringOrNull("gateway", 64),
            )
        }

        val networkIds = networks.map { it.id }.toSet()
        val devices = root.optJSONArray("manualDevices").objects(MAX_MANUAL_DEVICES).mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val address = item.optString("address").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val networkId = item.stringOrNull("networkId", 64)?.takeIf { it in networkIds }
            ManualDevice(
                id = clean(id, 64),
                networkId = networkId,
                name = clean(name, 96),
                address = clean(address, 128),
                mac = item.stringOrNull("mac", 32),
            )
        }

        val favorites = root.optJSONArray("favoriteTargets").strings(MAX_FAVORITES, 256)
        val monitors = root.optJSONArray("monitors").objects(MAX_MONITORS).mapNotNull { item ->
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = item.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val host = item.optString("host").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val port = item.optInt("port")
            val interval = item.optLong("intervalMinutes")
            if (port !in 1..65535 || interval < 15) return@mapNotNull null
            HostMonitorTarget(
                id = clean(id, 64),
                label = clean(label, 96),
                host = clean(host, 253),
                port = port,
                intervalMinutes = interval,
            )
        }
        return ToolkitPersistentState(speed, networks, devices, favorites, monitors)
    }

    private fun serialize(state: ToolkitPersistentState): String = JSONObject()
        .put("speedHistory", JSONArray().apply {
            state.speedHistory.forEach { result ->
                put(JSONObject()
                    .put("latencyMillis", result.latencyMillis)
                    .put("jitterMillis", result.jitterMillis)
                    .put("downloadMbps", result.downloadMbps)
                    .put("uploadMbps", result.uploadMbps)
                    .put("downloadedBytes", result.downloadedBytes)
                    .put("uploadedBytes", result.uploadedBytes)
                    .put("testedAtEpochMillis", result.testedAtEpochMillis)
                    .put("endpoint", result.endpoint))
            }
        })
        .put("manualNetworks", JSONArray().apply {
            state.manualNetworks.forEach { network ->
                put(JSONObject()
                    .put("id", network.id)
                    .put("name", network.name)
                    .put("cidr", network.cidr)
                    .put("gateway", network.gateway))
            }
        })
        .put("manualDevices", JSONArray().apply {
            state.manualDevices.forEach { device ->
                put(JSONObject()
                    .put("id", device.id)
                    .put("networkId", device.networkId)
                    .put("name", device.name)
                    .put("address", device.address)
                    .put("mac", device.mac))
            }
        })
        .put("favoriteTargets", JSONArray(state.favoriteTargets))
        .put("monitors", JSONArray().apply {
            state.monitors.forEach { target ->
                put(JSONObject()
                    .put("id", target.id)
                    .put("label", target.label)
                    .put("host", target.host)
                    .put("port", target.port)
                    .put("intervalMinutes", target.intervalMinutes))
            }
        })
        .toString()

    private fun JSONArray?.objects(limit: Int): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), limit)).mapNotNull(::optJSONObject)
    }

    private fun JSONArray?.strings(limit: Int, maxLength: Int): List<String> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), limit))
            .mapNotNull { optString(it, null) }
            .map { clean(it, maxLength) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun JSONObject.stringOrNull(key: String, limit: Int): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }?.let { clean(it, limit) }

    private fun clean(value: String, limit: Int): String =
        value.filter { it == ' ' || it == '\t' || !it.isISOControl() }.trim().take(limit)

    companion object {
        private val STATE_JSON = stringPreferencesKey("toolkit_state_json")
        private const val BACKUP_SCHEMA_VERSION = 1
        private const val MAX_SPEED_HISTORY = 30
        private const val MAX_MANUAL_NETWORKS = 100
        private const val MAX_MANUAL_DEVICES = 500
        private const val MAX_FAVORITES = 100
        private const val MAX_MONITORS = 100
        private const val MAX_BACKUP_CHARS = 2 * 1024 * 1024
    }
}
