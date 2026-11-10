package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du filtre unifié des sorties (ADR 0160 § Décision 3, lot L1) —
 * niveau (plancher OU ensemble explicite), texte (sous-chaîne pliée,
 * regex, mot entier), étiquettes, sources, sessions, plage de temps ;
 * le motif regex invalide se signale ([FiltreSorties.erreurMotif]) sans
 * jamais lever d'exception (héritage `FiltreLogcat`).
 */
class FiltreSortiesTest {
    private fun ligne(
        source: SourceSortie = SourceSortie.BUILD,
        message: String = "BUILD SUCCESSFUL in 42s",
        niveau: NiveauSortie = NiveauSortie.INDETERMINE,
        etiquette: String? = null,
    ): LigneSortie = LigneSortie(
        source = source,
        message = message,
        horodatageMs = 1_000L,
        niveau = niveau,
        etiquette = etiquette,
    )

    @Test
    fun `le filtre par defaut accepte tout`() {
        val filtre = FiltreSorties()

        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.VERBEUX)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.ASSERT)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.INDETERMINE)))
        assertFalse(filtre.erreurMotif)
    }

    @Test
    fun `le plancher masque les niveaux inferieurs mais laisse INDETERMINE passer`() {
        val filtre = FiltreSorties(niveauMinimal = NiveauSortie.AVERTISSEMENT)

        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.AVERTISSEMENT)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.ERREUR)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.ASSERT)))
        // Une ligne de flux sans sévérité ne viole pas un plancher —
        // jamais de perte silencieuse (règle 14).
        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.INDETERMINE)))
        assertFalse(filtre.accepte(ligne(niveau = NiveauSortie.INFO)))
        assertFalse(filtre.accepte(ligne(niveau = NiveauSortie.VERBEUX)))
    }

    @Test
    fun `l ensemble de niveaux est une membership stricte qui prevaut sur le plancher`() {
        val filtre = FiltreSorties(
            niveauMinimal = NiveauSortie.INFO,
            niveaux = setOf(NiveauSortie.ERREUR),
        )

        assertTrue(filtre.accepte(ligne(niveau = NiveauSortie.ERREUR)))
        // Membership stricte : même INDETERMINE est masqué par une
        // sélection EXPLICITE — c'est le seul cas où le filtre le cache.
        assertFalse(filtre.accepte(ligne(niveau = NiveauSortie.INDETERMINE)))
        assertFalse(filtre.accepte(ligne(niveau = NiveauSortie.INFO)))
        // Ensemble vide : aucune contrainte, le plancher reprend la main.
        val sansEnsemble = FiltreSorties(
            niveauMinimal = NiveauSortie.INFO,
            niveaux = emptySet(),
        )
        assertTrue(sansEnsemble.accepte(ligne(niveau = NiveauSortie.ASSERT)))
        // Une ligne INFO ne VIOLE pas un plancher INFO (le plancher masque
        // les niveaux INFÉRIEURS, lui-même compris comme admis).
        assertFalse(sansEnsemble.accepte(ligne(niveau = NiveauSortie.VERBEUX)))
    }

    @Test
    fun `la recherche plie casse et accents sur message et etiquette`() {
        val filtre = FiltreSorties(texte = "theses")
        val avecAccents = LigneSortie(
            source = SourceSortie.JOURNAL,
            message = "Indexation de « Thèses » terminée",
        )

        assertTrue(filtre.accepte(avecAccents))
        assertTrue(filtre.accepte(ligne(message = "PROJET THESES compilé")))
        assertFalse(filtre.accepte(ligne(message = "rien à voir ici")))
        // La sous-chaîne cherche aussi dans l'étiquette (parité FiltreLogcat).
        assertTrue(filtre.accepte(ligne(message = "sortie tâche", etiquette = "compileTheses")))
        // Sensibilité aux accents : «theses» ne trouve plus «Thèses».
        val sensible = FiltreSorties(texte = "theses", sensibleAccents = true)
        assertFalse(sensible.accepte(avecAccents))
    }

    @Test
    fun `le motif regex reconnait ses lignes et signale ses erreurs`() {
        val filtre = FiltreSorties(texte = "BUILD (\\w+) in \\d+ms", regex = true)

        assertTrue(filtre.accepte(ligne(message = "BUILD SUCCESSFUL in 954ms")))
        assertFalse(filtre.accepte(ligne(message = "BUILD SUCCESSFUL in 2s")))
        assertFalse(filtre.erreurMotif)

        // Motif invalide : repli « aucune correspondance » + erreur
        // signalée — JAMAIS d'exception vers l'appelant.
        val invalide = FiltreSorties(texte = "[invalide", regex = true)
        assertTrue(invalide.erreurMotif)
        assertFalse(invalide.accepte(ligne(message = "BUILD SUCCESSFUL in 954ms")))
        assertEquals(0, invalide.filtrer(listOf(ligne(), ligne())).size)
    }

    @Test
    fun `la recherche par mot entier exige des frontieres de mot`() {
        val filtre = FiltreSorties(texte = "build", motEntier = true)

        assertTrue(filtre.accepte(ligne(message = "BUILD SUCCESSFUL in 42s")))
        assertTrue(filtre.accepte(ligne(message = "le build est lancé")))
        assertFalse(filtre.accepte(ligne(message = "le rebuild est lancé")))
        assertTrue(filtre.accepte(ligne(message = "action sur l'étiquette", etiquette = "build")))
    }

    @Test
    fun `les etiquettes inclues et exclues s appliquent exactement`() {
        val inclues = FiltreSorties(etiquettesInclues = setOf("gradle-daemon"))
        assertTrue(inclues.accepte(ligne(etiquette = "gradle-daemon")))
        assertFalse(inclues.accepte(ligne(etiquette = "Gradle-Daemon")))
        assertFalse(inclues.accepte(ligne(etiquette = null)))

        val exclues = FiltreSorties(etiquettesExclues = setOf("chatty"))
        assertTrue(exclues.accepte(ligne(etiquette = "gradle-daemon")))
        assertFalse(exclues.accepte(ligne(etiquette = "chatty")))
        assertTrue(exclues.accepte(ligne(etiquette = null)))
    }

    @Test
    fun `les sources et sessions filtrent par appartenance`() {
        val filtre = FiltreSorties(sources = setOf(SourceSortie.BUILD, SourceSortie.SYNC))

        assertTrue(filtre.accepte(ligne(source = SourceSortie.BUILD)))
        assertTrue(filtre.accepte(ligne(source = SourceSortie.SYNC)))
        assertFalse(filtre.accepte(ligne(source = SourceSortie.LOGCAT)))

        // Les sessions admettent l'identité de session de journaux OU
        // l'identifiant d'exécution Gradle.
        val sessions = FiltreSorties(sessions = setOf("jo.app/4321/principal", "exec-42"))
        assertTrue(
            sessions.accepte(
                ligne(source = SourceSortie.LOGCAT).copy(identiteSession = "jo.app/4321/principal"),
            ),
        )
        assertTrue(sessions.accepte(ligne().copy(idExecution = "exec-42")))
        assertFalse(sessions.accepte(ligne().copy(idExecution = "exec-99")))
        assertFalse(sessions.accepte(ligne()))
    }

    @Test
    fun `la plage de temps borne les deux extremites inclusivement`() {
        val filtre = FiltreSorties(debutMs = 1_000L, finMs = 2_000L)

        assertTrue(filtre.accepte(ligne().copy(horodatageMs = 1_000L)))
        assertTrue(filtre.accepte(ligne().copy(horodatageMs = 2_000L)))
        assertFalse(filtre.accepte(ligne().copy(horodatageMs = 999L)))
        assertFalse(filtre.accepte(ligne().copy(horodatageMs = 2_001L)))
        // Une seule borne suffit.
        assertTrue(FiltreSorties(debutMs = 1_500L).accepte(ligne().copy(horodatageMs = 2_000L)))
        assertFalse(FiltreSorties(debutMs = 1_500L).accepte(ligne().copy(horodatageMs = 1_000L)))
    }

    @Test
    fun `filtrer applique le filtre a une liste entiere en gardant l ordre`() {
        val filtre = FiltreSorties(texte = "compile")
        val lignes = listOf(
            ligne(message = "> Task :app:compileDebugKotlin"),
            ligne(message = "BUILD SUCCESSFUL in 42s"),
            ligne(message = "> Task :app:compileReleaseKotlin"),
        )
        val retenues = filtre.filtrer(lignes)

        assertEquals(2, retenues.size)
        assertEquals("> Task :app:compileDebugKotlin", retenues[0].message)
        assertEquals("> Task :app:compileReleaseKotlin", retenues[1].message)
    }
}
