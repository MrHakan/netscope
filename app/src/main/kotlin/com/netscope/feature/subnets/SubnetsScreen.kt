package com.netscope.feature.subnets

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
import com.netscope.core.ui.StatusPill
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.StepOutcome
import com.netscope.core.model.SubnetReachability
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.core.ui.label
import com.netscope.feature.common.NetScopeScreen

@Composable
fun SubnetsScreen(
    onBack: () -> Unit,
    viewModel: SubnetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current

    NetScopeScreen(
        title = "Subnet analyzer",
        subtitle = state.network?.primaryIpv4Cidr?.let { "Currently on $it" },
        onBack = onBack,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.network?.let { network ->
                item {
                    SectionCard(title = "Connected subnet") {
                        Column {
                            val cidr = network.primaryIpv4Cidr
                            PlainRow("Network", cidr?.toString() ?: "NOT DISCOVERED", monospace = true)
                            PlainRow(
                                "Usable hosts",
                                cidr?.let { "%,d".format(it.usableHostCount) } ?: "NOT DISCOVERED",
                            )
                            PlainRow(
                                "Host range",
                                if (cidr != null) {
                                    "${cidr.firstUsableHost?.toCanonicalString()} – " +
                                        "${cidr.lastUsableHost?.toCanonicalString()}"
                                } else {
                                    "NOT DISCOVERED"
                                },
                                monospace = true,
                            )
                            PlainRow(
                                "Broadcast",
                                cidr?.broadcastAddress?.toCanonicalString()
                                    ?: "NOT APPLICABLE for this prefix length",
                                monospace = true,
                            )
                            PlainRow(
                                "Gateway",
                                network.ipv4Gateway?.toCanonicalString() ?: "NOT DISCOVERED",
                                monospace = true,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Can I reach another subnet?",
                    subtitle = "Enter a target network and NetScope will show the evidence chain",
                ) {
                    Column {
                        OutlinedTextField(
                            value = state.targetText,
                            onValueChange = viewModel::setTarget,
                            label = { Text("Target subnet (CIDR)") },
                            placeholder = { Text("10.0.7.0/24") },
                            singleLine = true,
                            isError = state.targetError != null,
                            supportingText = state.targetError?.let { { Text(it) } },
                            textStyle = MonoTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.isAnalyzing) {
                                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Button(onClick = viewModel::analyze) { Text("Analyze") }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Reach a specific host",
                    subtitle = "Tries ICMP, TCP connect on common ports, an HTTP response and " +
                        "reverse DNS, then reports which one worked",
                ) {
                    Column {
                        OutlinedTextField(
                            value = state.hostToTest,
                            onValueChange = viewModel::setHostToTest,
                            label = { Text("Host address") },
                            placeholder = { Text("10.0.7.5") },
                            singleLine = true,
                            isError = state.hostError != null,
                            supportingText = state.hostError?.let { { Text(it) } },
                            textStyle = MonoTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.isTestingHost) {
                                OutlinedButton(onClick = viewModel::cancel) { Text("Stop") }
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Button(onClick = viewModel::testHost) { Text("Try to connect") }
                                if (state.report?.observations?.respondingHosts?.isNotEmpty() == true) {
                                    OutlinedButton(onClick = viewModel::useRespondingHost) {
                                        Text("Use a host that answered")
                                    }
                                }
                            }
                        }

                        state.connectionReport?.let { report ->
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                val (background, foreground) = if (report.succeeded) {
                                    status.reachable to status.onReachable
                                } else {
                                    status.warning to status.onWarning
                                }
                                StatusPill(
                                    if (report.succeeded) "REACHABLE" else "NO RESPONSE",
                                    background,
                                    foreground,
                                )
                                Text(
                                    report.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    report.attempts.forEach { attempt ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            StatusPill(
                                                if (attempt.succeeded) "OK" else "—",
                                                if (attempt.succeeded) status.reachable else status.unknown,
                                                if (attempt.succeeded) status.onReachable else status.onUnknown,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "${attempt.technique.label} · ${attempt.target}",
                                                    style = MonoTextStyle,
                                                )
                                                Text(
                                                    attempt.detail,
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

            item {
                SectionCard(
                    title = "Scan the whole target subnet",
                    subtitle = "The analyzer only samples a few addresses; this enumerates them all",
                ) {
                    Column {
                        Text(
                            "Discovery runs the same engine as the Devices screen, and results " +
                                "appear there as they are found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::scanTargetSubnet,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Scan this subnet") }
                        state.scanStartedFor?.let { target ->
                            NoticeBanner(
                                text = "Scanning $target. Open the Devices tab to watch results " +
                                    "arrive and to stop the scan.",
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            state.report?.let { report ->
                item {
                    val (background, foreground) = when (report.reachability) {
                        SubnetReachability.CONNECTED, SubnetReachability.REACHABLE ->
                            status.reachable to status.onReachable
                        SubnetReachability.ROUTED -> status.informational to status.onInformational
                        SubnetReachability.NO_RESPONSE -> status.warning to status.onWarning
                        SubnetReachability.UNREACHABLE -> status.failed to status.onFailed
                        SubnetReachability.UNKNOWN -> status.unknown to status.onUnknown
                    }
                    SectionCard(
                        title = "Conclusion",
                        trailing = {
                            StatusPill(report.reachability.label.uppercase(), background, foreground)
                        },
                    ) {
                        Text(report.conclusion, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item {
                    SectionCard(
                        title = "Diagnostic chain",
                        subtitle = "Each step shows the evidence it is based on",
                    ) {
                        Column {
                            report.steps.forEach { step ->
                                val (background, foreground) = when (step.outcome) {
                                    StepOutcome.PASS -> status.reachable to status.onReachable
                                    StepOutcome.FAIL -> status.failed to status.onFailed
                                    StepOutcome.INCONCLUSIVE -> status.warning to status.onWarning
                                    StepOutcome.INFO -> status.informational to status.onInformational
                                }
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            step.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        StatusPill(step.outcome.name, background, foreground)
                                    }
                                    Text(
                                        step.evidence,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Source: ${step.source.label()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (report.candidateExplanations.isNotEmpty()) {
                    item {
                        SectionCard(
                            title = "Possible explanations",
                            subtitle = "Unranked — NetScope cannot tell which of these applies " +
                                "without further evidence",
                        ) {
                            Column {
                                report.candidateExplanations.forEach { explanation ->
                                    Column(modifier = Modifier.padding(vertical = 5.dp)) {
                                        Text(
                                            explanation.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            explanation.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                report.warning?.let { warning ->
                    item { NoticeBanner(text = warning, tone = NoticeTone.WARNING) }
                }

                item {
                    SectionCard(title = "What was probed") {
                        Column {
                            PlainRow(
                                "Sampled addresses",
                                report.observations.sampledHosts
                                    .joinToString(", ") { it.toCanonicalString() }
                                    .ifEmpty { "None" },
                                monospace = true,
                            )
                            PlainRow(
                                "Answered",
                                report.observations.respondingHosts
                                    .joinToString(", ") { it.toCanonicalString() }
                                    .ifEmpty { "None" },
                                monospace = true,
                            )
                            PlainRow(
                                "ICMP available",
                                if (report.observations.icmpAvailable) {
                                    "Yes — latency figures are real round trips"
                                } else {
                                    "No — probes fell back to TCP connect"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
