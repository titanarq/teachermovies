package com.teachermovies.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The 1 -> 2 migration (#274), driven from the schema Room exported for version 1: a file that
 * already holds torrents must keep them, and the four new tables must come out exactly as version 2
 * declares them. Room validates a migrated schema before it lets a query run, so the reads below fail
 * with "Migration didn't properly handle ..." if [MIGRATION_1_2] and the entities ever drift apart.
 *
 * No `MigrationTestHelper` here: it is `androidx.room:room-testing` under `src/androidTest` assets, and
 * reading the committed `1.json` from the module directory does the same job with the dependencies
 * `:core-model` already has (ADR-0003: JVM tests, fakes over mocks).
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {
    private lateinit var context: Context
    private val databases = mutableListOf<TeacherMoviesDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        databases.clear()
    }

    @Test
    fun migrationKeepsTheVersionOneTorrentRow() =
        runTest {
            createVersion1Database(MIGRATED).close()

            val row = openMigrated(MIGRATED).torrentDao().get("aaa")

            assertNotNull(row)
            assertEquals(V1_TORRENT_NAME, row!!.name)
            assertEquals("Completed", row.state)
            assertEquals(42_000L, row.lastPositionMs)
            assertEquals(2_000L, row.completedAtEpochMs)
        }

    @Test
    fun migrationAddsTheFourVersionTwoTables() =
        runTest {
            createVersion1Database(MIGRATED).close()
            val db = openMigrated(MIGRATED)

            assertEquals(0, db.translationCacheDao().count())
            assertEquals(0, db.explanationCacheDao().count())
            assertTrue(db.subtitleFetchStateDao().getAll().isEmpty())
            assertNull(db.subtitleAlignmentDao().bestForTorrent("aaa"))
        }

    @Test
    fun migratedTablesStoreAndReturnRows() =
        runTest {
            createVersion1Database(MIGRATED).close()
            val db = openMigrated(MIGRATED)

            val translations = db.translationCacheDao()
            val explanations = db.explanationCacheDao()
            val fetchStates = db.subtitleFetchStateDao()
            val alignments = db.subtitleAlignmentDao()

            translations.upsert(
                TranslationCacheEntity(
                    sourceText = "what did they say",
                    translationEs = "qué han dicho",
                    createdAtEpochMs = 1L,
                    lastUsedAtEpochMs = 1L,
                    hitCount = 0,
                ),
            )
            explanations.upsert(
                ExplanationCacheEntity(
                    movieTitle = V1_TORRENT_NAME,
                    line = "what did they say",
                    promptVersion = "v1",
                    explanationJson = """{"idiom":"what did they say"}""",
                    createdAtEpochMs = 1L,
                    lastUsedAtEpochMs = 1L,
                    hitCount = 0,
                ),
            )
            fetchStates.upsert(
                SubtitleFetchStateEntity(
                    infoHash = "aaa",
                    language = "es",
                    state = "Downloaded",
                    movieHash = "0123456789abcdef",
                    localPath = "/storage/Movies/aaa/subs/aaa.es.opensubtitles.srt",
                    variantLabel = null,
                    attempts = 1,
                    lastAttemptEpochMs = 1L,
                    nextRetryEpochMs = null,
                    errorMessage = null,
                    updatedAtEpochMs = 1L,
                ),
            )
            alignments.upsert(
                SubtitleAlignmentEntity(
                    infoHash = "aaa",
                    subtitlePath = "/storage/Movies/aaa/subs/aaa.es.opensubtitles.srt",
                    offsetMs = 250L,
                    frameRateScale = 0.999,
                    qualityScore = 0.87,
                    computedAtEpochMs = 1L,
                ),
            )

            assertEquals("qué han dicho", translations.get("what did they say")?.translationEs)
            assertEquals(
                """{"idiom":"what did they say"}""",
                explanations.get(V1_TORRENT_NAME, "what did they say", "v1")?.explanationJson,
            )
            assertEquals("Downloaded", fetchStates.get("aaa", "es")?.state)
            assertEquals(250L, alignments.bestForTorrent("aaa")?.offsetMs)
        }

    @Test
    fun theProductionBuilderMigratesAnExistingInstall() =
        runTest {
            createVersion1Database(TeacherMoviesDatabase.NAME).close()

            val db = TeacherMoviesDatabase.build(context)
            databases += db

            assertEquals(V1_TORRENT_NAME, db.torrentDao().get("aaa")?.name)
            assertEquals(0, db.translationCacheDao().count())
        }

    @Test
    fun withoutTheMigrationRoomRefusesTheVersionOneFile() {
        createVersion1Database(UNMIGRATED).close()
        val db =
            Room
                .databaseBuilder(context, TeacherMoviesDatabase::class.java, UNMIGRATED)
                .build()
        databases += db

        assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase }
    }

    @Test
    fun theExportedVersionTwoSchemaDescribesEveryTable() {
        val database = readSchema(2).getJSONObject("database")

        assertEquals(2, database.getInt("version"))
        assertEquals(
            listOf(
                "torrents",
                "translation_cache",
                "explanation_cache",
                "subtitle_fetch_state",
                "subtitle_alignment",
            ),
            database.getJSONArray("entities").tableNames(),
        )
        // Version 1 stays committed: it is what a future 2 -> 3 migration is tested against.
        assertEquals(1, readSchema(1).getJSONObject("database").getInt("version"))
    }

    @Test
    fun theTorrentsTableIsUnchangedBetweenVersions() {
        assertEquals(
            readSchema(1).createSqlOf("torrents"),
            readSchema(2).createSqlOf("torrents"),
        )
    }

    /** Opens [name] the way production does, migrations included, and remembers to close it. */
    private fun openMigrated(name: String): TeacherMoviesDatabase {
        val db =
            Room
                .databaseBuilder(context, TeacherMoviesDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
        databases += db
        return db
    }

    /**
     * A version 1 database file with one completed torrent in it, built from the exported `1.json`
     * rather than from a second copy of the entities: what an install upgrading from v1 looks like.
     */
    private fun createVersion1Database(name: String): SQLiteDatabase {
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val schema = readSchema(1).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            db.execSQL(setupQueries.getString(index))
        }
        db.execSQL(
            "INSERT INTO torrents (infoHash, name, state, progressPercent, downloadedBytes, totalBytes, " +
                "savePath, mainFileIndex, mainFilePath, audioTrackId, subtitleTrackId, lastPositionMs, " +
                "addedAtEpochMs, completedAtEpochMs, errorMessage) VALUES " +
                "('aaa', '$V1_TORRENT_NAME', 'Completed', 100.0, 1000, 1000, '/storage/Movies/aaa', 0, " +
                "'/storage/Movies/aaa/movie.mkv', NULL, NULL, 42000, 1000, 2000, NULL)",
        )
        // Room reads the SQLite user_version to decide it has an older schema to migrate.
        db.version = 1
        return db
    }

    private fun readSchema(version: Int): JSONObject {
        val file = schemaFile(version)
        assertTrue(
            "exported schema ${file.path} is missing -- Room writes it on build and it must be committed",
            file.isFile,
        )
        return JSONObject(file.readText())
    }

    /**
     * The exported schema of [version]. Gradle runs unit tests from the module directory, so
     * `schemas/...` is the usual hit; the walk up also covers a run from the repository root.
     */
    private fun schemaFile(version: Int): File {
        val roots = listOf("schemas", "core-model/schemas")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (root in roots) {
                val candidate = File(dir, "$root/$SCHEMA_DIR/$version.json")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        return File("schemas/$SCHEMA_DIR/$version.json")
    }

    private fun JSONObject.createSqlOf(tableName: String): String {
        val entities = getJSONObject("database").getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            if (entity.getString("tableName") == tableName) return entity.getString("createSql")
        }
        return error("no table $tableName in the exported schema")
    }

    private fun JSONArray.tableNames(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("tableName") }

    private companion object {
        const val SCHEMA_DIR = "com.teachermovies.core.db.TeacherMoviesDatabase"
        const val MIGRATED = "migration-1-to-2.db"
        const val UNMIGRATED = "migration-missing.db"
        const val V1_TORRENT_NAME = "Big Buck Bunny"
    }
}
