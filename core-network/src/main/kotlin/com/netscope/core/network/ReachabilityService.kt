package com.netscope.core.network

import com.netscope.core.model.HealthPanel
import com.netscope.core.model.HealthRow
import com.netscope.core.model.HealthStatus
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.PingStatistics
import com.netscope.core.model.ProbeType
import com.netscope.core.model.ReachabilityAnalyzer
import com.netscope.core.model.ReachabilityObservations
import com.netscope.core.model.ReachabilityReport
import com.netscope.core.model.RouteEntry
import com.netscope.core.model.RouteMatcher
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

/**
 * Gathers the evidence the reachability analyzer reasons over.
 *
 * The split is deliberate: this class only observes, and every conclusion is drawn by
 * the pure analyzer in core-model, which is unit-tested without a network.
 */
@Singleton
class ReachabilityService @Inject constructor(
    private val hostProber: HostProber,
    private val dnsTools: DnsTools,
) {

    /**
     * Probes a sample of the target block rather than all of it.
     *
     * A handful of well-chosen addresses answers the question "is anything there" at a
     * fraction of the cost of a sweep, and the report says exactly which were tried.
     */
    suspend fun analyze(
        target: Ipv4Cidr,
        network: NetworkSnapshot?,
        sampleSize: Int = 12,
        timeoutMillis: Long = 1_200,
    ): ReachabilityReport = coroutineScope {
        val routes = network?.routes.orEmpty()
        val gateway = network?.ipv4Gateway
        val localAddress = network?.primaryIpv4

        val gatewayProbe = gateway?.let {
            async(Dispatchers.IO) {
                hostProber.probe(
                    InetAddress.getByAddress(it.bytes),
                    timeoutMillis,
                    sequence = 1,
                )
            }
        }

        val sample = sampleAddresses(target, sampleSize, gateway)
        val semaphore = Semaphore(SAMPLE_CONCURRENCY)
        val sampleResults = sample.map { address ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    address to hostProber.probe(
                        InetAddress.getByAddress(address.bytes),
                        timeoutMillis,
                        sequence = (address.value and 0xFFFF).toInt(),
                    )
                }
            }
        }.awaitAll()

        val gatewayOutcome = gatewayProbe?.await()

        val observations = ReachabilityObservations(
            target = target,
            localAddress = localAddress,
            localCidr = network?.primaryIpv4Cidr,
            routes = routes,
            defaultGateway = gateway,
            gatewayResponded = gatewayOutcome?.responded,
            gatewayProbeType = gatewayOutcome?.probeType,
            sampledHosts = sample,
            respondingHosts = sampleResults.filter { it.second.responded }.map { it.first },
            dnsServers = network?.dnsServers.orEmpty(),
            hasValidatedInternet = network?.hasValidatedInternet?.value,
            interfaceName = network?.interfaceName?.value,
            transportLabel = network?.transportLabel,
            icmpAvailable = hostProber.icmpAvailable,
        )

        ReachabilityAnalyzer.analyze(observations)
    }

    /**
     * Picks addresses worth probing: the block's first few (where gateways and servers
     * usually live), a couple in the middle, and the last usable one.
     */
    internal fun sampleAddresses(target: Ipv4Cidr, sampleSize: Int, gateway: Ipv4Address?): List<Ipv4Address> {
        val first = target.firstUsableHost ?: return emptyList()
        val last = target.lastUsableHost ?: return listOf(first)
        val candidates = LinkedHashSet<Ipv4Address>()

        gateway?.takeIf { it in target }?.let(candidates::add)
        var value = first.value
        while (value <= last.value && candidates.size < sampleSize - 2 && value < first.value + HEAD_SAMPLE) {
            candidates += Ipv4Address(value)
            value++
        }
        val middle = first.value + (last.value - first.value) / 2
        if (middle in first.value..last.value) candidates += Ipv4Address(middle)
        candidates += last

        return candidates.take(sampleSize).toList()
    }

    /**
     * Explains what Android is expected to do with a packet to [destination].
     *
     * This is a route-selection explanation, not a promise: Android exposes only the
     * routes attached to the Network object, so the kernel may know more than we can see.
     */
    suspend fun explainRoute(
        destination: Ipv4Address,
        network: NetworkSnapshot?,
        timeoutMillis: Long = 1_500,
    ): RouteExplanation = withContext(Dispatchers.IO) {
        val routes = network?.routes.orEmpty()
        val best = RouteMatcher.bestMatch(routes, destination)
        val probe = hostProber.probe(
            InetAddress.getByAddress(destination.bytes),
            timeoutMillis,
            sequence = 7,
        )
        val hostname = dnsTools.reverseLookup(InetAddress.getByAddress(destination.bytes), 1_000)

        val narrative = buildString {
            val source = network?.primaryIpv4?.toCanonicalString()
            append("A packet to ${destination.toCanonicalString()} would leave ")
            append(source?.let { "$it " } ?: "this device ")
            append("on ${network?.interfaceName?.value ?: "an unnamed interface"}")
            append(network?.transportLabel?.let { " ($it)" } ?: "")
            append(". ")
            when {
                best == null -> append(
                    "No route in the set Android exposes matches this destination, so this app " +
                        "cannot say how the packet would be handled.",
                )
                best.isOnLink -> append(
                    "The best matching route is ${best.destinationText}, which is on-link, so the " +
                        "packet would be delivered directly on the local segment without a router.",
                )
                else -> append(
                    "The best matching route is ${best.destinationText} via " +
                        "${best.gateway?.toCanonicalString()}, so the packet would be handed to " +
                        "that gateway, which decides what happens next.",
                )
            }
        }

        RouteExplanation(
            destination = destination,
            hostname = hostname,
            selectedNetworkLabel = network?.transportLabel,
            interfaceName = network?.interfaceName?.value,
            sourceAddress = network?.primaryIpv4,
            bestRoute = best,
            gateway = best?.gateway,
            destinationSubnet = best?.destination,
            dnsServers = network?.dnsServers.orEmpty().mapNotNull { it.toCanonicalString() },
            probeResponded = probe.responded,
            probeLatencyMillis = probe.latencyMillis,
            probeType = probe.probeType,
            probeDetail = probe.detail,
            narrative = narrative,
        )
    }

    /**
     * Builds the health panel.
     *
     * Every row states the measurement behind it; there is no composite score, because
     * combining a signal level with a packet-loss figure produces a number that means
     * nothing.
     */
    suspend fun healthPanel(
        network: NetworkSnapshot?,
        probeCount: Int = 5,
        unlabelledDeviceCount: Int? = null,
    ): HealthPanel = coroutineScope {
        val rows = mutableListOf<HealthRow>()
        val now = System.currentTimeMillis()
        val gateway = network?.ipv4Gateway

        if (gateway != null) {
            val address = InetAddress.getByAddress(gateway.bytes)
            val results = (1..probeCount).map { sequence ->
                hostProber.probe(address, GATEWAY_TIMEOUT_MILLIS, sequence)
            }
            val stats = PingStatistics.from(
                gateway.toCanonicalString(),
                results.firstOrNull()?.probeType ?: ProbeType.TCP_CONNECT,
                results.map { it.latencyMillis },
            )
            rows += HealthRow(
                label = "Gateway reachable",
                status = if (stats.received > 0) HealthStatus.PASS else HealthStatus.WARN,
                value = if (stats.received > 0) "PASS" else "NO REPLY",
                evidence = if (stats.received > 0) {
                    "${stats.probeLabel} %.1f ms avg, %d/%d replies."
                        .format(stats.avgMillis ?: 0.0, stats.received, stats.sent)
                } else {
                    "${stats.sent} ${stats.probeLabel} probes to ${gateway.toCanonicalString()} " +
                        "went unanswered. Many gateways are configured not to reply."
                },
            )
            rows += HealthRow(
                label = "Packet loss",
                status = when {
                    stats.lossPercent == 0.0 -> HealthStatus.PASS
                    stats.lossPercent < 50.0 -> HealthStatus.WARN
                    else -> HealthStatus.FAIL
                },
                value = "%.0f%%".format(stats.lossPercent),
                evidence = "${stats.sent} probes to the gateway, ${stats.received} answered.",
            )
        } else {
            rows += HealthRow(
                label = "Gateway reachable",
                status = HealthStatus.UNKNOWN,
                value = "NO GATEWAY",
                evidence = "Android exposed no default gateway for the active network.",
            )
        }

        val dnsServers = network?.dnsServers.orEmpty()
        if (dnsServers.isEmpty()) {
            rows += HealthRow(
                "DNS", HealthStatus.UNKNOWN, "NOT CONFIGURED",
                "No DNS servers were exposed for this network.",
            )
        } else {
            val start = System.nanoTime()
            val lookup = dnsTools.resolve(DNS_PROBE_NAME, DNS_TIMEOUT_MILLIS)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            rows += HealthRow(
                label = "DNS",
                status = if (lookup.answers.isNotEmpty()) HealthStatus.PASS else HealthStatus.FAIL,
                value = if (lookup.answers.isNotEmpty()) "PASS" else "FAIL",
                evidence = if (lookup.answers.isNotEmpty()) {
                    "$DNS_PROBE_NAME resolved in $elapsed ms. ${lookup.resolverNote}"
                } else {
                    "$DNS_PROBE_NAME did not resolve. ${lookup.resolverNote}"
                },
            )
        }

        val validated = network?.hasValidatedInternet?.value
        rows += HealthRow(
            label = "Internet",
            status = when (validated) {
                true -> HealthStatus.PASS
                false -> HealthStatus.WARN
                null -> HealthStatus.UNKNOWN
            },
            value = when (validated) {
                true -> "PASS"
                false -> "NOT VALIDATED"
                null -> "UNKNOWN"
            },
            evidence = "NetworkCapabilities " +
                when (validated) {
                    true -> "reports VALIDATED: Android confirmed internet access on this network."
                    false -> "does not report VALIDATED for this network."
                    null -> "was unavailable."
                },
        )

        network?.let { snapshot ->
            rows += HealthRow(
                label = "Transport",
                status = HealthStatus.PASS,
                value = snapshot.transportLabel,
                evidence = "Reported by NetworkCapabilities for " +
                    "${snapshot.interfaceName.value ?: "this network"}.",
            )
        }

        unlabelledDeviceCount?.let { count ->
            rows += HealthRow(
                label = "Unlabelled devices",
                status = if (count == 0) HealthStatus.PASS else HealthStatus.WARN,
                value = count.toString(),
                evidence = if (count == 0) {
                    "Every discovered device has a hostname, vendor or service name."
                } else {
                    "$count discovered device(s) produced no hostname, vendor or service evidence."
                },
            )
        }

        HealthPanel(rows, now)
    }

    companion object {
        private const val SAMPLE_CONCURRENCY = 8
        private const val HEAD_SAMPLE = 8
        private const val GATEWAY_TIMEOUT_MILLIS = 1_000L
        private const val DNS_TIMEOUT_MILLIS = 3_000L

        /**
         * A name resolved to test DNS. Resolution is a local-resolver operation; the
         * name itself is never contacted.
         */
        private const val DNS_PROBE_NAME = "example.com"
    }
}

/** The output of the route diagnostics tool. */
data class RouteExplanation(
    val destination: Ipv4Address,
    val hostname: String?,
    val selectedNetworkLabel: String?,
    val interfaceName: String?,
    val sourceAddress: Ipv4Address?,
    val bestRoute: RouteEntry?,
    val gateway: com.netscope.core.model.IpAddress?,
    val destinationSubnet: Ipv4Cidr?,
    val dnsServers: List<String>,
    val probeResponded: Boolean,
    val probeLatencyMillis: Double?,
    val probeType: ProbeType,
    val probeDetail: String,
    val narrative: String,
)
