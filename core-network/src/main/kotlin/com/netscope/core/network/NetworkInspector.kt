package com.netscope.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import com.netscope.core.model.Confidence
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.Ipv6Address
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.RouteEntry
import com.netscope.core.model.RouteKind
import com.netscope.core.model.RouteOrigin
import com.netscope.core.model.TransportType
import com.netscope.core.model.Unavailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads network state from ConnectivityManager.
 *
 * LinkProperties and NetworkCapabilities are the authoritative sources on Android;
 * NetworkInterface is only consulted for interface-level detail, and the two models are
 * never silently mixed — every value records which one produced it.
 */
@Singleton
class NetworkInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /** Emits a fresh list of networks whenever connectivity changes. */
    fun observeNetworks(): Flow<List<NetworkSnapshot>> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        fun publish() {
            trySend(runCatching { snapshotAll() }.getOrDefault(emptyList()))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish()
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = publish()
        }

        // registerDefaultNetworkCallback only reports the default network; we want every
        // Network object, so a broad request is registered instead.
        runCatching { manager.registerNetworkCallback(buildAllNetworksRequest(), callback) }
        publish()

        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.conflate()

    private fun buildAllNetworksRequest() = android.net.NetworkRequest.Builder()
        // Clearing the default capabilities is what makes restricted and non-internet
        // networks (local-only, VPN transports) visible.
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    /** Every Network object the platform currently exposes to this app. */
    fun snapshotAll(): List<NetworkSnapshot> {
        val manager = connectivityManager ?: return emptyList()
        val activeNetwork = runCatching { manager.activeNetwork }.getOrNull()
        return manager.allNetworks.mapNotNull { network ->
            snapshot(network, isDefault = network == activeNetwork)
        }.sortedByDescending { it.isDefaultNetwork }
    }

    /** The network that currently carries traffic, if any. */
    fun activeSnapshot(): NetworkSnapshot? {
        val manager = connectivityManager ?: return null
        val active = runCatching { manager.activeNetwork }.getOrNull() ?: return null
        return snapshot(active, isDefault = true)
    }

    private fun snapshot(network: Network, isDefault: Boolean): NetworkSnapshot? {
        val manager = connectivityManager ?: return null
        val linkProperties = runCatching { manager.getLinkProperties(network) }.getOrNull()
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        val now = System.currentTimeMillis()

        val transports = capabilities?.let(::transportsOf) ?: emptyList()
        val interfaceName = linkProperties?.interfaceName

        val ipv4 = mutableListOf<Pair<Ipv4Address, Int>>()
        val ipv6 = mutableListOf<Pair<Ipv6Address, Int>>()
        linkProperties?.linkAddresses?.forEach { linkAddress ->
            when (val address = linkAddress.address) {
                is Inet4Address -> Ipv4Address.fromBytes(address.address)
                    ?.let { ipv4 += it to linkAddress.prefixLength }
                is Inet6Address -> Ipv6Address.fromBytes(address.address, address.scopeId.takeIf { it != 0 }?.toString())
                    ?.let { ipv6 += it to linkAddress.prefixLength }
            }
        }

        val label = transports.firstOrNull()?.label ?: interfaceName ?: "Network"
        val routes = linkProperties?.routes?.mapNotNull { toRouteEntry(it, label) } ?: emptyList()

        return NetworkSnapshot(
            networkId = network.toString(),
            interfaceName = interfaceName?.let {
                Evidence.observed(it, EvidenceSource.LINK_PROPERTIES, Confidence.HIGH, now)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            transports = transports,
            ipv4Addresses = ipv4,
            ipv6Addresses = ipv6,
            routes = routes,
            dnsServers = linkProperties?.dnsServers?.mapNotNull(::toModelAddress) ?: emptyList(),
            privateDnsActive = linkProperties?.isPrivateDnsActive ?: false,
            privateDnsServerName = linkProperties?.privateDnsServerName,
            domains = linkProperties?.domains,
            mtu = linkProperties?.mtu?.takeIf { it > 0 }?.let {
                Evidence.observed(it, EvidenceSource.LINK_PROPERTIES, Confidence.HIGH, now)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            httpProxy = linkProperties?.httpProxy?.toString(),
            hasValidatedInternet = capabilities?.let {
                Evidence.observed(
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    EvidenceSource.NETWORK_CAPABILITIES, Confidence.HIGH, now,
                    detail = "NetworkCapabilities reports NET_CAPABILITY_VALIDATED.",
                )
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            isMetered = capabilities?.let {
                Evidence.observed(
                    !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    EvidenceSource.NETWORK_CAPABILITIES, Confidence.HIGH, now,
                )
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            isDefaultNetwork = isDefault,
            isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false,
            linkDownstreamKbps = capabilities?.linkDownstreamBandwidthKbps,
            linkUpstreamKbps = capabilities?.linkUpstreamBandwidthKbps,
            observedAtEpochMillis = now,
        )
    }

    private fun transportsOf(capabilities: NetworkCapabilities): List<TransportType> = buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(TransportType.WIFI)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(TransportType.ETHERNET)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(TransportType.CELLULAR)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(TransportType.VPN)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(TransportType.BLUETOOTH)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) add(TransportType.LOWPAN)
        if (isEmpty()) add(TransportType.UNKNOWN)
    }

    private fun toRouteEntry(route: RouteInfo, networkLabel: String): RouteEntry? {
        val destination = route.destination ?: return null
        val prefixLength = destination.prefixLength
        val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (route.type) {
                RouteInfo.RTN_UNICAST -> RouteKind.UNICAST
                RouteInfo.RTN_UNREACHABLE -> RouteKind.UNREACHABLE
                RouteInfo.RTN_THROW -> RouteKind.THROW
                else -> RouteKind.UNKNOWN
            }
        } else {
            RouteKind.UNKNOWN
        }

        return when (val address = destination.address) {
            is Inet4Address -> {
                val base = Ipv4Address.fromBytes(address.address) ?: return null
                RouteEntry(
                    destination = Ipv4Cidr.of(base, prefixLength),
                    destinationV6 = null,
                    gateway = route.gateway?.let(::toModelAddress)?.takeUnless { isWildcard(route.gateway) },
                    interfaceName = route.`interface`,
                    isDefaultRoute = route.isDefaultRoute,
                    kind = kind,
                    origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
                    networkLabel = networkLabel,
                )
            }
            is Inet6Address -> {
                val base = Ipv6Address.fromBytes(address.address) ?: return null
                RouteEntry(
                    destination = null,
                    destinationV6 = com.netscope.core.model.Ipv6Cidr.of(base, prefixLength),
                    gateway = route.gateway?.let(::toModelAddress)?.takeUnless { isWildcard(route.gateway) },
                    interfaceName = route.`interface`,
                    isDefaultRoute = route.isDefaultRoute,
                    kind = kind,
                    origin = RouteOrigin.ANDROID_LINK_PROPERTIES,
                    networkLabel = networkLabel,
                )
            }
            else -> null
        }
    }

    /** Android reports on-link routes with an all-zero gateway rather than none. */
    private fun isWildcard(address: InetAddress?): Boolean =
        address != null && address.address.all { it.toInt() == 0 }

    private fun toModelAddress(address: InetAddress): com.netscope.core.model.IpAddress? = when (address) {
        is Inet4Address -> Ipv4Address.fromBytes(address.address)
        is Inet6Address -> Ipv6Address.fromBytes(address.address)
        else -> null
    }

    /**
     * Interface-level detail from the Linux view.
     *
     * Kept strictly separate from the Android view: this can list interfaces Android
     * has no Network object for, and it must never be presented as an Android guarantee.
     */
    fun interfaceDetails(): List<InterfaceDetail> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { nic ->
            InterfaceDetail(
                name = nic.name,
                displayName = nic.displayName,
                isUp = runCatching { nic.isUp }.getOrDefault(false),
                isLoopback = runCatching { nic.isLoopback }.getOrDefault(false),
                supportsMulticast = runCatching { nic.supportsMulticast() }.getOrDefault(false),
                mtu = runCatching { nic.mtu }.getOrNull(),
                addresses = nic.interfaceAddresses.mapNotNull { address ->
                    address.address?.hostAddress?.let { it to address.networkPrefixLength.toInt() }
                },
            )
        }
    }.getOrDefault(emptyList())

    data class InterfaceDetail(
        val name: String,
        val displayName: String?,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val supportsMulticast: Boolean,
        val mtu: Int?,
        val addresses: List<Pair<String, Int>>,
    )
}
