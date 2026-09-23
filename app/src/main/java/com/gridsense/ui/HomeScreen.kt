package com.gridsense.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridsense.data.SurveyRoom

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: SurveyViewModel) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SurveyRoom?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("GridSense") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.startLayout() },
                text = { Text("New survey") },
                icon = {}
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("No rooms yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Create a survey, measure its outline with AR or enter the room size, then mark the points you want to log.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rooms, key = { it.id }) { room ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { vm.openRoom(room.id) },
                                onLongClick = { pendingDelete = room }
                            )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(room.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                modeOf(room).label + " survey, " + room.samplesPerPoint +
                                    " samples per point, outline " +
                                    room.outlineSource.lowercase(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete room?") },
            text = {
                Text(
                    "This permanently removes \"" + target.name +
                        "\" together with all of its points, samples and routers."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRoom(target.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}
