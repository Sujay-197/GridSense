package com.gridsense.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gridsense.core.Metric
import com.gridsense.core.SurveyMode
import com.gridsense.core.gridNodes
import com.gridsense.core.gridOutline
import com.gridsense.data.Db
import com.gridsense.data.GridPoint
import com.gridsense.data.KIND_CELL
import com.gridsense.data.KIND_LINK
import com.gridsense.data.KIND_PING
import com.gridsense.data.KIND_SCAN
import com.gridsense.data.KIND_SCAN_CACHED
import com.gridsense.data.Router
import com.gridsense.data.STATUS_DONE
import com.gridsense.data.STATUS_LOGGING
import com.gridsense.data.STATUS_PENDING
import com.gridsense.data.Sample
import com.gridsense.data.SurveyRoom
import com.gridsense.data.encodePolygon
import com.gridsense.net.Neighbour
import com.gridsense.net.NeighbourScanner
import com.gridsense.net.ping
import com.gridsense.net.pingTarget
import com.gridsense.net.readCell
import com.gridsense.net.readLink
import com.gridsense.net.readReadiness
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Setup : Screen
    data class Survey(val roomId: Long) : Screen
}

private const val PING_COUNT = 10
private const val POLL_INTERVAL_MS = 500L
private const val EXPECTED_RUN_MS = 10_000L

@Suppress("OPT_IN_USAGE")
class SurveyViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()
    private val scanner = NeighbourScanner(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var readiness by mutableStateOf(readReadiness(app))
        private set

    var rationaleDismissed by mutableStateOf(false)
        private set

    var note by mutableStateOf<String?>(null)

    // --- setup draft -------------------------------------------------------

    var draftName by mutableStateOf("")
    var draftMode by mutableStateOf(SurveyMode.WIFI)
    var draftPingHost by mutableStateOf("")
    var draftSamples by mutableStateOf("10")
    var draftRows by mutableStateOf("4")
    var draftCols by mutableStateOf("4")
    var draftCell by mutableStateOf("1.0")

    // --- logging -----------------------------------------------------------

    var loggingPointId by mutableStateOf<Long?>(null)
        private set

    var loggingProgress by mutableStateOf(0f)
        private set

    private var loggingJob: Job? = null

    // --- router placement --------------------------------------------------

    var placingRouter by mutableStateOf(false)
        private set

    var pendingRouterX by mutableStateOf(0.0)
        private set

    var pendingRouterY by mutableStateOf(0.0)
        private set

    var routerDialogOpen by mutableStateOf(false)
        private set

    var scanChoices by mutableStateOf<List<Neighbour>>(emptyList())
        private set

    var scanChoicesLoading by mutableStateOf(false)
        private set

    // --- heatmap options ---------------------------------------------------

    var metric by mutableStateOf(Metric.RSSI)
    var bssidFilter by mutableStateOf<String?>(null)
    var showRouters by mutableStateOf(true)

    // --- data streams ------------------------------------------------------

    private val roomId = MutableStateFlow<Long?>(null)

    val rooms = dao.rooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val room = roomId
        .flatMapLatest { id -> if (id == null) flowOf(null) else dao.room(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val points = roomId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.points(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val samples = roomId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.samples(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routers = roomId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.routers(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- navigation --------------------------------------------------------

    fun refreshReadiness() {
        readiness = readReadiness(getApplication())
    }

    fun dismissRationale() {
        rationaleDismissed = true
    }

    fun goHome() {
        screen = Screen.Home
        roomId.value = null
    }

    fun openRoom(id: Long) {
        roomId.value = id
        screen = Screen.Survey(id)
        bssidFilter = null
        viewModelScope.launch {
            dao.resetStatus(id, STATUS_LOGGING, STATUS_PENDING)
            val opened = dao.roomNow(id)
            if (opened != null) metric = modeOf(opened).defaultMetric
        }
    }

    fun deleteRoom(id: Long) {
        viewModelScope.launch { dao.deleteRoom(id) }
    }

    // --- setup -------------------------------------------------------------

    fun startSetup() {
        draftName = ""
        draftMode = SurveyMode.WIFI
        draftPingHost = ""
        draftSamples = "10"
        draftRows = "4"
        draftCols = "4"
        draftCell = "1.0"
        note = null
        screen = Screen.Setup
    }

    /** Saves the survey with one pending node at the centre of every grid cell. */
    fun createGridSurvey() {
        val rows = draftRows.toIntOrNull() ?: return
        val cols = draftCols.toIntOrNull() ?: return
        val cell = draftCell.toDoubleOrNull() ?: return
        val samplesPerPoint = draftSamples.toIntOrNull() ?: return
        val name = draftName.trim()
        val mode = draftMode
        val host = draftPingHost.trim().ifBlank { null }
        viewModelScope.launch {
            val id = dao.insertRoom(
                SurveyRoom(
                    name = name,
                    mode = mode.name,
                    samplesPerPoint = samplesPerPoint,
                    pingHost = host,
                    polygon = encodePolygon(gridOutline(rows, cols, cell)),
                    gridRows = rows,
                    gridCols = cols,
                    cellSizeM = cell,
                    createdAt = System.currentTimeMillis()
                )
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
            openRoom(id)
        }
    }

    fun setPointEnabled(point: GridPoint, enabled: Boolean) {
        viewModelScope.launch { dao.updatePoint(point.copy(enabled = enabled)) }
    }

    // --- logging -----------------------------------------------------------

    fun startLogging(point: GridPoint) {
        if (loggingJob?.isActive == true) return
        val currentRoom = room.value ?: return
        refreshReadiness()
        if (!readiness.canLog) {
            note = "Neither the Wi-Fi identifiers nor the serving cell are readable, so " +
                "logging is blocked."
            return
        }
        loggingJob = viewModelScope.launch {
            val app = getApplication<Application>()
            loggingPointId = point.id
            loggingProgress = 0f
            note = null
            dao.deleteSamplesOf(point.id)
            dao.setStatus(point.id, STATUS_LOGGING)

            val host = pingTarget(app, currentRoom.pingHost)

            val pollJob = async {
                val rows = ArrayList<Sample>()
                repeat(currentRoom.samplesPerPoint) {
                    val now = System.currentTimeMillis()
                    readLink(app)?.let { reading ->
                        rows += Sample(
                            pointId = point.id,
                            ts = now,
                            kind = KIND_LINK,
                            bssid = reading.bssid,
                            ssid = reading.ssid,
                            freqMhz = reading.freqMhz,
                            rssiDbm = reading.rssiDbm,
                            linkSpeedMbps = reading.linkSpeedMbps
                        )
                    }
                    readCell(app)?.let { cell ->
                        rows += Sample(
                            pointId = point.id,
                            ts = now,
                            kind = KIND_CELL,
                            cellTech = cell.tech,
                            cellDbm = cell.dbm,
                            cellLevel = cell.level,
                            rsrp = cell.rsrp,
                            rsrq = cell.rsrq,
                            sinr = cell.sinr,
                            cellId = cell.cellId,
                            tac = cell.tac,
                            pci = cell.pci,
                            arfcn = cell.arfcn,
                            operator = cell.operator
                        )
                    }
                    delay(POLL_INTERVAL_MS)
                }
                rows
            }
            val pingJob = async { if (host == null) null else ping(host, PING_COUNT) }
            val scanJob = async { scanner.scanOnce() }

            val ticker = launch {
                val startedAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt).toFloat()
                    loggingProgress = (elapsed / EXPECTED_RUN_MS).coerceIn(0f, 0.97f)
                    delay(50)
                }
            }

            val polled = pollJob.await()
            val pingResult = pingJob.await()
            val scan = scanJob.await()
            ticker.cancel()
            loggingProgress = 1f

            dao.insertSamples(polled)

            val lastLink = polled.lastOrNull { it.kind == KIND_LINK }
            dao.insertSample(
                Sample(
                    pointId = point.id,
                    ts = System.currentTimeMillis(),
                    kind = KIND_PING,
                    bssid = lastLink?.bssid,
                    ssid = lastLink?.ssid,
                    pingHost = host,
                    rttAvgMs = pingResult?.avgMs,
                    rttJitterMs = pingResult?.jitterMs,
                    rttLossPct = pingResult?.lossPct
                )
            )

            val scanKind = if (scan.fresh) KIND_SCAN else KIND_SCAN_CACHED
            dao.insertSamples(
                scan.neighbours.map {
                    Sample(
                        pointId = point.id,
                        ts = System.currentTimeMillis(),
                        kind = scanKind,
                        bssid = it.bssid,
                        ssid = it.ssid,
                        freqMhz = it.freqMhz,
                        rssiDbm = it.rssiDbm
                    )
                }
            )

            dao.setStatus(point.id, STATUS_DONE)
            note = if (host == null) {
                "No ping target could be chosen, so latency, jitter and loss were not " +
                    "measured. " + scan.note
            } else {
                "Pinged " + host + ". " + scan.note
            }
            loggingPointId = null
            loggingProgress = 0f
        }
    }

    // --- routers -----------------------------------------------------------

    fun beginRouterPlacement() {
        placingRouter = true
        note = "Tap anywhere on the plan to drop the router marker."
    }

    fun cancelRouterPlacement() {
        placingRouter = false
        routerDialogOpen = false
    }

    fun placeRouterAt(x: Double, y: Double) {
        pendingRouterX = x
        pendingRouterY = y
        placingRouter = false
        routerDialogOpen = true
        note = null
        refreshScanChoices()
    }

    fun refreshScanChoices() {
        viewModelScope.launch {
            scanChoicesLoading = true
            val outcome = scanner.scanOnce()
            scanChoices = outcome.neighbours.sortedByDescending { it.rssiDbm }
            note = outcome.note
            scanChoicesLoading = false
        }
    }

    fun confirmRouter(bssid: String, label: String) {
        val id = roomId.value ?: return
        viewModelScope.launch {
            dao.insertRouter(
                Router(
                    roomId = id,
                    x = pendingRouterX,
                    y = pendingRouterY,
                    bssid = bssid,
                    label = label
                )
            )
            routerDialogOpen = false
        }
    }

    fun deleteRouter(routerId: Long) {
        viewModelScope.launch { dao.deleteRouter(routerId) }
    }
}

fun modeOf(room: SurveyRoom): SurveyMode =
    if (room.mode == SurveyMode.CELLULAR.name) SurveyMode.CELLULAR else SurveyMode.WIFI
