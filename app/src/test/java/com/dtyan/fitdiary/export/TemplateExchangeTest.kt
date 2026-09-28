package com.dtyan.fitdiary.export

import com.google.common.truth.Truth.assertThat
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.TemplateDetails
import com.dtyan.fitdiary.data.db.TemplateExerciseInfo
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import java.io.ByteArrayInputStream
import org.junit.Test

class TemplateExchangeTest {
    private val valid = """{"format":"fitdiary-template/1","name":"Моя программа","exercises":[
        {"name":"Жим","muscleGroup":"Грудь","equipment":"Штанга","targetSets":3,"targetReps":8,"note":"Свой рабочий вес"},
        {"name":"Тяга","muscleGroup":"Спина"}]}"""

    @Test fun parsesOrderedPortablePlanWithOptionalTargets() {
        val preview = TemplateExchange.parse(ByteArrayInputStream(("\uFEFF" + valid).toByteArray()))
        assertThat(preview.name).isEqualTo("Моя программа")
        assertThat(preview.exercises.map { it.name }).containsExactly("Жим", "Тяга").inOrder()
        assertThat(preview.exercises.first().targetReps).isEqualTo(8)
        assertThat(preview.exercises.last().targetSets).isEqualTo(3)
        assertThat(preview.exercises.last().targetReps).isNull()
    }

    @Test fun refusesForeignFormatAndEmbeddedLocalIdentifiers() {
        assertThat(runCatching { TemplateExchange.parse(valid.replace("template/1", "template/999")) }.isFailure).isTrue()
        assertThat(runCatching { TemplateExchange.parse(valid.replace("\"targetSets\":3", "\"id\":55,\"targetSets\":3")) }.isFailure).isTrue()
        assertThat(runCatching { TemplateExchange.parse(valid.replace("\"targetSets\":3", "\"photoPath\":\"../secret\",\"targetSets\":3")) }.isFailure).isTrue()
    }

    @Test fun invalidNumbersAndDuplicatesCannotBecomeAPreview() {
        listOf("0", "-1", "3.5", "\"3\"", "null", "101").forEach { invalid ->
            assertThat(runCatching { TemplateExchange.parse(valid.replace("\"targetSets\":3", "\"targetSets\":$invalid")) }.isFailure).isTrue()
        }
        val duplicate = """{"format":"fitdiary-template/1","name":"Test","exercises":[
            {"name":" Жим ","muscleGroup":"Грудь"},{"name":"жим","muscleGroup":"грудь"}]}"""
        assertThat(runCatching { TemplateExchange.parse(duplicate) }.isFailure).isTrue()
    }

    @Test fun emptyProgramsAndMissingExerciseNamesAreRejected() {
        assertThat(runCatching { TemplateExchange.parse("""{"format":"fitdiary-template/1","name":"Test","exercises":[]}""") }.isFailure).isTrue()
        assertThat(runCatching { TemplateExchange.parse(valid.replace("\"name\":\"Жим\"", "\"name\":\"  \"")) }.isFailure).isTrue()
    }

    @Test fun rejectsOversizedAndMalformedUtf8Streams() {
        assertThat(runCatching { TemplateExchange.parse(ByteArrayInputStream(ByteArray(TemplateExchange.MAX_BYTES + 1))) }.isFailure).isTrue()
        assertThat(runCatching { TemplateExchange.parse(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }.isFailure).isTrue()
    }

    @Test fun exportedProgramRoundTripsWithoutLocalProfileOrPhotoData() {
        val details = TemplateDetails(WorkoutTemplate(id = 41, athleteId = 99, name = "Программа друга"), listOf(
            TemplateExerciseInfo(Exercise(id = 51, name = "Жим", muscleGroup = "Грудь", equipment = "Штанга",
                photoPath = "/private/friend-photo.jpg"), 1, 4, 8, "Моя заметка"),
            TemplateExerciseInfo(Exercise(id = 52, name = "Тяга", muscleGroup = "Спина", equipment = "Блок"), 2, 3, null, null),
        ))
        val encoded = TemplateExchange.encode(details)
        assertThat(encoded).doesNotContain("athleteId")
        assertThat(encoded).doesNotContain("photoPath")
        assertThat(encoded).doesNotContain("friend-photo")
        assertThat(encoded).doesNotContain("\"id\"")
        val imported = TemplateExchange.parse(encoded)
        assertThat(imported.name).isEqualTo("Программа друга")
        assertThat(imported.exercises.map { it.name }).containsExactly("Жим", "Тяга").inOrder()
        assertThat(imported.exercises.first().targetSets).isEqualTo(4)
        assertThat(imported.exercises.first().note).isEqualTo("Моя заметка")
        assertThat(imported.exercises.last().targetReps).isNull()
    }
}
