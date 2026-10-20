package com.dtyan.fitdiary.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.ActivityProgression

@Entity(
    tableName = "activity_definitions",
    foreignKeys = [ForeignKey(entity = Athlete::class, parentColumns = ["id"], childColumns = ["athleteId"])],
    indices = [Index(value = ["athleteId", "key"], unique = true), Index(value = ["id", "athleteId"], unique = true)],
)
data class ActivityDefinition(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val key: String,
    val name: String,
    val kind: ActivityKind,
    val goalMetric: ActivityMetric,
    val goalsJson: String,
    val instructions: String = "",
    val weeklyScheduleNote: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
) {
    @get:Ignore
    val goals: List<Double> get() = ActivityProgression.decodeGoals(goalsJson)
}

/** One observed performance, never a prescribed result. REPS means one actual set. */
@Entity(
    tableName = "activity_entries",
    foreignKeys = [ForeignKey(entity = ActivityDefinition::class,
        parentColumns = ["id", "athleteId"], childColumns = ["activityId", "athleteId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["activityId", "athleteId"]),
        Index(value = ["athleteId", "epochDay"]),
        Index(value = ["clientToken"], unique = true),
    ],
)
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val activityId: Long,
    val epochDay: Long,
    val timestamp: Long,
    val reps: Int? = null,
    val durationSeconds: Long? = null,
    val distanceKm: Double? = null,
    val steps: Long? = null,
    val speedKmh: Double? = null,
    val inclinePercent: Double? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "1") val isClean: Boolean = true,
    val clientToken: String,
)

/** A stopped timer remains pending until the user saves or discards it. */
@Entity(
    tableName = "activity_timers",
    foreignKeys = [ForeignKey(entity = ActivityDefinition::class,
        parentColumns = ["id", "athleteId"], childColumns = ["activityId", "athleteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["activityId", "athleteId"]), Index("athleteId"), Index(value = ["clientToken"], unique = true)],
)
data class ActivityTimer(
    @PrimaryKey val activityId: Long,
    val athleteId: Long,
    val startElapsedRealtime: Long,
    val startWallTime: Long,
    val bootCount: Int?,
    val durationSeconds: Long? = null,
    val clientToken: String,
    val clockIdentity: String = "test-clock",
)

/** Imported schedule/document. Deleting it never deletes personal records or definitions. */
@Entity(
    tableName = "activity_plans",
    foreignKeys = [ForeignKey(entity = Athlete::class, parentColumns = ["id"], childColumns = ["athleteId"])],
    indices = [Index(value = ["athleteId", "planKey"], unique = true)],
)
data class ActivityPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val athleteId: Long,
    val planKey: String,
    val name: String,
    val documentJson: String,
)
