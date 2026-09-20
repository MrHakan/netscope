package com.netscope.feature.access

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.network.AccessProtocol
import com.netscope.core.network.ServiceEndpoint
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.MonoTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun ServiceExplorerScreen(
    onBack: () -> Unit,
    onOpenFtp: (String, Int) -> Unit,
    viewModel: ServiceExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val status = LocalStatusColors.current

    NetScopeScreen(
        title = "Service Explorer",
        subtitle = "Discover and open services on another host or subnet",
        onBack = onBack,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(
                    title = "Target",
                    subtitle = "A host or routed subnet such as 10.0.7.0/24",
                ) {
                    Column {
                        OutlinedTextField(
                            value = state.targetText,
                            onValueChange = viewModel::setTarget,
                            label = { Text("Host or CIDR") },
                            placeholder = { Text("10.0.7.0/24") },
                            singleLine = true,
                            textStyle = MonoTextStyle,
                            enabled = !state.isScanning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.isScanning) {
                                OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Button(onClick = viewModel::scan) { Text("Discover services") }
                            }
                        }
                    }
                }
            }

            item {
                NoticeBanner(
                    text = "Service discovery attempts TCP connections to common access and " +
                        "infrastructure ports. It does not try passwords or vulnerability checks. " +
                        "OPEN means the port accepted a connection; authentication may still be required.",
                )
            }

            if (state.progress.totalProbes > 0) {
                item {
                    SectionCard(title = "Progress") {
                        Text(
                            state.progress.completedProbes.toString() + " / " +
                                state.progress.totalProbes + " probes · " +
                                state.endpoints.size + " access endpoints found",
                            style = MonoSmallTextStyle,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item { NoticeBanner(text = error, tone = NoticeTone.WARNING) }
            }

            val grouped = state.endpoints.groupBy { it.host }.toSortedMap(
                compareBy { host -> com.netscope.core.model.Ipv4Address.parse(host)?.value ?: Long.MAX_VALUE },
            )

            grouped.forEach { (host, endpoints) ->
                item(key = host) {
                    SectionCard(
                        title = host,
                        subtitle = endpoints.map { it.port }.distinct().size.toString() + " open access ports",
                    ) {
                        Column {
                            endpoints.forEach { endpoint ->
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                endpoint.protocol.label + " · port " + endpoint.port,
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                "Protocol: INFERRED FROM WELL-KNOWN PORT",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            endpoint.uri?.let {
                                                Text(
                                                    it,
                                                    style = MonoSmallTextStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            endpoint.latencyMillis?.let {
                                                Text(
                                                    "TCP connect: " + it + " ms",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            endpoint.banner?.let { banner ->
                                                Text(
                                                    banner,
                                                    style = MonoSmallTextStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 3,
                                                )
                                            }
                                        }
                                        StatusPill(
                                            "OPEN",
                                            status.reachable,
                                            status.onReachable,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.padding(top = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (endpoint.protocol == AccessProtocol.FTP) {
                                            Button(
                                                onClick = { onOpenFtp(endpoint.host, endpoint.port) },
                                            ) { Text("Browse FTP") }
                                        }
                                        endpoint.uri?.let { uri ->
                                            OutlinedButton(
                                                onClick = { openExternal(context, uri) },
                                            ) { Text("Open") }
                                            TextButton(
                                                onClick = { copyUri(context, uri) },
                                            ) { Text("Copy URI") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!state.isScanning && state.endpoints.isEmpty() && state.progress.totalProbes > 0 && state.error == null) {
                item {
                    NoticeBanner(
                        text = "No common access ports accepted a TCP connection. This does not " +
                            "prove that the target has no services; it may use other ports or a firewall.",
                        tone = NoticeTone.WARNING,
                    )
                }
            }
        }
    }
}

private fun openExternal(context: Context, uri: String) {
    val parsed = Uri.parse(uri)
    val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(
            context,
            "No installed app handles " + parsed.scheme + " links. Copy the URI instead.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun copyUri(context: Context, uri: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("NetScope service URI", uri))
    Toast.makeText(context, "URI copied", Toast.LENGTH_SHORT).show()
}
