package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun v4(text: String) = Ipv4Address.parse(text)!!
private fun cidr(text: String) = Ipv4Cidr.parse(text)!!

private fun route(
    destination: String,
    gateway: String? = null,
    isDefault: Boolean = false,
    kind: RouteKind = RouteKind.UNICAST,
) = RouteEntry(
    destination = cidr(destination),
    destinationV6 = null,
    gateway = gateway?.let { v4(it) },
    interfaceName = "wlan0",
    isDefaultRoute = isDefault,
    kind = kind,
    origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
    networkLabel = "Wi-Fi",
)

private fun snapshot(routes: List<RouteEntry>, address: String = "10.0.2.37") = NetworkSnapshot(
    networkId = "net",
    interfaceName = Evidence.observed("wlan0", EvidenceSource.LINK_PROPERTIES, Confidence.HIGH, 1),
    transports = listOf(TransportType.WIFI),
    ipv4Addresses = listOf(v4(address) to 24),
    ipv6Addresses = emptyList(),
    routes = routes,
    dnsServers = emptyList(),
    privateDnsActive = false,
    privateDnsServerName = null,
    domains = null,
    mtu = Evidence.unavailable(Unavailability.NOT_DISCOVERED),
    httpProxy = null,
    hasValidatedInternet = Evidence.observed(true, EvidenceSource.NETWORK_CAPABILITIES, Confidence.HIGH, 1),
    isMetered = Evidence.observed(false, EvidenceSource.NETWORK_CAPABILITIES, Confidence.HIGH, 1),
    isDefaultNetwork = true,
    isVpn = false,
    linkDownstreamKbps = null,
    linkUpstreamKbps = null,
    observedAtEpochMillis = 1,
)

class TopologyBuilderTest {

    private val routes = listOf(
        route("0.0.0.0/0", gateway = "10.0.2.1", isDefault = true),
        route("10.0.2.0/24"),
        route("10.0.7.0/24", gateway = "10.0.2.1"),
    )

    @Test
    fun `an absent network yields an empty topology`() {
        val topology = TopologyBuilder.build(null)
        assertThat(topology.subnets).isEmpty()
        assertThat(topology.localAddress).isNull()
    }

    @Test
    fun `the local subnet is reported as connected`() {
        val topology = TopologyBuilder.build(snapshot(routes))
        val local = topology.subnets.first()
        assertThat(local.cidr.toString()).isEqualTo("10.0.2.0/24")
        assertThat(local.relation).isEqualTo(SubnetRelation.CONNECTED)
        assertThat(local.evidence).contains("directly connected")
    }

    @Test
    fun `a specific route produces a routed subnet`() {
        val topology = TopologyBuilder.build(snapshot(routes))
        val routed = topology.subnets.first { it.cidr.toString() == "10.0.7.0/24" }
        assertThat(routed.relation).isEqualTo(SubnetRelation.ROUTED)
        assertThat(routed.gateway?.toCanonicalString()).isEqualTo("10.0.2.1")
        assertThat(topology.hasRoutedSubnets).isTrue()
    }

    @Test
    fun `the default route is never drawn as a subnet`() {
        val topology = TopologyBuilder.build(snapshot(routes))
        // 0.0.0.0/0 covers every destination; showing it as a branch would imply the
        // whole internet is a neighbouring subnet.
        assertThat(topology.subnets.map { it.cidr.toString() }).doesNotContain("0.0.0.0/0")
        assertThat(topology.defaultGateway?.toCanonicalString()).isEqualTo("10.0.2.1")
    }

    @Test
    fun `host routes are not treated as subnets`() {
        val withHostRoute = routes + route("10.0.9.5/32", gateway = "10.0.2.1")
        val topology = TopologyBuilder.build(snapshot(withHostRoute))
        assertThat(topology.subnets.map { it.cidr.toString() }).doesNotContain("10.0.9.5/32")
    }

    @Test
    fun `unreachable routes are excluded`() {
        val withUnreachable = routes + route("192.168.5.0/24", kind = RouteKind.UNREACHABLE)
        val topology = TopologyBuilder.build(snapshot(withUnreachable))
        assertThat(topology.subnets.map { it.cidr.toString() }).doesNotContain("192.168.5.0/24")
    }

    @Test
    fun `the connected subnet sorts first`() {
        val topology = TopologyBuilder.build(snapshot(routes))
        assertThat(topology.subnets.first().relation).isEqualTo(SubnetRelation.CONNECTED)
    }

    @Test
    fun `device counts are attached only when known`() {
        val topology = TopologyBuilder.build(
            snapshot(routes),
            deviceCounts = mapOf("10.0.2.0/24" to 37),
        )
        val local = topology.subnets.first { it.cidr.toString() == "10.0.2.0/24" }
        val routed = topology.subnets.first { it.cidr.toString() == "10.0.7.0/24" }
        assertThat(local.deviceCount).isEqualTo(37)
        // Never scanned, so no count is invented.
        assertThat(routed.deviceCount).isNull()
    }

    @Test
    fun `duplicate routes do not duplicate subnets`() {
        val duplicated = routes + route("10.0.7.0/24", gateway = "10.0.2.1")
        val topology = TopologyBuilder.build(snapshot(duplicated))
        assertThat(topology.subnets.count { it.cidr.toString() == "10.0.7.0/24" }).isEqualTo(1)
    }
}
