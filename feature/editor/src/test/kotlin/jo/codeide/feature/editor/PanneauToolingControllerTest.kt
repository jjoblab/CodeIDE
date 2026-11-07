package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
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
        controleur =
            PanneauToolingController(
                activite = activite,
                liaison = jo.codeide.feature.editor.databinding.ActivityEditorBinding.bind(racine),
                horloge = TimeProvider { instant },
                surArret = {},
                surPeekChange = {},
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
