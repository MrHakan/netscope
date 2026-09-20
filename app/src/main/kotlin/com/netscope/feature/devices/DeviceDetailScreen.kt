package com.netscope.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.PortState
import com.netscope.core.ui.EvidenceRow
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.core.ui.label
import com.netscope.feature.common.NetScopeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceDetailScreen(
    deviceKey: String,
    onBack: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(deviceKey) { viewModel.load(deviceKey) }

    val device = state.device

    NetScopeScreen(
        title = device?.displayName ?: deviceKey,
        subtitle = device?.ipv4?.toCanonicalString(),
        onBack = onBack,
    ) { modifier ->
        if (device == null) {
            Text(
                "This device is not in the current scan results. Run a scan to load it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = modifier.padding(16.dp),
            )
            return@NetScopeScreen
        }

        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(title = "Overview") {
                    Column {
                        PlainRow(
                            "IPv4",
                            device.ipv4?.toCanonicalString() ?: "NOT DISCOVERED",
                            monospace = true,
                            copyable = device.ipv4 != null,
                        )
                        device.ipv6Addresses.forEach { address ->
                            PlainRow("IPv6", address.toCanonicalString(), monospace = true, copyable = true)
                        }
                        EvidenceRow("Hostname", device.hostname, monospace = true, copyable = true)
                        EvidenceRow("Advertised name", device.friendlyName)
                        EvidenceRow("MAC address", device.mac, monospace = true) { it.toString() }
                        EvidenceRow("Vendor", device.vendor)
                        EvidenceRow("Device type", device.deviceType) { it.name }
                        PlainRow("State", device.state.name.replace('_', ' '))
                        device.latencyMillis?.let { latency ->
                            PlainRow(
                                "Latency",
                                "%.1f ms measured by %s".format(
                                    latency,
                                    when (device.probeType) {
                                        com.netscope.core.model.ProbeType.ICMP -> "ICMP echo"
                                        com.netscope.core.model.ProbeType.TCP_CONNECT -> "TCP connect"
                                        com.netscope.core.model.ProbeType.FALLBACK -> "a fallback probe"
                                        null -> "an unknown probe"
                                    },
                                ),
                            )
                        }
                        PlainRow("First seen", formatTimestamp(device.firstSeenEpochMillis))
                        PlainRow("Last seen", formatTimestamp(device.lastSeenEpochMillis))
                    }
                }
            }

            item {
                SectionCard(
                    title = "Discovery",
                    subtitle = "How this device was found",
                ) {
                    Column {
                        if (device.discoverySources.isEmpty()) {
                            Text("No discovery source was recorded.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            device.discoverySources.forEach { source ->
                                PlainRow(source.label(), "Confirmed this device exists")
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Services",
                    subtitle = "${device.services.size} found",
                ) {
                    Column {
                        if (device.services.isEmpty()) {
                            Text(
                                "No services were advertised or discovered. This does not mean the " +
                                    "device offers none — only that nothing answered.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            device.services.forEach { service ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Row {
                                        Text(
                                            service.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        service.port?.let {
                                            Text("port $it", style = MonoSmallTextStyle)
                                        }
                                    }
                                    Text(
                                        (service.detail ?: "") + " Source: ${service.source.label()}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                PortsCard(
                    state = state,
                    onScan = viewModel::scanPorts,
                    onStop = viewModel::stopPortScan,
                )
            }

            item {
                SectionCard(title = "Your label") {
                    Column {
                        OutlinedTextField(
                            value = state.labelDraft,
                            onValueChange = viewModel::setLabelDraft,
                            label = { Text("Name this device") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "A label you set always takes priority over a discovered name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = viewModel::saveLabel,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Save label") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortsCard(
    state: DeviceDetailUiState,
    onScan: () -> Unit,
    onStop: () -> Unit,
) {
    val status = LocalStatusColors.current
    SectionCard(
        title = "Ports",
        subtitle = "Scanned only when you ask",
        trailing = {
            if (state.isScanningPorts) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            }
        },
    ) {
        Column {
            Text(
                "NetScope reports whether a port accepts a connection. It performs no credential " +
                    "testing and no vulnerability probing of any kind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.ports.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    state.ports.forEach { port ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                port.port.toString().padStart(5),
                                style = MonoSmallTextStyle,
                            )
                            Text(
                                state.serviceNames[port.port] ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            val (text, background, foreground) = when (port.state) {
                                PortState.OPEN -> Triple("OPEN", status.reachable, status.onReachable)
                                PortState.CLOSED -> Triple("CLOSED", status.unknown, status.onUnknown)
                                PortState.FILTERED_OR_TIMEOUT ->
                                    Triple("FILTERED / TIMEOUT", status.warning, status.onWarning)
                            }
                            StatusPill(text, background, foreground)
                        }
                        port.banner?.let { banner ->
                            Text(
                                banner,
                                style = MonoSmallTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isScanningPorts) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onScan) { Text("Scan common ports") }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    if (epochMillis <= 0) {
        "Unknown"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
    }
