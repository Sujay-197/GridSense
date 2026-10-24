package com.gridsense.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gridsense.core.Metric
import com.gridsense.core.PingResult
import com.gridsense.core.SurveyMode
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
import com.gridsense.data.SurveySummary
import com.gridsense.data.buildInsights
import com.gridsense.data.insightsAsText
import com.gridsense.data.surveySummary
import com.gridsense.export.baseName
import com.gridsense.export.writeCsvs
import com.gridsense.export.writeJson
import com.gridsense.net.Neighbour
import com.gridsense.net.NeighbourScanner
import com.gridsense.net.ScanOutcome
import com.gridsense.net.ProbeResult
import com.gridsense.net.Transport
import com.gridsense.net.activeTransport
import com.gridsense.net.dnsLookup
import com.gridsense.net.loadTest
import com.gridsense.net.ping
import com.gridsense.net.tcpConnect
import com.gridsense.core.median
import com.gridsense.data.KIND_DNS
import com.gridsense.data.KIND_DOWNLOAD
import com.gridsense.data.KIND_LOADED_PING
import com.gridsense.data.KIND_TCP
import com.gridsense.data.LOAD_TEST_MAX_MS
import com.gridsense.data.PROBE_ATTEMPTS
import com.gridsense.data.dataUsePerNode
import com.gridsense.data.downloadCapBytes
import com.gridsense.data.goodputMbps
import com.gridsense.net.pingTarget
import com.gridsense.net.readCell
import com.gridsense.net.readLink
import com.gridsense.net.readReadiness
import com.gridsense.ui.components.Stage
import com.gridsense.ui.components.StageStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PING_COUNT = 10
private const val POLL_INTERVAL_MS = 500L

private const val STAGE_RADIOS = "Sampling radios"
private const val STAGE_PING = "Ping"
private const val STAGE_SCAN = "Neighbour scan"
private const val STAGE_DNS = "DNS lookups"
private const val STAGE_TCP = "TCP connects"
private const val STAGE_LOAD = "Load test"

/**
 * Survey data shared by Home and the survey screen. Navigation lives in the NavHost; this
 * class only says which survey is open and runs the logging, router and export work.
 */
@Suppress("OPT_IN_USAGE")
class SurveyViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()
    private val scanner = NeighbourScanner(app)

    var readiness by mutableStateOf(readReadiness(app))
        private set

    private val notes = Channel<String>(Channel.BUFFERED)

    /** Transient notes for a snackbar. */
    val messages: Flow<String> = notes.receiveAsFlow()

    private fun say(text: String) {
        if (text.isNotBlank()) notes.trySend(text)
    }

    // --- logging -----------------------------------------------------------

    var loggingPointId by mutableStateOf<Long?>(null)
        private set

    /** Per-stage progress of the current logging run, shown in the node sheet. */
    var stages by mutableStateOf<List<Stage>>(emptyList())
        private set

    private var loggingJob: Job? = null

    /** A node waiting on the user's answer to running the load test over mobile data. */
    var cellularLoadPrompt by mutableStateOf<GridPoint?>(null)
        private set

    /** The answer, kept for the rest of the session: null until asked. */
    private var loadOnCellular: Boolean? = null

    fun answerCellularLoad(run: Boolean) {
        loadOnCellular = run
        val point = cellularLoadPrompt ?: return
        cellularLoadPrompt = null
        startLogging(point)
    }

    fun dismissCellularLoad() {
        cellularLoadPrompt = null
    }

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

    /** Fixed colours by quality band, instead of stretching the ramp over this room's range. */
    var fixedScale by mutableStateOf(false)

    // --- data streams ------------------------------------------------------

    private val roomId = MutableStateFlow<Long?>(null)

    val rooms = dao.rooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Progress and primary-metric quality for every survey card on Home. */
    val summaries = combine(dao.rooms(), dao.allPoints(), dao.signalSamples()) { rooms, points, samples ->
        val pointsByRoom = points.groupBy { it.roomId }
        val samplesByPoint = samples.groupBy { it.pointId }
        rooms.associate { room ->
            room.id to surveySummary(
                pointsByRoom[room.id].orEmpty(),
                samplesByPoint,
                modeOf(room).defaultMetric
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<Long, SurveySummary>())

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

    fun refreshReadiness() {
        readiness = readReadiness(getApplication())
    }

    /** Points the survey streams at [id]. Called by the survey destination on entry. */
    fun openRoom(id: Long) {
        if (roomId.value == id) return
        roomId.value = id
        bssidFilter = null
        placingRouter = false
        routerDialogOpen = false
        viewModelScope.launch {
            dao.resetStatus(id, STATUS_LOGGING, STATUS_PENDING)
            val opened = dao.roomNow(id)
            if (opened != null) metric = modeOf(opened).defaultMetric
        }
    }

    fun deleteRoom(id: Long) {
        if (roomId.value == id) roomId.value = null
        viewModelScope.launch { dao.deleteRoom(id) }
    }

    fun setPointEnabled(point: GridPoint, enabled: Boolean) {
        viewModelScope.launch { dao.updatePoint(point.copy(enabled = enabled)) }
    }

    // --- logging -----------------------------------------------------------

    private fun setStage(name: String, status: StageStatus, progress: Float? = null, detail: String? = null) {
        stages = stages.map { if (it.name == name) Stage(name, status, progress, detail) else it }
    }

    /** Runs one stage, turning an exception into a failed stage so the other stages carry on. */
    private suspend fun <T> stage(name: String, block: suspend () -> T): T? {
        setStage(name, StageStatus.RUNNING)
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStage(name, StageStatus.FAILED, detail = e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Runs [PROBE_ATTEMPTS] attempts as one stage; a failed attempt is recorded, not fatal. */
    private suspend fun runProbes(name: String, target: String, attempt: suspend () -> ProbeResult): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()
        stage(name) {
            repeat(PROBE_ATTEMPTS) { i ->
                results += attempt()
                setStage(name, StageStatus.RUNNING, (i + 1f) / PROBE_ATTEMPTS, target)
            }
            val ok = results.filter { it.ok }.mapNotNull { it.ms }
            setStage(
                name,
                if (ok.isEmpty()) StageStatus.FAILED else StageStatus.DONE,
                detail = if (ok.isEmpty()) "All $PROBE_ATTEMPTS failed: " + (results.lastOrNull()?.error ?: target)
                else String.format("%s, median %.1f ms, %d of %d ok", target, median(ok), ok.size, results.size)
            )
        }
        return results
    }

    private fun ProbeResult.toSample(pointId: Long, kind: String, target: String) = Sample(
        pointId = pointId, ts = System.currentTimeMillis(), kind = kind, probeMs = ms, probeOk = ok, target = target
    )

    fun startLogging(point: GridPoint) {
        if (loggingJob?.isActive == true) return
        val currentRoom = room.value ?: return
        refreshReadiness()
        if (!readiness.canLog) {
            say("Neither the Wi-Fi identifiers nor the serving cell are readable, so logging is blocked.")
            return
        }
        val onCellular = activeTransport(getApplication()) == Transport.CELLULAR
        if (currentRoom.loadTest && onCellular && loadOnCellular == null) {
            cellularLoadPrompt = point
            return
        }
        val runLoad = currentRoom.loadTest && (!onCellular || loadOnCellular == true)
        loggingJob = viewModelScope.launch {
            val app = getApplication<Application>()
            loggingPointId = point.id
            stages = listOf(STAGE_RADIOS, STAGE_PING, STAGE_SCAN, STAGE_DNS, STAGE_TCP, STAGE_LOAD)
                .map { Stage(it, StageStatus.PENDING) }
            dao.deleteSamplesOf(point.id)
            dao.setStatus(point.id, STATUS_LOGGING)

            val host = pingTarget(app, currentRoom.pingHost)

            val pollJob = async {
                stage(STAGE_RADIOS) {
                    val rows = ArrayList<Sample>()
                    val total = currentRoom.samplesPerPoint
                    repeat(total) { i ->
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
                        setStage(STAGE_RADIOS, StageStatus.RUNNING, (i + 1f) / total, "${i + 1} of $total polls")
                        delay(POLL_INTERVAL_MS)
                    }
                    setStage(STAGE_RADIOS, StageStatus.DONE, detail = "$total polls")
                    rows
                }.orEmpty()
            }
            val pingJob = async {
                if (host == null) {
                    setStage(STAGE_PING, StageStatus.SKIPPED, detail = "No ping target could be chosen")
                    null
                } else {
                    stage(STAGE_PING) {
                        val result: PingResult = ping(host, PING_COUNT)
                        val avg = result.avgMs
                        if (avg == null) {
                            setStage(STAGE_PING, StageStatus.FAILED, detail = "No replies from $host")
                        } else {
                            setStage(STAGE_PING, StageStatus.DONE, detail = String.format("%s, %.1f ms", host, avg))
                        }
                        result
                    }
                }
            }
            val scanJob = async {
                stage(STAGE_SCAN) {
                    val outcome: ScanOutcome = scanner.scanOnce()
                    setStage(
                        STAGE_SCAN,
                        StageStatus.DONE,
                        detail = outcome.neighbours.size.toString() + " access points" +
                            if (outcome.fresh) "" else ", cached list"
                    )
                    outcome
                }
            }

            val polled = pollJob.await()
            val pingResult = pingJob.await()
            val scan = scanJob.await()

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

            if (scan != null) {
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
                            rssiDbm = it.rssiDbm,
                            channelWidthMhz = it.widthMhz,
                            centerFreq0Mhz = it.centerFreq0Mhz,
                            wifiStandard = it.wifiStandard
                        )
                    }
                )
            }

            // After the radios and the idle ping, so these probes don't disturb either.
            if (currentRoom.extendedTests) {
                runProbes(STAGE_DNS, currentRoom.dnsName) { dnsLookup(app, currentRoom.dnsName) }
                    .let { dao.insertSamples(it.map { r -> r.toSample(point.id, KIND_DNS, currentRoom.dnsName) }) }
                val tcpTarget = currentRoom.tcpHost + ":" + currentRoom.tcpPort
                runProbes(STAGE_TCP, tcpTarget) { tcpConnect(currentRoom.tcpHost, currentRoom.tcpPort) }
                    .let { dao.insertSamples(it.map { r -> r.toSample(point.id, KIND_TCP, tcpTarget) }) }
            } else {
                setStage(STAGE_DNS, StageStatus.SKIPPED, detail = "Extended tests are off for this survey")
                setStage(STAGE_TCP, StageStatus.SKIPPED, detail = "Extended tests are off for this survey")
            }

            if (runLoad) {
                stage(STAGE_LOAD) {
                    val url = currentRoom.downloadUrl
                    val result = loadTest(url, downloadCapBytes(url), LOAD_TEST_MAX_MS, host) { p ->
                        setStage(STAGE_LOAD, StageStatus.RUNNING, p, dataUsePerNode(url))
                    }
                    val now = System.currentTimeMillis()
                    dao.insertSamples(
                        result.rtts.map {
                            Sample(pointId = point.id, ts = now, kind = KIND_LOADED_PING, probeMs = it, probeOk = true, target = host)
                        } + Sample(
                            pointId = point.id, ts = now, kind = KIND_DOWNLOAD, bytes = result.bytes,
                            durationMs = result.durationMs, probeOk = result.ok, target = url
                        )
                    )
                    val mbps = goodputMbps(result.bytes, result.durationMs)
                    setStage(
                        STAGE_LOAD,
                        if (result.ok) StageStatus.DONE else StageStatus.FAILED,
                        detail = if (result.ok && mbps != null) String.format("%.1f Mbps, %d pings", mbps, result.rtts.size)
                        else result.error ?: "Download failed"
                    )
                }
            } else {
                setStage(
                    STAGE_LOAD, StageStatus.SKIPPED,
                    detail = if (currentRoom.loadTest) "Skipped on mobile data" else "Off for this survey"
                )
            }

            dao.setStatus(point.id, STATUS_DONE)
            val scanNote = scan?.note.orEmpty()
            say(
                if (host == null) {
                    "No ping target could be chosen, so latency, jitter and loss were not measured. $scanNote"
                } else {
                    "Pinged $host. $scanNote"
                }.trim()
            )
            loggingPointId = null
        }
    }

    // --- routers -----------------------------------------------------------

    fun beginRouterPlacement() {
        placingRouter = true
        say("Tap anywhere on the plan to drop the router marker.")
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
        refreshScanChoices()
    }

    fun refreshScanChoices() {
        viewModelScope.launch {
            scanChoicesLoading = true
            val outcome = scanner.scanOnce()
            scanChoices = outcome.neighbours.sortedByDescending { it.rssiDbm }
            say(outcome.note)
            scanChoicesLoading = false
        }
    }

    fun confirmRouter(bssid: String, label: String, heightDiffM: Double) {
        val id = roomId.value ?: return
        viewModelScope.launch {
            dao.insertRouter(
                Router(
                    roomId = id,
                    x = pendingRouterX,
                    y = pendingRouterY,
                    bssid = bssid,
                    label = label,
                    heightDiffM = heightDiffM
                )
            )
            routerDialogOpen = false
        }
    }

    fun deleteRouter(routerId: Long) {
        viewModelScope.launch { dao.deleteRouter(routerId) }
    }

    // --- export ------------------------------------------------------------

    private class Snapshot(
        val room: SurveyRoom,
        val points: List<GridPoint>,
        val samples: List<Sample>,
        val routers: List<Router>
    )

    private suspend fun snapshot(id: Long): Snapshot? {
        val r = dao.roomNow(id) ?: return null
        return Snapshot(r, dao.pointsNow(id), dao.samplesNow(id), dao.routersNow(id))
    }

    suspend fun exportCsv(id: Long): List<File> = withContext(Dispatchers.IO) {
        val s = snapshot(id) ?: return@withContext emptyList()
        writeCsvs(getApplication(), s.room, s.points, s.samples, s.routers, baseName(s.room))
    }

    suspend fun exportJson(id: Long): File? = withContext(Dispatchers.IO) {
        val s = snapshot(id) ?: return@withContext null
        writeJson(getApplication(), s.room, s.points, s.samples, s.routers, baseName(s.room))
    }

    suspend fun reportText(id: Long): Pair<String, String>? = withContext(Dispatchers.Default) {
        val s = snapshot(id) ?: return@withContext null
        val title = "GridSense report: " + s.room.name
        title to insightsAsText(title, buildInsights(s.points, s.samples, s.routers, s.room.loadTest))
    }
}

fun modeOf(room: SurveyRoom): SurveyMode =
    if (room.mode == SurveyMode.CELLULAR.name) SurveyMode.CELLULAR else SurveyMode.WIFI
