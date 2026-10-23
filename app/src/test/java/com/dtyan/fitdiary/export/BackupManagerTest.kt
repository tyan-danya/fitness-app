package com.dtyan.fitdiary.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.*
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.data.repo.ActivityRepository
import com.dtyan.fitdiary.data.repo.AndroidActivityClock
import com.dtyan.fitdiary.data.repo.ActivityDefinitionInput
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.MeasurementType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var manager: BackupManager
    private lateinit var archive: File
    private lateinit var photo: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(context)
        settings.update { SettingsStore.Settings(calorieGoal = 2500, aiApiKey = "source-test-key") }
        manager = BackupManager(context, db, settings)
        archive = File(context.cacheDir, "backup-test-${UUID.randomUUID()}.zip")
        photo = File(context.filesDir, "exercise_photos/${UUID.randomUUID()}.jpg")
        photo.parentFile!!.mkdirs()
        // Real 8x8 JPEG bytes, independent of Robolectric's Bitmap.compress shadow.
        photo.writeBytes(Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q=="
        ))
    }
    @After fun tearDown() { db.close(); archive.delete(); photo.delete() }

    /** Opt-in local compatibility check; never package a personal archive as a test fixture. */
    @Test fun validatesLocallyProvidedBackupWhenConfigured() = runTest {
        val path = System.getenv("FITDIARY_BACKUP_CHECK_FILE") ?: return@runTest
        seed()
        val preview = File(path).inputStream().use { manager.inspectArchive(it) }
        assertThat(preview.profiles).isGreaterThan(0)
        manager.restoreBackup(preview)
        manager.writeArchive(archive)
        val roundTrip = archive.inputStream().use { manager.inspectArchive(it) }
        assertThat(roundTrip.profiles).isEqualTo(preview.profiles)
        assertThat(roundTrip.workouts).isEqualTo(preview.workouts)
        assertThat(roundTrip.measurements).isEqualTo(preview.measurements)
        manager.discard(roundTrip)
    }

    private suspend fun seed() {
        db.athleteDao().insert(Athlete(1, "Me"))
        db.athleteDao().insert(Athlete(2, "Friend"))
        db.exerciseDao().insert(Exercise(1, "Bench", "Chest", photoPath = "exercise_photos/${photo.name}"))
        db.workoutDao().insert(Workout(1, 1000, athleteId = 2, groupSessionId = "together"))
        db.workoutSetDao().insert(WorkoutSet(1, 1, 1, 1, 60.0, 10, 2000))
        db.workoutDao().insertPlan(WorkoutExercise(1, 1, 0))
        db.mealDao().insert(Meal(1, 1, 2000, "Saved meal", 300, 10.0, 10.0, 20.0, athleteId = 2))
        db.weightDao().insert(WeightEntry(1, 2000, 80.0, athleteId = 2))
        db.measurementDao().insertAll(listOf(BodyMeasurement(1, 1, 2000, MeasurementType.WAIST, 80.0, athleteId = 2)))
    }

    private fun entries(file: File): Map<String, ByteArray> = buildMap {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output -> entries.forEach { (name, value) ->
            output.putNextEntry(ZipEntry(name)); output.write(value); output.closeEntry()
        } }
        return bytes.toByteArray()
    }

    @Test fun roundTripRestoresAllProfilesActiveWorkoutPlanMeasurementsPhotosAndSafeSettings() = runTest {
        seed()
        settings.setPushUpDailyGoal(45, 1)
        settings.setPushUpDailyGoal(85, 2)
        manager.writeArchive(archive)
        val contents = entries(archive)
        assertThat(contents.getValue("diary.json").toString(Charsets.UTF_8)).doesNotContain("source-test-key")
        assertThat(contents.getValue("diary.json").toString(Charsets.UTF_8)).doesNotContain("aiBaseUrl")
        settings.update { it.copy(calorieGoal = 1800, aiApiKey = "destination-test-key") }
        settings.setPushUpDailyGoal(null, 1)
        settings.setPushUpDailyGoal(10, 2)
        db.mealDao().update(db.mealDao().getByIds(listOf(1), 2).single().copy(name = "Changed"))
        val preview = manager.inspectArchive(archive.inputStream())
        assertThat(preview.profiles).isEqualTo(2)
        manager.restoreBackup(preview)
        assertThat(db.mealDao().getByIds(listOf(1), 2).single().name).isEqualTo("Saved meal")
        assertThat(db.mealDao().getAllOnce(1)).isEmpty()
        assertThat(db.workoutDao().getActiveOnce(2)!!.groupSessionId).isEqualTo("together")
        assertThat(db.workoutDao().plannedExerciseIds(1)).containsExactly(1L)
        assertThat(db.measurementDao().getAllOnce(2).single().valueCm).isEqualTo(80.0)
        assertThat(db.weightDao().getAllOnce(2).single().weightKg).isEqualTo(80.0)
        assertThat(settings.settings.value.calorieGoal).isEqualTo(2500)
        assertThat(settings.settings.value.aiApiKey).isEqualTo("destination-test-key")
        assertThat(settings.observePushUpDailyGoal(1).first()).isEqualTo(45)
        assertThat(settings.observePushUpDailyGoal(2).first()).isEqualTo(85)
        val restoredPhoto = File(context.filesDir, db.exerciseDao().getById(1)!!.photoPath!!)
        assertThat(restoredPhoto.readBytes()).isEqualTo(photo.readBytes())
        assertThat(manager.hasRecoveryCopy()).isTrue()
    }

    @Test fun malformedReferenceAndTraversalAreRejectedBeforeChangingDiary() = runTest {
        seed(); manager.writeArchive(archive)
        val entries = entries(archive).toMutableMap()
        val root = Json.parseToJsonElement(entries.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val tables = root["tables"]!!.jsonObject.toMutableMap()
        val meal = tables.getValue("meals").jsonArray.single().jsonObject.toMutableMap()
        meal["athleteId"] = JsonPrimitive(999L)
        tables["meals"] = JsonArray(listOf(JsonObject(meal)))
        entries["diary.json"] = JsonObject(root.toMutableMap().apply { put("tables", JsonObject(tables)) }).toString().toByteArray()
        assertThat(runCatching { manager.inspectArchive(ByteArrayInputStream(zip(entries))) }.isFailure).isTrue()
        assertThat(runCatching { manager.inspectArchive(ByteArrayInputStream(zip(mapOf("../escape" to byteArrayOf(1))))) }.isFailure).isTrue()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
        assertThat(settings.settings.value.calorieGoal).isEqualTo(2500)
    }

    @Test fun databaseFailureRollsBackDeletesAndInsertsAndPreservesRecovery() = runTest {
        seed(); manager.writeArchive(archive)
        val preview = manager.inspectArchive(archive.inputStream())
        db.mealDao().update(db.mealDao().getByIds(listOf(1), 2).single().copy(name = "Current diary"))
        settings.update { it.copy(calorieGoal = 2700) }
        settings.setPushUpDailyGoal(55, 1)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON meals BEGIN SELECT RAISE(ABORT, 'test restore failure'); END")
        assertThat(runCatching { manager.restoreBackup(preview) }.isFailure).isTrue()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Current diary")
        assertThat(db.workoutSetDao().getForWorkoutOnce(1)).hasSize(1)
        assertThat(settings.settings.value.calorieGoal).isEqualTo(2700)
        assertThat(settings.observePushUpDailyGoal(1).first()).isEqualTo(55)
        assertThat(photo.exists()).isTrue()
        assertThat(manager.hasRecoveryCopy()).isTrue()
        manager.discard(preview)
    }

    @Test fun missingStagedPhotoCannotPartiallyRestoreDiary() = runTest {
        seed(); manager.writeArchive(archive)
        val preview = manager.inspectArchive(archive.inputStream())
        db.mealDao().update(db.mealDao().getByIds(listOf(1), 2).single().copy(name = "Current diary"))
        File(preview.staging, "exercise_photos/${photo.name}").delete()
        assertThat(runCatching { manager.restoreBackup(preview) }.isFailure).isTrue()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Current diary")
        assertThat(photo.exists()).isTrue()
        manager.discard(preview)
    }

    @Test fun programsAndIndependentCompletionSurviveRoundTrip() = runTest {
        seed()
        val programs = TemplateRepository(db)
        val id = programs.create("Friend's plan", listOf(TemplatePlanItem(1, 4, 8, "A note")), athleteId = 2)
        db.openHelper.writableDatabase.execSQL("UPDATE workout_exercises SET targetSets=4,targetReps=8,note='A note',completedAt=3000 WHERE workoutId=1")
        manager.writeArchive(archive)
        programs.delete(id, athleteId = 2)
        db.openHelper.writableDatabase.execSQL("UPDATE workout_exercises SET completedAt=NULL WHERE workoutId=1")
        val preview = manager.inspectArchive(archive.inputStream())
        assertThat(preview.templates).isEqualTo(1)
        manager.restoreBackup(preview)
        val restored = programs.getTemplate(id, athleteId = 2)!!
        assertThat(restored.template.name).isEqualTo("Friend's plan")
        assertThat(restored.items.single().targetSets).isEqualTo(4)
        assertThat(restored.items.single().targetReps).isEqualTo(8)
        assertThat(programs.getTemplate(id, athleteId = 1)).isNull()
        db.openHelper.readableDatabase.query("SELECT completedAt FROM workout_exercises WHERE workoutId=1").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(3000L)
        }
    }

    @Test fun version15BackupUpgradesWithoutLosingOldDiary() = runTest {
        seed(); manager.writeArchive(archive)
        settings.setPushUpDailyGoal(35, 1)
        settings.setPushUpDailyGoal(65, 2)
        val content = entries(archive).toMutableMap()
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val tables = document["tables"]!!.jsonObject.toMutableMap().apply {
            keys.filter { it.startsWith("activity_") }.forEach { remove(it) }
            remove("workout_templates"); remove("template_exercises"); remove("favorite_exercises"); remove("disliked_exercises")
            put("workout_exercises", JsonArray(getValue("workout_exercises").jsonArray.map { row ->
                JsonObject(row.jsonObject.filterKeys { it in setOf("workoutId", "exerciseId", "position") })
            }))
        }
        content["diary.json"] = JsonObject(document.toMutableMap().apply {
            put("schemaVersion", JsonPrimitive(5)); put("tables", JsonObject(tables))
        }).toString().toByteArray(Charsets.UTF_8)
        val programs = TemplateRepository(db)
        programs.create("Current program", listOf(TemplatePlanItem(1)))
        val preview = manager.inspectArchive(ByteArrayInputStream(zip(content)))
        assertThat(preview.templates).isEqualTo(0)
        manager.restoreBackup(preview)
        assertThat(settings.observePushUpDailyGoal(1).first()).isNull()
        assertThat(settings.observePushUpDailyGoal(2).first()).isNull()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
        assertThat(db.workoutDao().plannedExerciseIds(1)).containsExactly(1L)
        db.openHelper.readableDatabase.query("SELECT targetSets,completedAt FROM workout_exercises WHERE workoutId=1").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.isNull(0)).isTrue()
            assertThat(it.isNull(1)).isTrue()
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM workout_templates").use {
            it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(0)
        }
    }

    @Test fun invalidDailyGoalInBackupIsRejectedWithoutChangingCurrentDiaryOrSettings() = runTest {
        seed()
        settings.setPushUpDailyGoal(75, 1)
        settings.setPushUpDailyGoal(125, 2)
        manager.writeArchive(archive)
        val content = entries(archive).toMutableMap()
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val safeSettings = document.getValue("settings").jsonObject.toMutableMap()
        safeSettings["athlete.2.pushUpDailyGoal"] = JsonPrimitive(0)
        content["diary.json"] = JsonObject(document.toMutableMap().apply {
            put("settings", JsonObject(safeSettings))
        }).toString().toByteArray(Charsets.UTF_8)
        val before = settings.exportSafeSettings()
        assertThat(runCatching { manager.inspectArchive(ByteArrayInputStream(zip(content))) }.isFailure).isTrue()
        assertThat(settings.exportSafeSettings()).isEqualTo(before)
        assertThat(settings.observePushUpDailyGoal(1).first()).isEqualTo(75)
        assertThat(settings.observePushUpDailyGoal(2).first()).isEqualTo(125)
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
        assertThat(db.workoutSetDao().getForWorkoutOnce(1)).hasSize(1)
        assertThat(photo.exists()).isTrue()
    }

    @Test fun activityBackupPreservesGoalsScheduleHistoryAndExcludesUnrecordedStopwatch() = runTest {
        seed()
        val activity = ActivityRepository(db, clock = AndroidActivityClock(context))
        val bundles = PlanBundleRepository(db, TemplateRepository(db), activity)
        bundles.importPlan(PlanBundleExchange.parse(examplePlan().toString()), 2)
        val plank = activity.getAllDefinitions(2).first { it.key == "plank" }
        activity.saveEntry(plank.id, ActivityEntryInput(20000, durationSeconds = 18, clientToken = "recorded"), 2)
        activity.startTimer(plank.id, 2)
        manager.writeArchive(archive)
        val document = Json.parseToJsonElement(entries(archive).getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        assertThat(document["tables"]!!.jsonObject["activity_timers"]!!.jsonArray).isEmpty()
        activity.updateGoals(plank.id, listOf(100.0), 2)
        val preview = manager.inspectArchive(archive.inputStream())
        assertThat(preview.activities).isEqualTo(1)
        manager.restoreBackup(preview)
        assertThat(activity.getDefinition(plank.id, 2)!!.goals).containsExactly(10.0, 15.0, 20.0).inOrder()
        assertThat(activity.getAllEntries(2).single().durationSeconds).isEqualTo(18L)
        assertThat(activity.getAllEntries(1)).isEmpty()
        assertThat(db.activityDao().getTimer(plank.id, 2)).isNull()
        assertThat(db.activityDao().getPlanByKey("example-week", 2)).isNotNull()
    }

    @Test fun activityBackupRejectsForeignOwnerAndWrongMetricBeforeReplacement() = runTest {
        seed()
        val activity = ActivityRepository(db, clock = AndroidActivityClock(context))
        val plank = activity.upsertDefinition(ActivityDefinitionInput("plank", "Plank", ActivityKind.DURATION), 2)
        activity.saveEntry(plank, ActivityEntryInput(20000, durationSeconds = 18, clientToken = "recorded"), 2)
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        for (change in listOf("athleteId" to JsonPrimitive(1), "reps" to JsonPrimitive(5))) {
            val tables = document["tables"]!!.jsonObject.toMutableMap()
            tables["activity_entries"] = JsonArray(tables.getValue("activity_entries").jsonArray.map {
                JsonObject(it.jsonObject + change)
            })
            val altered = JsonObject(document + ("tables" to JsonObject(tables)))
            val bad = content + ("diary.json" to altered.toString().toByteArray())
            assertThat(runCatching { manager.inspectArchive(ByteArrayInputStream(zip(bad))) }.isFailure).isTrue()
        }
        assertThat(activity.getAllEntries(2).single().durationSeconds).isEqualTo(18L)
    }

    @Test fun version16BackupUpgradesAndRetainsPrograms() = runTest {
        seed()
        TemplateRepository(db).create("Saved program", listOf(TemplatePlanItem(1)), 2)
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val old = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(6),
            "tables" to JsonObject(preSchema9Tables(document).filterKeys { !it.startsWith("activity_") && it != "favorite_exercises" })))
        val preview = manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to old.toString().toByteArray()))))
        manager.restoreBackup(preview)
        assertThat(preview.templates).isEqualTo(1)
        assertThat(preview.activities).isEqualTo(0)
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
    }

    @Test fun completedExerciseWithoutMatchingActualSetsIsRejectedBeforeRestore() = runTest {
        seed()
        // A real set of the same exercise for another participant must not validate this person's completion.
        db.workoutDao().insert(Workout(id = 2, startedAt = 1000, athleteId = 1))
        db.workoutDao().insertPlan(WorkoutExercise(2, 1, 1))
        manager.writeArchive(archive)
        val content = entries(archive).toMutableMap()
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val tables = document["tables"]!!.jsonObject.toMutableMap()
        tables["workout_exercises"] = JsonArray(tables.getValue("workout_exercises").jsonArray.map { element ->
            val row = element.jsonObject
            if (row["workoutId"]!!.jsonPrimitive.long == 2L)
                JsonObject(row.toMutableMap().apply { put("completedAt", JsonPrimitive(3000L)) })
            else row
        })
        content["diary.json"] = JsonObject(document.toMutableMap().apply { put("tables", JsonObject(tables)) })
            .toString().toByteArray(Charsets.UTF_8)

        val error = runCatching { manager.inspectArchive(ByteArrayInputStream(zip(content))) }.exceptionOrNull()
        assertThat(error?.message).isEqualTo("Выполненное упражнение без записанных подходов")
        assertThat(db.workoutDao().getPlanOnce(2).single().completedAt).isNull()
        assertThat(db.workoutSetDao().getForWorkoutOnce(2)).isEmpty()
        assertThat(db.workoutSetDao().getForWorkoutOnce(1)).hasSize(1)
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
        assertThat(settings.settings.value.calorieGoal).isEqualTo(2500)
    }

    /** Таблицы документа, как их писали 1.5–1.12: без «Не нравится» и без отметки пропуска в плане. */
    private fun preSchema9Tables(document: JsonObject): Map<String, JsonElement> =
        document["tables"]!!.jsonObject.filterKeys { it != "disliked_exercises" }.mapValues { (table, rows) ->
            if (table != "workout_exercises") rows else JsonArray(rows.jsonArray.map { JsonObject(it.jsonObject - "skippedAt") })
        }

    private fun dislikeRow(athleteId: Long, exerciseId: Long, createdAt: Long) = buildJsonObject {
        put("athleteId", athleteId); put("exerciseId", exerciseId); put("createdAt", createdAt)
    }

    @Test fun dislikesAndSkipsSurviveRoundTripPerProfile() = runTest {
        seed()
        db.exerciseDao().insert(Exercise(2, "Squat", "Legs"))
        db.exerciseDao().markDisliked(2, 2, 3000)
        db.exerciseDao().markDisliked(1, 1, 1000)
        db.workoutDao().skipExercise(1, 1, skipped = true, now = 4000)
        manager.writeArchive(archive)
        val document = Json.parseToJsonElement(entries(archive).getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        assertThat(document["tables"]!!.jsonObject.getValue("disliked_exercises").jsonArray.map {
            it.jsonObject["athleteId"]!!.jsonPrimitive.long to it.jsonObject["exerciseId"]!!.jsonPrimitive.long
        }).containsExactly(1L to 1L, 2L to 2L).inOrder()

        db.exerciseDao().deleteDislike(2, 2)
        db.workoutDao().skipExercise(1, 1, skipped = false, now = 5000)
        manager.restoreBackup(manager.inspectArchive(archive.inputStream()))
        assertThat(db.exerciseDao().getDislikedIds(2)).containsExactly(2L)
        assertThat(db.exerciseDao().getDislikedIds(1)).containsExactly(1L)
        assertThat(db.workoutDao().getPlanOnce(1).single().skippedAt).isEqualTo(4000L)
    }

    @Test fun version8BackupKeepsFavoritesAndRestoresWithoutDislikesOrSkips() = runTest {
        seed()
        db.exerciseDao().insert(Exercise(2, "Squat", "Legs"))
        db.exerciseDao().markFavorite(2, 1, 2000)
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        // A schema 8 document (app 1.11–1.12): favorites, but no dislikes and no skip column.
        val v8 = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(8), "tables" to JsonObject(preSchema9Tables(document))))
        // The current diary's marks and skips must not leak into the restored one.
        db.exerciseDao().markDisliked(1, 2, 5000)
        db.workoutDao().skipExercise(1, 1, skipped = true, now = 6000)

        manager.restoreBackup(manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to v8.toString().toByteArray())))))
        assertThat(db.exerciseDao().getFavoriteIds(2)).containsExactly(1L)
        assertThat(db.exerciseDao().getDislikedIds(1)).isEmpty()
        assertThat(db.workoutDao().getPlanOnce(1).single().skippedAt).isNull()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")

        // Fail closed: a schema 8 plan never had a skip column.
        val mixed = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(8),
            "tables" to JsonObject(document["tables"]!!.jsonObject.filterKeys { it != "disliked_exercises" })))
        assertThat(runCatching {
            manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to mixed.toString().toByteArray()))))
        }.exceptionOrNull()?.message).isEqualTo("Некорректная структура старого плана")
    }

    @Test fun inconsistentDislikesOrSkipsAreRejectedBeforeRestore() = runTest {
        seed()
        db.exerciseDao().markFavorite(2, 1, 1000)
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        suspend fun attempt(tables: Map<String, JsonElement>): String? = runCatching {
            val altered = JsonObject(document + ("tables" to JsonObject(tables)))
            manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to altered.toString().toByteArray()))))
        }.exceptionOrNull()?.message
        val cases = listOf(
            listOf(dislikeRow(2, 999, 1000)) to "Отметка «Не нравится» ссылается на отсутствующее упражнение",
            listOf(dislikeRow(999, 1, 1000)) to "Запись без профиля",
            listOf(dislikeRow(1, 1, 1000), dislikeRow(1, 1, 2000)) to "Повторяющаяся отметка «Не нравится»",
            listOf(dislikeRow(1, 1, -1)) to "Некорректное время отметки «Не нравится»",
            listOf(dislikeRow(2, 1, 1000)) to "Упражнение одновременно в избранном и в «Не нравится»",
        )
        for ((rows, message) in cases) {
            val tables = document["tables"]!!.jsonObject.toMutableMap()
            tables["disliked_exercises"] = JsonArray(rows)
            assertThat(attempt(tables)).isEqualTo(message)
        }
        val tables = document["tables"]!!.jsonObject.toMutableMap()
        tables["workout_exercises"] = JsonArray(tables.getValue("workout_exercises").jsonArray.map { element ->
            JsonObject(element.jsonObject + mapOf("completedAt" to JsonPrimitive(3000L), "skippedAt" to JsonPrimitive(3000L)))
        })
        assertThat(attempt(tables)).isEqualTo("Упражнение одновременно выполнено и пропущено")
        assertThat(db.exerciseDao().getFavoriteIds(2)).containsExactly(1L)
        assertThat(db.exerciseDao().getDislikedIds(1)).isEmpty()
    }

    private fun favoriteCreatedAt(athleteId: Long, exerciseId: Long): Long? =
        db.openHelper.readableDatabase.query(
            "SELECT createdAt FROM favorite_exercises WHERE athleteId = ? AND exerciseId = ?", arrayOf<Any>(athleteId, exerciseId),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun favoriteRow(athleteId: Long, exerciseId: Long, createdAt: Long) = buildJsonObject {
        put("athleteId", athleteId); put("exerciseId", exerciseId); put("createdAt", createdAt)
    }

    @Test fun favoritesSurviveRoundTripPerProfileWithOriginalTimes() = runTest {
        seed()
        db.exerciseDao().insert(Exercise(2, "Squat", "Legs"))
        db.exerciseDao().insertFavorite(FavoriteExercise(2, 2, 3000))
        db.exerciseDao().insertFavorite(FavoriteExercise(1, 2, 1000))
        db.exerciseDao().insertFavorite(FavoriteExercise(2, 1, 2000))
        manager.writeArchive(archive)
        val document = Json.parseToJsonElement(entries(archive).getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        assertThat(document["schemaVersion"]!!.jsonPrimitive.int).isEqualTo(9)
        // Deterministic snapshot order: athleteId, exerciseId.
        assertThat(document["tables"]!!.jsonObject.getValue("favorite_exercises").jsonArray.map {
            it.jsonObject["athleteId"]!!.jsonPrimitive.long to it.jsonObject["exerciseId"]!!.jsonPrimitive.long
        }).containsExactly(1L to 2L, 2L to 1L, 2L to 2L).inOrder()

        db.exerciseDao().deleteFavorite(2, 1)
        db.exerciseDao().insertFavorite(FavoriteExercise(1, 1, 4000))
        val preview = manager.inspectArchive(archive.inputStream())
        manager.restoreBackup(preview)
        assertThat(db.exerciseDao().getFavoriteIds(1)).containsExactly(2L)
        assertThat(db.exerciseDao().getFavoriteIds(2)).containsExactly(1L, 2L).inOrder()
        assertThat(favoriteCreatedAt(2, 2)).isEqualTo(3000L)
        assertThat(favoriteCreatedAt(1, 1)).isNull()
    }

    @Test fun version17BackupWithoutFavoritesIsRestoredWithNoFavorites() = runTest {
        seed()
        db.exerciseDao().insert(Exercise(2, "Squat", "Legs"))
        val activity = ActivityRepository(db, clock = AndroidActivityClock(context))
        val plank = activity.upsertDefinition(ActivityDefinitionInput("plank", "Plank", ActivityKind.DURATION), 2)
        activity.saveEntry(plank, ActivityEntryInput(20000, durationSeconds = 18, clientToken = "recorded"), 2)
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        // A schema 7 document (app 1.7–1.10) has every current table except favorites.
        val v7Tables = preSchema9Tables(document).filterKeys { it != "favorite_exercises" }
        val v7 = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(7), "tables" to JsonObject(v7Tables)))
        // The current diary's favorites must not leak into the restored one.
        db.exerciseDao().insertFavorite(FavoriteExercise(1, 2, 1000))
        db.exerciseDao().insertFavorite(FavoriteExercise(2, 1, 2000))

        val preview = manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to v7.toString().toByteArray()))))
        assertThat(preview.profiles).isEqualTo(2)
        assertThat(preview.activities).isEqualTo(1)
        manager.restoreBackup(preview)
        assertThat(db.exerciseDao().getFavoriteIds(1)).isEmpty()
        assertThat(db.exerciseDao().getFavoriteIds(2)).isEmpty()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
        assertThat(db.workoutDao().plannedExerciseIds(1)).containsExactly(1L)
        assertThat(activity.getAllEntries(2).single().durationSeconds).isEqualTo(18L)
        assertThat(db.exerciseDao().getById(2)!!.name).isEqualTo("Squat")

        // Fail closed: a schema 7 document must carry exactly schema 7 tables.
        val mixed = JsonObject(document + ("schemaVersion" to JsonPrimitive(7)))
        assertThat(runCatching {
            manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to mixed.toString().toByteArray()))))
        }.exceptionOrNull()?.message).isEqualTo("Неполный набор таблиц старой копии")
    }

    @Test fun favoriteWithMissingExerciseOrProfileIsRejectedBeforeRestore() = runTest {
        seed()
        db.exerciseDao().insertFavorite(FavoriteExercise(2, 1, 1000))
        manager.writeArchive(archive)
        val content = entries(archive)
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val cases = listOf(
            listOf(favoriteRow(2, 999, 1000)) to "Избранное ссылается на отсутствующее упражнение",
            listOf(favoriteRow(999, 1, 1000)) to "Запись без профиля",
            listOf(favoriteRow(0, 1, 1000)) to "Некорректный профиль",
            listOf(favoriteRow(2, 1, 1000), favoriteRow(2, 1, 2000)) to "Повторяющееся избранное упражнение",
            listOf(favoriteRow(2, 1, -1)) to "Некорректное время добавления в избранное",
            listOf(JsonObject(favoriteRow(2, 1, 1000) + ("id" to JsonPrimitive(1)))) to "Структура записи не соответствует версии приложения",
        )
        for ((rows, message) in cases) {
            val tables = document["tables"]!!.jsonObject.toMutableMap()
            tables["favorite_exercises"] = JsonArray(rows)
            val altered = JsonObject(document + ("tables" to JsonObject(tables)))
            val error = runCatching {
                manager.inspectArchive(ByteArrayInputStream(zip(content + ("diary.json" to altered.toString().toByteArray()))))
            }.exceptionOrNull()
            assertThat(error?.message).isEqualTo(message)
        }
        assertThat(db.exerciseDao().getFavoriteIds(2)).containsExactly(1L)
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Saved meal")
    }
}
