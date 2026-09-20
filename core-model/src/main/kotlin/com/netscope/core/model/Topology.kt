package com.netscope.core.model

/**
 * How a subnet relates to this device.
 *
 * CONNECTED and ROUTED are established from the route table; REACHABLE requires a host
 * to have actually answered. Nothing here is inferred from an address alone.
 */
enum class SubnetRelation(val label: String) {
    CONNECTED("Connected"),
    ROUTED("Routed"),
    UNKNOWN("Unknown"),
}

/** One subnet in the topology view. */
data class SubnetNode(
    val cidr: Ipv4Cidr,
    val relation: SubnetRelation,
    val gateway: Ipv4Address?,
    val interfaceName: String?,
    /** Devices found the last time this subnet was scanned, when that is known. */
    val deviceCount: Int?,
    val evidence: String,
)

/**
 * The logical picture: this device, its gateway, and the subnets reachable through it.
 *
 * This is Layer 3 only. Which physical medium or switch a host sits behind is not
 * knowable from here and is never implied.
 */
data class NetworkTopology(
    val localAddress: Ipv4Address?,
    val localCidr: Ipv4Cidr?,
    val defaultGateway: Ipv4Address?,
    val interfaceName: String?,
    val transportLabel: String?,
    val subnets: List<SubnetNode>,
) {
    val hasRoutedSubnets: Boolean get() = subnets.any { it.relation == SubnetRelation.ROUTED }
}

/**
 * Derives the topology from the routes Android exposed.
 *
 * Only unicast routes are considered, and the default route is excluded: it covers
 * every destination, so drawing it as a subnet would suggest the whole internet is a
 * neighbour of the local network.
 */
object TopologyBuilder {

    fun build(
        snapshot: NetworkSnapshot?,
        deviceCounts: Map<String, Int> = emptyMap(),
    ): NetworkTopology {
        if (snapshot == null) {
            return NetworkTopology(null, null, null, null, null, emptyList())
        }

        val localCidr = snapshot.primaryIpv4Cidr
        val gateway = snapshot.ipv4Gateway
        val nodes = LinkedHashMap<String, SubnetNode>()

        if (localCidr != null) {
            nodes[localCidr.toString()] = SubnetNode(
                cidr = localCidr,
                relation = SubnetRelation.CONNECTED,
                gateway = gateway,
                interfaceName = snapshot.interfaceName.value,
                deviceCount = deviceCounts[localCidr.toString()],
                evidence = "This device holds ${snapshot.primaryIpv4?.toCanonicalString()} " +
                    "inside this block, so it is directly connected.",
            )
        }

        for (route in snapshot.routes) {
            val destination = route.destination ?: continue
            if (route.isDefaultRoute) continue
            if (route.kind != RouteKind.UNICAST && route.kind != RouteKind.UNKNOWN) continue
            // A /32 is a host route, not a subnet worth drawing as a branch.
            if (destination.prefixLength >= 32) continue
            val key = destination.toString()
            if (key in nodes) continue

            val relation = if (localCidr != null && destination == localCidr) {
                SubnetRelation.CONNECTED
            } else {
                SubnetRelation.ROUTED
            }

            nodes[key] = SubnetNode(
                cidr = destination,
                relation = relation,
                gateway = route.gateway as? Ipv4Address,
                interfaceName = route.interfaceName,
                deviceCount = deviceCounts[key],
                evidence = if (route.isOnLink) {
                    "Android exposes an on-link route to $key on " +
                        "${route.interfaceName ?: "this interface"}."
                } else {
                    "Android exposes a route to $key via " +
                        "${route.gateway?.toCanonicalString() ?: "a gateway"}."
                },
            )
        }

        return NetworkTopology(
            localAddress = snapshot.primaryIpv4,
            localCidr = localCidr,
            defaultGateway = gateway,
            interfaceName = snapshot.interfaceName.value,
            transportLabel = snapshot.transportLabel,
            subnets = nodes.values.sortedWith(
                compareBy({ it.relation != SubnetRelation.CONNECTED }, { it.cidr.networkAddress.value }),
            ),
        )
    }
}
