package com.dtyan.fitdiary.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateManifestTest {
    @Test fun acceptsCurrentPublicSchemaAndOptionalSchemaVersion() {
        val release = testRelease()
        assertThat(UpdateManifest.parse(release.toJson())).isEqualTo(release)
        assertThat(UpdateManifest.parse(release.toJson().replace("\"schema_version\":1,", ""))).isEqualTo(release)
    }

    @Test fun onlyPinnedHttpsArtifactOfThisProjectIsAccepted() {
        val allowed = testRelease().downloadUrl
        val rejected = listOf(
            allowed.replace("https:", "http:"),
            allowed.replace(UpdateManifest.HOST, "untrusted.example"),
            allowed.replace(UpdateManifest.PROJECT_ID, "other-project"),
            "${UpdateManifest.BASE_URL}/latest.apk",
            "$allowed?redirect=https://untrusted.example",
            "$allowed#fragment",
            allowed.replace("https://", "https://user@"),
            allowed.replace("/releases/", "/releases/../releases/"),
            allowed.replace("/releases/", "/releases/%2e%2e/releases/"),
            allowed.replace(UpdateManifest.HOST, "${UpdateManifest.HOST}:444"),
        )
        assertThat(UpdateManifest.isPinnedArtifact(allowed)).isTrue()
        rejected.forEach { url -> assertThat(runCatching { UpdateManifest.parse(testRelease().copy(downloadUrl = url).toJson()) }.isFailure).isTrue() }
    }

    @Test fun rejectsUnknownSchemaWrongIdentityAndInvalidBounds() {
        listOf(
            testRelease().copy(schemaVersion = 2),
            testRelease().copy(packageName = "com.some.other.app"),
            testRelease().copy(versionCode = 0),
            testRelease().copy(sizeBytes = UpdateManifest.MAX_APK_BYTES + 1),
            testRelease().copy(sha256 = "not-a-hash"),
            testRelease().copy(latestDownloadUrl = "https://untrusted.example/latest.apk"),
            testRelease().copy(notes = "x".repeat(20_001)),
        ).forEach { assertThat(runCatching { UpdateManifest.parse(it.toJson()) }.isFailure).isTrue() }
    }
}
