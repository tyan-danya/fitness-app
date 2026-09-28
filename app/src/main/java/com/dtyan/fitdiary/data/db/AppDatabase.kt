package com.dtyan.fitdiary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Exercise::class, Workout::class, WorkoutSet::class, Meal::class, WeightEntry::class, BodyMeasurement::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun mealDao(): MealDao
    abstract fun weightDao(): WeightDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        /** v2: у упражнений появились вид оборудования и фото тренажёра. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN equipment TEXT NOT NULL DEFAULT 'Другое'")
                db.execSQL("ALTER TABLE exercises ADD COLUMN photoPath TEXT")
                ExerciseSeed.backfillEquipment(db)
            }
        }

        /**
         * v3: у приёмов пищи появились тип (завтрак/обед/ужин/перекус), вес порции,
         * значения «на 100 г» и флаг «ждёт расчёта». Тип старых записей
         * восстанавливается по часу добавления в локальной зоне устройства.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN mealType TEXT NOT NULL DEFAULT 'SNACK'")
                db.execSQL("ALTER TABLE meals ADD COLUMN servingG REAL")
                db.execSQL("ALTER TABLE meals ADD COLUMN caloriesPer100 REAL")
                db.execSQL("ALTER TABLE meals ADD COLUMN proteinPer100 REAL")
                db.execSQL("ALTER TABLE meals ADD COLUMN fatPer100 REAL")
                db.execSQL("ALTER TABLE meals ADD COLUMN carbsPer100 REAL")
                db.execSQL("ALTER TABLE meals ADD COLUMN needsEstimate INTEGER NOT NULL DEFAULT 0")
                // Часы границ совпадают с MealType.forHour: 4–10 завтрак, 11–15 обед, 16–21 ужин, иначе перекус.
                db.execSQL(
                    """UPDATE meals SET mealType = CASE
                         WHEN CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) BETWEEN 4 AND 10 THEN 'BREAKFAST'
                         WHEN CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) BETWEEN 11 AND 15 THEN 'LUNCH'
                         WHEN CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) BETWEEN 16 AND 21 THEN 'DINNER'
                         ELSE 'SNACK' END"""
                )
            }
        }

        /** v4: таблица замеров тела (талия, грудь, руки…). */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `body_measurements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                        "`valueCm` REAL NOT NULL, `note` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_epochDay` ON `body_measurements` (`epochDay`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_type` ON `body_measurements` (`type`)")
            }
        }

        /** Колбэк с предзаполнением каталога упражнений. */
        fun seedCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ExerciseSeed.seed(db)
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fitdiary.db")
                .addCallback(seedCallback())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
