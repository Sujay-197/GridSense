package com.gridsense.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gridsense.core.SurveyMode
import com.gridsense.core.gridNodes
import com.gridsense.core.gridOutline
import com.gridsense.data.DEFAULT_DNS_NAME
import com.gridsense.data.DEFAULT_DOWNLOAD_URL
import com.gridsense.data.DEFAULT_TCP_HOST
import com.gridsense.data.DEFAULT_TCP_PORT
import com.gridsense.data.Db
import com.gridsense.data.GridPoint
import com.gridsense.data.STATUS_PENDING
import com.gridsense.data.SurveyRoom
import com.gridsense.data.encodePolygon
import kotlinx.coroutines.launch

const val MAX_CELLS_PER_SIDE = 20

/** "Bedroom" becomes "Bedroom (2)", or the next number not already taken. */
fun duplicateName(name: String, taken: Collection<String>): String {
    val base = name.replace(Regex("""\s\(\d+\)$"""), "")
    var i = 2
    while ("$base ($i)" in taken) i++
    return "$base ($i)"
}

/** Everything the setup form edits, as typed. */
data class SetupForm(
    val name: String = "",
    val condition: String = "",
    val mode: SurveyMode = SurveyMode.WIFI,
    val pingHost: String = "",
    val samples: String = "10",
    val rows: String = "4",
    val cols: String = "4",
    val cell: String = "1.0",
    val extendedTests: Boolean = true,
    val loadTest: Boolean = false,
    val dnsName: String = DEFAULT_DNS_NAME,
    val tcpHost: String = DEFAULT_TCP_HOST,
    val tcpPort: String = DEFAULT_TCP_PORT.toString(),
    val downloadUrl: String = DEFAULT_DOWNLOAD_URL
) {
    val tcpPortValue get() = tcpPort.toIntOrNull()

    val dnsNameError get() = if (extendedTests && dnsName.isBlank()) "Enter a name to look up" else null
    val tcpHostError get() = if (extendedTests && tcpHost.isBlank()) "Enter a host" else null
    val tcpPortError get() = if (extendedTests && tcpPortValue !in 1..65535) "Use 1 to 65535" else null
    val downloadUrlError
        get() = if (loadTest && !downloadUrl.trim().let { it.startsWith("https://") || it.startsWith("http://") })
            "Enter an http or https URL" else null

    val rowsValue get() = rows.toIntOrNull()
    val colsValue get() = cols.toIntOrNull()
    val cellValue get() = cell.toDoubleOrNull()
    val samplesValue get() = samples.toIntOrNull()

    val nameError get() = if (name.isBlank()) "Give the survey a name" else null
    val rowsError get() = sideError(rowsValue)
    val colsError get() = sideError(colsValue)
    val cellError get() = cellValue.let { if (it == null || it <= 0) "Enter a size above 0 m" else null }
    val samplesError get() = samplesValue.let { if (it == null || it < 1) "At least 1 sample" else null }

    val gridValid get() = rowsError == null && colsError == null && cellError == null

    /** The first problem, shown next to the disabled Create button. */
    val blocker: String?
        get() = nameError ?: rowsError?.let { "Rows: $it" } ?: colsError?.let { "Columns: $it" }
            ?: cellError?.let { "Cell size: $it" } ?: samplesError?.let { "Samples: $it" }
            ?: dnsNameError?.let { "DNS name: $it" } ?: tcpHostError?.let { "TCP host: $it" }
            ?: tcpPortError?.let { "TCP port: $it" } ?: downloadUrlError?.let { "Download URL: $it" }

    private fun sideError(v: Int?) =
        if (v == null || v !in 1..MAX_CELLS_PER_SIDE) "Use 1 to $MAX_CELLS_PER_SIDE" else null
}

/** Copies the form's latency-test settings onto a survey. Only call with a valid form. */
fun SurveyRoom.withTests(f: SetupForm) = copy(
    extendedTests = f.extendedTests,
    loadTest = f.loadTest,
    dnsName = f.dnsName.trim().ifBlank { DEFAULT_DNS_NAME },
    tcpHost = f.tcpHost.trim().ifBlank { DEFAULT_TCP_HOST },
    tcpPort = f.tcpPortValue ?: DEFAULT_TCP_PORT,
    downloadUrl = f.downloadUrl.trim().ifBlank { DEFAULT_DOWNLOAD_URL }
)

/**
 * The setup destination's own state. With an `editId` argument it edits that survey's details;
 * the grid is fixed once nodes exist, so only the survey and radio fields change. With `copyOf`
 * it starts a new survey on the same grid and settings, so the two can be compared node by node.
 */
class SetupViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()

    val editId: Long? = saved.get<Long>("editId")?.takeIf { it > 0 }
    val editing get() = editId != null

    val copyOf: Long? = saved.get<Long>("copyOf")?.takeIf { it > 0 }
    val duplicating get() = copyOf != null

    /** The grid can only be typed in for a brand-new survey. */
    val gridLocked get() = editing || duplicating

    var form by mutableStateOf(SetupForm())
    private var initial = SetupForm()

    var loaded by mutableStateOf(editId == null && copyOf == null)
        private set

    val dirty get() = form != initial

    private var editedRoom: SurveyRoom? = null

    init {
        val sourceId = editId ?: copyOf
        if (sourceId != null) {
            viewModelScope.launch {
                val r = dao.roomNow(sourceId)
                if (r != null) {
                    if (editing) editedRoom = r
                    val names = dao.roomNames()
                    val f = SetupForm(
                        name = if (editing) r.name else duplicateName(r.name, names),
                        condition = if (editing) r.condition else "",
                        mode = modeOf(r),
                        pingHost = r.pingHost.orEmpty(),
                        samples = r.samplesPerPoint.toString(),
                        rows = r.gridRows.toString(),
                        cols = r.gridCols.toString(),
                        cell = r.cellSizeM.toString(),
                        extendedTests = r.extendedTests,
                        loadTest = r.loadTest,
                        dnsName = r.dnsName,
                        tcpHost = r.tcpHost,
                        tcpPort = r.tcpPort.toString(),
                        downloadUrl = r.downloadUrl
                    )
                    // A duplicate is new: leaving it unsaved should still ask first.
                    initial = if (editing) f else SetupForm()
                    form = f
                }
                loaded = true
            }
        }
    }

    fun update(change: SetupForm.() -> SetupForm) {
        form = form.change()
    }

    /** Saves, then reports the survey id. Does nothing while the form has a blocker. */
    fun save(onSaved: (Long) -> Unit) {
        val f = form
        if (f.blocker != null) return
        val existing = editedRoom
        viewModelScope.launch {
            if (existing != null) {
                dao.updateRoom(
                    existing.copy(
                        name = f.name.trim(),
                        condition = f.condition.trim(),
                        mode = f.mode.name,
                        samplesPerPoint = f.samplesValue!!,
                        pingHost = f.pingHost.trim().ifBlank { null }
                    ).withTests(f)
                )
                initial = f
                onSaved(existing.id)
            } else {
                onSaved(create(f))
            }
        }
    }

    /** Saves the survey with one pending node at the centre of every grid cell. */
    private suspend fun create(f: SetupForm): Long {
        val rows = f.rowsValue!!
        val cols = f.colsValue!!
        val cell = f.cellValue!!
        val id = dao.insertRoom(
            SurveyRoom(
                name = f.name.trim(),
                condition = f.condition.trim(),
                mode = f.mode.name,
                samplesPerPoint = f.samplesValue!!,
                pingHost = f.pingHost.trim().ifBlank { null },
                polygon = encodePolygon(gridOutline(rows, cols, cell)),
                gridRows = rows,
                gridCols = cols,
                cellSizeM = cell,
                createdAt = System.currentTimeMillis()
            ).withTests(f)
        )
        dao.insertPoints(
            gridNodes(rows, cols, cell).mapIndexed { index, node ->
                GridPoint(
                    roomId = id,
                    seq = index,
                    row = node.row,
                    col = node.col,
                    x = node.x,
                    y = node.y,
                    enabled = true,
                    status = STATUS_PENDING
                )
            }
        )
        initial = f
        return id
    }
}
