package com.gridsense.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gridsense.ar.ArPose
import com.gridsense.ar.ArPreview
import com.gridsense.ar.ArStatus
import com.gridsense.ar.ArTracker
import com.gridsense.ar.rememberArTracker
import com.gridsense.ar.requestArInstall
import com.gridsense.core.Pt
import com.gridsense.core.SurveyMode
import com.gridsense.data.SOURCE_AR
import kotlin.math.hypot

private val ORIGIN_COLOR = Color(0xFF2E7D32)
private val CORNER_COLOR = Color(0xFF455A64)
private val AR_POINT_COLOR = Color(0xFF546E7A)
private val MANUAL_POINT_COLOR = Color(0xFFFF8F00)
private val WALKER_COLOR = Color(0xFF1976D2)

private const val GRAB_RADIUS_PX = 48f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutScreen(vm: SurveyViewModel) {
    val arUsable = vm.arStatus == ArStatus.READY && vm.readiness.cameraPermissionGranted
    val tracker = rememberArTracker(arUsable && vm.layoutPhase != LayoutPhase.SETTINGS)
    val pose = tracker?.pose ?: ArPose.NONE

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (vm.layoutPhase) {
                            LayoutPhase.SETTINGS -> "New survey"
                            LayoutPhase.OUTLINE -> "Walk the walls"
                            LayoutPhase.POINTS -> "Mark the points"
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = { vm.cancelLayout() }) { Text("Cancel") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NoteBanner(vm)
            when (vm.layoutPhase) {
                LayoutPhase.SETTINGS -> SettingsPhase(vm, arUsable)
                LayoutPhase.OUTLINE -> OutlinePhase(vm, tracker, pose)
                LayoutPhase.POINTS -> PointsPhase(vm, tracker, pose)
            }
        }
    }

    if (vm.closurePromptOpen) ClosureDialog(vm)
}

@Composable
private fun NoteBanner(vm: SurveyViewModel) {
    val note = vm.note ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF8E1))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.note = null }) { Text("Hide") }
    }
}

// --- settings ---------------------------------------------------------------

@Composable
private fun SettingsPhase(vm: SurveyViewModel, arUsable: Boolean) {
    val samples = vm.draftSamples.toIntOrNull()
    val width = vm.draftWidth.toDoubleOrNull()
    val length = vm.draftLength.toDoubleOrNull()
    val basicsValid = vm.draftName.isNotBlank() && samples != null && samples >= 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = vm.draftName,
            onValueChange = { vm.draftName = it },
            label = { Text("Survey name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Primary radio", style = MaterialTheme.typography.titleMedium)
        Text(
            "Both Wi-Fi and cellular are recorded at every point. This choice sets which one " +
                "the heatmap opens on and which ping target is used by default.",
            style = MaterialTheme.typography.bodySmall
        )
        SurveyMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = vm.draftMode == mode,
                        onClick = { vm.draftMode = mode }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = vm.draftMode == mode,
                    onClick = { vm.draftMode = mode }
                )
                Text(mode.label)
            }
        }

        OutlinedTextField(
            value = vm.draftSamples,
            onValueChange = { vm.draftSamples = it },
            label = { Text("Samples per point") },
            singleLine = true,
            isError = samples == null || samples < 1,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = vm.draftPingHost,
            onValueChange = { vm.draftPingHost = it },
            label = { Text("Ping target (optional)") },
            placeholder = { Text("Gateway on Wi-Fi, 8.8.8.8 on cellular") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Room outline", style = MaterialTheme.typography.titleMedium)
        ArOutlineCard(vm, arUsable, basicsValid)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Or enter the room size", style = MaterialTheme.typography.titleSmall)
                Text(
                    "For a rectangular room. Corner (0, 0) is the one with the length wall on " +
                        "your left as you face along it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = vm.draftWidth,
                        onValueChange = { vm.draftWidth = it },
                        label = { Text("Width (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vm.draftLength,
                        onValueChange = { vm.draftLength = it },
                        label = { Text("Length (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = { vm.useRectangle(width!!, length!!) },
                    enabled = basicsValid && width != null && width > 0 &&
                        length != null && length > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use this rectangle") }
            }
        }
    }
}

@Composable
private fun ArOutlineCard(vm: SurveyViewModel, arUsable: Boolean, basicsValid: Boolean) {
    val activity = LocalContext.current as Activity
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshReadiness() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Walk it with AR", style = MaterialTheme.typography.titleSmall)
            when {
                vm.arStatus == ArStatus.CHECKING -> Text(
                    "Checking whether this phone supports ARCore.",
                    style = MaterialTheme.typography.bodySmall
                )

                vm.arStatus == ArStatus.UNSUPPORTED -> Text(
                    "This phone does not support ARCore. Enter the room size below and " +
                        "place the points by hand.",
                    style = MaterialTheme.typography.bodySmall
                )

                vm.arStatus == ArStatus.NEEDS_INSTALL -> {
                    Text(
                        "ARCore needs Google Play Services for AR from the Play Store.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { vm.note = requestArInstall(activity) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Install Google Play Services for AR") }
                }

                !vm.readiness.cameraPermissionGranted -> {
                    Text(
                        "AR tracking needs the camera. Images are only used for tracking and " +
                            "are never stored.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow the camera") }
                }

                else -> Text(
                    "Stand in a corner with a wall on your left and point the camera along " +
                        "it. That corner becomes (0, 0). Walk the walls marking each corner, " +
                        "then return and close the outline.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = { vm.beginArOutline() },
                enabled = arUsable && basicsValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Walk the outline with AR") }
        }
    }
}

// --- AR outline walk ---------------------------------------------------------

@Composable
private fun OutlinePhase(vm: SurveyViewModel, tracker: ArTracker?, pose: ArPose) {
    Column(modifier = Modifier.fillMaxSize()) {
        CameraStrip(vm, tracker, pose, height = 200)
        PlanCanvas(vm, pose, modifier = Modifier.weight(1f))
        Readout(vm, pose)

        val originNeeded = vm.arFrame == null || vm.arFrameIsStale(pose)
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (originNeeded) {
                Button(
                    onClick = { vm.setOrigin(pose) },
                    enabled = pose.tracking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (vm.arFrame == null) "Set origin here" else "Back at (0, 0), set origin again")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.markCorner(pose) },
                        enabled = vm.positionOf(pose) != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Mark corner") }
                    OutlinedButton(
                        onClick = { vm.undoCorner() },
                        enabled = vm.draftCorners.size > 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("Undo") }
                }
                Button(
                    onClick = { vm.closeOutline(pose) },
                    enabled = vm.draftCorners.size >= 3,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back at the start, close the outline") }
            }
            TextButton(
                onClick = { vm.backToSettings() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("AR not working? Enter the room size instead") }
        }
    }
}

// --- points ------------------------------------------------------------------

@Composable
private fun PointsPhase(vm: SurveyViewModel, tracker: ArTracker?, pose: ArPose) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (tracker != null) CameraStrip(vm, tracker, pose, height = 150)
        PlanCanvas(vm, pose, modifier = Modifier.weight(1f))
        Readout(vm, pose)

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.markPointAr(pose) },
                    enabled = vm.positionOf(pose) != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Mark here") }
                OutlinedButton(
                    onClick = { vm.undoPoint() },
                    enabled = vm.draftPoints.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Undo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(
                    label = "Tap to add",
                    selected = vm.tool == PlanTool.ADD_POINT,
                    enabled = true,
                    onClick = {
                        vm.tool = if (vm.tool == PlanTool.ADD_POINT) PlanTool.NONE else PlanTool.ADD_POINT
                    },
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = "I'm here",
                    selected = vm.tool == PlanTool.REANCHOR,
                    enabled = vm.positionOf(pose) != null,
                    onClick = {
                        vm.tool = if (vm.tool == PlanTool.REANCHOR) PlanTool.NONE else PlanTool.REANCHOR
                    },
                    modifier = Modifier.weight(1f)
                )
                ToolButton(
                    label = if (vm.arFrame == null) "Set origin" else "Reset origin",
                    selected = false,
                    enabled = tracker != null && pose.tracking,
                    onClick = { vm.setOrigin(pose) },
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = { vm.finishLayout() },
                enabled = vm.draftPoints.isNotEmpty() && vm.draftCorners.size >= 3,
                modifier = Modifier.fillMaxWidth()
            ) { Text("All points plotted, start logging") }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

// --- shared pieces -----------------------------------------------------------

@Composable
private fun CameraStrip(vm: SurveyViewModel, tracker: ArTracker?, pose: ArPose, height: Int) {
    if (tracker == null) {
        Text(
            "AR is not available. Go back and enter the room size instead.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        return
    }
    ArPreview(tracker, modifier = Modifier.fillMaxWidth().height(height.dp))

    val status = when {
        tracker.startupProblem != null -> tracker.startupProblem!!
        vm.arFrameIsStale(pose) ->
            "AR restarted and lost the origin. Stand on corner (0, 0), point along the wall " +
                "on your left, and set the origin again."
        pose.tracking && vm.arFrame == null -> "Tracking. Set the origin to start."
        pose.tracking -> "Tracking"
        else -> pose.problem ?: "Starting up"
    }
    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = if (pose.tracking && !vm.arFrameIsStale(pose)) ORIGIN_COLOR else Color(0xFFB71C1C),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun Readout(vm: SurveyViewModel, pose: ArPose) {
    val here = vm.positionOf(pose)
    val manual = vm.draftPoints.count { it.source != SOURCE_AR }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            if (here == null) "Position unknown" else String.format("You are at %.2f m, %.2f m", here.x, here.y),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            vm.draftCorners.size.toString() + " corners, " + vm.draftPoints.size + " points (" +
                manual + " placed by hand). Long-press and drag to move any of them.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** A corner or point picked up by a long-press drag. */
private data class DragTarget(val corner: Boolean, val index: Int)

@Composable
private fun PlanCanvas(vm: SurveyViewModel, pose: ArPose, modifier: Modifier = Modifier) {
    val currentPose by rememberUpdatedState(pose)
    // Plain holders, not state: the draw pass records the view and gestures read it back.
    val lastView = remember { arrayOfNulls<PlanView>(1) }
    val frozenView = remember { arrayOfNulls<PlanView>(1) }
    val dragging = remember { arrayOfNulls<DragTarget>(1) }

    val corners = vm.draftCorners
    val points = vm.draftPoints
    val here = vm.positionOf(pose)
    val frame = vm.arFrame
    val world = corners + points.map { it.position } + listOfNotNull(here)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F6F7))
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val view = lastView[0] ?: return@detectTapGestures
                    when (vm.tool) {
                        PlanTool.ADD_POINT -> vm.addPointManually(view.world(tap))
                        PlanTool.REANCHOR -> {
                            val snapped = vm.draftCorners.firstOrNull {
                                hypot(view.sx(it.x) - tap.x, view.sy(it.y) - tap.y) <= GRAB_RADIUS_PX
                            }
                            vm.reanchor(currentPose, snapped ?: view.world(tap))
                        }
                        PlanTool.NONE -> Unit
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        val view = lastView[0] ?: return@detectDragGesturesAfterLongPress
                        dragging[0] = nearestTarget(vm, view, start)
                        if (dragging[0] != null) frozenView[0] = view
                    },
                    onDrag = { change, _ ->
                        val target = dragging[0]
                        val view = frozenView[0]
                        if (target != null && view != null) {
                            change.consume()
                            val moved = view.world(change.position)
                            if (target.corner) vm.moveCorner(target.index, moved)
                            else vm.movePoint(target.index, moved)
                        }
                    },
                    onDragEnd = {
                        dragging[0] = null
                        frozenView[0] = null
                    },
                    onDragCancel = {
                        dragging[0] = null
                        frozenView[0] = null
                    }
                )
            }
    ) {
        val view = frozenView[0] ?: fitPlan(world, size, padding = 32f)
        lastView[0] = view

        if (vm.layoutPhase == LayoutPhase.POINTS && corners.size >= 3) {
            drawOutline(view, corners, Color(0xFF263238), 3f)
        } else {
            for (i in 0 until corners.size - 1) {
                drawLine(
                    color = CORNER_COLOR,
                    start = view.screen(corners[i]),
                    end = view.screen(corners[i + 1]),
                    strokeWidth = 3f
                )
            }
        }

        corners.forEachIndexed { index, corner ->
            val centre = view.screen(corner)
            drawCircle(if (index == 0) ORIGIN_COLOR else CORNER_COLOR, radius = 9f, center = centre)
            drawCircle(Color.White, radius = 9f, center = centre, style = Stroke(2f))
        }

        for (p in points) {
            val centre = view.screen(p.position)
            val color = if (p.source == SOURCE_AR) AR_POINT_COLOR else MANUAL_POINT_COLOR
            drawCircle(color, radius = 10f, center = centre)
            drawCircle(Color(0xFF263238), radius = 10f, center = centre, style = Stroke(2f))
        }

        if (here != null && frame != null) {
            drawWalker(view, here, frame.headingOf(pose.lookX, pose.lookZ), WALKER_COLOR)
        }
    }
}

private fun nearestTarget(vm: SurveyViewModel, view: PlanView, at: Offset): DragTarget? {
    var best: DragTarget? = null
    var bestDistance = GRAB_RADIUS_PX
    vm.draftPoints.forEachIndexed { index, p ->
        val d = hypot(view.sx(p.position.x) - at.x, view.sy(p.position.y) - at.y)
        if (d <= bestDistance) {
            bestDistance = d
            best = DragTarget(corner = false, index = index)
        }
    }
    vm.draftCorners.forEachIndexed { index, c ->
        val d = hypot(view.sx(c.x) - at.x, view.sy(c.y) - at.y)
        if (d <= bestDistance) {
            bestDistance = d
            best = DragTarget(corner = true, index = index)
        }
    }
    return best
}

@Composable
private fun ClosureDialog(vm: SurveyViewModel) {
    val error = vm.closureErrorM
    AlertDialog(
        onDismissRequest = { vm.acknowledgeClosure() },
        title = { Text("Outline closed") },
        text = {
            Text(
                if (error == null) {
                    "AR was not tracking when you closed the outline, so the drift over the " +
                        "walk could not be measured. The corners are kept as marked."
                } else {
                    String.format(
                        "You should be back on the origin corner, and ARCore puts you %.2f m " +
                            "from it. That is the drift over the whole walk. The corners are " +
                            "kept exactly as marked, and the figure is stored with the survey.",
                        error
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.acknowledgeClosure() }) { Text("Continue") }
        }
    )
}
