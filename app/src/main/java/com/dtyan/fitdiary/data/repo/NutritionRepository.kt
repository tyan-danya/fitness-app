package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.DayNutritionTotal
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.MealDao
import kotlinx.coroutines.flow.Flow

class NutritionRepository(private val mealDao: MealDao) {

    fun observeMealsForDay(epochDay: Long): Flow<List<Meal>> = mealDao.observeForDay(epochDay)

    suspend fun addMeal(
        epochDay: Long,
        name: String,
        calories: Int,
        proteinG: Double,
        fatG: Double,
        carbsG: Double,
        timestamp: Long = System.currentTimeMillis(),
    ): Long = mealDao.insert(
        Meal(
            epochDay = epochDay,
            timestamp = timestamp,
            name = name.trim(),
            calories = calories,
            proteinG = proteinG,
            fatG = fatG,
            carbsG = carbsG,
        )
    )

    suspend fun updateMeal(meal: Meal) = mealDao.update(meal)
    suspend fun deleteMeal(meal: Meal) = mealDao.delete(meal)

    /** Недавние блюда (уникальные по названию) для быстрого повторного добавления. */
    suspend fun recentMeals(limit: Int = 12): List<Meal> = mealDao.recentDistinct(limit)

    suspend fun daysWithMeals(fromDay: Long, toDay: Long): List<Long> =
        mealDao.daysWithMeals(fromDay, toDay)

    suspend fun dailyTotals(fromDay: Long, toDay: Long): List<DayNutritionTotal> =
        mealDao.dailyTotals(fromDay, toDay)

    suspend fun getAllOnce(): List<Meal> = mealDao.getAllOnce()
    suspend fun getForDayOnce(epochDay: Long): List<Meal> = mealDao.getForDayOnce(epochDay)
}
