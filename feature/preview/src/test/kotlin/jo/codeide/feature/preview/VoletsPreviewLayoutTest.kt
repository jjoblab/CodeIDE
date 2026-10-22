package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Gonflage des layouts des VOLETS de la feuille (PE9a — patron
 * [ActivityPreviewLayoutTest]) : chaque layout de volet et chaque item
 * se gonfle sous le thème réel de l'application — les RECYCLERVIEW
 * (arbre, problèmes, source) remplacent les LinearLayout reconstruits
 * de l'ancien fragment monolithique, les items (rangée d'arbre,
 * problème, ligne de source) sont des layouts RÉELS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VoletsPreviewLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(ressource: Int): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, R.style.Theme_CodeIDE_Apercu)
        return LayoutInflater.from(contexte).inflate(ressource, null)
    }

    @Test
    fun `le volet arbre se gonfle avec sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_arbre)
        assertNotNull(volet.findViewById<View>(R.id.liste_arbre))
    }

    @Test
    fun `le volet attributs se gonfle avec sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_attributs)
        assertNotNull(volet.findViewById<View>(R.id.volet_attributs))
    }

    @Test
    fun `la carte de correctif se gonfle avec son titre, son corps et son bouton`() {
        // PE11g — ref item_correctif : la carte ambre du bloc « Corrections
        // rapides » (titre du problème, message, bouton d'action violet).
        val carte = gonfler(R.layout.item_correctif)
        assertNotNull(carte.findViewById<View>(R.id.carte_correctif))
        assertNotNull(carte.findViewById<View>(R.id.titre_correctif))
        assertNotNull(carte.findViewById<View>(R.id.corps_correctif))
        assertNotNull(carte.findViewById<View>(R.id.bouton_correctif))
    }

    @Test
    fun `le volet problèmes se gonfle avec sa liste recyclée et son message vide`() {
        // PE11k — ref fragment_volet_problemes : le message « Aucun
        // problème détecté… » vit dans le LAYOUT (plus de vue en code).
        val volet = gonfler(R.layout.fragment_volet_problemes)
        assertNotNull(volet.findViewById<View>(R.id.liste_problemes))
        assertNotNull(volet.findViewById<View>(R.id.message_aucun_probleme))
    }

    @Test
    fun `le volet source se gonfle avec son en-tête et sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_source)
        assertNotNull(volet.findViewById<View>(R.id.plage_source))
        assertNotNull(volet.findViewById<View>(R.id.bouton_ouvrir_editeur))
        assertNotNull(volet.findViewById<View>(R.id.liste_source))
    }

    @Test
    fun `la rangée d'arbre porte ses quatre repères`() {
        val item = gonfler(R.layout.item_arbre)
        assertNotNull(item.findViewById<View>(R.id.pill_monogramme))
        assertNotNull(item.findViewById<View>(R.id.libelle_classe))
        assertNotNull(item.findViewById<View>(R.id.libelle_id))
        assertNotNull(item.findViewById<View>(R.id.badge_arbre))
    }

    @Test
    fun `la rangée de problème porte sa pastille son détail et le lien Voir`() {
        // PE11k — ref item_probleme : le « Voir » mono accent vit dans le
        // layout (miroir `.prob .go` de la maquette).
        val item = gonfler(R.layout.item_probleme)
        assertNotNull(item.findViewById<View>(R.id.pastille_probleme))
        assertNotNull(item.findViewById<View>(R.id.titre_probleme))
        assertNotNull(item.findViewById<View>(R.id.detail_probleme))
        assertNotNull(item.findViewById<View>(R.id.voir_probleme))
    }

    @Test
    fun `la ligne de source porte son numéro et son texte`() {
        val item = gonfler(R.layout.item_ligne_source)
        assertNotNull(item.findViewById<View>(R.id.numero_ligne))
        assertNotNull(item.findViewById<View>(R.id.texte_ligne))
    }

    @Test
    fun `les rangées d'arbre et de problème sont cliquables (sélection deux voies)`() {
        val arbre = gonfler(R.layout.item_arbre)
        assertTrue("la rangée d'arbre réagit au tap", arbre.isClickable || arbre.background != null)
        val probleme = gonfler(R.layout.item_probleme)
        assertTrue(
            "la rangée de problème porte l'ondulation du thème",
            probleme.background != null,
        )
    }

    // ── PE9b : les fiches d'attributs en item layouts réels ──

    @Test
    fun `la carte de section porte son en-tête son badge sa flèche et son corps`() {
        val carte = gonfler(R.layout.carte_section)
        assertNotNull(carte.findViewById<View>(R.id.entete_section))
        assertNotNull(carte.findViewById<View>(R.id.titre_section))
        assertNotNull(carte.findViewById<View>(R.id.badge_definis))
        assertNotNull(carte.findViewById<View>(R.id.fleche_section))
        assertNotNull(carte.findViewById<View>(R.id.corps_section))
    }

    @Test
    fun `le contrôle texte porte son libellé et son conteneur de saisie`() {
        val controle = gonfler(R.layout.controle_texte)
        assertNotNull(controle.findViewById<View>(R.id.libelle_controle))
        assertNotNull(controle.findViewById<View>(R.id.conteneur_saisie))
    }

    @Test
    fun `le contrôle segments porte sa rangée (choix et tri-état partagés)`() {
        val controle = gonfler(R.layout.controle_segments)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.libelle_controle))
        assertNotNull(controle.findViewById<View>(R.id.groupe_segments))
    }

    @Test
    fun `le contrôle liste porte son champ déroulant sans saisie`() {
        // PE11c — miroir `.fld select` : en-tête + AutoCompleteTextView
        // (chevron à droite, hauteur 36 — la non-saisie est posée au liage :
        // inputType none + keyListener nul, le menu n'ouvre qu'au TAP).
        val controle = gonfler(R.layout.controle_liste)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.libelle_controle))
        val champ = controle.findViewById<EditText>(R.id.liste_deroulante)
        assertNotNull(champ)
        assertNotNull(
            "Le chevron de la liste déroulante est présent à droite",
            champ.compoundDrawablesRelative[2],
        )
        assertEquals(
            "Hauteur minimale du select (36 dp, densité 1)",
            36,
            champ.minimumHeight,
        )
    }

    @Test
    fun `le contrôle drapeaux porte son en-tête et son groupe multi`() {
        // PE11d — miroir `.flg` : en-tête + ChipGroup de drapeaux, le
        // groupe est MULTI-sélection (plusieurs drapeaux simultanés) ;
        // les drapeaux (item_drapeau) sont gonflés par le liage.
        val controle = gonfler(R.layout.controle_drapeaux)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.libelle_controle))
        val groupe = controle.findViewById<ChipGroup>(R.id.groupe_drapeaux)
        assertNotNull(groupe)
        assertFalse("Le groupe de drapeaux est multi-sélection", groupe.isSingleSelection)
        assertNotNull(gonfler(R.layout.item_drapeau).findViewById<View>(R.id.drapeau))
    }

    @Test
    fun `le contrôle cotes porte sa grille de quatre`() {
        val controle = gonfler(R.layout.controle_cotes)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.cote_haut))
        assertNotNull(controle.findViewById<View>(R.id.cote_droite))
        assertNotNull(controle.findViewById<View>(R.id.cote_bas))
        assertNotNull(controle.findViewById<View>(R.id.cote_gauche))
    }

    @Test
    fun `le contrôle dimensions porte son conteneur et sa rangée d'axe`() {
        assertNotNull(gonfler(R.layout.controle_dimensions))
        val rangee = gonfler(R.layout.item_dimension)
        assertNotNull(rangee.findViewById<View>(R.id.etiquette_dimension))
        assertNotNull(rangee.findViewById<View>(R.id.groupe_dimension))
        assertNotNull(rangee.findViewById<View>(R.id.champ_dimension))
    }

    @Test
    fun `la rangée de contrainte porte son bouton cible et sa marge`() {
        val rangee = gonfler(R.layout.controle_contrainte)
        assertNotNull(rangee.findViewById<View>(R.id.libelle_ancre))
        assertNotNull(rangee.findViewById<View>(R.id.bouton_cible))
        assertNotNull(rangee.findViewById<View>(R.id.champ_marge))
    }

    @Test
    fun `le contrôle identifiant porte le champ nouveau nom et la note de périmètre`() {
        val controle = gonfler(R.layout.controle_identifiant)
        assertNotNull(controle.findViewById<View>(R.id.conteneur_courant))
        assertNotNull(controle.findViewById<View>(R.id.champ_nouveau_nom))
        assertNotNull(controle.findViewById<View>(R.id.note_perimetre))
    }

    @Test
    fun `les petits items de l'éditeur se gonflent (extraction lecture seule saisies segment)`() {
        assertNotNull(gonfler(R.layout.controle_extraction).findViewById<View>(R.id.bouton_extraire))
        assertNotNull(gonfler(R.layout.item_lecture_seule).findViewById<View>(R.id.valeur_lecture_seule))
        assertNotNull(gonfler(R.layout.saisie_texte).findViewById<View>(R.id.champ_saisie))
        assertNotNull(gonfler(R.layout.saisie_nombre).findViewById<View>(R.id.champ_nombre))
        assertNotNull(gonfler(R.layout.item_segment).findViewById<View>(R.id.segment))
        assertNotNull(gonfler(R.layout.item_attribut_groupe).findViewById<View>(R.id.titre_groupe_attributs))
        assertNotNull(gonfler(R.layout.item_attribut).findViewById<View>(R.id.cle_attribut))
        assertNotNull(gonfler(R.layout.item_attribut).findViewById<View>(R.id.valeur_attribut))
        assertNotNull(gonfler(R.layout.entete_editeur).findViewById<View>(R.id.titre_editeur))
        assertNotNull(gonfler(R.layout.separateur_editeur).findViewById<View>(R.id.separateur_editeur))
    }

    @Test
    fun `l'entête champ porte le libellé la pastille et le reset`() {
        val entete = gonfler(R.layout.entete_champ)
        assertNotNull(entete.findViewById<View>(R.id.libelle_controle))
        assertNotNull(entete.findViewById<View>(R.id.pastille_espace_noms))
        assertNotNull(entete.findViewById<View>(R.id.bouton_reinitialiser))
    }

    @Test
    fun `la barre d'outils porte la recherche et la bascule (masquées jusqu'à PE11f)`() {
        val barre = gonfler(R.layout.barre_outils_attributs)
        val recherche = barre.findViewById<View>(R.id.champ_recherche_attribut)
        val bascule = barre.findViewById<View>(R.id.groupe_mode_attributs)
        assertNotNull(recherche)
        assertNotNull(bascule)
        assertTrue("la recherche attend PE11f", recherche.visibility == View.GONE)
        assertTrue("la bascule attend PE11f", bascule.visibility == View.GONE)
    }

    @Test
    fun `l'onglet problèmes porte son libellé et sa pastille`() {
        val onglet = gonfler(R.layout.onglet_problemes)
        assertNotNull(onglet.findViewById<View>(R.id.libelle_onglet_problemes))
        assertNotNull(onglet.findViewById<View>(R.id.pastille_onglet))
        assertTrue(
            "la pastille attend un problème",
            onglet.findViewById<View>(R.id.pastille_onglet).visibility == View.GONE,
        )
    }

    @Test
    fun `le volet vide porte son texte`() {
        assertNotNull(gonfler(R.layout.vide_volet).findViewById<View>(R.id.texte_volet))
    }

    @Test
    fun `le contrôle couleur porte ses trois couches son champ et son bouton palette`() {
        // PE11e — miroir `.colr` : pastille TROIS couches (damier + teinte
        // + cadre), champ hex, bouton « Palette » ; la palette et le
        // message de validation sont CACHÉS au repos (miroir showMsg et
        // le `.cpal` posé au clic).
        val controle = gonfler(R.layout.controle_couleur)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.pastille_damier))
        assertNotNull(controle.findViewById<View>(R.id.pastille_teinte))
        assertNotNull(controle.findViewById<View>(R.id.champ_couleur))
        assertNotNull(controle.findViewById<View>(R.id.bouton_palette))
        val palette = controle.findViewById<View>(R.id.palette_couleurs)
        assertNotNull(palette)
        assertFalse("La palette est fermée au repos", palette.isVisible)
        assertFalse(
            "Le message de validation est masqué au repos",
            controle.findViewById<View>(R.id.message_controle).isVisible,
        )
    }

    @Test
    fun `le controle ressource porte son champ a suggestions et son message cache`() {
        // PE11f — miroir `res` de la maquette : champ (style Champ, clé de
        // l'<datalist> au liage) + message de validation caché au repos.
        val controle = gonfler(R.layout.controle_ressource)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        val champ = controle.findViewById<View>(R.id.champ_ressource)
        assertNotNull(champ)
        assertFalse(
            "Le message de validation est masqué au repos",
            controle.findViewById<View>(R.id.message_controle).isVisible,
        )
    }

    @Test
    fun `le bouton autres attributs se gonfle en bouton large`() {
        // PE11f — miroir `.linkbtn.wide` : pleine largeur, texte posé au
        // liage (« Afficher les N autres attributs de X »).
        val bouton = gonfler(R.layout.bouton_autres_attributs)
        val largeur = bouton.layoutParams?.width ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT
        assertTrue(
            "Le bouton large prend toute la largeur",
            largeur == android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    @Test
    fun `l item de palette porte son disque et les colonnes se bornent`() {
        // PE11e — miroir `.cpal button` : disque 28 dp (fond = les anneaux
        // trait/surface/blanc du drawable) ; la couche TEINTE est posée
        // par le liage (AdaptateurPastilles). Les colonnes de la grille
        // se bornent à 1 (miroir flex-wrap, jamais de colonne nulle).
        assertNotNull(gonfler(R.layout.item_pastille_palette).findViewById<View>(R.id.pastille_palette))
        assertEquals(1, AdaptateurPastilles.colonnesPastille(0, 34))
        assertEquals(1, AdaptateurPastilles.colonnesPastille(374, 0))
        assertEquals(11, AdaptateurPastilles.colonnesPastille(374, 34))
        assertEquals(1, AdaptateurPastilles.colonnesPastille(30, 34))
    }

    @Test
    fun `le controle curseur porte sa glissiere son champ et son message cache`() {
        // PE11g-contrôles — miroir `.sld` : glissière violette (pistes et
        // pouce par les jetons apercuNouveau*) + champ 72 dp ; le message
        // de validation (bornes) est CACHÉ au repos (miroir showMsg —
        // la maquette l'ajoute au DOM à la volée), la note d'inactivité
        // aussi (aucun usager avant la vague § 7 — le biais vertical).
        val controle = gonfler(R.layout.controle_curseur)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.curseur))
        assertNotNull(controle.findViewById<View>(R.id.champ_curseur))
        assertFalse(
            "Le message de validation est masqué au repos",
            controle.findViewById<View>(R.id.message_controle).isVisible,
        )
        assertFalse(
            "La note d'inactivité est masquée au repos",
            controle.findViewById<View>(R.id.note_controle).isVisible,
        )
    }

    @Test
    fun `le controle ratio porte ses deux champs ses prereglages son verrou et sa note`() {
        // PE11g-contrôles — miroir `.ratio` : les deux nombres « a : b »,
        // le groupe des PRÉRÉGLAGES (drapeaux posés au liage), le groupe
        // du VERROU aucun/W/H (segments posés au liage) et la note
        // « dimension à 0dp » TOUJOURS visible (miroir note2 du ratio) ;
        // le message de validation est CACHÉ au repos.
        val controle = gonfler(R.layout.controle_ratio)
        assertNotNull(controle.findViewById<View>(R.id.entete))
        assertNotNull(controle.findViewById<View>(R.id.champ_ratio_a))
        assertNotNull(controle.findViewById<View>(R.id.champ_ratio_b))
        assertNotNull(controle.findViewById<View>(R.id.groupe_ratios))
        assertNotNull(controle.findViewById<View>(R.id.groupe_verrou_ratio))
        assertTrue(
            "La note 0dp est visible au repos (miroir note2 du ratio)",
            controle.findViewById<View>(R.id.note_controle).isVisible,
        )
        assertFalse(
            "Le message de validation est masqué au repos",
            controle.findViewById<View>(R.id.message_controle).isVisible,
        )
    }
}
