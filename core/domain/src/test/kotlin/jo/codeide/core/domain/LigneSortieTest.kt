package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du modèle de ligne unique du Centre de sorties (ADR 0160, lot
 * L1) — valeurs par défaut honnêtes, ordre de sévérité de l'échelle à
 * sept valeurs (INDETERMINE en dernier : il passe tout plancher), copie
 * structurée.
 */
class LigneSortieTest {
    @Test
    fun `la ligne par defaut est minimale et honnete`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "Task :app:compileKotlin",
        )

        assertEquals(SourceSortie.BUILD, ligne.source)
        assertEquals("Task :app:compileKotlin", ligne.message)
        assertEquals(0L, ligne.horodatageMs)
        assertNull(ligne.identiteSession)
        assertNull(ligne.idExecution)
        assertEquals(NiveauSortie.INDETERMINE, ligne.niveau)
        assertNull(ligne.etiquette)
        assertNull(ligne.tache)
        assertNull(ligne.statutTache)
        assertNull(ligne.dureeMs)
        assertNull(ligne.octetsRecus)
        assertNull(ligne.octetsTotal)
        assertTrue(ligne.positionsSource.isEmpty())
    }

    @Test
    fun `l echelle de severite croit jusqu a ASSERT puis INDETERMINE en dernier`() {
        val valeurs = NiveauSortie.values()

        assertEquals(NiveauSortie.VERBEUX, valeurs[0])
        assertEquals(NiveauSortie.DEBOGAGE, valeurs[1])
        assertEquals(NiveauSortie.INFO, valeurs[2])
        assertEquals(NiveauSortie.AVERTISSEMENT, valeurs[3])
        assertEquals(NiveauSortie.ERREUR, valeurs[4])
        assertEquals(NiveauSortie.ASSERT, valeurs[5])
        // INDETERMINE est DERNIER : un filtre par niveau minimal ne
        // masque jamais une ligne qu'il ne sait pas graduer (règle 14).
        assertEquals(7, valeurs.size)
        assertEquals(NiveauSortie.INDETERMINE, valeurs[6])
        assertTrue(NiveauSortie.INDETERMINE.ordinal > NiveauSortie.ASSERT.ordinal)
    }

    @Test
    fun `la copie structuree porte tous les champs supplementaires`() {
        val position = PositionSource(
            fichier = "app/src/main.kt",
            ligne = 12,
            colonne = 3,
            gravite = GravitePosition.AVERTISSEMENT,
        )
        val ligne = LigneSortie(
            source = SourceSortie.LOGCAT,
            message = "onCreate terminé",
            horodatageMs = 1_790_000_000_000L,
            identiteSession = "jo.app/4321/principal",
            niveau = NiveauSortie.INFO,
            etiquette = "MainActivity",
            tache = ":app:compileDebugKotlin",
            statutTache = StatutTache.REUSSIE,
            dureeMs = 250L,
            octetsRecus = 512L,
            octetsTotal = 1_024L,
            positionsSource = listOf(position),
        )
        val copie = ligne.copy(niveau = NiveauSortie.AVERTISSEMENT)

        assertEquals(NiveauSortie.AVERTISSEMENT, copie.niveau)
        assertEquals("jo.app/4321/principal", copie.identiteSession)
        assertEquals(1, copie.positionsSource.size)
        assertEquals("app/src/main.kt", copie.positionsSource[0].fichier)
        assertEquals(12, copie.positionsSource[0].ligne)
        assertEquals(3, copie.positionsSource[0].colonne)
        assertEquals(GravitePosition.AVERTISSEMENT, copie.positionsSource[0].gravite)
    }

    @Test
    fun `la position par defaut est une erreur sans colonne`() {
        val position = PositionSource(fichier = "build.gradle.kts", ligne = 2)

        assertNull(position.colonne)
        assertEquals(GravitePosition.ERREUR, position.gravite)
    }
}
