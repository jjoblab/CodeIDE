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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Tests du DÉDOUBLONNAGE du rendu de l'en-tête tooling (INC-14, volet C du
 * prompt « retard d'affichage des sorties de build » §6.2) : la collecte de
 * l'état Gradle ENTIER ([GradleService] publie à chaque ligne de build,
 * chaque événement de téléchargement) appelait `rendre` à chaque émission —
 * chaque appel annulait et relançait la coroutine du chrono, réaffectait
 * les visibilités et rappelait `setProgressCompat(…, animate = true)` :
 * travail inutile du fil principal et scintillement potentiel de la bande.
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
                surPeekChange = {},
            )
    }

    @Test
    fun `la note sync non annulable apparait pendant une sync - INC-12`() {
        controleur.definirOnglet(OngletPanneau.CONSOLE)

        // Sync en vol : l'emplacement du bouton Arrêter explique son
        // absence — plus de silence de trois minutes.
        controleur.rendre(EtatGradle(synchronisationEnCours = true, debutSyncMs = 1_000L))
        org.junit.Assert.assertTrue(
            "INC-12 : la note honnête remplace le bouton absent pendant une sync",
            controleurBinding().noteNonAnnulable.isVisible,
        )

        // Build en vol : le bouton Arrêter EST là, pas de note.
        controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 2_000L))
        org.junit.Assert.assertFalse(controleurBinding().noteNonAnnulable.isVisible)
        org.junit.Assert.assertTrue(controleurBinding().boutonArreterTooling.isVisible)
    }

    @Test
    fun `l indicateur compact est present hors de la ligne enrichie - INC-10`() {
        val etat = EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 1_000L)

        // Onglet Problèmes (première section porte l'indicateur compact).
        controleur.definirOnglet(OngletPanneau.PROBLEMES)
        controleur.rendre(etat)
        org.junit.Assert.assertTrue(
            "INC-10 : la première section porte l'indicateur compact sur les autres onglets",
            controleurBinding().indicateurCompactEntete.root.isVisible,
        )
        org.junit.Assert.assertFalse(
            "INC-10 : le rang des onglets ne le porte que sheet étendu",
            controleurBinding().indicateurCompactOnglets.root.isVisible,
        )

        // Sheet étendu : le rang des onglets prend le relais. La première
        // section est cachée par l'ACTIVITÉ à l'extension (hors contrôleur)
        // — simulé ici pour vérifier le relais.
        controleur.definirSheetEtendu(true)
        controleurBinding().entetePanneau.isVisible = false
        org.junit.Assert.assertTrue(
            "INC-10 : le rang des onglets porte l'indicateur compact sheet étendu",
            controleurBinding().indicateurCompactOnglets.root.isVisible,
        )
        controleur.definirSheetEtendu(false)
        org.junit.Assert.assertFalse(
            "replié, l'indicateur compact du rang disparaît (la première section reprend)",
            controleurBinding().indicateurCompactOnglets.root.isVisible,
        )

        // État vierge : aucun indicateur, aucune ligne.
        controleur.definirSheetEtendu(false)
        controleur.rendre(EtatGradle())
        org.junit.Assert.assertFalse(controleurBinding().indicateurCompactEntete.root.isVisible)
        org.junit.Assert.assertFalse(controleurBinding().indicateurCompactOnglets.root.isVisible)
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
        controleur.definirOnglet(OngletPanneau.CONSOLE)

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
    fun `le peek de la console ne bouge plus entre vol et verdict - INC-18`() {
        controleur.definirOnglet(OngletPanneau.CONSOLE)

        controleur.rendre(EtatGradle(statutBuild = StatutBuild.EN_COURS, debutBuildMs = 1_000L))
        val enVol = controleur.peekContenuPx

        controleur.rendre(
            EtatGradle(statutBuild = StatutBuild.ECHOUE, dureeBuildMs = 36_000, messageEchecBuild = "boom"),
        )
        val auRepos = controleur.peekContenuPx

        assertEquals(
            "INC-18 : la fin d'une activité ne retire plus la ligne ni l'emplacement de la bande du peek",
            enVol,
            auRepos,
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
}
