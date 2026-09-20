package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun v4(text: String) = Ipv4Address.parse(text)!!
private fun cidr(text: String) = Ipv4Cidr.parse(text)!!

private fun route(
    destination: String,
    gateway: String? = null,
    isDefault: Boolean = false,
    iface: String = "wlan0",
) = RouteEntry(
    destination = cidr(destination),
    destinationV6 = null,
    gateway = gateway?.let { v4(it) },
    interfaceName = iface,
    isDefaultRoute = isDefault,
    kind = RouteKind.UNICAST,
    origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
    networkLabel = "Wi-Fi",
)

class RouteMatcherTest {

    private val routes = listOf(
        route("0.0.0.0/0", gateway = "10.0.2.1", isDefault = true),
        route("10.0.2.0/24"),
        route("10.0.7.0/24", gateway = "10.0.2.1"),
        route("10.0.0.0/8", gateway = "10.0.2.254"),
    )

    @Test
    fun `picks the longest matching prefix`() {
        val match = RouteMatcher.bestMatch(routes, v4("10.0.2.37"))
        assertThat(match!!.destination.toString()).isEqualTo("10.0.2.0/24")
    }

    @Test
    fun `falls back to a shorter prefix`() {
        val match = RouteMatcher.bestMatch(routes, v4("10.5.5.5"))
        assertThat(match!!.destination.toString()).isEqualTo("10.0.0.0/8")
    }

    @Test
    fun `falls back to the default route`() {
        val match = RouteMatcher.bestMatch(routes, v4("8.8.8.8"))
        assertThat(match!!.isDefaultRoute).isTrue()
    }

    @Test
    fun `matches a whole block only when the route covers it`() {
        val covering = RouteMatcher.bestMatchForBlock(routes, cidr("10.0.7.0/24"))
        assertThat(covering!!.destination.toString()).isEqualTo("10.0.7.0/24")

        // A /8 covers a /24 inside it, but a /24 never covers a /16.
        val wide = RouteMatcher.bestMatchForBlock(routes, cidr("10.0.0.0/16"))
        assertThat(wide!!.destination.toString()).isEqualTo("10.0.0.0/8")
    }

    @Test
    fun `returns null when nothing matches`() {
        val withoutDefault = routes.filterNot { it.isDefaultRoute }
        assertThat(RouteMatcher.bestMatch(withoutDefault, v4("8.8.8.8"))).isNull()
    }

    @Test
    fun `finds routes nested inside a target block`() {
        val inside = RouteMatcher.routesInsideBlock(routes, cidr("10.0.0.0/8"))
        assertThat(inside.map { it.destination.toString() })
            .containsExactly("10.0.2.0/24", "10.0.7.0/24")
    }
}

class EvidenceResolverTest {

    @Test
    fun `user evidence always wins`() {
        val resolved = EvidenceResolver.resolve(
            listOf(
                Evidence.observed("router.lan", EvidenceSource.DNS_PTR, Confidence.HIGH, 100),
                Evidence.observed("My Router", EvidenceSource.USER, Confidence.LOW, 1),
            ),
        )
        assertThat(resolved.value).isEqualTo("My Router")
    }

    @Test
    fun `higher confidence wins`() {
        val resolved = EvidenceResolver.resolve(
            listOf(
                Evidence.observed("guess", EvidenceSource.INFERENCE, Confidence.LOW, 100),
                Evidence.observed("nas.local", EvidenceSource.MDNS, Confidence.HIGH, 50),
            ),
        )
        assertThat(resolved.value).isEqualTo("nas.local")
    }

    @Test
    fun `source rank breaks a confidence tie`() {
        val resolved = EvidenceResolver.resolve(
            listOf(
                Evidence.observed("from-oui", EvidenceSource.OUI, Confidence.MEDIUM, 100),
                Evidence.observed("from-mdns", EvidenceSource.MDNS, Confidence.MEDIUM, 50),
            ),
        )
        assertThat(resolved.value).isEqualTo("from-mdns")
    }

    @Test
    fun `recency breaks a remaining tie`() {
        val resolved = EvidenceResolver.resolve(
            listOf(
                Evidence.observed("old", EvidenceSource.MDNS, Confidence.MEDIUM, 10),
                Evidence.observed("new", EvidenceSource.MDNS, Confidence.MEDIUM, 900),
            ),
        )
        assertThat(resolved.value).isEqualTo("new")
    }

    @Test
    fun `absence keeps the most specific reason`() {
        val resolved = EvidenceResolver.resolve<String>(
            listOf(
                Evidence.unavailable(Unavailability.NOT_DISCOVERED),
                Evidence.unavailable(Unavailability.PERMISSION_REQUIRED),
            ),
        )
        assertThat(resolved.unavailability).isEqualTo(Unavailability.PERMISSION_REQUIRED)
    }

    @Test
    fun `empty input is not attempted`() {
        assertThat(EvidenceResolver.resolve<String>(emptyList()).unavailability)
            .isEqualTo(Unavailability.NOT_ATTEMPTED)
    }

    @Test
    fun `a present value beats any absence`() {
        val resolved = EvidenceResolver.resolve(
            listOf(
                Evidence.unavailable<String>(Unavailability.PERMISSION_REQUIRED),
                Evidence.observed("found", EvidenceSource.DNS_PTR, Confidence.LOW, 5),
            ),
        )
        assertThat(resolved.value).isEqualTo("found")
    }
}

class DeviceMergerTest {

    private fun device(ip: String, block: DiscoveredDevice.() -> DiscoveredDevice = { this }) =
        DiscoveredDevice(ipv4 = v4(ip)).block()

    @Test
    fun `merges evidence from two sources`() {
        val fromMdns = device("10.0.2.18") {
            copy(
                friendlyName = Evidence.observed("Office Printer", EvidenceSource.MDNS, Confidence.HIGH, 10),
                discoverySources = setOf(EvidenceSource.MDNS),
                state = HostState.ANNOUNCED,
            )
        }
        val fromProbe = device("10.0.2.18") {
            copy(
                hostname = Evidence.observed("printer.lan", EvidenceSource.DNS_PTR, Confidence.HIGH, 20),
                discoverySources = setOf(EvidenceSource.TCP_PROBE),
                state = HostState.RESPONDING,
                latencyMillis = 4.2,
                probeType = ProbeType.TCP_CONNECT,
            )
        }
        val merged = DeviceMerger.merge(fromMdns, fromProbe)
        assertThat(merged.friendlyName.value).isEqualTo("Office Printer")
        assertThat(merged.hostname.value).isEqualTo("printer.lan")
        assertThat(merged.state).isEqualTo(HostState.RESPONDING)
        assertThat(merged.discoverySources)
            .containsExactly(EvidenceSource.MDNS, EvidenceSource.TCP_PROBE)
    }

    @Test
    fun `prefers an icmp latency over a tcp latency`() {
        val tcp = device("10.0.2.1") { copy(latencyMillis = 9.0, probeType = ProbeType.TCP_CONNECT) }
        val icmp = device("10.0.2.1") { copy(latencyMillis = 3.0, probeType = ProbeType.ICMP) }
        val merged = DeviceMerger.merge(tcp, icmp)
        assertThat(merged.probeType).isEqualTo(ProbeType.ICMP)
        assertThat(merged.latencyMillis).isEqualTo(3.0)
    }

    @Test
    fun `deduplicates a list by key`() {
        val merged = DeviceMerger.mergeAll(
            listOf(
                device("10.0.2.5"),
                device("10.0.2.5") { copy(state = HostState.RESPONDING) },
                device("10.0.2.9"),
            ),
        )
        assertThat(merged).hasSize(2)
        assertThat(merged.first().state).isEqualTo(HostState.RESPONDING)
    }

    @Test
    fun `sorts merged devices by address`() {
        val merged = DeviceMerger.mergeAll(
            listOf(device("10.0.2.100"), device("10.0.2.9"), device("10.0.2.37")),
        )
        assertThat(merged.map { it.key })
            .containsExactly("10.0.2.9", "10.0.2.37", "10.0.2.100").inOrder()
    }

    @Test
    fun `refuses to merge different hosts`() {
        val error = runCatching { DeviceMerger.merge(device("10.0.2.1"), device("10.0.2.2")) }
        assertThat(error.isFailure).isTrue()
    }
}

class ScanComparatorTest {

    private fun device(ip: String, hostname: String? = null, services: List<String> = emptyList()) =
        DiscoveredDevice(
            ipv4 = v4(ip),
            hostname = hostname?.let {
                Evidence.observed(it, EvidenceSource.DNS_PTR, Confidence.HIGH, 1)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
            services = services.map {
                DiscoveredService(null, "tcp", it, null, null, EvidenceSource.MDNS, Confidence.HIGH, 1)
            },
        )

    @Test
    fun `detects new and disappeared devices`() {
        val diff = ScanComparator.compare(
            previous = listOf(device("10.0.2.1"), device("10.0.2.5")),
            current = listOf(device("10.0.2.1"), device("10.0.2.9")),
        )
        assertThat(diff.newDevices.map { it.key }).containsExactly("10.0.2.9")
        assertThat(diff.disappearedDevices.map { it.key }).containsExactly("10.0.2.5")
        assertThat(diff.hasChanges).isTrue()
    }

    @Test
    fun `detects hostname and service changes`() {
        val diff = ScanComparator.compare(
            previous = listOf(device("10.0.2.1", "old.lan", listOf("http"))),
            current = listOf(device("10.0.2.1", "new.lan", listOf("http", "ssh"))),
        )
        assertThat(diff.hostnameChanged).hasSize(1)
        assertThat(diff.hostnameChanged.first().before).isEqualTo("old.lan")
        assertThat(diff.hostnameChanged.first().after).isEqualTo("new.lan")
        assertThat(diff.servicesChanged).hasSize(1)
    }

    @Test
    fun `reports no changes for identical scans`() {
        val scan = listOf(device("10.0.2.1", "router.lan"))
        assertThat(ScanComparator.compare(scan, scan).hasChanges).isFalse()
    }

    @Test
    fun `detects an ip change only when a mac ties the records together`() {
        val mac = MacAddress.parse("AA:BB:CC:DD:EE:FF")!!
        val before = DiscoveredDevice(
            ipv4 = v4("10.0.2.5"),
            mac = Evidence.observed(mac, EvidenceSource.SSDP, Confidence.HIGH, 1),
        )
        val after = DiscoveredDevice(
            ipv4 = v4("10.0.2.6"),
            mac = Evidence.observed(mac, EvidenceSource.SSDP, Confidence.HIGH, 2),
        )
        val diff = ScanComparator.compare(listOf(before), listOf(after))
        assertThat(diff.addressChanged).hasSize(1)
        assertThat(diff.addressChanged.first().before).isEqualTo("10.0.2.5")
        assertThat(diff.addressChanged.first().after).isEqualTo("10.0.2.6")
    }
}
