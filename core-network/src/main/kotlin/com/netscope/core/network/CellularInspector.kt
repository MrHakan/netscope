package com.netscope.core.network

import android.content.Context
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CellularSnapshot(
    val available: Boolean,
    val operatorName: String?,
    val networkOperator: String?,
    val mcc: String?,
    val mnc: String?,
    val simOperator: String?,
    val networkType: String?,
    val isRoaming: Boolean?,
    val signalLevel: Int?,
    val signalDbm: Int?,
    val signalDetails: List<String>,
    val note: String?,
)

/**
 * Reads cellular state that Android exposes without requesting cell-location identity.
 *
 * Cell ID/LAC/TAC are deliberately not requested here because modern Android gates them
 * behind location/phone permissions. The UI names that restriction explicitly.
 */
@Singleton
class CellularInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val telephony: TelephonyManager?
        get() = context.getSystemService(TelephonyManager::class.java)

    @Suppress("DEPRECATION")
    fun snapshot(): CellularSnapshot {
        val manager = telephony ?: return CellularSnapshot(
            false, null, null, null, null, null, null, null, null, null, emptyList(),
            "This device does not expose TelephonyManager.",
        )

        val operator = runCatching { manager.networkOperator }.getOrNull()?.takeIf { it.isNotBlank() }
        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.signalStrength }.getOrNull()
        } else null
        val strengths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signal?.cellSignalStrengths.orEmpty()
        } else emptyList()

        val type = runCatching { manager.dataNetworkType }.getOrNull()
            ?: runCatching { manager.networkType }.getOrNull()

        return CellularSnapshot(
            available = true,
            operatorName = runCatching { manager.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            networkOperator = operator,
            mcc = operator?.takeIf { it.length >= 5 }?.take(3),
            mnc = operator?.takeIf { it.length >= 5 }?.drop(3),
            simOperator = runCatching { manager.simOperator }.getOrNull()?.takeIf { it.isNotBlank() },
            networkType = type?.let(::networkTypeLabel),
            isRoaming = runCatching { manager.isNetworkRoaming }.getOrNull(),
            signalLevel = signal?.level,
            signalDbm = strengths.mapNotNull { runCatching { it.dbm }.getOrNull() }
                .filter { it != CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN }
                .maxOrNull(),
            signalDetails = strengths.take(8).map { strength ->
                val name = strength.javaClass.simpleName.removePrefix("CellSignalStrength")
                val dbm = runCatching { strength.dbm }.getOrNull()
                val asu = runCatching { strength.asuLevel }.getOrNull()
                name + ": " + (dbm?.let { it.toString() + " dBm" } ?: "unknown") +
                    (asu?.let { ", ASU " + it } ?: "")
            },
            note = "Cell ID/LAC/TAC are not read without additional Android phone/location " +
                "permissions. Signal strength may be a cached modem value.",
        )
    }

    private fun networkTypeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA (2G)"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT (2G)"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN (2G)"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO rev. 0 (3G)"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO rev. A (3G)"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO rev. B (3G)"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD (3G)"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
        TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        else -> "Unknown (" + type + ")"
    }
}
