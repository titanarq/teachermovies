package com.teachermovies.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database. Every version's schema is exported to `core-model/schemas/`; bumping the
 * version needs a migration and the new schema JSON committed.
 */
@Database(entities = [TorrentEntity::class], version = 1, exportSchema = true)
abstract class TeacherMoviesDatabase : RoomDatabase() {
    abstract fun torrentDao(): TorrentDao

    companion object {
        const val NAME = "teachermovies.db"

        /** The production database, stored in the app's database directory as [NAME]. */
        fun build(context: Context): TeacherMoviesDatabase =
            Room.databaseBuilder(context.applicationContext, TeacherMoviesDatabase::class.java, NAME)
                .build()
    }
}
