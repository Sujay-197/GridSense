package com.gridsense.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** A neighbour AP seen in a scan. */
data class Neighbour(
    val bssid: String,
    val ssid: String,
    val freqMhz: Int,
    val rssiDbm: Int
)

data class ScanOutcome(val neighbours: List<Neighbour>, val fresh: Boolean, val note: String)

private const val THROTTLE_WINDOW_MS = 2 * 60 * 1000L
private const val THROTTLE_LIMIT = 4

/**
 * One neighbour scan per point. Android allows four foreground startScan calls per two
 * minutes, so we keep our own budget and never burn a call we know will be rejected.
 * When we are out of budget the cached scan list is used instead and the rows are
 * marked as cached.
 */
class NeighbourScanner(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val recentScans = ArrayDeque<Long>()

    fun budgetRemaining(): Int {
        prune()
        return (THROTTLE_LIMIT - recentScans.size).coerceAtLeast(0)
    }

    /** Milliseconds until a scan slot frees up, or 0 when one is available now. */
    fun msUntilNextSlot(): Long {
        prune()
        if (recentScans.size < THROTTLE_LIMIT) return 0
        return THROTTLE_WINDOW_MS - (SystemClock.elapsedRealtime() - recentScans.first())
    }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - THROTTLE_WINDOW_MS
        while (recentScans.isNotEmpty() && recentScans.first() < cutoff) recentScans.removeFirst()
    }

    @Suppress("DEPRECATION")
    suspend fun scanOnce(): ScanOutcome {
        if (budgetRemaining() == 0) {
            val seconds = (msUntilNextSlot() / 1000).coerceAtLeast(0)
            return ScanOutcome(
                cached(),
                false,
                "Scan throttled, cached neighbour list used. Next slot in " + seconds + " s."
            )
        }
        if (!wifi.startScan()) {
            return ScanOutcome(cached(), false, "System refused startScan, cached neighbour list used.")
        }
        recentScans.addLast(SystemClock.elapsedRealtime())
        return try {
            withTimeout(12_000) { awaitResults() }
            ScanOutcome(cached(), true, "Neighbour scan complete.")
        } catch (timeout: TimeoutCancellationException) {
            ScanOutcome(cached(), false, "Scan did not report back in time, cached neighbour list used.")
        }
    }

    private suspend fun awaitResults() = suspendCancellableCoroutine<Unit> { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                appContext.unregisterReceiver(this)
                if (cont.isActive) cont.resume(Unit)
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        cont.invokeOnCancellation {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun cached(): List<Neighbour> = wifi.scanResults.map {
        Neighbour(
            bssid = it.BSSID ?: DUMMY_BSSID,
            ssid = it.SSID ?: "",
            freqMhz = it.frequency,
            rssiDbm = it.level
        )
    }
}
