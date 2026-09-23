package com.gridsense.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gridsense.ar.ArPose
import com.gridsense.ar.ArStatus
import com.gridsense.ar.checkArStatus
import com.gridsense.core.Metric
import com.gridsense.core.Pt
import com.gridsense.core.RoomFrame
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
import com.gridsense.data.SOURCE_AR
import com.gridsense.data.SOURCE_MANUAL
import com.gridsense.data.SOURCE_MIXED
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
    data object Layout : Screen
    data class Survey(val roomId: Long) : Screen
}

/** The steps of laying a survey out: settings, the AR outline walk, then marking the points. */
enum class LayoutPhase { SETTINGS, OUTLINE, POINTS }

/** What a tap on the layout plan does. Long-press and drag always moves a corner or point. */
enum class PlanTool { NONE, ADD_POINT, REANCHOR }

/** A survey point while the layout is still being edited. */
data class DraftPoint(val position: Pt, val source: String)

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

    var arStatus by mutableStateOf(ArStatus.CHECKING)
        private set

    private var arCheckJob: Job? = null

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
    var draftWidth by mutableStateOf("")
    var draftLength by mutableStateOf("")

    var draftCorners by mutableStateOf<List<Pt>>(emptyList())
        private set

    var draftPoints by mutableStateOf<List<DraftPoint>>(emptyList())
        private set

    private var outlineSource = SOURCE_MANUAL

    /** Drift measured when the AR outline walk returned to the origin corner. */
    var closureErrorM by mutableStateOf<Double?>(null)
        private set

    var closurePromptOpen by mutableStateOf(false)
        private set

    /** How ARCore's world maps onto the room, once the origin has been set. */
    var arFrame by mutableStateOf<RoomFrame?>(null)
        private set

    /** The ARCore session the frame belongs to. A new session means a new, unrelated world. */
    private var arFrameSession = -1

    var tool by mutableStateOf(PlanTool.NONE)

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

    init {
        refreshArStatus()
    }

    // --- navigation --------------------------------------------------------

    fun refreshReadiness() {
        readiness = readReadiness(getApplication())
    }

    /** ARCore can take a moment to decide, so keep asking while it says it is still checking. */
    fun refreshArStatus() {
        arCheckJob?.cancel()
        arCheckJob = viewModelScope.launch {
            repeat(50) {
                val status = checkArStatus(getApplication())
                if (status != ArStatus.CHECKING) {
                    arStatus = status
                    return@launch
                }
                delay(200)
            }
            arStatus = ArStatus.UNSUPPORTED
        }
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

    // --- layout ------------------------------------------------------------

    fun startLayout() {
        draftName = ""
        draftMode = SurveyMode.WIFI
        draftPingHost = ""
        draftSamples = "10"
        draftWidth = ""
        draftLength = ""
        draftCorners = emptyList()
        draftPoints = emptyList()
        outlineSource = SOURCE_MANUAL
        closureErrorM = null
        arFrame = null
        arFrameSession = -1
        tool = PlanTool.NONE
        layoutPhase = LayoutPhase.SETTINGS
        note = null
        screen = Screen.Layout
    }

    fun cancelLayout() {
        goHome()
    }

    /** Leave the AR walk and go back to choose the outline another way. */
    fun backToSettings() {
        draftCorners = emptyList()
        closureErrorM = null
        tool = PlanTool.NONE
        layoutPhase = LayoutPhase.SETTINGS
    }

    fun beginArOutline() {
        draftCorners = emptyList()
        draftPoints = emptyList()
        outlineSource = SOURCE_AR
        closureErrorM = null
        arFrame = null
        arFrameSession = -1
        tool = PlanTool.NONE
        layoutPhase = LayoutPhase.OUTLINE
        note = "Stand in the corner you want as the origin with a wall on your left, point " +
            "the camera along that wall, and set the origin."
    }

    /**
     * The manual outline: a rectangle whose origin corner has the length wall on your left.
     * +y runs along that wall and +x across the room, which matches the AR frame, so AR can
     * still be used for the points by setting the origin at that same corner.
     */
    fun useRectangle(widthM: Double, lengthM: Double) {
        draftCorners = listOf(
            Pt(0.0, 0.0),
            Pt(0.0, lengthM),
            Pt(widthM, lengthM),
            Pt(widthM, 0.0)
        )
        outlineSource = SOURCE_MANUAL
        closureErrorM = null
        tool = PlanTool.NONE
        layoutPhase = LayoutPhase.POINTS
        note = "Mark each survey position. Use AR by setting the origin at corner (0, 0), or " +
            "switch on Tap to add and place points by hand."
    }

    /** True when the frame was set in an ARCore session that has since been replaced. */
    fun arFrameIsStale(pose: ArPose): Boolean =
        arFrame != null && pose.sessionId != -1 && pose.sessionId != arFrameSession

    /** Where ARCore puts you in room coordinates, or null when that is not known right now. */
    fun positionOf(pose: ArPose): Pt? {
        val frame = arFrame ?: return null
        if (!pose.tracking || pose.sessionId != arFrameSession) return null
        return frame.toRoom(pose.worldX, pose.worldZ)
    }

    fun setOrigin(pose: ArPose) {
        if (!pose.tracking) {
            note = "ARCore is not tracking yet. " + (pose.problem ?: "")
            return
        }
        val frame = RoomFrame.at(pose.worldX, pose.worldZ, pose.lookX, pose.lookZ)
        if (frame == null) {
            note = "Hold the phone upright and point the camera along the wall, not at the " +
                "floor or ceiling."
            return
        }
        arFrame = frame
        arFrameSession = pose.sessionId
        if (layoutPhase == LayoutPhase.OUTLINE && draftCorners.isEmpty()) {
            draftCorners = listOf(Pt(0.0, 0.0))
            note = "Origin set. Walk the walls and mark each corner, then come back here " +
                "and close the outline."
        } else {
            note = "Origin set at corner (0, 0)."
        }
    }

    fun markCorner(pose: ArPose) {
        val here = positionOf(pose)
        if (here == null) {
            note = "No AR position right now. " + (pose.problem ?: "")
            return
        }
        draftCorners = draftCorners + here
    }

    fun undoCorner() {
        if (draftCorners.size > 1) draftCorners = draftCorners.dropLast(1)
    }

    /** Called when you have walked the whole perimeter and are back on the origin corner. */
    fun closeOutline(pose: ArPose) {
        if (draftCorners.size < 3) {
            note = "An outline needs at least three corners."
            return
        }
        closureErrorM = positionOf(pose)?.let { distance(Pt(0.0, 0.0), it) }
        closurePromptOpen = true
    }

    fun acknowledgeClosure() {
        closurePromptOpen = false
        layoutPhase = LayoutPhase.POINTS
        note = "Walk to each survey position and mark it. If the marker drifts, use I'm here " +
            "to tap where you really are."
    }

    fun markPointAr(pose: ArPose) {
        val here = positionOf(pose)
        if (here == null) {
            note = "No AR position right now. " + (pose.problem ?: "") +
                " Switch on Tap to add to place the point by hand."
            return
        }
        draftPoints = draftPoints + DraftPoint(here, SOURCE_AR)
    }

    fun addPointManually(position: Pt) {
        draftPoints = draftPoints + DraftPoint(position, SOURCE_MANUAL)
    }

    fun undoPoint() {
        if (draftPoints.isNotEmpty()) draftPoints = draftPoints.dropLast(1)
    }

    /** Shifts the AR frame so where ARCore thinks you are becomes where you tapped. */
    fun reanchor(pose: ArPose, truePosition: Pt) {
        tool = PlanTool.NONE
        val frame = arFrame
        if (frame == null || !pose.tracking || pose.sessionId != arFrameSession) {
            note = "Re-anchoring needs AR to be tracking with the origin set."
            return
        }
        arFrame = frame.anchoredAt(pose.worldX, pose.worldZ, truePosition)
        note = String.format(
            "Re-anchored to %.2f m, %.2f m. Later AR positions are shifted to match.",
            truePosition.x,
            truePosition.y
        )
    }

    fun moveCorner(index: Int, position: Pt) {
        if (index !in draftCorners.indices) return
        draftCorners = draftCorners.mapIndexed { i, c -> if (i == index) position else c }
        if (outlineSource == SOURCE_AR) outlineSource = SOURCE_MIXED
    }

    fun movePoint(index: Int, position: Pt) {
        if (index !in draftPoints.indices) return
        draftPoints = draftPoints.mapIndexed { i, p ->
            if (i == index) DraftPoint(position, SOURCE_MANUAL) else p
        }
    }

    fun finishLayout() {
        val samplesPerPoint = draftSamples.toIntOrNull() ?: return
        val corners = draftCorners
        val positions = draftPoints
        val host = draftPingHost.trim().ifBlank { null }
        val mode = draftMode
        val name = draftName.trim()
        val source = outlineSource
        val closure = closureErrorM
        viewModelScope.launch {
            val id = dao.insertRoom(
                SurveyRoom(
                    name = name,
                    mode = mode.name,
                    samplesPerPoint = samplesPerPoint,
                    pingHost = host,
                    polygon = encodePolygon(corners),
                    outlineSource = source,
                    closureErrorM = closure,
                    createdAt = System.currentTimeMillis()
                )
            )
            dao.insertPoints(
                positions.mapIndexed { index, p ->
                    GridPoint(
                        roomId = id,
                        seq = index,
                        x = p.position.x,
                        y = p.position.y,
                        source = p.source,
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
