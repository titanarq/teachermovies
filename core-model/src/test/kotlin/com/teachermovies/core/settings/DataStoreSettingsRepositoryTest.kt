package com.teachermovies.core.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private fun newRepository() =
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create { tmpFolder.newFile("t.preferences_pb") },
        )

    @Test
    fun anUntouchedStoreReadsAsTheDefaults() =
        runTest {
            val settings = newRepository().settings.first()

            assertEquals(AppSettings(), settings)
            assertEquals(8787, settings.httpPort)
            assertNull(settings.downloadVolumeId)
            assertTrue(settings.authTokenHashes.isEmpty())
            assertTrue(settings.bridgeTokenHashes.isEmpty())
            assertFalse(settings.firstRunCompleted)
            assertFalse(settings.autostartOnBoot)
        }

    @Test
    fun setHttpPortRoundTrips() =
        runTest {
            val repository = newRepository()

            repository.setHttpPort(9000)

            assertEquals(9000, repository.settings.first().httpPort)
        }

    @Test
    fun setHttpPortAcceptsBothEndsOfTheRange() =
        runTest {
            val repository = newRepository()

            repository.setHttpPort(1024)
            assertEquals(1024, repository.settings.first().httpPort)

            repository.setHttpPort(65535)
            assertEquals(65535, repository.settings.first().httpPort)
        }

    @Test
    fun setHttpPortRejectsAPortOutsideTheRange() =
        runTest {
            val repository = newRepository()

            assertRejected { repository.setHttpPort(1023) }
            assertRejected { repository.setHttpPort(65536) }
            assertRejected { repository.setHttpPort(0) }
            assertRejected { repository.setHttpPort(-1) }

            // A rejected port must leave the stored one alone, not half-write it.
            assertEquals(8787, repository.settings.first().httpPort)
        }

    @Test
    fun setDownloadVolumeIdRoundTrips() =
        runTest {
            val repository = newRepository()

            repository.setDownloadVolumeId("usb-1")

            assertEquals("usb-1", repository.settings.first().downloadVolumeId)
        }

    @Test
    fun setDownloadVolumeIdClearsTheChoiceWithNull() =
        runTest {
            val repository = newRepository()
            repository.setDownloadVolumeId("usb-1")

            repository.setDownloadVolumeId(null)

            assertNull(repository.settings.first().downloadVolumeId)
        }

    @Test
    fun addAuthTokenHashKeepsTheHashesAlreadyStored() =
        runTest {
            val repository = newRepository()

            repository.addAuthTokenHash("hash-a")
            repository.addAuthTokenHash("hash-b")

            assertEquals(setOf("hash-a", "hash-b"), repository.settings.first().authTokenHashes)
        }

    @Test
    fun addAuthTokenHashStoresADuplicateOnlyOnce() =
        runTest {
            val repository = newRepository()

            repository.addAuthTokenHash("hash-a")
            repository.addAuthTokenHash("hash-a")

            assertEquals(setOf("hash-a"), repository.settings.first().authTokenHashes)
        }

    @Test
    fun clearAuthTokenHashesForgetsEveryHash() =
        runTest {
            val repository = newRepository()
            repository.addAuthTokenHash("hash-a")
            repository.addAuthTokenHash("hash-b")

            repository.clearAuthTokenHashes()

            assertTrue(
                repository.settings
                    .first()
                    .authTokenHashes
                    .isEmpty(),
            )
        }

    @Test
    fun addBridgeTokenHashKeepsTheHashesAlreadyStoredApartFromThePhoneOnes() =
        runTest {
            val repository = newRepository()
            repository.addAuthTokenHash("hash-phone")

            repository.addBridgeTokenHash("hash-a")
            repository.addBridgeTokenHash("hash-b")
            repository.addBridgeTokenHash("hash-a")

            assertEquals(setOf("hash-a", "hash-b"), repository.settings.first().bridgeTokenHashes)
            assertEquals(setOf("hash-phone"), repository.settings.first().authTokenHashes)
        }

    @Test
    fun clearBridgeTokenHashesForgetsTheBridgeAndItsNameButKeepsThePhones() =
        runTest {
            val repository = newRepository()
            repository.addAuthTokenHash("hash-phone")
            repository.addBridgeTokenHash("hash-bridge")
            repository.setBridgeDeviceName("portatil-manuel")

            repository.clearBridgeTokenHashes()

            val settings = repository.settings.first()
            assertTrue(settings.bridgeTokenHashes.isEmpty())
            assertEquals(null, settings.bridgeDeviceName)
            assertEquals(setOf("hash-phone"), settings.authTokenHashes)
        }

    @Test
    fun setBridgeDeviceNameRoundTripsAndBlankClearsIt() =
        runTest {
            val repository = newRepository()

            repository.setBridgeDeviceName("portatil-manuel")
            assertEquals("portatil-manuel", repository.settings.first().bridgeDeviceName)

            repository.setBridgeDeviceName("  ")
            assertEquals(null, repository.settings.first().bridgeDeviceName)
        }

    @Test
    fun clearAuthTokenHashesLeavesTheBridgeHashesAlone() =
        runTest {
            val repository = newRepository()
            repository.addAuthTokenHash("hash-phone")
            repository.addBridgeTokenHash("hash-bridge")

            repository.clearAuthTokenHashes()

            assertTrue(
                repository.settings
                    .first()
                    .authTokenHashes
                    .isEmpty(),
            )
            assertEquals(setOf("hash-bridge"), repository.settings.first().bridgeTokenHashes)
        }

    @Test
    fun setFirstRunCompletedRoundTrips() =
        runTest {
            val repository = newRepository()

            repository.setFirstRunCompleted(true)
            assertTrue(repository.settings.first().firstRunCompleted)

            repository.setFirstRunCompleted(false)
            assertFalse(repository.settings.first().firstRunCompleted)
        }

    @Test
    fun autostartOnBootIsOffByDefault() =
        runTest {
            assertFalse(newRepository().settings.first().autostartOnBoot)
        }

    @Test
    fun setAutostartOnBootIsWhatSettingsEmitsNext() =
        runTest {
            val repository = newRepository()

            repository.setAutostartOnBoot(true)

            assertTrue(repository.settings.first().autostartOnBoot)
        }

    @Test
    fun setAutostartOnBootCanBeTurnedBackOff() =
        runTest {
            val repository = newRepository()
            repository.setAutostartOnBoot(true)

            repository.setAutostartOnBoot(false)

            assertFalse(repository.settings.first().autostartOnBoot)
        }

    @Test
    fun togglingAutostartOnBootLeavesTheOtherSettingsAlone() =
        runTest {
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
    fun writingOneSettingLeavesTheOthersAlone() =
        runTest {
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
    fun aWriteReachesTheFileOnDisk() =
        runTest {
            val storeFile = tmpFolder.newFile("t.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create { storeFile }
            val repository = DataStoreSettingsRepository(dataStore)

            repository.setHttpPort(9000)

            assertTrue("nothing was written to $storeFile", storeFile.length() > 0L)
        }

    @Test
    fun translationApiKeyIsNullByDefault() =
        runTest {
            assertNull(newRepository().settings.first().translationApiKey)
        }

    @Test
    fun setTranslationApiKeyIsWhatSettingsEmitsNext() =
        runTest {
            val repository = newRepository()

            repository.setTranslationApiKey("sk-ant-test-key")

            assertEquals("sk-ant-test-key", repository.settings.first().translationApiKey)
        }

    @Test
    fun setTranslationApiKeyClearsTheKeyWithNull() =
        runTest {
            val repository = newRepository()
            repository.setTranslationApiKey("sk-ant-test-key")

            repository.setTranslationApiKey(null)

            assertNull(repository.settings.first().translationApiKey)
        }

    @Test
    fun setTranslationApiKeyClearsTheKeyWithABlankString() =
        runTest {
            val repository = newRepository()

            repository.setTranslationApiKey("sk-ant-test-key")
            repository.setTranslationApiKey("")
            assertNull(repository.settings.first().translationApiKey)

            repository.setTranslationApiKey("sk-ant-test-key")
            repository.setTranslationApiKey("   ")
            assertNull(repository.settings.first().translationApiKey)
        }

    @Test
    fun settingTheTranslationApiKeyLeavesTheOtherSettingsAlone() =
        runTest {
            val repository = newRepository()
            repository.setHttpPort(9000)
            repository.setDownloadVolumeId("usb-1")
            repository.addAuthTokenHash("hash-a")
            repository.setFirstRunCompleted(true)
            repository.setAutostartOnBoot(true)
            val before = repository.settings.first()

            repository.setTranslationApiKey("sk-ant-test-key")
            assertEquals(before.copy(translationApiKey = "sk-ant-test-key"), repository.settings.first())

            repository.setTranslationApiKey(null)
            assertEquals(before, repository.settings.first())
        }

    @Test
    fun upgradingClearsAStoredTranslationApiKeyAndLeavesTheOtherSettingsAlone() =
        runTest {
            val storeFile = tmpFolder.newFile("upgrade.preferences_pb")
            // The app before #290: no migrations, and a key pasted in Configuración.
            val before =
                withStore(storeFile, migrations = emptyList()) { repository ->
                    repository.setHttpPort(9000)
                    repository.setDownloadVolumeId("usb-1")
                    repository.addAuthTokenHash("hash-a")
                    repository.addBridgeTokenHash("bridge-a")
                    repository.setBridgeDeviceName("portatil")
                    repository.setFirstRunCompleted(true)
                    repository.setAutostartOnBoot(true)
                    repository.setTranslationApiKey("sk-ant-test-key")
                    repository.settings.first()
                }
            assertEquals("sk-ant-test-key", before.translationApiKey)

            // The upgraded app opens the same file with the production migrations.
            val upgraded = withStore(storeFile, SETTINGS_MIGRATIONS) { it.settings.first() }
            assertEquals(before.copy(translationApiKey = null), upgraded)

            // The removal was written back, not only hidden from the first read.
            val reopened = withStore(storeFile, migrations = emptyList()) { it.settings.first() }
            assertEquals(upgraded, reopened)
            assertFalse(storeFile.readBytes().decodeToString().contains("sk-ant-test-key"))
        }

    @Test
    fun theKeyMigrationIsANoOpOnAStoreWithoutAKey() =
        runTest {
            val storeFile = tmpFolder.newFile("nokey.preferences_pb")
            val before =
                withStore(storeFile, migrations = emptyList()) { repository ->
                    repository.setHttpPort(9000)
                    repository.settings.first()
                }

            assertEquals(before, withStore(storeFile, SETTINGS_MIGRATIONS) { it.settings.first() })
        }

    /**
     * Opens a store over [file] on its own scope, runs [block] and closes the store again, so the
     * next call can open the same file the way a restarted app would.
     */
    private suspend fun <T> withStore(
        file: File,
        migrations: List<DataMigration<Preferences>>,
        block: suspend (DataStoreSettingsRepository) -> T,
    ): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store =
                PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) { file }
            return block(DataStoreSettingsRepository(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun toStringNeverPrintsTheTranslationApiKey() {
        val settings = AppSettings(translationApiKey = "sk-ant-test-key")

        assertFalse(settings.toString().contains("sk-ant-test-key"))
        assertTrue(settings.toString().contains("translationApiKey=<redacted>"))
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
