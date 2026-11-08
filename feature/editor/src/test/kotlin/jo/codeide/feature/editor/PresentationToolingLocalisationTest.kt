package jo.codeide.feature.editor

import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.EtapeSync
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
            "Synchronisation du projet…",
            PresentationTooling.libelleActivite(
                EtatGradle(synchronisationEnCours = true),
                CanalTooling.SYNC,
            ).resoudre(contexte),
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
            PresentationTooling.libelleActivite(
                EtatGradle(statutBuild = StatutBuild.ANNULE),
                CanalTooling.BUILD,
            ).resoudre(contexte),
        )
    }

    @Test
    fun `le verdict court d echec de sync se resout en francais - INC-15`() {
        assertEquals(
            "Synchronisation échouée",
            PresentationTooling.libelleActivite(
                EtatGradle(messageEchecSync = "connexion perdue"),
                CanalTooling.SYNC,
            ).resoudre(contexte),
        )
    }

    @Test
    fun `le libelle honnete du canal sync jamais vecu se resout en francais`() {
        assertEquals(
            "Aucune synchronisation lancée",
            PresentationTooling.libelleActivite(EtatGradle(), CanalTooling.SYNC).resoudre(contexte),
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
            PresentationTooling.libelleStatut(
                EtatGradle(connexion = EtatConnexion.ECHOUEE),
            ).resoudre(contexte),
        )
        assertEquals(
            "La sortie du build apparaîtra ici.",
            PresentationTooling.libelleStatut(EtatGradle()).resoudre(contexte),
        )
    }

    @Test
    fun `l en tete enrichi se resout en francais - titre numerote et sous-titre d etape`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                etapesSync =
                listOf(
                    EtapeSyncAffichee(
                        etape = EtapeSync.DEPENDANCES,
                        octetsRecus = 44_040_192L,
                        compteur = 3,
                    ),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(
            "Synchronisation Gradle · étape 6/7",
            entete.titre.resoudre(contexte),
        )
        val libelleEtape = entete.libelleEtape?.let { contexte.getString(it) }
        assertEquals("Dépendances et modèle IDE", libelleEtape)
        // INC-16 : unités SI décimales (44 Mo), comme le reste d'Android
        // (l'ancien calcul binaire codé en dur affichait « 42 Mo »).
        assertEquals("44\u202fMo · 3 éléments", entete.sousTitre?.resoudre(contexte))
        assertEquals(
            "Dépendances et modèle IDE — 44\u202fMo · 3 éléments",
            libelleEtape?.let { libelle ->
                entete.sousTitre?.let { detail ->
                    contexte.getString(
                        R.string.editor_tooling_entete_sous_titre,
                        libelle,
                        detail.resoudre(contexte),
                    )
                }
            },
        )
    }

    @Test
    fun `le sous-titre de succes se resout en francais`() {
        val entete =
            PresentationTooling.etatEntete(
                EtatGradle(
                    synchronisationReussie =
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000),
                    tachesDisponibles =
                    listOf(
                        jo.codeide.core.domain
                            .InfoTache(chemin = ":app:build", nomAffiche = "build"),
                    ),
                ),
            )

        assertEquals("Tâche disponible : 1", entete.sousTitre?.resoudre(contexte))
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
            "Synchronizing project…",
            PresentationTooling.libelleActivite(
                EtatGradle(synchronisationEnCours = true),
                CanalTooling.SYNC,
            ).resoudre(contexte),
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
            PresentationTooling.libelleActivite(
                EtatGradle(statutBuild = StatutBuild.ANNULE),
                CanalTooling.BUILD,
            ).resoudre(contexte),
        )
    }

    @Test
    fun `le verdict court d echec de sync se resout en anglais - INC-15`() {
        assertEquals(
            "Synchronization failed",
            PresentationTooling.libelleActivite(
                EtatGradle(messageEchecSync = "connection lost"),
                CanalTooling.SYNC,
            ).resoudre(contexte),
        )
    }

    @Test
    fun `le libelle honnete du canal sync jamais vecu se resout en anglais`() {
        assertEquals(
            "No synchronization run yet",
            PresentationTooling.libelleActivite(EtatGradle(), CanalTooling.SYNC).resoudre(contexte),
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
            PresentationTooling.libelleStatut(
                EtatGradle(connexion = EtatConnexion.ECHOUEE),
            ).resoudre(contexte),
        )
        assertEquals(
            "Build output will appear here.",
            PresentationTooling.libelleStatut(EtatGradle()).resoudre(contexte),
        )
    }

    @Test
    fun `l en tete enrichi se resout en anglais - titre numerote et sous-titre d etape`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                etapesSync =
                listOf(
                    EtapeSyncAffichee(
                        etape = EtapeSync.DEPENDANCES,
                        octetsRecus = 44_040_192L,
                        compteur = 3,
                    ),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(
            "Gradle synchronization · step 6/7",
            entete.titre.resoudre(contexte),
        )
        val libelleEtape = entete.libelleEtape?.let { contexte.getString(it) }
        assertEquals("Dependencies and IDE model", libelleEtape)
        assertEquals("44 MB · 3 items", entete.sousTitre?.resoudre(contexte))
        assertEquals(
            "Dependencies and IDE model — 44 MB · 3 items",
            libelleEtape?.let { libelle ->
                entete.sousTitre?.let { detail ->
                    contexte.getString(
                        R.string.editor_tooling_entete_sous_titre,
                        libelle,
                        detail.resoudre(contexte),
                    )
                }
            },
        )
    }

    @Test
    fun `le sous-titre de succes se resout en anglais`() {
        val entete =
            PresentationTooling.etatEntete(
                EtatGradle(
                    synchronisationReussie =
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000),
                    tachesDisponibles =
                    listOf(
                        jo.codeide.core.domain
                            .InfoTache(chemin = ":app:build", nomAffiche = "build"),
                    ),
                ),
            )

        assertEquals("Task available: 1", entete.sousTitre?.resoudre(contexte))
    }
}
