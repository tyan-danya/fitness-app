package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.data.db.MeasurementDao
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementRepository(
    private val dao: MeasurementDao,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
) {
    val currentAthleteId: Long get() = activeAthleteId.value
    fun observeAll(): Flow<List<BodyMeasurement>> = activeAthleteId.flatMapLatest { dao.observeAll(it) }
    fun observeLastEpochDay(): Flow<Long?> = activeAthleteId.flatMapLatest { dao.observeLastEpochDay(it) }
    suspend fun getAllOnce(athleteId: Long = activeAthleteId.value): List<BodyMeasurement> = dao.getAllOnce(athleteId)
    suspend fun getForType(type: MeasurementType, athleteId: Long = activeAthleteId.value): List<BodyMeasurement> =
        dao.getForType(type, athleteId)
    suspend fun getForDayOnce(epochDay: Long, athleteId: Long = activeAthleteId.value): List<BodyMeasurement> =
        dao.getForDayOnce(epochDay, athleteId)
    suspend fun lastEpochDay(athleteId: Long = activeAthleteId.value): Long? = dao.lastEpochDay(athleteId)

    suspend fun addSession(
        values: Map<MeasurementType, Double>, now: Long = System.currentTimeMillis(),
        note: String? = null, athleteId: Long = activeAthleteId.value,
    ): List<Long> {
        if (values.isEmpty()) return emptyList()
        require(values.values.all { it.isFinite() && it > 0 }) { "Замеры должны быть больше нуля" }
        val day = Format.epochDayOf(now)
        return dao.insertAll(values.map { (type, cm) ->
            BodyMeasurement(epochDay = day, timestamp = now, type = type, valueCm = cm,
                note = note?.trim()?.ifBlank { null }, athleteId = athleteId)
        })
    }
    suspend fun update(item: BodyMeasurement) {
        require(item.valueCm.isFinite() && item.valueCm > 0) { "Замер должен быть больше нуля" }
        dao.update(item)
    }
    suspend fun delete(item: BodyMeasurement) = dao.delete(item)
}
