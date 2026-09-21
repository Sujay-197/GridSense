package com.gridsense.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {

    @Insert
    suspend fun insertRoom(room: SurveyRoom): Long

    @Insert
    suspend fun insertPoints(points: List<GridPoint>)

    @Insert
    suspend fun insertSample(sample: Sample)

    @Insert
    suspend fun insertSamples(samples: List<Sample>)

    @Insert
    suspend fun insertRouter(router: Router): Long

    @Update
    suspend fun updatePoint(point: GridPoint)

    @Query("SELECT * FROM survey_room ORDER BY createdAt DESC")
    fun rooms(): Flow<List<SurveyRoom>>

    @Query("SELECT * FROM survey_room WHERE id = :roomId")
    fun room(roomId: Long): Flow<SurveyRoom?>

    @Query("SELECT * FROM survey_room WHERE id = :roomId")
    suspend fun roomNow(roomId: Long): SurveyRoom?

    @Query("SELECT * FROM grid_point WHERE roomId = :roomId ORDER BY seq")
    fun points(roomId: Long): Flow<List<GridPoint>>

    @Query("SELECT * FROM grid_point WHERE roomId = :roomId ORDER BY seq")
    suspend fun pointsNow(roomId: Long): List<GridPoint>

    @Query(
        "SELECT s.* FROM sample s JOIN grid_point g ON s.pointId = g.id " +
            "WHERE g.roomId = :roomId ORDER BY s.ts"
    )
    fun samples(roomId: Long): Flow<List<Sample>>

    @Query(
        "SELECT s.* FROM sample s JOIN grid_point g ON s.pointId = g.id " +
            "WHERE g.roomId = :roomId ORDER BY s.ts"
    )
    suspend fun samplesNow(roomId: Long): List<Sample>

    @Query("SELECT * FROM router WHERE roomId = :roomId ORDER BY id")
    fun routers(roomId: Long): Flow<List<Router>>

    @Query("SELECT * FROM router WHERE roomId = :roomId ORDER BY id")
    suspend fun routersNow(roomId: Long): List<Router>

    @Query("DELETE FROM sample WHERE pointId = :pointId")
    suspend fun deleteSamplesOf(pointId: Long)

    @Query("UPDATE grid_point SET status = :status WHERE id = :pointId")
    suspend fun setStatus(pointId: Long, status: String)

    @Query("UPDATE grid_point SET status = :status WHERE roomId = :roomId AND status = :from")
    suspend fun resetStatus(roomId: Long, from: String, status: String)

    @Query("DELETE FROM router WHERE id = :routerId")
    suspend fun deleteRouter(routerId: Long)

    @Query("DELETE FROM survey_room WHERE id = :roomId")
    suspend fun deleteRoom(roomId: Long)
}
