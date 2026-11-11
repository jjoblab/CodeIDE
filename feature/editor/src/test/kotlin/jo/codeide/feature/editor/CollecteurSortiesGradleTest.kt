package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatTacheBuild
import jo.codeide.core.domain.EvenementSyncFlux
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.LigneSortieSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.domain.TelechargementBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.TypeExecution
import jo.codeide.core.domain.VerdictExecution
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [CollecteurSortiesGradle] (lot L3) — le raccord RÉEL du flux
 * tooling derrière le port du Centre de sorties : couplage ÉVÉNEMENT +
 * TEXTE (arbitrage Q6) sur un flux façonné comme le câble, publication
 * par lots, état d'exécution (verdict, compteurs, gravités), flux de
 * sync ordonné, conduite de l'[HistorienExecutions] (en-tête posé au
 * départ, conclusion sur le FAIT du câble).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollecteurSortiesGradleTest {
    private var instant = 1_000L
    private val horloge = TimeProvider { instant }

    /** Les lots reçus du flux (aplani) — rempli à chaque scène. */
    private val lotsRecus = ArrayList<List<LigneSortie>>()

    private lateinit var collecteur: CollecteurSortiesGradle
    private lateinit var registre: FauxRegistreExecutions
    private lateinit var porteeTest: TestScope

    /** Monte la scène : collecteur + historien + faux registre, collecteur
     *  de lots branché SUR LE SCOPE DU TEST (un seul scheduler partagé —
     *  l'émission et la réception ne peuvent pas se manquer). */
    private suspend fun scene(portee: TestScope) {
        porteeTest = portee
        registre = FauxRegistreExecutions()
        val historien = HistorienExecutions(
            registre = registre,
            horloge = horloge,
            journal = FakeAppLogger(),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(portee.testScheduler)),
        )
        collecteur = CollecteurSortiesGradle(horloge = horloge, historien = historien, journal = FakeAppLogger())
        lotsRecus.clear()
        // L'abonné vit en arrière-plan (runTest l'annule à la fin) et se
        // branche IMMÉDIATEMENT (unconfined — SharedFlow ne rejoue rien :
        // une émission sans abonné serait perdue).
        portee.backgroundScope.launch { collecteur.lots.collect { lot -> lotsRecus.add(lot) } }
    }

    /** Les lignes reçues du flux (tous lots aplani) — les reprises des
     *  abonnés d'abord (scheduler partagé du test, exhaustif). */
    private fun lignesRecues(): List<LigneSortie> {
        porteeTest.testScheduler.advanceUntilIdle()
        return lotsRecus.flatten()
    }

    @Test
    fun `le couplage evenement plus texte rattache les lignes a la bonne tache`() =
        runTest(UnconfinedTestDispatcher()) {
            scene(this)
            collecteur.demarrerBuild("b-1", listOf(":app:assembleDebug"))

            // L'ÉVÉNEMENT pose la frontière (arbitrage Q6), AVANT ses lignes.
            collecteur.surTache(EtatTacheBuild("b-1", ":app:compileDebugKotlin", StatutTache.EN_COURS))
            collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "sortie de la tâche", 5))
            // L'en-tête TEXTE ouvre la frontière suivante.
            collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "> Task :app:dexBuilderDebug", 6))
            collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "dexing", 7))

            // La fin du build conclut le lot (le câble fait foi).
            collecteur.surEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 10L))
            val lignes = lignesRecues()
            assertEquals(3, lignes.size)
            assertEquals(SourceSortie.BUILD, lignes[0].source)
            assertEquals(":app:compileDebugKotlin", lignes[0].tache)
            assertEquals(":app:dexBuilderDebug", lignes[1].tache)
            assertEquals(":app:dexBuilderDebug", lignes[2].tache)
            // La frontière ÉVÉNEMENT est posée même SANS en-tête texte (D1).
            assertEquals(
                StatutTache.EN_COURS,
                collecteur.etatExecution.value?.statutsParTache?.get(":app:compileDebugKotlin"),
            )
        }

    @Test
    fun `les positions cliquables et les gravites sont ingerees au passage`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", emptyList())
        collecteur.surLigneBuild(
            LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "e: /projet/app/src/A.kt:12:5 erreur", 5),
        )

        collecteur.surEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 1L))
        val position = lignesRecues().single().positionsSource.single()
        assertEquals("/projet/app/src/A.kt", position.fichier)
        assertEquals(12, position.ligne)
        assertEquals(5, position.colonne)
        assertEquals(1, collecteur.etatExecution.value?.erreurs)
    }

    @Test
    fun `les lignes structurelles ne portent jamais de tache`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", emptyList())
        collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "> Task :app:assembleDebug", 1))
        collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "BUILD SUCCESSFUL in 3s", 2))
        collecteur.surLigneBuild(
            LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "FAILURE: Build failed with an exception.", 3),
        )

        collecteur.surEtatBuild(EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 1L))
        val lignes = lignesRecues()
        assertEquals(":app:assembleDebug", lignes[0].tache)
        assertNull(lignes[1].tache)
        assertNull(lignes[2].tache)
    }

    @Test
    fun `la synthese du verdict porte les compteurs de l evenement`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", listOf(":app:assembleDebug"))
        collecteur.surEtatBuild(
            EtatBuild(
                buildId = "b-1",
                statut = StatutBuild.REUSSI,
                dureeMs = 4_200L,
                tachesActionnables = 12,
                tachesExecutees = 3,
                tachesAJour = 9,
            ),
        )

        val etat = collecteur.etatExecution.value
        assertEquals(VerdictExecution.REUSSI, etat?.verdict)
        assertEquals(4_200L, etat?.dureeMs)
        assertEquals(12, etat?.compteurs?.actionnables)
        assertEquals(3, etat?.compteurs?.executees)
        assertEquals(9, etat?.compteurs?.aJour)
        // La conclusion est passée au registre SUR LE FAIT du câble.
        porteeTest.testScheduler.advanceUntilIdle()
        assertEquals(1, registre.appelsConclure)
        assertEquals(VerdictExecution.REUSSI, registre.executions["b-1"]?.etat?.verdict)
        assertNotNull(registre.executions["b-1"]?.finMs)
    }

    @Test
    fun `l en tete du registre est pose au depart de l execution`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", listOf(":app:assembleDebug"))

        porteeTest.testScheduler.advanceUntilIdle()
        assertEquals(1, registre.appelsDemarrer)
        val entete = registre.executions["b-1"]
        assertEquals(TypeExecution.BUILD, entete?.type)
        assertEquals(listOf(":app:assembleDebug"), entete?.tachesDemandees)
        assertNull(entete?.finMs)
        assertNull(entete?.etat?.verdict)
    }

    @Test
    fun `les lots arrivent au seuil et le cliche suit la publication`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", emptyList())
        repeat(300) { indice ->
            collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "ligne $indice", indice.toLong()))
        }
        // Un lot complet (256) est parti sans attendre la fin du build.
        assertEquals(1, lotsRecus.size)
        assertEquals(256, lotsRecus.single().size)
        // Le cliché suit la PUBLICATION (256) — jamais le lot en attente
        // : un lecteur qui s'attache ne recevrait pas la même ligne
        // deux fois (critère § 10.1.1).
        assertEquals(256, collecteur.clicheSession().size)
        // Le verdict se vide puis conclut : un second lot part (les 44
        // lignes en attente + le verdict).
        collecteur.surLigneBuild(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "BUILD SUCCESSFUL in 1s", 300))
        collecteur.surEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 1_000L))
        assertEquals(2, lotsRecus.size)
        assertEquals(45, lotsRecus.last().size)
        // Chaque ligne a été livrée UNE fois au lecteur.
        val messages = lotsRecus.flatten().map { it.message }
        assertEquals(301, messages.size)
        assertEquals(301, messages.toSet().size)
    }

    @Test
    fun `les telechargements s ecrivent une ligne par artefact termine`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", emptyList())
        // Les ticks d'octets ne réécrivent pas : le compteur avancé écrit
        // UNE ligne (base de parité console v0.46.0, spec § 4.7).
        collecteur.surTelechargement(
            TelechargementBuild(buildId = "b-1", element = "kotlin-stdlib.jar", octetsRecus = 500, compteur = 0),
        )
        collecteur.surTelechargement(
            TelechargementBuild(
                buildId = "b-1",
                element = "kotlin-stdlib.jar",
                octetsRecus = 2_600_000,
                octetsTotal = 2_600_000,
                termine = true,
                dureeMs = 800,
                compteur = 1,
            ),
        )
        collecteur.surTelechargement(
            TelechargementBuild(buildId = "b-1", element = "kotlin-stdlib.jar", octetsRecus = 2_600_000, compteur = 1),
        )
        collecteur.surEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 1L))
        val lignes = lignesRecues().filter { it.etiquette == "kotlin-stdlib.jar" }
        assertEquals(1, lignes.size)
        assertEquals(2_600_000L, lignes.single().octetsRecus)
        assertEquals(2_600_000L, lignes.single().octetsTotal)
    }

    @Test
    fun `le flux de sync porte ses lignes ses etapes et son terminal`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.surEvenementSync(EvenementSyncFlux.Debut(projectDir = "/projet/Demo"))
        collecteur.surEvenementSync(
            EvenementSyncFlux.Ligne(
                LigneSortieSync(flux = FluxSortieBuild.STDOUT, ligne = "Starting Gradle Daemon", horodatageMs = 2),
            ),
        )
        collecteur.surEvenementSync(
            EvenementSyncFlux.Etape(EtapeSyncTooling(etape = EtapeSync.DAEMON, terminee = true, dureeMs = 1_500L)),
        )
        collecteur.surEvenementSync(
            EvenementSyncFlux.Terminal(
                AppResult.Success(
                    ResultatSynchronisation(projectDir = "/projet/Demo", reussie = true, dureeMs = 9_000L),
                ),
            ),
        )

        val lignes = lignesRecues()
        assertEquals(1, lignes.size)
        assertEquals(SourceSortie.SYNC, lignes.single().source)
        // La racine du câble devient la racine d'expurgation du registre.
        porteeTest.testScheduler.advanceUntilIdle()
        val entete = registre.executions.values.single()
        assertEquals("/projet/Demo", entete.projet)
        assertEquals(TypeExecution.SYNC, entete.type)
        assertEquals(VerdictExecution.REUSSI, entete.etat?.verdict)
        assertEquals(9_000L, entete.etat?.dureeMs)
        assertEquals(1_500L, entete.etat?.phases?.single()?.dureeMs)
    }

    @Test
    fun `les lignes d un autre build restent ignorees`() = runTest(UnconfinedTestDispatcher()) {
        scene(this)
        collecteur.demarrerBuild("b-1", emptyList())
        collecteur.surLigneBuild(LigneSortieBuild("b-2", FluxSortieBuild.STDOUT, "étranger", 1))
        assertTrue(lignesRecues().isEmpty())
        assertTrue(collecteur.clicheSession().isEmpty())
    }
}
