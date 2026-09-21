package com.gridsense.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.core.SurveyMode
import com.gridsense.core.distance
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
import com.gridsense.pdr.PdrTracker
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
    data object Layout : Screen
    data class Survey(val roomId: Long) : Screen
}

/** The three steps of laying a survey out: settings, walk the walls, then mark the points. */
enum class LayoutPhase { SETTINGS, OUTLINE, POINTS }

private const val PING_COUNT = 10
private const val POLL_INTERVAL_MS = 500L
private const val EXPECTED_RUN_MS = 10_000L

@Suppress("OPT_IN_USAGE")
class SurveyViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()
    private val scanner = NeighbourScanner(app)

    val tracker = PdrTracker(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var readiness by mutableStateOf(readReadiness(app))
        private set

    var rationaleDismissed by mutableStateOf(false)
        private set

    var note by mutableStateOf<String?>(null)

    // --- layout draft ------------------------------------------------------

    var layoutPhase by mutableStateOf(LayoutPhase.SETTINGS)
        private set

    var draftName by mutableStateOf("")
    var draftMode by mutableStateOf(SurveyMode.WIFI)
    var draftPingHost by mutableStateOf("")
    var draftSamples by mutableStateOf("10")

    var draftCorners by mutableStateOf<List<Pt>>(emptyList())
        private set

    var draftPoints by mutableStateOf<List<Pt>>(emptyList())
        private set

    /** Drift measured when the perimeter walk returned to the origin corner. */
    var closureErrorM by mutableStateOf(0.0)
        private set

    var closurePromptOpen by mutableStateOf(false)
        private set

    var calibrationPromptOpen by mutableStateOf(false)
        private set

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

    override fun onCleared() {
        tracker.stop()
        super.onCleared()
    }

    // --- navigation --------------------------------------------------------

    fun refreshReadiness() {
        readiness = readReadiness(getApplication())
    }

    fun dismissRationale() {
        rationaleDismissed = true
    }

    fun goHome() {
        tracker.stop()
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

    // --- layout ------------------------------------------------------------

    fun startLayout() {
        draftName = ""
        draftMode = SurveyMode.WIFI
        draftPingHost = ""
        draftSamples = "10"
        draftCorners = emptyList()
        draftPoints = emptyList()
        closureErrorM = 0.0
        layoutPhase = LayoutPhase.SETTINGS
        note = null
        screen = Screen.Layout
        tracker.start()
    }

    fun cancelLayout() {
        goHome()
    }

    fun openCalibration() {
        calibrationPromptOpen = true
        tracker.startCalibration()
    }

    fun cancelCalibration() {
        tracker.finishCalibration(0.0)
        calibrationPromptOpen = false
    }

    fun finishCalibration(distanceM: Double) {
        val stride = tracker.finishCalibration(distanceM)
        calibrationPromptOpen = false
        note = if (stride == null) {
            "Calibration needs at least one detected step and a distance above zero."
        } else {
            String.format("Stride set to %.2f m from %d steps.", stride, tracker.calibrationSteps)
        }
    }

    /** You are standing on the corner you chose as the origin, facing into the room. */
    fun beginOutlineWalk() {
        if (!tracker.start()) {
            note = "This phone has no step detector, or the activity recognition permission " +
                "is missing, so the walk cannot be tracked."
            return
        }
        tracker.setOrigin()
        draftCorners = listOf(Pt(0.0, 0.0))
        draftPoints = emptyList()
        layoutPhase = LayoutPhase.OUTLINE
        note = "Walk the walls. Mark a corner every time you reach one, then return here."
    }

    fun markCorner() {
        draftCorners = draftCorners + tracker.position()
    }

    fun undoCorner() {
        if (draftCorners.size > 1) draftCorners = draftCorners.dropLast(1)
    }

    /** Called when you have walked the whole perimeter and are back on the origin corner. */
    fun closeOutline() {
        if (draftCorners.size < 3) {
            note = "An outline needs at least three corners."
            return
        }
        closureErrorM = distance(Pt(0.0, 0.0), tracker.position())
        closurePromptOpen = true
    }

    fun acknowledgeClosure() {
        closurePromptOpen = false
        // You are standing on the origin, so put the tracker back there.
        tracker.anchorTo(Pt(0.0, 0.0))
        layoutPhase = LayoutPhase.POINTS
        note = "Now walk to each survey position and mark it. Re-anchor at a corner if the " +
            "marker drifts away from where you are."
    }

    fun markPoint() {
        draftPoints = draftPoints + tracker.position()
    }

    fun undoPoint() {
        if (draftPoints.isNotEmpty()) draftPoints = draftPoints.dropLast(1)
    }

    fun anchorToCorner(index: Int) {
        val corner = draftCorners.getOrNull(index) ?: return
        tracker.anchorTo(corner)
        note = String.format("Re-anchored to corner %d at %.2f m, %.2f m.", index, corner.x, corner.y)
    }

    fun finishLayout() {
        val samplesPerPoint = draftSamples.toIntOrNull() ?: return
        val corners = draftCorners
        val positions = draftPoints
        val host = draftPingHost.trim().ifBlank { null }
        val mode = draftMode
        val stride = tracker.stepLengthM
        val name = draftName.trim()
        val closure = closureErrorM
        viewModelScope.launch {
            val id = dao.insertRoom(
                SurveyRoom(
                    name = name,
                    mode = mode.name,
                    stepLengthM = stride,
                    samplesPerPoint = samplesPerPoint,
                    pingHost = host,
                    polygon = encodePolygon(corners),
                    closureErrorM = closure,
                    createdAt = System.currentTimeMillis()
                )
            )
            dao.insertPoints(
                positions.mapIndexed { index, p ->
                    GridPoint(
                        roomId = id,
                        seq = index,
                        x = p.x,
                        y = p.y,
                        enabled = true,
                        status = STATUS_PENDING
                    )
                }
            )
            tracker.stop()
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
