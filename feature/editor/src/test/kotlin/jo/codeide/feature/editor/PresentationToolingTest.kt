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
 * INC-02/22 (volet C) : le statut prend le CANAL AFFICHÉ en paramètre
 * ([PresentationTooling.canalAffiche]) et suit la MÊME cascade que
 * l'en-tête — les tests de statut ci-dessous ont été RÉÉCRITS avec cette
 * signature (les anciens verrouillaient la priorité sync-puis-build aux
 * replis divergents, contredite par le prompt §6.3 INC-22).
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
    fun `l activite sync partielle montre l echec partout - un seul verdict`() {
        // Sync partielle : réussie ET message d'échec. INC-22 : le statut de
        // la console suit la MÊME cascade que l'en-tête (échec d'abord) —
        // l'ancien test verrouillait la divergence VOLONTAIRE
        // (« Synchronisé » contre un échec affiché), contredite par le
        // prompt §6.3 INC-22 : un seul verdict par canal.
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
            "INC-22 : le statut de console montre l'échec, comme l'en-tête",
            TexteTooling.Brut("modèle IdeaProject indisponible"),
            PresentationTooling.libelleStatut(etat, CanalTooling.SYNC),
        )
    }

    @Test
    fun `le canal sync jamais vecu affiche le libelle honnete - INC-04`() {
        // INC-04 : l'ancienne branche finale mentait (« Synchronisation du
        // projet… » alors que rien ne tourne) ; le canal Sync devient
        // consultable sans qu'aucune sync n'ait jamais tourné (INC-20) —
        // le libellé honnête remplace l'activité fantôme.
        val libelle = PresentationTooling.libelleActivite(EtatGradle(), CanalTooling.SYNC)

        assertEquals(TexteTooling.Ressource(R.string.editor_tooling_sync_jamais), libelle)
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

    // ---- Statut de l'onglet Sortie (INC-22 : cascade de l'en-tête, sur le
    // canal affiché par canalAffiche) -------------------------------

    @Test
    fun `le statut du canal sync suit la cascade de l en-tete - vol puis echec puis reussite`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours),
            PresentationTooling.libelleStatut(EtatGradle(synchronisationEnCours = true), CanalTooling.SYNC),
        )
        assertEquals(
            TexteTooling.Brut("échec réseau"),
            PresentationTooling.libelleStatut(EtatGradle(messageEchecSync = "échec réseau"), CanalTooling.SYNC),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_sync_reussie, listOf("450ms")),
            PresentationTooling.libelleStatut(
                EtatGradle(
                    synchronisationReussie =
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 450),
                ),
                CanalTooling.SYNC,
            ),
        )
    }

    @Test
    fun `le statut du canal build suit la cascade de l en-tete - taches en vol puis verdicts`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_build_taches, listOf(":app:build")),
            PresentationTooling.libelleStatut(
                EtatGradle(statutBuild = StatutBuild.EN_COURS, taches = listOf(":app:build")),
                CanalTooling.BUILD,
            ),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_reussi, listOf("0ms")),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.REUSSI), CanalTooling.BUILD),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ECHOUE), CanalTooling.BUILD),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_annule),
            PresentationTooling.libelleStatut(EtatGradle(statutBuild = StatutBuild.ANNULE), CanalTooling.BUILD),
        )
    }

    @Test
    fun `le statut sans canal replie sur deconnecte puis sortie vide`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_outil_deconnecte),
            PresentationTooling.libelleStatut(EtatGradle(connexion = EtatConnexion.ECHOUEE), null),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_vide),
            PresentationTooling.libelleStatut(EtatGradle(), null),
        )
    }

    // ---- Canal affiché (INC-02) ----------------------------------------

    @Test
    fun `le canal affiche suit le choix des que sync ou build a vecu - jamais TACHES`() {
        // État vierge : rien à sélectionner.
        assertNull(PresentationTooling.canalAffiche(EtatGradle(), FiltreCanalConsole.SYNC))
        assertNull(PresentationTooling.canalAffiche(EtatGradle(), FiltreCanalConsole.BUILD))

        // Sync en vol : le choix désigne la console.
        assertEquals(
            CanalTooling.SYNC,
            PresentationTooling.canalAffiche(EtatGradle(synchronisationEnCours = true), FiltreCanalConsole.SYNC),
        )

        // Dernier résultat build : le choix désigne la console.
        assertEquals(
            CanalTooling.BUILD,
            PresentationTooling.canalAffiche(EtatGradle(statutBuild = StatutBuild.ECHOUE), FiltreCanalConsole.BUILD),
        )

        // Listage SEUL (INC-03) : pas de console à sélectionner.
        assertNull(PresentationTooling.canalAffiche(EtatGradle(tachesEnCours = true), FiltreCanalConsole.SYNC))
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
        assertEquals(StatutEntete.EN_VOL, entete.statut)
        assertEquals(1_000L, entete.chronoMs)
        assertEquals(null, entete.dureeFigeeMs)
        assertEquals(false, entete.arret)
        assertEquals(null, entete.libelleEtape)
        assertEquals(null, entete.sousTitre)
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
        assertEquals(StatutEntete.EN_VOL, entete.statut)
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
                etapesSync =
                listOf(
                    EtapeSyncAffichee(
                        etape = EtapeSync.DEPENDANCES,
                        octetsRecus = 44_040_192L,
                        compteur = 3,
                        element = "kotlin-stdlib.jar",
                    ),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(
            TexteTooling.Brut("42 Mo · 3 élément(s) · kotlin-stdlib.jar"),
            entete.sousTitre,
        )
        assertEquals(R.string.editor_console_etape_dependances_modele, entete.libelleEtape)
    }

    @Test
    fun `la phase modele IDE se projette sur l etape fusionnee du plan d affichage`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 0L,
                etapesSync =
                listOf(
                    EtapeSyncAffichee(etape = EtapeSync.MODELE_IDE),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(R.string.editor_console_etape_dependances_modele, entete.libelleEtape)
        assertEquals(6, etat.numeroEtape)
    }

    @Test
    fun `le titre est numerote par etape pendant la sync`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 0L,
                etapesSync =
                listOf(
                    EtapeSyncAffichee(etape = EtapeSync.DAEMON),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(
            TexteTooling.Ressource(
                R.string.editor_tooling_entete_sync_etape,
                listOf("${etat.numeroEtape}", "${etat.totalEtapes}"),
            ),
            entete.titre,
        )
        assertEquals(3, etat.numeroEtape)
        assertEquals(7, etat.totalEtapes)
        assertEquals(R.string.editor_console_etape_daemon, entete.libelleEtape)
    }

    @Test
    fun `le statut visuel suit le verdict du dernier resultat du canal`() {
        val reussie =
            EtatGradle(
                synchronisationReussie =
                ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 900),
            )
        val echoueeSync = EtatGradle(messageEchecSync = "réseau coupé")
        val buildReussi = EtatGradle(statutBuild = StatutBuild.REUSSI)
        val buildEchoue = EtatGradle(statutBuild = StatutBuild.ECHOUE)

        assertEquals(StatutEntete.SUCCES, PresentationTooling.etatEntete(reussie).statut)
        assertEquals(StatutEntete.ECHOUE, PresentationTooling.etatEntete(echoueeSync).statut)
        assertEquals(StatutEntete.SUCCES, PresentationTooling.etatEntete(buildReussi).statut)
        assertEquals(StatutEntete.ECHOUE, PresentationTooling.etatEntete(buildEchoue).statut)
    }

    @Test
    fun `l entete au repos suit le dernier resultat marque - sync puis build echoue (INC-01)`() {
        // Le scénario du propriétaire : sync réussie (194 s) puis build
        // échoué — l'en-tête au repos porte l'échec du BUILD, plus la
        // coche verte de la sync. Les repères d'ordre sont ceux que le
        // service pose à chaque conclusion.
        val etat =
            EtatGradle(
                synchronisationReussie =
                ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000),
                sequenceResultatSync = 1,
                statutBuild = StatutBuild.ECHOUE,
                dureeBuildMs = 36_000,
                messageEchecBuild = "compileKotlin a échoué",
                sequenceResultatBuild = 2,
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(StatutEntete.ECHOUE, entete.statut)
        assertEquals(jo.codeide.core.ui.R.color.codeide_canal_build, entete.couleur)
        assertEquals(TexteTooling.Brut("compileKotlin a échoué"), entete.titre)
        assertEquals(36_000L, entete.dureeFigeeMs)
    }

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

    @Test
    fun `la sync reussie avec taches montre le compte et une progression pleine`() {
        val etat =
            EtatGradle(
                synchronisationReussie =
                ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 2_500),
                tachesDisponibles =
                listOf(
                    jo.codeide.core.domain
                        .InfoTache(chemin = ":app:build", nomAffiche = "build"),
                    jo.codeide.core.domain
                        .InfoTache(chemin = ":app:test", nomAffiche = "test"),
                ),
            )

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_entete_succes_taches, listOf("2")),
            entete.sousTitre,
        )
        assertEquals(1f, entete.progression)
        assertEquals(null, entete.libelleEtape)
    }

    @Test
    fun `le sous-titre de succes detaille modules et taches - v5, aperçu`() {
        fun etatSucces(vararg etapes: EtapeSyncAffichee): EtatGradle = EtatGradle(
            synchronisationReussie =
            ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 2_500),
            tachesDisponibles =
            listOf(
                jo.codeide.core.domain
                    .InfoTache(chemin = ":app:build", nomAffiche = "build"),
            ),
            etapesSync = etapes.toList(),
        )

        // v6 : la distribution en cache n'est pas émise — aucune étape
        // DISTRIBUTION dans les lignes, AUCUN octet reçu — le détail
        // honnête dit « aucun téléchargement ».
        val aJour =
            etatSucces(
                EtapeSyncAffichee(
                    etape = EtapeSync.CLASSPATHS,
                    terminee = true,
                    compteur = 3,
                ),
            )
        assertEquals(
            TexteTooling.Ressource(
                R.string.editor_tooling_entete_succes_detail_a_jour,
                listOf("3", "1"),
            ),
            PresentationTooling.etatEntete(aJour).sousTitre,
        )

        // Sync froide : des octets ont été reçus — « classpaths prêts ».
        val froide =
            etatSucces(
                EtapeSyncAffichee(
                    etape = EtapeSync.DEPENDANCES,
                    terminee = true,
                    octetsRecus = 44_040_192L,
                ),
                EtapeSyncAffichee(
                    etape = EtapeSync.CLASSPATHS,
                    terminee = true,
                    compteur = 3,
                ),
            )
        assertEquals(
            TexteTooling.Ressource(
                R.string.editor_tooling_entete_succes_detail_classpaths,
                listOf("3", "1"),
            ),
            PresentationTooling.etatEntete(froide).sousTitre,
        )
    }

    @Test
    fun `la progression est determinee quand les octets totaux sont connus`() {
        fun etatAvec(recus: Long, total: Long?): EtatGradle = EtatGradle(
            synchronisationEnCours = true,
            etapesSync =
            listOf(
                EtapeSyncAffichee(
                    etape = EtapeSync.DISTRIBUTION,
                    octetsRecus = recus,
                    octetsTotal = total,
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
        assertEquals(StatutEntete.NEUTRE, entete.statut)
        assertEquals(null, entete.libelleEtape)
        assertEquals(null, entete.sousTitre)
        assertEquals(null, entete.chronoMs)
        assertEquals(false, entete.arret)
    }
}
