package com.netscope.core.model

import java.util.Locale

/**
 * Renders scan results as CSV.
 *
 * Kept in the pure module so the quoting rules are unit-tested: a hostname containing a
 * comma or a quote that is not escaped shifts every column after it, which corrupts the
 * export silently rather than failing loudly.
 */
object CsvExport {

    val HEADER: List<String> = listOf(
        "ip", "hostname", "hostname_source", "advertised_name", "mac", "mac_source",
        "vendor", "device_type", "device_type_confidence", "device_type_inferred",
        "state", "latency_ms", "probe_type", "services", "discovery_sources", "user_label",
    )

    fun render(devices: List<DiscoveredDevice>): String {
        val rows = devices.map(::rowFor)
        return (listOf(HEADER) + rows).joinToString("\n") { row ->
            row.joinToString(",", transform = ::escape)
        }
    }

    fun rowFor(device: DiscoveredDevice): List<String> = listOf(
        device.ipv4?.toCanonicalString().orEmpty(),
        device.hostname.value.orEmpty(),
        device.hostname.source.name,
        device.friendlyName.value.orEmpty(),
        device.mac.value?.toString().orEmpty(),
        device.mac.source.name,
        device.vendor.value.orEmpty(),
        device.deviceType.value?.name.orEmpty(),
        device.deviceType.confidence.name,
        device.deviceType.isInferred.toString(),
        device.state.name,
        device.latencyMillis?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
        device.probeType?.name.orEmpty(),
        device.services.joinToString("; ") { service ->
            service.port?.let { "${service.name}:$it" } ?: service.name
        },
        device.discoverySources.joinToString("; ") { it.name },
        device.userLabel.orEmpty(),
    )

    /** RFC 4180 quoting: wrap when the value contains a delimiter, quote or newline. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
