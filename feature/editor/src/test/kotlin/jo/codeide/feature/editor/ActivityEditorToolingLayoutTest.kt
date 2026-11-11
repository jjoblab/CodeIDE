package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du CHROME tooling du sheet (INC-10/12/17, volet C du prompt
 * « retard d'affichage des sorties de build ») : les indicateurs compacts
 * — un dans la première section (autres onglets repliés), un dans le rang
 * des onglets (sheet étendu) —, le rôle annoncé de la zone d'appui de la
 * ligne enrichie et la note honnête « sync non annulable ». Extraits
 * d'ActivityEditorLayoutTest (LargeClass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityEditorToolingLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(layout: Int): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(layout, null, false)
    }

    @Test
    fun `les indicateurs compacts tooling vivent dans l en tete du sheet - INC-10`() {
        val racine = gonfler(R.layout.activity_editor)

        // Le rang des onglets PORTE l'indicateur compact (sheet étendu)
        // et la première section en porte un autre (autres onglets) — le
        // retour d'activité tooling est toujours présent.
        val rangOnglets = racine.findViewById<View>(R.id.rang_onglets_panneau)
        assertNotNull(
            "indicateur compact du rang des onglets (INC-10)",
            racine.findViewById<View>(R.id.indicateur_compact_onglets),
        )
        assertNotNull(
            "indicateur compact de la première section (INC-10)",
            racine.findViewById<View>(R.id.indicateur_compact_entete),
        )
        assertTrue(
            "les onglets vivent dans le rang (INC-10)",
            rangOnglets.findViewById<View>(R.id.onglets_panneau) != null,
        )
        // INC-17 : la zone d'appui de la ligne tooling a un rôle annoncé.
        assertNotNull(
            "contentDescription de la ligne tooling (INC-17)",
            racine.findViewById<View>(R.id.ligne_tooling).contentDescription,
        )
    }

    @Test
    fun `la ligne enrichie porte sa note honnete sync non annulable - INC-12`() {
        val racine = gonfler(R.layout.activity_editor)

        assertNotNull(
            "note honnête à l'emplacement du bouton Arrêter (INC-12)",
            racine.findViewById<View>(R.id.note_non_annulable),
        )
    }
}
