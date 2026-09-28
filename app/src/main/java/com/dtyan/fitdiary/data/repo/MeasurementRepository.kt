package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.data.db.MeasurementDao
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.flow.Flow

class MeasurementRepository(private val dao: MeasurementDao) {

    fun observeAll(): Flow<List<BodyMeasurement>> = dao.observeAll()
    fun observeLastEpochDay(): Flow<Long?> = dao.observeLastEpochDay()

    suspend fun getAllOnce(): List<BodyMeasurement> = dao.getAllOnce()
    suspend fun getForType(type: MeasurementType): List<BodyMeasurement> = dao.getForType(type)
    suspend fun getForDayOnce(epochDay: Long): List<BodyMeasurement> = dao.getForDayOnce(epochDay)
    suspend fun lastEpochDay(): Long? = dao.lastEpochDay()

    /**
     * Записывает набор замеров одной «сессии» (обычно утро одного дня).
     * values — зона → сантиметры; пустые зоны в карте не передаются.
     */
    suspend fun addSession(
        values: Map<MeasurementType, Double>,
        now: Long = System.currentTimeMillis(),
        note: String? = null,
    ): List<Long> {
        if (values.isEmpty()) return emptyList()
        val day = Format.epochDayOf(now)
        return dao.insertAll(
            values.map { (type, cm) ->
                BodyMeasurement(epochDay = day, timestamp = now, type = type, valueCm = cm, note = note?.trim()?.ifBlank { null })
            }
        )
    }

    suspend fun update(item: BodyMeasurement) = dao.update(item)
    suspend fun delete(item: BodyMeasurement) = dao.delete(item)
}
