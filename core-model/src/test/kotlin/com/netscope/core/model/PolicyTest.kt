package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun state(
    sdkInt: Int = 35,
    targetSdkInt: Int = 35,
    granted: Set<String> = emptySet(),
    platformDefined: Set<String> = emptySet(),
    declared: Set<String> = emptySet(),
    locationServicesEnabled: Boolean = true,
    wifiEnabled: Boolean = true,
) = PlatformState(
    sdkInt = sdkInt,
    targetSdkInt = targetSdkInt,
    grantedPermissions = granted,
    platformDefinedPermissions = platformDefined,
    declaredPermissions = declared,
    locationServicesEnabled = locationServicesEnabled,
    wifiEnabled = wifiEnabled,
)

class WifiPermissionPolicyTest {

    @Test
    fun `connected wifi identity uses nearby permission from api 33`() {
        assertThat(PermissionPolicy.wifiPermissionFor(state(sdkInt = 33)))
            .isEqualTo(NetScopePermissions.NEARBY_WIFI_DEVICES)
        assertThat(PermissionPolicy.wifiPermissionFor(state(sdkInt = 32)))
            .isEqualTo(NetScopePermissions.ACCESS_FINE_LOCATION)
    }

    @Test
    fun `access point scans require fine location on modern android too`() {
        assertThat(PermissionPolicy.wifiScanPermissionsFor(state(sdkInt = 34)))
            .containsExactly(NetScopePermissions.ACCESS_FINE_LOCATION)

        val denied = state(
            sdkInt = 34,
            granted = setOf(NetScopePermissions.NEARBY_WIFI_DEVICES),
        )
        val verdict = PermissionPolicy.verdict(CapabilityArea.ACCESS_POINT_SCAN, denied)
        assertThat(verdict).isInstanceOf(CapabilityVerdict.PermissionRequired::class.java)
        assertThat((verdict as CapabilityVerdict.PermissionRequired).permissions)
            .containsExactly(NetScopePermissions.ACCESS_FINE_LOCATION)
    }

    @Test
    fun `fine location unlocks nearby access point scan`() {
        val granted = state(
            sdkInt = 34,
            granted = setOf(NetScopePermissions.ACCESS_FINE_LOCATION),
        )
        assertThat(PermissionPolicy.canScanAccessPoints(granted)).isTrue()
    }

    @Test
    fun `location services gate access point scans`() {
        val state = state(
            sdkInt = 34,
            granted = setOf(NetScopePermissions.ACCESS_FINE_LOCATION),
            locationServicesEnabled = false,
        )
        assertThat(PermissionPolicy.verdict(CapabilityArea.ACCESS_POINT_SCAN, state))
            .isEqualTo(CapabilityVerdict.LocationServicesRequired)
    }

    @Test
    fun `wifi being off outranks permission problems`() {
        val off = state(sdkInt = 34, wifiEnabled = false)
        assertThat(PermissionPolicy.verdict(CapabilityArea.ACCESS_POINT_SCAN, off))
            .isEqualTo(CapabilityVerdict.WifiDisabled)
        assertThat(PermissionPolicy.verdict(CapabilityArea.WIFI_INFO, off))
            .isEqualTo(CapabilityVerdict.WifiDisabled)
    }

    @Test
    fun `connected wifi details retain their own permission gate`() {
        val granted = state(
            sdkInt = 34,
            granted = setOf(NetScopePermissions.NEARBY_WIFI_DEVICES),
        )
        assertThat(PermissionPolicy.canReadWifiInfo(granted)).isTrue()
    }
}

class LocalNetworkPermissionPolicyTest {

    private val definesPermission = setOf(NetScopePermissions.ACCESS_LOCAL_NETWORK)

    @Test
    fun `no permission is required on platforms that do not define one`() {
        val legacy = state(sdkInt = 35, targetSdkInt = 35)
        assertThat(PermissionPolicy.localNetworkPermissionFor(legacy)).isNull()
        assertThat(PermissionPolicy.localNetworkPermissionRequired(legacy)).isFalse()
        assertThat(PermissionPolicy.canScanLan(legacy)).isTrue()
    }

    @Test
    fun `the permission is discovered by name at runtime`() {
        val modern = state(sdkInt = 37, targetSdkInt = 37, platformDefined = definesPermission)
        assertThat(PermissionPolicy.localNetworkPermissionFor(modern))
            .isEqualTo(NetScopePermissions.ACCESS_LOCAL_NETWORK)
    }

    @Test
    fun `an alternative platform name is still found`() {
        // If the platform ships a different name, runtime detection still matches it
        // rather than silently disabling local network scanning.
        val renamed = state(
            sdkInt = 37,
            targetSdkInt = 37,
            platformDefined = setOf("android.permission.LOCAL_NETWORK_ACCESS"),
        )
        assertThat(PermissionPolicy.localNetworkPermissionFor(renamed))
            .isEqualTo("android.permission.LOCAL_NETWORK_ACCESS")
    }

    @Test
    fun `targeting an older level keeps legacy behaviour`() {
        // The permission exists on the device, but this build targets 35, so the
        // platform applies legacy behaviour and demanding a grant would be wrong.
        val legacyTarget = state(
            sdkInt = 37,
            targetSdkInt = 35,
            platformDefined = definesPermission,
            declared = definesPermission,
        )
        assertThat(PermissionPolicy.localNetworkPermissionRequired(legacyTarget)).isFalse()
        assertThat(PermissionPolicy.canScanLan(legacyTarget)).isTrue()
    }

    @Test
    fun `lan scanning is blocked when the permission is required and not granted`() {
        val blocked = state(
            sdkInt = 37,
            targetSdkInt = 37,
            platformDefined = definesPermission,
            declared = definesPermission,
        )
        val verdict = PermissionPolicy.verdict(CapabilityArea.LAN_SCAN, blocked)
        assertThat(verdict).isInstanceOf(CapabilityVerdict.PermissionRequired::class.java)
        assertThat((verdict as CapabilityVerdict.PermissionRequired).permissions)
            .containsExactly(NetScopePermissions.ACCESS_LOCAL_NETWORK)
    }

    @Test
    fun `granting the permission unlocks lan scanning`() {
        val granted = state(
            sdkInt = 37,
            targetSdkInt = 37,
            granted = definesPermission,
            platformDefined = definesPermission,
            declared = definesPermission,
        )
        assertThat(PermissionPolicy.canScanLan(granted)).isTrue()
    }

    @Test
    fun `mdns and ssdp share the local network gate`() {
        // Multicast discovery is local network traffic and is governed by the same
        // permission, not treated as an unrelated capability.
        val blocked = state(
            sdkInt = 37,
            targetSdkInt = 37,
            platformDefined = definesPermission,
            declared = definesPermission,
        )
        assertThat(PermissionPolicy.canUseMdns(blocked)).isFalse()
        assertThat(PermissionPolicy.canUseSsdp(blocked)).isFalse()
        assertThat(PermissionPolicy.canScanLan(blocked)).isFalse()
    }

    @Test
    fun `denying local network access leaves other features working`() {
        val blocked = state(
            sdkInt = 37,
            targetSdkInt = 37,
            granted = setOf(NetScopePermissions.NEARBY_WIFI_DEVICES),
            platformDefined = definesPermission,
            declared = definesPermission,
        )
        // The Wi-Fi analyzer involves no local network traffic, so it is unaffected.
        assertThat(PermissionPolicy.canScanAccessPoints(blocked)).isTrue()
        assertThat(PermissionPolicy.canReadWifiInfo(blocked)).isTrue()
        assertThat(PermissionPolicy.featuresUnaffectedByLocalNetworkDenial()).isNotEmpty()
    }
}

class NotificationPolicyTest {

    @Test
    fun `notifications need no permission below api 33`() {
        assertThat(PermissionPolicy.verdict(CapabilityArea.NOTIFICATIONS, state(sdkInt = 32)))
            .isEqualTo(CapabilityVerdict.Allowed)
    }

    @Test
    fun `notifications need a grant from api 33`() {
        assertThat(PermissionPolicy.verdict(CapabilityArea.NOTIFICATIONS, state(sdkInt = 33)))
            .isInstanceOf(CapabilityVerdict.PermissionRequired::class.java)

        val granted = state(sdkInt = 33, granted = setOf(NetScopePermissions.POST_NOTIFICATIONS))
        assertThat(PermissionPolicy.verdict(CapabilityArea.NOTIFICATIONS, granted))
            .isEqualTo(CapabilityVerdict.Allowed)
    }
}

class MonitorSchedulingPolicyTest {

    @Test
    fun `fifteen minutes or longer uses deferred periodic work`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(15 * 60, sdkInt = 34, appIsVisible = false)
        assertThat(schedule).isInstanceOf(MonitorSchedule.Periodic::class.java)
        assertThat((schedule as MonitorSchedule.Periodic).intervalMinutes).isEqualTo(15)
        assertThat(schedule.caveat).contains("does not run it at an exact time")
    }

    @Test
    fun `a long interval is still periodic work`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(60 * 60, sdkInt = 34, appIsVisible = false)
        assertThat((schedule as MonitorSchedule.Periodic).intervalMinutes).isEqualTo(60)
    }

    @Test
    fun `below fifteen minutes needs a foreground session`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(60, sdkInt = 34, appIsVisible = true)
        assertThat(schedule).isInstanceOf(MonitorSchedule.ForegroundSession::class.java)
        assertThat((schedule as MonitorSchedule.ForegroundSession).intervalSeconds).isEqualTo(60)
    }

    @Test
    fun `a short interval cannot start from the background on android 12 and newer`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(60, sdkInt = 31, appIsVisible = false)
        assertThat(schedule).isInstanceOf(MonitorSchedule.Rejected::class.java)
        assertThat((schedule as MonitorSchedule.Rejected).reason)
            .contains("do not allow one to be started from the background")
    }

    @Test
    fun `the same request is accepted while the app is visible`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(60, sdkInt = 34, appIsVisible = true)
        assertThat(schedule).isInstanceOf(MonitorSchedule.ForegroundSession::class.java)
    }

    @Test
    fun `below android 12 a background start is still permitted`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(60, sdkInt = 30, appIsVisible = false)
        assertThat(schedule).isInstanceOf(MonitorSchedule.ForegroundSession::class.java)
    }

    @Test
    fun `an absurdly short interval is rejected outright`() {
        val schedule = MonitorSchedulingPolicy.scheduleFor(1, sdkInt = 34, appIsVisible = true)
        assertThat(schedule).isInstanceOf(MonitorSchedule.Rejected::class.java)
    }

    @Test
    fun `the boundary at exactly fifteen minutes is periodic`() {
        val justUnder = MonitorSchedulingPolicy.scheduleFor(15 * 60 - 1, sdkInt = 34, appIsVisible = true)
        val exactly = MonitorSchedulingPolicy.scheduleFor(15 * 60, sdkInt = 34, appIsVisible = true)
        assertThat(justUnder).isInstanceOf(MonitorSchedule.ForegroundSession::class.java)
        assertThat(exactly).isInstanceOf(MonitorSchedule.Periodic::class.java)
    }

    @Test
    fun `interval wording never implies precision the platform lacks`() {
        assertThat(MonitorSchedulingPolicy.describeInterval(30 * 60)).contains("About every")
        assertThat(MonitorSchedulingPolicy.describeInterval(30)).contains("persistent notification")
    }
}
