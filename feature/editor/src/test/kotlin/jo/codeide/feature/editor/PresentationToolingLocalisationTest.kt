package jo.codeide.feature.editor

import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Localisation du présentateur (correctif n°11) : les libellés produits par
 * [PresentationTooling] se résolvent en FRANÇAIS et en ANGLAIS — le dépôt
 * exige les deux langues pour toute chaîne visible (AGENTS.md, ADR 0013).
 * Robolectric fournit le `Context` ; le qualificatif de locale choisit le
 * dossier de ressources (`values/` par défaut FR, `values-en/`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class PresentationToolingLocalisationFrTest {
    private val contexte = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `les libelles de sync se resolvent en francais`() {
        assertEquals(
            "Synchronisation du projet…",
            PresentationTooling
                .libelleActivite(EtatGradle(synchronisationEnCours = true), CanalTooling.SYNC)
                .resoudre(contexte),
        )
        assertEquals(
            "Synchronisé en 2.1s",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(
                        synchronisationReussie =
                            ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 2_100),
                    ),
                    CanalTooling.SYNC,
                ).resoudre(contexte),
        )
        assertEquals(
            "Synchronisation en cours…",
            PresentationTooling.libelleStatut(EtatGradle(synchronisationEnCours = true)).resoudre(contexte),
        )
    }

    @Test
    fun `les libelles de build se resolvent en francais`() {
        assertEquals(
            "Build — :app:assembleDebug",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(statutBuild = StatutBuild.EN_COURS, taches = listOf(":app:assembleDebug")),
                    CanalTooling.BUILD,
                ).resoudre(contexte),
        )
        assertEquals(
            "Build réussi en 4.5s",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 4_500),
                    CanalTooling.BUILD,
                ).resoudre(contexte),
        )
        assertEquals(
            "Échec du build",
            PresentationTooling
                .libelleActivite(EtatGradle(statutBuild = StatutBuild.ECHOUE), CanalTooling.BUILD)
                .resoudre(contexte),
        )
        assertEquals(
            "Build annulé",
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ANNULE)).resoudre(contexte),
        )
    }

    @Test
    fun `les libelles de listage et de repli se resolvent en francais`() {
        assertEquals(
            "Chargement des tâches…",
            PresentationTooling
                .libelleActivite(EtatGradle(tachesEnCours = true), CanalTooling.TACHES)
                .resoudre(contexte),
        )
        assertEquals(
            "Outils Gradle non connectés",
            PresentationTooling.libelleStatut(EtatGradle(connexion = EtatConnexion.ECHOUEE)).resoudre(contexte),
        )
        assertEquals(
            "La sortie du build apparaîtra ici.",
            PresentationTooling.libelleStatut(EtatGradle()).resoudre(contexte),
        )
    }
}

/** Miroir anglais de [PresentationToolingLocalisationFrTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class PresentationToolingLocalisationEnTest {
    private val contexte = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `les libelles de sync se resolvent en anglais`() {
        assertEquals(
            "Synchronizing project…",
            PresentationTooling
                .libelleActivite(EtatGradle(synchronisationEnCours = true), CanalTooling.SYNC)
                .resoudre(contexte),
        )
        assertEquals(
            "Synced in 2.1s",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(
                        synchronisationReussie =
                            ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 2_100),
                    ),
                    CanalTooling.SYNC,
                ).resoudre(contexte),
        )
        assertEquals(
            "Syncing…",
            PresentationTooling.libelleStatut(EtatGradle(synchronisationEnCours = true)).resoudre(contexte),
        )
    }

    @Test
    fun `les libelles de build se resolvent en anglais`() {
        assertEquals(
            "Build — :app:assembleDebug",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(statutBuild = StatutBuild.EN_COURS, taches = listOf(":app:assembleDebug")),
                    CanalTooling.BUILD,
                ).resoudre(contexte),
        )
        assertEquals(
            "Build succeeded in 4.5s",
            PresentationTooling
                .libelleActivite(
                    EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 4_500),
                    CanalTooling.BUILD,
                ).resoudre(contexte),
        )
        assertEquals(
            "Build failed",
            PresentationTooling
                .libelleActivite(EtatGradle(statutBuild = StatutBuild.ECHOUE), CanalTooling.BUILD)
                .resoudre(contexte),
        )
        assertEquals(
            "Build cancelled",
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ANNULE)).resoudre(contexte),
        )
    }

    @Test
    fun `les libelles de listage et de repli se resolvent en anglais`() {
        assertEquals(
            "Loading tasks…",
            PresentationTooling
                .libelleActivite(EtatGradle(tachesEnCours = true), CanalTooling.TACHES)
                .resoudre(contexte),
        )
        assertEquals(
            "Gradle tools not connected",
            PresentationTooling.libelleStatut(EtatGradle(connexion = EtatConnexion.ECHOUEE)).resoudre(contexte),
        )
        assertEquals(
            "Build output will appear here.",
            PresentationTooling.libelleStatut(EtatGradle()).resoudre(contexte),
        )
    }
}
