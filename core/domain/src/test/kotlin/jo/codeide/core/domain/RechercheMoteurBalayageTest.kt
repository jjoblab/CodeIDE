package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [RechercheMoteurBalayage] (S1, ADR 0094) — fonctions pures
 * de compilation de motif et d'exclusion, JVM pur.
 */
class RechercheMoteurBalayageTest {
    private val moteur = RechercheMoteurBalayage()

    @Test
    fun `compiler motif literal simple`() {
        val regex = moteur.compilerMotif("main", OptionsRecherche())
        assertNotNull(regex)
        assertTrue(regex!!.find("fun main()") != null)
    }

    @Test
    fun `compiler motif insensible a la casse`() {
        val regex = moteur.compilerMotif("MAIN", OptionsRecherche(ignorerCasse = true))
        assertNotNull(regex)
        assertTrue(regex!!.find("fun main()") != null)
    }

    @Test
    fun `compiler motif mot entier`() {
        val regex = moteur.compilerMotif("main", OptionsRecherche(motEntier = true))
        assertNotNull(regex)
        assertTrue(regex!!.find("fun main()") != null)
        assertFalse(regex.find("mainFunction()") != null)
    }

    @Test
    fun `compiler motif regex invalide retourne null`() {
        val regex = moteur.compilerMotif("[invalid", OptionsRecherche(regex = true))
        assertNull(regex)
    }

    @Test
    fun `est exclu pour dossier build`() {
        val racine = java.io.File("/tmp")
        val fichier = java.io.File("/tmp/project/build/classes/Main.class")
        assertTrue(moteur.estExclu(fichier, racine, OptionsRecherche()))
    }

    @Test
    fun `est exclu pour dossier git`() {
        val racine = java.io.File("/tmp")
        val fichier = java.io.File("/tmp/project/.git/config")
        assertTrue(moteur.estExclu(fichier, racine, OptionsRecherche()))
    }

    @Test
    fun `est exclu pour fichier cache quand option activee`() {
        val racine = java.io.File("/tmp")
        val fichier = java.io.File("/tmp/project/.gitignore")
        assertTrue(moteur.estExclu(fichier, racine, OptionsRecherche(masquerCaches = true)))
        assertFalse(moteur.estExclu(fichier, racine, OptionsRecherche(masquerCaches = false)))
    }

    @Test
    fun `n est pas exclu pour fichier normal`() {
        val racine = java.io.File("/tmp")
        val fichier = java.io.File("/tmp/project/src/Main.kt")
        assertFalse(moteur.estExclu(fichier, racine, OptionsRecherche()))
    }

    private fun <T> assertNotNull(value: T?) {
        org.junit.Assert.assertNotNull(value)
    }
}
