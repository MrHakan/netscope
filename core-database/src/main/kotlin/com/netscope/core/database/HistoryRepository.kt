package com.netscope.core.database

import com.netscope.core.model.Confidence
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.DiscoveredService
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.HostState
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.MacAddress
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.ProbeType
import com.netscope.core.model.ScanMetadata
import com.netscope.core.model.Unavailability
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists scans and reads history back.
 *
 * Observations are stored as deltas against the device record, so repeated scans of an
 * unchanging network add rows proportional to what actually changed.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val profileDao: NetworkProfileDao,
    private val sessionDao: ScanSessionDao,
    private val deviceDao: DeviceDao,
    private val observationDao: DeviceObservationDao,
    private val serviceDao: ServiceDao,
    private val routeDao: RouteSnapshotDao,
    private val subnetDao: SubnetDao,
) {

    fun observeProfiles(): Flow<List<NetworkProfileEntity>> = profileDao.observeAll()

    fun observeSessions(limit: Int = 100): Flow<List<ScanSessionEntity>> =
        sessionDao.observeRecent(limit)

    fun observeDevices(profileId: Long): Flow<List<DeviceRecordEntity>> =
        deviceDao.observeForProfile(profileId)

    fun observeSubnets(profileId: Long): Flow<List<SubnetRecordEntity>> =
        subnetDao.observeForProfile(profileId)

    suspend fun renameProfile(profileId: Long, name: String) = profileDao.rename(profileId, name)

    suspend fun setDeviceLabel(deviceId: Long, label: String?) = deviceDao.setUserLabel(deviceId, label)

    suspend fun deleteProfile(profileId: Long) = profileDao.delete(profileId)

    suspend fun clearHistory() = sessionDao.deleteAll()

    /** Device keys seen before on this profile, used to flag genuinely new devices. */
    suspend fun knownDeviceKeys(profileId: Long): Set<String> =
        deviceDao.keysForProfile(profileId).toSet()

    /** Finds or creates the profile for a network snapshot. */
    suspend fun profileFor(snapshot: NetworkSnapshot, ssid: String?, bssid: String?): Long {
        val now = System.currentTimeMillis()
        val gateway = snapshot.ipv4Gateway?.toCanonicalString()
        val subnet = snapshot.primaryIpv4Cidr?.toString()
        val fingerprint = fingerprint(ssid, bssid, gateway, subnet)
        return profileDao.upsert(
            NetworkProfileEntity(
                fingerprint = fingerprint,
                displayName = ssid ?: snapshot.interfaceName.value ?: subnet ?: "Network",
                ssid = ssid,
                bssid = bssid,
                gatewayAddress = gateway,
                subnetCidr = subnet,
                transportLabel = snapshot.transportLabel,
                firstSeenEpochMillis = now,
                lastSeenEpochMillis = now,
            ),
        )
    }

    /**
     * The network fingerprint.
     *
     * The gateway and BSSID carry the weight: an SSID alone is not an identity, since
     * "Guest" exists on a million networks.
     */
    private fun fingerprint(ssid: String?, bssid: String?, gateway: String?, subnet: String?): String {
        val material = listOf(ssid.orEmpty(), bssid.orEmpty(), gateway.orEmpty(), subnet.orEmpty())
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Writes a completed scan and trims history to the retention cap. */
    suspend fun recordScan(
        profileId: Long,
        metadata: ScanMetadata,
        devices: List<DiscoveredDevice>,
        routes: List<com.netscope.core.model.RouteEntry>,
    ): Long {
        val sessionId = sessionDao.insert(
            ScanSessionEntity(
                profileId = profileId,
                target = metadata.target,
                scanProfile = metadata.profile.name,
                performanceProfile = metadata.performanceProfile.name,
                startedAtEpochMillis = metadata.startedAtEpochMillis,
                durationMillis = metadata.durationMillis,
                addressesProbed = metadata.addressesProbed,
                devicesFound = metadata.devicesFound,
                peakConcurrency = metadata.peakConcurrency,
                icmpAvailable = metadata.icmpAvailable,
                completed = metadata.completed,
                isDemoData = metadata.isDemoData,
            ),
        )

        for (device in devices) {
            val existing = deviceDao.find(profileId, device.key)
            val entity = DeviceRecordEntity(
                profileId = profileId,
                deviceKey = device.key,
                ipv4 = device.ipv4?.toCanonicalString(),
                macAddress = device.mac.value?.toString(),
                vendor = device.vendor.value,
                hostname = device.hostname.value,
                friendlyName = device.friendlyName.value,
                deviceType = device.deviceType.value?.name,
                deviceTypeConfidence = device.deviceType.confidence.name,
                userLabel = device.userLabel,
                firstSeenEpochMillis = device.firstSeenEpochMillis,
                lastSeenEpochMillis = device.lastSeenEpochMillis,
            )
            val deviceId = deviceDao.upsert(entity)

            // Only the fields that actually differ are written to the observation.
            val changes = buildList {
                if (existing != null) {
                    if (existing.hostname != entity.hostname) add("hostname")
                    if (existing.friendlyName != entity.friendlyName) add("name")
                    if (existing.macAddress != entity.macAddress) add("mac")
                    if (existing.deviceType != entity.deviceType) add("type")
                }
            }

            observationDao.insert(
                DeviceObservationEntity(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    observedAtEpochMillis = device.lastSeenEpochMillis,
                    state = device.state.name,
                    latencyMillis = device.latencyMillis,
                    probeType = device.probeType?.name,
                    discoverySources = device.discoverySources.joinToString(",") { it.name },
                    changedFields = changes.joinToString(",").ifEmpty { null },
                ),
            )

            if (device.services.isNotEmpty()) {
                serviceDao.insertAll(
                    device.services.map { service ->
                        ServiceRecordEntity(
                            deviceId = deviceId,
                            port = service.port,
                            protocol = service.protocol,
                            name = service.name,
                            serviceType = service.serviceType,
                            detail = service.detail,
                            source = service.source.name,
                            confidence = service.confidence.name,
                            firstSeenEpochMillis = service.observedAtEpochMillis,
                            lastSeenEpochMillis = service.observedAtEpochMillis,
                        )
                    },
                )
            }
        }

        if (routes.isNotEmpty()) {
            routeDao.insertAll(
                routes.map { route ->
                    RouteSnapshotEntity(
                        sessionId = sessionId,
                        destination = route.destinationText,
                        gateway = route.gateway?.toCanonicalString(),
                        interfaceName = route.interfaceName,
                        isDefaultRoute = route.isDefaultRoute,
                        routeKind = route.kind.name,
                        origin = route.origin.name,
                        networkLabel = route.networkLabel,
                        observedAtEpochMillis = metadata.startedAtEpochMillis,
                    )
                },
            )
        }

        sessionDao.trimTo(NetScopeDatabase.SESSION_RETENTION_COUNT)
        return sessionId
    }

    /** Rebuilds the devices of a stored scan so two scans can be compared. */
    suspend fun devicesForSession(sessionId: Long): List<DiscoveredDevice> {
        val observations = observationDao.forSession(sessionId)
        return observations.mapNotNull { observation ->
            val record = deviceDao.findById(observation.deviceId) ?: return@mapNotNull null
            val services = serviceDao.forDevice(record.id)
            toModel(record, observation, services)
        }
    }

    private fun toModel(
        record: DeviceRecordEntity,
        observation: DeviceObservationEntity,
        services: List<ServiceRecordEntity>,
    ): DiscoveredDevice = DiscoveredDevice(
        ipv4 = record.ipv4?.let { Ipv4Address.parse(it) },
        hostname = record.hostname?.let {
            Evidence.observed(it, EvidenceSource.DNS_PTR, Confidence.HIGH, record.lastSeenEpochMillis)
        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        friendlyName = record.friendlyName?.let {
            Evidence.observed(it, EvidenceSource.MDNS, Confidence.MEDIUM, record.lastSeenEpochMillis)
        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        mac = record.macAddress?.let { MacAddress.parse(it) }?.let {
            Evidence.observed(it, EvidenceSource.SSDP, Confidence.MEDIUM, record.lastSeenEpochMillis)
        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        vendor = record.vendor?.let {
            Evidence.observed(it, EvidenceSource.OUI, Confidence.HIGH, record.lastSeenEpochMillis)
        } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        deviceType = record.deviceType
            ?.let { name -> runCatching { com.netscope.core.model.DeviceType.valueOf(name) }.getOrNull() }
            ?.let {
                Evidence.inferred(
                    it,
                    runCatching { Confidence.valueOf(record.deviceTypeConfidence ?: "LOW") }
                        .getOrDefault(Confidence.LOW),
                    record.lastSeenEpochMillis,
                    "Stored from a previous scan.",
                )
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED),
        state = runCatching { HostState.valueOf(observation.state) }.getOrDefault(HostState.NOT_PROBED),
        latencyMillis = observation.latencyMillis,
        probeType = observation.probeType?.let { runCatching { ProbeType.valueOf(it) }.getOrNull() },
        services = services.map { service ->
            DiscoveredService(
                port = service.port,
                protocol = service.protocol,
                name = service.name,
                serviceType = service.serviceType,
                detail = service.detail,
                source = runCatching { EvidenceSource.valueOf(service.source) }
                    .getOrDefault(EvidenceSource.NONE),
                confidence = runCatching { Confidence.valueOf(service.confidence) }
                    .getOrDefault(Confidence.UNKNOWN),
                observedAtEpochMillis = service.lastSeenEpochMillis,
            )
        },
        firstSeenEpochMillis = record.firstSeenEpochMillis,
        lastSeenEpochMillis = record.lastSeenEpochMillis,
        userLabel = record.userLabel,
    )
}
