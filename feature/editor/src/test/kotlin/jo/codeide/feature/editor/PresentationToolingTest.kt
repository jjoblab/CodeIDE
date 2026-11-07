package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `l activite sync echouee affiche le verdict court - le message va en sous-titre`() {
        val etat = EtatGradle(messageEchecSync = "connexion Gradle impossible")

        // INC-15 : verdict LOCALISÉ ET COURT en titre — le message brut
        // du serveur (multi-lignes, technique) vit dans le SOUS-TITRE, le
        // bandeau et la console, pas dans un titre singleLine tronqué.
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_sync_echouee),
            PresentationTooling.libelleActivite(etat, CanalTooling.SYNC),
        )
        assertEquals(
            TexteTooling.Brut("connexion Gradle impossible"),
            PresentationTooling.etatEntete(etat).sousTitre,
        )
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
            PresentationTooling.etatEntete(etat).sousTitre,
        )
        assertEquals(
            "INC-15/22 : l'en-tête (ligne enrichie et indicateurs compacts) porte le verdict court, pas le message brut",
            TexteTooling.Ressource(R.string.editor_tooling_sync_echouee),
            PresentationTooling.libelleActivite(etat, CanalTooling.SYNC),
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
    fun `l activite build echoue affiche le verdict court - le message va en sous-titre`() {
        val avecMessage =
            EtatGradle(statutBuild = StatutBuild.ECHOUE, messageEchecBuild = "tache compileKotlin echouee")
        val sansMessage = EtatGradle(statutBuild = StatutBuild.ECHOUE)

        // INC-15 : verdict court localisé dans TOUTES les cascades — le
        // message du serveur vit dans le sous-titre (et le bandeau).
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue),
            PresentationTooling.libelleActivite(avecMessage, CanalTooling.BUILD),
        )
        assertEquals(
            TexteTooling.Brut("tache compileKotlin echouee"),
            PresentationTooling.etatEntete(avecMessage).sousTitre,
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue),
            PresentationTooling.libelleActivite(sansMessage, CanalTooling.BUILD),
        )
        assertEquals(
            "sans message serveur, pas de sous-titre",
            null,
            PresentationTooling.etatEntete(sansMessage).sousTitre,
        )
    }

    @Test
    fun `un echec de sync porte aussi sa duree figee - INC-15`() {
        val etat = EtatGradle(messageEchecSync = "non connecté", dureeSyncMs = 36_000)

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(36_000L, entete.dureeFigeeMs)
    }

    @Test
    fun `un build annule a son propre statut visuel - INC-15`() {
        val etat = EtatGradle(statutBuild = StatutBuild.ANNULE, dureeBuildMs = 4_000)

        val entete = PresentationTooling.etatEntete(etat)

        assertEquals(StatutEntete.ANNULE, entete.statut)
        assertEquals(4_000L, entete.dureeFigeeMs)
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

    // ---- Statut de la console (INC-22 finale : état vierge uniquement) --

    @Test
    fun `le statut de la console ne porte plus que l etat vierge - INC-22 finale`() {
        // INC-22 (avec l'INC-10) : l'activité et le verdict vivent dans
        // l'EN-TÊTE du sheet (ligne enrichie sur Console replié,
        // indicateurs compacts ailleurs) — la ligne de statut ne montre
        // plus que ce que l'en-tête ne porte pas : l'orchestrateur
        // déconnecté avant toute activité (l'état vide a sa vue dédiée).
        assertEquals(
            TexteTooling.Ressource(R.string.editor_outil_deconnecte),
            PresentationTooling.libelleStatut(EtatGradle(connexion = EtatConnexion.ECHOUEE)),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_vide),
            PresentationTooling.libelleStatut(EtatGradle()),
        )
    }

    // ---- Bandeau d'échec par canal (INC-21) ----------------------------

    @Test
    fun `le bandeau d echec porte le verdict du canal affiche - sync ou build`() {
        val syncEchouee = EtatGradle(messageEchecSync = "non connecté")
        val buildEchoue = EtatGradle(statutBuild = StatutBuild.ECHOUE, messageEchecBuild = "compileKotlin a échoué")
        val buildReussi = EtatGradle(statutBuild = StatutBuild.REUSSI)

        // Canal Sync affiché : l'échec de SYNC porte le bandeau (INC-21 :
        // avant, il n'avait qu'une ligne de statut — sans « Réessayer »).
        assertEquals(
            TexteTooling.Brut("non connecté"),
            PresentationTooling.messageEchecCanal(syncEchouee, CanalTooling.SYNC),
        )
        assertTrue(PresentationTooling.reessaiPossible(syncEchouee, CanalTooling.SYNC))

        // Canal Build affiché : le verdict du build — le message du serveur.
        assertEquals(
            TexteTooling.Brut("compileKotlin a échoué"),
            PresentationTooling.messageEchecCanal(buildEchoue, CanalTooling.BUILD),
        )

        // Un canal qui n'a PAS échoué ne montre rien (même si l'autre a
        // échoué) — le point d'actualité du sélecteur signale l'autre.
        assertEquals(null, PresentationTooling.messageEchecCanal(buildReussi, CanalTooling.BUILD))
        assertEquals(
            "l'échec du build ne déclenche pas le bandeau de la console Sync",
            null,
            PresentationTooling.messageEchecCanal(buildEchoue, CanalTooling.SYNC),
        )
        assertEquals(
            "l'échec de sync ne déclenche pas le bandeau de la console Build",
            null,
            PresentationTooling.messageEchecCanal(syncEchouee, CanalTooling.BUILD),
        )
    }

    @Test
    fun `le repli du bandeau est localise par canal - message vide ou absent`() {
        assertEquals(
            TexteTooling.Ressource(R.string.editor_console_echec_sync_defaut),
            PresentationTooling.messageEchecCanal(EtatGradle(messageEchecSync = "  "), CanalTooling.SYNC),
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_console_echec_defaut),
            PresentationTooling.messageEchecCanal(EtatGradle(statutBuild = StatutBuild.ECHOUE), CanalTooling.BUILD),
        )
    }

    @Test
    fun `le reessai du build exige des taches - la sync se relance toujours`() {
        assertFalse(PresentationTooling.reessaiPossible(EtatGradle(taches = emptyList()), CanalTooling.BUILD))
        assertTrue(
            PresentationTooling.reessaiPossible(
                EtatGradle(taches = listOf(":app:assembleDebug")),
                CanalTooling.BUILD,
            ),
        )
        assertTrue(PresentationTooling.reessaiPossible(EtatGradle(), CanalTooling.SYNC))
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
        // INC-15 : verdict COURT en titre, message du serveur en sous-titre.
        assertEquals(TexteTooling.Ressource(R.string.editor_sortie_build_echoue), entete.titre)
        assertEquals(TexteTooling.Brut("compileKotlin a échoué"), entete.sousTitre)
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
