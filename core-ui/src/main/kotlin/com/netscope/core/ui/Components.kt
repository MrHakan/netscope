package com.netscope.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netscope.core.model.Confidence
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.Unavailability

/** How an unavailable value is worded. These strings are shown verbatim, never blank. */
fun Unavailability.label(): String = when (this) {
    Unavailability.NOT_DISCOVERED -> "NOT DISCOVERED"
    Unavailability.RESTRICTED_BY_ANDROID -> "RESTRICTED BY ANDROID"
    Unavailability.NOT_APPLICABLE -> "NOT APPLICABLE"
    Unavailability.PERMISSION_REQUIRED -> "PERMISSION REQUIRED"
    Unavailability.LOCATION_SERVICES_REQUIRED -> "LOCATION SERVICES REQUIRED"
    Unavailability.ELEVATED_CAPABILITY_REQUIRED -> "ROOT REQUIRED"
    Unavailability.NOT_ATTEMPTED -> "NOT CHECKED"
}

fun EvidenceSource.label(): String = when (this) {
    EvidenceSource.LINK_PROPERTIES -> "LinkProperties"
    EvidenceSource.NETWORK_CAPABILITIES -> "NetworkCapabilities"
    EvidenceSource.NETWORK_INTERFACE -> "NetworkInterface"
    EvidenceSource.WIFI_MANAGER -> "WifiManager"
    EvidenceSource.TCP_PROBE -> "TCP probe"
    EvidenceSource.ICMP_PROBE -> "ICMP probe"
    EvidenceSource.DNS -> "DNS"
    EvidenceSource.DNS_PTR -> "Reverse DNS"
    EvidenceSource.MDNS -> "mDNS"
    EvidenceSource.SSDP -> "SSDP"
    EvidenceSource.NETBIOS -> "NetBIOS"
    EvidenceSource.LLMNR -> "LLMNR"
    EvidenceSource.OUI -> "OUI database"
    EvidenceSource.INFERENCE -> "INFERRED"
    EvidenceSource.USER -> "You"
    EvidenceSource.DEMO -> "DEMO DATA"
    EvidenceSource.NONE -> "No source"
}

fun Confidence.label(): String = when (this) {
    Confidence.HIGH -> "High confidence"
    Confidence.MEDIUM -> "Medium confidence"
    Confidence.LOW -> "Low confidence"
    Confidence.UNKNOWN -> "Unknown confidence"
}

/** A small pill carrying a status. Always includes text, never colour alone. */
@Composable
fun StatusPill(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The chip that marks where a value came from. */
@Composable
fun SourceChip(source: EvidenceSource, confidence: Confidence, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (background, foreground) = when {
        source == EvidenceSource.DEMO -> status.warning to status.onWarning
        source == EvidenceSource.INFERENCE -> status.warning to status.onWarning
        confidence == Confidence.HIGH -> status.informational to status.onInformational
        else -> status.unknown to status.onUnknown
    }
    StatusPill(
        text = source.label(),
        background = background,
        foreground = foreground,
        modifier = modifier.semantics {
            contentDescription = "Source: ${source.label()}, ${confidence.label()}"
        },
    )
}

/**
 * One labelled property.
 *
 * This is the workhorse of the whole app: it shows the value, where it came from, and,
 * when it is missing, why. Tapping reveals the justification text.
 */
@Composable
fun <T> EvidenceRow(
    label: String,
    evidence: Evidence<T>,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    copyable: Boolean = false,
    render: (T) -> String = { it.toString() },
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val status = LocalStatusColors.current
    val valueText = evidence.value?.let(render) ?: evidence.unavailability?.label() ?: "UNKNOWN"
    val hasDetail = evidence.detail != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetail) { expanded = !expanded }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Show how this was determined",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = valueText,
                style = if (monospace) MonoTextStyle else MaterialTheme.typography.bodyMedium,
                color = if (evidence.isPresent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (evidence.isInferred) {
                StatusPill("INFERRED", status.warning, status.onWarning)
            }
            if (copyable && evidence.isPresent) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { clipboard.setText(AnnotatedString(valueText)) },
                )
            }
            SourceChip(evidence.source, evidence.confidence)
        }
        if (expanded && evidence.detail != null) {
            Text(
                text = evidence.detail!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A plain label/value row for values that are not Evidence-wrapped. */
@Composable
fun PlainRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    copyable: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (monospace) MonoSmallTextStyle else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.4f),
        )
        if (copyable) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { clipboard.setText(AnnotatedString(value)) },
            )
        }
    }
}

/** A titled card, the standard container for a group of rows. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Box(modifier = Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

/**
 * The banner used for every platform limitation.
 *
 * Restrictions are explained, not hidden: an engineer needs to know the difference
 * between "nothing is there" and "Android will not tell me".
 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    action: (@Composable () -> Unit)? = null,
) {
    val status = LocalStatusColors.current
    val (background, foreground) = when (tone) {
        NoticeTone.INFO -> status.informational to status.onInformational
        NoticeTone.WARNING -> status.warning to status.onWarning
        NoticeTone.ERROR -> status.failed to status.onFailed
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(10.dp))
            .border(1.dp, foreground.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = foreground)
        if (action != null) {
            Box(modifier = Modifier.padding(top = 8.dp)) { action() }
        }
    }
}

enum class NoticeTone { INFO, WARNING, ERROR }
