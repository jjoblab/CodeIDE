package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Tests du DÉDOUBLONNAGE du rendu tooling (INC-14, volet C du prompt
 * « retard d'affichage des sorties de build » §6.2) : la collecte de
 * l'état Gradle ENTIER ([GradleService] publie à chaque ligne de build,
 * chaque événement de téléchargement) appelait `rendre` à chaque émission —
 * chaque appel annulait et relançait la coroutine du chrono, réaffectait
 * les visibilités et rappelait `setProgressCompat(…, animate = true)` :
 * travail inutile du fil principal et scintillement potentiel de la bande.
 *
 * En-tête unifié v2 (maquette sorties) : l'INDICATEUR COMPACT UNIQUE du
 * rang 1 porte tout le retour tooling — l'ancienne pile (ligne enrichie
 * 34 dp sur Console + deux indicateurs compacts) a disparu. Les tests
 * suivent : la note « Sync non annulable » vit dans le SOUS-TITRE de
 * l'indicateur (INC-12), la bande de progression vit sous le rang 1 pour
 * TOUS les onglets (INC-13), le peek ne bouge plus (INC-18 : contenu
 * constant 48 dp).
 *
 * Le correctif : dédoublonnage STRUCTUREL (un en-tête identique ne rend
 * rien), chrono relancé seulement au CHANGEMENT de départ, progression
 * bornée à ~10 mises à jour/s. Les compteurs d'observabilité
 * ([PanneauToolingController.nbRendusEntete], [nbRelancesChrono],
 * [nbMajProgression]) portent la mesure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PanneauToolingControllerTest {
    /** Horloge pilotable : le seuil de progression se vérifie sans attendre. */
    private var instant = 10_000L

    private lateinit var controleur: PanneauToolingController

    /** Liaison de la MÊME racine que celle du contrôleur (il ne
     *  l'expose pas — les vues sont identiques). */
    private fun controleurBinding(): jo.codeide.feature.editor.databinding.ActivityEditorBinding = liaisonLiee

    private lateinit var liaisonLiee: jo.codeide.feature.editor.databinding.ActivityEditorBinding

    @Before
    fun monter() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        val racine = LayoutInflater.from(contexte).inflate(R.layout.activity_editor, null, false)
        // Le thème doit être posé AVANT le cycle de vie (AppCompat exige
        // un descendant de Theme.AppCompat dès onCreate).
        val activiteControleur = Robolectric.buildActivity(AppCompatActivity::class.java)
        activiteControleur.get().setTheme(RUi.style.Theme_CodeIDE)
        val activite = activiteControleur.setup().get()
        liaisonLiee = jo.codeide.feature.editor.databinding.ActivityEditorBinding.bind(racine)
        controleur =
            PanneauToolingController(
                activite = activite,
                liaison = liaisonLiee,
                horloge = TimeProvider { instant },
                surArret = {},
            )
    }

    @Test
    fun `la note sync non annulable occupe le sous-titre pendant une sync - INC-12`() {
        // Sync en vol : l'emplacement du bouton Arrêter (absent) explique
        // son absence — le sous-titre de l'indicateur dit POURQUOI.
        controleur.rendre(EtatGradle(synchronisationEnCours = true, debutSyncMs = 1_000L))
        org.junit.Assert.assertTrue(
            "INC-12 : la note honnête remplace le bouton absent dans le sous-titre de l'indicateur",
            controleurBinding().indicateurCompactEntete.sousTitreCompact.isVisible,
        )
        org.junit.Assert.assertEquals(
            "INC-12 : le sous-titre PORTE la note « Sync non annulable »",
            controleurBinding().indicateurCompactEntete.sousTitreCompact.text.toString(),
            controleurBinding().root.context.getString(R.string.editor_tooling_sync_non_annulable),
        )

        // Build en vol : le bouton Arrêter EST là, le sous-titre rend au
        // détail d'étape (aucune étape ici : masqué).
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 2_000L))
        org.junit.Assert.assertTrue(
            "INC-11 : le bouton Arrêter de l'indicateur est visible pendant un build",
            controleurBinding().indicateurCompactEntete.boutonArreterCompact.isVisible,
        )
    }

    @Test
    fun `l indicateur compact unique suit l activite et se repose proprement - INC-10`() {
        val etat = EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 1_000L)

        // En vol : la pastille du rang 1 est là, quel que soit l'onglet.
        controleur.rendre(etat)
        org.junit.Assert.assertTrue(
            "INC-10 : la pastille de l'indicateur compact du rang 1 est visible en vol",
            controleurBinding().indicateurCompactEntete.pastilleCanalCompact.isVisible,
        )
        org.junit.Assert.assertEquals(
            "INC-10 : le titre de l'indicateur porte l'activité courante",
            controleurBinding().indicateurCompactEntete.activiteCompact.text.toString().isNotEmpty(),
            true,
        )

        // État vierge : RE-RENDU propre (v0.39.1) — pastille masquée,
        // titres effacés, plus de résidu d'un build précédent.
        controleur.rendre(EtatGradle())
        org.junit.Assert.assertFalse(
            "état vierge : la pastille disparaît, l'indicateur se repose",
            controleurBinding().indicateurCompactEntete.pastilleCanalCompact.isVisible,
        )
        assertEquals(
            "état vierge : le titre est effacé (aucun résidu)",
            "",
            controleurBinding().indicateurCompactEntete.activiteCompact.text.toString(),
        )
    }

    @Test
    fun `une rafale d etats identiques ne produit qu un rendu`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 1_000L,
                etapesSync =
                listOf(EtapeSyncAffichee(etape = EtapeSync.DISTRIBUTION, octetsRecus = 42)),
            )

        repeat(20) { controleur.rendre(etat) }

        assertEquals(
            "INC-14 : 20 émissions identiques → UN SEUL rendu effectif",
            1,
            controleur.nbRendusEntete,
        )
    }

    @Test
    fun `le chrono n est relance qu au changement de depart`() {
        // Sync en vol (départ 1 000), puis la progression avance — le
        // ticker ne doit PAS être relancé à chaque événement.
        controleur.rendre(
            EtatGradle(
                synchronisationEnCours = true,
                debutSyncMs = 1_000L,
                etapesSync =
                listOf(
                    EtapeSyncAffichee(
                        etape = EtapeSync.DEPENDANCES,
                        octetsRecus = 1_048_576L,
                        octetsTotal = 10_485_760L,
                    ),
                ),
            ),
        )
        repeat(10) { indice ->
            controleur.rendre(
                EtatGradle(
                    synchronisationEnCours = true,
                    debutSyncMs = 1_000L,
                    etapesSync =
                    listOf(
                        EtapeSyncAffichee(
                            etape = EtapeSync.DEPENDANCES,
                            octetsRecus = (indice + 2) * 1_048_576L,
                            octetsTotal = 10_485_760L,
                        ),
                    ),
                ),
            )
        }
        assertEquals(1, controleur.nbRelancesChrono)

        // Un build démarre : NOUVEAU départ → nouvelle relance (et une seule).
        repeat(5) {
            controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 90_000L))
        }
        assertEquals(2, controleur.nbRelancesChrono)
    }

    @Test
    fun `la bande flashe au verdict terminal puis se masque - INC-13`() {
        // Build en vol : bande visible (indéterminée).
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 1_000L))
        org.junit.Assert.assertTrue(
            "en vol, la bande est visible",
            controleurBinding().progressionTooling.isVisible,
        )

        // Verdict RÉUSSI : flash — la bande reste visible (pleine, verte)
        // bien que l'activité soit terminée ; plus de bande permanente.
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 4_000))
        org.junit.Assert.assertTrue(
            "INC-13 : au verdict, la bande flashe (succès comme échec)",
            controleurBinding().progressionTooling.isVisible,
        )
        org.junit.Assert.assertFalse(
            "INC-13 : la bande n'est plus indéterminée au verdict — pleine",
            controleurBinding().progressionTooling.isIndeterminate,
        )
    }

    @Test
    fun `la progression determinee est bornee a dix mises a jour par seconde`() {
        fun etatProgression(recus: Long): EtatGradle = EtatGradle(
            synchronisationEnCours = true,
            debutSyncMs = 1_000L,
            etapesSync =
            listOf(
                EtapeSyncAffichee(
                    etape = EtapeSync.DEPENDANCES,
                    octetsRecus = recus,
                    octetsTotal = 10_485_760L,
                ),
            ),
        )

        // T0 : première valeur appliquée.
        controleur.rendre(etatProgression(1_048_576L))
        assertEquals(1, controleur.nbMajProgression)

        // Rafale de 8 événements en 80 ms (moins que le seuil de 100 ms) :
        // la barre ne repeint PAS — c'est précisément le throttle.
        repeat(8) { indice ->
            instant += 10
            controleur.rendre(etatProgression((indice + 2) * 512_000L))
        }
        assertEquals(
            "INC-14 : la rafale de téléchargement ne repeint pas la barre à chaque événement",
            1,
            controleur.nbMajProgression,
        )

        // Le seuil passé, la valeur suivante s'applique.
        instant += 150
        controleur.rendre(etatProgression(8_388_608L))
        assertEquals(2, controleur.nbMajProgression)

        // La valeur FINALE (succès, pleine) passe toujours, hors throttle.
        instant += 10
        controleur.rendre(
            EtatGradle(
                synchronisationReussie =
                jo.codeide.core.domain.ResultatSynchronisation(
                    projectDir = "/p",
                    reussie = true,
                    dureeMs = 4_000,
                ),
            ),
        )
        assertEquals(3, controleur.nbMajProgression)
    }

    @Test
    fun `l identite problemes dit le compte reel des diagnostics - retour 2026-11-12`() {
        val contexte = liaisonLiee.root.context
        val indicateur = controleurBinding().indicateurCompactEntete
        controleur.rendre(EtatGradle(groupesProblemes = listOf(etatGroupeProblemes())))
        controleur.definirOnglet(OngletPanneau.PROBLEMES)
        assertEquals(
            contexte.getString(R.string.editor_panneau_problemes),
            indicateur.activiteCompact.text.toString(),
        )
        assertEquals(
            contexte.resources.getQuantityString(R.plurals.editor_panneau_problemes_compte, 3, 3),
            indicateur.sousTitreCompact.text.toString(),
        )
        assertTrue(indicateur.pastilleCanalCompact.isVisible)
        assertFalse(indicateur.boutonArreterCompact.isVisible)
        assertFalse(controleurBinding().progressionTooling.isVisible)
    }

    @Test
    fun `l identite journal dit la fenetre vivante - retour 2026-11-12`() {
        val contexte = liaisonLiee.root.context
        val indicateur = controleurBinding().indicateurCompactEntete
        controleur.rendre(EtatGradle())
        controleur.definirOnglet(OngletPanneau.JOURNAL)
        assertEquals(
            contexte.getString(R.string.editor_panneau_journal_entete_titre),
            indicateur.activiteCompact.text.toString(),
        )
        assertEquals(
            contexte.getString(R.string.editor_panneau_journal_entete_sous),
            indicateur.sousTitreCompact.text.toString(),
        )
    }

    @Test
    fun `l identite logcat suit le processus reel du pont - retour 2026-11-12`() {
        val contexte = liaisonLiee.root.context
        val indicateur = controleurBinding().indicateurCompactEntete
        controleur.rendre(EtatGradle())

        // Sans session : honnête — aucun processus.
        controleur.definirSessionsLogcat(jo.codeide.feature.sorties.EtatSessionsLogcat())
        controleur.definirOnglet(OngletPanneau.LOGCAT)
        assertEquals(
            contexte.getString(R.string.editor_panneau_logcat),
            indicateur.activiteCompact.text.toString(),
        )
        assertEquals(
            contexte.getString(R.string.editor_panneau_logcat_aucun),
            indicateur.sousTitreCompact.text.toString(),
        )

        // Session vivante : le processus réel et son pid (données du pont).
        controleur.definirSessionsLogcat(
            jo.codeide.feature.sorties.EtatSessionsLogcat(
                sessions = listOf(
                    jo.codeide.core.domain.SessionJournal(
                        id = "s1",
                        identite = jo.codeide.core.domain.IdentiteSessionJournal(
                            paquet = "jo.demo",
                            pid = 12345,
                            nomProcessus = ":monapp",
                        ),
                        etat = jo.codeide.core.domain.EtatSessionJournal.VIVANTE,
                        fin = null,
                        nombreLignes = 12,
                        lignesPerdues = 0L,
                        sortiePrecedente = null,
                    ),
                ),
                selectionRegistre = "s1",
            ),
        )
        assertEquals(":monapp", indicateur.activiteCompact.text.toString())
        assertEquals(
            contexte.getString(R.string.editor_panneau_logcat_pid, 12345),
            indicateur.sousTitreCompact.text.toString(),
        )
    }

    @Test
    fun `une activite en vol prime sur l identite de l onglet - retour 2026-11-12`() {
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 1_000L))
        // Même sur l'onglet Journal, l'en-tête unifié garde l'activité
        // visible : le bouton Arrêter reste là (INC-11), le verdict
        // tooling habite le rang 1.
        controleur.definirOnglet(OngletPanneau.JOURNAL)
        assertTrue(
            controleurBinding().indicateurCompactEntete.boutonArreterCompact.isVisible,
        )
    }

    @Test
    fun `le retour sur l onglet console re-rend le verdict tooling apres une identite - retour 2026-11-12`() {
        // Un build RÉUSSI conclut : le verdict vit sur l'onglet Console.
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 4_000L))
        val indicateur = controleurBinding().indicateurCompactEntete
        val titreVerdict = indicateur.activiteCompact.text.toString()
        org.junit.Assert.assertTrue(titreVerdict.isNotEmpty())

        // Journal : l'identité remplace le verdict à l'écran.
        controleur.definirOnglet(OngletPanneau.JOURNAL)
        org.junit.Assert.assertNotEquals(titreVerdict, indicateur.activiteCompact.text.toString())

        // Retour Console : le verdict REVIENT — le dédoublonnage INC-14
        // ne peut pas avaler le rendu derrière une identité d'onglet.
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        assertEquals(titreVerdict, indicateur.activiteCompact.text.toString())
    }

    /** Un groupe de diagnostics de test (3 problèmes — le compte du badge). */
    private fun etatGroupeProblemes(): GroupeProblemes = GroupeProblemes(
        fichier = "app/MainActivity.kt",
        nomFichier = "MainActivity.kt",
        problems = listOf(
            jo.codeide.core.domain.DiagnosticBuild(
                severite = jo.codeide.core.domain.SeveriteDiagnostic.ERREUR,
                fichier = "app/MainActivity.kt",
                ligne = 42L,
                colonne = 19L,
                message = "Référence non résolue",
                source = "compileDebugKotlin",
            ),
            jo.codeide.core.domain.DiagnosticBuild(
                severite = jo.codeide.core.domain.SeveriteDiagnostic.AVERTISSEMENT,
                fichier = "app/MainActivity.kt",
                ligne = 7L,
                colonne = 9L,
                message = "paramètre jamais utilisé",
                source = "compileDebugKotlin",
            ),
            jo.codeide.core.domain.DiagnosticBuild(
                severite = jo.codeide.core.domain.SeveriteDiagnostic.ERREUR,
                fichier = "app/Ui/Apercu.kt",
                ligne = 18L,
                colonne = 1L,
                message = "type mismatch",
                source = "compileDebugKotlin",
            ),
        ),
    )
}
