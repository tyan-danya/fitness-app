package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.DayNutritionTotal
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.MealDao
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.Per100
import com.dtyan.fitdiary.domain.totalsFor
import kotlinx.coroutines.flow.Flow

class NutritionRepository(private val mealDao: MealDao) {

    fun observeMealsForDay(epochDay: Long): Flow<List<Meal>> = mealDao.observeForDay(epochDay)

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
        )
    )

    suspend fun updateMeal(meal: Meal) = mealDao.update(meal)
    suspend fun deleteMeal(meal: Meal) = mealDao.delete(meal)

    /** Перенос приёма в другую группу (перетаскивание на экране). */
    suspend fun moveMeal(mealId: Long, type: MealType) = mealDao.setMealType(mealId, type)

    /** Недавние блюда (уникальные по названию) для быстрого повторного добавления. */
    suspend fun recentMeals(limit: Int = 12): List<Meal> = mealDao.recentDistinct(limit)

    suspend fun daysWithMeals(fromDay: Long, toDay: Long): List<Long> =
        mealDao.daysWithMeals(fromDay, toDay)

    suspend fun dailyTotals(fromDay: Long, toDay: Long): List<DayNutritionTotal> =
        mealDao.dailyTotals(fromDay, toDay)

    suspend fun getAllOnce(): List<Meal> = mealDao.getAllOnce()
    suspend fun getForDayOnce(epochDay: Long): List<Meal> = mealDao.getForDayOnce(epochDay)

    // --- Расчёт КБЖУ «потом» ---

    fun observePendingEstimateCount(): Flow<Int> = mealDao.observePendingEstimateCount()
    suspend fun getPendingEstimatesOnce(): List<Meal> = mealDao.getPendingEstimatesOnce()

    /** Один рассчитанный ответ: значения на 100 г и (опционально) уточнённый вес порции. */
    data class EstimateUpdate(val mealId: Long, val per100: Per100, val servingG: Double?)

    /**
     * Применяет результаты расчёта к приёмам по id: записывает per100, вес порции
     * (если пришёл, иначе оставляет прежний; без веса берётся 100 г), пересчитывает итог
     * и снимает флаг «ждёт расчёта». Возвращает число обновлённых приёмов.
     */
    suspend fun applyEstimates(updates: List<EstimateUpdate>): Int {
        if (updates.isEmpty()) return 0
        val byId = mealDao.getByIds(updates.map { it.mealId }).associateBy { it.id }
        var applied = 0
        for (u in updates) {
            val meal = byId[u.mealId] ?: continue
            val serving = u.servingG ?: meal.servingG ?: 100.0
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
        return applied
    }
}
