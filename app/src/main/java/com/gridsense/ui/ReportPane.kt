package com.gridsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.gridsense.data.GridPoint
import com.gridsense.data.Sample
import com.gridsense.data.buildInsights
import com.gridsense.ui.components.MetricRow
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.theme.Spacing

private fun sectionIcon(title: String): ImageVector = when (title) {
    "Overview" -> Icons.Filled.Summarize
    "Wi-Fi coverage" -> Icons.Filled.Wifi
    "Bands and neighbours" -> Icons.Filled.Router
    "Channels" -> Icons.Filled.SettingsInputAntenna
    "Cellular" -> Icons.Filled.SignalCellularAlt
    "Latency, jitter and loss" -> Icons.Filled.Speed
    "Data quality" -> Icons.Filled.WarningAmber
    else -> Icons.Filled.Info
}

/** Plain-language findings for the survey, worked out from the logged samples. */
@Composable
fun ReportPane(points: List<GridPoint>, samples: List<Sample>) {
    val sections = remember(points, samples) { buildInsights(points, samples) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        items(sections, key = { it.title }) { section ->
            SectionCard(title = section.title, icon = sectionIcon(section.title)) {
                for (r in section.ratings) {
                    MetricRow(r.label, r.value, level = r.level, rated = true)
                }
                for (line in section.lines) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
