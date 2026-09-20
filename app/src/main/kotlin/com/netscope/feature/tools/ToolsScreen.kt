package com.netscope.feature.tools

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.PortState
import com.netscope.core.network.DnsRecordType
import com.netscope.core.network.PortScanner
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun ToolsScreen(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NetScopeScreen(
        title = "Tools",
        subtitle = state.selectedTool.label,
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
                    Tool.entries.forEach { tool ->
                        FilterChip(
                            selected = state.selectedTool == tool,
                            onClick = { viewModel.selectTool(tool) },
                            label = { Text(tool.label) },
                        )
                    }
                }
            }

            state.error?.let { error ->
                item { NoticeBanner(text = error, tone = NoticeTone.ERROR) }
            }

            item {
                when (state.selectedTool) {
                    Tool.PING -> PingTool(state, viewModel)
                    Tool.TCP_PING -> TcpPingTool(state, viewModel)
                    Tool.TRACEROUTE -> TracerouteTool(state, viewModel)
                    Tool.DNS -> DnsTool(state, viewModel)
                    Tool.PORT_SCAN -> PortScanTool(state, viewModel)
                    Tool.SUBNET_CALCULATOR -> SubnetCalculatorTool(state, viewModel)
                    Tool.ROUTE_DIAGNOSTICS -> RouteDiagnosticsTool(state, viewModel)
                    Tool.WAKE_ON_LAN -> WakeOnLanTool(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun RunButton(busy: Boolean, label: String, onRun: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (busy) {
            OutlinedButton(onClick = onCancel) { Text("Stop") }
            CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onRun) { Text(label) }
        }
    }
}

@Composable
private fun PingStatisticsBlock(state: ToolsUiState) {
    state.pingStatistics?.let { stats ->
        Column(modifier = Modifier.padding(top = 10.dp)) {
            PlainRow("Probe type", stats.probeLabel)
            PlainRow("Sent / received", "${stats.sent} / ${stats.received}")
            PlainRow("Packet loss", "%.0f%%".format(stats.lossPercent))
            PlainRow(
                "min / avg / max",
                if (stats.avgMillis != null) {
                    "%.1f / %.1f / %.1f ms".format(stats.minMillis, stats.avgMillis, stats.maxMillis)
                } else {
                    "No replies"
                },
                monospace = true,
            )
            PlainRow(
                "Jitter",
                stats.jitterMillis?.let { "%.1f ms".format(it) } ?: "Needs at least two replies",
            )
            Text(
                state.pingResults.joinToString(" ") { rtt ->
                    rtt?.let { "%.0f".format(it) } ?: "*"
                },
                style = MonoSmallTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    state.pingProbeDetail?.let { detail ->
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PingTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(
        title = "Ping",
        subtitle = if (state.icmpAvailable) {
            "Unprivileged ICMP is available on this device"
        } else {
            "Unprivileged ICMP is unavailable — results will use TCP connect and say so"
        },
    ) {
        Column {
            OutlinedTextField(
                value = state.pingHost,
                onValueChange = viewModel::setPingHost,
                label = { Text("Host or IP address") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            RunButton(state.busy, "Ping", { viewModel.runPing() }, viewModel::cancel)
            PingStatisticsBlock(state)
        }
    }
}

@Composable
private fun TcpPingTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(
        title = "TCP ping",
        subtitle = "Connection latency to a host and port — the right tool when ICMP is filtered",
    ) {
        Column {
            OutlinedTextField(
                value = state.tcpHost,
                onValueChange = viewModel::setTcpHost,
                label = { Text("Host or IP address") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.tcpPort,
                onValueChange = viewModel::setTcpPort,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            RunButton(state.busy, "TCP ping", { viewModel.runTcpPing() }, viewModel::cancel)
            PingStatisticsBlock(state)
        }
    }
}

@Composable
private fun TracerouteTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(title = "Traceroute") {
        Column {
            NoticeBanner(
                text = "Android does not expose the socket error queue to apps, so the addresses of " +
                    "intermediate routers cannot be read without root. NetScope measures how many " +
                    "hops away the destination is, which it can establish accurately, and shows " +
                    "unidentified hops as * rather than inventing them.",
                tone = NoticeTone.INFO,
            )
            OutlinedTextField(
                value = state.tracerouteHost,
                onValueChange = viewModel::setTracerouteHost,
                label = { Text("Host or IP address") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            RunButton(state.busy, "Trace", viewModel::runTraceroute, viewModel::cancel)

            if (state.tracerouteHops.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    state.tracerouteHops.forEach { hop ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(hop.ttl.toString().padStart(2), style = MonoSmallTextStyle)
                            Text(
                                hop.displayAddress,
                                style = MonoSmallTextStyle,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                hop.rttMillis.joinToString(" ") { rtt ->
                                    rtt?.let { "%.0fms".format(it) } ?: "*"
                                },
                                style = MonoSmallTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.tracerouteResult?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    PlainRow("Method", result.method.label)
                    PlainRow(
                        "Destination reached",
                        result.destinationReachedAtTtl?.let { "Yes, at $it hop(s)" }
                            ?: "Not within the hop limit",
                    )
                    Text(
                        result.method.caveat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(title = "DNS lookup") {
        Column {
            if (!state.dnsSupportsAllTypes) {
                NoticeBanner(
                    text = "This Android version predates DnsResolver, so only address lookups are " +
                        "possible. MX, NS, TXT and CNAME queries need Android 10 or newer.",
                    tone = NoticeTone.INFO,
                )
            }
            OutlinedTextField(
                value = state.dnsQuery,
                onValueChange = viewModel::setDnsQuery,
                label = { Text("Name to look up, or an IP address for PTR") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DnsRecordType.entries.forEach { type ->
                    FilterChip(
                        selected = state.dnsRecordType == type,
                        onClick = { viewModel.setDnsRecordType(type) },
                        label = { Text(type.name) },
                    )
                }
            }
            RunButton(state.busy, "Look up", viewModel::runDnsLookup, viewModel::cancel)

            state.dnsResult?.let { result ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    PlainRow("Query", "${result.query} (${result.type.name})", monospace = true)
                    PlainRow("Duration", "${result.durationMillis} ms")
                    result.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    result.answers.forEach { answer ->
                        Text(
                            "${answer.type.name.padEnd(6)} ${answer.value}",
                            style = MonoSmallTextStyle,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Text(
                        result.resolverNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PortScanTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    val status = LocalStatusColors.current
    SectionCard(title = "Port scanner") {
        Column {
            Text(
                "Reports which ports accept a connection. NetScope performs no credential testing " +
                    "and no vulnerability probing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.portHost,
                onValueChange = viewModel::setPortHost,
                label = { Text("Host or IP address") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PortScanner.Profile.entries.forEach { profile ->
                    FilterChip(
                        selected = state.portProfile == profile,
                        onClick = { viewModel.setPortProfile(profile) },
                        label = { Text(profile.label) },
                    )
                }
            }
            if (state.portProfile == PortScanner.Profile.CUSTOM) {
                OutlinedTextField(
                    value = state.portSpec,
                    onValueChange = viewModel::setPortSpec,
                    label = { Text("Ports, e.g. 22, 80, 8000-8010") },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            RunButton(state.busy, "Scan ports", viewModel::runPortScan, viewModel::cancel)

            val open = state.portResults.filter { it.state == PortState.OPEN }
            if (state.portResults.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        "${open.size} open of ${state.portResults.size} scanned",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    open.forEach { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(result.port.toString().padStart(5), style = MonoSmallTextStyle)
                            Text(
                                PortScanner.Profile.COMMON.ports
                                    .firstOrNull { it == result.port }
                                    ?.let { "" } ?: "",
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill("OPEN", status.reachable, status.onReachable)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubnetCalculatorTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(
        title = "Subnet calculator",
        subtitle = "IPv4 and IPv6, including /31 and /32",
    ) {
        Column {
            OutlinedTextField(
                value = state.calculatorInput,
                onValueChange = viewModel::setCalculatorInput,
                label = { Text("Address and prefix") },
                placeholder = { Text("192.168.1.10/24") },
                singleLine = true,
                isError = state.calculatorError != null,
                supportingText = state.calculatorError?.let { { Text(it) } },
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::calculate, modifier = Modifier.padding(top = 8.dp)) {
                Text("Calculate")
            }

            state.calculation?.let { calculation ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    PlainRow("Network", calculation.cidr, monospace = true, copyable = true)
                    PlainRow("Network address", calculation.networkAddress, monospace = true)
                    PlainRow("Subnet mask", calculation.maskAddress, monospace = true)
                    PlainRow("Wildcard", calculation.wildcardAddress, monospace = true)
                    PlainRow("Broadcast", calculation.broadcastAddress, monospace = true)
                    PlainRow("First host", calculation.firstHost, monospace = true)
                    PlainRow("Last host", calculation.lastHost, monospace = true)
                    PlainRow("Total addresses", calculation.totalAddresses)
                    PlainRow("Usable hosts", calculation.usableHosts)
                    calculation.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteDiagnosticsTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(
        title = "Route diagnostics",
        subtitle = "What Android is expected to do with a packet to this destination",
    ) {
        Column {
            OutlinedTextField(
                value = state.routeDestination,
                onValueChange = viewModel::setRouteDestination,
                label = { Text("Destination IPv4 address") },
                placeholder = { Text("10.0.7.5") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            RunButton(state.busy, "Explain", viewModel::runRouteDiagnostics, viewModel::cancel)

            state.routeExplanation?.let { explanation ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    PlainRow("Network", explanation.selectedNetworkLabel ?: "Unknown")
                    PlainRow("Interface", explanation.interfaceName ?: "NOT DISCOVERED", monospace = true)
                    PlainRow(
                        "Source address",
                        explanation.sourceAddress?.toCanonicalString() ?: "NOT DISCOVERED",
                        monospace = true,
                    )
                    PlainRow(
                        "Best matching route",
                        explanation.bestRoute?.destinationText ?: "None in the exposed route set",
                        monospace = true,
                    )
                    PlainRow(
                        "Gateway",
                        explanation.gateway?.toCanonicalString() ?: "On-link (no gateway)",
                        monospace = true,
                    )
                    PlainRow("Reverse DNS", explanation.hostname ?: "NOT DISCOVERED")
                    PlainRow(
                        "Probe",
                        if (explanation.probeResponded) {
                            "Answered in %.1f ms".format(explanation.probeLatencyMillis ?: 0.0)
                        } else {
                            "No response"
                        },
                    )
                    Text(
                        explanation.probeDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        explanation.narrative,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeOnLanTool(state: ToolsUiState, viewModel: ToolsViewModel) {
    SectionCard(title = "Wake-on-LAN") {
        Column {
            OutlinedTextField(
                value = state.wolMac,
                onValueChange = viewModel::setWolMac,
                label = { Text("Target MAC address") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.wolBroadcast,
                onValueChange = viewModel::setWolBroadcast,
                label = { Text("Broadcast address") },
                singleLine = true,
                textStyle = MonoTextStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = state.wolPort,
                onValueChange = viewModel::setWolPort,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            RunButton(state.busy, "Send magic packet", viewModel::sendWakeOnLan, viewModel::cancel)

            state.wolMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
