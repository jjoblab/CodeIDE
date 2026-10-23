package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.model.ProjectId
import jo.codeide.core.session.GestionnaireSessionProjet
import jo.codeide.core.session.RaisonFermeture
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de l'adaptateur du port d'observation de session (mission «
 * Cycle de vie » étape E) : la projection LECTURE SEULE que l'accueil
 * consomme — chaque état du contrat se reflète honnêtement, y compris
 * la transition de fermeture (l'état RÉEL, pas une liste).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservateurSessionProjetEspaceTest {
    @Test
    fun `la projection suit le cycle complet de la session`() = runTest {
        val gestionnaire = GestionnaireSessionProjet()
        val observateur =
            ObservateurSessionProjetEspace(
                gestionnaireSession = gestionnaire,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            )
        val alpha = ProjectId("alpha")

        assertEquals(EtatSessionProjetVue.Aucune, observateur.etatSession.value)

        gestionnaire.ouvrir(alpha) {
            jo.codeide.core.model.AppResult
                .Success(Unit)
        }
        testScheduler.advanceUntilIdle()
        assertEquals(EtatSessionProjetVue.Ouverte(alpha), observateur.etatSession.value)

        gestionnaire.fermer(RaisonFermeture.Utilisateur)
        testScheduler.advanceUntilIdle()
        assertEquals(EtatSessionProjetVue.Aucune, observateur.etatSession.value)
    }
}
