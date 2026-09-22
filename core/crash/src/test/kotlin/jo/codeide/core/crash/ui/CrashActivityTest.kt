package jo.codeide.core.crash.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import jo.codeide.core.crash.CrashLimits
import jo.codeide.core.crash.CrashReportFileStore
import jo.codeide.core.crash.OutilsTestCrash
import jo.codeide.core.crash.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests de l'écran dédié (section 5.8) : modes LIVE et VIEW, masquage du
 * redémarrage en cas de boucle, repli quand le rapport est introuvable —
 * sous Robolectric, dans le processus de test (le comportement multi-
 * processus relève des tests manuels P3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashActivityTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var store: CrashReportFileStore

    @Before
    fun preparer() {
        val repertoire = File(contexte.filesDir, CrashLimits.DIRECTORY_NAME)
        repertoire.deleteRecursively()
        store = CrashReportFileStore(repertoire)
    }

    /** Lance l'écran pour un rapport, dans le mode demandé. */
    private fun lancer(
        idRapport: String,
        mode: String,
    ): CrashActivity {
        val intention =
            Intent(contexte, CrashActivity::class.java)
                .putExtra(CrashActivity.EXTRA_REPORT_ID, idRapport)
                .putExtra(CrashActivity.EXTRA_MODE, mode)
        return Robolectric
            .buildActivity(CrashActivity::class.java, intention)
            .setup()
            .get()
    }

    @Test
    fun `le mode consultation affiche le résumé sans bouton redémarrer`() {
        store.save(OutilsTestCrash.rapport(id = "vue", horodatage = 10_000L))

        val ecran = lancer("vue", CrashActivity.MODE_VIEW)

        assertEquals(
            contexte.getString(R.string.crash_titre_consultation),
            ecran.findViewById<TextView>(R.id.crash_titre).text.toString(),
        )
        assertEquals(View.GONE, ecran.findViewById<Button>(R.id.crash_bouton_redemarrer).visibility)
        // Les détails sont repliés par défaut.
        assertEquals(View.GONE, ecran.findViewById<TextView>(R.id.crash_trace).visibility)
    }

    @Test
    fun `le mode direct affiche le bouton redémarrer`() {
        store.save(OutilsTestCrash.rapport(id = "direct", horodatage = 10_000L))

        val ecran = lancer("direct", CrashActivity.MODE_LIVE)

        assertEquals(
            contexte.getString(R.string.crash_titre_live),
            ecran.findViewById<TextView>(R.id.crash_titre).text.toString(),
        )
        assertEquals(View.VISIBLE, ecran.findViewById<Button>(R.id.crash_bouton_redemarrer).visibility)
    }

    @Test
    fun `une boucle masque le redémarrage et propose le conseil`() {
        store.save(OutilsTestCrash.rapport(id = "boucle", horodatage = 10_000L, boucle = true))

        val ecran = lancer("boucle", CrashActivity.MODE_LIVE)

        assertEquals(View.GONE, ecran.findViewById<Button>(R.id.crash_bouton_redemarrer).visibility)
        assertEquals(View.VISIBLE, ecran.findViewById<TextView>(R.id.crash_conseil_boucle).visibility)
        assertEquals(View.VISIBLE, ecran.findViewById<Button>(R.id.crash_bouton_vider_cache).visibility)
    }

    @Test
    fun `un rapport introuvable affiche l'écran de repli`() {
        val ecran = lancer("inexistant", CrashActivity.MODE_VIEW)

        assertEquals(
            contexte.getString(R.string.crash_introuvable_titre),
            ecran.findViewById<TextView>(R.id.crash_titre).text.toString(),
        )
        assertEquals(View.GONE, ecran.findViewById<Button>(R.id.crash_bouton_copier).visibility)
        // Fermer reste la seule action disponible en repli.
        assertEquals(View.VISIBLE, ecran.findViewById<Button>(R.id.crash_bouton_fermer).visibility)
        assertEquals(View.GONE, ecran.findViewById<MaterialCardView>(R.id.crash_carte_resume).visibility)
    }

    @Test
    fun `le bouton de détails déplie la trace puis la referme`() {
        store.save(OutilsTestCrash.rapport(id = "details", horodatage = 10_000L))
        val ecran = lancer("details", CrashActivity.MODE_VIEW)

        ecran.findViewById<Button>(R.id.crash_bouton_details).performClick()

        assertEquals(View.VISIBLE, ecran.findViewById<TextView>(R.id.crash_trace).visibility)
        assertEquals(
            contexte.getString(R.string.crash_details_masquer),
            ecran.findViewById<Button>(R.id.crash_bouton_details).text.toString(),
        )

        ecran.findViewById<Button>(R.id.crash_bouton_details).performClick()

        assertEquals(View.GONE, ecran.findViewById<TextView>(R.id.crash_trace).visibility)
    }

    @Test
    fun `la trace dépliée porte le rapport mis en forme`() {
        store.save(OutilsTestCrash.rapport(id = "trace", horodatage = 10_000L))
        val ecran = lancer("trace", CrashActivity.MODE_VIEW)

        ecran.findViewById<Button>(R.id.crash_bouton_details).performClick()

        val texte = ecran.findViewById<TextView>(R.id.crash_trace).text.toString()
        assertTrue(texte.contains("CodeIDE — Rapport de plantage"))
        assertTrue(texte.contains("java.lang.IllegalStateException"))
    }
}
