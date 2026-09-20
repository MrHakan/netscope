package com.netscope.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.feature.common.NetScopeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    NetScopeScreen(
        title = "History",
        subtitle = "${state.sessions.size} stored scan(s)",
        onBack = onBack,
        actions = {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear") }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(
                    title = "Network profiles",
                    subtitle = "Identified by gateway and BSSID, not by SSID alone",
                ) {
                    Column {
                        if (state.profiles.isEmpty()) {
                            Text(
                                "No networks recorded yet. Run a scan to create a profile.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.profiles.forEach { profile ->
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                                    PlainRow("Subnet", profile.subnetCidr ?: "Unknown", monospace = true)
                                    PlainRow("Gateway", profile.gatewayAddress ?: "Unknown", monospace = true)
                                    PlainRow("Last seen", formatTimestamp(profile.lastSeenEpochMillis))
                                }
                            }
                        }
                    }
                }
            }

            items(state.sessions, key = { it.id }) { session ->
                SectionCard(
                    title = session.target,
                    subtitle = formatTimestamp(session.startedAtEpochMillis),
                ) {
                    Column {
                        PlainRow("Devices found", session.devicesFound.toString())
                        PlainRow("Addresses probed", session.addressesProbed.toString())
                        PlainRow("Duration", "${session.durationMillis} ms")
                        PlainRow("Scan profile", session.scanProfile)
                        PlainRow("Performance profile", session.performanceProfile)
                        PlainRow("Peak concurrency", session.peakConcurrency.toString())
                        PlainRow(
                            "ICMP available",
                            if (session.icmpAvailable) "Yes" else "No — probes used TCP connect",
                        )
                        PlainRow("Completed", if (session.completed) "Yes" else "Stopped early")
                        if (session.isDemoData) {
                            Text(
                                "This session recorded demo data, not a real scan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        "No scans stored yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear scan history?") },
            text = {
                Text(
                    "This permanently deletes every stored scan session on this device. Network " +
                        "profiles and device labels are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    if (epochMillis <= 0) {
        "Unknown"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
