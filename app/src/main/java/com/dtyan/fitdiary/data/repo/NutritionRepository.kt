package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.DayNutritionTotal
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.MealDao
import com.dtyan.fitdiary.data.db.AppDatabase
import androidx.room.withTransaction
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.Per100
import com.dtyan.fitdiary.domain.totalsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class NutritionRepository(
    private val mealDao: MealDao,
    val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
    private val database: AppDatabase? = null,
) {
    val currentAthleteId: Long get() = activeAthleteId.value
    fun observeAllMeals(): Flow<List<Meal>> = activeAthleteId.flatMapLatest { mealDao.observeAll(it) }

    fun observeMealsForDay(epochDay: Long): Flow<List<Meal>> = activeAthleteId.flatMapLatest { mealDao.observeForDay(epochDay, it) }

    /**
     * Добавляет приём с уже посчитанным итогом порции.
     * mealType == null — определить по часу [timestamp]. servingG/per100 — как вводил пользователь.
     */
    suspend fun addMeal(
        epochDay: Long,
        name: String,
        calories: Int,
        proteinG: Double,
        fatG: Double,
        carbsG: Double,
        timestamp: Long = System.currentTimeMillis(),
        mealType: MealType? = null,
        servingG: Double? = null,
        per100: Per100? = null,
        needsEstimate: Boolean = false,
        athleteId: Long = currentAthleteId,
    ): Long = mealDao.insert(
        Meal(
            epochDay = epochDay,
            timestamp = timestamp,
            name = name.trim(),
            calories = calories,
            proteinG = proteinG,
            fatG = fatG,
            carbsG = carbsG,
            mealType = mealType ?: MealType.forTimestamp(timestamp),
            servingG = servingG,
            caloriesPer100 = per100?.kcal,
            proteinPer100 = per100?.proteinG,
            fatPer100 = per100?.fatG,
            carbsPer100 = per100?.carbsG,
            needsEstimate = needsEstimate,
            athleteId = athleteId,
        )
    )

    suspend fun updateMeal(meal: Meal, athleteId: Long = currentAthleteId) {
        if (meal.athleteId == athleteId && mealDao.getByIds(listOf(meal.id), athleteId).isNotEmpty()) mealDao.update(meal)
    }
    suspend fun deleteMeal(meal: Meal, athleteId: Long = currentAthleteId) {
        if (meal.athleteId == athleteId && mealDao.getByIds(listOf(meal.id), athleteId).isNotEmpty()) mealDao.delete(meal)
    }

    /** Перенос приёма в другую группу (перетаскивание на экране). */
    suspend fun moveMeal(mealId: Long, type: MealType, athleteId: Long = currentAthleteId) = mealDao.setMealType(mealId, type, athleteId)

    /** Недавние блюда (уникальные по названию) для быстрого повторного добавления. */
    suspend fun recentMeals(limit: Int = 12, athleteId: Long = currentAthleteId): List<Meal> = mealDao.recentDistinct(limit, athleteId)

    suspend fun daysWithMeals(fromDay: Long, toDay: Long, athleteId: Long = currentAthleteId): List<Long> =
        mealDao.daysWithMeals(fromDay, toDay, athleteId)

    suspend fun dailyTotals(fromDay: Long, toDay: Long, athleteId: Long = currentAthleteId): List<DayNutritionTotal> =
        mealDao.dailyTotals(fromDay, toDay, athleteId)

    suspend fun getAllOnce(athleteId: Long = currentAthleteId): List<Meal> = mealDao.getAllOnce(athleteId)
    suspend fun getForDayOnce(epochDay: Long, athleteId: Long = currentAthleteId): List<Meal> = mealDao.getForDayOnce(epochDay, athleteId)

    // --- Расчёт КБЖУ «потом» ---

    fun observePendingEstimateCount(): Flow<Int> = activeAthleteId.flatMapLatest { mealDao.observePendingEstimateCount(it) }
    suspend fun getPendingEstimatesOnce(athleteId: Long = currentAthleteId): List<Meal> = mealDao.getPendingEstimatesOnce(athleteId)

    /** Один рассчитанный ответ: значения на 100 г и (опционально) уточнённый вес порции. */
    data class EstimateUpdate(
        val mealId: Long,
        val per100: Per100,
        val servingG: Double?,
        val fingerprint: String? = null,
        val athleteId: Long = 1L,
    )

    data class EstimatePreview(val accepted: List<EstimateUpdate>, val conflicts: List<String>, val athleteId: Long)

    suspend fun previewEstimates(updates: List<EstimateUpdate>, athleteId: Long): EstimatePreview {
        require(athleteId == currentAthleteId) { "Выберите профиль, для которого был выгружен файл" }
        require(updates.map { it.mealId }.distinct().size == updates.size) { "В файле повторяются приёмы" }
        val meals = mealDao.getByIds(updates.map { it.mealId }, athleteId).associateBy { it.id }
        val conflicts = mutableListOf<String>()
        val accepted = updates.filter { u ->
            validateEstimate(u)
            val meal = meals[u.mealId]
            val matches = meal != null && meal.needsEstimate && u.athleteId == athleteId &&
                u.fingerprint == fingerprint(meal)
            if (!matches) conflicts += meal?.name ?: "Приём №${u.mealId} удалён или принадлежит другому профилю"
            matches
        }
        return EstimatePreview(accepted, conflicts, athleteId)
    }

    companion object {
        /** Fingerprint includes the complete persisted record, so manual edits conflict. */
        fun fingerprint(meal: Meal): String {
            val fields = listOf(meal.id, meal.athleteId, meal.epochDay, meal.timestamp, meal.name,
                meal.calories, meal.proteinG, meal.fatG, meal.carbsG, meal.mealType.name, meal.servingG,
                meal.caloriesPer100, meal.proteinPer100, meal.fatPer100, meal.carbsPer100, meal.needsEstimate)
            val text = fields.joinToString("") { value -> value.toString().let { "${it.length}:$it" } }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun validateEstimate(u: EstimateUpdate) {
            require(u.mealId > 0 && u.athleteId > 0) { "Некорректный идентификатор приёма" }
            require(u.per100.kcal.isFinite() && u.per100.kcal in 0.0..1000.0) { "Некорректные калории на 100 г" }
            require(listOf(u.per100.proteinG, u.per100.fatG, u.per100.carbsG).all { it.isFinite() && it in 0.0..100.0 }) { "Некорректные БЖУ на 100 г" }
            require(u.servingG == null || (u.servingG.isFinite() && u.servingG > 0 && u.servingG <= 100_000)) { "Некорректный вес порции" }
        }
    }

    /**
     * Применяет результаты расчёта к приёмам по id: записывает per100, вес порции
     * (сохраняет введённый пользователем; использует оценку только если вес не задан),
     * пересчитывает итог и снимает флаг «ждёт расчёта». Все записи применяются атомарно.
     */
    suspend fun applyEstimates(updates: List<EstimateUpdate>): Int {
        if (updates.isEmpty()) return 0
        val db = requireNotNull(database) { "Для импорта требуется транзакция базы данных" }
        val athleteId = currentAthleteId
        return db.withTransaction {
        val preview = previewEstimates(updates, athleteId)
        require(preview.conflicts.isEmpty()) { "Данные изменились после предпросмотра. Загрузите файл повторно." }
        val byId = mealDao.getByIds(updates.map { it.mealId }, athleteId).associateBy { it.id }
        var applied = 0
        for (u in updates) {
            val meal = byId[u.mealId] ?: continue
            val serving = meal.servingG ?: u.servingG ?: throw IllegalArgumentException("Не указан вес порции")
            val totals = totalsFor(u.per100, serving)
            mealDao.update(
                meal.copy(
                    calories = totals.calories,
                    proteinG = totals.proteinG,
                    fatG = totals.fatG,
                    carbsG = totals.carbsG,
                    servingG = serving,
                    caloriesPer100 = u.per100.kcal,
                    proteinPer100 = u.per100.proteinG,
                    fatPer100 = u.per100.fatG,
                    carbsPer100 = u.per100.carbsG,
                    needsEstimate = false,
                )
            )
            applied++
        }
        applied
        }
    }
}
