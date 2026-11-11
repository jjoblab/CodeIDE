package jo.codeide.feature.ai

import jo.codeide.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du présentateur d'erreurs (mission IA, lot IA-2 + rapport IA,
 * Lot 5, point 5) : la table `action` est EXHAUSTIVE — chaque raison
 * fermée d'[AppError.AiReason] a SON action de remédiation. Une
 * nouvelle raison du modèle fait échouer la compilation du `when` :
 * ce test garantit que le miroir est complet côté présentateur.
 */
class PresentationErreursIaTest {
    @Test
    fun `chaque raison Ai propose son action de remédiation`() {
        val attendus =
            mapOf(
                AppError.AiReason.Authentication to ActionErreurIa.OUVRIR_REGLAGES,
                AppError.AiReason.QuotaExceeded to ActionErreurIa.REESSAYER,
                AppError.AiReason.ServiceUnavailable to ActionErreurIa.REESSAYER,
                AppError.AiReason.Offline to ActionErreurIa.REESSAYER,
                AppError.AiReason.Timeout to ActionErreurIa.REESSAYER,
                AppError.AiReason.MalformedResponse to ActionErreurIa.CHANGER_MODELE,
                AppError.AiReason.ConsentRequired to ActionErreurIa.OUVRIR_REGLAGES,
                AppError.AiReason.Configuration to ActionErreurIa.OUVRIR_REGLAGES,
            )
        attendus.forEach { (raison, action) ->
            assertEquals("raison $raison", action, PresentationErreursIa.action(AppError.Ai(raison)))
        }
    }

    @Test
    fun `les erreurs de stockage et inconnues retombent honnêtement`() {
        // Clé illisible (Keystore réinitialisé) : réparer la configuration.
        assertEquals(
            ActionErreurIa.OUVRIR_REGLAGES,
            PresentationErreursIa.action(AppError.Storage(AppError.StorageReason.KeyUnavailable)),
        )
        // Stockage générique : retenter peut suffire.
        assertEquals(
            ActionErreurIa.REESSAYER,
            PresentationErreursIa.action(AppError.Storage(AppError.StorageReason.NotFound)),
        )
        // Inconnue : le réessai reste le geste par défaut.
        assertEquals(
            ActionErreurIa.REESSAYER,
            PresentationErreursIa.action(AppError.Unknown("hors périmètre")),
        )
    }

    @Test
    fun `chaque action porte son libellé localisé distinct`() {
        assertEquals(
            "trois actions, trois libellés — jamais deux boutons jumeaux",
            listOf(
                R.string.ia_erreur_action_reessayer,
                R.string.ia_erreur_action_reglages,
                R.string.ia_erreur_action_modele,
            ),
            ActionErreurIa.entries.map { it.libelleRes }.distinct(),
        )
    }
}
