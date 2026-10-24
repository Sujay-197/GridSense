package com.gridsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gridsense.core.SurveyMode
import com.gridsense.core.gridNodes
import com.gridsense.core.gridOutline

private const val MAX_CELLS_PER_SIDE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SurveyViewModel) {
    val rows = vm.draftRows.toIntOrNull()
    val cols = vm.draftCols.toIntOrNull()
    val cell = vm.draftCell.toDoubleOrNull()
    val samples = vm.draftSamples.toIntOrNull()
    val gridValid = rows != null && rows in 1..MAX_CELLS_PER_SIDE &&
        cols != null && cols in 1..MAX_CELLS_PER_SIDE &&
        cell != null && cell > 0
    val valid = gridValid && vm.draftName.isNotBlank() && samples != null && samples >= 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New survey") },
                navigationIcon = { TextButton(onClick = { vm.goHome() }) { Text("Cancel") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            Text("Room grid", style = MaterialTheme.typography.titleMedium)
            Text(
                "Split the room into a grid of equal cells. You will stand in the middle of " +
                    "each cell to take a reading. Columns go across the room from the corner " +
                    "you start in, rows go away from it. Cell A1 is that corner.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Rows", vm.draftRows, { vm.draftRows = it }, rows, Modifier.weight(1f))
                NumberField("Columns", vm.draftCols, { vm.draftCols = it }, cols, Modifier.weight(1f))
            }
            OutlinedTextField(
                value = vm.draftCell,
                onValueChange = { vm.draftCell = it },
                label = { Text("Cell size (m)") },
                supportingText = {
                    Text("A rough guess is fine, e.g. 1 m or two floor tiles. It only sets the map scale.")
                },
                singleLine = true,
                isError = cell == null || cell <= 0,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            if (gridValid) {
                Text(
                    (rows!! * cols!!).toString() + " nodes over " +
                        String.format("%.1f m x %.1f m", cols * cell!!, rows * cell),
                    style = MaterialTheme.typography.bodyMedium
                )
                GridPreview(rows, cols, cell)
            }

            Text("Primary radio", style = MaterialTheme.typography.titleMedium)
            Text(
                "Both Wi-Fi and cellular are recorded at every node. This sets which one the " +
                    "heatmap opens on and which ping target is used by default.",
                style = MaterialTheme.typography.bodySmall
            )
            SurveyMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = vm.draftMode == mode, onClick = { vm.draftMode = mode })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = vm.draftMode == mode, onClick = { vm.draftMode = mode })
                    Text(mode.label)
                }
            }

            NumberField("Samples per node", vm.draftSamples, { vm.draftSamples = it }, samples, Modifier.fillMaxWidth())

            OutlinedTextField(
                value = vm.draftPingHost,
                onValueChange = { vm.draftPingHost = it },
                label = { Text("Ping target (optional)") },
                placeholder = { Text("Gateway on Wi-Fi, 8.8.8.8 on cellular") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.createGridSurvey() },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create grid and start") }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    parsed: Int?,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null || parsed < 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun GridPreview(rows: Int, cols: Int, cell: Double) {
    val outline = gridOutline(rows, cols, cell)
    val nodes = gridNodes(rows, cols, cell)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFFF4F6F7))
    ) {
        val view = fitPlan(outline, size, padding = 20f)
        drawGridCells(view, rows, cols, cell)
        drawOutline(view, outline, Color(0xFF263238), 3f)
        for (n in nodes) drawCircle(Color(0xFF546E7A), radius = 6f, center = view.screen(n.x, n.y))
        drawCircle(Color(0xFF2E7D32), radius = 9f, center = view.screen(nodes[0].x, nodes[0].y))
    }
}
