package jo.codeide.core.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.model.PaletteCouleur
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test du point d'application unique des couleurs (ADR 0060) — volet
 * v0.80.6 : [AppliquerApparence.rappliquer] doit retinter un thème que
 * l'activité vient de REMPLACER. Cas réel de l'application :
 * `installSplashScreen()` résout `postSplashScreenTheme` puis appelle
 * `Activity.setTheme()` — un thème NEUF, sans l'overlay posé avant
 * création — si l'activité hôte ne repose pas l'overlay, elle et tous
 * ses fragments restent sur le thème de base quand l'éditeur et le
 * diagnostic suivent le réglage (retour utilisateur v0.80.5 : « à part
 * EditorActivity et CrashActivity, tous les autres écrans n'utilisent
 * pas le thème ou la palette choisie »).
 *
 * Distinction dynamique/palette : sur un appareil SANS Material You
 * (Robolectric SDK 26 < 31), un réglage « couleurs dynamiques » ne
 * s'applique pas — l'état doit retomber honnêtement sur la palette
 * statique, jamais sur « ni l'un ni l'autre ».
 *
 * Le cycle complet (splash réel → re-application) est couvert par le
 * test d'intégration de `app` qui lance `MainActivity` ; ici, le
 * `setTheme` du splash est simulé littéralement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppliquerApparenceTest {
    @Before
    fun preparer() {
        // État par défaut de l'objet — chaque test repasse par
        // installer() pour poser SON état, celui-ci ne sert qu'au
        // démarrage propre du test.
        reinitialiserEtat()
    }

    @After
    fun nettoyer() {
        // Bonne citoyenneté du singleton entre les tests du module.
        reinitialiserEtat()
    }

    @Test
    fun `rappliquer reteinte un theme remplace par la palette choisie`() {
        val contexte: Context = ApplicationProvider.getApplicationContext()
        AppliquerApparence.installer(
            application = contexte as Application,
            couleursDynamiques = false,
            palette = PaletteCouleur.BLEU,
        )

        val activite = activiteAuThemeRemplace()

        // Le remplacement de thème (setTheme d'installSplashScreen) a
        // effacé l'overlay posé avant création : retour à la base.
        assertEquals(
            "le thème remplacé doit retomber sur la palette de base",
            couleurAttendue(R.color.codeide_primary),
            activite.couleurPrimaire(),
        )

        // Le correctif : l'activité repose l'overlay APRÈS son
        // remplacement de thème, AVANT le gonflement du contenu.
        AppliquerApparence.rappliquer(activite)

        assertEquals(
            "la palette choisie doit teinter le thème remplacé",
            couleurAttendue(R.color.palette_bleu_primary),
            activite.couleurPrimaire(),
        )
    }

    @Test
    fun `les couleurs dynamiques indisponibles retombent sur la palette`() {
        val contexte: Context = ApplicationProvider.getApplicationContext()
        // Réglage « dynamiques » demandé… sur un appareil sans Material
        // You (SDK 26) : la demande ne s'applique pas.
        AppliquerApparence.installer(
            application = contexte as Application,
            couleursDynamiques = true,
            palette = PaletteCouleur.VERT,
        )

        val activite = activiteAuThemeRemplace()
        AppliquerApparence.rappliquer(activite)

        assertEquals(
            "l'état doit retomber sur la palette statique, pas sur la base",
            couleurAttendue(R.color.palette_vert_primary),
            activite.couleurPrimaire(),
        )
    }

    /** Activité attachée dont le thème vient d'être REMPLACÉ. */
    private fun activiteAuThemeRemplace(): Activity {
        val activite = Robolectric.buildActivity(Activity::class.java).get()
        // Littéralement le setTheme(postSplashScreenTheme) d'installSplashScreen.
        activite.setTheme(R.style.Theme_CodeIDE)
        return activite
    }

    /** Couleur effective d'une ressource du module, résolue par le contexte de test. */
    private fun couleurAttendue(idCouleur: Int): Int =
        ContextCompat.getColor(
            ApplicationProvider.getApplicationContext<Context>(),
            idCouleur,
        )

    /** Remet l'état du singleton sur les valeurs de production. */
    private fun reinitialiserEtat() {
        AppliquerApparence.installer(
            application = ApplicationProvider.getApplicationContext(),
            couleursDynamiques = true,
            palette = PaletteCouleur.INDIGO,
        )
    }
}
