package com.teachermovies.bridge.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The paths the bridge keeps (#271): "`~` is expanded and every stored path is absolute". A test
 * here never touches the real home directory -- [home] is a path that need not exist, because
 * [ConfigLocation] only ever computes with it.
 */
class ConfigLocationTest {
    private val home = Paths.get("/home/alumno")

    private val noEnv: (String) -> String? = { null }

    /** The default config file when `XDG_CONFIG_HOME` is [value]. */
    private fun withConfigHome(value: String?): Path =
        ConfigLocation.configFile(null, home) { if (it == ConfigLocation.CONFIG_HOME_VARIABLE) value else null }

    @Test
    fun `the default config file is the documented one under the home directory`() {
        assertEquals(
            Paths.get("/home/alumno/.config/teachermovies-bridge/config.json"),
            ConfigLocation.configFile(explicitSpec = null, home = home, env = noEnv),
        )
    }

    @Test
    fun `XDG_CONFIG_HOME replaces the dot-config directory`() {
        assertEquals(Paths.get("/datos/config/teachermovies-bridge/config.json"), withConfigHome("/datos/config"))
    }

    @Test
    fun `a tilde inside XDG_CONFIG_HOME is expanded too`() {
        assertEquals(
            Paths.get("/home/alumno/configuracion/teachermovies-bridge/config.json"),
            withConfigHome("~/configuracion"),
        )
    }

    @Test
    fun `a blank XDG_CONFIG_HOME is ignored`() {
        assertEquals(
            Paths.get("/home/alumno/.config/teachermovies-bridge/config.json"),
            withConfigHome("   "),
        )
    }

    @Test
    fun `--config wins over both defaults, and its tilde is expanded`() {
        val env = { name: String -> if (name == ConfigLocation.CONFIG_HOME_VARIABLE) "/datos/config" else null }

        assertEquals(
            Paths.get("/home/alumno/otro-lugar/puente.json"),
            ConfigLocation.configFile("~/otro-lugar/puente.json", home, env),
        )
    }

    @Test
    fun `a bare tilde is the home directory itself`() {
        assertEquals(home, ConfigLocation.expand("~", home))
    }

    @Test
    fun `a tilde prefix is replaced by the home directory, not by a directory named tilde`() {
        assertEquals(Paths.get("/home/alumno/.config"), ConfigLocation.expand("~/.config", home))
    }

    @Test
    fun `every expanded path is normalized`() {
        assertEquals(Paths.get("/opt/puente.json"), ConfigLocation.expand("/etc/../opt/puente.json", home))
        assertEquals(Paths.get("/home/alumno/config.json"), ConfigLocation.expand("~/a/../config.json", home))
    }

    @Test
    fun `a relative spec is made absolute against the working directory`() {
        val expanded = ConfigLocation.expand("config.json", home)

        assertTrue(expanded.isAbsolute)
        assertEquals(Paths.get("config.json").toAbsolutePath().normalize(), expanded)
        assertTrue(expanded.startsWith(Paths.get("").toAbsolutePath()))
    }

    @Test
    fun `the config dir is absolute and is the config file's parent`() {
        val dir = ConfigLocation.configDir(home, noEnv)

        assertTrue(dir.isAbsolute)
        assertEquals(dir.resolve(ConfigLocation.FILE_NAME), ConfigLocation.configFile(null, home, noEnv))
    }
}
