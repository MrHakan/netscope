package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun v4(text: String) = Ipv4Address.parse(text)!!
private fun cidr(text: String) = Ipv4Cidr.parse(text)!!

class ReachabilityAnalyzerTest {

    private fun observations(
        target: String,
        local: String? = "10.0.2.37",
        routes: List<RouteEntry> = emptyList(),
        gateway: String? = "10.0.2.1",
        gatewayResponded: Boolean? = true,
        sampled: List<String> = emptyList(),
        responding: List<String> = emptyList(),
        icmpAvailable: Boolean = true,
    ) = ReachabilityObservations(
        target = cidr(target),
        localAddress = local?.let { v4(it) },
        localCidr = local?.let { Ipv4Cidr.of(v4(it), 24) },
        routes = routes,
        defaultGateway = gateway?.let { v4(it) },
        gatewayResponded = gatewayResponded,
        gatewayProbeType = ProbeType.ICMP,
        sampledHosts = sampled.map { v4(it) },
        respondingHosts = responding.map { v4(it) },
        dnsServers = listOf(v4("10.0.2.1")),
        hasValidatedInternet = true,
        interfaceName = "wlan0",
        transportLabel = "Wi-Fi",
        icmpAvailable = icmpAvailable,
    )

    private fun route(destination: String, gateway: String?, isDefault: Boolean = false) = RouteEntry(
        destination = cidr(destination),
        destinationV6 = null,
        gateway = gateway?.let { v4(it) },
        interfaceName = "wlan0",
        isDefaultRoute = isDefault,
        kind = RouteKind.UNICAST,
        origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
        networkLabel = "Wi-Fi",
    )

    @Test
    fun `reports connected when the device sits in the target`() {
        val report = ReachabilityAnalyzer.analyze(observations(target = "10.0.2.0/24"))
        assertThat(report.reachability).isEqualTo(SubnetReachability.CONNECTED)
        assertThat(report.conclusion).contains("directly connected")
        // No VLAN warning is needed when the target is the current subnet.
        assertThat(report.warning).isNull()
        assertThat(report.candidateExplanations).isEmpty()
    }

    @Test
    fun `reports routed when a specific route covers the target`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(
                target = "10.0.7.0/24",
                routes = listOf(route("10.0.7.0/24", "10.0.2.1")),
            ),
        )
        assertThat(report.reachability).isEqualTo(SubnetReachability.ROUTED)
        assertThat(report.conclusion).contains("appears to be routed")
        assertThat(report.warning).isEqualTo(ReachabilityAnalyzer.VLAN_WARNING)
    }

    @Test
    fun `a default route alone is not treated as a specific route`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(
                target = "10.0.7.0/24",
                routes = listOf(route("0.0.0.0/0", "10.0.2.1", isDefault = true)),
                sampled = listOf("10.0.7.1"),
            ),
        )
        assertThat(report.reachability).isEqualTo(SubnetReachability.NO_RESPONSE)
        val routeStep = report.steps.first { it.title == "Exposed routes" }
        assertThat(routeStep.outcome).isEqualTo(StepOutcome.INFO)
        assertThat(routeStep.evidence).contains("Only the default route")
    }

    @Test
    fun `a responding host outranks the route table`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(
                target = "10.0.7.0/24",
                sampled = listOf("10.0.7.5", "10.0.7.22"),
                responding = listOf("10.0.7.5"),
            ),
        )
        assertThat(report.reachability).isEqualTo(SubnetReachability.REACHABLE)
        assertThat(report.conclusion).contains("You do not need a")
    }

    @Test
    fun `silence produces unranked candidate explanations`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(target = "10.0.7.0/24", sampled = listOf("10.0.7.1", "10.0.7.2")),
        )
        assertThat(report.reachability).isEqualTo(SubnetReachability.NO_RESPONSE)
        assertThat(report.candidateExplanations).isNotEmpty()
        // The app must never claim to know which cause applies.
        assertThat(report.conclusion).contains("cannot determine")
        val vlanExplanation = report.candidateExplanations.first { it.title.contains("VLAN") }
        assertThat(vlanExplanation.detail).contains("a subnet number is not a VLAN ID")
    }

    @Test
    fun `adds an icmp caveat when icmp was unavailable`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(target = "10.0.7.0/24", sampled = listOf("10.0.7.1"), icmpAvailable = false),
        )
        assertThat(report.candidateExplanations.map { it.title })
            .contains("ICMP was unavailable on this device")
    }

    @Test
    fun `reports unknown when nothing was probed and nothing is routed`() {
        val report = ReachabilityAnalyzer.analyze(observations(target = "192.168.50.0/24"))
        assertThat(report.reachability).isEqualTo(SubnetReachability.UNKNOWN)
    }

    @Test
    fun `an unanswered gateway is inconclusive rather than a failure`() {
        val report = ReachabilityAnalyzer.analyze(
            observations(target = "10.0.7.0/24", gatewayResponded = false),
        )
        val step = report.steps.first { it.title == "Gateway reachability" }
        assertThat(step.outcome).isEqualTo(StepOutcome.INCONCLUSIVE)
        assertThat(step.evidence).contains("does not prove it is down")
    }

    @Test
    fun `every step carries evidence`() {
        val report = ReachabilityAnalyzer.analyze(observations(target = "10.0.7.0/24"))
        assertThat(report.steps).isNotEmpty()
        for (step in report.steps) {
            assertThat(step.evidence).isNotEmpty()
        }
    }
}

class DeviceTypeInferenceTest {

    @Test
    fun `the gateway is a router with high confidence`() {
        val result = DeviceTypeInference.infer(
            isGateway = true, isThisDevice = false, hostname = null,
            mdnsServiceTypes = emptyList(), openPorts = emptyList(),
            upnpDeviceType = null, vendor = null, observedAtEpochMillis = 1,
        )
        assertThat(result.value).isEqualTo(DeviceType.ROUTER)
        assertThat(result.confidence).isEqualTo(Confidence.HIGH)
        assertThat(result.isInferred).isTrue()
    }

    @Test
    fun `no evidence yields no guess`() {
        val result = DeviceTypeInference.infer(
            isGateway = false, isThisDevice = false, hostname = null,
            mdnsServiceTypes = emptyList(), openPorts = emptyList(),
            upnpDeviceType = null, vendor = null, observedAtEpochMillis = 1,
        )
        assertThat(result.value).isNull()
        assertThat(result.unavailability).isEqualTo(Unavailability.NOT_DISCOVERED)
    }

    @Test
    fun `agreeing sources raise confidence`() {
        val weak = DeviceTypeInference.infer(
            isGateway = false, isThisDevice = false, hostname = null,
            mdnsServiceTypes = listOf("_ipp._tcp"), openPorts = emptyList(),
            upnpDeviceType = null, vendor = null, observedAtEpochMillis = 1,
        )
        assertThat(weak.value).isEqualTo(DeviceType.PRINTER)
        assertThat(weak.confidence).isEqualTo(Confidence.LOW)

        val strong = DeviceTypeInference.infer(
            isGateway = false, isThisDevice = false, hostname = "office-printer",
            mdnsServiceTypes = listOf("_ipp._tcp"), openPorts = listOf(631),
            upnpDeviceType = null, vendor = null, observedAtEpochMillis = 1,
        )
        assertThat(strong.value).isEqualTo(DeviceType.PRINTER)
        assertThat(strong.confidence).isEqualTo(Confidence.HIGH)
        assertThat(strong.detail).contains("Inferred from")
    }

    @Test
    fun `this device is identified directly, not inferred`() {
        val result = DeviceTypeInference.infer(
            isGateway = false, isThisDevice = true, hostname = null,
            mdnsServiceTypes = emptyList(), openPorts = emptyList(),
            upnpDeviceType = null, vendor = null, observedAtEpochMillis = 1,
        )
        assertThat(result.value).isEqualTo(DeviceType.THIS_DEVICE)
        assertThat(result.isInferred).isFalse()
    }
}

class OuiLookupTest {

    private val lookup = OuiLookup.parse(
        sequenceOf(
            "# comment line",
            "",
            "B827EB\tRaspberry Pi Foundation",
            "001B63\tApple, Inc.",
            "malformed line without a tab",
        ),
    )

    @Test
    fun `parses the offline table`() {
        assertThat(lookup.size).isEqualTo(2)
    }

    @Test
    fun `resolves a known oui`() {
        val mac = MacAddress.parse("B8:27:EB:11:22:33")!!
        val vendor = lookup.vendorFor(mac, 1)
        assertThat(vendor.value).isEqualTo("Raspberry Pi Foundation")
        assertThat(vendor.source).isEqualTo(EvidenceSource.OUI)
    }

    @Test
    fun `an unknown oui is not discovered rather than guessed`() {
        val mac = MacAddress.parse("00:11:22:33:44:55")!!
        val vendor = lookup.vendorFor(mac, 1)
        assertThat(vendor.value).isNull()
        assertThat(vendor.unavailability).isEqualTo(Unavailability.NOT_DISCOVERED)
    }

    @Test
    fun `a randomised mac is never looked up`() {
        // The 0x02 bit marks a locally administered address.
        val mac = MacAddress.parse("B A:27:EB:11:22:33".replace(" ", ""))!!
        assertThat(mac.isLocallyAdministered).isTrue()
        val vendor = lookup.vendorFor(mac, 1)
        assertThat(vendor.value).isNull()
        assertThat(vendor.unavailability).isEqualTo(Unavailability.NOT_APPLICABLE)
        assertThat(vendor.detail).contains("randomised")
    }
}

class MacAddressTest {

    @Test
    fun `parses common formats`() {
        val expected = "AA:BB:CC:DD:EE:FF"
        assertThat(MacAddress.parse("aa:bb:cc:dd:ee:ff").toString()).isEqualTo(expected)
        assertThat(MacAddress.parse("AA-BB-CC-DD-EE-FF").toString()).isEqualTo(expected)
        assertThat(MacAddress.parse("AABBCCDDEEFF").toString()).isEqualTo(expected)
    }

    @Test
    fun `rejects malformed input`() {
        assertThat(MacAddress.parse("AA:BB:CC:DD:EE")).isNull()
        assertThat(MacAddress.parse("ZZ:BB:CC:DD:EE:FF")).isNull()
        assertThat(MacAddress.parse("")).isNull()
    }

    @Test
    fun `extracts the oui`() {
        assertThat(MacAddress.parse("b8:27:eb:11:22:33")!!.oui).isEqualTo("B827EB")
    }
}

class PingStatisticsTest {

    @Test
    fun `computes loss and jitter`() {
        val stats = PingStatistics.from("10.0.2.1", ProbeType.ICMP, listOf(10.0, null, 14.0, 12.0))
        assertThat(stats.sent).isEqualTo(4)
        assertThat(stats.received).isEqualTo(3)
        assertThat(stats.lossPercent).isEqualTo(25.0)
        assertThat(stats.minMillis).isEqualTo(10.0)
        assertThat(stats.maxMillis).isEqualTo(14.0)
        assertThat(stats.avgMillis).isEqualTo(12.0)
        assertThat(stats.jitterMillis).isEqualTo(3.0)
        assertThat(stats.probeLabel).isEqualTo("ICMP")
    }

    @Test
    fun `total loss produces no latency figures`() {
        val stats = PingStatistics.from("10.0.2.9", ProbeType.TCP_CONNECT, listOf(null, null))
        assertThat(stats.lossPercent).isEqualTo(100.0)
        assertThat(stats.avgMillis).isNull()
        assertThat(stats.jitterMillis).isNull()
        assertThat(stats.probeLabel).isEqualTo("TCP connect")
    }
}

class WifiChannelTest {

    @Test
    fun `maps 2_4 ghz frequencies to channels`() {
        assertThat(channelForFrequency(2412)).isEqualTo(1)
        assertThat(channelForFrequency(2437)).isEqualTo(6)
        assertThat(channelForFrequency(2472)).isEqualTo(13)
        assertThat(channelForFrequency(2484)).isEqualTo(14)
    }

    @Test
    fun `maps 5 ghz frequencies to channels`() {
        assertThat(channelForFrequency(5180)).isEqualTo(36)
        assertThat(channelForFrequency(5745)).isEqualTo(149)
    }

    @Test
    fun `maps 6 ghz frequencies to channels`() {
        assertThat(channelForFrequency(5955)).isEqualTo(1)
        assertThat(channelForFrequency(6175)).isEqualTo(45)
    }

    @Test
    fun `returns null rather than guessing for undefined frequencies`() {
        assertThat(channelForFrequency(1234)).isNull()
        assertThat(channelForFrequency(2413)).isNull()
    }

    @Test
    fun `classifies bands`() {
        assertThat(WifiBand.forFrequency(2437)).isEqualTo(WifiBand.BAND_2_4)
        assertThat(WifiBand.forFrequency(5180)).isEqualTo(WifiBand.BAND_5)
        assertThat(WifiBand.forFrequency(6175)).isEqualTo(WifiBand.BAND_6)
    }

    @Test
    fun `classifies signal quality from raw dbm`() {
        assertThat(SignalQuality.forRssi(-45)).isEqualTo(SignalQuality.EXCELLENT)
        assertThat(SignalQuality.forRssi(-65)).isEqualTo(SignalQuality.FAIR)
        assertThat(SignalQuality.forRssi(-92)).isEqualTo(SignalQuality.VERY_WEAK)
    }
}
