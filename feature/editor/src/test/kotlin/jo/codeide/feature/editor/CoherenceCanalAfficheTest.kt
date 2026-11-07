package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test de COHÉRENCE CROISÉE du canal tooling (INC-02, volet C du prompt
 * « retard d'affichage des sorties de build » §6.1) : pour les états
 * publiés par le service, les sorties des consommateurs — chip, icône de
 * canal, ligne de statut, console visible ([PresentationTooling.canalAffiche])
 * — désignent le MÊME canal, et ce canal est toujours le canal du DERNIER
 * CHOIX D'AFFICHAGE poussé par l'action qui démarre.
 *
 * Avant le correctif, QUATRE cascades décidaient « quel canal afficher ? »
 * (chip et icône : sync dès qu'une sync avait vécu ; statut : priorité
 * sync-puis-build aux replis différents ; console visible : filtre poussé
 * par l'action) : pendant un build lancé après une sync réussie, la
 * console du build s'affichait avec son bouton Annuler, mais le chip
 * disait « Sync », l'icône celle de la sync et le statut « Synchronisé
 * en X » — constat §6.1 reproduit par le test
 * `build lance apres une sync reussie - chip icone et statut disent BUILD`.
 *
 * L'en-tête du panneau suit l'ACTIVITÉ (`canalActif ?: canalDernierResultat`)
 * — un rôle distinct, vérifié ici aussi : il annonce le canal en vol même
 * quand l'utilisateur consulte l'autre console (INC-20).
 */
class CoherenceCanalAfficheTest {
    /** Horloge pilotable : les chronos se vérifient sans cadre Android. */
    private var instant = 1_000L

    private val service =
        GradleService(
            horloge = TimeProvider { instant },
            demarreur = FauxDemarreurServiceTooling(),
            journal = FakeAppLogger(),
        )

    // ------------------------------------------------------------------
    // Constat §6.1 INC-02 : build lancé après une sync réussie — le chip,
    // l'icône et le statut doivent dire BUILD (la console visible est
    // celle du build, poussée par l'action).
    // ------------------------------------------------------------------

    @Test
    fun `build lance apres une sync reussie - chip icone et statut disent BUILD`() {
        // Sync (l'action pousse le choix d'affichage vers SYNC).
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 194_000)),
        )
        // Build (l'action pousse le choix d'affichage vers BUILD).
        service.suivreBuild("b-1")
        service.publierEtatBuild(EtatBuild("b-1", StatutBuild.EN_COURS))
        val filtre = FiltreCanalConsole.BUILD
        val etat = service.etat.value

        val canal = PresentationTooling.canalAffiche(etat, filtre)

        assertEquals(
            "INC-02 : le canal affiché est celui du build en vol, pas celui de la sync conclue",
            CanalTooling.BUILD,
            canal,
        )
        // Le statut suit la MÊME cascade que l'en-tête, sur CE canal :
        // « Build en cours… », plus jamais « Synchronisé en X ».
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_build_en_cours),
            PresentationTooling.libelleStatut(etat, canal),
        )
        // L'icône du canal (baliserCanalStatut) dérive du même canal.
        assertEquals(CanalTooling.BUILD, canal)
    }

    @Test
    fun `sync en vol apres un build echoue - chip icone et statut disent SYNC`() {
        service.suivreBuild("b-1")
        service.publierEtatBuild(
            EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 36_000, messageEchec = "compileKotlin a échoué"),
        )
        service.marquerSyncEnCours()
        val filtre = FiltreCanalConsole.SYNC
        val etat = service.etat.value

        val canal = PresentationTooling.canalAffiche(etat, filtre)

        assertEquals(
            "INC-02 : le canal affiché est celui de la sync en vol",
            CanalTooling.SYNC,
            canal,
        )
        assertEquals(
            TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours),
            PresentationTooling.libelleStatut(etat, canal),
        )
    }

    // ------------------------------------------------------------------
    // INC-03 : le canal TACHES n'a PAS de représentation console — le
    // listage reste un transitoire de l'en-tête.
    // ------------------------------------------------------------------

    @Test
    fun `listage en vol par-dessus un resultat - la console reste celle du choix`() {
        service.marquerSyncEnCours()
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000)),
        )
        service.marquerTachesEnCours()
        val etat = service.etat.value

        assertEquals(
            "l'en-tête annonce le listage (canal actif)",
            CanalTooling.TACHES,
            etat.canalActif,
        )
        assertEquals(
            "INC-03 : la console affiche le choix utilisateur, jamais TACHES",
            CanalTooling.SYNC,
            PresentationTooling.canalAffiche(etat, FiltreCanalConsole.SYNC),
        )
        assertNotEquals(
            CanalTooling.TACHES,
            PresentationTooling.canalAffiche(etat, FiltreCanalConsole.BUILD),
        )
    }

    // ------------------------------------------------------------------
    // État vierge : rien à sélectionner — chip, icône et statut se taisent.
    // ------------------------------------------------------------------

    @Test
    fun `etat vierge - aucun canal affiche, statut replie sur l etat vide`() {
        val etat = service.etat.value

        assertNull(PresentationTooling.canalAffiche(etat, FiltreCanalConsole.SYNC))
        assertNull(PresentationTooling.canalAffiche(etat, FiltreCanalConsole.BUILD))
        assertEquals(
            TexteTooling.Ressource(R.string.editor_sortie_vide),
            PresentationTooling.libelleStatut(etat, null),
        )
    }

    // ------------------------------------------------------------------
    // Table croisée (INC-02, « test de cohérence croisée ») : pour toute
    // combinaison service-publiable {sync, build} × {vierge, en vol,
    // reussi, echoue, annule}, les sorties chip/icône/statut désignent le
    // même canal, et l'en-tête est COHÉRENT avec la console tant que
    // l'utilisateur n'a pas dérogé (pas de listage en vol).
    // ------------------------------------------------------------------

    @Test
    fun `table croisee - statut et console designent toujours le meme canal`() {
        val scenarios = tableScenariosService()

        assertTrue("la table de scénarios est peuplée", scenarios.isNotEmpty())
        scenarios.forEach { (nom, etat, filtre) ->
            val canal = PresentationTooling.canalAffiche(etat, filtre)

            // Le canal affiché est toujours SYNC ou BUILD (jamais TACHES,
            // jamais null hors état vierge).
            assertTrue(
                "$nom : canal null ou TACHES inattendu ($canal)",
                canal == null || canal == CanalTooling.SYNC || canal == CanalTooling.BUILD,
            )

            // Le statut parle du MÊME canal que le chip/icône : la
            // résolution passe par la cascade de l'en-tête sur ce canal.
            val statut = PresentationTooling.libelleStatut(etat, canal)
            assertTrue("$nom : statut résolu", statut is TexteTooling.Ressource || statut is TexteTooling.Brut)

            // Cohérence en-tête/console SANS dérogation utilisateur et SANS
            // listage en vol : l'en-tête suit l'activité (ou le dernier
            // résultat), la console suit le choix poussé par l'action.
            if (etat.tachesEnCours) return@forEach
            val canalEntete = etat.canalActif ?: etat.canalDernierResultat
            if (canalEntete != CanalTooling.TACHES) {
                assertEquals(
                    "$nom : sans dérogation, l'en-tête et la console désignent le même canal",
                    canalEntete,
                    canal,
                )
            }
        }
    }

    /** Construit la table des états service-publiables avec le choix
     *  d'affichage cohérent avec la dernière action lancée. */
    private fun tableScenariosService(): List<Triple<String, EtatGradle, FiltreCanalConsole>> = buildList {
        fun ajouter(nom: String, filtre: FiltreCanalConsole, bloc: GradleService.() -> Unit) {
            val service = GradleService(
                horloge = TimeProvider { instant },
                demarreur = FauxDemarreurServiceTooling(),
                journal = FakeAppLogger(),
            )
            service.bloc()
            add(Triple(nom, service.etat.value, filtre))
        }

        ajouter("vierge", FiltreCanalConsole.SYNC) { /* rien */ }
        ajouter("sync en vol", FiltreCanalConsole.SYNC) { marquerSyncEnCours() }
        ajouter("sync reussie", FiltreCanalConsole.SYNC) {
            marquerSyncEnCours()
            publierResultatSync(
                AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000)),
            )
        }
        ajouter("sync echouee", FiltreCanalConsole.SYNC) {
            marquerSyncEnCours()
            publierResultatSync(
                AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté")),
            )
        }
        ajouter("build en vol", FiltreCanalConsole.BUILD) {
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.EN_COURS))
        }
        ajouter("build reussi", FiltreCanalConsole.BUILD) {
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.REUSSI, dureeMs = 1_000))
        }
        ajouter("build echoue", FiltreCanalConsole.BUILD) {
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 1_000, messageEchec = "boom"))
        }
        ajouter("build annule", FiltreCanalConsole.BUILD) {
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.ANNULE, dureeMs = 500))
        }
        ajouter("sync reussie puis build echoue", FiltreCanalConsole.BUILD) {
            marquerSyncEnCours()
            publierResultatSync(
                AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000)),
            )
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 1_000, messageEchec = "boom"))
        }
        ajouter("build echoue puis sync reussie", FiltreCanalConsole.SYNC) {
            suivreBuild("b-1")
            publierEtatBuild(EtatBuild("b-1", StatutBuild.ECHOUE, dureeMs = 1_000, messageEchec = "boom"))
            marquerSyncEnCours()
            publierResultatSync(
                AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000)),
            )
        }
    }
}
