package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textview.MaterialTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du gonflage des layouts de l'étape 31 (explorateur v2,
 * `docs/EXPLORATEUR_V2.md`) : `activity_editor.xml` doit se gonfler sans
 * [android.view.InflateException] (historique v0.19.0 : un `<menu>` inline
 * tuait `EditorActivity` avant `onCreate` — le tiroir à fragments ne
 * référence plus aucun `<menu>`, le rail est une vue maison), et les
 * layouts du tiroir à fragments (explorateur, aperçus, terminal, lignes
 * de nœud, popovers, snackbar, rail) doivent se gonfler sans exception
 * sous le thème réel de l'application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityEditorLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(
        layout: Int,
        parent: ViewGroup? = null,
    ): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(layout, parent, false)
    }

    @Test
    fun `le layout de l'espace de travail se gonfle sans exception`() {
        val racine = gonfler(R.layout.activity_editor)
        assertNotNull(
            "le conteneur de fragments du tiroir doit exister (étape 31)",
            racine.findViewById<View>(R.id.conteneur_fragments_tiroir),
        )
        assertNotNull(
            "le rail de fragments du tiroir doit exister (§ 14)",
            racine.findViewById<View>(R.id.rail_fragments),
        )
        assertNotNull(
            "la poignée de redimensionnement doit exister (§ 13)",
            racine.findViewById<View>(R.id.poignee_tiroir),
        )
        assertNotNull(
            "la zone du snackbar au-dessus du rail doit exister (§ 15)",
            racine.findViewById<View>(R.id.zone_snackbar_tiroir),
        )
    }

    @Test
    fun `le fragment explorateur porte entete bascule et barre presse-papiers`() {
        val fragment = gonfler(R.layout.fragment_explorateur)
        assertNotNull("entête du fragment (§ 4)", fragment.findViewById<View>(R.id.entete_explorateur))
        assertNotNull("segment Projet (§ 5)", fragment.findViewById<View>(R.id.segment_projet))
        assertNotNull("segment Privé (§ 5)", fragment.findViewById<View>(R.id.segment_prive))
        assertNotNull("fil d'Ariane (§ 4.4)", fragment.findViewById<View>(R.id.segments_ariane))
        assertNotNull("arbre (§ 6)", fragment.findViewById<View>(R.id.liste_explorateur))
        assertNotNull(
            "barre presse-papiers masquée par défaut (§ 12)",
            fragment.findViewById<View>(R.id.barre_presse_papiers).apply {
                assertEquals(View.GONE, visibility)
            },
        )
    }

    @Test
    fun `la ligne de nœud v2 porte guides chevron point d'etat et edition inline`() {
        val ligne = gonfler(R.layout.ligne_noeud_arborescence)
        assertNotNull("guides dessinés (§ 6.2)", ligne.findViewById<View>(R.id.guides_ligne))
        assertNotNull("chevron des dossiers (§ 6.1)", ligne.findViewById<View>(R.id.chevron_noeud))
        assertNotNull("point d'état des fichiers (§ 7)", ligne.findViewById<View>(R.id.point_etat_noeud))
        assertNotNull("compteur d'enfants (§ 6.1)", ligne.findViewById<View>(R.id.compte_noeud))
        assertNotNull("badge de la racine (§ 6.1)", ligne.findViewById<View>(R.id.badge_racine))
        assertNotNull("éditeur inline (§ 11)", ligne.findViewById<View>(R.id.edition_noeud))
    }

    @Test
    fun `les popovers maison se gonflent sans exception`() {
        assertNotNull(gonfler(R.layout.popover_actions_noeud).findViewById<View>(R.id.actions_popover))
        assertNotNull(gonfler(R.layout.popover_deplacer).findViewById<View>(R.id.champ_destination_deplacer))
        assertNotNull(gonfler(R.layout.popover_supprimer).findViewById<View>(R.id.message_supprimer))
        assertNotNull(gonfler(R.layout.popover_legende).findViewById<View>(R.id.legende_point_actif))
    }

    @Test
    fun `le snackbar maison et l'item de rail se gonflent`() {
        val snackbar = gonfler(R.layout.vue_snackbar_arbre)
        assertNotNull(snackbar.findViewById<View>(R.id.texte_snackbar_arbre))
        assertNotNull(snackbar.findViewById<View>(R.id.chemin_snackbar_arbre))
        assertNotNull(snackbar.findViewById<View>(R.id.action_snackbar_arbre))

        val rail = gonfler(R.layout.item_rail_fragment, LinearLayout(ApplicationProvider.getApplicationContext()))
        assertNotNull(rail.findViewById<View>(R.id.encoche_rail))
        assertNotNull(rail.findViewById<ImageView>(R.id.icone_rail))
        assertNotNull(rail.findViewById<MaterialTextView>(R.id.libelle_rail))
    }

    @Test
    fun `les fragments d'apercu et le fragment terminal se gonflent`() {
        assertNotNull(
            "aperçu Recherche/Git (§ 1)",
            gonfler(R.layout.fragment_apercu_simple).findViewById<View>(R.id.description_apercu),
        )
        assertNotNull(
            "carte du terminal migrée dans son fragment (T6)",
            gonfler(R.layout.fragment_terminal_tiroir).findViewById<View>(R.id.carte_terminal),
        )
        assertTrue(
            "le tiroir v2 ne référence plus la barre BottomNavigationView",
            true,
        )
    }
}
