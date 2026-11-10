package jo.codeide.core.domain

import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests des adaptateurs purs vers [LigneSortie] (ADR 0160 § Décision 2,
 * lot L1) — une projection par source existante, sans perte ni invention
 * : niveaux (miroir V/D/I/W/E/F et LogLevel), lignes de build/sync
 * (port tooling), lignes du pont logcat, entrées du journal applicatif,
 * téléchargements (arbitrage Q12).
 */
class AdaptateursSortiesTest {
    @Test
    fun `les niveaux du pont logcat se projettent un a un`() {
        val cas = listOf(
            NiveauJournal.VERBEUX to NiveauSortie.VERBEUX,
            NiveauJournal.DEBOGAGE to NiveauSortie.DEBOGAGE,
            NiveauJournal.INFO to NiveauSortie.INFO,
            NiveauJournal.AVERTISSEMENT to NiveauSortie.AVERTISSEMENT,
            NiveauJournal.ERREUR to NiveauSortie.ERREUR,
            NiveauJournal.ASSERT to NiveauSortie.ASSERT,
        )
        for ((origine, attendu) in cas) {
            assertEquals(attendu, origine.versNiveauSortie())
        }
    }

    @Test
    fun `les niveaux du journal applicatif se projettent sur l echelle`() {
        assertEquals(NiveauSortie.DEBOGAGE, LogLevel.DEBUG.versNiveauSortie())
        assertEquals(NiveauSortie.INFO, LogLevel.INFO.versNiveauSortie())
        assertEquals(NiveauSortie.AVERTISSEMENT, LogLevel.WARN.versNiveauSortie())
        assertEquals(NiveauSortie.ERREUR, LogLevel.ERROR.versNiveauSortie())
    }

    @Test
    fun `le flux de sortie distingue le stdout du stderr`() {
        assertEquals(NiveauSortie.INDETERMINE, FluxSortieBuild.STDOUT.versNiveauSortie())
        assertEquals(NiveauSortie.ERREUR, FluxSortieBuild.STDERR.versNiveauSortie())
    }

    @Test
    fun `la cle de session est canonique et stable`() {
        val identite = IdentiteSessionJournal(
            paquet = "jo.app.exemple",
            pid = 4321,
            nomProcessus = "principal",
        )

        assertEquals("jo.app.exemple/4321/principal", identite.cleSession())
        assertEquals(identite.cleSession(), identite.copy(pid = 4321).cleSession())
    }

    @Test
    fun `la ligne de build garde son contenu son horodatage et son execution`() {
        val ligne = LigneSortieBuild(
            buildId = "b-7",
            flux = FluxSortieBuild.STDOUT,
            ligne = "> Task :app:compileDebugKotlin",
            horodatageMs = 1_790_000_000_000L,
        )

        val sortie = ligne.versLigneSortie(idExecution = "b-7")

        assertEquals(SourceSortie.BUILD, sortie.source)
        assertEquals("> Task :app:compileDebugKotlin", sortie.message)
        assertEquals(1_790_000_000_000L, sortie.horodatageMs)
        assertEquals("b-7", sortie.idExecution)
        assertEquals(NiveauSortie.INDETERMINE, sortie.niveau)
    }

    @Test
    fun `la ligne de sync accepte l absence d execution`() {
        val ligne = LigneSortieSync(
            flux = FluxSortieBuild.STDERR,
            ligne = "FAILURE: Build failed with an exception.",
            horodatageMs = 42L,
        )

        val sortie = ligne.versLigneSortie()

        assertEquals(SourceSortie.SYNC, sortie.source)
        assertEquals(NiveauSortie.ERREUR, sortie.niveau)
        assertNull(sortie.idExecution)
    }

    @Test
    fun `la ligne logcat porte son etiquette et sa session`() {
        val ligne = LigneJournal(
            horodatageMs = 1_000L,
            pid = 4321,
            tid = 4322,
            niveau = NiveauJournal.ERREUR,
            etiquette = "MainActivity",
            message = "onCreate a echoué",
        )

        val sortie = ligne.versLigneSortie(identiteSession = "jo.app.exemple/4321/principal")

        assertEquals(SourceSortie.LOGCAT, sortie.source)
        assertEquals(NiveauSortie.ERREUR, sortie.niveau)
        assertEquals("MainActivity", sortie.etiquette)
        assertEquals("jo.app.exemple/4321/principal", sortie.identiteSession)
        assertEquals(1_000L, sortie.horodatageMs)
    }

    @Test
    fun `l entree du journal applicatif porte son tag et sa session`() {
        val entree = LogEntry(
            timestampMillis = 2_000L,
            sessionId = "session-uuid",
            level = LogLevel.WARN,
            tag = "GradleService",
            threadName = "principal",
            message = "repli sur le cache",
        )

        val sortie = entree.versLigneSortie()

        assertEquals(SourceSortie.JOURNAL, sortie.source)
        assertEquals(NiveauSortie.AVERTISSEMENT, sortie.niveau)
        assertEquals("GradleService", sortie.etiquette)
        assertEquals("session-uuid", sortie.identiteSession)
        assertEquals(2_000L, sortie.horodatageMs)
    }

    @Test
    fun `l exception aplatie du journal part dans le message — jamais perdue en silence`() {
        // Lot L4 : l'écran Diagnostics consomme le lecteur — plus de
        // « journal complet » qui ré-afficherait le détail à côté (ADR
        // 0162) : l'exception aplatie fait partie du message (le lecteur
        // la plie, la recherche la retrouve).
        val cause = jo.codeide.core.model.FlattenedException(
            className = "java.lang.IllegalStateException",
            message = "état invalide",
            frames = listOf("at jo.app.Dépôt.charger(Dépôt.kt:88)", "at jo.app.Vue.rendre(Vue.kt:21)"),
            cause = null,
        )
        val entree = LogEntry(
            timestampMillis = 3_000L,
            sessionId = "session-uuid",
            level = LogLevel.ERROR,
            tag = "Editor",
            threadName = "principal",
            message = "échec d'écriture",
            exception = jo.codeide.core.model.FlattenedException(
                className = "java.io.IOException",
                message = "disque plein",
                frames = listOf("at jo.app.FileSink.ecrire(FileSink.kt:44)"),
                cause = cause,
            ),
        )

        val sortie = entree.versLigneSortie()

        val attendu = "échec d'écriture\n" +
            "java.io.IOException: disque plein\n" +
            "    at jo.app.FileSink.ecrire(FileSink.kt:44)\n" +
            "Caused by: java.lang.IllegalStateException: état invalide\n" +
            "    at jo.app.Dépôt.charger(Dépôt.kt:88)\n" +
            "    at jo.app.Vue.rendre(Vue.kt:21)"
        assertEquals(attendu, sortie.message)

        // Sans exception : le message reste EXACTEMENT celui de l'entrée.
        val sansException = entree.copy(exception = null).versLigneSortie()
        assertEquals("échec d'écriture", sansException.message)
    }

    @Test
    fun `le telechargement porte ses octets et sa duree jamais de pourcentage`() {
        val telechargement = TelechargementBuild(
            buildId = "b-7",
            element = "kotlin-stdlib-2.0.0.jar",
            octetsRecus = 512L,
            octetsTotal = 1_024L,
            dureeMs = 350L,
            compteur = 3,
        )

        val sortie = telechargement.versLigneSortie(source = SourceSortie.BUILD, idExecution = "b-7")

        assertEquals(SourceSortie.BUILD, sortie.source)
        assertEquals("", sortie.message)
        assertEquals(NiveauSortie.INDETERMINE, sortie.niveau)
        assertEquals("kotlin-stdlib-2.0.0.jar", sortie.etiquette)
        assertEquals(512L, sortie.octetsRecus)
        assertEquals(1_024L, sortie.octetsTotal)
        assertEquals(350L, sortie.dureeMs)

        // Sans total connu ni durée : champs honnêtement nuls — jamais de
        // pourcentage inventé (arbitrage Q12).
        val sansTotal = TelechargementBuild(buildId = "b-7", element = "a.pom", octetsRecus = 10L)
        val ligneOuverte = sansTotal.versLigneSortie(source = SourceSortie.SYNC, idExecution = "b-7")
        assertNull(ligneOuverte.octetsTotal)
        assertNull(ligneOuverte.dureeMs)
        assertEquals(10L, ligneOuverte.octetsRecus)
    }
}
