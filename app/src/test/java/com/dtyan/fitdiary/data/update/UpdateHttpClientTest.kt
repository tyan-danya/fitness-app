package com.dtyan.fitdiary.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateHttpClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun redirectsAreNeverFollowedAndConnectionAlwaysCloses() = runTest {
        val connection = FakeUpdateConnection(byteArrayOf(), 302)
        assertThat(runCatching { UpdateHttpClient { connection }.manifest() }.isFailure).isTrue()
        assertThat(connection.instanceFollowRedirects).isFalse()
        assertThat(connection.bodyOpened).isFalse()
        assertThat(connection.disconnected).isTrue()
    }

    @Test fun oversizedManifestAndDownloadAreRejected() = runTest {
        val huge = FakeUpdateConnection(ByteArray(UpdateManifest.MAX_MANIFEST_BYTES + 1))
        assertThat(runCatching { UpdateHttpClient { huge }.manifest() }.isFailure).isTrue()
        val apk = FakeUpdateConnection(testPayload + byteArrayOf(1), advertisedLength = -1)
        assertThat(runCatching { UpdateHttpClient { apk }.download(testRelease(), temporary.newFile(), { true }) {} }.isFailure).isTrue()
        assertThat(apk.disconnected).isTrue()
    }

    @Test fun downloadChecksNetworkPolicyAndTruncation() = runTest {
        val denied = FakeUpdateConnection(testPayload)
        assertThat(runCatching { UpdateHttpClient { denied }.download(testRelease(), temporary.newFile(), { false }) {} }.isFailure).isTrue()
        val truncated = FakeUpdateConnection(testPayload.dropLast(1).toByteArray(), advertisedLength = -1)
        assertThat(runCatching { UpdateHttpClient { truncated }.download(testRelease(), temporary.newFile(), { true }) {} }.isFailure).isTrue()
    }
}
