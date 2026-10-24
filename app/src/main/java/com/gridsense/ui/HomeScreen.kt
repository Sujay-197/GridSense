package com.gridsense.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridsense.data.SurveyRoom
import com.gridsense.data.SurveySummary
import com.gridsense.export.share
import com.gridsense.ui.components.ConfirmDialog
import com.gridsense.ui.components.EmptyState
import com.gridsense.ui.components.QualityChip
import com.gridsense.ui.components.ScreenScaffold
import com.gridsense.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    vm: SurveyViewModel,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onPermissions: () -> Unit
) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<SurveyRoom?>(null) }

    ScreenScaffold(
        title = "GridSense",
        actions = {
            IconButton(onClick = onPermissions) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = "Permissions and readiness")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New survey") }
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.GridOn,
                title = "No surveys yet",
                body = "Split a room into a grid, stand at the centre of each cell and log it. " +
                    "GridSense maps Wi-Fi, cellular and latency across the room.",
                actionLabel = "New survey",
                onAction = onNew,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                items(rooms, key = { it.id }) { room ->
                    SurveyCard(
                        room = room,
                        summary = summaries[room.id],
                        onOpen = { onOpen(room.id) },
                        onExportCsv = { scope.launch { share(context, vm.exportCsv(room.id), "text/csv") } },
                        onExportJson = {
                            scope.launch { vm.exportJson(room.id)?.let { share(context, listOf(it), "application/json") } }
                        },
                        onDelete = { pendingDelete = room }
                    )
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDialog(
            title = "Delete survey?",
            text = "This permanently removes \"" + target.name +
                "\" together with all of its nodes, samples and routers.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vm.deleteRoom(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun SurveyCard(
    room: SurveyRoom,
    summary: SurveySummary?,
    onOpen: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(start = Spacing.l, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(top = Spacing.l)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        modeOf(room).label + " · " + room.gridRows + " x " + room.gridCols + " grid · " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(room.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options for " + room.name)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Export CSV", Icons.Filled.TableChart) {
                            menu = false
                            onExportCsv()
                        }
                        MenuItem("Export JSON", Icons.Filled.DataObject) {
                            menu = false
                            onExportJson()
                        }
                        MenuItem("Delete", Icons.Filled.Delete) {
                            menu = false
                            onDelete()
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(end = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(
                        progress = { summary?.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        (summary?.logged ?: 0).toString() + " of " + (summary?.total ?: 0) + " nodes logged",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                QualityChip(summary?.level)
            }
        }
    }
}
