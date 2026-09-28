package com.dtyan.fitdiary.data.repo

import android.content.Context
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.AthleteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local profiles, independent of a network account. Profile 1 owns pre-v5 history. */
class ProfileRepository(context: Context, private val athleteDao: AthleteDao) {
    private val prefs = context.applicationContext.getSharedPreferences("fitdiary_profiles", Context.MODE_PRIVATE)
    private val selection = MutableStateFlow(prefs.getLong("activeId", 1L).coerceAtLeast(1L))
    val activeId: StateFlow<Long> = selection.asStateFlow()
    val profiles: Flow<List<Athlete>> = athleteDao.observeActive()
    val allProfiles: Flow<List<Athlete>> = athleteDao.observeAll()
    private val mutation = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { refreshSelection() }
    }

    private suspend fun ensureDefault() {
        athleteDao.insert(Athlete(id = 1L, name = "Я"))
        athleteDao.getById(1L)?.takeIf { it.isArchived }?.let {
            athleteDao.update(it.copy(isArchived = false))
        }
    }

    /** Revalidate after importing a backup or restoring application state. */
    suspend fun refreshSelection() = mutation.withLock {
        ensureDefault()
        val current = athleteDao.getById(selection.value)
        if (current == null || current.isArchived) persistSelection(1L)
    }

    suspend fun create(name: String): Long = mutation.withLock {
        ensureDefault()
        athleteDao.insert(Athlete(name = validName(name)))
    }

    suspend fun rename(id: Long, name: String) = mutation.withLock {
        val athlete = requireNotNull(athleteDao.getById(id)) { "Профиль не найден" }
        athleteDao.update(athlete.copy(name = validName(name)))
    }

    suspend fun archive(id: Long) = mutation.withLock {
        ensureDefault()
        athleteDao.archiveKeepingOne(id)
        if (selection.value == id) persistSelection(1L)
    }

    suspend fun restore(id: Long) = mutation.withLock {
        val athlete = requireNotNull(athleteDao.getById(id)) { "Профиль не найден" }
        athleteDao.update(athlete.copy(isArchived = false))
    }

    suspend fun select(id: Long) = mutation.withLock {
        ensureDefault()
        val athlete = requireNotNull(athleteDao.getById(id)) { "Профиль не найден" }
        require(!athlete.isArchived) { "Профиль скрыт" }
        persistSelection(id)
    }

    private fun persistSelection(id: Long) {
        prefs.edit().putLong("activeId", id).apply()
        selection.value = id
    }

    private fun validName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 40) { "Имя должно содержать от 1 до 40 символов" }
    }
}
