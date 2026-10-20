package com.dtyan.fitdiary.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_definitions WHERE athleteId = :athleteId AND (:includeArchived OR isArchived = 0) ORDER BY sortOrder, id")
    fun observeDefinitions(athleteId: Long, includeArchived: Boolean = false): Flow<List<ActivityDefinition>>
    @Query("SELECT * FROM activity_definitions WHERE athleteId = :athleteId ORDER BY sortOrder, id")
    suspend fun getAllDefinitions(athleteId: Long): List<ActivityDefinition>
    @Query("SELECT * FROM activity_entries WHERE athleteId = :athleteId ORDER BY timestamp, id")
    suspend fun getAllEntries(athleteId: Long): List<ActivityEntry>
    @Query("SELECT * FROM activity_definitions WHERE id = :id AND athleteId = :athleteId")
    suspend fun getDefinition(id: Long, athleteId: Long): ActivityDefinition?
    @Query("SELECT * FROM activity_definitions WHERE `key` = :key AND athleteId = :athleteId")
    suspend fun getDefinitionByKey(key: String, athleteId: Long): ActivityDefinition?
    @Insert
    suspend fun insertDefinition(definition: ActivityDefinition): Long
    @Update
    suspend fun updateDefinition(definition: ActivityDefinition)
    @Query("SELECT COUNT(*) FROM activity_entries WHERE activityId = :activityId")
    suspend fun entryCount(activityId: Long): Int
    @Query("SELECT * FROM activity_entries WHERE athleteId = :athleteId ORDER BY timestamp DESC, id DESC")
    fun observeEntries(athleteId: Long): Flow<List<ActivityEntry>>
    @Query("SELECT * FROM activity_entries WHERE athleteId = :athleteId AND epochDay = :epochDay ORDER BY timestamp DESC, id DESC")
    fun observeEntriesForDay(epochDay: Long, athleteId: Long): Flow<List<ActivityEntry>>
    @Query("SELECT * FROM activity_entries WHERE athleteId = :athleteId AND activityId = :activityId ORDER BY epochDay DESC, timestamp DESC, id DESC")
    fun observeHistory(activityId: Long, athleteId: Long): Flow<List<ActivityEntry>>
    @Query("SELECT * FROM activity_entries WHERE athleteId = :athleteId AND id = :id")
    suspend fun getEntry(id: Long, athleteId: Long): ActivityEntry?
    @Query("SELECT * FROM activity_entries WHERE clientToken = :token")
    suspend fun getEntryByToken(token: String): ActivityEntry?
    @Insert
    suspend fun insertEntry(entry: ActivityEntry): Long
    @Update
    suspend fun updateEntry(entry: ActivityEntry)
    @Query("DELETE FROM activity_entries WHERE id = :id AND athleteId = :athleteId")
    suspend fun deleteEntry(id: Long, athleteId: Long)
    @Query("SELECT * FROM activity_timers WHERE activityId = :activityId AND athleteId = :athleteId")
    fun observeTimer(activityId: Long, athleteId: Long): Flow<ActivityTimer?>
    @Query("SELECT * FROM activity_timers WHERE athleteId = :athleteId ORDER BY startWallTime, activityId")
    fun observeTimers(athleteId: Long): Flow<List<ActivityTimer>>
    @Query("SELECT * FROM activity_timers WHERE activityId = :activityId AND athleteId = :athleteId")
    suspend fun getTimer(activityId: Long, athleteId: Long): ActivityTimer?
    @Query("SELECT * FROM activity_timers WHERE clientToken = :token")
    suspend fun getTimerByToken(token: String): ActivityTimer?
    @Insert
    suspend fun insertTimer(timer: ActivityTimer)
    @Update
    suspend fun updateTimer(timer: ActivityTimer)
    @Query("DELETE FROM activity_timers WHERE activityId = :activityId AND athleteId = :athleteId")
    suspend fun deleteTimer(activityId: Long, athleteId: Long)
    @Query("SELECT * FROM activity_plans WHERE planKey = :key AND athleteId = :athleteId")
    suspend fun getPlanByKey(key: String, athleteId: Long): ActivityPlan?
    @Query("SELECT * FROM activity_plans WHERE athleteId = :athleteId ORDER BY id DESC")
    fun observePlans(athleteId: Long): Flow<List<ActivityPlan>>
    @Insert
    suspend fun insertPlan(plan: ActivityPlan): Long
    @Update
    suspend fun updatePlan(plan: ActivityPlan)
    @Query("DELETE FROM activity_plans WHERE id = :id AND athleteId = :athleteId")
    suspend fun deletePlan(id: Long, athleteId: Long)
}
