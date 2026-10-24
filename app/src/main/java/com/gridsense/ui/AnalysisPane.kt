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

/** Analyses beyond the heatmap. Each one is a section; more join as they are built. */
@Composable
fun AnalysisPane(points: List<GridPoint>, samples: List<Sample>, textMeasurer: TextMeasurer) {
    val report = remember(points, samples) {
        channelReport(loggedNodes(points), samples.groupBy { it.pointId })
    }
    val findings = remember(points, samples) { channelInsights(points, samples) }

    if (report == null) {
        EmptyState(
            icon = Icons.Filled.Analytics,
            title = "Nothing to analyse yet",
            body = "Log a few nodes first. Channel analysis uses the neighbour scans taken at each node."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        item(key = "channels") {
            ChannelsSection(report, findings?.lines.orEmpty(), textMeasurer)
        }
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
