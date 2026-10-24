package com.gridsense.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gridsense.core.Metric
import com.gridsense.data.Db
import com.gridsense.data.Repeatability
import com.gridsense.data.SurveyRoom
import com.gridsense.data.compareNodes
import com.gridsense.data.incompatibility
import com.gridsense.data.sameCondition
import com.gridsense.data.summarise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State for one A-versus-B comparison, scoped to the compare destination. */
class CompareViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()

    val aId: Long = saved.get<Long>("a") ?: -1
    val bId: Long = saved.get<Long>("b") ?: -1

    private fun <T> live(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val roomA = live(dao.room(aId), null)
    val roomB = live(dao.room(bId), null)
    val pointsA = live(dao.points(aId), emptyList())
    val pointsB = live(dao.points(bId), emptyList())
    val samplesA = live(dao.samples(aId), emptyList())
    val samplesB = live(dao.samples(bId), emptyList())

    var metric by mutableStateOf(Metric.RSSI)

    /** The user's claim that nothing was changed between the two passes. */
    var sameConditions by mutableStateOf(false)

    private var initialised = false

    init {
        viewModelScope.launch {
            val a = dao.roomNow(aId)
            val b = dao.roomNow(bId)
            if (a != null && !initialised) {
                metric = modeOf(a).defaultMetric
                sameConditions = b != null && sameCondition(a, b)
                initialised = true
            }
        }
    }

    /**
     * Repeatability from another same-condition pair on the same grid, for this metric: the pair
     * with the most matched nodes. Null when no such pair has been surveyed.
     */
    suspend fun referenceRepeatability(a: SurveyRoom, metric: Metric): Repeatability? = withContext(Dispatchers.IO) {
        val rooms = dao.roomsNow().filter { incompatibility(a, it) == null || it.id == a.id }
        val pairs = rooms.flatMapIndexed { i, r1 ->
            rooms.drop(i + 1).filter { r2 -> sameCondition(r1, r2) }.map { r1 to it }
        }.filterNot { (r1, r2) -> setOf(r1.id, r2.id) == setOf(aId, bId) }
        pairs.mapNotNull { (r1, r2) ->
            val d = compareNodes(
                dao.pointsNow(r1.id), dao.samplesNow(r1.id).groupBy { it.pointId },
                dao.pointsNow(r2.id), dao.samplesNow(r2.id).groupBy { it.pointId },
                metric
            )
            summarise(d, metric)?.takeIf { it.count >= 2 }?.let {
                Repeatability(r1.name + " / " + r2.name, it.count, it.repeatabilityBand)
            }
        }.maxByOrNull { it.count }
    }
}
