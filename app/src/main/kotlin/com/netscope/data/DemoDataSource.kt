package com.netscope.data

import com.netscope.core.model.Confidence
import com.netscope.core.model.DeviceType
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.DiscoveredService
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.HostState
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.ProbeType
import com.netscope.core.model.RouteEntry
import com.netscope.core.model.RouteKind
import com.netscope.core.model.RouteOrigin
import com.netscope.core.model.Unavailability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A fixed topology for development and UI testing without a physical LAN.
 *
 * Every value carries EvidenceSource.DEMO, which the UI renders as a DEMO DATA chip, so
 * a demo scan can never be mistaken for a real one.
 */
@Singleton
class DemoDataSource @Inject constructor() {

    val localSubnet: Ipv4Cidr = Ipv4Cidr.parse("10.0.2.0/24")!!
    val routedSubnet: Ipv4Cidr = Ipv4Cidr.parse("10.0.7.0/24")!!
    val gateway: Ipv4Address = Ipv4Address.parse("10.0.2.1")!!
    val thisDevice: Ipv4Address = Ipv4Address.parse("10.0.2.37")!!

    fun routes(): List<RouteEntry> = listOf(
        RouteEntry(
            destination = Ipv4Cidr.parse("0.0.0.0/0"),
            destinationV6 = null,
            gateway = gateway,
            interfaceName = "wlan0",
            isDefaultRoute = true,
            kind = RouteKind.UNICAST,
            origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
            networkLabel = "Wi-Fi (demo)",
        ),
        RouteEntry(
            destination = localSubnet,
            destinationV6 = null,
            gateway = null,
            interfaceName = "wlan0",
            isDefaultRoute = false,
            kind = RouteKind.UNICAST,
            origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
            networkLabel = "Wi-Fi (demo)",
        ),
        RouteEntry(
            destination = routedSubnet,
            destinationV6 = null,
            gateway = gateway,
            interfaceName = "wlan0",
            isDefaultRoute = false,
            kind = RouteKind.UNICAST,
            origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
            networkLabel = "Wi-Fi (demo)",
        ),
    )

    fun devices(): List<DiscoveredDevice> = listOf(
        device("10.0.2.1", "gateway.lan", "Demo Router", DeviceType.ROUTER, 1.4, isGateway = true,
            services = listOf("HTTP" to 80, "HTTPS" to 443, "DNS" to 53)),
        device("10.0.2.12", "workstation.lan", "Demo PC", DeviceType.COMPUTER, 3.2,
            services = listOf("SMB" to 445, "RDP" to 3389)),
        device("10.0.2.18", "printer.lan", "Demo Printer", DeviceType.PRINTER, 6.8,
            services = listOf("IPP" to 631, "Raw printing" to 9100)),
        device("10.0.2.24", "nas.lan", "Demo NAS", DeviceType.NAS, 2.1,
            services = listOf("SMB" to 445, "NFS" to 2049, "HTTP" to 5000)),
        device("10.0.2.37", null, "This device", DeviceType.THIS_DEVICE, 0.0, isThisDevice = true),
        device("10.0.7.5", "server.remote.lan", "Demo Server", DeviceType.SERVER, 8.9,
            services = listOf("SSH" to 22, "HTTPS" to 443)),
        device("10.0.7.22", "ws2.remote.lan", "Demo Workstation", DeviceType.COMPUTER, 9.4),
    )

    private fun device(
        ip: String,
        hostname: String?,
        friendlyName: String,
        type: DeviceType,
        latency: Double,
        isGateway: Boolean = false,
        isThisDevice: Boolean = false,
        services: List<Pair<String, Int>> = emptyList(),
    ): DiscoveredDevice {
        val now = System.currentTimeMillis()
        return DiscoveredDevice(
            ipv4 = Ipv4Address.parse(ip),
            hostname = hostname?.let {
                Evidence.observed(it, EvidenceSource.DEMO, Confidence.HIGH, now, DEMO_DETAIL)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now, DEMO_DETAIL),
            friendlyName = Evidence.observed(
                friendlyName, EvidenceSource.DEMO, Confidence.HIGH, now, DEMO_DETAIL,
            ),
            mac = Evidence.unavailable(
                Unavailability.RESTRICTED_BY_ANDROID, now,
                "Demo data does not invent MAC addresses, because a real scan could not read them.",
            ),
            vendor = Evidence.unavailable(Unavailability.NOT_DISCOVERED, now, DEMO_DETAIL),
            deviceType = Evidence.observed(type, EvidenceSource.DEMO, Confidence.HIGH, now, DEMO_DETAIL),
            state = HostState.RESPONDING,
            latencyMillis = latency,
            probeType = ProbeType.ICMP,
            services = services.map { (name, port) ->
                DiscoveredService(
                    port = port,
                    protocol = "tcp",
                    name = name,
                    serviceType = null,
                    detail = DEMO_DETAIL,
                    source = EvidenceSource.DEMO,
                    confidence = Confidence.HIGH,
                    observedAtEpochMillis = now,
                )
            },
            discoverySources = setOf(EvidenceSource.DEMO),
            firstSeenEpochMillis = now,
            lastSeenEpochMillis = now,
            isThisDevice = isThisDevice,
            isGateway = isGateway,
        )
    }

    private companion object {
        const val DEMO_DETAIL = "Fabricated demo data. Nothing here was measured on a real network."
    }
}
