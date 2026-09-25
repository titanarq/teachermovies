package com.teachermovies.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePairedTvStoreTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val tv =
        PairedTv(instanceName = "Movie Assistant", baseUrl = "http://192.168.1.20:8787", token = "s3cr3t-token")

    private fun newStore() =
        DataStorePairedTvStore(
            PreferenceDataStoreFactory.create { tmpFolder.newFile("paired.preferences_pb") },
        )

    @Test
    fun `an empty store reads as null`() =
        runTest {
            assertNull(newStore().pairedTv.first())
        }

    @Test
    fun `save then read returns the saved TV`() =
        runTest {
            val store = newStore()

            store.save(tv)

            assertEquals(tv, store.pairedTv.first())
        }

    @Test
    fun `save replaces a previously paired TV`() =
        runTest {
            val store = newStore()
            store.save(tv)

            val other = PairedTv("Other TV", "http://192.168.1.30:8787", "tok2")
            store.save(other)

            assertEquals(other, store.pairedTv.first())
        }

    @Test
    fun `updateBaseUrl changes only the base url of a stored TV`() =
        runTest {
            val store = newStore()
            store.save(tv)

            store.updateBaseUrl("http://192.168.1.21:8787")

            assertEquals(tv.copy(baseUrl = "http://192.168.1.21:8787"), store.pairedTv.first())
        }

    @Test
    fun `updateBaseUrl without a stored TV is a no-op`() =
        runTest {
            val store = newStore()

            store.updateBaseUrl("http://192.168.1.21:8787")

            assertNull(store.pairedTv.first())
        }

    @Test
    fun `clear forgets the TV`() =
        runTest {
            val store = newStore()
            store.save(tv)

            store.clear()

            assertNull(store.pairedTv.first())
        }

    @Test
    fun `toString redacts the token`() {
        assertTrue("s3cr3t" !in tv.toString())
    }
}
