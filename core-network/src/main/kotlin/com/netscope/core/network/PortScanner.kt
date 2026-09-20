package com.netscope.core.network

import com.netscope.core.model.PortResult
import com.netscope.core.model.PortState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP port scanning.
 *
 * This identifies which ports accept a connection. It does not test credentials, probe
 * for vulnerabilities or attempt any exploit: NetScope is a diagnostic instrument, and
 * those are explicit non-goals.
 */
@Singleton
class PortScanner @Inject constructor(private val hostProber: HostProber) {

    enum class Profile(val label: String, val ports: List<Int>) {
        QUICK("Quick", listOf(22, 53, 80, 443, 445, 554, 631, 3389, 8080, 8443)),
        COMMON(
            "Common",
            listOf(
                20, 21, 22, 23, 25, 53, 67, 80, 110, 111, 123, 135, 139, 143, 161, 389,
                443, 445, 465, 514, 548, 554, 587, 631, 993, 995, 1433, 1723, 1883, 2049,
                3128, 3306, 3389, 5000, 5060, 5432, 5900, 5901, 6379, 7547, 8000, 8008,
                8080, 8443, 8883, 9000, 9100, 27017,
            ),
        ),
        CUSTOM("Custom", emptyList()),
    }

    /**
     * Scans [ports] on [address], reporting each result as it completes.
     *
     * Concurrency is kept conservative: a burst of hundreds of simultaneous SYNs looks
     * like an attack to an IDS and is the fastest way to get a device blocked.
     */
    suspend fun scan(
        address: InetAddress,
        ports: List<Int>,
        timeoutMillis: Long = 1_000,
        concurrency: Int = DEFAULT_CONCURRENCY,
        grabBanners: Boolean = false,
        onResult: suspend (PortResult) -> Unit = {},
    ): List<PortResult> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
        ports.distinct().sorted().map { port ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    val result = hostProber.scanPort(address, port, timeoutMillis)
                    val enriched = if (grabBanners && result.state == PortState.OPEN) {
                        result.copy(banner = hostProber.grabBanner(address, port, timeoutMillis))
                    } else {
                        result
                    }
                    onResult(enriched)
                    enriched
                }
            }
        }.awaitAll().sortedBy { it.port }
    }

    /** Human-readable name for a well-known port, or null rather than a guess. */
    fun serviceNameFor(port: Int): String? = wellKnownPorts[port]

    private val wellKnownPorts = mapOf(
        20 to "FTP data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 67 to "DHCP server", 80 to "HTTP", 110 to "POP3", 111 to "rpcbind",
        123 to "NTP", 135 to "MS RPC", 139 to "NetBIOS session", 143 to "IMAP",
        161 to "SNMP", 389 to "LDAP", 443 to "HTTPS", 445 to "SMB", 465 to "SMTPS",
        514 to "syslog", 548 to "AFP", 554 to "RTSP", 587 to "SMTP submission",
        631 to "IPP", 993 to "IMAPS", 995 to "POP3S", 1433 to "MS SQL",
        1723 to "PPTP", 1883 to "MQTT", 2049 to "NFS", 3128 to "Squid proxy",
        3306 to "MySQL", 3389 to "RDP", 5000 to "UPnP / HTTP", 5060 to "SIP",
        5432 to "PostgreSQL", 5900 to "VNC", 5901 to "VNC", 6379 to "Redis",
        7547 to "TR-069", 8000 to "HTTP alt", 8008 to "HTTP alt", 8080 to "HTTP proxy",
        8443 to "HTTPS alt", 8883 to "MQTT over TLS", 9000 to "HTTP alt",
        9100 to "Raw printing", 27017 to "MongoDB",
    )

    companion object {
        private const val DEFAULT_CONCURRENCY = 16
        private const val MAX_CONCURRENCY = 64

        /** Parses "22, 80, 8000-8010" into a bounded port list. */
        fun parsePortSpec(spec: String): List<Int> {
            val ports = LinkedHashSet<Int>()
            for (part in spec.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
                val dash = part.indexOf('-')
                if (dash > 0) {
                    val start = part.substring(0, dash).trim().toIntOrNull() ?: continue
                    val end = part.substring(dash + 1).trim().toIntOrNull() ?: continue
                    if (start !in 1..65535 || end !in 1..65535 || end < start) continue
                    for (port in start..end) {
                        ports += port
                        if (ports.size >= MAX_PORTS) return ports.toList()
                    }
                } else {
                    part.toIntOrNull()?.takeIf { it in 1..65535 }?.let { ports += it }
                }
                if (ports.size >= MAX_PORTS) break
            }
            return ports.toList()
        }

        /** A hard cap so a typo like 1-65535 cannot start an hour-long scan by accident. */
        const val MAX_PORTS = 2048
    }
}
