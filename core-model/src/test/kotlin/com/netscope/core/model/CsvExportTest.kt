package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CsvExportTest {

    private fun device(
        ip: String,
        hostname: String? = null,
        label: String? = null,
        services: List<Pair<String, Int?>> = emptyList(),
    ) = DiscoveredDevice(
        ipv4 = Ipv4Address.parse(ip),
        hostname = hostname?.let {
            Evidence.observed(it, EvidenceSource.DNS_PTR, Confidence.HIGH, 1)
        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        state = HostState.RESPONDING,
        latencyMillis = 12.34,
        probeType = ProbeType.ICMP,
        services = services.map { (name, port) ->
            DiscoveredService(port, "tcp", name, null, null, EvidenceSource.MDNS, Confidence.HIGH, 1)
        },
        discoverySources = setOf(EvidenceSource.ICMP_PROBE),
        userLabel = label,
    )

    @Test
    fun `emits a header and one row per device`() {
        val csv = CsvExport.render(listOf(device("10.0.2.1"), device("10.0.2.5")))
        val lines = csv.lines()
        assertThat(lines).hasSize(3)
        assertThat(lines.first()).startsWith("ip,hostname")
    }

    @Test
    fun `quotes a value containing a comma`() {
        // An unquoted comma would shift every column after it.
        assertThat(CsvExport.escape("Kitchen, Printer")).isEqualTo("\"Kitchen, Printer\"")
    }

    @Test
    fun `doubles embedded quotes`() {
        assertThat(CsvExport.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
    }

    @Test
    fun `quotes values containing newlines`() {
        assertThat(CsvExport.escape("line1\nline2")).isEqualTo("\"line1\nline2\"")
        assertThat(CsvExport.escape("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"")
    }

    @Test
    fun `leaves plain values untouched`() {
        assertThat(CsvExport.escape("printer.lan")).isEqualTo("printer.lan")
        assertThat(CsvExport.escape("")).isEqualTo("")
    }

    @Test
    fun `a label containing a comma does not break the column count`() {
        val csv = CsvExport.render(listOf(device("10.0.2.9", label = "Office, upstairs")))
        val row = csv.lines()[1]
        assertThat(row).contains("\"Office, upstairs\"")
        // Parsed back, the row must still have exactly as many fields as the header.
        assertThat(parseCsvRow(row)).hasSize(CsvExport.HEADER.size)
    }

    @Test
    fun `services are joined without breaking the row`() {
        val csv = CsvExport.render(
            listOf(device("10.0.2.18", services = listOf("IPP" to 631, "Raw" to 9100))),
        )
        val row = csv.lines()[1]
        assertThat(row).contains("IPP:631; Raw:9100")
        assertThat(parseCsvRow(row)).hasSize(CsvExport.HEADER.size)
    }

    @Test
    fun `every row has the same field count as the header`() {
        val csv = CsvExport.render(
            listOf(
                device("10.0.2.1", hostname = "gw.lan"),
                device("10.0.2.5", label = "a,b,c"),
                device("10.0.2.7", services = listOf("HTTP" to 80)),
            ),
        )
        for (row in csv.lines().drop(1)) {
            assertThat(parseCsvRow(row)).hasSize(CsvExport.HEADER.size)
        }
    }

    @Test
    fun `latency uses a dot regardless of locale`() {
        // A comma decimal separator would silently add a column.
        val csv = CsvExport.render(listOf(device("10.0.2.1")))
        assertThat(csv.lines()[1]).contains("12.3")
    }

    /** Minimal RFC 4180 reader, used only to prove the writer round-trips. */
    private fun parseCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuotes && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
