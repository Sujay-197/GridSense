package com.gridsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import com.gridsense.data.MIN_FIT_DISTANCE_M
import com.gridsense.data.PathLossFit
import com.gridsense.data.PathLossPoint
import com.gridsense.data.Router
import com.gridsense.data.exponentBand
import com.gridsense.data.pathLossFit
import com.gridsense.data.pathLossWarnings
import com.gridsense.ui.components.MetricRow
import com.gridsense.ui.components.StatusBanner
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.gridsense.core.isDfs
import com.gridsense.data.AirNetwork
import com.gridsense.data.ChannelReport
import com.gridsense.data.GridPoint
import com.gridsense.data.Sample
import com.gridsense.data.channelInsights
import com.gridsense.data.channelReport
import com.gridsense.data.loggedNodes
import com.gridsense.ui.components.EmptyState
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.theme.Spacing
import kotlin.math.roundToInt

/** Analyses beyond the heatmap, each one a section. */
@Composable
fun AnalysisPane(
    points: List<GridPoint>,
    samples: List<Sample>,
    routers: List<Router>,
    textMeasurer: TextMeasurer
) {
    val logged = remember(points) { loggedNodes(points) }
    val byPoint = remember(samples) { samples.groupBy { it.pointId } }
    val report = remember(logged, byPoint) { channelReport(logged, byPoint) }
    val findings = remember(points, samples) { channelInsights(points, samples) }
    val fits = remember(logged, byPoint, routers) { routers.map { it to pathLossFit(it, logged, byPoint) } }

    if (logged.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Analytics,
            title = "Nothing to analyse yet",
            body = "Log a few nodes first. Path loss needs a router marker; channel analysis uses " +
                "the neighbour scans taken at each node."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        if (fits.isEmpty()) {
            item(key = "pathloss-empty") {
                SectionCard(title = "Path loss", icon = Icons.AutoMirrored.Filled.ShowChart) {
                    Text(
                        "Place a router marker to fit a path-loss model. On the Grid tab, tap Router, " +
                            "tap where the router stands, and pick its BSSID. If it sits above or below " +
                            "the phone, enter the height difference too.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        items(fits, key = { "pathloss-" + it.first.id }) { (router, fit) ->
            PathLossSection(router, fit, textMeasurer)
        }
        item(key = "channels") {
            if (report == null) {
                SectionCard(title = "Channels", icon = Icons.Filled.SettingsInputAntenna) {
                    Text("No neighbour scans have been logged yet.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                ChannelsSection(report, findings?.lines.orEmpty(), textMeasurer)
            }
        }
    }
}

private fun f1(v: Double) = String.format("%.1f", v)

@Composable
private fun PathLossSection(router: Router, fit: PathLossFit?, textMeasurer: TextMeasurer) {
    var picked by remember(fit) { mutableStateOf<PathLossPoint?>(null) }
    SectionCard(title = "Path loss: " + router.label, icon = Icons.AutoMirrored.Filled.ShowChart) {
        if (fit == null) {
            Text(
                "Too few nodes heard ${router.bssid} at different distances of " +
                    "${f1(MIN_FIT_DISTANCE_M)} m or more to fit a model. Log more nodes.",
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }
        val warnings = pathLossWarnings(fit)
        if (warnings.isNotEmpty()) {
            StatusBanner(
                text = warnings.joinToString(" ") + " Treat n as indicative.",
                icon = Icons.Filled.WarningAmber
            )
        }
        PathLossChart(fit, picked?.pointId, textMeasurer) { picked = it }
        Text(
            picked?.let {
                "Node ${it.label}: ${f1(it.distanceM)} m, ${f1(it.rssiDbm)} dBm, " +
                    (if (it.fromLink) "from the connection" else "from neighbour scans") +
                    String.format(", model %.1f dBm", fit.predict(it.distanceM))
            } ?: "Solid line: fit. Dashed: free space (n = 2). Hollow dots came from scans. Tap a dot to identify its node.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MetricRow("Exponent n", f1(fit.n))
        MetricRow("P0 (RSSI at 1 m)", f1(fit.p0Dbm), unit = "dBm")
        MetricRow("Shadowing σ", f1(fit.sigmaDb), unit = "dB")
        MetricRow("R²", fit.r2?.let { String.format("%.2f", it) })
        MetricRow("Nodes used", fit.count.toString() + if (fit.tooClose.isEmpty()) "" else " (${fit.tooClose.size} too close)")
        MetricRow("Distance range", f1(fit.minDistanceM) + " to " + f1(fit.maxDistanceM), unit = "m")
        fit.eirpDbm?.let { MetricRow("EIRP (estimate)", f1(it), unit = "dBm") }
        Text(
            "n = ${f1(fit.n)} is ${exponentBand(fit.n)}.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ChannelsSection(report: ChannelReport, lines: List<String>, textMeasurer: TextMeasurer) {
    SectionCard(title = "Channels", icon = Icons.Filled.SettingsInputAntenna) {
        val band24 = report.networks.filter { it.band24 }
        val band5 = report.networks.filter { it.band5 }
        if (band24.isNotEmpty()) {
            Text("2.4 GHz", style = MaterialTheme.typography.titleSmall)
            ChannelChart(band24, band5 = false, textMeasurer = textMeasurer)
        }
        if (band5.isNotEmpty()) {
            Text("5 GHz", style = MaterialTheme.typography.titleSmall)
            ChannelChart(band5, band5 = true, textMeasurer = textMeasurer)
        }
        for (line in lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Networks heard", style = MaterialTheme.typography.titleSmall)
        for (n in report.networks) NetworkRow(n)
    }
}

@Composable
private fun NetworkRow(n: AirNetwork) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                n.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (n.own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                listOfNotNull(
                    n.channel?.let { "ch $it" + if (n.band5 && isDfs(it)) " (DFS)" else "" },
                    "${n.widthMhz} MHz",
                    "${n.nodesSeen} node${if (n.nodesSeen == 1) "" else "s"}"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (n.own) AssistChip(onClick = {}, label = { Text("Yours") })
        Text("${n.rssiDbm.roundToInt()} dBm", style = MaterialTheme.typography.bodyMedium)
    }
}
