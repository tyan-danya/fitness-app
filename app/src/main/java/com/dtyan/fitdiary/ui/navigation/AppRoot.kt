package com.dtyan.fitdiary.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.dtyan.fitdiary.ui.theme.fitAccents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.ui.profile.AccountSheet
import com.dtyan.fitdiary.ui.profile.ActiveProfile
import com.dtyan.fitdiary.ui.profile.LocalActiveProfile
import com.dtyan.fitdiary.ui.profile.AppChrome
import com.dtyan.fitdiary.ui.profile.LocalAppChrome
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
import com.dtyan.fitdiary.ui.activity.ActivityScreen
import com.dtyan.fitdiary.ui.update.UpdateDialog

/** Маршруты приложения. */
object Routes {
    const val HOME = "home"
    const val NUTRITION = "nutrition"
    const val MEASUREMENTS = "measurements"
    const val STATS = "stats"
    const val ACTIVITY = "activity"
    const val ACTIVE_WORKOUT = "workout/{workoutId}"
    const val EXERCISE_PICKER = "workout/{workoutId}/pick"
    const val EXERCISE_LOG = "workout/{workoutId}/exercise/{exerciseId}"
    const val EXERCISE_REPLACE = "workout/{workoutId}/replace/{exerciseId}"
    const val DAY_DETAILS = "stats/day/{epochDay}"
    const val WORKOUT_DETAILS = "stats/workout/{workoutId}"
    const val TEMPLATES = "templates?sourceWorkoutId={sourceWorkoutId}"

    fun activeWorkout(workoutId: Long) = "workout/$workoutId"
    fun exercisePicker(workoutId: Long) = "workout/$workoutId/pick"
    fun exerciseLog(workoutId: Long, exerciseId: Long) = "workout/$workoutId/exercise/$exerciseId"
    fun exerciseReplace(workoutId: Long, exerciseId: Long) = "workout/$workoutId/replace/$exerciseId"
    fun dayDetails(epochDay: Long) = "stats/day/$epochDay"
    fun workoutDetails(workoutId: Long) = "stats/workout/$workoutId"
    fun templates(sourceWorkoutId: Long? = null) = "templates?sourceWorkoutId=${sourceWorkoutId ?: 0L}"
}

private data class TabItem(val route: String, val title: String, val shortTitle: String, val icon: ImageVector)

/** Вкладка «Тело» объединяет вес и замеры; маршрут прежний, чтобы уведомления открывали её как раньше. */
private val TABS = listOf(
    TabItem(Routes.HOME, "Тренировка", "Зал", Icons.Rounded.FitnessCenter),
    TabItem(Routes.NUTRITION, "Питание", "Питание", Icons.Rounded.Restaurant),
    TabItem(Routes.MEASUREMENTS, "Тело", "Тело", Icons.Rounded.MonitorWeight),
    TabItem(Routes.STATS, "Статистика", "Итоги", Icons.Rounded.Insights),
)

/**
 * Корень навигации. [requestedTab] — вкладка, которую попросили открыть извне
 * (например, тап по уведомлению о замерах); меняется — переходим на неё.
 */
@Composable
fun AppRoot(requestedTab: String? = null, requestNonce: Int = 0, updateRequestNonce: Int = 0) {
    val container = LocalContext.current.appContainer
    val athleteId by container.profiles.activeId.collectAsStateWithLifecycle()
    val people by container.profiles.profiles.collectAsStateWithLifecycle(null)
    val activeProfile = ActiveProfile(athleteId, people?.firstOrNull { it.id == athleteId }?.name.orEmpty())
    var dataRevision by remember { mutableIntStateOf(0) }
    var showUpdates by remember { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(updateRequestNonce) {
        if (updateRequestNonce > 0) showUpdates = true
    }
    val chrome = remember { AppChrome(openAccount = { showAccount = true }, openUpdates = { showUpdates = true }) }
    CompositionLocalProvider(LocalAppChrome provides chrome, LocalActiveProfile provides activeProfile) {
        key(athleteId, dataRevision) {
            ProfileNavigation(requestedTab, requestNonce)
        }
    }
    if (showAccount) AccountSheet(
        onDismiss = { showAccount = false },
        onDataRestored = { dataRevision++ },
        onOpenUpdates = { showAccount = false; showUpdates = true },
    )
    if (showUpdates) UpdateDialog(container.updater, onDismiss = { showUpdates = false })
}

@Composable
private fun ProfileNavigation(requestedTab: String?, requestNonce: Int) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TABS.map { it.route }
    val compactLabels = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f

    /** Переход на вкладку так же, как нажатие на неё внизу: со своим стеком и без дублей. */
    fun openTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(requestedTab, requestNonce) {
        val route = requestedTab?.takeIf { r -> TABS.any { it.route == r } } ?: return@LaunchedEffect
        openTab(route)
    }

    Scaffold(
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
                            onClick = { openTab(tab.route) },
                            modifier = Modifier.testTag("tab-${tab.route}"),
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(if (compactLabels) tab.shortTitle else tab.title, maxLines = 1) },
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
                    onOpenActivity = { navController.navigate(Routes.ACTIVITY) },
                    onOpenStats = { openTab(Routes.STATS) },
                    onOpenBody = { openTab(Routes.MEASUREMENTS) },
                )
            }
            composable(Routes.ACTIVITY) {
                ActivityScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPrograms = { navController.navigate(Routes.templates()) },
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
                    onReplaceExercise = { selectedWorkoutId, exerciseId ->
                        navController.navigate(Routes.exerciseReplace(selectedWorkoutId, exerciseId))
                    },
                )
            }
            composable(
                Routes.EXERCISE_REPLACE,
                arguments = listOf(
                    navArgument("workoutId") { type = NavType.LongType },
                    navArgument("exerciseId") { type = NavType.LongType },
                ),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: return@composable
                val exerciseId = entry.arguments?.getLong("exerciseId") ?: return@composable
                ExercisePickerScreen(
                    workoutId = workoutId,
                    onExerciseChosen = {},
                    onBack = { navController.popBackStack() },
                    replaceExerciseId = exerciseId,
                    onReplaced = { newExerciseId ->
                        // Замена встала в тренировку — сразу к ней; старый экран подходов и каталог уходят из стека.
                        if (newExerciseId != null) navController.navigate(Routes.exerciseLog(workoutId, newExerciseId)) {
                            popUpTo(Routes.ACTIVE_WORKOUT)
                        } else navController.popBackStack()
                    },
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
                    onReplaceExercise = { selectedWorkoutId, oldExerciseId ->
                        navController.navigate(Routes.exerciseReplace(selectedWorkoutId, oldExerciseId))
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
