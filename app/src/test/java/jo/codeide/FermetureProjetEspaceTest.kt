package jo.codeide

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.feature.editor.ClesEditor
import jo.codeide.feature.editor.EditorActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Régression v0.183.0 → v0.184.0 (retour propriétaire « j'appuie sur
 * Fermer le projet du dialogue, rien ne se passe, ça ne ferme rien du
 * tout ») : reproduction HONNÊTE du geste réel sur l'activité VIVANTE.
 *
 * Le parcours testé est le chemin de PRODUCTION complet :
 * 1. retour système (`onBackPressedDispatcher`) → `ActionEditor.Quitter`
 *    (jamais d'appel privé au ViewModel — la panne peut vivre dans le
 *    câblage de l'écran, c'est justement lui qu'on éprouve) ;
 * 2. l'effet `ConfirmerSortie` doit faire apparaître le dialogue
 *    honnête « Fermer le projet ? » ;
 * 3. l'appui sur son bouton « Fermer le projet » doit terminer
 *    l'activité (l'écran se referme — le projet est fermé).
 *
 * Le projet « alpha » est inconnu du registre : l'espace vit SANS
 * session (contrat du navigateur — aucune identité vide ou inconnue ne
 * crashe, l'accueil reste) ; ce choix épargne au test la couche FUSE
 * tout en exerçant le cycle de fermeture de l'écran lui-même.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
class FermetureProjetEspaceTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Dépôt des paramètres : terminer l'installation avant de lancer. */
    @Inject
    lateinit var depotParametres: SettingsRepository

    @Before
    fun preparer() {
        regleHilt.inject()
        runBlocking {
            depotParametres.updateSettings { it.copy(isSetupCompleted = true) }
            depotParametres.observeSettings().first()
        }
    }

    private fun intentionEspace(): Intent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        EditorActivity::class.java,
    ).putExtra(ClesEditor.EXTRA_PROJECT_ID, "alpha")

    @Test
    fun `le retour sans onglet affiche le dialogue et son bouton referme l espace`() {
        val controleur = Robolectric.buildActivity(EditorActivity::class.java, intentionEspace())
        controleur.setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activite = controleur.get()
        try {
            // 1. Geste réel de sortie : le retour système.
            activite.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()

            // Diagnostic honnête : ni sortie silencieuse (l'écran vivrait
            // fermé sans dialogue) ni dialogue absent (le bouton n'aurait
            // alors rien à refermer).
            assertTrue(
                "le retour ne doit JAMAIS refermer l'espace sans dialogue",
                !activite.isFinishing && !activite.isDestroyed,
            )

            // 2. Le dialogue honnête « Fermer le projet ? » s'affiche
            //    (ShadowDialog : les dialogues Material sont des
            //    android.app.Dialog — ShadowAlertDialog ne voit QUE les
            //    android.app.AlertDialog du framework).
            val dialogue = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotNull(
                "le retour doit afficher le dialogue « Fermer le projet ? »",
                dialogue,
            )

            // 3. L'appui sur « Fermer le projet » referme l'espace.
            dialogue!!.findViewById<android.widget.Button>(android.R.id.button1)!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "l'espace doit se refermer après confirmation (écran vivant = bug)",
                activite.isFinishing || activite.isDestroyed,
            )
        } finally {
            controleur.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
