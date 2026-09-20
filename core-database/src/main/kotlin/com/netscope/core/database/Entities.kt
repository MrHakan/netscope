package com.netscope.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A network the user has been on, identified by its fingerprint.
 *
 * The gateway MAC/BSSID is the stable identifier, not the SSID: several networks share
 * an SSID and a single network can be renamed. No location data is stored.
 */
@Entity(tableName = "network_profiles", indices = [Index(value = ["fingerprint"], unique = true)])
data class NetworkProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Hash of SSID + gateway + subnet + BSSID set. */
    val fingerprint: String,
    val displayName: String,
    val ssid: String?,
    val bssid: String?,
    val gatewayAddress: String?,
    val subnetCidr: String?,
    val transportLabel: String?,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    /** Set when the user renames the profile; never overwritten by a scan. */
    val userRenamed: Boolean = false,
)

/** One scan run. */
@Entity(
    tableName = "scan_sessions",
    foreignKeys = [
        ForeignKey(
            entity = NetworkProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("startedAtEpochMillis")],
)
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val target: String,
    val scanProfile: String,
    val performanceProfile: String,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val addressesProbed: Int,
    val devicesFound: Int,
    val peakConcurrency: Int,
    val icmpAvailable: Boolean,
    val completed: Boolean,
    val isDemoData: Boolean,
)

/**
 * A device, stored once per network profile rather than once per scan.
 *
 * Per-scan detail lives in [DeviceObservationEntity], so a device seen in 200 scans
 * costs one row here plus small observation deltas.
 */
@Entity(
    tableName = "device_records",
    foreignKeys = [
        ForeignKey(
            entity = NetworkProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "deviceKey"], unique = true)],
)
data class DeviceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** The device's stable key: its IPv4 address, or first IPv6 when there is none. */
    val deviceKey: String,
    val ipv4: String?,
    val macAddress: String?,
    val vendor: String?,
    val hostname: String?,
    val friendlyName: String?,
    val deviceType: String?,
    val deviceTypeConfidence: String?,
    /** A user label always wins over discovered names. */
    val userLabel: String?,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
)

/**
 * What a specific scan saw of a device.
 *
 * Only values that changed are meaningful here; unchanged snapshots are not duplicated
 * wholesale, which is what keeps the history small enough to retain for months.
 */
@Entity(
    tableName = "device_observations",
    foreignKeys = [
        ForeignKey(
            entity = DeviceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId"), Index("sessionId")],
)
data class DeviceObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val sessionId: Long,
    val observedAtEpochMillis: Long,
    val state: String,
    val latencyMillis: Double?,
    val probeType: String?,
    val discoverySources: String,
    /** Populated only when this scan saw something different from the stored record. */
    val changedFields: String?,
)

@Entity(
    tableName = "service_records",
    foreignKeys = [
        ForeignKey(
            entity = DeviceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId", "name", "port"], unique = true)],
)
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val port: Int?,
    val protocol: String,
    val name: String,
    val serviceType: String?,
    val detail: String?,
    val source: String,
    val confidence: String,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
)

/** A subnet seen on a profile, with the reachability verdict last reached for it. */
@Entity(
    tableName = "subnet_records",
    foreignKeys = [
        ForeignKey(
            entity = NetworkProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "cidr"], unique = true)],
)
data class SubnetRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val cidr: String,
    val reachability: String,
    val gatewayAddress: String?,
    val deviceCount: Int,
    val lastScanEpochMillis: Long,
    val isUserAdded: Boolean,
)

/** The routes Android exposed at a point in time. */
@Entity(
    tableName = "route_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class RouteSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val destination: String,
    val gateway: String?,
    val interfaceName: String?,
    val isDefaultRoute: Boolean,
    val routeKind: String,
    val origin: String,
    val networkLabel: String?,
    val observedAtEpochMillis: Long,
)
