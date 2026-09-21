package com.gridsense.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** One reading of the current Wi-Fi association. */
data class LinkReading(
    val bssid: String?,
    val ssid: String?,
    val freqMhz: Int,
    val rssiDbm: Int,
    val linkSpeedMbps: Int
)

const val UNKNOWN_SSID = "<unknown ssid>"
const val DUMMY_BSSID = "02:00:00:00:00:00"

/**
 * Reads the connection info. This is the WifiManager path that is not subject to the
 * scan throttle, so it can be polled every 500 ms.
 */
@Suppress("DEPRECATION")
fun readLink(context: Context): LinkReading? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wifi.connectionInfo ?: return null
    if (info.networkId == -1 && info.bssid == null) return null
    val ssid = info.ssid?.trim('"')
    return LinkReading(
        bssid = info.bssid,
        ssid = ssid,
        freqMhz = info.frequency,
        rssiDbm = info.rssi,
        linkSpeedMbps = info.linkSpeed
    )
}

/** True when Android is handing back real identifiers rather than the redacted placeholders. */
fun identifiersReadable(reading: LinkReading?): Boolean {
    if (reading == null) return false
    if (reading.bssid == null || reading.bssid == DUMMY_BSSID) return false
    if (reading.ssid == null || reading.ssid == UNKNOWN_SSID) return false
    return true
}

/** The default-route gateway of the active network, which is what we ping. */
fun gatewayAddress(context: Context): String? {
    val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return null
    val props = cm.getLinkProperties(network) ?: return null
    return props.routes
        .firstOrNull { it.isDefaultRoute && it.hasGateway() }
        ?.gateway
        ?.hostAddress
}

enum class Transport { WIFI, CELLULAR, OTHER, NONE }

const val DEFAULT_PUBLIC_HOST = "8.8.8.8"

fun activeTransport(context: Context): Transport {
    val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return Transport.NONE
    val caps = cm.getNetworkCapabilities(network) ?: return Transport.NONE
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
        else -> Transport.OTHER
    }
}

/**
 * What to ping. An explicit per-survey host always wins. Otherwise the gateway is used on
 * Wi-Fi, and a public address on cellular, where carrier NAT usually makes the gateway
 * unreachable.
 */
fun pingTarget(context: Context, override: String?): String? {
    if (!override.isNullOrBlank()) return override.trim()
    return when (activeTransport(context)) {
        Transport.CELLULAR -> DEFAULT_PUBLIC_HOST
        Transport.WIFI -> gatewayAddress(context)
        else -> gatewayAddress(context) ?: DEFAULT_PUBLIC_HOST
    }
}
