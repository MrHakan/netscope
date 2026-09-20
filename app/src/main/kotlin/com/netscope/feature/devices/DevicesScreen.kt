package com.netscope.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.HostState
import com.netscope.core.model.ProbeType
import com.netscope.core.model.ScanProfile
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun DevicesScreen(
    onOpenDevice: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val progress = state.scan.progress

    NetScopeScreen(
        title = "Devices",
        subtitle = if (progress.isRunning) {
            "Scanning ${progress.target} — ${progress.probed}/${progress.total} — " +
                "${progress.found} found — ${progress.phase.label}"
        } else {
            "${state.scan.devices.size} device(s) from the last scan"
        },
    ) { modifier ->
        Column(modifier = modifier.fillMaxWidth()) {
            ScanControls(
                state = state,
                onTargetChange = viewModel::setTarget,
                onProfileChange = viewModel::setScanProfile,
                onStart = viewModel::startScan,
                onStop = viewModel::stopScan,
            )

            if (progress.isRunning) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .semantics {
                            contentDescription =
                                "Scan progress ${(progress.fraction * 100).toInt()} percent"
                        },
                )
            }

            state.scan.error?.let { error ->
                NoticeBanner(
                    text = error,
                    tone = NoticeTone.ERROR,
                    modifier = Modifier.padding(12.dp),
                )
            }

            progress.message?.let { message ->
                NoticeBanner(
                    text = message,
                    tone = if (state.scan.isDemoData) NoticeTone.WARNING else NoticeTone.INFO,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            FiltersRow(state, viewModel::setQuery, viewModel::setFilter)

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visibleDevices, key = { it.key }) { device ->
                    DeviceCard(device = device, onClick = { onOpenDevice(device.key) })
                }
                if (state.visibleDevices.isEmpty()) {
                    item {
                        Text(
                            text = if (state.scan.devices.isEmpty()) {
                                "No scan results yet. Choose a target and start a scan."
                            } else {
                                "No devices match the current search or filter."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    state.pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text("Scan ${confirmation.target}?") },
            text = {
                Text(
                    "This range contains ${"%,d".format(confirmation.hostCount)} usable host " +
                        "addresses. Probing all of them will take a long time, generate a great " +
                        "deal of traffic and use significant battery. Smart Scan will still " +
                        "prioritise likely hosts first.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingScan) { Text("Scan anyway") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) { Text("Cancel") }
            },
        )
    }

    state.scopeWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text("Scanning outside private address space") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingScan) {
                    Text("I am authorised — scan")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ScanControls(
    state: DevicesUiState,
    onTargetChange: (String) -> Unit,
    onProfileChange: (ScanProfile) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = state.target,
            onValueChange = onTargetChange,
            label = { Text("Target range (CIDR)") },
            placeholder = { Text("10.0.2.0/24") },
            singleLine = true,
            isError = state.targetError != null,
            supportingText = state.targetError?.let { { Text(it) } },
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
            ScanProfile.entries.filter { it != ScanProfile.CUSTOM }.forEach { profile ->
                FilterChip(
                    selected = state.scanProfile == profile,
                    onClick = { onProfileChange(profile) },
                    label = { Text(profile.label) },
                )
            }
        }
        Text(
            text = state.scanProfile.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.scan.progress.isRunning) {
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("STOP") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("SCAN NETWORK") }
            }
        }
    }
}

@Composable
private fun FiltersRow(
    state: DevicesUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (DeviceFilter) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search IP, name, vendor, MAC, service or port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DeviceFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (device.isNew) {
                    StatusPill("NEW", status.reachable, status.onReachable)
                }
            }
            Text(
                text = device.ipv4?.toCanonicalString() ?: device.key,
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (stateText, background, foreground) = when (device.state) {
                    HostState.RESPONDING -> Triple("RESPONDING", status.reachable, status.onReachable)
                    HostState.ANNOUNCED -> Triple("ANNOUNCED", status.informational, status.onInformational)
                    HostState.NO_RESPONSE -> Triple("NO RESPONSE", status.warning, status.onWarning)
                    HostState.NOT_PROBED -> Triple("NOT PROBED", status.unknown, status.onUnknown)
                }
                StatusPill(stateText, background, foreground)

                device.latencyMillis?.let { latency ->
                    val probeLabel = when (device.probeType) {
                        ProbeType.ICMP -> "ICMP"
                        ProbeType.TCP_CONNECT -> "TCP connect"
                        ProbeType.FALLBACK -> "fallback"
                        null -> "unknown probe"
                    }
                    StatusPill(
                        "%.1f ms · %s".format(latency, probeLabel),
                        status.unknown,
                        status.onUnknown,
                    )
                }

                device.deviceType.value?.let { type ->
                    StatusPill(
                        if (device.deviceType.isInferred) {
                            "${type.name} (inferred)"
                        } else {
                            type.name
                        },
                        status.warning.takeIf { device.deviceType.isInferred } ?: status.unknown,
                        status.onWarning.takeIf { device.deviceType.isInferred } ?: status.onUnknown,
                    )
                }

                if (device.services.isNotEmpty()) {
                    StatusPill(
                        "${device.services.size} service(s)",
                        status.unknown,
                        status.onUnknown,
                    )
                }
            }
        }
    }
}
