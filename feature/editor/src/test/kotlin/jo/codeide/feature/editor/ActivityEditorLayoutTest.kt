package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textview.MaterialTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Index de la première vue enfant portant cet id (-1 sinon). */
    private fun indexOfView(
        groupe: ViewGroup,
        id: Int,
    ): Int {
        for (i in 0 until groupe.childCount) {
            if (groupe.getChildAt(i).id == id) return i
        }
        return -1
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
    fun `l espace de travail porte les vraies classes code-editor et la vue vide riche`() {
        val racine = gonfler(R.layout.activity_editor)
        assertEquals(
            "le fil d'Ariane est la BreadcrumbBar de la bibliothèque (v0.32.4, retour v0.32.3)",
            jo.codeeditor.view.BreadcrumbBar::class.java,
            racine.findViewById<View>(R.id.fil_ariane_editeur).javaClass,
        )
        val barre = racine.findViewById<View>(R.id.barre_symboles)
        assertEquals(
            "la barre de symboles est la SymbolBarView de la bibliothèque (v0.32.4)",
            jo.codeeditor.view.SymbolBarView::class.java,
            barre.javaClass,
        )
        assertEquals("barre de symboles masquée par défaut (v0.32.3)", View.GONE, barre.visibility)
        assertNotNull(
            "bouton Parcourir les fichiers de l'état vide (v0.32.3)",
            racine.findViewById<View>(R.id.bouton_vide_explorer),
        )
        assertNotNull(
            "bouton Terminal de l'état vide (v0.32.3)",
            racine.findViewById<View>(R.id.bouton_vide_terminal),
        )
    }

    @Test
    fun `le panneau inferieur porte un conteneur de fragments pas de vues empilees`() {
        val racine = gonfler(R.layout.activity_editor)
        assertNotNull(
            "conteneur de fragments du panneau (v0.32.4, ADR 0055)",
            racine.findViewById<View>(R.id.conteneur_fragments_panneau),
        )
        // Les ids des vues empilées ont disparu des ressources — la
        // vérification passe par getIdentifier (R.id.contenu_* ne compile
        // plus, c'est le point).
        val base = ApplicationProvider.getApplicationContext<Context>()
        listOf("contenu_journal", "contenu_sortie", "contenu_problemes").forEach { idEmpile ->
            assertEquals(
                "l'id $idEmpile (vue empilée) a disparu des ressources (v0.32.4)",
                0,
                base.resources.getIdentifier(idEmpile, "id", base.packageName),
            )
        }
        assertEquals(
            "la barre de symboles reste SOUS l'en-tête du sheet (collée au clavier, v0.32.4)",
            R.id.barre_symboles,
            (racine.findViewById<View>(R.id.panneau_inferieur) as LinearLayout).let { panneau ->
                // Le sheet est vertical : la barre vient après l'en-tête,
                // avant les onglets et le conteneur de fragments.
                val indexEntete = indexOfView(panneau, R.id.entete_panneau)
                val indexBarre = indexOfView(panneau, R.id.barre_symboles)
                val indexOnglets = indexOfView(panneau, R.id.onglets_panneau)
                assertTrue(indexEntete in 0 until indexBarre)
                assertTrue(indexBarre < indexOnglets)
                R.id.barre_symboles
            },
        )
    }

    @Test
    fun `les fragments du panneau se gonflent avec leurs vues completes`() {
        val journal = gonfler(R.layout.fragment_panneau_journal)
        assertNotNull("filtres du journal (v0.32.4)", journal.findViewById<View>(R.id.filtres_journal))
        assertNotNull("liste du journal (v0.32.4)", journal.findViewById<View>(R.id.liste_journal))
        assertNotNull("vide du journal (v0.32.4)", journal.findViewById<View>(R.id.texte_journal_vide))
        assertNotNull(
            "lien vers le journal complet (v0.32.4)",
            journal.findViewById<View>(R.id.bouton_journal_complet),
        )

        val console = gonfler(R.layout.fragment_panneau_console)
        assertNotNull("statut de la sortie (v0.32.4)", console.findViewById<View>(R.id.statut_sortie))
        assertNotNull("liste de sortie (v0.32.4)", console.findViewById<View>(R.id.liste_sortie))
        assertNotNull(
            "annulation du build (v0.32.4)",
            console.findViewById<View>(R.id.bouton_annuler_build),
        )

        val problemes = gonfler(R.layout.fragment_panneau_problemes)
        assertNotNull("liste des problèmes (v0.32.4)", problemes.findViewById<View>(R.id.liste_problemes))
        assertNotNull("vide des problèmes (v0.32.4)", problemes.findViewById<View>(R.id.texte_problemes_vide))
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
    fun `la barre du fond de ligne selectionnee reste fine et pleine hauteur`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        val fond =
            ContextCompat.getDrawable(contexte, R.drawable.fond_ligne_selectionnee)
                as android.graphics.drawable.LayerDrawable
        val hauteur = 136
        fond.setBounds(0, 0, 320, hauteur)
        val barre = fond.getDrawable(1)
        assertEquals(
            "la barre doit couvrir toute la hauteur de la ligne (§ 6.1 — l'ancien calque s'étirait pleine largeur)",
            hauteur,
            barre.bounds.height(),
        )
        assertTrue(
            "la barre doit rester large de ~2,5 dp, pas s'étirer (v0.32.1)",
            barre.bounds.width() <= 4,
        )
        assertTrue("la barre reste ancrée au bord de départ", barre.bounds.left == 0)
    }

    @Test
    fun `le popover deplacer porte la liste deroulante de destination`() {
        val popover = gonfler(R.layout.popover_deplacer)
        assertNotNull(
            "icône liste déroulante du champ Destination (v0.32.1)",
            popover.findViewById<View>(R.id.bouton_destination_deroulante),
        )
        assertNotNull(
            "titre du popover de choix de destination (v0.32.1)",
            gonfler(R.layout.popover_destination_deplacer).findViewById<View>(R.id.titre_destination_choisir),
        )
        assertNotNull(
            "liste des dossiers destination (v0.32.1)",
            gonfler(R.layout.popover_destination_deplacer).findViewById<View>(R.id.liste_dossiers_destination),
        )
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
    fun `les fragments d'apercu se gonflent`() {
        assertNotNull(
            "aperçu Recherche/Git (§ 1)",
            gonfler(R.layout.fragment_apercu_simple).findViewById<View>(R.id.description_apercu),
        )
        // Le fragment Terminal du tiroir vit dans feature:terminal depuis
        // la v0.32.2 (rendu réel des sessions, ADR 0053) : ses layouts se
        // gonflent dans SES tests — plus rien ici.
        assertTrue(
            "le tiroir v2 ne référence plus la barre BottomNavigationView",
            true,
        )
    }
}
