package com.teachermovies.core.settings.fake

import com.teachermovies.core.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySettingsRepositoryTest {
    @Test
    fun startsFromTheDefaults() =
        runTest {
            assertEquals(AppSettings(), InMemorySettingsRepository().settings.first())
        }

    @Test
    fun startsFromTheGivenSettings() =
        runTest {
            val initial = AppSettings(httpPort = 9000, autostartOnBoot = true)

            assertEquals(initial, InMemorySettingsRepository(initial).settings.first())
        }

    @Test
    fun setHttpPortIsReflectedInSettings() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setHttpPort(9000)

            assertEquals(9000, repository.settings.first().httpPort)
        }

    @Test
    fun setHttpPortAcceptsBothEndsOfTheRange() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setHttpPort(1024)
            assertEquals(1024, repository.settings.first().httpPort)

            repository.setHttpPort(65535)
            assertEquals(65535, repository.settings.first().httpPort)
        }

    @Test
    fun setHttpPortRejectsAPortOutsideTheRangeAndKeepsTheStoredOne() =
        runTest {
            val repository = InMemorySettingsRepository()
            repository.setHttpPort(9000)

            assertRejected { repository.setHttpPort(1023) }
            assertRejected { repository.setHttpPort(65536) }
            assertRejected { repository.setHttpPort(0) }
            assertRejected { repository.setHttpPort(-1) }

            assertEquals(9000, repository.settings.first().httpPort)
        }

    @Test
    fun setDownloadVolumeIdIsReflectedInSettingsAndNullClearsIt() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setDownloadVolumeId("usb-1")
            assertEquals("usb-1", repository.settings.first().downloadVolumeId)

            repository.setDownloadVolumeId(null)
            assertNull(repository.settings.first().downloadVolumeId)
        }

    @Test
    fun addAuthTokenHashKeepsTheHashesAlreadyStored() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.addAuthTokenHash("hash-a")
            repository.addAuthTokenHash("hash-b")
            repository.addAuthTokenHash("hash-a")

            assertEquals(setOf("hash-a", "hash-b"), repository.settings.first().authTokenHashes)
        }

    @Test
    fun clearAuthTokenHashesForgetsEveryHash() =
        runTest {
            val repository = InMemorySettingsRepository()
            repository.addAuthTokenHash("hash-a")

            repository.clearAuthTokenHashes()

            assertTrue(
                repository.settings
                    .first()
                    .authTokenHashes
                    .isEmpty(),
            )
        }

    @Test
    fun setFirstRunCompletedIsReflectedInSettings() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setFirstRunCompleted(true)
            assertTrue(repository.settings.first().firstRunCompleted)

            repository.setFirstRunCompleted(false)
            assertFalse(repository.settings.first().firstRunCompleted)
        }

    @Test
    fun setAutostartOnBootIsReflectedInSettings() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setAutostartOnBoot(true)
            assertTrue(repository.settings.first().autostartOnBoot)

            repository.setAutostartOnBoot(false)
            assertFalse(repository.settings.first().autostartOnBoot)
        }

    @Test
    fun writingOneSettingLeavesTheOthersAlone() =
        runTest {
            val repository = InMemorySettingsRepository()

            repository.setHttpPort(9000)
            repository.setDownloadVolumeId("usb-1")
            repository.addAuthTokenHash("hash-a")
            repository.setFirstRunCompleted(true)
            repository.setAutostartOnBoot(true)

            assertEquals(
                AppSettings(
                    httpPort = 9000,
                    downloadVolumeId = "usb-1",
                    authTokenHashes = setOf("hash-a"),
                    firstRunCompleted = true,
                    autostartOnBoot = true,
                ),
                repository.settings.first(),
            )
            assertEquals(repository.settings.first(), repository.current)
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
