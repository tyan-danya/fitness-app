package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.util.Locale
import org.junit.Test

class ExerciseGuidesTest {
    @Test fun everySeedHasItsOwnSpecificGuideRegardlessOfDatabaseId() {
        val keys = ExerciseSeed.EXERCISES.mapIndexed { index, seed ->
            val guide = ExerciseGuides.forExercise(Exercise(
                id = 1000L + index, name = seed.name, muscleGroup = seed.muscleGroup,
                equipment = seed.equipment, isCustom = true, photoPath = "personal-photo.jpg",
            ))
            assertThat(guide.isGeneric).isFalse()
            assertThat(guide.title).isEqualTo(seed.name)
            assertThat(guide.setup).isNotEmpty()
            assertThat(guide.movement).isNotEmpty()
            assertThat(guide.mistakes).isNotEmpty()
            assertThat(guide.sources).isNotEmpty()
            guide.key
        }
        assertThat(keys).hasSize(26)
        assertThat(keys.distinct()).hasSize(26)
    }

    @Test fun exactPlanMovementsAndConservativeAliasesStaySeparate() {
        val examples = listOf(
            Triple("Подъём на носки с опорой", "Своё тело", "supported_calf_raise"),
            Triple("Тяга в тренажёре с опорой грудью", "Тренажёр", "chest_supported_row"),
            Triple("Вставание со скамьи", "Своё тело", "bench_sit_to_stand"),
            Triple("Ягодичный мост без веса", "Своё тело", "glute_bridge"),
            Triple("Dead bug — поочерёдное выпрямление", "Своё тело", "dead_bug"),
            Triple("БИЦЕПС-ТРЕНАЖЕР", "Тренажёр", "machine_biceps_curl"),
            Triple("Сгибание рук в тренажёре", "Тренажёр", "machine_biceps_curl"),
        )
        examples.forEach { (name, equipment, key) ->
            assertThat(ExerciseGuides.resolve(name, equipment).key).isEqualTo(key)
        }
        assertThat(ExerciseGuides.resolve("Тяга горизонтального блока", "Блок").key).isEqualTo("seated_cable_row")
        assertThat(ExerciseGuides.resolve("Тяга штанги в наклоне", "Штанга").key).isEqualTo("bent_over_row")
    }

    @Test fun normalizationAcceptsTypographyButDoesNotEraseVariationWords() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(ExerciseGuides.resolve("  ЖИМ\u00a0ШТАНГИ  ЛЕЖА  ", "ШТАНГА").key)
                .isEqualTo("barbell_bench_press")
            assertThat(ExerciseGuides.resolve("Dead bug - поочередное выпрямление", "Свой вес").key)
                .isEqualTo("dead_bug")
            assertThat(ExerciseGuides.resolve("Жим штанги лёжа на наклонной скамье", "Штанга").isGeneric).isTrue()
            assertThat(ExerciseGuides.resolve("Жим штанги лёжа узким хватом", "Штанга").isGeneric).isTrue()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun ambiguousNamesAndContradictoryEquipmentDoNotGuessTheMovement() {
        listOf("плечи", "Нижний трицепс", "Сведение рук двойное", "Жим", "Тяга", "Жим лёжа").forEach { name ->
            val guide = ExerciseGuides.resolve(name, "Тренажёр")
            assertThat(guide.key).isEqualTo("equipment_machine")
            assertThat(guide.isGeneric).isTrue()
            assertThat(guide.equipmentDescription).contains("не определена")
        }
        assertThat(ExerciseGuides.resolve("Жим штанги лёжа", "Гантели").key).isEqualTo("equipment_dumbbells")
        assertThat(ExerciseGuides.resolve("Жим штанги лёжа").key).isEqualTo("barbell_bench_press")
        assertThat(ExerciseGuides.resolve("Моё упражнение", "Неизвестный тип").key).isEqualTo("equipment_other")
        assertThat(ExerciseGuides.resolve("Моё упражнение", "Блок").key).isEqualTo("equipment_cable")
    }

    @Test fun allDefaultAndImportedActivityVariantsHaveDistinctGuides() {
        val activities = listOf(
            Triple("walking", "Ходьба", ActivityKind.WALK),
            Triple("plank", "Планка", ActivityKind.DURATION),
            Triple("pushup_standard", "Отжимания — обычная постановка", ActivityKind.REPS),
            Triple("pushup_wide", "Отжимания — широкая постановка", ActivityKind.REPS),
            Triple("pushup_narrow", "Отжимания — узкая постановка", ActivityKind.REPS),
            Triple("pushup_knees", "Отжимания — с колен", ActivityKind.REPS),
            Triple("pushup_incline", "Отжимания от высокой опоры", ActivityKind.REPS),
            Triple("pushup_low", "Отжимания — ладони ближе к поясу", ActivityKind.REPS),
            Triple("pushup_high", "Отжимания — ладони ближе к голове", ActivityKind.REPS),
            Triple("pullup_assisted", "Подтягивания с противовесом", ActivityKind.REPS),
            Triple("dip_assisted", "Брусья с противовесом", ActivityKind.REPS),
            Triple("bench_dip_reverse", "Обратные отжимания от скамьи", ActivityKind.REPS),
        )
        val keys = activities.map { (key, name, kind) ->
            val definition = ActivityDefinition(
                athleteId = 42, key = key, name = name, kind = kind,
                goalMetric = kind.defaultMetric, goalsJson = "[]",
            )
            assertThat(ExerciseGuides.forActivity(definition).key).isEqualTo(key)
            assertThat(ExerciseGuides.forActivity(key, name).key).isEqualTo(key)
            key
        }
        assertThat(keys.distinct()).hasSize(12)
    }

    @Test fun reservedActivityKeyCannotMisidentifyRenamedOrDifferentKindActivity() {
        assertThat(ExerciseGuides.forActivity("plank", "Бег").isGeneric).isTrue()
        assertThat(ExerciseGuides.forActivity("pushup_high", "Отжимания от высокой опоры").isGeneric).isTrue()
        assertThat(ExerciseGuides.forActivity("custom", "Плечи").isGeneric).isTrue()
        val changed = ActivityDefinition(athleteId = 1, key = "plank", name = "Планка",
            kind = ActivityKind.WALK, goalMetric = ActivityMetric.DURATION_SECONDS, goalsJson = "[]")
        assertThat(ExerciseGuides.forActivity(changed).isGeneric).isTrue()
    }

    @Test fun catalogKeysAndSourceLinksAreSuitableForOfflineArtworkAndExternalLinks() {
        assertThat(ExerciseGuides.all).hasSize(44)
        assertThat(ExerciseGuides.all.map { it.key }.distinct()).hasSize(44)
        ExerciseGuides.all.forEach { guide ->
            assertThat(guide.key.matches(Regex("[a-z][a-z0-9_]*"))).isTrue()
            assertThat(guide.equipmentTitle).isNotEmpty()
            assertThat(guide.equipmentDescription).isNotEmpty()
            guide.sources.forEach { source ->
                val uri = URI(source.url)
                assertThat(uri.scheme).isEqualTo("https")
                assertThat(uri.host).isNotEmpty()
                assertThat(source.title).isNotEmpty()
            }
        }
    }
}
