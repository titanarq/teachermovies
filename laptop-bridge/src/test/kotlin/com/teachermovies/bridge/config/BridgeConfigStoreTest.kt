package com.teachermovies.bridge.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The config file's contract (#271): `~/.config/teachermovies-bridge/config.json` written with
 * `0600` permissions, in a directory of its own, holding the token and nothing readable by anybody
 * else. Every test writes inside a [TemporaryFolder], so the real home directory is never touched.
 */
class BridgeConfigStoreTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val config =
        BridgeConfig(
            tvUrl = "http://192.168.1.20:8787",
            token = "tok_super-secreto_9f3a",
            deviceName = "salon",
        )

    private fun store(relative: String = "teachermovies-bridge/config.json"): BridgeConfigStore =
        BridgeConfigStore(tmpFolder.root.toPath().resolve(relative))

    /** The directory a store's config file lives in. */
    private fun directoryOf(store: BridgeConfigStore): Path = requireNotNull(store.path.parent)

    @Test
    fun `a missing file loads as Missing and has no permissions to report`() {
        assertEquals(ConfigLoad.Missing, store().load())
        assertNull(store().permissions())
    }

    @Test
    fun `save then load round-trips the config`() {
        val store = store()

        assertEquals(ConfigSave.Saved(store.path), store.save(config))
        assertEquals(ConfigLoad.Loaded(config), store.load())
    }

    @Test
    fun `the config file is written with mode 0600`() {
        val store = store()

        store.save(config)

        assertEquals("0600", store.permissions())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(store.path),
        )
    }

    @Test
    fun `the config directory is created with mode 0700`() {
        val store = store()

        store.save(config)

        val permissions = Files.getPosixFilePermissions(directoryOf(store))
        assertEquals("rwx------", PosixFilePermissions.toString(permissions))
    }

    @Test
    fun `saving replaces a config whose permissions had been loosened`() {
        val store = store()
        Files.createDirectories(directoryOf(store))
        Files.writeString(store.path, "{}")
        Files.setPosixFilePermissions(store.path, PosixFilePermissions.fromString("rw-r--r--"))

        store.save(config)

        assertEquals("0600", store.permissions())
    }

    @Test
    fun `a save leaves no temporary file holding a second copy of the token`() {
        val store = store()

        store.save(config)
        store.save(config.copy(deviceName = "portatil"))

        val names = Files.list(directoryOf(store)).use { it.map { path -> path.fileName.toString() }.toList() }
        assertEquals(listOf("config.json"), names.sorted())
    }

    @Test
    fun `the stored path is absolute and normalized`() {
        val store = BridgeConfigStore(tmpFolder.root.toPath().resolve("a/../b/config.json"))

        assertTrue(store.path.isAbsolute)
        assertFalse(store.path.toString().contains(".."))
        assertEquals(tmpFolder.root.toPath().resolve("b/config.json"), store.path)
    }

    @Test
    fun `a file that is not config JSON loads as Corrupt, quoting no part of it`() {
        val store = store()
        Files.createDirectories(directoryOf(store))
        Files.writeString(store.path, "esto no es json {")

        val load = store.load()

        assertTrue(load is ConfigLoad.Corrupt)
        val reason = (load as ConfigLoad.Corrupt).reason
        assertTrue(reason.isNotBlank())
        assertFalse(reason.contains("esto"))
    }

    @Test
    fun `a config path that cannot be read loads as Unreadable`() {
        val store = store()
        Files.createDirectories(store.path)

        assertTrue(store.load() is ConfigLoad.Unreadable)
    }

    @Test
    fun `unknown keys are ignored, so a newer bridge's config stays readable`() {
        val store = store()
        Files.createDirectories(directoryOf(store))
        Files.writeString(store.path, """{"tvUrl":"http://tv:8787","token":"t","jobsDir":"/var/tmp/jobs"}""")

        assertEquals(ConfigLoad.Loaded(BridgeConfig(tvUrl = "http://tv:8787", token = "t")), store.load())
    }

    @Test
    fun `a field that is null is not written to the file`() {
        val store = store()

        store.save(BridgeConfig(tvUrl = "http://192.168.1.20:8787"))

        val text = Files.readString(store.path)
        assertTrue(text.contains("tvUrl"))
        assertFalse(text.contains("token"))
    }

    @Test
    fun `delete removes the config, and a second delete finds nothing`() {
        val store = store()
        store.save(config)

        assertEquals(ConfigDelete.Deleted(store.path), store.delete())
        assertEquals(ConfigLoad.Missing, store.load())
        assertEquals(ConfigDelete.Absent(store.path), store.delete())
    }
}
