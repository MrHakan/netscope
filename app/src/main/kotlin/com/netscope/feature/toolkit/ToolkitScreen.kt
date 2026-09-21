package com.netscope.feature.toolkit

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.data.ManualNetwork
import com.netscope.feature.common.NetScopeScreen
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ToolkitScreen(
    onBack: () -> Unit,
    viewModel: ToolkitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NetScopeScreen(
        title = "Advanced Toolkit",
        subtitle = "Network Analyzer Pro / NetX parity tools",
        onBack = onBack,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolkitSection.entries.forEach { section ->
                        FilterChip(
                            selected = state.section == section,
                            onClick = { viewModel.select(section) },
                            label = { Text(section.label) },
                        )
                    }
                }
            }

            state.error?.let { item { NoticeBanner(it, NoticeTone.ERROR) } }
            state.info?.let { item { NoticeBanner(it, NoticeTone.INFO) } }

            item {
                when (state.section) {
                    ToolkitSection.SPEED -> SpeedSection(state, viewModel)
                    ToolkitSection.LOOKUP -> LookupSection(state, viewModel)
                    ToolkitSection.DISCOVERY -> DiscoverySection(state, viewModel)
                    ToolkitSection.LEGACY -> LegacySection(state, viewModel)
                    ToolkitSection.CELLULAR -> CellularSection(state, viewModel)
                    ToolkitSection.MONITOR -> MonitorSection(state, viewModel)
                    ToolkitSection.INVENTORY -> InventorySection(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun BusyButton(
    busy: Boolean,
    label: String,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            OutlinedButton(onClick = onCancel) { Text("Stop") }
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
        } else {
            Button(onClick = onRun) { Text(label) }
        }
    }
}

@Composable
private fun SpeedSection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    SectionCard(
        title = "Internet speed test",
        subtitle = "Real HTTP transfer against speed.cloudflare.com",
    ) {
        Column {
            NoticeBanner(
                "User initiated only. The test downloads about 10 MB and uploads about 5 MB. " +
                    "The PHY link speed shown elsewhere in NetScope is not used as a substitute.",
                NoticeTone.INFO,
            )
            BusyButton(state.busy, "Run speed test", viewModel::runSpeedTest, viewModel::cancel)

            state.speedProgress?.let { progress ->
                val percent = if (progress.totalBytes > 0) {
                    (progress.transferredBytes * 100 / progress.totalBytes).coerceIn(0, 100)
                } else 0
                Text(
                    progress.phase + ": " + percent + "% · " +
                        (progress.mbps?.let { "%.1f Mbps".format(it) } ?: "measuring"),
                    style = MonoSmallTextStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            state.speedResult?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    PlainRow("Latency", "%.1f ms".format(result.latencyMillis))
                    PlainRow("Jitter", result.jitterMillis?.let { "%.1f ms".format(it) } ?: "N/A")
                    PlainRow("Download", "%.2f Mbps".format(result.downloadMbps))
                    PlainRow("Upload", "%.2f Mbps".format(result.uploadMbps))
                    PlainRow("Endpoint", result.endpoint)
                }
            }

            if (state.persistent.speedHistory.isNotEmpty()) {
                Text(
                    "History",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                state.persistent.speedHistory.take(10).forEach { result ->
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(result.testedAtEpochMillis)) +
                            "  ↓ %.1f  ↑ %.1f Mbps  %.0f ms".format(
                                result.downloadMbps,
                                result.uploadMbps,
                                result.latencyMillis,
                            ),
                        style = MonoSmallTextStyle,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LookupSection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    val context = LocalContext.current
    SectionCard(
        title = "RDAP / WHOIS · ASN · IP geolocation",
        subtitle = "Modern RDAP plus public-IP metadata",
    ) {
        Column {
            NoticeBanner(
                "RDAP queries use rdap.org. IP geolocation uses ipwho.is. Current public IP/ASN " +
                    "metadata uses speed.cloudflare.com/meta. These are explicit public lookups.",
                NoticeTone.INFO,
            )
            OutlinedTextField(
                value = state.lookupTarget,
                onValueChange = viewModel::setLookupTarget,
                label = { Text("IP, hostname, domain or AS number") },
                placeholder = { Text("1.1.1.1 · example.com · AS13335") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::runRdap, enabled = !state.busy) { Text("RDAP") }
                OutlinedButton(onClick = viewModel::runGeo, enabled = !state.busy) { Text("Geo / ASN") }
                OutlinedButton(onClick = viewModel::loadIdentity, enabled = !state.busy) { Text("My public IP") }
                TextButton(onClick = viewModel::toggleFavorite) { Text("★ Favorite") }
                if (state.busy) TextButton(onClick = viewModel::cancel) { Text("Stop") }
            }

            state.rdapResult?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("RDAP", style = MaterialTheme.typography.titleSmall)
                    PlainRow("Type", result.kind)
                    PlainRow("Handle", result.handle ?: "NOT DISCOVERED")
                    PlainRow("Name", result.name ?: "NOT DISCOVERED")
                    if (result.startAddress != null || result.endAddress != null) {
                        PlainRow(
                            "Range",
                            (result.startAddress ?: "?") + " – " + (result.endAddress ?: "?"),
                            monospace = true,
                        )
                    }
                    PlainRow("Country", result.country ?: "NOT DISCOVERED")
                    result.status.takeIf { it.isNotEmpty() }?.let {
                        PlainRow("Status", it.joinToString(", "))
                    }
                    result.entities.take(8).forEach { Text("entity  " + it, style = MonoSmallTextStyle) }
                    result.events.take(8).forEach { Text("event   " + it, style = MonoSmallTextStyle) }
                    PlainRow("Source", result.source, copyable = true)
                    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            state.geoResult?.let { geo ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("IP geolocation", style = MaterialTheme.typography.titleSmall)
                    PlainRow("IP", geo.ip ?: "NOT DISCOVERED", monospace = true, copyable = true)
                    PlainRow("ASN", geo.asn ?: "NOT DISCOVERED")
                    PlainRow("Organization", geo.organization ?: "NOT DISCOVERED")
                    PlainRow(
                        "Location",
                        listOfNotNull(geo.city, geo.region, geo.country).joinToString(", ")
                            .ifBlank { "NOT DISCOVERED" },
                    )
                    PlainRow("Timezone", geo.timezone ?: "NOT DISCOVERED")
                    if (geo.latitude != null && geo.longitude != null) {
                        PlainRow("Coordinates", geo.latitude.toString() + ", " + geo.longitude)
                        OutlinedButton(
                            onClick = {
                                val uri = Uri.parse(
                                    "geo:" + geo.latitude + "," + geo.longitude +
                                        "?q=" + geo.latitude + "," + geo.longitude,
                                )
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text("Open on map") }
                    }
                    PlainRow("Source", geo.source)
                    geo.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            state.identity?.let { identity ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("Current public network", style = MaterialTheme.typography.titleSmall)
                    PlainRow("Public IP", identity.ip ?: "NOT DISCOVERED", monospace = true, copyable = true)
                    PlainRow("ASN", identity.asn ?: "NOT DISCOVERED")
                    PlainRow("Organization", identity.organization ?: "NOT DISCOVERED")
                    PlainRow(
                        "Location",
                        listOfNotNull(identity.city, identity.region, identity.country)
                            .joinToString(", ").ifBlank { "NOT DISCOVERED" },
                    )
                    PlainRow("Cloudflare colo", identity.colo ?: "NOT DISCOVERED")
                }
            }

            if (state.persistent.favoriteTargets.isNotEmpty()) {
                Text(
                    "Favorites: " + state.persistent.favoriteTargets.joinToString(" · "),
                    style = MonoSmallTextStyle,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverySection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    SectionCard(
        title = "Bonjour / mDNS and UPnP / SSDP browser",
        subtitle = "Browse local services independently of a full device scan",
    ) {
        Column {
            BusyButton(state.busy, "Browse services", viewModel::discoverServices, viewModel::cancel)

            if (state.mdnsRecords.isNotEmpty()) {
                Text(
                    "Bonjour / mDNS (" + state.mdnsRecords.size + ")",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                state.mdnsRecords.forEach { record ->
                    Text(
                        (record.address.hostAddress ?: "?") + ":" + record.port + "  " +
                            record.serviceName + "  " + record.serviceType,
                        style = MonoSmallTextStyle,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    record.hostname?.let {
                        Text("  host " + it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.ssdpRecords.isNotEmpty()) {
                Text(
                    "UPnP / SSDP (" + state.ssdpRecords.size + ")",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                state.ssdpRecords.forEach { record ->
                    Text(
                        (record.address.hostAddress ?: "?") + "  " +
                            (record.server ?: record.searchTarget ?: "UPnP device"),
                        style = MonoSmallTextStyle,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    record.location?.let { Text("  " + it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (!state.busy && state.mdnsRecords.isEmpty() && state.ssdpRecords.isEmpty()) {
                Text(
                    "No browse has run yet, or no device answered. Silence is not treated as offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LegacySection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    SectionCard(
        title = "Legacy / managed network discovery",
        subtitle = "NetBIOS Node Status, LLMNR and read-only SNMP v2c",
    ) {
        Column {
            NoticeBanner(
                "SNMP only performs GET for sysName, sysDescr and sysUpTime. It never sends SET " +
                    "and never tries community strings automatically.",
                NoticeTone.INFO,
            )
            OutlinedTextField(
                value = state.legacyTarget,
                onValueChange = viewModel::setLegacyTarget,
                label = { Text("Target IP or hostname") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = state.llmnrName,
                onValueChange = viewModel::setLlmnrName,
                label = { Text("LLMNR hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = state.snmpCommunity,
                onValueChange = viewModel::setSnmpCommunity,
                label = { Text("SNMP v2c community") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::runNetBios, enabled = !state.busy) { Text("NetBIOS") }
                OutlinedButton(onClick = viewModel::runLlmnr, enabled = !state.busy) { Text("LLMNR") }
                OutlinedButton(onClick = viewModel::runSnmp, enabled = !state.busy) { Text("SNMP GET") }
                if (state.busy) TextButton(onClick = viewModel::cancel) { Text("Stop") }
            }

            state.netBios?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("NetBIOS", style = MaterialTheme.typography.titleSmall)
                    PlainRow("Address", result.address, monospace = true)
                    PlainRow("Names", result.names.joinToString(", ").ifBlank { "NOT DISCOVERED" })
                    PlainRow("Volunteered MAC", result.macAddress ?: "NOT DISCOVERED", monospace = true)
                }
            }
            state.llmnr?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("LLMNR", style = MaterialTheme.typography.titleSmall)
                    PlainRow("Name", result.name)
                    result.addresses.forEach { Text(it, style = MonoSmallTextStyle) }
                }
            }
            state.snmp?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text("SNMP", style = MaterialTheme.typography.titleSmall)
                    result.values.forEach { value ->
                        PlainRow(value.label, value.value, copyable = true)
                    }
                    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun CellularSection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    SectionCard(
        title = "Cellular network",
        subtitle = "Operator, radio generation, MCC/MNC and signal",
    ) {
        Column {
            Button(onClick = viewModel::refreshCellular) { Text("Refresh") }
            val cellular = state.cellular
            if (cellular == null) {
                Text(
                    "Tap refresh to read TelephonyManager.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (!cellular.available) {
                NoticeBanner(cellular.note ?: "Cellular telephony is unavailable.", NoticeTone.INFO)
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    PlainRow("Operator", cellular.operatorName ?: "NOT DISCOVERED")
                    PlainRow("MCC", cellular.mcc ?: "NOT DISCOVERED")
                    PlainRow("MNC", cellular.mnc ?: "NOT DISCOVERED")
                    PlainRow("Radio", cellular.networkType ?: "NOT DISCOVERED")
                    PlainRow(
                        "Roaming",
                        cellular.isRoaming?.let { if (it) "Yes" else "No" } ?: "NOT DISCOVERED",
                    )
                    PlainRow("Signal level", cellular.signalLevel?.toString() ?: "NOT DISCOVERED")
                    PlainRow(
                        "Signal",
                        cellular.signalDbm?.let { it.toString() + " dBm" } ?: "NOT DISCOVERED",
                    )
                    cellular.signalDetails.forEach { Text(it, style = MonoSmallTextStyle) }
                    cellular.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorSection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    SectionCard(
        title = "Host monitor",
        subtitle = "Periodic TCP reachability with state-change alerts",
    ) {
        Column {
            NoticeBanner(
                "Android WorkManager has a 15-minute minimum periodic interval and is inexact. " +
                    "A refused TCP connection still proves the host responded; NetScope keeps it " +
                    "separate from an open port.",
                NoticeTone.INFO,
            )
            OutlinedTextField(
                value = state.monitorLabel,
                onValueChange = viewModel::setMonitorLabel,
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = state.monitorHost,
                onValueChange = viewModel::setMonitorHost,
                label = { Text("Host or IP") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.monitorPort,
                    onValueChange = viewModel::setMonitorPort,
                    label = { Text("TCP port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.monitorIntervalMinutes,
                    onValueChange = viewModel::setMonitorInterval,
                    label = { Text("Minutes") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = viewModel::addMonitor,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Start monitoring") }

            if (state.persistent.monitors.isNotEmpty()) {
                Text(
                    "Scheduled monitors",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                state.persistent.monitors.forEach { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(target.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                target.host + ":" + target.port + " · about every " +
                                    target.intervalMinutes + " min",
                                style = MonoSmallTextStyle,
                            )
                        }
                        TextButton(onClick = { viewModel.removeMonitor(target.id) }) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventorySection(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupToWrite by remember { mutableStateOf<String?>(null) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val data = backupToWrite
        backupToWrite = null
        if (uri != null && data != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data) }
                    ?: error("Could not open the destination.")
            }.onSuccess {
                Toast.makeText(context, "Backup exported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    val text = it.readText()
                    require(text.length <= 2 * 1024 * 1024) { "Backup is too large." }
                    text
                } ?: error("Could not open the backup.")
            }.onSuccess(viewModel::importBackup)
                .onFailure {
                    Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard(
            title = "Manual networks",
            subtitle = "Keep devices that cannot be discovered automatically",
        ) {
            Column {
                OutlinedTextField(
                    value = state.networkName,
                    onValueChange = viewModel::setNetworkName,
                    label = { Text("Network name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.networkCidr,
                    onValueChange = viewModel::setNetworkCidr,
                    label = { Text("CIDR (optional)") },
                    placeholder = { Text("10.0.7.0/24") },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = state.networkGateway,
                    onValueChange = viewModel::setNetworkGateway,
                    label = { Text("Gateway (optional)") },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Button(
                    onClick = viewModel::addManualNetwork,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Add network") }

                state.persistent.manualNetworks.forEach { network ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(network.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(network.cidr, network.gateway).joinToString(" · "),
                                style = MonoSmallTextStyle,
                            )
                        }
                        TextButton(onClick = { viewModel.removeManualNetwork(network.id) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        SectionCard(title = "Manual devices") {
            Column {
                if (state.persistent.manualNetworks.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = state.selectedNetworkId == null,
                            onClick = { viewModel.selectManualNetwork(null) },
                            label = { Text("Unassigned") },
                        )
                        state.persistent.manualNetworks.forEach { network ->
                            FilterChip(
                                selected = state.selectedNetworkId == network.id,
                                onClick = { viewModel.selectManualNetwork(network.id) },
                                label = { Text(network.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.deviceName,
                    onValueChange = viewModel::setDeviceName,
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = state.deviceAddress,
                    onValueChange = viewModel::setDeviceAddress,
                    label = { Text("IP / hostname") },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = state.deviceMac,
                    onValueChange = viewModel::setDeviceMac,
                    label = { Text("MAC (optional)") },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Button(
                    onClick = viewModel::addManualDevice,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Add device") }

                state.persistent.manualDevices.forEach { device ->
                    val networkName = state.persistent.manualNetworks
                        .firstOrNull { it.id == device.networkId }?.name
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                device.address +
                                    (networkName?.let { " · " + it } ?: "") +
                                    (device.mac?.let { " · " + it } ?: ""),
                                style = MonoSmallTextStyle,
                            )
                        }
                        TextButton(onClick = { viewModel.removeManualDevice(device.id) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        SectionCard(
            title = "Local backup / restore",
            subtitle = "Manual inventory, favorites and speed-test history",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            backupToWrite = viewModel.exportBackup()
                            createBackup.launch("netscope-toolkit-backup.json")
                        }
                    },
                ) { Text("Export JSON") }
                OutlinedButton(
                    onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain")) },
                ) { Text("Restore JSON") }
            }
            Text(
                "Observed scan evidence remains in the Room history database and is not overwritten " +
                    "by a toolkit backup. This prevents imported user data from masquerading as a scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
