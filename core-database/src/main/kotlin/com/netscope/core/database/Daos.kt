package com.netscope.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkProfileDao {

    @Query("SELECT * FROM network_profiles ORDER BY lastSeenEpochMillis DESC")
    fun observeAll(): Flow<List<NetworkProfileEntity>>

    @Query("SELECT * FROM network_profiles WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): NetworkProfileEntity?

    @Query("SELECT * FROM network_profiles WHERE id = :id")
    suspend fun findById(id: Long): NetworkProfileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: NetworkProfileEntity): Long

    @Update
    suspend fun update(profile: NetworkProfileEntity)

    @Query("UPDATE network_profiles SET displayName = :name, userRenamed = 1 WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM network_profiles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Inserts the profile or refreshes the timestamp of the existing one. */
    @Transaction
    suspend fun upsert(profile: NetworkProfileEntity): Long {
        val existing = findByFingerprint(profile.fingerprint)
        if (existing == null) {
            val id = insert(profile)
            return if (id > 0) id else findByFingerprint(profile.fingerprint)?.id ?: 0
        }
        update(
            existing.copy(
                lastSeenEpochMillis = profile.lastSeenEpochMillis,
                // A name the user chose is never replaced by a discovered one.
                displayName = if (existing.userRenamed) existing.displayName else profile.displayName,
                gatewayAddress = profile.gatewayAddress ?: existing.gatewayAddress,
                subnetCidr = profile.subnetCidr ?: existing.subnetCidr,
                transportLabel = profile.transportLabel ?: existing.transportLabel,
            ),
        )
        return existing.id
    }
}

@Dao
interface ScanSessionDao {

    @Query("SELECT * FROM scan_sessions ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC")
    fun observeForProfile(profileId: Long): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun findById(id: Long): ScanSessionEntity?

    @Insert
    suspend fun insert(session: ScanSessionEntity): Long

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scan_sessions")
    suspend fun deleteAll()

    /** Trims history beyond the retention cap, oldest first. */
    @Query(
        """
        DELETE FROM scan_sessions WHERE id IN (
            SELECT id FROM scan_sessions ORDER BY startedAtEpochMillis DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM scan_sessions")
    suspend fun count(): Int
}

@Dao
interface DeviceDao {

    @Query("SELECT * FROM device_records WHERE profileId = :profileId ORDER BY lastSeenEpochMillis DESC")
    fun observeForProfile(profileId: Long): Flow<List<DeviceRecordEntity>>

    @Query("SELECT * FROM device_records WHERE profileId = :profileId AND deviceKey = :key LIMIT 1")
    suspend fun find(profileId: Long, key: String): DeviceRecordEntity?

    @Query("SELECT * FROM device_records WHERE id = :id")
    suspend fun findById(id: Long): DeviceRecordEntity?

    @Query("SELECT deviceKey FROM device_records WHERE profileId = :profileId")
    suspend fun keysForProfile(profileId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(device: DeviceRecordEntity): Long

    @Update
    suspend fun update(device: DeviceRecordEntity)

    @Query("UPDATE device_records SET userLabel = :label WHERE id = :id")
    suspend fun setUserLabel(id: Long, label: String?)

    @Query("DELETE FROM device_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM device_records WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)

    /**
     * Stores a device, keeping the earliest first-seen timestamp and never letting a
     * scan overwrite a user label.
     */
    @Transaction
    suspend fun upsert(device: DeviceRecordEntity): Long {
        val existing = find(device.profileId, device.deviceKey)
        if (existing == null) {
            val id = insert(device)
            return if (id > 0) id else find(device.profileId, device.deviceKey)?.id ?: 0
        }
        update(
            existing.copy(
                ipv4 = device.ipv4 ?: existing.ipv4,
                macAddress = device.macAddress ?: existing.macAddress,
                vendor = device.vendor ?: existing.vendor,
                hostname = device.hostname ?: existing.hostname,
                friendlyName = device.friendlyName ?: existing.friendlyName,
                deviceType = device.deviceType ?: existing.deviceType,
                deviceTypeConfidence = device.deviceTypeConfidence ?: existing.deviceTypeConfidence,
                userLabel = existing.userLabel ?: device.userLabel,
                firstSeenEpochMillis = minOf(existing.firstSeenEpochMillis, device.firstSeenEpochMillis),
                lastSeenEpochMillis = maxOf(existing.lastSeenEpochMillis, device.lastSeenEpochMillis),
            ),
        )
        return existing.id
    }
}

@Dao
interface DeviceObservationDao {

    @Query("SELECT * FROM device_observations WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): List<DeviceObservationEntity>

    @Query("SELECT * FROM device_observations WHERE deviceId = :deviceId ORDER BY observedAtEpochMillis DESC LIMIT :limit")
    suspend fun forDevice(deviceId: Long, limit: Int = 50): List<DeviceObservationEntity>

    @Insert
    suspend fun insert(observation: DeviceObservationEntity): Long

    @Insert
    suspend fun insertAll(observations: List<DeviceObservationEntity>)
}

@Dao
interface ServiceDao {

    @Query("SELECT * FROM service_records WHERE deviceId = :deviceId ORDER BY port")
    suspend fun forDevice(deviceId: Long): List<ServiceRecordEntity>

    @Query("SELECT * FROM service_records WHERE deviceId = :deviceId ORDER BY port")
    fun observeForDevice(deviceId: Long): Flow<List<ServiceRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(services: List<ServiceRecordEntity>)

    @Query("DELETE FROM service_records WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)
}

@Dao
interface SubnetDao {

    @Query("SELECT * FROM subnet_records WHERE profileId = :profileId ORDER BY cidr")
    fun observeForProfile(profileId: Long): Flow<List<SubnetRecordEntity>>

    @Query("SELECT * FROM subnet_records WHERE profileId = :profileId AND cidr = :cidr LIMIT 1")
    suspend fun find(profileId: Long, cidr: String): SubnetRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subnet: SubnetRecordEntity): Long

    @Query("DELETE FROM subnet_records WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RouteSnapshotDao {

    @Query("SELECT * FROM route_snapshots WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: Long): List<RouteSnapshotEntity>

    @Insert
    suspend fun insertAll(routes: List<RouteSnapshotEntity>)
}
