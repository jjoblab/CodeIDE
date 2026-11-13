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
 * « retard d'affichage des sorties de build » ; en-tête unifié v2 —
 * maquette sorties) : l'indicateur compact UNIQUE du rang 1 porte tout
 * le retour d'activité (l'ancien doublon du rang des onglets et la ligne
 * enrichie ont disparu), le rôle annoncé de la zone d'appui du rang 1 et
 * la note honnête « sync non annulable » dans le sous-titre de
 * l'indicateur. Extraits d'ActivityEditorLayoutTest (LargeClass).
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
    fun `l indicateur compact unique vit dans le rang unifie du sheet - INC-10`() {
        val racine = gonfler(R.layout.activity_editor)

        // En-tête unifié v2 : le rang 1 porte l'indicateur compact UNIQUE
        // — l'ancien doublon du rang des onglets a disparu, le retour
        // d'activité tooling reste toujours présent.
        val rangEntete = racine.findViewById<View>(R.id.rang_entete_panneau)
        assertNotNull(
            "indicateur compact du rang 1 (INC-10, en-tête unifié v2)",
            racine.findViewById<View>(R.id.indicateur_compact_entete),
        )
        assertNotNull(
            "l'indicateur compact vit DANS le rang 1 (INC-10)",
            rangEntete.findViewById<View>(R.id.pastille_canal_compact),
        )
        assertTrue(
            "les onglets vivent dans le rang 2 (INC-10)",
            racine.findViewById<View>(R.id.rang_onglets_panneau)
                .findViewById<View>(R.id.onglets_panneau) != null,
        )
        // Le doublon a bien disparu : l'ancien id ne résout plus.
        val base = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assert.assertEquals(
            "l'indicateur compact du rang des onglets a disparu (en-tête unifié v2)",
            0,
            base.resources.getIdentifier("indicateur_compact_onglets", "id", base.packageName),
        )
        // INC-17 : la zone d'appui du rang 1 a un rôle annoncé.
        assertNotNull(
            "contentDescription du rang 1 (INC-17)",
            rangEntete.contentDescription,
        )
    }

    @Test
    fun `l indicateur unique porte le sous-titre honnete sync non annulable - INC-12`() {
        val racine = gonfler(R.layout.activity_editor)

        // INC-12 : l'emplacement du bouton Arrêter (sous-titre de
        // l'indicateur) explique son absence pendant une sync.
        val sousTitre = racine.findViewById<android.widget.TextView>(R.id.sous_titre_compact)
        assertNotNull(
            "sous-titre de l'indicateur compact — porte la note honnête (INC-12)",
            sousTitre,
        )
        assertTrue(
            "le sous-titre vit DANS le rang 1, à l'emplacement du bouton Arrêter (INC-12)",
            racine.findViewById<View>(R.id.rang_entete_panneau)
                .findViewById<View>(R.id.sous_titre_compact) === sousTitre,
        )
    }
}
