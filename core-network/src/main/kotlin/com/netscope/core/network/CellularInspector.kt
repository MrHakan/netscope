package com.netscope.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
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
    val cellIdentities: List<String>,
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
            false, null, null, null, null, null, null, null, null, null, emptyList(), emptyList(),
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

        val canReadCellIdentity =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val cellIdentities = if (canReadCellIdentity) {
            runCatching { manager.allCellInfo.orEmpty() }
                .getOrDefault(emptyList())
                .take(16)
                .mapNotNull(::cellIdentitySummary)
        } else {
            emptyList()
        }

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
            cellIdentities = cellIdentities,
            note = if (canReadCellIdentity) {
                "Cell identity is the latest cached radio information Android exposed. " +
                    "Unavailable modem fields remain omitted."
            } else {
                "CID/LAC/TAC/CI/NCI require the optional precise-location permission. " +
                    "NetScope does not infer them when Android withholds them."
            },
        )
    }

    private fun cellIdentitySummary(info: CellInfo): String? {
        val registered = if (info.isRegistered) "serving" else "neighbor"
        return when (info) {
            is CellInfoGsm -> {
                val id: CellIdentityGsm = info.cellIdentity
                parts(
                    "GSM", registered,
                    value("CID", id.cid),
                    value("LAC", id.lac),
                    value("ARFCN", id.arfcn),
                    plmn(id.mccString, id.mncString),
                )
            }
            is CellInfoWcdma -> {
                val id: CellIdentityWcdma = info.cellIdentity
                parts(
                    "WCDMA", registered,
                    value("CID", id.cid),
                    value("LAC", id.lac),
                    value("PSC", id.psc),
                    value("UARFCN", id.uarfcn),
                    plmn(id.mccString, id.mncString),
                )
            }
            is CellInfoLte -> {
                val id: CellIdentityLte = info.cellIdentity
                parts(
                    "LTE", registered,
                    value("CI", id.ci),
                    value("TAC", id.tac),
                    value("PCI", id.pci),
                    value("EARFCN", id.earfcn),
                    plmn(id.mccString, id.mncString),
                )
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as? CellIdentityNr ?: return null
                parts(
                    "NR", registered,
                    longValue("NCI", id.nci),
                    value("TAC", id.tac),
                    value("PCI", id.pci),
                    value("NRARFCN", id.nrarfcn),
                    plmn(id.mccString, id.mncString),
                )
            }
            is CellInfoTdscdma -> {
                val id: CellIdentityTdscdma = info.cellIdentity
                parts(
                    "TD-SCDMA", registered,
                    value("CID", id.cid),
                    value("LAC", id.lac),
                    value("CPID", id.cpid),
                    value("UARFCN", id.uarfcn),
                    plmn(id.mccString, id.mncString),
                )
            }
            is CellInfoCdma -> {
                val id: CellIdentityCdma = info.cellIdentity
                parts(
                    "CDMA", registered,
                    value("BID", id.basestationId),
                    value("NID", id.networkId),
                    value("SID", id.systemId),
                )
            }
            else -> null
        }
    }

    private fun value(label: String, value: Int): String? =
        value.takeUnless { it == Int.MAX_VALUE || it < 0 }?.let { label + "=" + it }

    private fun longValue(label: String, value: Long): String? =
        value.takeUnless { it == Long.MAX_VALUE || it < 0L }?.let { label + "=" + it }

    private fun plmn(mcc: String?, mnc: String?): String? =
        if (!mcc.isNullOrBlank() && !mnc.isNullOrBlank()) "PLMN=" + mcc + "-" + mnc else null

    private fun parts(vararg values: String?): String =
        values.filterNotNull().joinToString(" · ")

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
