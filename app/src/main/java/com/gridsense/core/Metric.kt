package com.gridsense.core

/** Which family of readings a metric is drawn from. */
enum class Band { WIFI, CELLULAR, IP }

/** The quantities the heatmap can display. */
enum class Metric(
    val label: String,
    val unit: String,
    val higherIsBetter: Boolean,
    val band: Band,
    /** Decimal places when the value is printed. */
    val decimals: Int = 0
) {
    RSSI("Wi-Fi RSSI", "dBm", true, Band.WIFI),
    CELL_DBM("Cellular signal", "dBm", true, Band.CELLULAR),
    CELL_RSRP("Cellular RSRP", "dBm", true, Band.CELLULAR),
    CELL_RSRQ("Cellular RSRQ", "dB", true, Band.CELLULAR),
    CELL_SINR("Cellular SINR", "dB", true, Band.CELLULAR),
    LATENCY("Latency", "ms", false, Band.IP, 1),
    JITTER("Jitter", "ms", false, Band.IP, 1),
    LOSS("Packet loss", "%", false, Band.IP),
    DNS("DNS lookup", "ms", false, Band.IP, 1),
    TCP_CONNECT("TCP connect", "ms", false, Band.IP, 1),
    LOADED_LATENCY("Loaded latency", "ms", false, Band.IP, 1),
    BUFFERBLOAT("Bufferbloat", "ms", false, Band.IP, 1),
    DOWNLOAD("Download", "Mbps", true, Band.IP, 1);

    /** The value with this metric's decimals, without the unit. */
    fun format(value: Double): String = String.format(java.util.Locale.US, "%.${decimals}f", value)
}

/** Which network a survey is primarily about. It picks the default metric and ping target. */
enum class SurveyMode(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular");

    val defaultMetric: Metric get() = if (this == WIFI) Metric.RSSI else Metric.CELL_DBM
}
