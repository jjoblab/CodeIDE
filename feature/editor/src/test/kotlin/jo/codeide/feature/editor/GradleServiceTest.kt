package jo.codeide.feature.editor

import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [GradleService] (G5 ; étape 32 : canaux Taches, transitions de
 * notification, rattachement process-wide ; v3 : lignes typées ; v0.42.0 :
 * flux dédié de la zone texte ; **v0.46.0 — console FLUX BRUT (ADR 0078) :
 * chaque ligne porte son CANAL et son STYLE, les étapes de sync et les
 * téléchargements écrivent des LIGNES, plus aucune rangée dans l'état** —
 * les tâches ne produisent plus rien : Gradle écrit les siennes sur
 * stdout, les dupliquer était le problème « deux endroits »).
 */
class GradleServiceTest {
    /** Horloge pilotable : les instants de départ (v0.32.5) avancent
     *  à la main — les chronos se vérifient sans cadre Android. */
    private var instant = 1_000L

    /** Démarreur enregistrant : les transitions de notification (étape
     *  32) se vérifient sans cadre Android — le service réel n'est jamais
     *  construit ici. */
    private val demarreur = FauxDemarreurServiceTooling()

    private val service =
        GradleService(horloge = TimeProvider { instant }, demarreur = demarreur, journal = FakeAppLogger())

    /** Événements de la console conservés par le rejeu (v0.42.0 — le
     *  rejeu EST le tampon borné : la liste reflète ce qu'une vue qui
     *  s'abonne reconstruirait). */
    private fun evenementsTexte(): List<EvenementConsoleTexte> =
        service.lignesBrutes.replayCache.filterIsInstance<EvenementConsoleTexte>()

    /** Lignes de la console (le genre seul — les vidages ont leur propre
     *  genre). */
    private fun lignesConsole(): List<EvenementConsoleTexte.Ligne> =
        evenementsTexte().filterIsInstance<EvenementConsoleTexte.Ligne>()

    /** Texte brut des lignes de la console (les lignes brutes de Gradle
     *  voyagent en [TexteTooling.Brut] ; les lignes composées — étapes,
     *  téléchargements, synthèses — deviennent vides ici : elles ont leurs
     *  PROPRES assertions sur libelle/style). */
    private fun textesConsole(): List<String> =
        lignesConsole().map { (it.libelle as? TexteTooling.Brut)?.texte.orEmpty() }

    // ------------------------------------------------------------------
    // v0.46.0 — console flux brut (canal, style, ordre).
    // ------------------------------------------------------------------

    @Test
    fun `les lignes brutes s accumulent dans l ordre sur le flux dedie - jamais dans l etat`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "a", 0))
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDERR, "b", 1))

        assertEquals(
            listOf("a", "b"),
            textesConsole(),
        )
        assertEquals(
            listOf(StyleLigne.SORTIE, StyleLigne.ERREUR),
            lignesConsole().map { it.style },
        )
        assertTrue(
            "v0.46.0 : chaque ligne du build porte le canal BUILD (le filtre du fragment choisit sa console)",
            lignesConsole().all { it.canal == CanalTooling.BUILD },
        )
        assertTrue(
            "v0.42.0 : plus AUCUNE ligne de console dans l état — aucune émission d état par ligne, " +
                "la cause du O(N²) historique (phase 1 du roadmap)",
            service.etat.value.etapesSync
                .isEmpty(),
        )
    }

    @Test
    fun `les lignes d un autre build sont ignorees`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-autre", FluxSortieBuild.STDOUT, "ailleurs", 0))

        assertTrue(
            "la garde buildId s applique au flux dédié comme à l ancienne fenêtre",
            lignesConsole().isEmpty(),
        )
    }

    @Test
    fun `les lignes d un build annule sont ignorees - et l annulation conclut la console`() {
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.ANNULE))

        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "trop tard", 0))

        assertTrue(
            "la garde annulation s applique au flux dédié",
            textesConsole().none { it == "trop tard" },
        )
        assertEquals(
            "v0.46.0 : l annulation reçoit SA ligne de conclusion — Gradle n'imprime rien " +
                "de tel sur son flux après un cancel en pleine configuration",
            listOf(CanalTooling.BUILD to StyleLigne.SYNTHESE),
            lignesConsole().map { it.canal to it.style },
        )
    }

    @Test
    fun `un build reussi ne produit AUCUNE ligne de conclusion - Gradle conclut lui meme`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "> Task :app:build", 0))
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 6))

        assertEquals(
            "v0.46.0 (parité Android Studio) : « BUILD SUCCESSFUL in 6s » et « N actionable " +
                "tasks » sont des lignes DE GRADLE (stdout) — les dupliquer était le problème",
            listOf("> Task :app:build"),
            textesConsole(),
        )
    }

    @Test
    fun `un nouveau build vide la console remet l etat et memorise taches et depart`() {
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 12))
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "a", 0))

        instant = 5_000L
        service.suivreBuild("b-2", listOf("assembleDebug"))

        assertTrue(
            service.etat.value.etapesSync
                .isEmpty(),
        )
        assertEquals(
            "le Vider conclut l ancienne console du canal BUILD : une vue qui s abonne " +
                "reconstruit VIERGE puis suit le nouveau build",
            EvenementConsoleTexte.Vider(CanalTooling.BUILD),
            evenementsTexte().last(),
        )
        assertEquals(StatutBuild.EN_COURS, service.etat.value.statutBuild)
        assertNull(service.etat.value.dureeBuildMs)
        assertEquals(
            "les tâches demandées voyagent avec le build (v0.32.5)",
            listOf("assembleDebug"),
            service.etat.value.taches,
        )
        assertEquals(
            "l instant de départ du chrono vient de l horloge injectée (v0.32.5)",
            5_000L,
            service.etat.value.debutBuildMs,
        )
    }

    @Test
    fun `la fenetre de sortie est bornee - tete tronquee`() {
        service.suivreBuild("b-1")
        repeat(GradleServiceTest.NB_LIGNES_GRAND) { indice ->
            service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "ligne $indice", indice.toLong()))
        }

        assertEquals(
            "le rejeu EST le tampon borné : 2 000 événements au plus (le Vider du " +
                "suivi est tombé de la tête avec les 500 premières lignes)",
            2_000,
            evenementsTexte().size,
        )
        assertEquals(
            "ligne ${NB_LIGNES_GRAND - 1}",
            textesConsole().last(),
        )
        assertEquals(
            "ligne ${NB_LIGNES_GRAND - 2_000}",
            textesConsole().first(),
        )
    }

    // ------------------------------------------------------------------
    // v0.46.0 — téléchargements du build (UNE LIGNE par artefact terminé).
    // ------------------------------------------------------------------

    @Test
    fun `les telechargements du build ecrivent UNE ligne par artefact termine - jamais par tick`() {
        service.suivreBuild("b-1")
        // Tick d'octets intermédiaire (compteur inchangé) : RIEN.
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-1",
                element = "kotlin-stdlib.jar",
                octetsRecus = 900_000,
                termine = false,
                compteur = 0,
            ),
        )
        assertTrue(
            "le détail d'octets EN VOL vit dans l'en-tête du panneau, pas dans la console",
            lignesConsole().none { it.style == StyleLigne.TELECHARGEMENT },
        )

        // Premier artefact terminé (compteur 0 → 1) : SA ligne.
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-1",
                element = "kotlin-stdlib.jar",
                octetsRecus = 869_000,
                termine = true,
                compteur = 1,
            ),
        )
        // Tick intermédiaire du deuxième artefact (compteur toujours 1) : RIEN.
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-1",
                element = "gradle-9.7.1-all.zip",
                octetsRecus = 60_000_000,
                termine = false,
                compteur = 1,
            ),
        )
        // Deuxième artefact terminé (1 → 2) : SA ligne, volume CUMULÉ.
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-1",
                element = "gradle-9.7.1-all.zip",
                octetsRecus = 70_000_000,
                termine = true,
                compteur = 2,
            ),
        )

        val lignesTelechargement = lignesConsole().filter { it.style == StyleLigne.TELECHARGEMENT }
        assertEquals("une ligne par artefact TERMINÉ, jamais par tick", 2, lignesTelechargement.size)
        assertTrue(lignesTelechargement.all { it.canal == CanalTooling.BUILD })
        val premiere = lignesTelechargement.first().libelle as TexteTooling.Ressource
        assertEquals(
            "le volume de la ligne est le CUMUL des artefacts terminés (900 Ko du tick + 869 Ko)",
            listOf("kotlin-stdlib.jar", "1.7 Mo"),
            premiere.args,
        )
    }

    @Test
    fun `les telechargements d un autre build ou hors vol sont ignores`() {
        service.suivreBuild("b-1")
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-autre",
                element = "ailleurs.jar",
                octetsRecus = 10,
                termine = true,
                compteur = 1,
            ),
        )
        assertTrue(
            "la garde buildId s applique",
            lignesConsole().none { it.style == StyleLigne.TELECHARGEMENT },
        )

        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 5))
        service.ajouterTelechargement(
            jo.codeide.core.domain.TelechargementBuild(
                buildId = "b-1",
                element = "trop-tard.jar",
                octetsRecus = 10,
                termine = true,
                compteur = 1,
            ),
        )
        assertTrue(
            "les téléchargements ne s'écrivent QUE pendant le vol — le statut terminal conclut",
            textesConsole().none { it.contains("trop-tard") },
        )
    }

    // ------------------------------------------------------------------
    // v0.46.0 — étapes de synchronisation (lignes accumulées + état pour
    // l'en-tête ; la nouvelle sync vide la console du canal SYNC).
    // ------------------------------------------------------------------

    @Test
    fun `une etape de sync ecrit sa ligne de depart puis sa ligne de conclusion - l etat garde l affichage`() {
        service.marquerSyncEnCours()
        service.ajouterEtapeSync(EtapeSyncTooling(etape = EtapeSync.DAEMON))
        service.ajouterEtapeSync(EtapeSyncTooling(etape = EtapeSync.DAEMON, octetsRecus = 12))
        service.ajouterEtapeSync(
            EtapeSyncTooling(etape = EtapeSync.DAEMON, terminee = true, dureeMs = 4_200),
        )

        val etat = service.etat.value
        assertEquals(
            "l'état ne porte QU'UNE entrée par étape (l'en-tête y lit son compteur)",
            1,
            etat.etapesSync.size,
        )
        assertEquals(EtapeSync.DAEMON, etat.etapesSync.single().etape)
        assertTrue(etat.etapesSync.single().terminee)
        assertEquals(4_200L, etat.etapesSync.single().dureeMs)

        val lignesSync = lignesConsole().filter { it.canal == CanalTooling.SYNC }
        assertEquals(
            "départ UNE fois (le tick intermédiaire n'écrit rien), conclusion UNE fois",
            2,
            lignesSync.size,
        )
        assertEquals(StyleLigne.ETAPE, lignesSync.first().style)
        assertEquals(StyleLigne.ETAPE, lignesSync.last().style)
    }

    @Test
    fun `une nouvelle sync vide la console du canal SYNC et reannonce ses etapes`() {
        service.marquerSyncEnCours()
        service.ajouterEtapeSync(EtapeSyncTooling(etape = EtapeSync.DAEMON))
        service.ajouterEtapeSync(
            EtapeSyncTooling(etape = EtapeSync.DAEMON, terminee = true, dureeMs = 100),
        )
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 500)),
        )

        // Deuxième sync : la console SYNC repart VIERGE (Vider), l'état des
        // étapes aussi — la phase réannoncée écrit une ligne NEUVE.
        service.marquerSyncEnCours()
        service.ajouterEtapeSync(EtapeSyncTooling(etape = EtapeSync.DAEMON))

        assertEquals(
            "la fenêtre d'étapes repart vierge à chaque sync",
            1,
            service.etat.value.etapesSync.size,
        )
        assertFalse(
            service.etat.value.etapesSync
                .single()
                .terminee,
        )
        val apresVider =
            evenementsTexte()
                .drop(
                    evenementsTexte().indexOfLast {
                        it is EvenementConsoleTexte.Vider && it.canal == CanalTooling.SYNC
                    } +
                        1,
                ).filterIsInstance<EvenementConsoleTexte.Ligne>()
        assertEquals(
            "le Vider du canal SYNC conclut l'ancienne console : la reconstruction " +
                "après vidage ne contient QUE l'étape réannoncée",
            listOf(StyleLigne.ETAPE),
            apresVider.map { it.style },
        )
    }

    @Test
    fun `la sync reussie conclut la console - la ligne porte la duree et le canal SYNC`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 8_400)),
        )

        val conclusion = lignesConsole().single()
        assertEquals(CanalTooling.SYNC, conclusion.canal)
        assertEquals(StyleLigne.SYNTHESE, conclusion.style)
    }

    @Test
    fun `la sync sans telechargement se dit a jour dans sa conclusion`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 500)),
        )

        val conclusion = lignesConsole().single().libelle
        assertTrue(
            "aucune étape n'a reçu d'octet : la conclusion se dit « à jour »",
            conclusion is TexteTooling.Ressource &&
                conclusion.id == R.string.editor_console_synthese_sync_a_jour_duree,
        )
    }

    // v6 (prompt de suivi §2) : le test `une etape SAUTEE en cache s affiche
    // conclue sans duree` est SUPPRIMÉ — le drapeau `sautee` n'existe plus.
    // Une distribution en cache n'est pas émise par le serveur, le client
    // ne la reçoit pas et ne l'affiche pas.

    // ------------------------------------------------------------------
    // v3 — correctif C5 : l'avertissement bénin du daemon voyage apaisé.
    // ------------------------------------------------------------------

    @Test
    fun `l avertissement bénin du daemon Gradle est apaise - pas le reste de stderr`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(
            LigneSortieBuild(
                "b-1",
                FluxSortieBuild.STDERR,
                "Unable to set daemon's environment variables to match the client because:",
                0,
            ),
        )
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDERR, "échec de compilation", 1))
        service.ajouterLigne(
            LigneSortieBuild(
                "b-1",
                FluxSortieBuild.STDOUT,
                "Unable to set daemon's environment variables (écho stdout, non apaisé)",
                2,
            ),
        )

        assertEquals(
            "seule la ligne stderr CONNUE est apaisée (C5)",
            listOf(StyleLigne.APAISEE, StyleLigne.ERREUR, StyleLigne.SORTIE),
            lignesConsole().map { it.style },
        )
    }

    @Test
    fun `les diagnostics sont groupes par fichier et tries par ligne`() {
        service.publierDiagnostics(
            listOf(
                diagnostic("A.java", 8),
                diagnostic("B.java", 2),
                diagnostic("A.java", 3),
            ),
        )

        val groupes = service.etat.value.groupesProblemes
        assertEquals(2, groupes.size)
        assertEquals(listOf(3, 8), groupes.first { it.nomFichier == "A.java" }.problems.map { it.ligne.toInt() })
        assertEquals(3, service.etat.value.problemesTotal)
    }

    @Test
    fun `la synchronisation reussie se publie l echec porte son message et le canal suit`() {
        instant = 2_000L
        service.marquerSyncEnCours()
        assertTrue(service.etat.value.synchronisationEnCours)
        assertEquals(
            "l instant de départ du chrono de sync vient de l horloge (v0.32.5)",
            2_000L,
            service.etat.value.debutSyncMs,
        )
        assertEquals(
            "la sync en cours EST le canal actif (v0.32.5)",
            CanalTooling.SYNC,
            service.etat.value.canalActif,
        )

        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 42)),
        )
        assertFalseEnCours()
        assertNull(
            "plus d activité : le canal actif retombe à null (v0.32.5)",
            service.etat.value.canalActif,
        )
        assertEquals(CanalTooling.SYNC, service.etat.value.canalDernierResultat)

        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté")),
        )
        assertFalseEnCours()
        assertEquals("non connecté", service.etat.value.messageEchecSync)
    }

    @Test
    fun `le canal actif est BUILD pendant un build et retombe a null apres`() {
        assertNull(service.etat.value.canalActif)

        service.suivreBuild("b-1")
        assertEquals(CanalTooling.BUILD, service.etat.value.canalActif)
        assertTrue(service.etat.value.activiteEnCours)

        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 12))
        assertNull(service.etat.value.canalActif)
        assertFalse(service.etat.value.activiteEnCours)
        assertEquals(CanalTooling.BUILD, service.etat.value.canalDernierResultat)
    }

    @Test
    fun `le marquage de sync est idempotent - l annonce du serveur ne remet pas le chrono`() {
        instant = 3_000L
        service.marquerSyncEnCours()

        // L'événement SyncStarted du serveur retarde : le geste local a
        // déjà posé le chrono — la confirmation ne le remet PAS à zéro
        // (étape 32, ADR 0057).
        instant = 3_500L
        service.marquerSyncEnCours()

        assertEquals(
            "le départ du chrono reste celui du premier marquage (étape 32)",
            3_000L,
            service.etat.value.debutSyncMs,
        )
    }

    @Test
    fun `le listage des taches vit sur SON canal puis retombe`() {
        instant = 7_000L
        service.marquerTachesEnCours()
        assertEquals(
            "le listage en vol EST le canal actif Taches (étape 32)",
            CanalTooling.TACHES,
            service.etat.value.canalActif,
        )
        assertEquals(7_000L, service.etat.value.debutTachesMs)

        service.tachesTerminees()
        assertNull(service.etat.value.canalActif)
        assertNull(
            "le canal Taches ne produit pas de résultat d en-tête : le sélecteur est le résultat (étape 32)",
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `le demarreur du service est appele a chaque depart d activite - jamais en cours d activite`() {
        // Aucune activité : rien n'est lancé (le service s'arrête de lui-même).
        service.publierConnexion(jo.codeide.core.domain.EtatConnexion.CONNECTEE)
        assertEquals(0, demarreur.lancements)

        // Premier départ d'activité (sync) : le service part.
        service.marquerSyncEnCours()
        assertEquals(1, demarreur.lancements)

        // Une SECONDE activité commence SANS que la première ne finisse :
        // le service vivant n'est pas relancé.
        service.marquerTachesEnCours()
        assertEquals(
            "le service vivant n est pas relancé en cours d activité (étape 32)",
            1,
            demarreur.lancements,
        )

        // Le résultat tombe (le service s'arrêtera de lui-même sur sa
        // notification finale), puis une AUTRE activité repart : le
        // service repart AVEC elle.
        service.tachesTerminees()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 42)),
        )
        assertEquals(
            "l arrêt appartient au service, pas au détenteur d état (étape 32)",
            1,
            demarreur.lancements,
        )

        service.suivreBuild("b-1")
        assertEquals(
            "un nouveau départ d activité relance le service arrêté (étape 32)",
            2,
            demarreur.lancements,
        )
    }

    @Test
    fun `attacher vide les vues de l espace et conserve les activites en vol`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "a", 0))
        service.publierDiagnostics(listOf(diagnostic("A.java", 8)))

        service.attacher()

        assertTrue(
            "la console repart vierge pour le nouvel espace (étape 32)",
            service.etat.value.etapesSync
                .isEmpty(),
        )
        assertTrue(
            service.etat.value.groupesProblemes
                .isEmpty(),
        )
        assertEquals(
            "v0.46.0 : les DEUX canaux sont vidés — le dernier Vider est celui du canal SYNC, " +
                "une vue qui s abonne reconstruit depuis ces vidages",
            EvenementConsoleTexte.Vider(CanalTooling.SYNC),
            evenementsTexte().last(),
        )
        assertEquals(
            "le build en vol survit au changement d écran (étape 32)",
            StatutBuild.EN_COURS,
            service.etat.value.statutBuild,
        )
        assertEquals("b-1", service.etat.value.buildId)
    }

    private fun assertFalseEnCours() {
        assertTrue(
            service.etat.value.synchronisationEnCours
                .not(),
        )
    }

    private companion object {
        /** Dépasse largement la fenêtre (2 000). */
        const val NB_LIGNES_GRAND = 2_500
    }
}

/** Diagnostic de test minimal. */
internal fun diagnostic(
    fichier: String,
    ligne: Int,
): DiagnosticBuild =
    DiagnosticBuild(
        severite = SeveriteDiagnostic.ERREUR,
        fichier = fichier,
        ligne = ligne.toLong(),
        colonne = 1,
        message = "erreur de test",
        source = "javac",
    )

/** Faux du port de lancement du service (étape 32) : compte, ne lance rien. */
internal class FauxDemarreurServiceTooling : DemarreurServiceTooling {
    /** Nombre de lancements demandés au service Android. */
    var lancements: Int = 0

    override fun demarrer() {
        lancements++
    }

    @Test
    fun `les etapes derivees des lignes portent leur statut et leur detail - v4`() {
        val service = GradleService(TimeProvider { 0L }, FauxDemarreurServiceTooling(), FakeAppLogger())

        service.ajouterEtapeSync(EtapeSyncTooling(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 12))
        service.ajouterEtapeSync(
            EtapeSyncTooling(
                etape = EtapeSync.DEPENDANCES,
                octetsRecus = 44_040_192L,
                element = "kotlin-stdlib.jar",
                compteur = 3,
            ),
        )

        val etat = service.etat.value
        assertEquals(2, etat.etapesAffichees.size)
        assertEquals(EtapeSync.OUTILS, etat.etapesAffichees[0].etape)
        assertEquals(12L, etat.etapesAffichees[0].dureeMs)
        assertEquals(EtapeSync.DEPENDANCES, etat.etapeCourante?.etape)
        assertEquals("kotlin-stdlib.jar", etat.etapeCourante?.element)
        // Compteur de l'en-tête : position dans le plan d'AFFICHAGE v5 —
        // DEPENDANCES partage la rangée « Dépendances et modèle IDE » (6/7).
        assertEquals(6, etat.numeroEtape)
        assertEquals(EtapeConsoleSync.entries.size, etat.totalEtapes)
    }

    @Test
    fun `les taches disponibles se publient puis s invalident a la sync suivante - v4`() {
        val service = GradleService(TimeProvider { 0L }, FauxDemarreurServiceTooling(), FakeAppLogger())

        service.publierTachesDisponibles(listOf(InfoTache(chemin = ":app:build", nomAffiche = "build")))
        assertEquals(
            1,
            service.etat.value.tachesDisponibles
                ?.size,
        )

        service.marquerSyncEnCours()
        assertEquals(
            "une nouvelle sync invalide les tâches connues (§3.2)",
            null,
            service.etat.value.tachesDisponibles,
        )
    }
}
