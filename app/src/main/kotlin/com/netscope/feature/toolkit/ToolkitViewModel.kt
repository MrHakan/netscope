package com.netscope.feature.toolkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.network.CellularInspector
import com.netscope.core.network.CellularSnapshot
import com.netscope.core.network.InternetDiagnostics
import com.netscope.core.network.IpGeoResult
import com.netscope.core.network.LegacyDiscovery
import com.netscope.core.network.LlmnrAnswer
import com.netscope.core.network.MdnsDiscovery
import com.netscope.core.network.MdnsRecord
import com.netscope.core.network.NetBiosNodeStatus
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.PublicNetworkIdentity
import com.netscope.core.network.RdapResult
import com.netscope.core.network.SnmpSnapshot
import com.netscope.core.network.SpeedTestProgress
import com.netscope.core.network.SpeedTestResult
import com.netscope.core.network.SsdpDiscovery
import com.netscope.core.network.SsdpRecord
import com.netscope.data.HostMonitorScheduler
import com.netscope.data.ToolkitPersistentState
import com.netscope.data.ToolkitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject

enum class ToolkitSection(val label: String) {
    SPEED("Speed"),
    LOOKUP("RDAP / Geo"),
    DISCOVERY("Bonjour / UPnP"),
    LEGACY("NetBIOS / LLMNR / SNMP"),
    CELLULAR("Cellular"),
    MONITOR("Monitor"),
    INVENTORY("Inventory / Backup"),
}

data class ToolkitUiState(
    val section: ToolkitSection = ToolkitSection.SPEED,
    val persistent: ToolkitPersistentState = ToolkitPersistentState(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,

    val speedProgress: SpeedTestProgress? = null,
    val speedResult: SpeedTestResult? = null,

    val lookupTarget: String = "",
    val rdapResult: RdapResult? = null,
    val geoResult: IpGeoResult? = null,
    val identity: PublicNetworkIdentity? = null,

    val mdnsRecords: List<MdnsRecord> = emptyList(),
    val ssdpRecords: List<SsdpRecord> = emptyList(),

    val legacyTarget: String = "",
    val llmnrName: String = "",
    val snmpCommunity: String = "public",
    val netBios: NetBiosNodeStatus? = null,
    val llmnr: LlmnrAnswer? = null,
    val snmp: SnmpSnapshot? = null,

    val cellular: CellularSnapshot? = null,

    val monitorLabel: String = "",
    val monitorHost: String = "",
    val monitorPort: String = "443",
    val monitorIntervalMinutes: String = "15",

    val networkName: String = "",
    val networkCidr: String = "",
    val networkGateway: String = "",
    val deviceName: String = "",
    val deviceAddress: String = "",
    val deviceMac: String = "",
    val selectedNetworkId: String? = null,
)

@HiltViewModel
class ToolkitViewModel @Inject constructor(
    private val internetDiagnostics: InternetDiagnostics,
    private val legacyDiscovery: LegacyDiscovery,
    private val cellularInspector: CellularInspector,
    private val mdnsDiscovery: MdnsDiscovery,
    private val ssdpDiscovery: SsdpDiscovery,
    private val networkInspector: NetworkInspector,
    private val toolkitRepository: ToolkitRepository,
    private val hostMonitorScheduler: HostMonitorScheduler,
) : ViewModel() {

    private val transient = MutableStateFlow(ToolkitUiState())

    val state: StateFlow<ToolkitUiState> = combine(
        transient,
        toolkitRepository.state,
    ) { current, persistent ->
        current.copy(persistent = persistent)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ToolkitUiState(),
    )

    private var activeJob: Job? = null

    fun select(section: ToolkitSection) {
        cancel()
        transient.value = transient.value.copy(section = section, error = null, info = null)
        if (section == ToolkitSection.CELLULAR) refreshCellular()
    }

    fun setMonitorLabel(value: String) {
        transient.value = transient.value.copy(monitorLabel = value.take(96), error = null)
    }

    fun setMonitorHost(value: String) {
        transient.value = transient.value.copy(monitorHost = value.take(253), error = null)
    }

    fun setMonitorPort(value: String) {
        transient.value = transient.value.copy(monitorPort = value.filter(Char::isDigit).take(5), error = null)
    }

    fun setMonitorInterval(value: String) {
        transient.value = transient.value.copy(
            monitorIntervalMinutes = value.filter(Char::isDigit).take(5),
            error = null,
        )
    }

    fun addMonitor() {
        val current = transient.value
        val host = current.monitorHost.trim()
        val label = current.monitorLabel.trim().ifBlank { host }
        val port = current.monitorPort.toIntOrNull()
        val interval = current.monitorIntervalMinutes.toLongOrNull()
        if (host.isBlank() || port == null || port !in 1..65535 || interval == null || interval < 15) {
            transient.value = current.copy(
                error = "Monitor requires a host, port 1-65535, and an interval of at least 15 minutes.",
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                toolkitRepository.addMonitor(label, host, port, interval)
            }.onSuccess { target ->
                hostMonitorScheduler.schedule(target)
                transient.value = transient.value.copy(
                    monitorLabel = "",
                    monitorHost = "",
                    info = "Monitor scheduled. Android may run periodic work inexactly.",
                    error = null,
                )
            }.onFailure {
                transient.value = transient.value.copy(error = it.message ?: "Monitor could not be scheduled.")
            }
        }
    }

    fun removeMonitor(id: String) {
        hostMonitorScheduler.cancel(id)
        viewModelScope.launch { toolkitRepository.removeMonitor(id) }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        transient.value = transient.value.copy(busy = false)
    }

    fun runSpeedTest() = launchTask {
        transient.value = transient.value.copy(
            speedProgress = null,
            speedResult = null,
            info = "Testing against speed.cloudflare.com. About 15 MB of traffic is used.",
        )
        val result = internetDiagnostics.speedTest { progress ->
            transient.value = transient.value.copy(speedProgress = progress)
        }
        toolkitRepository.addSpeedResult(result)
        transient.value = transient.value.copy(speedResult = result, speedProgress = null)
    }

    fun setLookupTarget(value: String) {
        transient.value = transient.value.copy(lookupTarget = value.take(256), error = null)
    }

    fun runRdap() = launchTask {
        val target = transient.value.lookupTarget.trim()
        require(target.isNotEmpty()) { "Enter an IP address, domain, or AS number." }
        transient.value = transient.value.copy(rdapResult = internetDiagnostics.rdap(target))
    }

    fun runGeo() = launchTask {
        val target = transient.value.lookupTarget.trim()
        require(target.isNotEmpty()) { "Enter a public IP address or hostname." }
        transient.value = transient.value.copy(geoResult = internetDiagnostics.geolocate(target))
    }

    fun loadIdentity() = launchTask {
        transient.value = transient.value.copy(identity = internetDiagnostics.currentIdentity())
    }

    fun toggleFavorite() {
        val target = transient.value.lookupTarget.trim()
        if (target.isEmpty()) return
        viewModelScope.launch { toolkitRepository.toggleFavorite(target) }
    }

    fun discoverServices() = launchTask {
        transient.value = transient.value.copy(mdnsRecords = emptyList(), ssdpRecords = emptyList())
        val active = networkInspector.activeSnapshot()
        val bound = active?.primaryIpv4?.let {
            runCatching { InetAddress.getByAddress(it.bytes) }.getOrNull()
        }

        coroutineScope {
            val mdns = async {
                mdnsDiscovery.discover(bound, 5_000) { record ->
                    val current = transient.value.mdnsRecords
                    val key = record.address.hostAddress + "|" + record.serviceType + "|" + record.port
                    if (current.none {
                            it.address.hostAddress + "|" + it.serviceType + "|" + it.port == key
                        }
                    ) {
                        transient.value = transient.value.copy(
                            mdnsRecords = (current + record).take(MAX_DISCOVERY_ROWS),
                        )
                    }
                }
            }
            val ssdp = async {
                ssdpDiscovery.discover(5_000) { record ->
                    val current = transient.value.ssdpRecords
                    val key = record.usn ?: record.address.hostAddress
                    if (current.none { (it.usn ?: it.address.hostAddress) == key }) {
                        transient.value = transient.value.copy(
                            ssdpRecords = (current + record).take(MAX_DISCOVERY_ROWS),
                        )
                    }
                }
            }
            mdns.await()
            ssdp.await()
        }
    }

    fun setLegacyTarget(value: String) {
        transient.value = transient.value.copy(legacyTarget = value.take(128), error = null)
    }

    fun setLlmnrName(value: String) {
        transient.value = transient.value.copy(llmnrName = value.take(253), error = null)
    }

    fun setSnmpCommunity(value: String) {
        transient.value = transient.value.copy(snmpCommunity = value.take(128), error = null)
    }

    fun runNetBios() = launchTask {
        val address = resolveLegacyTarget() as? Inet4Address
            ?: error("NetBIOS node status requires an IPv4 target.")
        transient.value = transient.value.copy(
            netBios = legacyDiscovery.netBiosNodeStatus(address),
        )
    }

    fun runLlmnr() = launchTask {
        val name = transient.value.llmnrName.trim()
        require(name.isNotEmpty()) { "Enter a host name to resolve over LLMNR." }
        transient.value = transient.value.copy(llmnr = legacyDiscovery.llmnr(name))
    }

    fun runSnmp() = launchTask {
        val address = resolveLegacyTarget()
        val community = transient.value.snmpCommunity
        transient.value = transient.value.copy(
            snmp = legacyDiscovery.snmpSystem(address, community),
        )
    }

    fun refreshCellular() {
        transient.value = transient.value.copy(
            cellular = runCatching { cellularInspector.snapshot() }.getOrNull(),
            error = null,
        )
    }

    fun setNetworkName(value: String) {
        transient.value = transient.value.copy(networkName = value.take(96))
    }

    fun setNetworkCidr(value: String) {
        transient.value = transient.value.copy(networkCidr = value.take(64))
    }

    fun setNetworkGateway(value: String) {
        transient.value = transient.value.copy(networkGateway = value.take(64))
    }

    fun setDeviceName(value: String) {
        transient.value = transient.value.copy(deviceName = value.take(96))
    }

    fun setDeviceAddress(value: String) {
        transient.value = transient.value.copy(deviceAddress = value.take(128))
    }

    fun setDeviceMac(value: String) {
        transient.value = transient.value.copy(deviceMac = value.take(32))
    }

    fun selectManualNetwork(id: String?) {
        transient.value = transient.value.copy(selectedNetworkId = id)
    }

    fun addManualNetwork() {
        val current = transient.value
        if (current.networkName.isBlank()) {
            transient.value = current.copy(error = "Network name is required.")
            return
        }
        viewModelScope.launch {
            toolkitRepository.addNetwork(
                current.networkName,
                current.networkCidr,
                current.networkGateway,
            )
            transient.value = transient.value.copy(
                networkName = "",
                networkCidr = "",
                networkGateway = "",
                info = "Manual network added.",
            )
        }
    }

    fun removeManualNetwork(id: String) {
        viewModelScope.launch { toolkitRepository.removeNetwork(id) }
    }

    fun addManualDevice() {
        val current = transient.value
        if (current.deviceName.isBlank() || current.deviceAddress.isBlank()) {
            transient.value = current.copy(error = "Device name and address are required.")
            return
        }
        viewModelScope.launch {
            toolkitRepository.addDevice(
                current.selectedNetworkId,
                current.deviceName,
                current.deviceAddress,
                current.deviceMac,
            )
            transient.value = transient.value.copy(
                deviceName = "",
                deviceAddress = "",
                deviceMac = "",
                info = "Manual device added.",
            )
        }
    }

    fun removeManualDevice(id: String) {
        viewModelScope.launch { toolkitRepository.removeDevice(id) }
    }

    suspend fun exportBackup(): String = toolkitRepository.exportBackup()

    fun importBackup(json: String) {
        viewModelScope.launch {
            toolkitRepository.importBackup(json)
                .onSuccess {
                    transient.value = transient.value.copy(
                        info = "Backup restored.",
                        error = null,
                    )
                }
                .onFailure {
                    transient.value = transient.value.copy(
                        error = it.message ?: "Backup could not be restored.",
                    )
                }
        }
    }

    private suspend fun resolveLegacyTarget(): InetAddress {
        val target = transient.value.legacyTarget.trim()
        require(target.isNotEmpty()) { "Enter an IP address or hostname." }
        return InetAddress.getByName(target)
    }

    private fun launchTask(block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            transient.value = transient.value.copy(busy = true, error = null, info = null)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transient.value = transient.value.copy(
                    error = e.message ?: "The operation failed.",
                )
            } finally {
                transient.value = transient.value.copy(busy = false)
                activeJob = null
            }
        }
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_DISCOVERY_ROWS = 250
    }
}
