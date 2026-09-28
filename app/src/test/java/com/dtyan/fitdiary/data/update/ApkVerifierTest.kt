package com.dtyan.fitdiary.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsOnlyExactSizeDigestIdentityNewerCodeAndSigner() = runTest {
        val file = temporary.newFile("update.apk").apply { writeBytes(testPayload) }
        ApkVerifier { testArchiveIdentity }.verify(file, testRelease(), testInstalled)
        listOf(
            testArchiveIdentity.copy(packageName = "other.app"),
            testArchiveIdentity.copy(versionCode = 9),
            testArchiveIdentity.copy(signers = setOf("untrusted-certificate")),
            testArchiveIdentity.copy(signers = emptySet()),
        ).forEach { identity -> assertThat(runCatching { ApkVerifier { identity }.verify(file, testRelease(), testInstalled) }.isFailure).isTrue() }
        assertThat(runCatching { ApkVerifier { testArchiveIdentity }.verify(file, testRelease(), testInstalled.copy(versionCode = 8)) }.isFailure).isTrue()
        assertThat(runCatching { ApkVerifier { testArchiveIdentity }.verify(file, testRelease().copy(sha256 = "0".repeat(64)), testInstalled) }.isFailure).isTrue()
        file.appendBytes(byteArrayOf(0))
        assertThat(runCatching { ApkVerifier { testArchiveIdentity }.verify(file, testRelease(), testInstalled) }.isFailure).isTrue()
    }
}
