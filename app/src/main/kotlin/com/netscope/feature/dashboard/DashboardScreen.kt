package com.netscope.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.HealthStatus
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.NetworkTopology
import com.netscope.core.model.SubnetRelation
import com.netscope.core.model.SignalQuality
import com.netscope.core.model.WifiConnectionInfo
import com.netscope.core.ui.EvidenceRow
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun DashboardScreen(
    onOpenDevices: () -> Unit,
    onOpenSubnets: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNetworks: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runHealthCheck() }

    NetScopeScreen(
        title = "NetScope",
        subtitle = state.network?.transportLabel ?: "No active network",
        actions = {
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh network state")
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (state.demoMode) {
                item {
                    NoticeBanner(
                        text = "Demo mode is on. Everything shown is fabricated sample data, not a " +
                            "measurement of a real network.",
                        tone = NoticeTone.WARNING,
                    )
                }
            }

            item { ConnectionCard(state.network, state.wifi) }

            if (state.topology.localCidr != null || state.topology.subnets.isNotEmpty()) {
                item { TopologyCard(state.topology) }
            }

            state.network?.let { network ->
                item { AddressingCard(network) }
            }

            item {
                WifiCard(state.wifi, onOpenWifi)
            }

            item {
                PublicIpCard(
                    state = state,
                    onLookup = viewModel::lookupPublicIp,
                )
            }

            item { HealthCard(state, viewModel::runHealthCheck) }

            item {
                ActionsCard(
                    onOpenDevices = onOpenDevices,
                    onOpenSubnets = onOpenSubnets,
                    onOpenWifi = onOpenWifi,
                    onOpenTools = onOpenTools,
                    onOpenHistory = onOpenHistory,
                    onOpenNetworks = onOpenNetworks,
                )
            }

            val missingPermissions = state.permissions.filterNot { it.isGranted }
            if (missingPermissions.isNotEmpty()) {
                item {
                    SectionCard(title = "Permissions") {
                        Column {
                            missingPermissions.forEach { requirement ->
                                Text(
                                    requirement.title,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    requirement.rationale,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            Text(
                                "NetScope stays usable with every optional permission denied — the " +
                                    "affected fields are labelled PERMISSION REQUIRED rather than " +
                                    "left blank.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(network: NetworkSnapshot?, wifi: WifiConnectionInfo?) {
    val status = LocalStatusColors.current
    SectionCard(
        title = wifi?.ssid?.value ?: network?.interfaceName?.value ?: "Not connected",
        subtitle = network?.transportLabel,
        trailing = {
            val validated = network?.hasValidatedInternet?.value
            val (text, background, foreground) = when (validated) {
                true -> Triple("INTERNET OK", status.reachable, status.onReachable)
                false -> Triple("NO INTERNET", status.warning, status.onWarning)
                null -> Triple("UNKNOWN", status.unknown, status.onUnknown)
            }
            StatusPill(text, background, foreground)
        },
    ) {
        Column {
            if (network == null) {
                Text(
                    "Android reports no active network. Connect to Wi-Fi or Ethernet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                EvidenceRow("Interface", network.interfaceName, monospace = true)
                PlainRow("Transport", network.transportLabel)
                EvidenceRow("Validated internet", network.hasValidatedInternet) {
                    if (it) "Yes" else "No"
                }
                EvidenceRow("Metered", network.isMetered) { if (it) "Yes" else "No" }
                PlainRow("Default network", if (network.isDefaultNetwork) "Yes" else "No")
                if (network.isVpn) {
                    PlainRow("VPN", "This network is a VPN transport")
                }
            }
        }
    }
}

@Composable
private fun AddressingCard(network: NetworkSnapshot) {
    SectionCard(
        title = "Addressing",
        subtitle = "Source: LinkProperties for this Network object",
    ) {
        Column {
            val primary = network.primaryIpv4
            PlainRow(
                "IPv4",
                primary?.toCanonicalString() ?: "NOT DISCOVERED",
                monospace = true,
                copyable = primary != null,
            )
            PlainRow(
                "Subnet (CIDR)",
                network.primaryIpv4Cidr?.toString() ?: "NOT DISCOVERED",
                monospace = true,
                copyable = network.primaryIpv4Cidr != null,
            )
            PlainRow(
                "Subnet mask",
                network.primaryIpv4Cidr?.maskAddress?.toCanonicalString() ?: "NOT DISCOVERED",
                monospace = true,
            )
            PlainRow(
                "Gateway",
                network.ipv4Gateway?.toCanonicalString() ?: "NOT DISCOVERED",
                monospace = true,
                copyable = network.ipv4Gateway != null,
            )
            PlainRow(
                "DNS",
                network.dnsServers.joinToString(", ") { it.toCanonicalString() }
                    .ifEmpty { "NOT DISCOVERED" },
                monospace = true,
            )
            if (network.privateDnsActive) {
                PlainRow("Private DNS", network.privateDnsServerName ?: "Active (opportunistic)")
            }
            EvidenceRow("MTU", network.mtu) { "$it bytes" }
            network.httpProxy?.let { PlainRow("Proxy", it) }

            if (network.ipv6Addresses.isEmpty()) {
                PlainRow("IPv6", "NOT DISCOVERED")
            } else {
                network.ipv6Addresses.forEachIndexed { index, (address, prefix) ->
                    PlainRow(
                        "IPv6 (${address.scope.name.lowercase().replace('_', ' ')})",
                        "${address.toCanonicalString()}/$prefix",
                        monospace = true,
                        copyable = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiCard(wifi: WifiConnectionInfo?, onOpenWifi: () -> Unit) {
    SectionCard(
        title = "Wi-Fi",
        subtitle = "Source: WifiManager",
        trailing = {
            OutlinedButton(onClick = onOpenWifi) { Text("Analyzer") }
        },
    ) {
        if (wifi == null) {
            Text(
                "Not associated with a Wi-Fi network, or Wi-Fi is off.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column {
                EvidenceRow("SSID", wifi.ssid)
                EvidenceRow("BSSID", wifi.bssid, monospace = true, copyable = true)
                EvidenceRow("Signal", wifi.rssiDbm) { rssi ->
                    "$rssi dBm — ${SignalQuality.forRssi(rssi).label}"
                }
                wifi.channel?.let { channel ->
                    PlainRow("Channel", "$channel (${wifi.band?.label ?: "unknown band"})")
                }
                EvidenceRow("PHY link speed", wifi.linkSpeedMbps) { "$it Mbps" }
                EvidenceRow("TX / RX", wifi.txLinkSpeedMbps) { "$it Mbps transmit" }
                EvidenceRow("Wi-Fi generation", wifi.wifiStandard)
                EvidenceRow("Security", wifi.securityType)
                EvidenceRow("This device's MAC", wifi.ownMacAddress) { it.toString() }
            }
        }
    }
}

@Composable
private fun PublicIpCard(state: DashboardUiState, onLookup: () -> Unit) {
    SectionCard(title = "Public IP address") {
        Column {
            Text(
                "Finding your public address requires contacting a server outside your network. " +
                    "NetScope will send one request to ${DashboardViewModel.PUBLIC_IP_ENDPOINT} and " +
                    "nothing else. No device inventory, SSID or local address is included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            EvidenceRow("Public IP", state.publicIp, monospace = true, copyable = true)
            OutlinedButton(onClick = onLookup, modifier = Modifier.padding(top = 6.dp)) {
                Text("Look up public IP")
            }
        }
    }
}

@Composable
private fun HealthCard(state: DashboardUiState, onRun: () -> Unit) {
    val status = LocalStatusColors.current
    SectionCard(
        title = "Network health",
        subtitle = "Each row shows the measurement behind it. There is no single score.",
        trailing = {
            if (state.isLoadingHealth) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRun) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-run health checks")
                }
            }
        },
    ) {
        val panel = state.health
        if (panel == null) {
            Text(
                if (state.isLoadingHealth) "Running checks…" else "Not checked yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column {
                panel.rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.label, style = MaterialTheme.typography.bodyMedium)
                                val (background, foreground) = when (row.status) {
                                    HealthStatus.PASS -> status.reachable to status.onReachable
                                    HealthStatus.WARN -> status.warning to status.onWarning
                                    HealthStatus.FAIL -> status.failed to status.onFailed
                                    HealthStatus.UNKNOWN -> status.unknown to status.onUnknown
                                }
                                StatusPill(
                                    row.value,
                                    background,
                                    foreground,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Text(
                                row.evidence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    onOpenDevices: () -> Unit,
    onOpenSubnets: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNetworks: () -> Unit,
) {
    SectionCard(title = "Go to") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDevices, modifier = Modifier.weight(1f)) { Text("Scan network") }
                OutlinedButton(onClick = onOpenSubnets, modifier = Modifier.weight(1f)) { Text("Subnets") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenWifi, modifier = Modifier.weight(1f)) { Text("Wi-Fi") }
                OutlinedButton(onClick = onOpenTools, modifier = Modifier.weight(1f)) { Text("Tools") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenNetworks, modifier = Modifier.weight(1f)) { Text("Routes") }
                OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) { Text("History") }
            }
        }
    }
}

/**
 * The logical picture: this device, its gateway, and the subnets reachable through it.
 *
 * Every branch comes from a route Android actually exposed. Layer 2 is not shown
 * because it is not knowable from here, and a subnet with no recorded scan shows no
 * device count rather than a zero.
 */
@Composable
private fun TopologyCard(topology: NetworkTopology) {
    val status = LocalStatusColors.current
    SectionCard(
        title = "Network topology",
        subtitle = "Layer 3 only, derived from the routes Android exposes",
    ) {
        Column {
            Text(
                text = "YOU",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = topology.localCidr?.let {
                    "${topology.localAddress?.toCanonicalString()}/${it.prefixLength}"
                } ?: (topology.localAddress?.toCanonicalString() ?: "No IPv4 address"),
                style = MonoTextStyle,
            )
            topology.interfaceName?.let { name ->
                Text(
                    text = name + (topology.transportLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (topology.defaultGateway != null) {
                Text(
                    text = "│",
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "▼",
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = topology.defaultGateway!!.toCanonicalString(),
                    style = MonoTextStyle,
                )
                Text(
                    text = "DEFAULT GATEWAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (topology.subnets.isEmpty()) {
                Text(
                    text = "Android exposed no subnet routes for this network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@SectionCard
            }

            Column(modifier = Modifier.padding(top = 8.dp)) {
                topology.subnets.forEachIndexed { index, node ->
                    val isLast = index == topology.subnets.lastIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isLast) "└──" else "├──",
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = " ${node.cidr}",
                            style = MonoTextStyle,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        val (background, foreground) = when (node.relation) {
                            SubnetRelation.CONNECTED -> status.reachable to status.onReachable
                            SubnetRelation.ROUTED -> status.informational to status.onInformational
                            SubnetRelation.UNKNOWN -> status.unknown to status.onUnknown
                        }
                        StatusPill(node.relation.label.uppercase(), background, foreground)
                    }
                    Text(
                        text = "      " + (
                            node.deviceCount?.let { "$it device(s) in the last scan" }
                                ?: "not scanned yet"
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "      " + node.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}
