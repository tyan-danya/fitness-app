package com.dtyan.fitdiary.data.repo

import androidx.room.withTransaction
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.TemplateDetails
import com.dtyan.fitdiary.data.db.TemplateExercise
import com.dtyan.fitdiary.data.db.TemplateItemRef
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutExercise
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** The order in this list is the order in which exercises will be offered. */
data class TemplatePlanItem(
    val exerciseId: Long,
    val targetSets: Int = 3,
    val targetReps: Int? = null,
    val note: String? = null,
)

/** Portable imports never carry database IDs, local photos or another athlete's identity. */
data class ImportedTemplateExercise(
    val name: String,
    val muscleGroup: String,
    val equipment: String = "Другое",
    val targetSets: Int = 3,
    val targetReps: Int? = null,
    val note: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TemplateRepository(
    private val database: AppDatabase,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
) {
    private val dao = database.templateDao()
    private val workoutDao = database.workoutDao()
    private val exerciseDao = database.exerciseDao()

    /** Capture this in a UI event before launching asynchronous work. */
    val currentAthleteId: Long get() = activeAthleteId.value

    fun observeTemplates(): Flow<List<WorkoutTemplate>> =
        activeAthleteId.flatMapLatest { dao.observeTemplates(it) }

    fun observeTemplates(athleteId: Long): Flow<List<WorkoutTemplate>> = dao.observeTemplates(athleteId)

    fun observeTemplate(id: Long, athleteId: Long = currentAthleteId): Flow<TemplateDetails?> =
        combine(dao.observeById(id, athleteId), dao.observeItems(id)) { _, _ -> Unit }
            // Read one transaction snapshot, even if Room invalidations arrive separately.
            .map { getTemplate(id, athleteId) }
            .distinctUntilChanged()

    suspend fun getTemplate(id: Long, athleteId: Long = currentAthleteId): TemplateDetails? =
        database.withTransaction {
            dao.getById(id, athleteId)?.let { TemplateDetails(it, dao.getItems(id)) }
        }

    suspend fun create(
        name: String,
        items: List<TemplatePlanItem>,
        athleteId: Long = currentAthleteId,
    ): Long {
        validatePlan(name, items)
        return database.withTransaction {
            requireAvailableAthlete(athleteId)
            requireExercises(items)
            insertTemplate(name, items, athleteId)
        }
    }

    suspend fun update(
        id: Long,
        name: String,
        items: List<TemplatePlanItem>,
        athleteId: Long = currentAthleteId,
    ) {
        validatePlan(name, items)
        database.withTransaction {
            requireAvailableAthlete(athleteId)
            val old = requireOwnedTemplate(id, athleteId)
            requireExercises(items)
            dao.update(old.copy(name = name.trim()))
            dao.deleteItems(id)
            dao.insertItems(rows(id, items))
        }
    }

    /** Состав всех программ профиля в порядке упражнений. */
    fun observeItemRefs(athleteId: Long): Flow<List<TemplateItemRef>> = dao.observeItemRefs(athleteId)

    /** Программы профиля, где встречается упражнение: им предлагается та же замена. */
    suspend fun templatesWithExercise(exerciseId: Long, athleteId: Long = currentAthleteId): List<WorkoutTemplate> =
        dao.templatesWithExercise(exerciseId, athleteId)

    /**
     * Одна замена сразу в тренировке участника и в выбранных его программах, всё или ничего.
     * Новое упражнение встаёт на место старого с прежними подходами и повторами; заметка
     * старого к нему не подходит, поэтому вместо неё — откуда взялась замена.
     */
    suspend fun replaceExercise(
        oldExerciseId: Long,
        newExerciseId: Long,
        workoutId: Long?,
        templateIds: List<Long>,
        athleteId: Long = currentAthleteId,
        now: Long = System.currentTimeMillis(),
    ) {
        require(oldExerciseId != newExerciseId) { "Выберите другое упражнение" }
        require(workoutId != null || templateIds.isNotEmpty()) { "Выберите, где заменить" }
        database.withTransaction {
            requireAvailableAthlete(athleteId)
            val old = requireNotNull(exerciseDao.getById(oldExerciseId)) { "Упражнение не найдено" }
            val replacement = requireNotNull(exerciseDao.getById(newExerciseId)) { "Упражнение не найдено" }
            require(!replacement.isArchived) { "Упражнение скрыто из каталога" }
            val note = replacementNote(old.name)
            if (workoutId != null) {
                val workout = requireNotNull(workoutDao.getById(workoutId)) { "Тренировка не найдена" }
                require(workout.athleteId == athleteId) { "Эта тренировка принадлежит другому профилю" }
                workoutDao.replacePlanExercise(workoutId, oldExerciseId, newExerciseId, note, now)
            }
            templateIds.distinct().forEach { templateId ->
                val template = requireOwnedTemplate(templateId, athleteId)
                val items = dao.getItems(templateId)
                require(items.any { it.exercise.id == oldExerciseId }) { "В программе «${template.name}» нет «${old.name}»" }
                require(items.none { it.exercise.id == newExerciseId }) { "«${replacement.name}» уже есть в программе «${template.name}»" }
                dao.replaceItem(templateId, oldExerciseId, newExerciseId, note)
            }
        }
    }

    suspend fun delete(id: Long, athleteId: Long = currentAthleteId) = database.withTransaction {
        requireOwnedTemplate(id, athleteId)
        dao.delete(id, athleteId)
    }

    /** Stores a plan, including unperformed exercises, without copying actual sets or completion. */
    suspend fun saveFromWorkout(
        workoutId: Long,
        name: String,
        athleteId: Long = currentAthleteId,
    ): Long = database.withTransaction {
        requireAvailableAthlete(athleteId)
        val source = requireNotNull(workoutDao.getById(workoutId)) { "Тренировка не найдена" }
        require(source.athleteId == athleteId) { "Эта тренировка принадлежит другому профилю" }
        val plan = workoutDao.getPlanOnce(workoutId).associateBy { it.exerciseId }
        val performed = database.workoutSetDao().getForWorkoutOnce(workoutId).groupBy { it.exerciseId }
        val items = (plan.keys + performed.keys).distinct().map { exerciseId ->
            val goal = plan[exerciseId]
            TemplatePlanItem(exerciseId,
                targetSets = goal?.targetSets ?: performed[exerciseId]?.size?.coerceIn(1, 100) ?: 3,
                targetReps = goal?.targetReps,
                note = goal?.note)
        }
        validatePlan(name, items)
        insertTemplate(name, items, athleteId)
    }

    /** All participants receive independent snapshots or none do. Existing active work is never replaced. */
    suspend fun startTemplate(
        templateId: Long,
        athleteIds: List<Long>,
        now: Long = System.currentTimeMillis(),
        athleteId: Long = currentAthleteId,
    ): List<Workout> {
        val ids = athleteIds.distinct()
        require(ids.isNotEmpty() && ids.all { it > 0 }) { "Выберите участников" }
        return database.withTransaction {
            requireAvailableAthlete(athleteId)
            requireOwnedTemplate(templateId, athleteId)
            val items = dao.getItems(templateId)
            require(items.isNotEmpty()) { "Добавьте упражнения в шаблон" }
            ids.forEach { requireAvailableAthlete(it) }
            require(ids.none { workoutDao.getActiveOnce(it) != null }) { "Сначала завершите текущую тренировку" }
            val workouts = if (ids.size == 1) listOf(workoutDao.startForAthlete(ids.single(), now))
                else workoutDao.startGroup(ids, now, UUID.randomUUID().toString())
            workouts.forEach { workout ->
                items.forEachIndexed { index, item ->
                    workoutDao.insertPlan(WorkoutExercise(
                        workoutId = workout.id, exerciseId = item.exercise.id, position = index + 1,
                        targetSets = item.targetSets, targetReps = item.targetReps, note = item.note,
                    ))
                }
            }
            workouts
        }
    }

    /** Invoke only after the portable import preview is confirmed by the user. */
    suspend fun importTemplate(
        name: String,
        items: List<ImportedTemplateExercise>,
        athleteId: Long = currentAthleteId,
    ): Long {
        validateImport(name, items)
        return database.withTransaction {
            requireAvailableAthlete(athleteId)
            // Prefer an active existing exercise, then the oldest archived match.
            val catalog = exerciseDao.getAllOnce().groupBy { identity(it.name, it.muscleGroup, it.equipment) }
            val plan = items.map { imported ->
                val existing = catalog[identity(imported.name, imported.muscleGroup, imported.equipment)]?.firstOrNull()
                val exerciseId = if (existing != null) {
                    if (existing.isArchived) exerciseDao.update(existing.copy(isArchived = false))
                    existing.id
                } else exerciseDao.insert(Exercise(
                    name = clean(imported.name), muscleGroup = clean(imported.muscleGroup),
                    equipment = clean(imported.equipment), isCustom = true,
                ))
                TemplatePlanItem(exerciseId, imported.targetSets, imported.targetReps, imported.note)
            }
            insertTemplate(name, plan, athleteId)
        }
    }

    private suspend fun requireAvailableAthlete(athleteId: Long) {
        workoutDao.ensureDefaultAthlete()
        require(workoutDao.availableAthlete(athleteId) == 1) { "Профиль не найден или скрыт" }
    }

    private suspend fun requireOwnedTemplate(id: Long, athleteId: Long): WorkoutTemplate =
        requireNotNull(dao.getById(id, athleteId)) { "Шаблон не найден в этом профиле" }

    private suspend fun requireExercises(items: List<TemplatePlanItem>) {
        items.forEach { requireNotNull(exerciseDao.getById(it.exerciseId)) { "Упражнение не найдено" } }
    }

    private suspend fun insertTemplate(name: String, items: List<TemplatePlanItem>, athleteId: Long): Long {
        val id = dao.insert(WorkoutTemplate(athleteId = athleteId, name = name.trim()))
        dao.insertItems(rows(id, items))
        return id
    }

    private fun rows(id: Long, items: List<TemplatePlanItem>): List<TemplateExercise> =
        items.mapIndexed { index, item ->
            TemplateExercise(id, item.exerciseId, index + 1, item.targetSets, item.targetReps,
                item.note?.trim()?.takeIf { it.isNotEmpty() })
        }

    companion object {
        /** Заметка замены: прежняя относилась к старому упражнению и к новому не подходит. */
        fun replacementNote(oldName: String): String = "Вместо «${clean(oldName).take(120)}»."

        /** Pure validation shared by preview parsing and the transaction that commits an import. */
        fun validateImport(name: String, items: List<ImportedTemplateExercise>) {
            validateNameAndSize(name, items.size)
            items.forEach {
                require(clean(it.name).length in 1..120) { "Название упражнения: от 1 до 120 символов" }
                require(clean(it.muscleGroup).length in 1..80) { "Группа мышц: от 1 до 80 символов" }
                require(clean(it.equipment).length in 1..80) { "Оборудование: от 1 до 80 символов" }
                validateGoals(it.targetSets, it.targetReps, it.note)
            }
            require(items.map { identity(it.name, it.muscleGroup, it.equipment) }.distinct().size == items.size) {
                "Упражнения в шаблоне не должны повторяться"
            }
        }

        private fun validatePlan(name: String, items: List<TemplatePlanItem>) {
            validateNameAndSize(name, items.size)
            require(items.all { it.exerciseId > 0 } && items.map { it.exerciseId }.distinct().size == items.size) {
                "Упражнения в шаблоне не должны повторяться"
            }
            items.forEach { validateGoals(it.targetSets, it.targetReps, it.note) }
        }

        private fun validateNameAndSize(name: String, count: Int) {
            require(name.trim().length in 1..80) { "Название шаблона: от 1 до 80 символов" }
            require(count in 1..100) { "В шаблоне должно быть от 1 до 100 упражнений" }
        }

        private fun validateGoals(sets: Int, reps: Int?, note: String?) {
            require(sets in 1..100) { "Количество подходов: от 1 до 100" }
            require(reps == null || reps in 1..999) { "Количество повторов: от 1 до 999" }
            require(note == null || note.length <= 500) { "Заметка: не больше 500 символов" }
        }

        private fun clean(value: String): String = value.trim().replace(Regex("\\s+"), " ")
        private fun identity(name: String, muscleGroup: String, equipment: String): List<String> =
            listOf(name, muscleGroup, equipment).map { clean(it).lowercase(Locale.ROOT) }
    }
}
