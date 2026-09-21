package com.netscope.core.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.netscope.core.model.CapabilityArea
import com.netscope.core.model.CapabilityVerdict
import com.netscope.core.model.Confidence
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.MacAddress
import com.netscope.core.model.PermissionPolicy
import com.netscope.core.model.Unavailability
import com.netscope.core.model.WifiConnectionInfo
import com.netscope.core.model.WifiScanAvailability
import com.netscope.core.model.WifiScanEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi state and nearby-network scanning.
 *
 * Android throttles foreground startScan() calls to roughly four per two minutes, so
 * this class never polls: it registers for the scan-results broadcast, serves cached
 * results in the meantime, and reports exactly how stale each result is.
 */
@Singleton
class WifiInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionInspector: PermissionInspector,
) {

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Timestamps of our own startScan() calls, used to predict the next allowed one. */
    private val recentScanRequests = ArrayDeque<Long>()

    /**
     * The permissions this Android version actually requires for scan results.
     *
     * The rule lives in [PermissionPolicy] so it is unit-tested across every API level
     * rather than restated here.
     */
    fun requiredScanPermissions(): List<String> =
        PermissionPolicy.wifiScanPermissionsFor(permissionInspector.platformState())

    private fun hasPermission(permission: String): Boolean = permissionInspector.isGranted(permission)

    /**
     * Why a scan would or would not produce results right now.
     *
     * Permission, location-services and Wi-Fi-state rules come from the shared policy;
     * only throttling is decided here, because it depends on this class's own call
     * history rather than on platform state.
     */
    fun scanAvailability(): WifiScanAvailability {
        return when (val verdict = permissionInspector.verdict(CapabilityArea.ACCESS_POINT_SCAN)) {
            is CapabilityVerdict.PermissionRequired ->
                WifiScanAvailability.PermissionRequired(verdict.permissions)
            CapabilityVerdict.LocationServicesRequired ->
                WifiScanAvailability.LocationServicesDisabled
            CapabilityVerdict.WifiDisabled -> WifiScanAvailability.WifiDisabled
            is CapabilityVerdict.Unsupported -> WifiScanAvailability.WifiDisabled
            CapabilityVerdict.Allowed ->
                throttleRetryDelayMillis()
                    ?.let { WifiScanAvailability.Throttled(it) }
                    ?: WifiScanAvailability.Available
        }
    }

    /**
     * How long until another startScan() is likely to be honoured.
     *
     * Android does not publish the remaining quota, so this is derived from our own
     * call history against the documented four-per-two-minutes budget. It is shown as
     * a prediction, never as a platform guarantee.
     */
    private fun throttleRetryDelayMillis(): Long? {
        val now = SystemClock.elapsedRealtime()
        synchronized(recentScanRequests) {
            while (recentScanRequests.isNotEmpty() && now - recentScanRequests.first() > THROTTLE_WINDOW_MILLIS) {
                recentScanRequests.removeFirst()
            }
            if (recentScanRequests.size < THROTTLE_MAX_SCANS) return null
            val oldest = recentScanRequests.first()
            return (THROTTLE_WINDOW_MILLIS - (now - oldest)).coerceAtLeast(0)
        }
    }

    /**
     * Asks the platform for a fresh scan.
     *
     * Returns false when the request was not made, so the caller can explain why rather
     * than silently showing stale data.
     */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean {
        val manager = wifiManager ?: return false
        if (scanAvailability() != WifiScanAvailability.Available) return false
        val requested = runCatching { manager.startScan() }.getOrDefault(false)
        if (requested) {
            synchronized(recentScanRequests) { recentScanRequests.addLast(SystemClock.elapsedRealtime()) }
        }
        return requested
    }

    /** Cached scan results, each tagged with its age. */
    fun cachedScanResults(): List<WifiScanEntry> {
        val manager = wifiManager ?: return emptyList()
        if (requiredScanPermissions().any { !hasPermission(it) }) return emptyList()
        val results = runCatching { manager.scanResults }.getOrNull() ?: return emptyList()
        val nowMillis = System.currentTimeMillis()
        val nowBootMicros = SystemClock.elapsedRealtime() * 1000
        return results.mapNotNull { toEntry(it, nowMillis, nowBootMicros) }
            .sortedByDescending { it.rssiDbm }
    }

    /** Emits whenever the platform publishes new scan results. */
    fun observeScanResults(): Flow<List<WifiScanEntry>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(cachedScanResults())
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        trySend(cachedScanResults())
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun toEntry(result: ScanResult, nowMillis: Long, nowBootMicros: Long): WifiScanEntry? {
        val bssid = result.BSSID ?: return null
        @Suppress("DEPRECATION")
        val rawSsid = result.SSID
        val hidden = rawSsid.isNullOrEmpty()
        // ScanResult.timestamp is microseconds since boot, not wall-clock time.
        val ageMillis = ((nowBootMicros - result.timestamp) / 1000).coerceAtLeast(0)
        return WifiScanEntry(
            ssid = if (hidden) null else sanitizeSsid(rawSsid),
            isHidden = hidden,
            bssid = bssid,
            rssiDbm = result.level,
            frequencyMhz = result.frequency,
            centerFrequency0Mhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) result.centerFreq0 else 0,
            centerFrequency1Mhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) result.centerFreq1 else 0,
            channelWidthMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                channelWidthMhz(result.channelWidth)
            } else {
                null
            },
            securityCapabilities = result.capabilities ?: "",
            securityTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.securityTypes.mapNotNull(::securityTypeLabel).distinct()
            } else {
                emptyList()
            },
            wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiStandardLabel(result.wifiStandard)
            } else {
                null
            },
            observedAtEpochMillis = nowMillis - ageMillis,
            ageMillis = ageMillis,
        )
    }

    private fun channelWidthMhz(channelWidth: Int): Int? = when (channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        else ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ
            ) {
                320
            } else {
                null
            }
    }

    /**
     * Maps the platform's Wi-Fi generation constant to a label.
     *
     * Unknown values return null rather than an invented generation number.
     */
    private fun wifiStandardLabel(standard: Int): String? = when (standard) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
        ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                standard == ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                standard == ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
            else -> null
        }
    }

    /** Remote-controlled text: strip control characters before it reaches the UI. */
    private fun sanitizeSsid(raw: String): String =
        raw.filter { it == ' ' || !it.isISOControl() }.take(64)

    /**
     * Details of the AP this device is associated with.
     *
     * The device's own MAC is reported as RESTRICTED BY ANDROID: the platform returns
     * the placeholder 02:00:00:00:00:00 to third-party apps, and showing that as if it
     * were real would be a lie.
     */
    @Suppress("DEPRECATION")
    fun connectionInfo(): WifiConnectionInfo? {
        val manager = wifiManager ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        if (info.networkId == -1 && info.bssid == null) return null

        val hasScanPermission = requiredScanPermissions().all(::hasPermission)
        val rawSsid = info.ssid?.trim('"')
        val ssidHidden = rawSsid.isNullOrEmpty() || rawSsid == UNKNOWN_SSID

        return WifiConnectionInfo(
            ssid = when {
                !hasScanPermission -> Evidence.unavailable(
                    Unavailability.PERMISSION_REQUIRED, now,
                    "The SSID of the connected network requires " +
                        requiredScanPermissions().joinToString(", ") + ".",
                )
                ssidHidden -> Evidence.unavailable(Unavailability.NOT_DISCOVERED, now)
                else -> Evidence.observed(
                    sanitizeSsid(rawSsid!!), EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now,
                )
            },
            bssid = info.bssid?.takeIf { it != INVALID_BSSID }?.let {
                Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            rssiDbm = Evidence.observed(info.rssi, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now),
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 }?.let {
                Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now,
                    detail = "PHY link speed negotiated with the access point. This is not an " +
                        "internet throughput measurement.")
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.txLinkSpeedMbps.takeIf { it > 0 }?.let {
                    Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
                } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now)
            } else {
                Evidence.unavailable(Unavailability.NOT_APPLICABLE, now, "Requires Android 10 or newer.")
            },
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.rxLinkSpeedMbps.takeIf { it > 0 }?.let {
                    Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
                } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now)
            } else {
                Evidence.unavailable(Unavailability.NOT_APPLICABLE, now, "Requires Android 10 or newer.")
            },
            frequencyMhz = info.frequency.takeIf { it > 0 }?.let {
                Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
            } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now),
            wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiStandardLabel(info.wifiStandard)?.let {
                    Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
                } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now)
            } else {
                Evidence.unavailable(Unavailability.NOT_APPLICABLE, now, "Requires Android 11 or newer.")
            },
            securityType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                securityTypeLabel(info.currentSecurityType)?.let {
                    Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
                } ?: Evidence.unavailable(Unavailability.NOT_DISCOVERED, now)
            } else {
                Evidence.unavailable(Unavailability.NOT_APPLICABLE, now, "Requires Android 12 or newer.")
            },
            ownMacAddress = ownMacEvidence(info.macAddress, now),
            observedAtEpochMillis = now,
        )
    }

    private fun ownMacEvidence(raw: String?, now: Long): Evidence<MacAddress> =
        if (raw == null || raw == PLACEHOLDER_MAC) {
            Evidence.unavailable(
                Unavailability.RESTRICTED_BY_ANDROID, now,
                "Android returns the placeholder $PLACEHOLDER_MAC instead of this device's real " +
                    "Wi-Fi MAC address. No app can read it.",
            )
        } else {
            MacAddress.parse(raw)?.let {
                Evidence.observed(it, EvidenceSource.WIFI_MANAGER, Confidence.HIGH, now)
            } ?: Evidence.unavailable(Unavailability.RESTRICTED_BY_ANDROID, now)
        }

    private fun securityTypeLabel(securityType: Int): String? = when (securityType) {
        WifiInfo.SECURITY_TYPE_UNKNOWN -> null
        WifiInfo.SECURITY_TYPE_OPEN -> "Open"
        WifiInfo.SECURITY_TYPE_WEP -> "WEP"
        WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2 Personal (PSK)"
        WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2 Enterprise (EAP)"
        WifiInfo.SECURITY_TYPE_SAE -> "WPA3 Personal (SAE)"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3 Enterprise 192-bit"
        WifiInfo.SECURITY_TYPE_OWE -> "Enhanced Open (OWE)"
        WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI PSK"
        WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI Certificate"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3 Enterprise"
        WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
        WifiInfo.SECURITY_TYPE_DPP -> "Wi-Fi Easy Connect (DPP)"
        else -> null
    }

    companion object {
        /** Documented foreground budget since API 28: four scans per two minutes. */
        private const val THROTTLE_MAX_SCANS = 4
        private const val THROTTLE_WINDOW_MILLIS = 2 * 60 * 1000L

        const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
        private const val INVALID_BSSID = "02:00:00:00:00:00"
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
