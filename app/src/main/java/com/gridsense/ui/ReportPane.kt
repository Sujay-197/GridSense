package com.gridsense.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gridsense.data.GridPoint
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import com.gridsense.data.buildInsights
import com.gridsense.data.insightsAsText

/** Plain-language findings for the survey, worked out from the logged samples. */
@Composable
fun ReportPane(room: SurveyRoom, points: List<GridPoint>, samples: List<Sample>) {
    val context = LocalContext.current
    val sections = remember(points, samples) { buildInsights(points, samples) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sections, key = { it.title }) { section ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        for (line in section.lines) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val text = insightsAsText("GridSense report: " + room.name, sections)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "GridSense report: " + room.name)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share report"))
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("Share report as text") }
    }
}
