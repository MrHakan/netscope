package com.netscope.feature.networks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.RouteEntry
import com.netscope.core.ui.EvidenceRow
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun NetworksScreen(
    onBack: () -> Unit,
    viewModel: NetworksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NetScopeScreen(
        title = "Networks & routes",
        subtitle = "${state.networks.size} Network object(s) from ConnectivityManager",
        onBack = onBack,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                NoticeBanner(
                    text = "Android exposes only the routes attached to each Network object. This is " +
                        "a subset of the kernel routing table: a destination missing here may still " +
                        "be routed by the default gateway. /proc/net/route is not readable by apps.",
                )
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Filter routes and interfaces") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            items(state.networks, key = { it.networkId }) { network ->
                NetworkCard(network, state.query)
            }

            item {
                SectionCard(
                    title = "Interfaces (Linux view)",
                    subtitle = "Source: java.net.NetworkInterface — not an Android guarantee",
                ) {
                    Column {
                        Text(
                            "These come from the operating system rather than from ConnectivityManager. " +
                                "They can list interfaces Android has no Network object for, and the " +
                                "two views must not be treated as interchangeable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        state.interfaces
                            .filter { state.query.isBlank() || it.name.contains(state.query, true) }
                            .forEach { detail ->
                                Column(modifier = Modifier.padding(vertical = 5.dp)) {
                                    Row {
                                        Text(detail.name, style = MonoSmallTextStyle)
                                        Text(
                                            if (detail.isUp) "  up" else "  down",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    detail.addresses.forEach { (address, prefix) ->
                                        Text(
                                            "  $address/$prefix",
                                            style = MonoSmallTextStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    detail.mtu?.let {
                                        Text(
                                            "  MTU $it",
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
    }
}

@Composable
private fun NetworkCard(network: NetworkSnapshot, query: String) {
    val status = LocalStatusColors.current
    SectionCard(
        title = network.interfaceName.value ?: "Unnamed network",
        subtitle = network.transportLabel,
        trailing = {
            if (network.isDefaultNetwork) {
                StatusPill("DEFAULT", status.reachable, status.onReachable)
            }
        },
    ) {
        Column {
            network.ipv4Addresses.forEach { (address, prefix) ->
                PlainRow(
                    "IPv4",
                    "${address.toCanonicalString()}/$prefix",
                    monospace = true,
                    copyable = true,
                )
            }
            network.ipv6Addresses.forEach { (address, prefix) ->
                PlainRow(
                    "IPv6 (${address.scope.name.lowercase().replace('_', ' ')})",
                    "${address.toCanonicalString()}/$prefix",
                    monospace = true,
                    copyable = true,
                )
            }
            PlainRow(
                "DNS",
                network.dnsServers.joinToString(", ") { it.toCanonicalString() }
                    .ifEmpty { "NOT DISCOVERED" },
                monospace = true,
            )
            PlainRow("Private DNS", if (network.privateDnsActive) {
                network.privateDnsServerName ?: "Active (opportunistic)"
            } else {
                "Off"
            })
            EvidenceRow("MTU", network.mtu) { "$it bytes" }
            EvidenceRow("Validated internet", network.hasValidatedInternet) { if (it) "Yes" else "No" }
            EvidenceRow("Metered", network.isMetered) { if (it) "Yes" else "No" }
            network.linkDownstreamKbps?.takeIf { it > 0 }?.let {
                PlainRow("Reported downstream", "%,d kbps (an estimate from the platform)".format(it))
            }

            Text(
                "Routes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            val routes = network.routes.filter {
                query.isBlank() ||
                    it.destinationText.contains(query, true) ||
                    it.gateway?.toCanonicalString()?.contains(query, true) == true
            }
            if (routes.isEmpty()) {
                Text(
                    if (network.routes.isEmpty()) {
                        "No routes were exposed for this network."
                    } else {
                        "No routes match the filter."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                routes.forEach { route -> RouteRow(route) }
            }
        }
    }
}

@Composable
private fun RouteRow(route: RouteEntry) {
    val status = LocalStatusColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            route.destinationText,
            style = MonoSmallTextStyle,
            modifier = Modifier.weight(1.3f),
        )
        Text(
            route.gateway?.toCanonicalString() ?: "on-link",
            style = MonoSmallTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.1f),
        )
        if (route.isDefaultRoute) {
            StatusPill("DEFAULT", status.informational, status.onInformational)
        }
    }
}
