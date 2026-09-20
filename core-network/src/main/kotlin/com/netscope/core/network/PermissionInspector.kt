package com.netscope.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import com.netscope.core.model.ApiLevel
import com.netscope.core.model.CapabilityArea
import com.netscope.core.model.CapabilityVerdict
import com.netscope.core.model.NetScopePermissions
import com.netscope.core.model.PermissionPolicy
import com.netscope.core.model.PlatformState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A permission the app may need, with everything the UI needs to explain it. */
data class PermissionRequirement(
    val permission: String,
    val title: String,
    val rationale: String,
    val isGranted: Boolean,
    val isDefinedByPlatform: Boolean,
    val isOptional: Boolean,
)

/**
 * The Android adapter for [PermissionPolicy].
 *
 * Its only job is to observe the platform and hand a [PlatformState] to the policy. All
 * the release-specific rules live in core-model, where they are unit-tested for every
 * API level — including ones this build's compile SDK predates.
 */
@Singleton
class PermissionInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Reads the current platform state.
     *
     * Permission names that may not exist on this release are probed by asking the
     * package manager whether it defines them, rather than by referencing a constant
     * the compile SDK may not have.
     */
    fun platformState(): PlatformState {
        val candidates = buildSet {
            addAll(NetScopePermissions.LOCAL_NETWORK_CANDIDATES)
            add(NetScopePermissions.NEARBY_WIFI_DEVICES)
            add(NetScopePermissions.ACCESS_FINE_LOCATION)
            add(NetScopePermissions.POST_NOTIFICATIONS)
        }
        val declared = declaredPermissions()
        return PlatformState(
            sdkInt = Build.VERSION.SDK_INT,
            targetSdkInt = context.applicationInfo.targetSdkVersion,
            grantedPermissions = candidates.filterTo(mutableSetOf(), ::isGranted),
            platformDefinedPermissions = candidates.filterTo(mutableSetOf(), ::isDefinedByPlatform),
            declaredPermissions = declared,
            locationServicesEnabled = locationServicesEnabled(),
            wifiEnabled = wifiEnabled(),
        )
    }

    fun verdict(area: CapabilityArea): CapabilityVerdict =
        PermissionPolicy.verdict(area, platformState())

    fun canReadWifiInfo(): Boolean = PermissionPolicy.canReadWifiInfo(platformState())
    fun canScanAccessPoints(): Boolean = PermissionPolicy.canScanAccessPoints(platformState())
    fun canScanLan(): Boolean = PermissionPolicy.canScanLan(platformState())
    fun canUseMdns(): Boolean = PermissionPolicy.canUseMdns(platformState())
    fun canUseSsdp(): Boolean = PermissionPolicy.canUseSsdp(platformState())

    fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the running platform defines [permission] at all. */
    fun isDefinedByPlatform(permission: String): Boolean = runCatching {
        context.packageManager.getPermissionInfo(permission, 0)
        true
    }.getOrDefault(false)

    private fun declaredPermissions(): Set<String> = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /**
     * Location services must be on system-wide before some releases return Wi-Fi scan
     * results, even with the permission granted.
     */
    private fun locationServicesEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= ApiLevel.LOCATION_ENABLED_API) {
            runCatching { manager.isLocationEnabled }.getOrDefault(false)
        } else {
            runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    private fun wifiEnabled(): Boolean = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    /** The local network permission name this platform uses, or null if it has none. */
    fun localNetworkPermission(): String? =
        PermissionPolicy.localNetworkPermissionFor(platformState())

    /** Everything the app may ask for, in the order the UI should present it. */
    fun requirements(): List<PermissionRequirement> {
        val state = platformState()
        return buildList {
            val wifiPermission = PermissionPolicy.wifiPermissionFor(state)
            val wifiVerdict = PermissionPolicy.verdict(CapabilityArea.ACCESS_POINT_SCAN, state)
            add(
                PermissionRequirement(
                    permission = wifiPermission,
                    title = "Wi-Fi scanning",
                    rationale = (wifiVerdict as? CapabilityVerdict.PermissionRequired)?.rationale
                        ?: "Lists nearby access points and reads the connected network's SSID.",
                    isGranted = wifiPermission in state.grantedPermissions,
                    isDefinedByPlatform = wifiPermission in state.platformDefinedPermissions,
                    isOptional = true,
                ),
            )

            PermissionPolicy.localNetworkPermissionFor(state)?.let { permission ->
                val verdict = PermissionPolicy.verdict(CapabilityArea.LAN_SCAN, state)
                add(
                    PermissionRequirement(
                        permission = permission,
                        title = "Local network access",
                        rationale = (verdict as? CapabilityVerdict.PermissionRequired)?.rationale
                            ?: "Required before this app may reach other devices on your network.",
                        isGranted = permission in state.grantedPermissions,
                        isDefinedByPlatform = true,
                        // Scanning is the core of the app, but everything that does not
                        // touch the LAN keeps working without it.
                        isOptional = false,
                    ),
                )
            }

            val notificationVerdict = PermissionPolicy.verdict(CapabilityArea.NOTIFICATIONS, state)
            if (notificationVerdict is CapabilityVerdict.PermissionRequired) {
                add(
                    PermissionRequirement(
                        permission = NetScopePermissions.POST_NOTIFICATIONS,
                        title = "Notifications",
                        rationale = notificationVerdict.rationale,
                        isGranted = false,
                        isDefinedByPlatform = true,
                        isOptional = true,
                    ),
                )
            }
        }
    }

    /** Features that keep working when local network access is denied. */
    fun featuresUnaffectedByLocalNetworkDenial(): List<String> =
        PermissionPolicy.featuresUnaffectedByLocalNetworkDenial()
}
