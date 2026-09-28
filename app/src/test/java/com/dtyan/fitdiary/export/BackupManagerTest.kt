package com.dtyan.fitdiary.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.*
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.domain.MeasurementType
import com.google.common.truth.Truth.assertThat
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
        manager.writeArchive(archive)
        val contents = entries(archive)
        assertThat(contents.getValue("diary.json").toString(Charsets.UTF_8)).doesNotContain("source-test-key")
        assertThat(contents.getValue("diary.json").toString(Charsets.UTF_8)).doesNotContain("aiBaseUrl")
        settings.update { it.copy(calorieGoal = 1800, aiApiKey = "destination-test-key") }
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
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON meals BEGIN SELECT RAISE(ABORT, 'test restore failure'); END")
        assertThat(runCatching { manager.restoreBackup(preview) }.isFailure).isTrue()
        assertThat(db.mealDao().getAllOnce(2).single().name).isEqualTo("Current diary")
        assertThat(db.workoutSetDao().getForWorkoutOnce(1)).hasSize(1)
        assertThat(settings.settings.value.calorieGoal).isEqualTo(2700)
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
        val content = entries(archive).toMutableMap()
        val document = Json.parseToJsonElement(content.getValue("diary.json").toString(Charsets.UTF_8)).jsonObject
        val tables = document["tables"]!!.jsonObject.toMutableMap().apply {
            remove("workout_templates"); remove("template_exercises")
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
}
