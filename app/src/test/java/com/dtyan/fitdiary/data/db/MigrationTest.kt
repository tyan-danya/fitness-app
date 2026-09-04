package com.dtyan.fitdiary.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.domain.MealType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import org.robolectric.RobolectricTestRunner

/**
 * Тесты миграций 1→2 и 2→3.
 *
 * MigrationTestHelper под Robolectric не работает: AGP не кладёт assets
 * test-sourceSet-а в android_merged_assets / apk-for-local-test, поэтому
 * helper не находит "com.dtyan.fitdiary.data.db.AppDatabase/1.json".
 * Вместо него — fallback: БД версии 1 собирается вручную (CREATE TABLE
 * дословно из app/schemas/.../1.json) на FrameworkSQLiteOpenHelperFactory,
 * затем прогоняется настоящая AppDatabase.MIGRATION_1_2.
 *
 * Валидация схемы двухслойная:
 *  - migrate_...: MIGRATION_1_2.migrate(db) напрямую + PRAGMA table_info + SELECT;
 *  - roomOpens_...: тот же v1-файл открывает настоящая Room с addMigrations.
 *    room_master_table в файле нет, поэтому Room после миграции выполняет
 *    сгенерированный onValidateSchema (строгая сверка со схемой версии 2 —
 *    эквивалент runMigrationsAndValidate), а данные читаются типизированными DAO.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** createSql дословно из app/schemas/com.dtyan.fitdiary.data.db.AppDatabase/1.json. */
        val V1_SCHEMA_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `muscleGroup` TEXT NOT NULL, `isCustom` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `workouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `bodyWeightKg` REAL, `note` TEXT)",
            "CREATE TABLE IF NOT EXISTS `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `setIndex` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `reps` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_workout_sets_workoutId` ON `workout_sets` (`workoutId`)",
            "CREATE INDEX IF NOT EXISTS `index_workout_sets_exerciseId` ON `workout_sets` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `meals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `name` TEXT NOT NULL, `calories` INTEGER NOT NULL, `proteinG` REAL NOT NULL, `fatG` REAL NOT NULL, `carbsG` REAL NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_meals_epochDay` ON `meals` (`epochDay`)",
            "CREATE TABLE IF NOT EXISTS `weight_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `fromWorkout` INTEGER NOT NULL)",
        )
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openHelpers = mutableListOf<SupportSQLiteOpenHelper>()

    @After
    fun tearDown() {
        openHelpers.forEach { it.close() }
    }

    /**
     * Создаёт файл БД версии 1 и наполняет данными «до обновления»:
     * два сидовых упражнения, кастомное, кастомное с сидовым именем,
     * завершённая тренировка с подходом и приём пищи.
     */
    private fun createV1DatabaseWithData(): SupportSQLiteDatabase {
        context.deleteDatabase(TEST_DB)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_SCHEMA_SQL.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("В тесте версия фиксирована: $oldVersion→$newVersion не ожидается")
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        openHelpers += helper
        return helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO exercises (id, name, muscleGroup, isCustom, isArchived) VALUES (1, ?, ?, 0, 0)",
                arrayOf<Any>("Жим ногами", "Ноги"),
            )
            execSQL(
                "INSERT INTO exercises (id, name, muscleGroup, isCustom, isArchived) VALUES (2, ?, ?, 0, 0)",
                arrayOf<Any>("Тяга верхнего блока", "Спина"),
            )
            execSQL(
                "INSERT INTO exercises (id, name, muscleGroup, isCustom, isArchived) VALUES (3, ?, ?, 1, 0)",
                arrayOf<Any>("Моя тяга", "Спина"),
            )
            // Кастомное с «сидовым» именем — backfill не должен его трогать (WHERE isCustom = 0).
            execSQL(
                "INSERT INTO exercises (id, name, muscleGroup, isCustom, isArchived) VALUES (4, ?, ?, 1, 0)",
                arrayOf<Any>("Жим ногами", "Ноги"),
            )
            execSQL("INSERT INTO workouts (id, startedAt, endedAt, bodyWeightKg, note) VALUES (1, 1000, 5000, 82.5, NULL)")
            execSQL("INSERT INTO workout_sets (id, workoutId, exerciseId, setIndex, weightKg, reps, completedAt) VALUES (1, 1, 1, 1, 100.0, 5, 1500)")
            execSQL(
                "INSERT INTO meals (id, epochDay, timestamp, name, calories, proteinG, fatG, carbsG) VALUES (1, 20000, 123456, ?, 650, 45.0, 20.0, 10.0)",
                arrayOf<Any>("Курица"),
            )
        }
    }

    /** Строка PRAGMA table_info: тип, NOT NULL, значение по умолчанию. */
    private data class ColumnInfo(val type: String, val notNull: Boolean, val default: String?)

    private fun SupportSQLiteDatabase.tableInfo(table: String): Map<String, ColumnInfo> =
        query("PRAGMA table_info(`$table`)").use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(
                        c.getString(c.getColumnIndexOrThrow("name")),
                        ColumnInfo(
                            type = c.getString(c.getColumnIndexOrThrow("type")),
                            notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                            default = c.getColumnIndexOrThrow("dflt_value")
                                .let { if (c.isNull(it)) null else c.getString(it) },
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.exerciseRow(id: Long): Pair<String, String?> =
        query("SELECT equipment, photoPath FROM exercises WHERE id = $id").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            c.getString(0) to (if (c.isNull(1)) null else c.getString(1))
        }

    @Test
    fun migrate_addsColumns_backfillsSeedEquipment_keepsOldData() {
        val db = createV1DatabaseWithData()
        assertThat(db.tableInfo("exercises")).doesNotContainKey("equipment")

        AppDatabase.MIGRATION_1_2.migrate(db)

        // Схема: новые колонки с правильными типом/NOT NULL/дефолтом.
        val columns = db.tableInfo("exercises")
        val equipment = columns.getValue("equipment")
        assertThat(equipment.type).isEqualTo("TEXT")
        assertThat(equipment.notNull).isTrue()
        assertThat(equipment.default).isEqualTo("'Другое'")
        val photoPath = columns.getValue("photoPath")
        assertThat(photoPath.type).isEqualTo("TEXT")
        assertThat(photoPath.notNull).isFalse()
        assertThat(photoPath.default).isNull()

        // Backfill: сидовые получили вид оборудования из каталога.
        val (legPressEquipment, legPressPhoto) = db.exerciseRow(1)
        assertThat(legPressEquipment).isEqualTo("Тренажёр")
        assertThat(legPressPhoto).isNull()
        assertThat(db.exerciseRow(2).first).isEqualTo("Блок")

        // Кастомные — дефолт «Другое» и без фото, даже если имя совпало с сидовым.
        val (customEquipment, customPhoto) = db.exerciseRow(3)
        assertThat(customEquipment).isEqualTo("Другое")
        assertThat(customPhoto).isNull()
        assertThat(db.exerciseRow(4).first).isEqualTo("Другое")

        // Старые данные пережили миграцию.
        db.query("SELECT workoutId, exerciseId, setIndex, weightKg, reps, completedAt FROM workout_sets").use { c ->
            assertThat(c.count).isEqualTo(1)
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1L)
            assertThat(c.getLong(1)).isEqualTo(1L)
            assertThat(c.getInt(2)).isEqualTo(1)
            assertThat(c.getDouble(3)).isEqualTo(100.0)
            assertThat(c.getInt(4)).isEqualTo(5)
            assertThat(c.getLong(5)).isEqualTo(1500L)
        }
        db.query("SELECT name, calories, proteinG FROM meals").use { c ->
            assertThat(c.count).isEqualTo(1)
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Курица")
            assertThat(c.getInt(1)).isEqualTo(650)
            assertThat(c.getDouble(2)).isEqualTo(45.0)
        }
        db.query("SELECT startedAt, endedAt, bodyWeightKg FROM workouts").use { c ->
            assertThat(c.count).isEqualTo(1)
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1000L)
            assertThat(c.getLong(1)).isEqualTo(5000L)
            assertThat(c.getDouble(2)).isEqualTo(82.5)
        }
    }

    @Test
    fun roomOpens_v1File_runsMigration_validatesSchema_andReadsViaDao() = runTest {
        // Файл версии 1 с данными; room_master_table отсутствует, поэтому Room
        // после MIGRATION_1_2 прогонит сгенерированный onValidateSchema —
        // строгую сверку фактической схемы со схемой версии 2.
        createV1DatabaseWithData()
        openHelpers.removeAt(openHelpers.lastIndex).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val legPress = db.exerciseDao().getById(1)!!
            assertThat(legPress.equipment).isEqualTo("Тренажёр")
            assertThat(legPress.photoPath).isNull()
            assertThat(legPress.isCustom).isFalse()

            val custom = db.exerciseDao().getById(3)!!
            assertThat(custom.equipment).isEqualTo("Другое")
            assertThat(custom.photoPath).isNull()

            // Старые данные читаются DAO, включая JOIN с новым photoPath.
            val sets = db.workoutSetDao().getForWorkoutOnce(1)
            assertThat(sets).hasSize(1)
            assertThat(sets.single().weightKg).isEqualTo(100.0)
            assertThat(sets.single().reps).isEqualTo(5)
            assertThat(sets.single().exerciseName).isEqualTo("Жим ногами")
            assertThat(sets.single().photoPath).isNull()

            val meals = db.mealDao().getForDayOnce(20000)
            assertThat(meals).hasSize(1)
            assertThat(meals.single().name).isEqualTo("Курица")
            assertThat(meals.single().calories).isEqualTo(650)
            // v3: тип восстановлен по часу timestamp, новые поля пустые, флаг расчёта снят
            assertThat(meals.single().mealType).isEqualTo(MealType.forTimestamp(123456L))
            assertThat(meals.single().servingG).isNull()
            assertThat(meals.single().per100).isNull()
            assertThat(meals.single().needsEstimate).isFalse()
        } finally {
            db.close()
        }
    }

    // ---------- 2 → 3: приёмы пищи ----------

    /** Схема версии 2 (после MIGRATION_1_2) — meals не менялась, exercises уже с equipment/photoPath. */
    private fun createV2DatabaseWithMeals(timestamps: List<Long>): SupportSQLiteDatabase {
        context.deleteDatabase(TEST_DB)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_SCHEMA_SQL.forEach(db::execSQL)
                    db.execSQL("ALTER TABLE exercises ADD COLUMN equipment TEXT NOT NULL DEFAULT 'Другое'")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN photoPath TEXT")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("В тесте версия фиксирована: $oldVersion→$newVersion не ожидается")
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        openHelpers += helper
        return helper.writableDatabase.apply {
            timestamps.forEachIndexed { i, ts ->
                execSQL(
                    "INSERT INTO meals (id, epochDay, timestamp, name, calories, proteinG, fatG, carbsG) VALUES (${i + 1}, 20000, $ts, ?, 100, 1.0, 2.0, 3.0)",
                    arrayOf<Any>("Блюдо ${i + 1}"),
                )
            }
        }
    }

    private fun localMillis(hour: Int): Long =
        LocalDate.of(2026, 9, 4).atTime(hour, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun migrate2to3_addsMealColumns_andBackfillsTypeByLocalHour() {
        // 08:30 завтрак, 13:30 обед, 19:30 ужин, 23:30 перекус — в локальной зоне устройства
        val db = createV2DatabaseWithMeals(listOf(localMillis(8), localMillis(13), localMillis(19), localMillis(23)))
        assertThat(db.tableInfo("meals")).doesNotContainKey("mealType")

        AppDatabase.MIGRATION_2_3.migrate(db)

        val columns = db.tableInfo("meals")
        val mealType = columns.getValue("mealType")
        assertThat(mealType.type).isEqualTo("TEXT")
        assertThat(mealType.notNull).isTrue()
        assertThat(mealType.default).isEqualTo("'SNACK'")
        for (nullable in listOf("servingG", "caloriesPer100", "proteinPer100", "fatPer100", "carbsPer100")) {
            val c = columns.getValue(nullable)
            assertThat(c.type).isEqualTo("REAL")
            assertThat(c.notNull).isFalse()
        }
        val needs = columns.getValue("needsEstimate")
        assertThat(needs.type).isEqualTo("INTEGER")
        assertThat(needs.notNull).isTrue()
        assertThat(needs.default).isEqualTo("0")

        db.query("SELECT id, mealType, servingG, needsEstimate, calories FROM meals ORDER BY id").use { c ->
            val types = mutableListOf<String>()
            while (c.moveToNext()) {
                types += c.getString(1)
                assertThat(c.isNull(2)).isTrue()
                assertThat(c.getInt(3)).isEqualTo(0)
                assertThat(c.getInt(4)).isEqualTo(100) // старые данные целы
            }
            assertThat(types).containsExactly("BREAKFAST", "LUNCH", "DINNER", "SNACK").inOrder()
        }
    }

    @Test
    fun roomOpens_v2File_runsMigration2to3_validatesSchema_andReadsViaDao() = runTest {
        createV2DatabaseWithMeals(listOf(localMillis(13)))
        openHelpers.removeAt(openHelpers.lastIndex).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val meal = db.mealDao().getForDayOnce(20000).single()
            assertThat(meal.mealType).isEqualTo(MealType.LUNCH)
            assertThat(meal.needsEstimate).isFalse()
            assertThat(meal.servingG).isNull()

            // Новые DAO-запросы работают на мигрированной схеме
            db.mealDao().setMealType(meal.id, MealType.DINNER)
            assertThat(db.mealDao().getByIds(listOf(meal.id)).single().mealType).isEqualTo(MealType.DINNER)
            assertThat(db.mealDao().getPendingEstimatesOnce()).isEmpty()
        } finally {
            db.close()
        }
    }
}
