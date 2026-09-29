package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du présentateur pur du tooling (correctif n°11 du prompt « tooling
 * Gradle professionnel ») : UN seul code traduit [EtatGradle] pour l'en-tête
 * du panneau ([PresentationTooling.libelleActivite]) et le statut de
 * l'onglet Sortie ([PresentationTooling.libelleStatut]).
 *
 * Ces tests verrouillent la STRUCTURE (identifiant de ressource, arguments,
 * texte brut) — la localisation FR/EN est couverte par
 * [PresentationToolingLocalisationTest]. Cas exigés par le prompt : FR/EN,
 * échec, annulé, sync, build, listage.
 */
class PresentationToolingTest {
    // ---- En-tête : canal Sync ------------------------------------------

    @Test
    fun `l activite sync en vol affiche la synchronisation en cours`() {
        val etat = EtatGradle(synchronisationEnCours = true)

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.SYNC)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours), libelle)
    }

    @Test
    fun `l activite sync echouee affiche le message brut du serveur`() {
        val etat = EtatGradle(messageEchecSync = "connexion Gradle impossible")

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.SYNC)

        assertEquals(TexteTooling.Brut("connexion Gradle impossible"), libelle)
    }

    @Test
    fun `l activite sync reussie affiche la duree formatee`() {
        val etat =
            EtatGradle(
                synchronisationReussie =
                    ResultatSynchronisation(
                        projectDir = "/projets/demo",
                        reussie = true,
                        dureeMs = 2_100,
                    ),
            )

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.SYNC)

        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_reussie, listOf("2.1s")),
            libelle,
        )
    }

    @Test
    fun `l activite sync partielle montre l echec - l en tete ne mente jamais`() {
        // Sync partielle : réussie ET message d'échec — l'en-tête garde la
        // cascade historique (échec d'abord), le statut de console garde la
        // sienne (réussite d'abord) : les deux comportements VOLONTAIRES
        // sont verrouillés chacun par son test.
        val etat =
            EtatGradle(
                synchronisationReussie =
                    ResultatSynchronisation(
                        projectDir = "/projets/demo",
                        reussie = true,
                        partielle = true,
                        dureeMs = 3_000,
                        messageEchec = "modèle IdeaProject indisponible",
                    ),
                // Miroir tenu par GradleService.publierResultatSync : un
                // succès PARTIEL publie aussi le message d'échec des
                // modèles manquants dans messageEchecSync.
                messageEchecSync = "modèle IdeaProject indisponible",
            )

        assertEquals(
            TexteTooling.Brut("modèle IdeaProject indisponible"),
            PresentationTooling.libelleActivite(etat, CanalTooling.SYNC),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_reussie, listOf("3.0s")),
            PresentationTooling.libelleStatut(etat),
        )
    }

    @Test
    fun `l activite sync sans resultat affiche la synchronisation en cours`() {
        val libelle = PresentationTooling.libelleActivite(EtatGradle(), CanalTooling.SYNC)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours), libelle)
    }

    // ---- En-tête : canal Build -----------------------------------------

    @Test
    fun `l activite build en vol sans taches affiche le build en cours`() {
        val etat = EtatGradle(statutBuild = StatutBuild.EN_COURS)

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.BUILD)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_build_en_cours), libelle)
    }

    @Test
    fun `l activite build en vol avec taches affiche leurs noms`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.EN_COURS,
                taches = listOf(":app:assembleDebug", ":app:lintDebug"),
            )

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.BUILD)

        assertEquals(
            TexteTooling.Ressource(
                R.string.editor_tooling_build_taches,
                listOf(":app:assembleDebug, :app:lintDebug"),
            ),
            libelle,
        )
    }

    @Test
    fun `l activite build reussi affiche la duree`() {
        val etat = EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 12_400)

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.BUILD)

        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_reussi, listOf("12.4s")),
            libelle,
        )
    }

    @Test
    fun `l activite build echoue affiche le message sinon le libelle generique`() {
        val avecMessage =
            EtatGradle(statutBuild = StatutBuild.ECHOUE, messageEchecBuild = "tache compileKotlin echouee")
        val sansMessage = EtatGradle(statutBuild = StatutBuild.ECHOUE)

        assertEquals(
            TexteTooling.Brut("tache compileKotlin echouee"),
            PresentationTooling.libelleActivite(avecMessage, CanalTooling.BUILD),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue),
            PresentationTooling.libelleActivite(sansMessage, CanalTooling.BUILD),
        )
    }

    @Test
    fun `l activite build annule affiche l annulation`() {
        val etat = EtatGradle(statutBuild = StatutBuild.ANNULE)

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.BUILD)

        assertEquals(TexteTooling.Ressource(R.string.editor_sortie_build_annule), libelle)
    }

    @Test
    fun `l activite build sans statut affiche la sortie vide`() {
        val libelle = PresentationTooling.libelleActivite(EtatGradle(), CanalTooling.BUILD)

        assertEquals(TexteTooling.Ressource(R.string.editor_sortie_vide), libelle)
    }

    // ---- En-tête : canal Taches (listage) -------------------------------

    @Test
    fun `l activite de listage affiche le chargement des taches`() {
        val etat = EtatGradle(tachesEnCours = true)

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.TACHES)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_taches_en_cours), libelle)
    }

    // ---- Statut de l'onglet Sortie --------------------------------------

    @Test
    fun `le statut priorise la sync en vol puis son resultat puis l echec`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_en_cours),
            PresentationTooling.libelleStatut(EtatGradle(synchronisationEnCours = true)),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_reussie, listOf("450ms")),
            PresentationTooling.libelleStatut(
                EtatGradle(
                    synchronisationReussie =
                        ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 450),
                ),
            ),
        )
        assertEquals(
            TexteTooling.Brut("échec réseau"),
            PresentationTooling.libelleStatut(EtatGradle(messageEchecSync = "échec réseau")),
        )
    }

    @Test
    fun `le statut replie sur le build - en cours sans noms de taches`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.EN_COURS,
                taches = listOf(":app:build"),
            )

        val libelle = PresentationTooling.libelleStatut(etat)

        assertEquals(TexteTooling.Ressource(R.string.editor_sortie_build_en_cours), libelle)
    }

    @Test
    fun `le statut replie sur le build termine - reussi echoue annule`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_reussi, listOf("0ms")),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.REUSSI)),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ECHOUE)),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_annule),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ANNULE)),
        )
    }

    @Test
    fun `le statut signale l orchestrateur deconnecte en dernier recours`() {
        val etat = EtatGradle(connexion = EtatConnexion.ECHOUEE)

        val libelle = PresentationTooling.libelleStatut(etat)

        assertEquals(TexteTooling.Ressource(R.string.editor_outil_deconnecte), libelle)
    }

    @Test
    fun `le statut vierge affiche la sortie vide`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_vide),
            PresentationTooling.libelleStatut(EtatGradle()),
        )
    }

    // ---- Durées (formateur partagé) -------------------------------------

    @Test
    fun `les durees sous la seconde s affichent en millisecondes`() {
        val etat =
            EtatGradle(
                synchronisationReussie =
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 350),
            )

        val libelle = PresentationTooling.libelleActivite(etat, CanalTooling.SYNC)

        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_reussie, listOf("350ms")),
            libelle,
        )
    }

    // ---- État d'en-tête complet (v4, §3.2) -------------------------------

    @Test
    fun `l entete en vol porte son canal sa couleur et son chrono`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 1_000L,
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours), entete.titre)
        assertEquals(jo.codeide.core.ui.R.color.codeide_canal_sync, entete.couleur)
        assertEquals(1_000L, entete.chronoMs)
        assertEquals(null, entete.dureeFigeeMs)
        assertEquals(false, entete.arret)
    }

    @Test
    fun `l entete d un build porte le bouton arret et son chrono`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.EN_COURS,
                taches = listOf(":app:build"),
                debutBuildMs = 2_000L,
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertTrue(entete.arret)
        assertEquals(2_000L, entete.chronoMs)
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_build_taches, listOf(":app:build")),
            entete.titre,
        )
    }

    @Test
    fun `le sous-titre detaille l etape courante de sync - octets et compteur`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 0L,
                lignes =
                    listOf(
                        LigneConsole.Etape(
                            id = 1L,
                            canal = CanalTooling.SYNC,
                            etat =
                                EtapeSyncAffichee(
                                    etape = EtapeSync.DEPENDANCES,
                                    octetsRecus = 44_040_192L,
                                    compteur = 3,
                                    element = "kotlin-stdlib.jar",
                                ),
                        ),
                    ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals("42 Mo · 3 élément(s) · kotlin-stdlib.jar", entete.sousTitre)
    }

    @Test
    fun `la progression est determinee quand les octets totaux sont connus`() {
        fun etatAvec(
            recus: Long,
            total: Long?,
        ): EtatGradle =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        LigneConsole.Etape(
                            id = 1L,
                            canal = CanalTooling.SYNC,
                            etat =
                                EtapeSyncAffichee(
                                    etape = EtapeSync.DISTRIBUTION,
                                    octetsRecus = recus,
                                    octetsTotal = total,
                                ),
                        ),
                    ),
            )

        assertEquals(0.5f, PresentationTooling.etatEntete(etatAvec(50L, 100L)).progression)
        assertEquals(null, PresentationTooling.etatEntete(etatAvec(50L, null)).progression)
        assertEquals(null, PresentationTooling.etatEntete(etatAvec(0L, 100L)).progression)
    }

    @Test
    fun `le chrono se fige sur la duree du dernier resultat`() {
        val etat =
            EtatGradle(
                synchronisationReussie =
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 3_100),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(null, entete.chronoMs)
        assertEquals(3_100L, entete.dureeFigeeMs)
        assertEquals(null, entete.sousTitre)
    }

    @Test
    fun `l etat vierge reste neutre sans canal`() {
        val entete = PresentationTooling.etatEntete(EtatGradle())

        assertEquals(TexteTooling.Ressource(R.string.editor_sortie_vide), entete.titre)
        assertEquals(null, entete.sousTitre)
        assertEquals(null, entete.chronoMs)
        assertEquals(false, entete.arret)
    }
}
