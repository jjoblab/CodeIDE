package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de recherche du lecteur (lot L2) : chemin rapide (cache
 * de pliage) et chemin général rendent les MÊMES verdicts, regex invalide
 * signalée sans exception, mot entier, annulation honnête, décalage des
 * correspondances après troncature de tête.
 */
class RechercheSortiesTest {
    private val lignes =
        listOf(
            LigneSortie(SourceSortie.BUILD, "Compiling Thèses.kt"),
            LigneSortie(SourceSortie.BUILD, "> Task :app:compileDebugKotlin", etiquette = "tache"),
            LigneSortie(SourceSortie.BUILD, "e: file:///home/moi/main.kt:2:8 erreur"),
            LigneSortie(SourceSortie.SYNC, "Download gradient-2.7.mo", etiquette = "artifact"),
        )
    private val messagesPlies =
        listOf(
            "compiling theses.kt",
            "> task :app:compiledebugkotlin",
            "e: file:///home/moi/main.kt:2:8 erreur",
            "download gradient-2.7.mo",
        )
    private val etiquettesPlies = listOf(null, "tache", null, "artifact")

    /** Le tampon produit toujours des pliés alignés — le test vérifie. */
    private fun recherche(requete: RequeteRecherche, annule: () -> Boolean = { false }): ResultatRechercheSorties =
        RechercheSorties.rechercher(lignes, messagesPlies, etiquettesPlies, requete, annule)

    @Test
    fun `sous-chaîne par défaut - casse et accents pliés via le cache`() {
        val resultat = recherche(RequeteRecherche("theses"))
        assertEquals(listOf(0), resultat.correspondances)
        assertFalse(resultat.motifInvalide)
        assertFalse(resultat.interrompue)
    }

    @Test
    fun `le chemin rapide cherche aussi dans l étiquette`() {
        val resultat = recherche(RequeteRecherche("artifact"))
        assertEquals(listOf(3), resultat.correspondances)
    }

    @Test
    fun `le chemin général (options) rend les mêmes verdicts que le rapide`() {
        // Même requête en force le chemin général (sensibleAccents = true)
        // : « theses » doit encore trouver « Thèses » car le PLIAGE de
        // l'aiguille absorbe l'accent.
        val resultat = recherche(
            RequeteRecherche("theses", sensibleAccents = true, sensibleCasse = true),
        )
        assertTrue(resultat.correspondances.isEmpty())
        // Sans accent dans l'aiguille, la casse sensible cherche « Compiling ».
        val casse = recherche(RequeteRecherche("Compiling", sensibleCasse = true))
        assertEquals(listOf(0), casse.correspondances)
    }

    @Test
    fun `mot entier délimite l occurrence`() {
        val entier = recherche(RequeteRecherche("tache", motEntier = true))
        assertEquals(listOf(1), entier.correspondances)
        val fragment = recherche(RequeteRecherche("tache", motEntier = false))
        assertEquals(listOf(1), fragment.correspondances)
        // « erreur » entier ne matche pas dans « erred » — mais ici il est
        // entier ; en revanche « tach » entier ne matche pas « tache ».
        val incommplet = recherche(RequeteRecherche("tach", motEntier = true))
        assertTrue(incommplet.correspondances.isEmpty())
    }

    @Test
    fun `regex valide et invalide sans exception`() {
        val valide = recherche(RequeteRecherche("e: .*:\\d+:\\d+:?", regex = true))
        assertEquals(listOf(2), valide.correspondances)
        val invalide = recherche(RequeteRecherche("[", regex = true))
        assertTrue(invalide.correspondances.isEmpty())
        assertTrue(invalide.motifInvalide)
    }

    @Test
    fun `annulation en cours - résultat partiel honnête`() {
        var sondages = 0
        val resultat = recherche(RequeteRecherche("task")) {
            sondages++
            true
        }
        assertTrue(resultat.interrompue)
        assertTrue(resultat.correspondances.isEmpty())
        assertTrue(sondages > 0)
    }

    @Test
    fun `requête vide - aucun état`() {
        assertTrue(recherche(RequeteRecherche("")).correspondances.isEmpty())
    }

    @Test
    fun `décalage après troncature de tête`() {
        val resultat = ResultatRechercheSorties(listOf(0, 2, 3)).decaler(2)
        assertEquals(listOf(0, 1), resultat.correspondances)
    }
}
