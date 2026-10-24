package com.gridsense.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gridsense.core.SurveyMode
import com.gridsense.core.gridNodes
import com.gridsense.core.gridOutline
import com.gridsense.ui.components.ConfirmDialog
import com.gridsense.ui.components.ScreenScaffold
import com.gridsense.ui.components.SectionCard
import com.gridsense.ui.theme.GridSenseTheme
import com.gridsense.ui.theme.Spacing

@Composable
fun SetupScreen(vm: SetupViewModel, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val form = vm.form
    val focus = LocalFocusManager.current
    var confirmDiscard by remember { mutableStateOf(false) }

    val leave = { if (vm.dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = vm.dirty) { confirmDiscard = true }

    ScreenScaffold(
        title = if (vm.editing) "Edit details" else "New survey",
        onBack = leave
    ) { padding ->
        if (!vm.loaded) return@ScreenScaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            SectionCard(title = "Survey", icon = Icons.AutoMirrored.Filled.Label) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> vm.update { copy(name = v) } },
                    label = { Text("Survey name") },
                    singleLine = true,
                    isError = form.nameError != null && form.name.isNotEmpty(),
                    supportingText = form.nameError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard(title = "Grid", icon = Icons.Filled.GridOn) {
                if (vm.editing) {
                    Text(
                        form.rows + " rows x " + form.cols + " columns, " + form.cell +
                            " m cells. The grid can't change once nodes exist.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "Split the room into a grid of equal cells. You will stand in the middle " +
                            "of each cell to take a reading. Columns go across the room from the " +
                            "corner you start in, rows go away from it. Cell A1 is that corner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        NumberField(
                            "Rows", form.rows, { v -> vm.update { copy(rows = v) } },
                            form.rowsError, Modifier.weight(1f)
                        )
                        NumberField(
                            "Columns", form.cols, { v -> vm.update { copy(cols = v) } },
                            form.colsError, Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = form.cell,
                        onValueChange = { v -> vm.update { copy(cell = v) } },
                        label = { Text("Cell size (m)") },
                        supportingText = {
                            Text(form.cellError ?: "A rough guess is fine, e.g. 1 m or two floor tiles. It only sets the map scale.")
                        },
                        singleLine = true,
                        isError = form.cellError != null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (form.gridValid) {
                    val rows = form.rowsValue!!
                    val cols = form.colsValue!!
                    val cell = form.cellValue!!
                    Text(
                        (rows * cols).toString() + " nodes over " +
                            String.format("%.1f m x %.1f m", cols * cell, rows * cell),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    GridPreview(rows, cols, cell)
                }
            }

            SectionCard(title = "Radios and tests", icon = Icons.Filled.SettingsInputAntenna) {
                Text(
                    "Both Wi-Fi and cellular are recorded at every node. The primary radio sets " +
                        "which one the heatmap opens on and which ping target is used by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.selectableGroup()) {
                    SurveyMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = form.mode == mode,
                                    onClick = { vm.update { copy(mode = mode) } },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = form.mode == mode, onClick = null)
                            Text(mode.label, modifier = Modifier.padding(start = Spacing.s))
                        }
                    }
                }
                NumberField(
                    "Samples per node", form.samples, { v -> vm.update { copy(samples = v) } },
                    form.samplesError, Modifier.fillMaxWidth(),
                    hint = "Polled every 500 ms, so 10 samples take about 5 s."
                )
                OutlinedTextField(
                    value = form.pingHost,
                    onValueChange = { v -> vm.update { copy(pingHost = v) } },
                    label = { Text("Ping target (optional)") },
                    placeholder = { Text("Gateway on Wi-Fi, 8.8.8.8 on cellular") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val blocker = form.blocker
            Button(
                onClick = { vm.save(onSaved) },
                enabled = blocker == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (vm.editing) "Save" else "Create grid and start") }
            if (blocker != null) {
                Text(
                    blocker,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = if (vm.editing) "Your edits to this survey's details will be lost."
            else "The survey has not been created yet. Leaving now discards the form.",
            confirmLabel = "Discard",
            destructive = true,
            dismissLabel = "Keep editing",
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    modifier: Modifier,
    hint: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier
    )
}

@Composable
private fun GridPreview(rows: Int, cols: Int, cell: Double) {
    val outline = gridOutline(rows, cols, cell)
    val nodes = gridNodes(rows, cols, cell)
    val colors = GridSenseTheme.plan
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            drawRect(colors.background)
            val view = fitPlan(outline, size, padding = 20f)
            drawGridCells(view, rows, cols, cell, colors.gridLine)
            drawOutline(view, outline, colors.outline, 3f)
            for (n in nodes) drawCircle(colors.pending, radius = 6f, center = view.screen(n.x, n.y))
            drawCircle(colors.start, radius = 9f, center = view.screen(nodes[0].x, nodes[0].y))
        }
    }
}
