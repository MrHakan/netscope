package com.netscope.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.feature.common.NetScopeScreen

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NetScopeScreen(title = "Settings", onBack = onBack) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(title = "Scanning") {
                    Column {
                        Text("Performance profile", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PerformanceProfile.entries.forEach { profile ->
                                FilterChip(
                                    selected = state.settings.performanceProfile == profile,
                                    onClick = { viewModel.setPerformanceProfile(profile) },
                                    label = { Text(profile.label) },
                                )
                            }
                        }
                        val active = state.settings.performanceProfile
                        Text(
                            "Up to ${active.maxConcurrency} probes in flight, " +
                                "${active.probeTimeoutMillis} ms per probe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingSwitch(
                            title = "Read service banners",
                            description = "Reads a short, size-capped banner from open ports. " +
                                "Banners are sanitised before display and nothing returned by a " +
                                "remote device is ever executed or rendered as markup.",
                            checked = state.settings.bannerGrabbingEnabled,
                            onCheckedChange = viewModel::setBannerGrabbing,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Privacy") {
                    Column {
                        SettingSwitch(
                            title = "Allow public IP lookup",
                            description = "When off, NetScope contacts nothing outside your local " +
                                "network. When on, the dashboard can send a single request to " +
                                "api.ipify.org, and only when you tap the button.",
                            checked = state.settings.publicIpLookupEnabled,
                            onCheckedChange = viewModel::setPublicIpLookup,
                        )
                        SettingSwitch(
                            title = "New device notifications",
                            description = "Notify when a scan finds a device that was not seen " +
                                "before on this network. Requires the notification permission.",
                            checked = state.settings.newDeviceNotificationsEnabled,
                            onCheckedChange = viewModel::setNewDeviceNotifications,
                        )
                        Text(
                            "NetScope has no account, no cloud service and no analytics. Scan " +
                                "results, SSIDs, hostnames and device inventories stay on this " +
                                "device and are excluded from cloud backup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Developer") {
                    Column {
                        SettingSwitch(
                            title = "Demo mode",
                            description = "Replaces scan results with a fixed sample topology so " +
                                "the app can be explored without a LAN. Demo data is always " +
                                "marked as such and can never be mistaken for a real scan.",
                            checked = state.settings.demoModeEnabled,
                            onCheckedChange = viewModel::setDemoMode,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Capability level") {
                    Column {
                        PlainRow("Mode", state.capabilityLabel)
                        PlainRow(
                            "Unprivileged ICMP",
                            if (state.icmpAvailable) {
                                "Available — latency figures are real round trips"
                            } else {
                                "Unavailable — probes fall back to TCP connect"
                            },
                        )
                        PlainRow(
                            "Local network permission",
                            state.localNetworkPermission ?: "Not required on this Android version",
                        )
                        PlainRow("Offline OUI entries", state.ouiEntryCount.toString())
                    }
                }
            }

            item {
                SectionCard(title = "About") {
                    Column {
                        PlainRow("Version", state.versionName)
                        Text(
                            "NetScope reports what it can observe, marks what it infers, and names " +
                                "what Android will not allow. It performs no exploitation, " +
                                "credential testing or vulnerability probing of any kind.",
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
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
