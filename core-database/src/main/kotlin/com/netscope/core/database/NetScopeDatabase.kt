package com.netscope.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        NetworkProfileEntity::class,
        ScanSessionEntity::class,
        DeviceRecordEntity::class,
        DeviceObservationEntity::class,
        ServiceRecordEntity::class,
        SubnetRecordEntity::class,
        RouteSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NetScopeDatabase : RoomDatabase() {
    abstract fun networkProfileDao(): NetworkProfileDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun deviceDao(): DeviceDao
    abstract fun deviceObservationDao(): DeviceObservationDao
    abstract fun serviceDao(): ServiceDao
    abstract fun subnetDao(): SubnetDao
    abstract fun routeSnapshotDao(): RouteSnapshotDao

    companion object {
        const val NAME = "netscope.db"

        /**
         * How many scan sessions to keep.
         *
         * History is useful precisely because it is long, but it must not grow without
         * limit, so the oldest sessions are trimmed after each scan.
         */
        const val SESSION_RETENTION_COUNT = 200
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NetScopeDatabase =
        Room.databaseBuilder(context, NetScopeDatabase::class.java, NetScopeDatabase.NAME)
            // There is no migration path yet because this is schema version 1. When
            // version 2 arrives it needs a real migration: destructive fallback would
            // silently delete a user's scan history, which is the one thing this app
            // promises to keep.
            .build()

    @Provides fun provideNetworkProfileDao(db: NetScopeDatabase) = db.networkProfileDao()
    @Provides fun provideScanSessionDao(db: NetScopeDatabase) = db.scanSessionDao()
    @Provides fun provideDeviceDao(db: NetScopeDatabase) = db.deviceDao()
    @Provides fun provideDeviceObservationDao(db: NetScopeDatabase) = db.deviceObservationDao()
    @Provides fun provideServiceDao(db: NetScopeDatabase) = db.serviceDao()
    @Provides fun provideSubnetDao(db: NetScopeDatabase) = db.subnetDao()
    @Provides fun provideRouteSnapshotDao(db: NetScopeDatabase) = db.routeSnapshotDao()
}
