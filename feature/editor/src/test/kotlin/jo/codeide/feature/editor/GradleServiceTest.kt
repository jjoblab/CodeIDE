package jo.codeide.feature.editor

import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [GradleService] (G5 ; étape 32 : canaux Taches, transitions de
 * notification, rattachement process-wide) : fenêtre de sortie bornée,
 * lignes du seul build suivi, groupement des diagnostics par fichier,
 * états de synchronisation, pilotage du service Android.
 */
class GradleServiceTest {
    /** Horloge pilotable : les instants de départ (v0.32.5) avancent
     *  à la main — les chronos se vérifient sans cadre Android. */
    private var instant = 1_000L

    /** Démarreur enregistrant : les transitions de notification (étape
     *  32) se vérifient sans cadre Android — le service réel n'est jamais
     *  construit ici. */
    private val demarreur = FauxDemarreurServiceTooling()

    private val service = GradleService(horloge = TimeProvider { instant }, demarreur = demarreur)

    @Test
    fun `les lignes du build suivi s accumulent dans l ordre et portent le canal BUILD`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "a", 0))
        service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDERR, "b", 1))

        assertEquals(
            listOf("a", "b"),
            service.etat.value.lignes
                .map { it.texte },
        )
        assertEquals(
            listOf(FluxSortieBuild.STDOUT, FluxSortieBuild.STDERR),
            service.etat.value.lignes
                .map { it.flux },
        )
        assertEquals(
            "chaque ligne du build porte le canal BUILD (v0.32.5, ADR 0056)",
            listOf(CanalTooling.BUILD, CanalTooling.BUILD),
            service.etat.value.lignes
                .map { it.canal },
        )
    }

    @Test
    fun `les lignes d un autre build sont ignorees`() {
        service.suivreBuild("b-1")
        service.ajouterLigne(LigneSortieBuild("b-autre", FluxSortieBuild.STDOUT, "ailleurs", 0))

        assertTrue(
            service.etat.value.lignes
                .isEmpty(),
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
            service.etat.value.lignes
                .isEmpty(),
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
    fun `la fenetre de sortie est bornee`() {
        service.suivreBuild("b-1")
        repeat(GradleServiceTest.NB_LIGNES_GRAND) { indice ->
            service.ajouterLigne(LigneSortieBuild("b-1", FluxSortieBuild.STDOUT, "ligne $indice", indice.toLong()))
        }

        assertEquals(2_000, service.etat.value.lignes.size)
        assertEquals(
            "ligne ${NB_LIGNES_GRAND - 1}",
            service.etat.value.lignes
                .last()
                .texte,
        )
        assertEquals(
            "ligne ${NB_LIGNES_GRAND - 2_000}",
            service.etat.value.lignes
                .first()
                .texte,
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
            service.etat.value.lignes
                .isEmpty(),
        )
        assertTrue(
            service.etat.value.groupesProblemes
                .isEmpty(),
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
}
