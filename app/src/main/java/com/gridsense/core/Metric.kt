package com.gridsense.core

/** Which family of readings a metric is drawn from. */
enum class Band { WIFI, CELLULAR, IP }

/** The quantities the heatmap can display. */
enum class Metric(
    val label: String,
    val unit: String,
    val higherIsBetter: Boolean,
    val band: Band
) {
    RSSI("Wi-Fi RSSI", "dBm", true, Band.WIFI),
    CELL_DBM("Cellular signal", "dBm", true, Band.CELLULAR),
    CELL_RSRP("Cellular RSRP", "dBm", true, Band.CELLULAR),
    CELL_RSRQ("Cellular RSRQ", "dB", true, Band.CELLULAR),
    CELL_SINR("Cellular SINR", "dB", true, Band.CELLULAR),
    LATENCY("Latency", "ms", false, Band.IP),
    JITTER("Jitter", "ms", false, Band.IP),
    LOSS("Packet loss", "%", false, Band.IP)
}

/** Which network a survey is primarily about. It picks the default metric and ping target. */
enum class SurveyMode(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular");

    val defaultMetric: Metric get() = if (this == WIFI) Metric.RSSI else Metric.CELL_DBM
}
