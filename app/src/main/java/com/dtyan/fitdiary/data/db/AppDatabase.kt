package com.dtyan.fitdiary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Athlete::class, Exercise::class, Workout::class, WorkoutSet::class, Meal::class, WeightEntry::class, BodyMeasurement::class, WorkoutExercise::class, WorkoutTemplate::class, TemplateExercise::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun athleteDao(): AthleteDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun mealDao(): MealDao
    abstract fun weightDao(): WeightDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun templateDao(): TemplateDao

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

        /** v5: локальные спортсмены, совместные занятия и планы без фиктивных подходов. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `athletes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isArchived` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO athletes (id, name, isArchived) VALUES (1, 'Я', 0)")
                for (table in listOf("workouts", "meals", "weight_entries", "body_measurements")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN athleteId INTEGER NOT NULL DEFAULT 1")
                }
                db.execSQL("ALTER TABLE workouts ADD COLUMN groupSessionId TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN weightStepKg REAL NOT NULL DEFAULT 2.5")
                db.execSQL("ALTER TABLE weight_entries ADD COLUMN sourceWorkoutId INTEGER")
                db.execSQL("CREATE INDEX index_workouts_athleteId ON workouts(athleteId)")
                db.execSQL("CREATE INDEX index_workouts_groupSessionId ON workouts(groupSessionId)")
                db.execSQL("CREATE INDEX index_meals_athleteId_epochDay ON meals(athleteId, epochDay)")
                db.execSQL("CREATE INDEX index_weight_entries_athleteId ON weight_entries(athleteId)")
                db.execSQL("CREATE INDEX index_weight_entries_sourceWorkoutId ON weight_entries(sourceWorkoutId)")
                db.execSQL("CREATE INDEX index_body_measurements_athleteId_epochDay ON body_measurements(athleteId, epochDay)")

                // Link only an unambiguous historical weigh-in; never guess for manual/duplicate entries.
                db.execSQL("""UPDATE weight_entries SET sourceWorkoutId = (
                    SELECT w.id FROM workouts w WHERE w.endedAt = weight_entries.timestamp
                        AND w.bodyWeightKg = weight_entries.weightKg
                    ) WHERE fromWorkout = 1
                    AND (SELECT COUNT(*) FROM workouts w WHERE w.endedAt = weight_entries.timestamp
                        AND w.bodyWeightKg = weight_entries.weightKg) = 1
                    AND (SELECT COUNT(*) FROM weight_entries other WHERE other.timestamp = weight_entries.timestamp
                        AND other.weightKg = weight_entries.weightKg AND other.fromWorkout = 1) = 1""")

                // Repair old COUNT+1 duplicates deterministically before adding the invariant.
                db.execSQL("""UPDATE workout_sets SET setIndex = (SELECT COUNT(*) FROM workout_sets other
                    WHERE other.workoutId = workout_sets.workoutId AND other.exerciseId = workout_sets.exerciseId
                      AND (other.completedAt < workout_sets.completedAt
                        OR (other.completedAt = workout_sets.completedAt AND other.id <= workout_sets.id)))""")
                db.execSQL("CREATE UNIQUE INDEX index_workout_sets_workoutId_exerciseId_setIndex ON workout_sets(workoutId, exerciseId, setIndex)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS workout_exercises (
                    workoutId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL,
                    PRIMARY KEY(workoutId, exerciseId),
                    FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX index_workout_exercises_exerciseId ON workout_exercises(exerciseId)")
                db.execSQL("""INSERT INTO workout_exercises(workoutId, exerciseId, position)
                    SELECT workoutId, exerciseId, MIN(id) FROM workout_sets GROUP BY workoutId, exerciseId""")
            }
        }

        /** v6: личные шаблоны и независимые цели/отметки выполнения в плане занятия. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_exercises ADD COLUMN targetSets INTEGER")
                db.execSQL("ALTER TABLE workout_exercises ADD COLUMN targetReps INTEGER")
                db.execSQL("ALTER TABLE workout_exercises ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE workout_exercises ADD COLUMN completedAt INTEGER")
                db.execSQL("""CREATE TABLE workout_templates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    athleteId INTEGER NOT NULL DEFAULT 1, name TEXT NOT NULL,
                    FOREIGN KEY(athleteId) REFERENCES athletes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX index_workout_templates_athleteId ON workout_templates(athleteId)")
                db.execSQL("""CREATE TABLE template_exercises (
                    templateId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL,
                    targetSets INTEGER NOT NULL DEFAULT 3, targetReps INTEGER, note TEXT,
                    PRIMARY KEY(templateId, exerciseId),
                    FOREIGN KEY(templateId) REFERENCES workout_templates(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX index_template_exercises_exerciseId ON template_exercises(exerciseId)")
            }
        }

        /** Колбэк с предзаполнением каталога упражнений. */
        fun seedCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT OR IGNORE INTO athletes (id, name, isArchived) VALUES (1, 'Я', 0)")
                ExerciseSeed.seed(db)
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fitdiary.db")
                .addCallback(seedCallback())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
