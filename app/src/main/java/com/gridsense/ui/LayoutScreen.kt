package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gridsense.core.Pt
import com.gridsense.core.SurveyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutScreen(vm: SurveyViewModel) {
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
                LayoutPhase.SETTINGS -> SettingsPhase(vm)
                LayoutPhase.OUTLINE -> OutlinePhase(vm)
                LayoutPhase.POINTS -> PointsPhase(vm)
            }
        }
    }

    if (vm.calibrationPromptOpen) CalibrationDialog(vm)
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

@Composable
private fun SettingsPhase(vm: SurveyViewModel) {
    val samples = vm.draftSamples.toIntOrNull()
    val ready = vm.readiness

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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Stride", style = MaterialTheme.typography.titleMedium)
                Text(
                    String.format("Currently %.2f m per step.", vm.tracker.stepLengthM),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Distance comes from counting your steps, so the stride has to be right. " +
                        "Walk a measured distance, for example a corridor you can pace out, " +
                        "and tell the app how long it was.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { vm.openCalibration() },
                    enabled = ready.canWalk,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Calibrate stride") }
            }
        }

        if (!ready.canWalk) {
            Text(
                "Dead reckoning is unavailable: this needs the physical activity permission " +
                    "as well as a step detector and a rotation vector sensor on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB71C1C)
            )
        }

        Text(
            "Stand in the corner you want as the origin, face into the room, and start. " +
                "That corner becomes (0, 0) and the direction you are facing becomes the " +
                "+y axis of the plan.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { vm.beginOutlineWalk() },
            enabled = ready.canWalk && vm.draftName.isNotBlank() && samples != null && samples >= 1,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start at this corner") }
    }
}

@Composable
private fun OutlinePhase(vm: SurveyViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        WalkCanvas(vm, modifier = Modifier.weight(1f))
        Readout(vm)
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { vm.markCorner() }, modifier = Modifier.weight(1f)) {
                Text("Mark corner")
            }
            OutlinedButton(
                onClick = { vm.undoCorner() },
                enabled = vm.draftCorners.size > 1,
                modifier = Modifier.weight(1f)
            ) { Text("Undo") }
        }
        Button(
            onClick = { vm.closeOutline() },
            enabled = vm.draftCorners.size >= 3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
        ) { Text("Back at the start, close the outline") }
    }
}

@Composable
private fun PointsPhase(vm: SurveyViewModel) {
    var anchorMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        WalkCanvas(vm, modifier = Modifier.weight(1f))
        Readout(vm)
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { vm.markPoint() }, modifier = Modifier.weight(1f)) {
                Text("Mark point")
            }
            OutlinedButton(
                onClick = { vm.undoPoint() },
                enabled = vm.draftPoints.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Undo") }
            Box {
                OutlinedButton(onClick = { anchorMenuOpen = true }) { Text("Re-anchor") }
                DropdownMenu(
                    expanded = anchorMenuOpen,
                    onDismissRequest = { anchorMenuOpen = false }
                ) {
                    vm.draftCorners.forEachIndexed { index, corner ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (index == 0) {
                                        "Origin corner"
                                    } else {
                                        String.format(
                                            "Corner %d  (%.1f, %.1f)",
                                            index,
                                            corner.x,
                                            corner.y
                                        )
                                    }
                                )
                            },
                            onClick = {
                                vm.anchorToCorner(index)
                                anchorMenuOpen = false
                            }
                        )
                    }
                }
            }
        }
        Button(
            onClick = { vm.finishLayout() },
            enabled = vm.draftPoints.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
        ) { Text("All points plotted, start logging") }
    }
}

@Composable
private fun Readout(vm: SurveyViewModel) {
    val tracker = vm.tracker
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            String.format("Position %.2f m, %.2f m", tracker.x, tracker.y),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            tracker.steps.toString() + " steps at " +
                String.format("%.2f", tracker.stepLengthM) + " m   -   " +
                vm.draftCorners.size + " corners, " + vm.draftPoints.size + " points",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun WalkCanvas(vm: SurveyViewModel, modifier: Modifier = Modifier) {
    val tracker = vm.tracker
    val corners = vm.draftCorners
    val points = vm.draftPoints
    val trace = tracker.trace
    val here = Pt(tracker.x, tracker.y)
    val world = corners + points + trace + here

    Canvas(modifier = modifier.fillMaxWidth().background(Color(0xFFF4F6F7))) {
        val view = fitPlan(world, size, padding = 32f)

        drawTrace(view, trace, Color(0x33607D8B))

        if (vm.layoutPhase == LayoutPhase.POINTS && corners.size >= 3) {
            drawOutline(view, corners, Color(0xFF263238), 3f)
        } else if (corners.size >= 2) {
            for (i in 0 until corners.size - 1) {
                drawLine(
                    color = Color(0xFF455A64),
                    start = view.screen(corners[i]),
                    end = view.screen(corners[i + 1]),
                    strokeWidth = 3f
                )
            }
        }

        corners.forEachIndexed { index, corner ->
            val centre = view.screen(corner)
            drawCircle(
                color = if (index == 0) Color(0xFF2E7D32) else Color(0xFF455A64),
                radius = 9f,
                center = centre
            )
            drawCircle(Color.White, radius = 9f, center = centre, style = Stroke(2f))
        }

        for (p in points) {
            drawCircle(Color(0xFF9E9E9E), radius = 10f, center = view.screen(p))
            drawCircle(Color(0xFF37474F), radius = 10f, center = view.screen(p), style = Stroke(2f))
        }

        drawWalker(view, here, tracker.azimuth - tracker.reference, Color(0xFF1976D2))
    }
}

@Composable
private fun CalibrationDialog(vm: SurveyViewModel) {
    var distanceText by remember { mutableStateOf("") }
    val distance = distanceText.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = { vm.cancelCalibration() },
        title = { Text("Calibrate stride") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Walk a straight line you have measured, then enter its length. " +
                        "Counting continues while this dialog is open.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    vm.tracker.calibrationSteps.toString() + " steps counted",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = distanceText,
                    onValueChange = { distanceText = it },
                    label = { Text("Distance walked (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.finishCalibration(distance ?: 0.0) },
                enabled = distance != null && distance > 0 && vm.tracker.calibrationSteps > 0
            ) { Text("Set stride") }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelCalibration() }) { Text("Cancel") }
        }
    )
}

@Composable
private fun ClosureDialog(vm: SurveyViewModel) {
    AlertDialog(
        onDismissRequest = { vm.acknowledgeClosure() },
        title = { Text("Outline closed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    String.format(
                        "You should be back on the origin corner, but dead reckoning puts you " +
                            "%.2f m away from it. That gap is the drift accumulated over the " +
                            "whole perimeter walk.",
                        vm.closureErrorM
                    )
                )
                Text(
                    "The corners are kept exactly as they were measured. The figure is stored " +
                        "with the survey and appears in the JSON export, so you can quote it."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.acknowledgeClosure() }) { Text("Continue") }
        }
    )
}
