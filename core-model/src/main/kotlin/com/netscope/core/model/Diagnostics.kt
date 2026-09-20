package com.netscope.core.model

/** Outcome of one step in a diagnostic chain. */
enum class StepOutcome { PASS, FAIL, INCONCLUSIVE, INFO }

/**
 * One link in a diagnostic chain.
 *
 * [evidence] is the human-readable justification and is always populated: a step
 * without evidence is a claim without proof and must not be shown.
 */
data class DiagnosticStep(
    val title: String,
    val outcome: StepOutcome,
    val evidence: String,
    val source: EvidenceSource,
)

/** How reachable a target subnet appears, on the evidence available. */
enum class SubnetReachability(val label: String) {
    /** This device holds an address in the target block. */
    CONNECTED("Connected"),

    /** Android exposes a route covering the target. */
    ROUTED("Routed"),

    /** At least one host in the target answered a probe. */
    REACHABLE("Reachable"),

    /** Probes went out and nothing came back. Not the same as unreachable. */
    NO_RESPONSE("No response"),

    /** Something actively reported the destination as unreachable. */
    UNREACHABLE("Unreachable"),

    /** Not enough evidence either way. */
    UNKNOWN("Unknown"),
}

/** A candidate explanation for a failed reachability test. Never ranked as "the" cause. */
data class CandidateExplanation(val title: String, val detail: String)

/** Everything the analyzer observed, kept separate from the conclusions it draws. */
data class ReachabilityObservations(
    val target: Ipv4Cidr,
    val localAddress: Ipv4Address?,
    val localCidr: Ipv4Cidr?,
    val routes: List<RouteEntry>,
    val defaultGateway: Ipv4Address?,
    val gatewayResponded: Boolean?,
    val gatewayProbeType: ProbeType?,
    val sampledHosts: List<Ipv4Address>,
    val respondingHosts: List<Ipv4Address>,
    val dnsServers: List<IpAddress>,
    val hasValidatedInternet: Boolean?,
    val interfaceName: String?,
    val transportLabel: String?,
    val icmpAvailable: Boolean,
)

/** The analyzer's verdict plus the chain that produced it. */
data class ReachabilityReport(
    val observations: ReachabilityObservations,
    val reachability: SubnetReachability,
    val steps: List<DiagnosticStep>,
    val conclusion: String,
    val candidateExplanations: List<CandidateExplanation>,
    val warning: String?,
)

/**
 * Turns observations into a diagnostic chain.
 *
 * This is pure so the reasoning can be unit-tested without a network. It never asserts
 * a single cause for a failure and never claims a subnet is a VLAN.
 */
object ReachabilityAnalyzer {

    /**
     * Shown whenever the target differs from the current subnet. Required by spec and
     * repeated verbatim, because the misconception it corrects is extremely common.
     */
    const val VLAN_WARNING: String =
        "Changing the IP address does not move the device into another VLAN. If the target " +
            "network belongs to a different VLAN, the switch, access point or router must " +
            "grant access to that VLAN."

    fun analyze(observations: ReachabilityObservations): ReachabilityReport {
        val steps = mutableListOf<DiagnosticStep>()

        val localCidr = observations.localCidr
        val isDirectlyConnected = localCidr != null && localCidr == observations.target

        steps += localAddressStep(observations, isDirectlyConnected)
        val coveringRoute = RouteMatcher.bestMatchForBlock(observations.routes, observations.target)
        steps += routeStep(observations, coveringRoute)
        steps += gatewayStep(observations)
        steps += hostProbeStep(observations)
        steps += dnsStep(observations)

        val reachability = determineReachability(observations, isDirectlyConnected, coveringRoute)
        val conclusion = buildConclusion(observations, reachability, coveringRoute)
        val explanations = if (
            reachability == SubnetReachability.NO_RESPONSE ||
            reachability == SubnetReachability.UNKNOWN ||
            reachability == SubnetReachability.UNREACHABLE
        ) {
            candidateExplanations(observations)
        } else {
            emptyList()
        }

        return ReachabilityReport(
            observations = observations,
            reachability = reachability,
            steps = steps,
            conclusion = conclusion,
            candidateExplanations = explanations,
            warning = if (!isDirectlyConnected) VLAN_WARNING else null,
        )
    }

    private fun localAddressStep(
        observations: ReachabilityObservations,
        isDirectlyConnected: Boolean,
    ): DiagnosticStep {
        val local = observations.localAddress
        return when {
            local == null -> DiagnosticStep(
                title = "This device's address",
                outcome = StepOutcome.INCONCLUSIVE,
                evidence = "No IPv4 address is assigned to the active network.",
                source = EvidenceSource.LINK_PROPERTIES,
            )
            local in observations.target -> DiagnosticStep(
                title = "This device's address",
                outcome = StepOutcome.PASS,
                evidence = "${local.toCanonicalString()} is inside ${observations.target}. " +
                    "The target network is directly connected.",
                source = EvidenceSource.LINK_PROPERTIES,
            )
            else -> DiagnosticStep(
                title = "This device's address",
                outcome = StepOutcome.INFO,
                evidence = "${local.toCanonicalString()}" +
                    (observations.localCidr?.let { " in $it" } ?: "") +
                    " is outside ${observations.target}. That alone does not prevent reachability.",
                source = EvidenceSource.LINK_PROPERTIES,
            )
        }.also { if (isDirectlyConnected) Unit }
    }

    private fun routeStep(
        observations: ReachabilityObservations,
        coveringRoute: RouteEntry?,
    ): DiagnosticStep = when {
        coveringRoute == null -> DiagnosticStep(
            title = "Exposed routes",
            outcome = StepOutcome.INCONCLUSIVE,
            evidence = "No route covering ${observations.target} was found in the routes Android " +
                "exposes. Android exposes only the routes attached to this Network object, so the " +
                "default gateway may still route this network.",
            source = EvidenceSource.LINK_PROPERTIES,
        )
        coveringRoute.isDefaultRoute -> DiagnosticStep(
            title = "Exposed routes",
            outcome = StepOutcome.INFO,
            evidence = "Only the default route (via " +
                "${coveringRoute.gateway?.toCanonicalString() ?: "an unnamed gateway"}) covers " +
                "${observations.target}. Traffic would be handed to the gateway, which may or may " +
                "not forward it.",
            source = EvidenceSource.LINK_PROPERTIES,
        )
        else -> DiagnosticStep(
            title = "Exposed routes",
            outcome = StepOutcome.PASS,
            evidence = "${coveringRoute.destinationText} is routed via " +
                (coveringRoute.gateway?.toCanonicalString() ?: "an on-link interface") +
                " on ${coveringRoute.interfaceName ?: "an unnamed interface"}.",
            source = EvidenceSource.LINK_PROPERTIES,
        )
    }

    private fun gatewayStep(observations: ReachabilityObservations): DiagnosticStep {
        val gateway = observations.defaultGateway
            ?: return DiagnosticStep(
                title = "Gateway reachability",
                outcome = StepOutcome.INCONCLUSIVE,
                evidence = "No default gateway was exposed for the active network.",
                source = EvidenceSource.LINK_PROPERTIES,
            )
        val probeLabel = when (observations.gatewayProbeType) {
            ProbeType.ICMP -> "ICMP"
            ProbeType.TCP_CONNECT -> "TCP connect"
            ProbeType.FALLBACK -> "fallback probe"
            null -> "no probe"
        }
        return when (observations.gatewayResponded) {
            true -> DiagnosticStep(
                title = "Gateway reachability",
                outcome = StepOutcome.PASS,
                evidence = "${gateway.toCanonicalString()} answered a $probeLabel probe.",
                source = probeSource(observations.gatewayProbeType),
            )
            false -> DiagnosticStep(
                title = "Gateway reachability",
                outcome = StepOutcome.INCONCLUSIVE,
                evidence = "${gateway.toCanonicalString()} did not answer a $probeLabel probe. " +
                    "Many gateways are configured not to reply, so this does not prove it is down.",
                source = probeSource(observations.gatewayProbeType),
            )
            null -> DiagnosticStep(
                title = "Gateway reachability",
                outcome = StepOutcome.INFO,
                evidence = "${gateway.toCanonicalString()} was not probed.",
                source = EvidenceSource.NONE,
            )
        }
    }

    private fun hostProbeStep(observations: ReachabilityObservations): DiagnosticStep = when {
        observations.sampledHosts.isEmpty() -> DiagnosticStep(
            title = "Sample hosts in target",
            outcome = StepOutcome.INFO,
            evidence = "No hosts in ${observations.target} were probed.",
            source = EvidenceSource.NONE,
        )
        observations.respondingHosts.isNotEmpty() -> DiagnosticStep(
            title = "Sample hosts in target",
            outcome = StepOutcome.PASS,
            evidence = "${observations.respondingHosts.size} of ${observations.sampledHosts.size} " +
                "probed hosts answered, including " +
                observations.respondingHosts.take(3).joinToString(", ") { it.toCanonicalString() } +
                ". Hosts in this network are reachable from here.",
            source = EvidenceSource.TCP_PROBE,
        )
        else -> DiagnosticStep(
            title = "Sample hosts in target",
            outcome = StepOutcome.INCONCLUSIVE,
            evidence = "None of the ${observations.sampledHosts.size} probed hosts answered. " +
                "Silence is not proof that the network is unreachable — the addresses probed may " +
                "simply have no host on them.",
            source = EvidenceSource.TCP_PROBE,
        )
    }

    private fun dnsStep(observations: ReachabilityObservations): DiagnosticStep = when {
        observations.dnsServers.isEmpty() -> DiagnosticStep(
            title = "DNS",
            outcome = StepOutcome.INFO,
            evidence = "No DNS servers were exposed for this network.",
            source = EvidenceSource.LINK_PROPERTIES,
        )
        else -> DiagnosticStep(
            title = "DNS",
            outcome = StepOutcome.INFO,
            evidence = "Resolvers: " + observations.dnsServers.joinToString(", ") { it.toCanonicalString() } +
                ". DNS does not affect reachability of a numeric address.",
            source = EvidenceSource.LINK_PROPERTIES,
        )
    }

    private fun probeSource(probeType: ProbeType?): EvidenceSource = when (probeType) {
        ProbeType.ICMP -> EvidenceSource.ICMP_PROBE
        ProbeType.TCP_CONNECT, ProbeType.FALLBACK -> EvidenceSource.TCP_PROBE
        null -> EvidenceSource.NONE
    }

    private fun determineReachability(
        observations: ReachabilityObservations,
        isDirectlyConnected: Boolean,
        coveringRoute: RouteEntry?,
    ): SubnetReachability = when {
        observations.localAddress != null && observations.localAddress in observations.target ->
            SubnetReachability.CONNECTED

        observations.respondingHosts.isNotEmpty() -> SubnetReachability.REACHABLE

        coveringRoute != null && !coveringRoute.isDefaultRoute -> SubnetReachability.ROUTED

        observations.sampledHosts.isNotEmpty() -> SubnetReachability.NO_RESPONSE

        else -> SubnetReachability.UNKNOWN
    }.also { if (isDirectlyConnected) Unit }

    private fun buildConclusion(
        observations: ReachabilityObservations,
        reachability: SubnetReachability,
        coveringRoute: RouteEntry?,
    ): String = when (reachability) {
        SubnetReachability.CONNECTED ->
            "${observations.target} is directly connected: this device holds " +
                "${observations.localAddress?.toCanonicalString()} inside it."

        SubnetReachability.REACHABLE ->
            "Hosts in ${observations.target} answered probes from this device. You do not need a " +
                "${observations.target.networkAddress.toCanonicalString().substringBeforeLast('.')}.x " +
                "address on this device merely to communicate with hosts there."

        SubnetReachability.ROUTED ->
            "${observations.target} appears to be routed from your current network via " +
                (coveringRoute?.gateway?.toCanonicalString() ?: "an on-link interface") +
                ". No host in the range answered yet, so this is based on the route table alone."

        SubnetReachability.NO_RESPONSE ->
            "No host in ${observations.target} answered. This app cannot determine from the " +
                "available evidence whether the network is unreachable, filtered, or simply empty."

        SubnetReachability.UNREACHABLE ->
            "A probe to ${observations.target} was actively reported as unreachable."

        SubnetReachability.UNKNOWN ->
            "There is not enough evidence to say whether ${observations.target} is reachable."
    }

    /**
     * Every plausible cause, unranked.
     *
     * Ranking these without evidence would be exactly the kind of confident guess this
     * app exists to avoid.
     */
    private fun candidateExplanations(observations: ReachabilityObservations): List<CandidateExplanation> {
        val explanations = mutableListOf(
            CandidateExplanation(
                "No routing between the networks",
                "The router may have no path to ${observations.target}, or may not be configured " +
                    "to forward traffic between these networks.",
            ),
            CandidateExplanation(
                "VLAN separation",
                "The target network may sit on a different VLAN that this port or SSID is not a " +
                    "member of. Note that a subnet number is not a VLAN ID — this app cannot see VLAN tags.",
            ),
            CandidateExplanation(
                "Firewall or access control list",
                "A firewall rule or ACL between the networks may be dropping the probes.",
            ),
            CandidateExplanation(
                "Client isolation",
                "The access point may isolate wireless clients, blocking traffic to other hosts.",
            ),
            CandidateExplanation(
                "Hosts silently dropping probes",
                "Hosts may exist but be configured not to answer ICMP or to refuse the ports probed.",
            ),
            CandidateExplanation(
                "No live hosts in the range",
                "The addresses probed may simply be unused.",
            ),
            CandidateExplanation(
                "The target subnet assumption is wrong",
                "The network may not use ${observations.target} at all.",
            ),
        )
        if (!observations.icmpAvailable) {
            explanations += CandidateExplanation(
                "ICMP was unavailable on this device",
                "Probes fell back to TCP connect attempts, which a firewall may block even where " +
                    "ICMP would have succeeded.",
            )
        }
        return explanations
    }
}
