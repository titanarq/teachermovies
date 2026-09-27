package com.teachermovies.core.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.teachermovies.core.db.TeacherMoviesDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSubtitleFetchRepositoryTest : SubtitleFetchRepositoryContractTest() {
    private lateinit var db: TeacherMoviesDatabase

    override fun createRepository(): SubtitleFetchRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return RoomSubtitleFetchRepository(db.subtitleFetchStateDao())
    }

    override fun releaseRepository() {
        db.close()
    }
}
