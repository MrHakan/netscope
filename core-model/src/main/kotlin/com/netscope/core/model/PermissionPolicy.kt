package com.netscope.core.model

/**
 * Android API levels NetScope's behaviour actually changes at.
 *
 * These are plain integers rather than Build.VERSION_CODES constants so the whole
 * policy stays in the pure JVM module and can be unit-tested for every release,
 * including ones the compile SDK predates.
 */
object ApiLevel {
    /** Wi-Fi scan throttling begins: four foreground scans per two minutes. */
    const val SCAN_THROTTLING = 28

    /** LocationManager.isLocationEnabled arrives. */
    const val LOCATION_ENABLED_API = 28

    /** NEARBY_WIFI_DEVICES was introduced for nearby Wi-Fi connection APIs. */
    const val NEARBY_WIFI_DEVICES = 33

    /**
     * Local network access becomes a runtime permission for apps targeting this level.
     *
     * The permission is referenced by name and probed at runtime rather than through a
     * constant, because NetScope is compiled against an older SDK. See
     * [NetScopePermissions.LOCAL_NETWORK_CANDIDATES].
     */
    const val LOCAL_NETWORK_PERMISSION = 37
}

/** Permission names used by the policy. Plain strings: several postdate the compile SDK. */
object NetScopePermissions {
    const val INTERNET = "android.permission.INTERNET"
    const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    const val ACCESS_WIFI_STATE = "android.permission.ACCESS_WIFI_STATE"
    const val CHANGE_WIFI_STATE = "android.permission.CHANGE_WIFI_STATE"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** The name introduced for local network access. */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * Names the platform may use for the local network permission, most likely first.
     *
     * The app asks the running platform which of these it actually defines instead of
     * assuming one. A name that no platform defines simply never matches, so an extra
     * candidate costs nothing and a rename does not silently disable LAN scanning.
     */
    val LOCAL_NETWORK_CANDIDATES: List<String> = listOf(
        ACCESS_LOCAL_NETWORK,
        "android.permission.LOCAL_NETWORK_ACCESS",
        "android.permission.NEARBY_DEVICES_LOCAL_NETWORK",
    )
}

/**
 * The capabilities NetScope gates separately.
 *
 * They are split because Android gates them separately: reading the connected network's
 * details, listing nearby access points and reaching other hosts on the LAN each have
 * their own rules, and collapsing them into one "has permission" flag is what produces
 * an app that shows an empty list with no explanation.
 */
enum class CapabilityArea(val label: String) {
    /** SSID, BSSID, RSSI and link rates for the network this device is joined to. */
    WIFI_INFO("Connected Wi-Fi details"),

    /** WifiManager.startScan / getScanResults for nearby access points. */
    ACCESS_POINT_SCAN("Nearby access point scanning"),

    /** TCP and ICMP probing of other hosts on the local network. */
    LAN_SCAN("Local network scanning"),

    /** Multicast DNS browsing. */
    MDNS("mDNS discovery"),

    /** SSDP / UPnP discovery. */
    SSDP("SSDP discovery"),

    /** Posting new-device and host-monitor notifications. */
    NOTIFICATIONS("Notifications"),
}

/** Whether a capability may run right now, and what to tell the user when it may not. */
sealed interface CapabilityVerdict {

    /** Nothing is in the way. */
    data object Allowed : CapabilityVerdict

    /** A runtime permission must be granted first. */
    data class PermissionRequired(
        val permissions: List<String>,
        val rationale: String,
    ) : CapabilityVerdict

    /**
     * The permission is granted but location services are off system-wide, which blanks
     * scan results on the affected releases.
     */
    data object LocationServicesRequired : CapabilityVerdict

    /** Wi-Fi is switched off. */
    data object WifiDisabled : CapabilityVerdict

    /** The platform cannot do this at all. */
    data class Unsupported(val reason: String) : CapabilityVerdict

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Everything the policy needs to reach a verdict.
 *
 * The Android adapter builds this; the rules themselves never touch the framework, so
 * every branch below is reachable from a unit test.
 */
data class PlatformState(
    val sdkInt: Int,
    val targetSdkInt: Int,
    val grantedPermissions: Set<String>,
    /** Permissions the running platform actually defines, discovered at runtime. */
    val platformDefinedPermissions: Set<String>,
    /** Permissions this build declared in its manifest. */
    val declaredPermissions: Set<String>,
    val locationServicesEnabled: Boolean,
    val wifiEnabled: Boolean,
)

/**
 * The single place Android's permission differences are encoded.
 *
 * Keeping this in one pure object is what stops release-specific `if (SDK_INT >= ...)`
 * checks from spreading into view models and composables, where they are impossible to
 * test and easy to get subtly wrong.
 */
object PermissionPolicy {

    fun verdict(area: CapabilityArea, state: PlatformState): CapabilityVerdict = when (area) {
        CapabilityArea.WIFI_INFO -> wifiInfoVerdict(state)
        CapabilityArea.ACCESS_POINT_SCAN -> accessPointScanVerdict(state)
        CapabilityArea.LAN_SCAN -> lanVerdict(state, CapabilityArea.LAN_SCAN)
        CapabilityArea.MDNS -> lanVerdict(state, CapabilityArea.MDNS)
        CapabilityArea.SSDP -> lanVerdict(state, CapabilityArea.SSDP)
        CapabilityArea.NOTIFICATIONS -> notificationsVerdict(state)
    }

    fun canReadWifiInfo(state: PlatformState): Boolean =
        verdict(CapabilityArea.WIFI_INFO, state).isAllowed

    fun canScanAccessPoints(state: PlatformState): Boolean =
        verdict(CapabilityArea.ACCESS_POINT_SCAN, state).isAllowed

    fun canScanLan(state: PlatformState): Boolean =
        verdict(CapabilityArea.LAN_SCAN, state).isAllowed

    fun canUseMdns(state: PlatformState): Boolean =
        verdict(CapabilityArea.MDNS, state).isAllowed

    fun canUseSsdp(state: PlatformState): Boolean =
        verdict(CapabilityArea.SSDP, state).isAllowed

    /** Permission used for connected Wi-Fi identity on this release. */
    fun wifiPermissionFor(state: PlatformState): String =
        if (state.sdkInt >= ApiLevel.NEARBY_WIFI_DEVICES) {
            NetScopePermissions.NEARBY_WIFI_DEVICES
        } else {
            NetScopePermissions.ACCESS_FINE_LOCATION
        }

    /**
     * WifiManager.startScan/getScanResults remain location-sensitive APIs.
     * Android's current documentation still requires ACCESS_FINE_LOCATION even when
     * the app targets API 33+, so this gate is intentionally separate from WIFI_INFO.
     */
    fun wifiScanPermissionsFor(state: PlatformState): List<String> =
        if (state.sdkInt >= 31) {
            listOf(
                NetScopePermissions.ACCESS_COARSE_LOCATION,
                NetScopePermissions.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(NetScopePermissions.ACCESS_FINE_LOCATION)
        }

    /**
     * The local network permission name this platform uses, or null if it has none.
     *
     * Null means local network access is not gated by a runtime permission here — it
     * does not mean a permission was denied.
     */
    fun localNetworkPermissionFor(state: PlatformState): String? =
        NetScopePermissions.LOCAL_NETWORK_CANDIDATES
            .firstOrNull { it in state.platformDefinedPermissions }

    /**
     * Whether local network access needs an explicit grant.
     *
     * Two conditions must both hold: the platform defines the permission, and this build
     * targets the level at which it is enforced. An app targeting an older level keeps
     * legacy behaviour, so demanding the permission there would block scanning for no
     * reason.
     */
    fun localNetworkPermissionRequired(state: PlatformState): Boolean {
        val permission = localNetworkPermissionFor(state) ?: return false
        if (state.targetSdkInt < ApiLevel.LOCAL_NETWORK_PERMISSION) return false
        return permission in state.declaredPermissions
    }

    private fun wifiInfoVerdict(state: PlatformState): CapabilityVerdict {
        if (!state.wifiEnabled) return CapabilityVerdict.WifiDisabled
        val permission = wifiPermissionFor(state)
        if (permission !in state.grantedPermissions) {
            return CapabilityVerdict.PermissionRequired(
                permissions = listOf(permission),
                rationale = if (state.sdkInt >= ApiLevel.NEARBY_WIFI_DEVICES) {
                    "Android 13 and newer require the nearby Wi-Fi devices permission to read " +
                        "the SSID and BSSID of the connected network. NetScope declares it with " +
                        "neverForLocation: it does not derive your location."
                } else {
                    "Below Android 13 the platform gates the connected SSID and BSSID behind the " +
                        "location permission. NetScope does not use your location for anything else."
                },
            )
        }
        return CapabilityVerdict.Allowed
    }

    private fun accessPointScanVerdict(state: PlatformState): CapabilityVerdict {
        if (!state.wifiEnabled) return CapabilityVerdict.WifiDisabled
        val permissions = wifiScanPermissionsFor(state)
        val missing = permissions.filterNot { it in state.grantedPermissions }
        if (missing.isNotEmpty()) {
            return CapabilityVerdict.PermissionRequired(
                permissions = missing,
                rationale = "WifiManager.startScan/getScanResults require ACCESS_FINE_LOCATION " +
                    "on current Android releases. NetScope uses it only to display nearby access " +
                    "points; it does not calculate or store your physical position.",
            )
        }
        if (!state.locationServicesEnabled) {
            return CapabilityVerdict.LocationServicesRequired
        }
        return CapabilityVerdict.Allowed
    }

    /**
     * Local network reachability: direct probing, mDNS and SSDP share one gate.
     *
     * Multicast discovery counts as local network traffic, so it is governed by the same
     * permission rather than treated as a separate capability.
     */
    private fun lanVerdict(state: PlatformState, area: CapabilityArea): CapabilityVerdict {
        if (!localNetworkPermissionRequired(state)) return CapabilityVerdict.Allowed
        val permission = localNetworkPermissionFor(state) ?: return CapabilityVerdict.Allowed
        if (permission in state.grantedPermissions) return CapabilityVerdict.Allowed
        return CapabilityVerdict.PermissionRequired(
            permissions = listOf(permission),
            rationale = "This version of Android requires explicit permission before an app may " +
                "reach other devices on your local network. ${area.label} covers exactly that " +
                "traffic. Without the grant a scan returns nothing, which looks identical to an " +
                "empty network.",
        )
    }

    private fun notificationsVerdict(state: PlatformState): CapabilityVerdict {
        if (state.sdkInt < ApiLevel.NEARBY_WIFI_DEVICES) return CapabilityVerdict.Allowed
        if (NetScopePermissions.POST_NOTIFICATIONS in state.grantedPermissions) {
            return CapabilityVerdict.Allowed
        }
        return CapabilityVerdict.PermissionRequired(
            permissions = listOf(NetScopePermissions.POST_NOTIFICATIONS),
            rationale = "Alerts for newly discovered devices need the notification permission. " +
                "They are off by default and NetScope works fully without them.",
        )
    }

    /**
     * Features that keep working when local network access is denied.
     *
     * Denying the permission must disable LAN discovery and nothing else: the subnet
     * calculator, interface and route information, DNS lookups and the Wi-Fi analyzer
     * involve no local network traffic and stay available.
     */
    fun featuresUnaffectedByLocalNetworkDenial(): List<String> = listOf(
        "Subnet calculator",
        "Interface and route information",
        "Connected Wi-Fi details and nearby access point scanning",
        "DNS lookups",
        "Public IP lookup, when you enable it",
    )
}
