package com.dtyan.fitdiary.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.dtyan.fitdiary.ui.theme.fitAccents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.ui.profile.ProfileToolbar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtyan.fitdiary.ui.home.HomeScreen
import com.dtyan.fitdiary.ui.measure.MeasurementsScreen
import com.dtyan.fitdiary.ui.nutrition.NutritionScreen
import com.dtyan.fitdiary.ui.stats.DayDetailsScreen
import com.dtyan.fitdiary.ui.stats.StatsScreen
import com.dtyan.fitdiary.ui.stats.WorkoutDetailsScreen
import com.dtyan.fitdiary.ui.workout.ActiveWorkoutScreen
import com.dtyan.fitdiary.ui.workout.ExerciseLogScreen
import com.dtyan.fitdiary.ui.workout.ExercisePickerScreen
import com.dtyan.fitdiary.ui.templates.TemplatesScreen
import com.dtyan.fitdiary.ui.update.UpdateBanner
import com.dtyan.fitdiary.ui.update.UpdateDialog

/** Маршруты приложения. */
object Routes {
    const val HOME = "home"
    const val NUTRITION = "nutrition"
    const val MEASUREMENTS = "measurements"
    const val STATS = "stats"
    const val ACTIVE_WORKOUT = "workout/{workoutId}"
    const val EXERCISE_PICKER = "workout/{workoutId}/pick"
    const val EXERCISE_LOG = "workout/{workoutId}/exercise/{exerciseId}"
    const val DAY_DETAILS = "stats/day/{epochDay}"
    const val WORKOUT_DETAILS = "stats/workout/{workoutId}"
    const val TEMPLATES = "templates?sourceWorkoutId={sourceWorkoutId}"

    fun activeWorkout(workoutId: Long) = "workout/$workoutId"
    fun exercisePicker(workoutId: Long) = "workout/$workoutId/pick"
    fun exerciseLog(workoutId: Long, exerciseId: Long) = "workout/$workoutId/exercise/$exerciseId"
    fun dayDetails(epochDay: Long) = "stats/day/$epochDay"
    fun workoutDetails(workoutId: Long) = "stats/workout/$workoutId"
    fun templates(sourceWorkoutId: Long? = null) = "templates?sourceWorkoutId=${sourceWorkoutId ?: 0L}"
}

private data class TabItem(val route: String, val title: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem(Routes.HOME, "Тренировка", Icons.Filled.FitnessCenter),
    TabItem(Routes.NUTRITION, "Питание", Icons.Filled.Restaurant),
    TabItem(Routes.MEASUREMENTS, "Замеры", Icons.Filled.Straighten),
    TabItem(Routes.STATS, "Статистика", Icons.Filled.Insights),
)

/**
 * Корень навигации. [requestedTab] — вкладка, которую попросили открыть извне
 * (например, тап по уведомлению о замерах); меняется — переходим на неё.
 */
@Composable
fun AppRoot(requestedTab: String? = null, requestNonce: Int = 0, updateRequestNonce: Int = 0) {
    val container = LocalContext.current.appContainer
    val athleteId by container.profiles.activeId.collectAsStateWithLifecycle()
    var dataRevision by remember { mutableIntStateOf(0) }
    var showUpdates by remember { mutableStateOf(false) }
    LaunchedEffect(updateRequestNonce) {
        if (updateRequestNonce > 0) showUpdates = true
    }
    key(athleteId, dataRevision) {
        ProfileNavigation(requestedTab, requestNonce, onUpdates = { showUpdates = true }, onDataRestored = { dataRevision++ })
    }
    if (showUpdates) UpdateDialog(container.updater, onDismiss = { showUpdates = false })
}

@Composable
private fun ProfileNavigation(requestedTab: String?, requestNonce: Int, onUpdates: () -> Unit, onDataRestored: () -> Unit) {
    val updater = LocalContext.current.appContainer.updater
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TABS.map { it.route }
    val compactLabels = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f

    LaunchedEffect(requestedTab, requestNonce) {
        val route = requestedTab?.takeIf { r -> TABS.any { it.route == r } } ?: return@LaunchedEffect
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = { if (showBottomBar) Column {
            ProfileToolbar(onDataRestored, onUpdates = onUpdates)
            if (currentRoute == Routes.HOME) UpdateBanner(updater, onOpen = onUpdates)
        } },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val accents = fitAccents
                    TABS.forEach { tab ->
                        // У каждой вкладки — свой фирменный акцент раздела.
                        val (accent, container) = when (tab.route) {
                            Routes.HOME -> accents.workout to accents.workoutContainer
                            Routes.NUTRITION -> accents.nutrition to accents.nutritionContainer
                            Routes.MEASUREMENTS -> accents.measure to accents.measureContainer
                            else -> accents.stats to accents.statsContainer
                        }
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(if (compactLabels) when (tab.route) {
                                Routes.HOME -> "Зал"
                                Routes.STATS -> "Итоги"
                                else -> tab.title
                            } else tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = accent,
                                selectedTextColor = accent,
                                indicatorColor = container,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenWorkout = { id -> navController.navigate(Routes.activeWorkout(id)) },
                    onOpenWorkoutDetails = { id -> navController.navigate(Routes.workoutDetails(id)) },
                    onOpenTemplates = { navController.navigate(Routes.templates()) },
                )
            }
            composable(Routes.NUTRITION) {
                NutritionScreen()
            }
            composable(Routes.MEASUREMENTS) {
                MeasurementsScreen()
            }
            composable(Routes.STATS) {
                StatsScreen(
                    onOpenDay = { day -> navController.navigate(Routes.dayDetails(day)) },
                    onOpenWorkout = { id -> navController.navigate(Routes.workoutDetails(id)) },
                )
            }
            composable(
                Routes.ACTIVE_WORKOUT,
                arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: return@composable
                ActiveWorkoutScreen(
                    workoutId = workoutId,
                    onAddExercise = { selectedWorkoutId -> navController.navigate(Routes.exercisePicker(selectedWorkoutId)) },
                    onOpenExercise = { selectedWorkoutId, exerciseId ->
                        navController.navigate(Routes.exerciseLog(selectedWorkoutId, exerciseId))
                    },
                    onWorkoutClosed = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onSaveTemplate = { id -> navController.navigate(Routes.templates(id)) },
                )
            }
            composable(
                Routes.EXERCISE_PICKER,
                arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: return@composable
                ExercisePickerScreen(
                    workoutId = workoutId,
                    onExerciseChosen = { exerciseId ->
                        // Пикер убираем из стека: назад с экрана подходов — сразу в тренировку.
                        navController.navigate(Routes.exerciseLog(workoutId, exerciseId)) {
                            popUpTo(Routes.ACTIVE_WORKOUT)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.EXERCISE_LOG,
                arguments = listOf(
                    navArgument("workoutId") { type = NavType.LongType },
                    navArgument("exerciseId") { type = NavType.LongType },
                ),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: return@composable
                val exerciseId = entry.arguments?.getLong("exerciseId") ?: return@composable
                ExerciseLogScreen(
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    onBack = { navController.popBackStack() },
                    onOpenExercise = { nextWorkoutId, nextExerciseId ->
                        navController.navigate(Routes.exerciseLog(nextWorkoutId, nextExerciseId)) {
                            popUpTo(Routes.EXERCISE_LOG) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onPlanComplete = { id ->
                        navController.navigate(Routes.activeWorkout(id)) {
                            popUpTo(Routes.ACTIVE_WORKOUT) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                Routes.DAY_DETAILS,
                arguments = listOf(navArgument("epochDay") { type = NavType.LongType }),
            ) { entry ->
                val epochDay = entry.arguments?.getLong("epochDay") ?: return@composable
                DayDetailsScreen(
                    epochDay = epochDay,
                    onOpenWorkout = { id -> navController.navigate(Routes.workoutDetails(id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.WORKOUT_DETAILS,
                arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: return@composable
                WorkoutDetailsScreen(
                    workoutId = workoutId,
                    onBack = { navController.popBackStack() },
                    onRepeatWorkout = { id -> navController.navigate(Routes.activeWorkout(id)) },
                    onSaveTemplate = { id -> navController.navigate(Routes.templates(id)) },
                )
            }
            composable(Routes.TEMPLATES, arguments = listOf(navArgument("sourceWorkoutId") {
                type = NavType.LongType; defaultValue = 0L
            })) { entry ->
                TemplatesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWorkout = { id ->
                        navController.navigate(Routes.activeWorkout(id)) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    },
                    sourceWorkoutId = entry.arguments?.getLong("sourceWorkoutId")?.takeIf { it > 0L },
                )
            }
        }
    }
}
