package com.dtyan.fitdiary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Exercise::class, Workout::class, WorkoutSet::class, Meal::class, WeightEntry::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun mealDao(): MealDao
    abstract fun weightDao(): WeightDao

    companion object {
        /** v2: у упражнений появились вид оборудования и фото тренажёра. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN equipment TEXT NOT NULL DEFAULT 'Другое'")
                db.execSQL("ALTER TABLE exercises ADD COLUMN photoPath TEXT")
                ExerciseSeed.backfillEquipment(db)
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
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
