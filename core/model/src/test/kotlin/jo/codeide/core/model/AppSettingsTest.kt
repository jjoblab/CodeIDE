package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [AppSettings] et de ses conversions : défauts par type de build
 * (section 5.7) et lecture tolérante de la licence persistée.
 */
class AppSettingsTest {
    @Test
    fun `les défauts d'un build déboguable journalisent en mode détaillé`() {
        val reglages = AppSettings.defaults(buildDebuggable = true)

        assertEquals(LogVerbosity.DETAILED, reglages.logLevel)
    }

    @Test
    fun `les défauts d'un build release journalisent en mode normal`() {
        val reglages = AppSettings.defaults(buildDebuggable = false)

        assertEquals(LogVerbosity.NORMAL, reglages.logLevel)
    }

    @Test
    fun `les autres défauts sont identiques quelle que soit la variante`() {
        val debug = AppSettings.defaults(buildDebuggable = true)
        val release = AppSettings.defaults(buildDebuggable = false)

        assertEquals(debug.copy(logLevel = release.logLevel), release)
        assertEquals(ThemeMode.SYSTEM, release.themeMode)
        assertTrue(release.useDynamicColor)
        assertEquals("", release.languageTag)
        assertEquals(null, release.workspace)
        assertEquals("", release.authorName)
        assertEquals(License.MIT, release.defaultLicense)
        assertFalse(release.isSetupCompleted)
    }

    @Test
    fun `la verbosité se projette sur les niveaux du moteur`() {
        assertEquals(LogLevel.INFO, LogVerbosity.NORMAL.toLogLevel())
        assertEquals(LogLevel.DEBUG, LogVerbosity.DETAILED.toLogLevel())
    }

    @Test
    fun `la conversion de licence tolère les valeurs inconnues`() {
        assertEquals(License.MIT, License.fromPersistedName("MIT"))
        assertEquals(License.APACHE_2_0, License.fromPersistedName("APACHE_2_0"))
        assertNull(License.fromPersistedName("Apache-2.0"))
        assertNull(License.fromPersistedName(""))
        assertNull(License.fromPersistedName("n'importe quoi"))
    }

    @Test
    fun `le catalogue de licences couvre les cinq choix du wizard`() {
        // Section 12.3 : Aucune, MIT, Apache-2.0, GPL-3.0, BSD-3-Clause.
        assertEquals(5, License.entries.size)
    }
}
