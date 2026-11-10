package jo.codeide.feature.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Les layouts des deux onglets sorties du panneau inférieur (lot L4) :
 * le chrome LOGCAT (R3/R4 — puce de processus, niveaux, IA, bandeaux)
 * et le chrome JOURNAL (chips de niveaux, lien Diagnostic) autour du
 * lecteur UNIQUE du Centre de sorties, vérifié famille par famille
 * (ADR 0162 — le même composant partout, jamais un second afficheur).
 * Classé à part d'[ActivityEditorLayoutTest] (taille bornée — detekt
 * LargeClass) : un test par famille d'onglet, même hôte et même thème.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PanneauxSortiesLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(layout: Int, parent: ViewGroup? = null): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = android.view.ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(layout, parent, false)
    }

    @Test
    fun `le layout logcat porte son chrome R3 R4 et le lecteur famille LOGCAT`() {
        val logcat = gonfler(R.layout.fragment_panneau_sorties_logcat)
        assertNotNull("puce de processus (R3, lot L4)", logcat.findViewById<View>(R.id.puce_processus))
        assertNotNull("nom du processus (R3, lot L4)", logcat.findViewById<View>(R.id.nom_processus))
        assertNotNull("filtre de niveau (R3, lot L4)", logcat.findViewById<View>(R.id.bouton_niveau))
        assertNotNull("« Expliquer » IA (R3, lot L4)", logcat.findViewById<View>(R.id.bouton_expliquer_ia))
        assertNotNull("bandeau de pertes (R3, lot L4)", logcat.findViewById<View>(R.id.bandeau_pertes))
        assertNotNull("bandeau d'arrêt (R3, lot L4)", logcat.findViewById<View>(R.id.bandeau_arret))
        assertNotNull(
            "bandeau de session précédente (R3, lot L4)",
            logcat.findViewById<View>(R.id.bandeau_precedente),
        )
        assertNotNull(
            "lecteur famille LOGCAT embarqué (lot L4)",
            logcat.findViewById<View>(R.id.conteneur_lecteur),
        )
    }

    @Test
    fun `le layout journal porte ses chips son lien diagnostic et le lecteur famille JOURNAL`() {
        val journal = gonfler(R.layout.fragment_panneau_sorties_journal)
        assertNotNull("chip Débogage du journal (lot L4)", journal.findViewById<View>(R.id.chip_journal_debug))
        assertNotNull("chip Info du journal (lot L4)", journal.findViewById<View>(R.id.chip_journal_info))
        assertNotNull("chip Avertissement du journal (lot L4)", journal.findViewById<View>(R.id.chip_journal_warn))
        assertNotNull("chip Erreur du journal (lot L4)", journal.findViewById<View>(R.id.chip_journal_error))
        assertNotNull(
            "lien vers le journal complet (v0.32.4)",
            journal.findViewById<View>(R.id.bouton_journal_complet),
        )
        assertNotNull(
            "lecteur famille JOURNAL embarqué (lot L4)",
            journal.findViewById<View>(R.id.conteneur_lecteur),
        )
    }
}
