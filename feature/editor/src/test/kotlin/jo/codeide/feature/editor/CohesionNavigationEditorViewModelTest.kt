package jo.codeide.feature.editor

import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.session.EtatSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la COHÉRENCE DE NAVIGATION dans l'espace (mission « Cycle de
 * vie » étape E — docs/CYCLE_DE_VIE.md §8) : le miroir de projet des
 * canaux tooling (routage de notification, E10), l'espace qui se
 * referme quand la session bascule vers un autre projet (I1 visible à
 * l'écran), la sortie qui ne referme JAMAIS la session d'un autre, la
 * permission SAF révoquée détectée AU RETOUR, et le refus tooling
 * honnête pendant une installation.
 *
 * Le routage proprement dit (extras d'intention, PendingIntent) vit
 * dans les services et l'hôte du graphe — Android ; ces tests éprouvent
 * la part OBSERVABLE : ce que l'espace publie, ce qu'il ressent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CohesionNavigationEditorViewModelTest : BaseEditorViewModelTest() {
    /** Avance le temps virtuel et laisse tourner toutes les portées test. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    /** Détruit le ViewModel comme la sortie réelle de l'écran (rotation exclue). */
    private fun EditorViewModel.detruire() {
        val classe = androidx.lifecycle.ViewModel::class.java
        val methode =
            classe.methods.firstOrNull { it.name == "clear\$lifecycle_viewmodel" }
                ?: classe.methods.firstOrNull { it.name == "clear" }
                ?: error("méthode clear introuvable sur ViewModel (lifecycle inconnu)")
        methode.invoke(this)
    }

    @Test
    fun `la sync d ouverture publie le projet du canal SYNC`() =
        runTest {
            // E10 : la notification tooling route vers le projet DONT
            // l'activité part — le miroir process-wide est alimenté au
            // lancement, AVANT toute garde (même refusé, l'origine est
            // connue — la notification d'échec route aussi vers le projet).
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            avancer()

            assertEquals(
                "le miroir du canal SYNC porte le projet de l'espace",
                alpha,
                serviceGradleTest.etat.value.projetDuCanal(CanalTooling.SYNC),
            )
        }

    @Test
    fun `l espace se referme quand un autre projet ouvre la session`() =
        runTest {
            // Matrice §6.3 « notification (X) pendant que Y est ouvert » :
            // ouvrir X ferme la session de Y par le gestionnaire (I1) —
            // l'espace de Y n'a plus rien à montrer, il se referme LUI-MÊME.
            val effets = mutableListOf<EffetEditor>()
            val alpha = ajouterProjet("Alpha")
            val beta = ajouterProjet("Beta")
            val vmAlpha = viewModel(alpha)
            collecterEffets(vmAlpha, effets)
            avancer()
            assertEquals(EtatSession.Ouverte(alpha), gestionnaireSessionTest.etat.value)

            // Le gestionnaire bascule vers B (comme l'ouverture de B par un
            // retour de notification) : l'espace de A le RESSENT.
            gestionnaireSessionTest.ouvrir(beta) {
                jo.codeide.core.model.AppResult
                    .Success(Unit)
            }
            avancer()

            assertEquals(EtatSession.Ouverte(beta), gestionnaireSessionTest.etat.value)
            assertTrue(
                "l'espace de A a reçu l'ordre de se refermer (une seule fois)",
                effets.count { it == EffetEditor.FermerEspace } == 1,
            )
        }

    @Test
    fun `un espace ne sur une session deja tenue se referme aussitot`() =
        runTest {
            // Double ouverture du MÊME projet (routage de notification alors
            // qu'un espace vit dessous) : le PREMIER reste propriétaire, le
            // nouveau se referme — jamais deux espaces sur une session.
            val effets = mutableListOf<EffetEditor>()
            val alpha = ajouterProjet("Alpha")
            gestionnaireSessionTest.ouvrir(alpha) {
                jo.codeide.core.model.AppResult
                    .Success(Unit)
            }
            avancer()

            val vmNouveau = viewModel(alpha)
            collecterEffets(vmNouveau, effets)
            avancer()

            assertEquals(
                "la session reste celle du premier propriétaire",
                EtatSession.Ouverte(alpha),
                gestionnaireSessionTest.etat.value,
            )
            assertTrue(
                "l'espace nouveau reçoit l'ordre de se refermer",
                effets.contains(EffetEditor.FermerEspace),
            )
        }

    @Test
    fun `la sortie d un espace ne referme pas la session d un autre`() =
        runTest {
            // Cas dégénéré P4 enfin clos : l'espace de A survit DESSOUS
            // pendant que B vit ; sa sortie (onCleared) ne doit PAS tuer
            // la session de B — sinon l'écran de B perdrait son travail.
            val alpha = ajouterProjet("Alpha")
            val beta = ajouterProjet("Beta")
            val vmAlpha = viewModel(alpha)
            avancer()

            gestionnaireSessionTest.ouvrir(beta) {
                jo.codeide.core.model.AppResult
                    .Success(Unit)
            }
            avancer()
            assertEquals(EtatSession.Ouverte(beta), gestionnaireSessionTest.etat.value)

            vmAlpha.detruire()
            avancer()

            assertEquals(
                "la sortie de A n'a pas refermé la session de B",
                EtatSession.Ouverte(beta),
                gestionnaireSessionTest.etat.value,
            )
        }

    @Test
    fun `une permission revoquee pendant l absence remonte au retour`() =
        runTest {
            // Prompt §6 E : « détection d'une permission SAF révoquée ou
            // d'un stockage disparu au retour » — la surveillance repart à
            // onStart ; le contrôle AU RETOUR alimente le bandeau existant
            // (jamais un balayage muet).
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            avancer()
            assertTrue(
                "pré-condition : l'arborescence est affichée",
                viewModel.etat.value.noeuds
                    .isNotEmpty(),
            )
            assertEquals(
                "pré-condition : accès vérifié disponible au chargement",
                ProjectAccessState.Available,
                viewModel.etat.value.acces,
            )

            // Le stockage est révoqué pendant que l'écran est absent.
            fichiers.revokePermission(URI_ARBRE_PROJET)

            // Retour : onStart → DemarrerSurveillanceArbre → contrôle.
            viewModel.onAction(ActionEditor.DemarrerSurveillanceArbre)
            avancer()

            assertEquals(
                "le bandeau d'accès est posé au retour",
                ProjectAccessState.PermissionLost,
                viewModel.etat.value.acces,
            )
            assertTrue(
                "l'arborescence laisse place au bandeau",
                viewModel.etat.value.noeuds
                    .isEmpty(),
            )
        }

    @Test
    fun `le refus tooling pendant une installation dit l installation en cours`() =
        runTest {
            // Prompt §6 E : l'état de l'installation (en cours/terminée)
            // répercuté sur la disponibilité du tooling — le refus dit
            // l'état RÉEL du parcours, pas une absence d'outils mensongère.
            parcoursInstallationTest.semerEtat(
                jo.codeide.core.domain.EnvironmentSetupState
                    .initial()
                    .copy(running = InstallPhase.JAVA),
            )
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            avancer()

            assertEquals(
                "le refus porte le message du parcours en cours",
                EditorViewModel.MESSAGE_INSTALLATION_EN_COURS,
                serviceGradleTest.etat.value.messageEchecSync,
            )
        }
}
