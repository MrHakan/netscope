package com.netscope.core.model

/** How a route row was obtained. Never mixed silently in the UI. */
enum class RouteOrigin {
    /** LinkProperties.getRoutes() for a specific Network. Authoritative for that Network. */
    ANDROID_LINK_PROPERTIES,

    /** Derived from an interface address + prefix length. */
    DERIVED_FROM_INTERFACE,

    /** Entered by the user for analysis purposes. */
    USER,
}

enum class RouteKind { UNICAST, UNREACHABLE, THROW, UNKNOWN }

/**
 * One row of the route table as Android exposes it.
 *
 * This is deliberately not called "the routing table": Android only ever shows the
 * routes attached to a Network object, which is a subset of the kernel table.
 */
data class RouteEntry(
    val destination: Ipv4Cidr?,
    val destinationV6: Ipv6Cidr?,
    val gateway: IpAddress?,
    val interfaceName: String?,
    val isDefaultRoute: Boolean,
    val kind: RouteKind,
    val origin: RouteOrigin,
    val networkLabel: String?,
) {
    val prefixLength: Int
        get() = destination?.prefixLength ?: destinationV6?.prefixLength ?: 0

    val destinationText: String
        get() = destination?.toString() ?: destinationV6?.toString() ?: "unknown"

    /** A route with no gateway is on-link: the destination is directly connected. */
    val isOnLink: Boolean get() = gateway == null
}

/**
 * Longest-prefix matching over the routes Android exposed.
 *
 * This reproduces the kernel's selection rule for the subset of routes we can see. It
 * is not a promise about what the kernel will actually do, and callers must label the
 * result accordingly.
 */
object RouteMatcher {

    /** Returns the most specific route covering [address], or null if none matches. */
    fun bestMatch(routes: List<RouteEntry>, address: Ipv4Address): RouteEntry? =
        routes.asSequence()
            .filter { it.destination != null && address in it.destination }
            .maxByOrNull { it.destination!!.prefixLength }

    fun bestMatch(routes: List<RouteEntry>, address: Ipv6Address): RouteEntry? =
        routes.asSequence()
            .filter { it.destinationV6 != null && address in it.destinationV6 }
            .maxByOrNull { it.destinationV6!!.prefixLength }

    /**
     * The route that would be selected for a whole target block.
     *
     * A route only "covers" the target if it contains the entire block; a route to a
     * more specific prefix inside the target does not make the target routed.
     */
    fun bestMatchForBlock(routes: List<RouteEntry>, target: Ipv4Cidr): RouteEntry? =
        routes.asSequence()
            .filter { it.destination != null && target in it.destination!! }
            .maxByOrNull { it.destination!!.prefixLength }

    /** Routes that fall inside the target without covering it, useful as partial evidence. */
    fun routesInsideBlock(routes: List<RouteEntry>, target: Ipv4Cidr): List<RouteEntry> =
        routes.filter { it.destination != null && it.destination!! in target && it.destination != target }

    fun defaultRoutes(routes: List<RouteEntry>): List<RouteEntry> = routes.filter { it.isDefaultRoute }
}
