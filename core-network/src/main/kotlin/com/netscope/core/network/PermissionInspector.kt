package com.netscope.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 * Works out which permissions this Android version actually requires.
 *
 * Permission names and behaviour change with almost every release, so nothing here is
 * assumed: the platform is asked whether it defines a permission before the app tries
 * to use it, and an undefined permission is simply skipped.
 */
@Singleton
class PermissionInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Candidate names for the local-network access permission introduced on recent
     * releases.
     *
     * The app is built against an SDK that may predate it, so the constant cannot be
     * referenced directly. Each candidate is checked against the running platform and
     * only a name the platform actually defines is ever used; if none is defined, local
     * network access needs no permission on this device.
     */
    private val localNetworkPermissionCandidates = listOf(
        "android.permission.LOCAL_NETWORK_ACCESS",
        "android.permission.NEARBY_DEVICES_LOCAL_NETWORK",
    )

    fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the running platform defines [permission] at all. */
    fun isDefinedByPlatform(permission: String): Boolean = runCatching {
        context.packageManager.getPermissionInfo(permission, 0)
        true
    }.getOrDefault(false)

    /** Whether this app declared [permission] in its manifest. */
    fun isDeclaredByApp(permission: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    /**
     * The local-network permission name this device uses, or null if it has none.
     *
     * Returning null means local network access is not gated by a runtime permission
     * here — not that the permission was denied.
     */
    fun localNetworkPermission(): String? =
        localNetworkPermissionCandidates.firstOrNull { isDefinedByPlatform(it) }

    /**
     * True when the platform gates local network access and the user has not granted it.
     *
     * The scan screen uses this to show PERMISSION REQUIRED instead of an empty result,
     * which would look identical to a network with nothing on it.
     */
    fun localNetworkAccessBlocked(): Boolean {
        val permission = localNetworkPermission() ?: return false
        if (!isDeclaredByApp(permission)) return false
        return !isGranted(permission)
    }

    /** Everything the app may ask for, in the order the UI should present it. */
    fun requirements(): List<PermissionRequirement> = buildList {
        val wifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        add(
            PermissionRequirement(
                permission = wifiPermission,
                title = "Wi-Fi scanning",
                rationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "Android 13 and newer require the nearby Wi-Fi devices permission to list " +
                        "access points and to read the SSID of the connected network. NetScope " +
                        "declares it with neverForLocation: it does not derive your location."
                } else {
                    "Below Android 13 the platform gates Wi-Fi scan results and the connected " +
                        "SSID behind the location permission. NetScope does not use your location " +
                        "for anything else."
                },
                isGranted = isGranted(wifiPermission),
                isDefinedByPlatform = isDefinedByPlatform(wifiPermission),
                isOptional = true,
            ),
        )

        localNetworkPermission()?.let { permission ->
            add(
                PermissionRequirement(
                    permission = permission,
                    title = "Local network access",
                    rationale = "This version of Android requires explicit permission before an " +
                        "app may reach other devices on your local network. Without it, scans " +
                        "return nothing at all rather than failing visibly.",
                    isGranted = isGranted(permission),
                    isDefinedByPlatform = true,
                    isOptional = false,
                ),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermissionRequirement(
                    permission = android.Manifest.permission.POST_NOTIFICATIONS,
                    title = "Notifications",
                    rationale = "Only needed if you turn on alerts for newly discovered devices " +
                        "or run the host monitor. Off by default.",
                    isGranted = isGranted(android.Manifest.permission.POST_NOTIFICATIONS),
                    isDefinedByPlatform = true,
                    isOptional = true,
                ),
            )
        }
    }
}
