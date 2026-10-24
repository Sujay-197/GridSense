package com.gridsense.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gridsense.data.STATUS_DONE
import com.gridsense.data.decodePolygon
import com.gridsense.export.baseName
import com.gridsense.export.share
import com.gridsense.export.writePng
import com.gridsense.ui.components.ConfirmDialog
import com.gridsense.ui.components.ScreenScaffold
import com.gridsense.ui.components.StatusBanner
import com.gridsense.ui.theme.LightPlanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SurveyTab(val label: String, val icon: ImageVector) {
    GRID("Grid", Icons.Filled.GridOn),
    HEATMAP("Heatmap", Icons.Filled.Layers),
    ANALYSIS("Analysis", Icons.Filled.Analytics),
    REPORT("Report", Icons.Filled.Description)
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share report"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    vm: SurveyViewModel,
    roomId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onFixPermissions: () -> Unit
) {
    LaunchedEffect(roomId) { vm.openRoom(roomId) }

    val room by vm.room.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val routers by vm.routers.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(SurveyTab.GRID) }
    var selectedPointId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.placingRouter) { vm.cancelRouterPlacement() }

    val currentRoom = room?.takeIf { it.id == roomId }
    val polygon = remember(currentRoom?.polygon) { decodePolygon(currentRoom?.polygon.orEmpty()) }
    val samplesByPoint = remember(samples) { samples.groupBy { it.pointId } }
    val active = points.filter { it.enabled }
    val done = active.count { it.status == STATUS_DONE }

    fun exportPng() {
        val r = currentRoom ?: return
        scope.launch {
            val file = withContext(Dispatchers.Default) {
                val scene = buildHeatmapScene(
                    r.name, polygon, points, samples, routers,
                    vm.metric, vm.bssidFilter, vm.showRouters, vm.fixedScale
                )
                if (scene.raster == null) null
                else writePng(context, renderScene(scene, textMeasurer, density, LightPlanColors), baseName(r))
            }
            if (file == null) snackbar.showSnackbar("Nothing to export: no logged nodes have " + vm.metric.label + " readings.")
            else share(context, listOf(file), "image/png")
        }
    }

    ScreenScaffold(
        title = currentRoom?.name ?: "",
        subtitle = currentRoom?.let { "$done of ${active.size} nodes · " + modeOf(it).label },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            Box {
                IconButton(onClick = { exportMenu = true }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share or export")
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    MenuItem("Heatmap PNG", Icons.Filled.Image) {
                        exportMenu = false
                        exportPng()
                    }
                    MenuItem("Samples CSV", Icons.Filled.TableChart) {
                        exportMenu = false
                        scope.launch { share(context, vm.exportCsv(roomId), "text/csv") }
                    }
                    MenuItem("Survey JSON", Icons.Filled.DataObject) {
                        exportMenu = false
                        scope.launch { vm.exportJson(roomId)?.let { share(context, listOf(it), "application/json") } }
                    }
                    MenuItem("Report text", Icons.Filled.Description) {
                        exportMenu = false
                        scope.launch { vm.reportText(roomId)?.let { (title, text) -> shareText(context, title, text) } }
                    }
                }
            }
            Box {
                IconButton(onClick = { overflowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                    MenuItem("Edit details", Icons.Filled.Edit) {
                        overflowMenu = false
                        onEdit()
                    }
                    MenuItem("Delete survey", Icons.Filled.Delete) {
                        overflowMenu = false
                        confirmDelete = true
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                SurveyTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == SurveyTab.GRID && currentRoom != null) {
                if (vm.placingRouter) {
                    ExtendedFloatingActionButton(
                        onClick = { vm.cancelRouterPlacement() },
                        icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        text = { Text("Cancel placing") }
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { vm.beginRouterPlacement() },
                        icon = { Icon(Icons.Filled.Router, contentDescription = null) },
                        text = { Text("Router") }
                    )
                }
            }
        }
    ) { padding ->
        if (currentRoom == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ScreenScaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!vm.readiness.canLog) {
                StatusBanner(
                    text = "Logging is blocked: grant the location permission, switch location services " +
                        "on, and be on the Wi-Fi network or have a serving cell.",
                    actionLabel = "Fix",
                    onAction = onFixPermissions
                )
            }
            when (tab) {
                SurveyTab.GRID -> GridPane(
                    vm = vm,
                    room = currentRoom,
                    polygon = polygon,
                    primaryMetric = modeOf(currentRoom).defaultMetric,
                    points = points,
                    samplesByPoint = samplesByPoint,
                    routers = routers,
                    textMeasurer = textMeasurer,
                    onSelect = { selectedPointId = it.id },
                    onRetake = {
                        selectedPointId = it.id
                        if (it.enabled) vm.startLogging(it)
                    }
                )
                SurveyTab.HEATMAP -> HeatmapPane(vm, currentRoom, polygon, points, samples, routers, textMeasurer)
                SurveyTab.ANALYSIS -> AnalysisPane(points, samples, routers, textMeasurer)
                SurveyTab.REPORT -> ReportPane(points, samples, routers)
            }
        }
    }

    val selected = points.firstOrNull { it.id == selectedPointId }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedPointId = null }, sheetState = sheetState) {
            NodeSheet(vm, selected, samplesByPoint[selected.id].orEmpty())
        }
    }

    if (vm.routerDialogOpen) RouterDialog(vm)

    if (confirmDelete && currentRoom != null) {
        ConfirmDialog(
            title = "Delete survey?",
            text = "This permanently removes \"" + currentRoom.name +
                "\" together with all of its nodes, samples and routers.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.deleteRoom(roomId)
                onBack()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}
