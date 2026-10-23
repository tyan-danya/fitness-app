package com.dtyan.fitdiary.ui.templates

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.export.TemplateExchange
import com.dtyan.fitdiary.export.TemplateImportPreview
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.export.PlanBundlePreview
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.data.db.ActivityPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TemplateDraftRow(
    val exerciseId: Long,
    val name: String,
    val muscleGroup: String,
    val sets: String = "3",
    val reps: String = "",
    val note: String = "",
)

data class TemplateDraft(val id: Long? = null, val name: String = "", val rows: List<TemplateDraftRow> = emptyList())

/** The owner is captured when this screen is opened, including during system file pickers. */
class TemplatesViewModel(
    private val repository: TemplateRepository,
    private val exerciseRepository: ExerciseRepository,
    private val ownerId: Long,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val plans: PlanBundleRepository? = null,
) : ViewModel() {
    val templates = repository.observeTemplates(ownerId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val exercises = exerciseRepository.observeExercises().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Избранное владельца программы — в каталоге выбора оно идёт первым. */
    val favoriteIds = exerciseRepository.observeFavoriteIds(ownerId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    /** «Не нравится» владельца: такие упражнения помечены в программах, в каталоге идут последними. */
    val dislikedIds = exerciseRepository.observeDislikedIds(ownerId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    /** Состав каждой программы — карточка показывает его без открытия редактора. */
    val itemsByTemplate = repository.observeItemRefs(ownerId).map { refs -> refs.groupBy { it.templateId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private val _draft = MutableStateFlow(decodeDraft(savedState["templateDraft"]))
    val draft = _draft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _preview = MutableStateFlow<TemplateImportPreview?>(null)
    val preview = _preview.asStateFlow()
    val schedules = (plans?.observePlans(ownerId) ?: flowOf(emptyList<ActivityPlan>()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _planPreview = MutableStateFlow<PlanBundlePreview?>(null)
    val planPreview = _planPreview.asStateFlow()
    private var pendingExport: String? = null
    val sourceHandled: Boolean get() = savedState["templateSourceHandled"] ?: false

    fun newTemplate() { if (!_busy.value) { _error.value = null; _message.value = null; setDraft(TemplateDraft()) } }
    fun discardDraft() { if (!_busy.value) { _error.value = null; _message.value = null; setDraft(null) } }
    fun changeName(value: String) { updateDraft { it.copy(name = value.take(80)) } }
    fun updateRow(id: Long, transform: (TemplateDraftRow) -> TemplateDraftRow) {
        updateDraft { draft -> draft.copy(rows = draft.rows.map { if (it.exerciseId == id) transform(it) else it }) }
    }
    fun addExercise(exercise: Exercise) {
        updateDraft { draft ->
            if (draft.rows.any { it.exerciseId == exercise.id } || draft.rows.size >= 100) draft
            else draft.copy(rows = draft.rows + TemplateDraftRow(exercise.id, exercise.name, exercise.muscleGroup))
        }
    }
    fun removeExercise(id: Long) { updateDraft { it.copy(rows = it.rows.filterNot { row -> row.exerciseId == id }) } }
    /**
     * Замена в черновике: новое упражнение на том же месте с прежними подходами и повторами.
     * Заметка старого к нему не подходит — вместо неё пометка, что это замена. Сохраняет «Сохранить программу».
     */
    fun replaceExercise(oldId: Long, exercise: Exercise) {
        updateDraft { draft ->
            if (exercise.id == oldId || draft.rows.any { it.exerciseId == exercise.id }) draft
            else draft.copy(rows = draft.rows.map { row ->
                if (row.exerciseId != oldId) row
                else row.copy(exerciseId = exercise.id, name = exercise.name, muscleGroup = exercise.muscleGroup,
                    note = TemplateRepository.replacementNote(row.name))
            })
        }
    }
    /** «Не нравится» прямо из программы: отметка общая для каталога и тренировок владельца. */
    fun toggleDislike(exerciseId: Long) {
        val disliked = exerciseId !in dislikedIds.value
        viewModelScope.launch {
            try { exerciseRepository.setDisliked(exerciseId, disliked, ownerId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Не удалось изменить отметку. Попробуйте ещё раз." }
        }
    }
    fun moveExercise(id: Long, delta: Int) {
        updateDraft { draft ->
            val index = draft.rows.indexOfFirst { it.exerciseId == id }
            val target = index + delta
            if (index < 0 || target !in draft.rows.indices) draft else {
                val rows = draft.rows.toMutableList()
                rows.add(target, rows.removeAt(index))
                draft.copy(rows = rows.toList())
            }
        }
    }

    fun edit(id: Long) = operation {
        val details = requireNotNull(repository.getTemplate(id, athleteId = ownerId)) { "Программа не найдена" }
        setDraft(TemplateDraft(details.template.id, details.template.name, details.items.map {
            TemplateDraftRow(it.exercise.id, it.exercise.name, it.exercise.muscleGroup,
                it.targetSets.toString(), it.targetReps?.toString().orEmpty(), it.note.orEmpty())
        }))
    }

    fun save() {
        val snapshot = _draft.value ?: return
        operation {
            require(snapshot.name.isNotBlank()) { "Введите название программы" }
            require(snapshot.rows.isNotEmpty()) { "Добавьте хотя бы одно упражнение" }
            val items = snapshot.rows.map { row ->
                val sets = row.sets.toIntOrNull()
                require(sets != null && sets in 1..100) { "${row.name}: укажите от 1 до 100 подходов" }
                val reps = row.reps.takeIf { it.isNotBlank() }?.toIntOrNull()
                require(row.reps.isBlank() || (reps != null && reps in 1..999)) { "${row.name}: повторы должны быть от 1 до 999 или пустыми" }
                TemplatePlanItem(row.exerciseId, sets, reps, row.note.trim().ifEmpty { null })
            }
            if (snapshot.id == null) repository.create(snapshot.name, items, athleteId = ownerId)
            else repository.update(snapshot.id, snapshot.name, items, athleteId = ownerId)
            setDraft(null)
            _message.value = "Программа сохранена"
        }
    }

    fun saveSource(workoutId: Long, name: String) = operation {
        val id = repository.saveFromWorkout(workoutId, name, athleteId = ownerId)
        savedState["templateSourceHandled"] = true
        val details = requireNotNull(repository.getTemplate(id, athleteId = ownerId))
        setDraft(TemplateDraft(details.template.id, details.template.name, details.items.map {
            TemplateDraftRow(it.exercise.id, it.exercise.name, it.exercise.muscleGroup,
                it.targetSets.toString(), it.targetReps?.toString().orEmpty(), it.note.orEmpty())
        }))
        _message.value = "Программа сохранена. Можно уточнить ориентиры."
    }

    fun dismissSource() { savedState["templateSourceHandled"] = true }
    fun delete(id: Long, onDeleted: () -> Unit) = operation {
        repository.delete(id, athleteId = ownerId)
        _message.value = "Программа удалена. История тренировок сохранена."
        onDeleted()
    }

    fun start(id: Long, participants: List<Long>, onStarted: (Long) -> Unit) {
        val captured = listOf(ownerId) + participants.filter { it != ownerId }.distinct()
        operation {
            val workouts = repository.startTemplate(id, captured, athleteId = ownerId)
            onStarted(workouts.first { it.athleteId == ownerId }.id)
        }
    }

    fun readImport(resolver: ContentResolver, uri: Uri) = operation {
        val text = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use(TemplateExchange::readText)
                ?: error("Не удалось открыть файл")
        }
        _preview.value = null
        _planPreview.value = null
        if (PlanBundleExchange.isBundle(text)) _planPreview.value = PlanBundleExchange.parse(text)
        else _preview.value = TemplateExchange.parse(text)
    }
    fun dismissPreview() { if (!_busy.value) { _preview.value = null; _planPreview.value = null } }
    fun importPlanPreview() {
        val captured = _planPreview.value ?: return
        operation {
            val added = requireNotNull(plans) { "Обновите приложение для импорта плана" }.importPlan(captured, ownerId)
            _planPreview.value = null
            _message.value = if (added) "План загружен. Цели и домашние упражнения — в «Активности за день»."
                else "Этот план уже загружен. Повторных записей не создано."
        }
    }
    fun deleteSchedule(id: Long) = operation {
        plans?.deleteSchedule(id, ownerId)
        _message.value = "Расписание удалено. Программы, цели и результаты сохранены."
    }
    /** Days 1..7 (Monday..Sunday) exchange their whole contents. */
    fun swapDays(planId: Long, first: Int, second: Int) = operation {
        requireNotNull(plans) { "Обновите приложение для изменения плана" }.swapDays(planId, ownerId, first, second)
        _message.value = PLAN_UPDATED
    }
    /** +1 moves every day's contents to the next weekday, −1 to the previous one. */
    fun shiftWeek(planId: Long, days: Int) = operation {
        requireNotNull(plans) { "Обновите приложение для изменения плана" }.shiftWeek(planId, ownerId, days)
        _message.value = PLAN_UPDATED
    }
    fun importPreview() {
        val captured = _preview.value ?: return
        operation {
            repository.importTemplate(captured.name, captured.exercises, athleteId = ownerId)
            _preview.value = null
            _message.value = "Программа импортирована"
        }
    }

    fun prepareExport(id: Long, onReady: (String) -> Unit) = operation {
        val details = requireNotNull(repository.getTemplate(id, athleteId = ownerId)) { "Программа не найдена" }
        pendingExport = TemplateExchange.encode(details)
        onReady("fitness-program-$id.json")
    }
    fun writeExport(resolver: ContentResolver, uri: Uri?) {
        val captured = pendingExport
        pendingExport = null
        if (uri == null) return
        if (captured == null) { _error.value = "Повторите экспорт программы"; return }
        operation {
            withContext(Dispatchers.IO) {
                val stream = resolver.openOutputStream(uri, "wt") ?: error("Не удалось создать файл")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(captured) }
            }
            _message.value = "Файл программы сохранён"
        }
    }

    private fun updateDraft(transform: (TemplateDraft) -> TemplateDraft) {
        if (!_busy.value) _draft.value?.let { setDraft(transform(it)) }
    }
    private fun setDraft(value: TemplateDraft?) {
        _draft.value = value
        savedState["templateDraft"] = value?.let { draft -> JSONObject().apply {
            put("id", draft.id ?: JSONObject.NULL); put("name", draft.name)
            put("rows", JSONArray().apply { draft.rows.forEach { row -> put(JSONObject().apply {
                put("id", row.exerciseId); put("name", row.name); put("muscle", row.muscleGroup)
                put("sets", row.sets); put("reps", row.reps); put("note", row.note)
            }) } })
        }.toString() }
    }
    private fun operation(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _message.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Не удалось выполнить действие. Попробуйте ещё раз." }
            finally { _busy.value = false }
        }
    }

    companion object {
        const val PLAN_UPDATED = "План недели обновлён"
        private fun decodeDraft(value: String?): TemplateDraft? = value?.let { text -> runCatching {
            val json = JSONObject(text)
            val rows = json.getJSONArray("rows")
            TemplateDraft(if (json.isNull("id")) null else json.getLong("id"), json.getString("name"),
                (0 until rows.length()).map { i -> rows.getJSONObject(i).let { row ->
                    TemplateDraftRow(row.getLong("id"), row.getString("name"), row.getString("muscle"),
                        row.getString("sets"), row.getString("reps"), row.getString("note"))
                } })
        }.getOrNull() }
    }
}
