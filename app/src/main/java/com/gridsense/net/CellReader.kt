package com.gridsense.net

import android.content.Context
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager

/** One reading of the serving cell. Fields the radio does not report come back null. */
data class CellReading(
    val tech: String,
    val dbm: Int,
    val level: Int,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val cellId: Long?,
    val tac: Int?,
    val pci: Int?,
    val arfcn: Int?,
    val operator: String?
)

private fun Int.orNull(): Int? = if (this == CellInfo.UNAVAILABLE) null else this

private fun Long.orNull(): Long? = if (this == CellInfo.UNAVAILABLE_LONG) null else this

/**
 * Reads the registered cell. Like the Wi-Fi identifiers this needs ACCESS_FINE_LOCATION, and
 * the platform throws rather than returning empty when the permission is missing.
 */
fun readCell(context: Context): CellReading? {
    val telephony = context.applicationContext
        .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    val operator = telephony.networkOperatorName?.ifBlank { null }
    val infos = try {
        telephony.allCellInfo
    } catch (denied: SecurityException) {
        null
    } ?: return null

    val registered = infos.firstOrNull { it.isRegistered } ?: return null

    return when (registered) {
        is CellInfoLte -> {
            val signal = registered.cellSignalStrength
            val identity: CellIdentityLte = registered.cellIdentity
            CellReading(
                tech = "LTE",
                dbm = signal.dbm,
                level = signal.level,
                rsrp = signal.rsrp.orNull(),
                rsrq = signal.rsrq.orNull(),
                sinr = signal.rssnr.orNull(),
                cellId = identity.ci.orNull()?.toLong(),
                tac = identity.tac.orNull(),
                pci = identity.pci.orNull(),
                arfcn = identity.earfcn.orNull(),
                operator = operator
            )
        }

        is CellInfoNr -> {
            val signal = registered.cellSignalStrength as? CellSignalStrengthNr
            val identity = registered.cellIdentity as? CellIdentityNr
            CellReading(
                tech = "NR",
                dbm = signal?.dbm ?: registered.cellSignalStrength.dbm,
                level = registered.cellSignalStrength.level,
                rsrp = signal?.ssRsrp?.orNull(),
                rsrq = signal?.ssRsrq?.orNull(),
                sinr = signal?.ssSinr?.orNull(),
                cellId = identity?.nci?.orNull(),
                tac = identity?.tac?.orNull(),
                pci = identity?.pci?.orNull(),
                arfcn = identity?.nrarfcn?.orNull(),
                operator = operator
            )
        }

        is CellInfoWcdma -> {
            val signal = registered.cellSignalStrength
            val identity: CellIdentityWcdma = registered.cellIdentity
            CellReading(
                tech = "WCDMA",
                dbm = signal.dbm,
                level = signal.level,
                rsrp = null,
                rsrq = null,
                sinr = null,
                cellId = identity.cid.orNull()?.toLong(),
                tac = identity.lac.orNull(),
                pci = identity.psc.orNull(),
                arfcn = identity.uarfcn.orNull(),
                operator = operator
            )
        }

        is CellInfoGsm -> {
            val signal = registered.cellSignalStrength
            val identity: CellIdentityGsm = registered.cellIdentity
            CellReading(
                tech = "GSM",
                dbm = signal.dbm,
                level = signal.level,
                rsrp = null,
                rsrq = null,
                sinr = null,
                cellId = identity.cid.orNull()?.toLong(),
                tac = identity.lac.orNull(),
                pci = null,
                arfcn = identity.arfcn.orNull(),
                operator = operator
            )
        }

        else -> null
    }
}
