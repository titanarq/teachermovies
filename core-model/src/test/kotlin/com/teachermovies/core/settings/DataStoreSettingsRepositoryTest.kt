package com.teachermovies.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private fun newRepository() =
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create { tmpFolder.newFile("t.preferences_pb") },
        )

    @Test
    fun anUntouchedStoreReadsAsTheDefaults() = runTest {
        val settings = newRepository().settings.first()

        assertEquals(AppSettings(), settings)
        assertEquals(8787, settings.httpPort)
        assertNull(settings.downloadVolumeId)
        assertTrue(settings.authTokenHashes.isEmpty())
        assertFalse(settings.firstRunCompleted)
        assertFalse(settings.autostartOnBoot)
    }

    @Test
    fun setHttpPortRoundTrips() = runTest {
        val repository = newRepository()

        repository.setHttpPort(9000)

        assertEquals(9000, repository.settings.first().httpPort)
    }

    @Test
    fun setHttpPortAcceptsBothEndsOfTheRange() = runTest {
        val repository = newRepository()

        repository.setHttpPort(1024)
        assertEquals(1024, repository.settings.first().httpPort)

        repository.setHttpPort(65535)
        assertEquals(65535, repository.settings.first().httpPort)
    }

    @Test
    fun setHttpPortRejectsAPortOutsideTheRange() = runTest {
        val repository = newRepository()

        assertRejected { repository.setHttpPort(1023) }
        assertRejected { repository.setHttpPort(65536) }
        assertRejected { repository.setHttpPort(0) }
        assertRejected { repository.setHttpPort(-1) }

        // A rejected port must leave the stored one alone, not half-write it.
        assertEquals(8787, repository.settings.first().httpPort)
    }

    @Test
    fun setDownloadVolumeIdRoundTrips() = runTest {
        val repository = newRepository()

        repository.setDownloadVolumeId("usb-1")

        assertEquals("usb-1", repository.settings.first().downloadVolumeId)
    }

    @Test
    fun setDownloadVolumeIdClearsTheChoiceWithNull() = runTest {
        val repository = newRepository()
        repository.setDownloadVolumeId("usb-1")

        repository.setDownloadVolumeId(null)

        assertNull(repository.settings.first().downloadVolumeId)
    }

    @Test
    fun addAuthTokenHashKeepsTheHashesAlreadyStored() = runTest {
        val repository = newRepository()

        repository.addAuthTokenHash("hash-a")
        repository.addAuthTokenHash("hash-b")

        assertEquals(setOf("hash-a", "hash-b"), repository.settings.first().authTokenHashes)
    }

    @Test
    fun addAuthTokenHashStoresADuplicateOnlyOnce() = runTest {
        val repository = newRepository()

        repository.addAuthTokenHash("hash-a")
        repository.addAuthTokenHash("hash-a")

        assertEquals(setOf("hash-a"), repository.settings.first().authTokenHashes)
    }

    @Test
    fun clearAuthTokenHashesForgetsEveryHash() = runTest {
        val repository = newRepository()
        repository.addAuthTokenHash("hash-a")
        repository.addAuthTokenHash("hash-b")

        repository.clearAuthTokenHashes()

        assertTrue(repository.settings.first().authTokenHashes.isEmpty())
    }

    @Test
    fun setFirstRunCompletedRoundTrips() = runTest {
        val repository = newRepository()

        repository.setFirstRunCompleted(true)
        assertTrue(repository.settings.first().firstRunCompleted)

        repository.setFirstRunCompleted(false)
        assertFalse(repository.settings.first().firstRunCompleted)
    }

    @Test
    fun autostartOnBootIsOffByDefault() = runTest {
        assertFalse(newRepository().settings.first().autostartOnBoot)
    }

    @Test
    fun setAutostartOnBootIsWhatSettingsEmitsNext() = runTest {
        val repository = newRepository()

        repository.setAutostartOnBoot(true)

        assertTrue(repository.settings.first().autostartOnBoot)
    }

    @Test
    fun setAutostartOnBootCanBeTurnedBackOff() = runTest {
        val repository = newRepository()
        repository.setAutostartOnBoot(true)

        repository.setAutostartOnBoot(false)

        assertFalse(repository.settings.first().autostartOnBoot)
    }

    @Test
    fun togglingAutostartOnBootLeavesTheOtherSettingsAlone() = runTest {
        val repository = newRepository()
        repository.setHttpPort(9000)
        repository.setDownloadVolumeId("usb-1")
        repository.addAuthTokenHash("hash-a")
        repository.setFirstRunCompleted(true)
        val before = repository.settings.first()

        repository.setAutostartOnBoot(true)
        assertEquals(before.copy(autostartOnBoot = true), repository.settings.first())

        repository.setAutostartOnBoot(false)
        assertEquals(before, repository.settings.first())
    }

    @Test
    fun writingOneSettingLeavesTheOthersAlone() = runTest {
        val repository = newRepository()

        repository.setHttpPort(9000)
        repository.setDownloadVolumeId("usb-1")
        repository.addAuthTokenHash("hash-a")
        repository.setFirstRunCompleted(true)

        assertEquals(
            AppSettings(
                httpPort = 9000,
                downloadVolumeId = "usb-1",
                authTokenHashes = setOf("hash-a"),
                firstRunCompleted = true,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun aWriteReachesTheFileOnDisk() = runTest {
        val storeFile = tmpFolder.newFile("t.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create { storeFile }
        val repository = DataStoreSettingsRepository(dataStore)

        repository.setHttpPort(9000)

        assertTrue("nothing was written to $storeFile", storeFile.length() > 0L)
    }

    /** The suspend cousin of `assertThrows`, for a call that has to run inside `runTest`. */
    private suspend fun assertRejected(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()

        assertTrue(
            "expected an IllegalArgumentException, was $failure",
            failure is IllegalArgumentException,
        )
    }
}
