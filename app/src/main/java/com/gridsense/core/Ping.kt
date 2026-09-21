package com.gridsense.core

private val TIME_FIELD = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)""")

/** Pulls the per-reply RTTs out of the `ping` binary's stdout. */
fun parsePingRtts(output: String): List<Double> =
    TIME_FIELD.findAll(output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

data class PingResult(val sent: Int, val rtts: List<Double>) {
    val received: Int get() = rtts.size
    val lossPct: Double get() = (sent - received).coerceAtLeast(0) * 100.0 / sent
    val avgMs: Double? get() = if (rtts.isEmpty()) null else rtts.average()
    val jitterMs: Double? get() = if (rtts.isEmpty()) null else meanAbsDiff(rtts)
}
