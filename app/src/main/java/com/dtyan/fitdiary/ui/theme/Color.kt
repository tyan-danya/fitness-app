package com.dtyan.fitdiary.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Фирменные акценты разделов и вспомогательные цвета,
 * которых нет в стандартной схеме Material3.
 * Раздел «Тренировки» — зелёный, «Питание» — оранжевый, «Замеры» — бирюзовый, «Статистика» — фиолетовый.
 */
data class FitAccents(
    val workout: Color,
    val workoutContainer: Color,
    val onWorkoutContainer: Color,
    val workoutGradient: List<Color>,
    val nutrition: Color,
    val nutritionContainer: Color,
    val onNutritionContainer: Color,
    val nutritionGradient: List<Color>,
    val stats: Color,
    val statsContainer: Color,
    val onStatsContainer: Color,
    val statsGradient: List<Color>,
    val measure: Color,
    val measureContainer: Color,
    val onMeasureContainer: Color,
    /** Цвета макронутриентов: белки/жиры/углеводы. */
    val protein: Color,
    val fat: Color,
    val carbs: Color,
    /** Золото личных рекордов. */
    val gold: Color,
)

val LightFitAccents = FitAccents(
    workout = Color(0xFF16A34A),
    workoutContainer = Color(0xFFDCFCE7),
    onWorkoutContainer = Color(0xFF14532D),
    workoutGradient = listOf(Color(0xFF22C55E), Color(0xFF059669)),
    nutrition = Color(0xFFEA8A00),
    nutritionContainer = Color(0xFFFFEDD5),
    onNutritionContainer = Color(0xFF7C2D12),
    nutritionGradient = listOf(Color(0xFFF59E0B), Color(0xFFEA580C)),
    stats = Color(0xFF7C3AED),
    statsContainer = Color(0xFFF3E8FF),
    onStatsContainer = Color(0xFF4C1D95),
    statsGradient = listOf(Color(0xFF8B5CF6), Color(0xFF6366F1)),
    measure = Color(0xFF0D9488),
    measureContainer = Color(0xFFCCFBF1),
    onMeasureContainer = Color(0xFF134E4A),
    protein = Color(0xFF0EA5E9),
    fat = Color(0xFFF59E0B),
    carbs = Color(0xFFA855F7),
    gold = Color(0xFFD97706),
)

val DarkFitAccents = FitAccents(
    workout = Color(0xFF4ADE80),
    workoutContainer = Color(0xFF1C3A28),
    onWorkoutContainer = Color(0xFFBBF7D0),
    workoutGradient = listOf(Color(0xFF22C55E), Color(0xFF0D9488)),
    nutrition = Color(0xFFFBBF24),
    nutritionContainer = Color(0xFF3D2E14),
    onNutritionContainer = Color(0xFFFDE68A),
    nutritionGradient = listOf(Color(0xFFF59E0B), Color(0xFFDC6803)),
    stats = Color(0xFFA78BFA),
    statsContainer = Color(0xFF2E2547),
    onStatsContainer = Color(0xFFE9D5FF),
    statsGradient = listOf(Color(0xFF8B5CF6), Color(0xFF4F46E5)),
    measure = Color(0xFF2DD4BF),
    measureContainer = Color(0xFF173F3B),
    onMeasureContainer = Color(0xFF99F6E4),
    protein = Color(0xFF38BDF8),
    fat = Color(0xFFFBBF24),
    carbs = Color(0xFFC084FC),
    gold = Color(0xFFFACC15),
)

val LocalFitAccents = staticCompositionLocalOf { LightFitAccents }

/** Доступ к акцентам из composable: `fitAccents.workout`. */
val fitAccents: FitAccents
    @Composable
    @ReadOnlyComposable
    get() = LocalFitAccents.current
