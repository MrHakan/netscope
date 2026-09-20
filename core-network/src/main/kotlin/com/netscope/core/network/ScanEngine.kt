package com.netscope.core.network

import com.netscope.core.model.Confidence
import com.netscope.core.model.DeviceTypeInference
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.DiscoveredService
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.HostState
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.model.ScanPhase
import com.netscope.core.model.ScanProfile
import com.netscope.core.model.ScanProgress
import com.netscope.core.model.Unavailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** A device update as it is found, so the UI can render results while the scan runs. */
data class ScanUpdate(val device: DiscoveredDevice)

/**
 * The host-discovery engine.
 *
 * Concurrency is bounded by a semaphore sized from the performance profile: one
 * coroutine per host would create thousands of sockets and thermally throttle the
 * device. Results are pushed to a Flow as they arrive rather than collected into a
 * single batch, and cancellation is cooperative at every await point so STOP takes
 * effect within a couple of hundred milliseconds.
 */
@Singleton
class ScanEngine @Inject constructor(
    private val hostProber: HostProber,
    private val mdnsDiscovery: MdnsDiscovery,
    private val ssdpDiscovery: SsdpDiscovery,
    private val dnsTools: DnsTools,
) {

    private val _progress = MutableStateFlow(
        ScanProgress(
            phase = ScanPhase.NETWORK_ENUMERATION,
            target = "",
            probed = 0,
            total = 0,
            found = 0,
            isRunning = false,
            startedAtEpochMillis = 0,
        ),
    )
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _updates = MutableSharedFlow<ScanUpdate>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val updates: Flow<ScanUpdate> = _updates.asSharedFlow()

    private val peakConcurrency = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)

    /**
     * Scans [target].
     *
     * The caller owns the scope, so cancelling that scope stops every probe, closes
     * every socket and releases the multicast lock through the finally blocks in the
     * discovery classes.
     */
    suspend fun scan(
        target: Ipv4Cidr,
        profile: ScanProfile,
        performanceProfile: PerformanceProfile,
        localAddress: Ipv4Address?,
        gateway: Ipv4Address?,
        previouslySeen: Set<String> = emptySet(),
        boundInterfaceAddress: InetAddress? = null,
    ): List<DiscoveredDevice> = coroutineScope {
        val startedAt = System.currentTimeMillis()
        peakConcurrency.set(0)
        inFlight.set(0)

        val addresses = orderAddresses(target, profile, localAddress, gateway, previouslySeen)
        val devices = java.util.concurrent.ConcurrentHashMap<String, DiscoveredDevice>()

        _progress.value = ScanProgress(
            phase = ScanPhase.HOST_DISCOVERY,
            target = target.toString(),
            probed = 0,
            total = addresses.size,
            found = 0,
            isRunning = true,
            startedAtEpochMillis = startedAt,
            message = if (hostProber.icmpAvailable) {
                "Using ICMP echo where hosts allow it, with TCP connect as a fallback."
            } else {
                "Unprivileged ICMP is unavailable on this device; probing with TCP connect."
            },
        )

        // Multicast discovery runs alongside host probing: it costs almost nothing and
        // frequently names devices that never answer a probe.
        val discoveryJob = async(Dispatchers.IO) {
            runCatching {
                launchMulticastDiscovery(target, devices, boundInterfaceAddress)
            }
        }

        val semaphore = Semaphore(performanceProfile.maxConcurrency)
        val probed = AtomicInteger(0)

        val probeJobs = addresses.map { address ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    trackConcurrency {
                        probeAddress(
                            address = address,
                            target = target,
                            performanceProfile = performanceProfile,
                            localAddress = localAddress,
                            gateway = gateway,
                            previouslySeen = previouslySeen,
                            devices = devices,
                        )
                    }
                }
                val done = probed.incrementAndGet()
                _progress.value = _progress.value.copy(
                    probed = done,
                    found = devices.size,
                )
            }
        }

        probeJobs.awaitAll()
        discoveryJob.await()

        // Name resolution runs after discovery so it only touches hosts that exist.
        _progress.value = _progress.value.copy(phase = ScanPhase.NAME_RESOLUTION)
        resolveNames(devices, performanceProfile)

        _progress.value = _progress.value.copy(phase = ScanPhase.FINGERPRINTING)
        fingerprint(devices, gateway, localAddress)

        _progress.value = _progress.value.copy(
            phase = ScanPhase.DATABASE_COMPARISON,
            isRunning = false,
            found = devices.size,
            finishedAtEpochMillis = System.currentTimeMillis(),
        )

        devices.values.sortedBy { it.ipv4?.value ?: Long.MAX_VALUE }
    }

    /** Peak parallel probe count, reported in scan metadata. */
    val observedPeakConcurrency: Int get() = peakConcurrency.get()

    private inline fun <T> trackConcurrency(block: () -> T): T {
        val current = inFlight.incrementAndGet()
        peakConcurrency.updateAndGet { maxOf(it, current) }
        return try {
            block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun probeAddress(
        address: Ipv4Address,
        target: Ipv4Cidr,
        performanceProfile: PerformanceProfile,
        localAddress: Ipv4Address?,
        gateway: Ipv4Address?,
        previouslySeen: Set<String>,
        devices: MutableMap<String, DiscoveredDevice>,
    ) {
        val now = System.currentTimeMillis()
        val key = address.toCanonicalString()
        val isThisDevice = address == localAddress

        val outcome = if (isThisDevice) {
            // Probing our own address proves nothing; we know it is here.
            ProbeOutcome(true, 0.0, com.netscope.core.model.ProbeType.ICMP, "This device.")
        } else {
            hostProber.probe(
                address = InetAddress.getByAddress(address.bytes),
                timeoutMillis = performanceProfile.probeTimeoutMillis,
                sequence = (address.value and 0xFFFF).toInt(),
            )
        }

        if (!outcome.responded) {
            // A silent address is not recorded as a device at all; recording it as
            // "offline" would imply knowledge we do not have.
            return
        }

        val device = DiscoveredDevice(
            ipv4 = address,
            state = HostState.RESPONDING,
            latencyMillis = outcome.latencyMillis,
            probeType = outcome.probeType,
            discoverySources = setOf(
                if (outcome.probeType == com.netscope.core.model.ProbeType.ICMP) {
                    EvidenceSource.ICMP_PROBE
                } else {
                    EvidenceSource.TCP_PROBE
                },
            ),
            firstSeenEpochMillis = now,
            lastSeenEpochMillis = now,
            isNew = key !in previouslySeen,
            isThisDevice = isThisDevice,
            isGateway = address == gateway,
        )
        mergeInto(devices, device)
        _updates.emit(ScanUpdate(devices.getValue(key)))
    }

    /** mDNS and SSDP, both scoped to the target block. */
    private suspend fun launchMulticastDiscovery(
        target: Ipv4Cidr,
        devices: MutableMap<String, DiscoveredDevice>,
        boundInterfaceAddress: InetAddress?,
    ) = coroutineScope {
        val now = { System.currentTimeMillis() }

        val mdnsJob = async(Dispatchers.IO) {
            runCatching {
                mdnsDiscovery.discover(boundInterfaceAddress, MDNS_BUDGET_MILLIS) { record ->
                    val address = Ipv4Address.fromBytes(record.address.address) ?: return@discover
                    if (address !in target) return@discover
                    val timestamp = now()
                    val device = DiscoveredDevice(
                        ipv4 = address,
                        hostname = record.hostname?.let {
                            Evidence.observed(it, EvidenceSource.MDNS, Confidence.HIGH, timestamp)
                        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, timestamp),
                        friendlyName = Evidence.observed(
                            record.serviceName, EvidenceSource.MDNS, Confidence.MEDIUM, timestamp,
                            detail = "Name advertised in the ${record.serviceType} record.",
                        ),
                        state = HostState.ANNOUNCED,
                        services = listOf(MdnsDiscovery.toService(record, timestamp)),
                        discoverySources = setOf(EvidenceSource.MDNS),
                        firstSeenEpochMillis = timestamp,
                        lastSeenEpochMillis = timestamp,
                    )
                    mergeInto(devices, device)
                    _updates.emit(ScanUpdate(devices.getValue(address.toCanonicalString())))
                }
            }
        }

        val ssdpJob = async(Dispatchers.IO) {
            runCatching {
                ssdpDiscovery.discover(SSDP_BUDGET_MILLIS) { record ->
                    val address = Ipv4Address.fromBytes(record.address.address) ?: return@discover
                    if (address !in target) return@discover
                    val timestamp = now()
                    val device = DiscoveredDevice(
                        ipv4 = address,
                        // A MAC volunteered in an SSDP payload is a real observation.
                        mac = record.macAddress?.let {
                            Evidence.observed(
                                it, EvidenceSource.SSDP, Confidence.MEDIUM, timestamp,
                                detail = "The device published this MAC address in its SSDP response.",
                            )
                        } ?: Evidence.unavailable(
                            Unavailability.RESTRICTED_BY_ANDROID, timestamp,
                            "Android does not let apps read the ARP table, so a remote MAC is only " +
                                "available when a device volunteers it.",
                        ),
                        state = HostState.ANNOUNCED,
                        services = listOf(SsdpDiscovery.toService(record, timestamp)),
                        discoverySources = setOf(EvidenceSource.SSDP),
                        firstSeenEpochMillis = timestamp,
                        lastSeenEpochMillis = timestamp,
                    )
                    mergeInto(devices, device)
                    _updates.emit(ScanUpdate(devices.getValue(address.toCanonicalString())))
                }
            }
        }

        mdnsJob.await()
        ssdpJob.await()
    }

    private suspend fun resolveNames(
        devices: MutableMap<String, DiscoveredDevice>,
        performanceProfile: PerformanceProfile,
    ) = coroutineScope {
        val semaphore = Semaphore(performanceProfile.maxConcurrency.coerceAtMost(16))
        devices.values.toList()
            .filter { it.hostname.value == null && it.ipv4 != null }
            .map { device ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        ensureActive()
                        val address = InetAddress.getByAddress(device.ipv4!!.bytes)
                        val name = dnsTools.reverseLookup(address, REVERSE_DNS_TIMEOUT_MILLIS)
                        if (name != null) {
                            val timestamp = System.currentTimeMillis()
                            mergeInto(
                                devices,
                                device.copy(
                                    hostname = Evidence.observed(
                                        name, EvidenceSource.DNS_PTR, Confidence.HIGH, timestamp,
                                        detail = "Reverse DNS (PTR) lookup returned $name.",
                                    ),
                                    discoverySources = setOf(EvidenceSource.DNS_PTR),
                                ),
                            )
                            _updates.emit(ScanUpdate(devices.getValue(device.key)))
                        }
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun fingerprint(
        devices: MutableMap<String, DiscoveredDevice>,
        gateway: Ipv4Address?,
        localAddress: Ipv4Address?,
    ) {
        for (device in devices.values.toList()) {
            val inferred = DeviceTypeInference.infer(
                isGateway = device.ipv4 != null && device.ipv4 == gateway,
                isThisDevice = device.ipv4 != null && device.ipv4 == localAddress,
                hostname = device.hostname.value ?: device.friendlyName.value,
                mdnsServiceTypes = device.services.mapNotNull { it.serviceType },
                openPorts = device.ports.filter {
                    it.state == com.netscope.core.model.PortState.OPEN
                }.map { it.port },
                upnpDeviceType = device.services.firstOrNull { it.protocol == "upnp" }?.serviceType,
                vendor = device.vendor.value,
                observedAtEpochMillis = System.currentTimeMillis(),
            )
            mergeInto(devices, device.copy(deviceType = inferred))
            _updates.emit(ScanUpdate(devices.getValue(device.key)))
        }
    }

    /** Merges a partial observation into the map under the device's stable key. */
    private fun mergeInto(devices: MutableMap<String, DiscoveredDevice>, device: DiscoveredDevice) {
        val key = device.key
        val existing = devices[key]
        devices[key] = if (existing == null) {
            device
        } else {
            com.netscope.core.model.DeviceMerger.merge(existing, device)
        }
    }

    /**
     * Decides the order addresses are probed in.
     *
     * Smart Scan front-loads the addresses most likely to answer — the gateway, this
     * device, previously seen hosts and the low addresses DHCP pools usually start at —
     * so the list is useful long before the sweep finishes.
     */
    internal fun orderAddresses(
        target: Ipv4Cidr,
        profile: ScanProfile,
        localAddress: Ipv4Address?,
        gateway: Ipv4Address?,
        previouslySeen: Set<String>,
    ): List<Ipv4Address> {
        val all = target.hostAddresses().toList()
        return when (profile) {
            ScanProfile.FULL, ScanProfile.CUSTOM -> all

            ScanProfile.QUICK -> all.filter { address ->
                address == gateway || address == localAddress ||
                    address.toCanonicalString() in previouslySeen
            }

            ScanProfile.SMART -> {
                val priority = LinkedHashSet<Ipv4Address>()
                gateway?.takeIf { it in target }?.let(priority::add)
                localAddress?.takeIf { it in target }?.let(priority::add)
                all.filter { it.toCanonicalString() in previouslySeen }.forEach(priority::add)
                // DHCP pools overwhelmingly start low in the block.
                all.take(DHCP_HEAD_COUNT).forEach(priority::add)
                (priority + all.filterNot { it in priority }).toList()
            }
        }
    }

    /** Total addresses a scan of [target] would probe under [profile]. */
    fun plannedAddressCount(
        target: Ipv4Cidr,
        profile: ScanProfile,
        localAddress: Ipv4Address?,
        gateway: Ipv4Address?,
        previouslySeen: Set<String>,
    ): Int = orderAddresses(target, profile, localAddress, gateway, previouslySeen).size

    fun markCancelled() {
        _progress.value = _progress.value.copy(
            isRunning = false,
            cancelled = true,
            finishedAtEpochMillis = System.currentTimeMillis(),
            message = "Scan cancelled.",
        )
    }

    companion object {
        private const val MDNS_BUDGET_MILLIS = 4_000L
        private const val SSDP_BUDGET_MILLIS = 3_000L
        private const val REVERSE_DNS_TIMEOUT_MILLIS = 1_500L
        private const val DHCP_HEAD_COUNT = 60

        /** Blocks at least this large need explicit confirmation before scanning. */
        const val LARGE_SCAN_THRESHOLD_PREFIX = 16
    }
}
