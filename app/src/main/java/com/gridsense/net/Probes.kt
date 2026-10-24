package com.gridsense.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.CancellationSignal
import android.os.SystemClock
import com.gridsense.core.parsePingRtts
import com.gridsense.data.LOADED_PING_INTERVAL_S
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Thin wrappers over the network. Everything they return is aggregated and judged by the pure,
// unit-tested code in data/Latency.kt; nothing here can be exercised without a device.

/** One timed attempt. [ms] is null when it failed. */
data class ProbeResult(val ms: Double?, val ok: Boolean, val error: String? = null)

private val dnsExecutor = Executors.newSingleThreadExecutor()

private fun elapsedMs(startNs: Long) = (SystemClock.elapsedRealtimeNanos() - startNs) / 1e6

/** An A-record lookup that skips the phone's DNS cache, so it reaches the network's resolver. */
suspend fun dnsLookup(context: Context, name: String, timeoutMs: Long = 3_000): ProbeResult {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return ProbeResult(null, false, "No active network")
    val cancel = CancellationSignal()
    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<ProbeResult> { cont ->
            val start = SystemClock.elapsedRealtimeNanos()
            DnsResolver.getInstance().query(
                network, name, DnsResolver.TYPE_A, DnsResolver.FLAG_NO_CACHE_LOOKUP, dnsExecutor, cancel,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        val ok = rcode == 0 && answer.isNotEmpty()
                        if (cont.isActive) cont.resume(ProbeResult(if (ok) elapsedMs(start) else null, ok, if (ok) null else "rcode $rcode"))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (cont.isActive) cont.resume(ProbeResult(null, false, error.message))
                    }
                }
            )
            cont.invokeOnCancellation { cancel.cancel() }
        }
    }
    return result ?: ProbeResult(null, false, "Timed out")
}

/** Time to complete a TCP handshake. The name is resolved first, outside the timing. */
suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int = 2_000): ProbeResult = withContext(Dispatchers.IO) {
    try {
        val address = InetAddress.getByName(host)
        Socket().use { socket ->
            val start = SystemClock.elapsedRealtimeNanos()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            ProbeResult(elapsedMs(start), true)
        }
    } catch (e: IOException) {
        ProbeResult(null, false, e.message ?: e.javaClass.simpleName)
    }
}

data class LoadResult(
    val bytes: Long,
    val durationMs: Long,
    val ok: Boolean,
    val rtts: List<Double>,
    val error: String? = null
)

/**
 * Downloads [url] for at most [maxMs] or [capBytes], whichever comes first, while pinging
 * [pingHost] every 0.2 s. [onProgress] gets the fraction of the time budget used.
 */
suspend fun loadTest(
    url: String,
    capBytes: Long?,
    maxMs: Long,
    pingHost: String?,
    onProgress: (Float) -> Unit
): LoadResult = coroutineScope {
    val pinger = pingHost?.let {
        ProcessBuilder(
            "/system/bin/ping", "-i", LOADED_PING_INTERVAL_S.toString(), "-W", "1",
            "-w", ((maxMs / 1000) + 2).toString(), it
        ).redirectErrorStream(true).start()
    }
    val pingOutput = async(Dispatchers.IO) {
        pinger?.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
    val download = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        var bytes = 0L
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.useCaches = false
            try {
                if (conn.responseCode !in 200..299) {
                    return@withContext LoadResult(0, SystemClock.elapsedRealtime() - start, false, emptyList(), "HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (elapsed >= maxMs || (capBytes != null && bytes >= capBytes)) break
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        onProgress((elapsed.toFloat() / maxMs).coerceIn(0f, 1f))
                    }
                }
            } finally {
                conn.disconnect()
            }
            LoadResult(bytes, SystemClock.elapsedRealtime() - start, bytes > 0, emptyList())
        } catch (e: IOException) {
            LoadResult(bytes, SystemClock.elapsedRealtime() - start, false, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }
    pinger?.destroy()
    download.copy(rtts = parsePingRtts(pingOutput.await()))
}
