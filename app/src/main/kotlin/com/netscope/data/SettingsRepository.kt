package com.netscope.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.model.ScanProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "netscope_settings")

/**
 * User preferences.
 *
 * Every setting that would send data off the device defaults to off, and the UI
 * discloses the endpoint at the point of use rather than burying it here.
 */
data class NetScopeSettings(
    val publicIpLookupEnabled: Boolean = false,
    val newDeviceNotificationsEnabled: Boolean = false,
    val demoModeEnabled: Boolean = false,
    val bannerGrabbingEnabled: Boolean = false,
    val scanProfile: ScanProfile = ScanProfile.SMART,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<NetScopeSettings> = context.dataStore.data.map { preferences ->
        NetScopeSettings(
            publicIpLookupEnabled = preferences[PUBLIC_IP] ?: false,
            newDeviceNotificationsEnabled = preferences[NEW_DEVICE_NOTIFICATIONS] ?: false,
            demoModeEnabled = preferences[DEMO_MODE] ?: false,
            bannerGrabbingEnabled = preferences[BANNER_GRABBING] ?: false,
            scanProfile = preferences[SCAN_PROFILE]
                ?.let { runCatching { ScanProfile.valueOf(it) }.getOrNull() }
                ?: ScanProfile.SMART,
            performanceProfile = preferences[PERFORMANCE_PROFILE]
                ?.let { runCatching { PerformanceProfile.valueOf(it) }.getOrNull() }
                ?: PerformanceProfile.BALANCED,
        )
    }

    suspend fun setPublicIpLookupEnabled(enabled: Boolean) = put(PUBLIC_IP, enabled)
    suspend fun setNewDeviceNotificationsEnabled(enabled: Boolean) = put(NEW_DEVICE_NOTIFICATIONS, enabled)
    suspend fun setDemoModeEnabled(enabled: Boolean) = put(DEMO_MODE, enabled)
    suspend fun setBannerGrabbingEnabled(enabled: Boolean) = put(BANNER_GRABBING, enabled)

    suspend fun setScanProfile(profile: ScanProfile) {
        context.dataStore.edit { it[SCAN_PROFILE] = profile.name }
    }

    suspend fun setPerformanceProfile(profile: PerformanceProfile) {
        context.dataStore.edit { it[PERFORMANCE_PROFILE] = profile.name }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val PUBLIC_IP = booleanPreferencesKey("public_ip_lookup_enabled")
        val NEW_DEVICE_NOTIFICATIONS = booleanPreferencesKey("new_device_notifications_enabled")
        val DEMO_MODE = booleanPreferencesKey("demo_mode_enabled")
        val BANNER_GRABBING = booleanPreferencesKey("banner_grabbing_enabled")
        val SCAN_PROFILE = stringPreferencesKey("scan_profile")
        val PERFORMANCE_PROFILE = stringPreferencesKey("performance_profile")
    }
}
