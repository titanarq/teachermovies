package com.teachermovies.bridge.opensubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class CredentialsFileTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val home: Path
        get() = tmpFolder.root.toPath()

    private fun file(text: String): CredentialsFile {
        val path = home.resolve("opensubtitles.env")
        Files.writeString(path, text)
        return CredentialsFile(path)
    }

    @Test
    fun `reads the three keys, with comments, export and quotes`() {
        val load =
            file(
                """
                # cuenta de OpenSubtitles
                export OPENSUBTITLES_API_KEY="clave con espacios"
                OPENSUBTITLES_USERNAME='usuario'
                OPENSUBTITLES_PASSWORD=p=a=s
                OPENSUBTITLES_USER_AGENT=casa v1.2
                """.trimIndent(),
            ).load()

        val credentials = (load as CredentialsLoad.Loaded).credentials
        assertEquals("clave con espacios", credentials.apiKey)
        assertEquals("usuario", credentials.username)
        assertEquals("p=a=s", credentials.password)
        assertEquals("casa v1.2", credentials.userAgent)
    }

    @Test
    fun `a missing key is named, its neighbours' values are not`() {
        val load = file("OPENSUBTITLES_API_KEY=secreta\nOPENSUBTITLES_PASSWORD=\n").load()

        assertEquals(CredentialsLoad.Incomplete(listOf("OPENSUBTITLES_USERNAME", "OPENSUBTITLES_PASSWORD")), load)
        assertFalse(load.toString().contains("secreta"))
    }

    @Test
    fun `no file is Missing and has no permissions`() {
        val absent = CredentialsFile(home.resolve("nada.env"))

        assertEquals(CredentialsLoad.Missing, absent.load())
        assertFalse(absent.exists())
        assertNull(absent.permissions())
    }

    @Test
    fun `permissions are reported as four octal digits`() {
        val credentials = file("x=y")
        Files.setPosixFilePermissions(credentials.path, PosixFilePermissions.fromString("rw-------"))
        assertEquals("0600", credentials.permissions())

        Files.setPosixFilePermissions(credentials.path, PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals("0644", credentials.permissions())
    }

    @Test
    fun `the default path is under the bridge config dir, and the variable moves it`() {
        assertEquals(
            home.resolve(".config/teachermovies-bridge/.secrets/opensubtitles.env"),
            CredentialsFile.defaultPath(home) { null },
        )
        assertEquals(
            home.resolve("xdg/teachermovies-bridge/.secrets/opensubtitles.env"),
            CredentialsFile.defaultPath(home) { if (it == "XDG_CONFIG_HOME") home.resolve("xdg").toString() else null },
        )
        assertEquals(
            home.resolve("repo/.secrets/opensubtitles.env"),
            CredentialsFile.defaultPath(home) {
                if (it == CredentialsFile.PATH_VARIABLE) "~/repo/.secrets/opensubtitles.env" else null
            },
        )
    }

    @Test
    fun `an unreadable file is Unreadable with a class name only`() {
        val credentials = file("OPENSUBTITLES_API_KEY=secreta")
        Files.setPosixFilePermissions(credentials.path, PosixFilePermissions.fromString("-w-------"))

        val load = credentials.load()

        assertTrue(load is CredentialsLoad.Unreadable)
        assertFalse(load.toString().contains("secreta"))
    }
}
