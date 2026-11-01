package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de COHÉRENCE du « dernier résultat » tooling (INC-01, volet C du
 * prompt « retard d'affichage des sorties de build ») : le canal du
 * dernier résultat est l'activité terminée la PLUS RÉCENTE — le repère
 * d'ordre ([EtatGradle.sequenceResultatSync] /
 * [EtatGradle.sequenceResultatBuild]) posé par [GradleService] à chaque
 * conclusion décide, pas une priorité fixe de canal. Avant le correctif,
 * une sync réussie suivie d'un build échoué laissait l'en-tête au repos
 * sur « Synchronisé en … » avec sa coche verte : le scénario même du
 * propriétaire (sync ✓ puis trois builds ✗), reproduit par le premier
 * test de cette classe.
 *
 * Les consommateurs — en-tête ([PresentationTooling.etatEntete]) et
 * notification finale ([decisionNotificationTooling]) — dérivent du MÊME
 * [EtatGradle.canalDernierResultat] : le test croisé du scénario du
 * propriétaire vérifie la chaîne de bout en bout (les sorties chip,
 * icône de canal et statut de console suivront avec l'INC-02, dérivation
 * unique `CanalAffiche`).
 */
class CoherenceDernierResultatTest {
    /** Horloge pilotable : les chronos se vérifient sans cadre Android. */
    private var instant = 1_000L

    private val service =
        GradleService(
            horloge = TimeProvider {
                instant
            },
            demarreur = FauxDemarreurServiceTooling(),
            journal = FakeAppLogger(),
        )

    // ------------------------------------------------------------------
    // Table de scénarios du prompt §6.1 (INC-01) : chronologie croisée
    // sync/build, service-driven (le repère d'ordre vient du service).
    // ------------------------------------------------------------------

    @Test
    fun `sync reussie puis build echoue - le dernier resultat est le BUILD`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000)),
        )
        assertEquals(CanalTooling.SYNC, service.etat.value.canalDernierResultat)

        service.suivreBuild("b-1")
        service.publierEtatBuild(
            EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 36_000, messageEchec = "compileKotlin a échoué"),
        )

        assertEquals(
            "INC-01 : l activité terminée la plus récente est le build échoué, pas la sync",
            CanalTooling.BUILD,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `sync reussie puis build reussi - le dernier resultat est le BUILD`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000)),
        )
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 12_000))

        assertEquals(
            "INC-01 : le build conclu APRÈS la sync est le dernier résultat",
            CanalTooling.BUILD,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `sync echouee puis build reussi - le dernier resultat est le BUILD`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté")),
        )
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 8_000))

        assertEquals(
            "INC-01 : même un échec de sync ancien cède la place au build plus récent",
            CanalTooling.BUILD,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `build echoue puis sync reussie - le dernier resultat est la SYNC`() {
        service.suivreBuild("b-1")
        service.publierEtatBuild(
            EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 36_000, messageEchec = "compileKotlin a échoué"),
        )
        assertEquals(CanalTooling.BUILD, service.etat.value.canalDernierResultat)

        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 8_000)),
        )

        assertEquals(
            "INC-01 : la sync conclue APRÈS le build reprend le dernier résultat",
            CanalTooling.SYNC,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `build annule apres une sync reussie - le dernier resultat reste le BUILD`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000)),
        )
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.ANNULE, dureeMs = 4_000))

        assertEquals(
            "INC-01 : un build annulé conclusif détrône la sync plus ancienne",
            CanalTooling.BUILD,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `un listage en vol par-dessus un resultat ne derange pas le dernier resultat`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 42)),
        )
        service.marquerTachesEnCours()

        assertEquals(
            "l activité en vol est le listage (canal actif inchangé)",
            CanalTooling.TACHES,
            service.etat.value.canalActif,
        )
        assertEquals(
            "INC-01 : le listage ne produit PAS de résultat — le dernier reste la sync",
            CanalTooling.SYNC,
            service.etat.value.canalDernierResultat,
        )
    }

    @Test
    fun `le verdict croise alimente en tete et notification - scenario du proprietaire`() {
        // sync ✓ → build ✗ : l'en-tête au repos porte l'échec du build et
        // la notification finale dit le build échoué — plus de « sync
        // réussie » mensonger (INC-01 : les trois consommateurs dérivent
        // du même canalDernierResultat).
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000)),
        )
        service.suivreBuild("b-1")
        service.publierEtatBuild(
            EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 36_000, messageEchec = "compileKotlin a échoué"),
        )

        val etat = service.etat.value
        val entete = PresentationTooling.etatEntete(etat)
        assertEquals(StatutEntete.ECHOUE, entete.statut)
        assertEquals(jo.codeide.core.ui.R.color.codeide_canal_build, entete.couleur)
        assertEquals(TexteTooling.Brut("compileKotlin a échoué"), entete.titre)
        assertEquals(36_000L, entete.dureeFigeeMs)
        assertEquals(
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.BUILD,
                reussi = false,
                dureeMs = 36_000,
                messageEchec = "compileKotlin a échoué",
            ),
            decisionNotificationTooling(etat),
        )
    }

    // ------------------------------------------------------------------
    // Le même correctif, lu côté consommateurs sur des états marqués
    // construits directement (la chronologie vient des repères d'ordre).
    // ------------------------------------------------------------------

    @Test
    fun `l entete au repos suit le dernier resultat marque - build puis sync reussie (INC-01)`() {
        // Ordre inverse : la sync conclue APRÈS le build reprend l'en-tête.
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.ECHOUE,
                dureeBuildMs = 36_000,
                messageEchecBuild = "compileKotlin a échoué",
                sequenceResultatBuild = 1,
                synchronisationReussie =
                ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 8_000),
                sequenceResultatSync = 2,
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(StatutEntete.SUCCES, entete.statut)
        assertEquals(jo.codeide.core.ui.R.color.codeide_canal_sync, entete.couleur)
        assertEquals(8_000L, entete.dureeFigeeMs)
    }
}
