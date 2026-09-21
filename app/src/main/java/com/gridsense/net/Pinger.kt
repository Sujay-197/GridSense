package com.gridsense.net

import com.gridsense.core.PingResult
import com.gridsense.core.parsePingRtts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs ping -c <count> against [host] and parses the per-reply RTTs out of its output. */
suspend fun ping(host: String, count: Int): PingResult = withContext(Dispatchers.IO) {
    val process = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "1", host)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    PingResult(sent = count, rtts = parsePingRtts(output))
}
