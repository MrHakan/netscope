package com.netscope.feature.wifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.model.WifiBand
import com.netscope.core.model.WifiScanAvailability
import com.netscope.core.model.WifiScanEntry
import com.netscope.core.ui.EvidenceRow
import com.netscope.core.ui.LocalStatusColors
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.PlainRow
import com.netscope.core.ui.SectionCard
import com.netscope.core.ui.StatusPill
import com.netscope.feature.common.NetScopeScreen

@Composable
fun WifiScreen(viewModel: WifiViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The live meter must never outlive the screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopLiveSignal() }
    }

    NetScopeScreen(
        title = "Wi-Fi",
        subtitle = "${state.networks.size} nearby network(s)",
        actions = {
            IconButton(onClick = viewModel::requestScan) {
                Icon(Icons.Default.Refresh, contentDescription = "Request a new Wi-Fi scan")
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            availabilityBanner(state.availability)?.let { (text, tone) ->
                item {
                    NoticeBanner(
                        text = text,
                        tone = tone,
                        action = if (state.availability is WifiScanAvailability.LocationServicesDisabled) {
                            {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }) { Text("Open location settings") }
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            state.connection?.let { connection ->
                item {
                    SectionCard(title = "Connected network", subtitle = "Source: WifiManager") {
                        Column {
                            EvidenceRow("SSID", connection.ssid)
                            EvidenceRow("BSSID", connection.bssid, monospace = true, copyable = true)
                            EvidenceRow("Signal", connection.rssiDbm) { "$it dBm" }
                            connection.channel?.let {
                                PlainRow("Channel", "$it (${connection.band?.label ?: "unknown"})")
                            }
                            EvidenceRow("TX link speed", connection.txLinkSpeedMbps) { "$it Mbps" }
                            EvidenceRow("RX link speed", connection.rxLinkSpeedMbps) { "$it Mbps" }
                            Text(
                                "Link speed is the negotiated PHY rate between this device and the " +
                                    "access point. It is not an internet speed and the two must not " +
                                    "be compared.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                item { LiveSignalCard(state, viewModel::startLiveSignal, viewModel::stopLiveSignal) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.selectedBand == null,
                        onClick = { viewModel.setBand(null) },
                        label = { Text("All bands") },
                    )
                    WifiBand.entries.forEach { band ->
                        FilterChip(
                            selected = state.selectedBand == band,
                            onClick = { viewModel.setBand(band) },
                            label = { Text(band.label) },
                        )
                    }
                }
            }

            if (state.visibleNetworks.isNotEmpty()) {
                item { ChannelGraphCard(state.visibleNetworks) }
            }

            items(state.visibleNetworks, key = { it.bssid }) { entry ->
                AccessPointCard(entry, state.vendors[entry.bssid])
            }

            if (state.visibleNetworks.isEmpty() && state.availability == WifiScanAvailability.Available) {
                item {
                    Text(
                        "No networks in the cached results yet. Tap refresh to ask Android for a scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Maps a scan-availability state to a specific, actionable message. */
private fun availabilityBanner(availability: WifiScanAvailability): Pair<String, NoticeTone>? =
    when (availability) {
        WifiScanAvailability.Available -> null
        is WifiScanAvailability.PermissionRequired ->
            "PERMISSION REQUIRED — Wi-Fi scan results need ${availability.permissions.joinToString(", ")}. " +
                "Until it is granted Android returns an empty list, which is not the same as no " +
                "networks being present." to NoticeTone.WARNING
        WifiScanAvailability.LocationServicesDisabled ->
            "LOCATION SERVICES REQUIRED — on this Android version Wi-Fi scan results are empty " +
                "unless location services are enabled system-wide, even though the permission is " +
                "granted. This is a platform rule, not a NetScope limitation." to NoticeTone.WARNING
        WifiScanAvailability.WifiDisabled ->
            "Wi-Fi is turned off, so no scan can run." to NoticeTone.INFO
        is WifiScanAvailability.Throttled ->
            "THROTTLED BY ANDROID — the platform limits foreground apps to a few scans every two " +
                "minutes. The next scan should be permitted in about " +
                "${availability.retryAfterMillis / 1000} s. Cached results are shown with their " +
                "age until then." to NoticeTone.INFO
    }

@Composable
private fun LiveSignalCard(
    state: WifiUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(
        title = "Live signal",
        subtitle = "Sampled once per second over a rolling 60-second window",
    ) {
        Column {
            if (state.samples.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("min ${state.minRssi} dBm", style = MonoSmallTextStyle)
                    Text("avg %.1f dBm".format(state.averageRssi ?: 0.0), style = MonoSmallTextStyle)
                    Text("max ${state.maxRssi} dBm", style = MonoSmallTextStyle)
                }
                SignalGraph(
                    samples = state.samples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 8.dp),
                )
            } else {
                Text(
                    "Start the meter to record signal strength over time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                if (state.liveSignalActive) {
                    OutlinedButton(onClick = onStop) { Text("Stop meter") }
                } else {
                    Button(onClick = onStart) { Text("Start meter") }
                }
            }
        }
    }
}

/** Plots the rolling RSSI window on a fixed -100..-20 dBm scale. */
@Composable
private fun SignalGraph(samples: List<SignalSample>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val description = "Signal graph, ${samples.size} samples, " +
        "latest ${samples.lastOrNull()?.rssiDbm ?: 0} dBm"

    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val minDbm = -100f
        val maxDbm = -20f
        val range = maxDbm - minDbm

        // Horizontal grid every 20 dBm.
        var level = minDbm
        while (level <= maxDbm) {
            val y = size.height * (1f - (level - minDbm) / range)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            level += 20f
        }

        if (samples.size < 2) return@Canvas
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = size.width * index / (samples.size - 1).toFloat()
            val clamped = sample.rssiDbm.toFloat().coerceIn(minDbm, maxDbm)
            val y = size.height * (1f - (clamped - minDbm) / range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
    }
}

/**
 * The channel overlap graph.
 *
 * Each access point is drawn as a trapezoid spanning the frequencies it occupies, so
 * overlapping networks are visible as overlapping shapes rather than as a list.
 */
@Composable
private fun ChannelGraphCard(networks: List<WifiScanEntry>) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )
    val axisColor = MaterialTheme.colorScheme.outline

    SectionCard(
        title = "Channel usage",
        subtitle = "Width of each shape is the spectrum the access point occupies",
    ) {
        val withRange = networks.filter { it.occupiedRangeMhz != null }
        if (withRange.isEmpty()) {
            Text(
                "Channel width is not reported for these networks, so the overlap graph cannot be " +
                    "drawn. The list below still shows each channel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val minFrequency = withRange.minOf { it.occupiedRangeMhz!!.first }
        val maxFrequency = withRange.maxOf { it.occupiedRangeMhz!!.last }
        val span = (maxFrequency - minFrequency).coerceAtLeast(1)

        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .semantics {
                        contentDescription = "Channel overlap graph for ${withRange.size} networks"
                    },
            ) {
                drawLine(
                    axisColor,
                    Offset(0f, size.height - 1),
                    Offset(size.width, size.height - 1),
                    strokeWidth = 2f,
                )
                withRange.forEachIndexed { index, entry ->
                    val range = entry.occupiedRangeMhz ?: return@forEachIndexed
                    val left = size.width * (range.first - minFrequency) / span.toFloat()
                    val right = size.width * (range.last - minFrequency) / span.toFloat()
                    val center = (left + right) / 2f
                    // -100 dBm sits on the axis, -20 dBm at the top.
                    val height = size.height *
                        ((entry.rssiDbm.coerceIn(-100, -20) + 100) / 80f)
                    val color = colors[index % colors.size]

                    val path = Path().apply {
                        moveTo(left, size.height)
                        lineTo(center, size.height - height)
                        lineTo(right, size.height)
                        close()
                    }
                    drawPath(path, color = color.copy(alpha = 0.22f))
                    drawPath(path, color = color, style = Stroke(width = 2f))
                }
            }
        }
        Text(
            "$minFrequency MHz – $maxFrequency MHz",
            style = MonoSmallTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AccessPointCard(entry: WifiScanEntry, vendor: String?) {
    val status = LocalStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.ssid ?: "Hidden network",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.ssid == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    "${entry.rssiDbm} dBm · ${entry.signalQuality.label}",
                    when (entry.signalQuality) {
                        com.netscope.core.model.SignalQuality.EXCELLENT,
                        com.netscope.core.model.SignalQuality.GOOD,
                        -> status.reachable
                        com.netscope.core.model.SignalQuality.FAIR -> status.warning
                        else -> status.failed
                    },
                    when (entry.signalQuality) {
                        com.netscope.core.model.SignalQuality.EXCELLENT,
                        com.netscope.core.model.SignalQuality.GOOD,
                        -> status.onReachable
                        com.netscope.core.model.SignalQuality.FAIR -> status.onWarning
                        else -> status.onFailed
                    },
                )
            }
            Text(entry.bssid, style = MonoSmallTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlainRow("Manufacturer", vendor ?: "NOT DISCOVERED")
            PlainRow(
                "Channel",
                "${entry.channel ?: "unknown"} · ${entry.band?.label ?: "unknown band"} · " +
                    "${entry.frequencyMhz} MHz",
            )
            PlainRow("Width", entry.channelWidthMhz?.let { "$it MHz" } ?: "NOT DISCOVERED")
            PlainRow("Security", entry.securitySummary)
            PlainRow("Cipher", entry.cipherSummary)
            PlainRow("WPS advertised", if (entry.wpsSupported) "Yes" else "No / not advertised")
            PlainRow("Generation", entry.wifiStandard ?: "NOT DISCOVERED")
            PlainRow("Result age", "${entry.ageMillis / 1000} s old")
        }
    }
}
