package com.netscope.core.network

import com.netscope.core.model.PortState
import com.netscope.core.model.ProbeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** One way of trying to reach a host. */
enum class ConnectionTechnique(val label: String) {
    ICMP_ECHO("ICMP echo"),
    TCP_CONNECT("TCP connect"),
    HTTP_HEAD("HTTP response"),
    REVERSE_DNS("Reverse DNS"),
}

/** What one technique produced. */
data class ConnectionAttempt(
    val technique: ConnectionTechnique,
    val target: String,
    val succeeded: Boolean,
    val latencyMillis: Double?,
    val detail: String,
    val tcpState: PortState? = null,
)

/**
 * The outcome of trying every technique against one host.
 *
 * [proofOfLife] is the crux: a single technique succeeding proves the host is there and
 * reachable from this device, no matter how many others failed. The reverse is not
 * true, which is why [summary] never concludes a host is absent.
 */
data class ConnectionReport(
    val address: String,
    val attempts: List<ConnectionAttempt>,
    val proofOfLife: ConnectionAttempt?,
    val summary: String,
) {
    val succeeded: Boolean get() = proofOfLife != null
    val openPorts: List<Int>
        get() = attempts
            .filter {
                it.technique == ConnectionTechnique.TCP_CONNECT &&
                    it.tcpState == PortState.OPEN
            }
            .mapNotNull { it.target.substringAfterLast(':').toIntOrNull() }
}

/**
 * Tries several ways of reaching a single host and reports what each one did.
 *
 * Hosts routinely ignore ICMP while happily accepting TCP, answer on an unusual port,
 * or exist only in DNS. Running one probe and calling the host dead is the most common
 * way a network tool produces a confidently wrong answer, so this runs a battery and
 * shows the evidence per technique.
 *
 * Everything here is an ordinary connection attempt. There is no exploitation, no
 * credential testing and no vulnerability probing.
 */
@Singleton
class ConnectionTester @Inject constructor(
    private val hostProber: HostProber,
    private val icmpProbe: IcmpProbe,
    private val dnsTools: DnsTools,
) {

    /**
     * Ports worth trying, ordered by how often they answer on a typical LAN.
     *
     * Kept short deliberately: this is a reachability question, not a port scan, and a
     * long list would look like one to an intrusion detection system.
     */
    private val probePorts = listOf(80, 443, 22, 445, 8080, 53, 139, 8443, 3389, 9100, 631, 554, 5000)

    suspend fun test(
        address: InetAddress,
        timeoutMillis: Long = 1_200,
        ports: List<Int> = probePorts,
    ): ConnectionReport = coroutineScope {
        val attempts = mutableListOf<ConnectionAttempt>()
        val host = address.hostAddress.orEmpty()

        // ICMP first: it is the only technique that yields a true round-trip time.
        if (icmpProbe.isAvailable()) {
            val icmp = icmpProbe.echo(address, timeoutMillis, sequence = 11)
            attempts += ConnectionAttempt(
                technique = ConnectionTechnique.ICMP_ECHO,
                target = host,
                succeeded = icmp.rttMillis != null,
                latencyMillis = icmp.rttMillis,
                detail = icmp.rttMillis?.let { "Echo reply in %.1f ms.".format(it) }
                    ?: "No echo reply. Many hosts are configured to ignore ICMP, so this alone " +
                    "proves nothing.",
            )
        } else {
            attempts += ConnectionAttempt(
                technique = ConnectionTechnique.ICMP_ECHO,
                target = host,
                succeeded = false,
                latencyMillis = null,
                detail = "Unprivileged ICMP is unavailable on this device, so no echo was sent.",
            )
        }

        // TCP connects run in parallel under a small bound: a burst of simultaneous
        // SYNs is exactly what gets a device blocked by an IDS.
        val semaphore = Semaphore(TCP_CONCURRENCY)
        val tcpAttempts = ports.map { port ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = hostProber.scanPort(address, port, timeoutMillis)
                    ConnectionAttempt(
                        technique = ConnectionTechnique.TCP_CONNECT,
                        target = "$host:$port",
                        succeeded = result.state == PortState.OPEN,
                        latencyMillis = result.latencyMillis?.toDouble(),
                        detail = when (result.state) {
                            PortState.OPEN -> "TCP port $port accepted a connection."
                            PortState.CLOSED -> "TCP port $port refused the connection; the RST proves the host responded."
                            PortState.FILTERED_OR_TIMEOUT ->
                                "TCP port $port timed out or was filtered; this proves nothing about host absence."
                        },
                        tcpState = result.state,
                    )
                }
            }
        }.awaitAll()
        attempts += tcpAttempts.filter {
            it.tcpState == PortState.OPEN || it.tcpState == PortState.CLOSED
        }
        val silentPorts = tcpAttempts.count { it.tcpState == PortState.FILTERED_OR_TIMEOUT }
        if (silentPorts > 0) {
            attempts += ConnectionAttempt(
                technique = ConnectionTechnique.TCP_CONNECT,
                target = host,
                succeeded = false,
                latencyMillis = null,
                detail = "$silentPorts of ${ports.size} ports did not answer. A port that times " +
                    "out is indistinguishable from one that is filtered.",
            )
        }

        // An HTTP response is the strongest evidence of all: something is not just
        // reachable, it is serving.
        val webPort = tcpAttempts.firstOrNull { it.succeeded && it.target.endsWith(":80") }
            ?: tcpAttempts.firstOrNull { it.succeeded && it.target.endsWith(":8080") }
        if (webPort != null) {
            val port = webPort.target.substringAfterLast(':').toIntOrNull()
            if (port != null) {
                val banner = hostProber.grabBanner(address, port, timeoutMillis)
                attempts += ConnectionAttempt(
                    technique = ConnectionTechnique.HTTP_HEAD,
                    target = "$host:$port",
                    succeeded = banner != null,
                    latencyMillis = null,
                    detail = banner?.let { "Responded to a HEAD request: ${it.lineSequence().first()}" }
                        ?: "The port accepted a connection but returned nothing readable.",
                )
            }
        }

        val hostname = withContext(Dispatchers.IO) { dnsTools.reverseLookup(address, 1_500) }
        attempts += ConnectionAttempt(
            technique = ConnectionTechnique.REVERSE_DNS,
            target = host,
            succeeded = hostname != null,
            latencyMillis = null,
            detail = hostname?.let { "Resolves to $it." }
                ?: "No PTR record. This says nothing about whether the host is reachable.",
        )

        val proof = attempts.firstOrNull {
            (it.succeeded && it.technique != ConnectionTechnique.REVERSE_DNS) ||
                (it.technique == ConnectionTechnique.TCP_CONNECT && it.tcpState == PortState.CLOSED)
        }

        ConnectionReport(
            address = host,
            attempts = attempts,
            proofOfLife = proof,
            summary = buildSummary(host, proof, attempts, hostname),
        )
    }

    private fun buildSummary(
        host: String,
        proof: ConnectionAttempt?,
        attempts: List<ConnectionAttempt>,
        hostname: String?,
    ): String {
        if (proof != null) {
            val openPorts = attempts
                .filter {
                    it.technique == ConnectionTechnique.TCP_CONNECT &&
                        it.tcpState == PortState.OPEN
                }
                .mapNotNull { it.target.substringAfterLast(':').toIntOrNull() }
            return buildString {
                append("$host is reachable from this device: ")
                if (proof.technique == ConnectionTechnique.TCP_CONNECT &&
                    proof.tcpState == PortState.CLOSED
                ) {
                    append("a TCP port refused the connection, which proves the host answered")
                } else {
                    append(proof.technique.label + " succeeded")
                    proof.latencyMillis?.let { append(" in %.1f ms".format(it)) }
                }
                append(". ")
                if (openPorts.isNotEmpty()) {
                    append("Accepting connections on ${openPorts.joinToString(", ")}. ")
                }
                hostname?.let { append("Reverse DNS names it $it. ") }
                append(
                    "You do not need an address in that host's subnet on this device to talk to it.",
                )
            }
        }
        return "Nothing answered at $host. That is not proof the host is absent — it may be " +
            "powered off, firewalled, configured to ignore every probe, or the address may " +
            "simply be unused. NetScope cannot tell which from here."
    }

    private companion object {
        const val TCP_CONCURRENCY = 6
    }
}
