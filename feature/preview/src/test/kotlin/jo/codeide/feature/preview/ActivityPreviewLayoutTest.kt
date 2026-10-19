package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gonflage du layout de l'écran d'aperçu sous Robolectric (PE6-0 —
 * miroir [jo.codeide.feature.editor.ActivityEditorLayoutTest]) :
 * `activity_preview.xml` se gonfle sous le thème de l'aperçu, la vue de
 * rendu :preview:xml, la BARRE DU HAUT (PE11i, ADR 0134 D1 : retour,
 * bloc titre, pastille de mode, bouton plus — plus de toolbar), les
 * puces de réglages, le BANDEAU et le TOAST en vues (D6) se trouvent par
 * identifiant. PE6d : la racine est un CoordinatorLayout (la feuille
 * inférieure en BottomSheet) et le layout du fragment de feuille se
 * gonfle aussi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityPreviewLayoutTest {
    /** Gonfle le layout sous le thème réel de l'aperçu. */
    private fun gonfler(): ViewGroup {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, R.style.Theme_CodeIDE_Apercu)
        return LayoutInflater.from(contexte).inflate(R.layout.activity_preview, null) as ViewGroup
    }

    @Test
    fun `le layout de l'aperçu se gonfle sans exception`() {
        val racine = gonfler()
        // PE11i (D1) : la barre du haut est un LinearLayout de 52 dp —
        // bouton retour, bloc titre, pastille de mode, bouton plus.
        assertNotNull(racine.findViewById<View>(R.id.entete_preview))
        assertNotNull(racine.findViewById<View>(R.id.appbar_preview))
        assertNotNull(racine.findViewById<View>(R.id.bouton_retour))
        assertNotNull(racine.findViewById<View>(R.id.titre_apercu))
        assertNotNull(racine.findViewById<View>(R.id.sous_titre_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_plus))
        assertNotNull(racine.findViewById<View>(R.id.vue_preview))
        assertNotNull(racine.findViewById<View>(R.id.bouton_appareil_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_nuit_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_langue_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_calques_apercu))
        assertNotNull(racine.findViewById<View>(R.id.pastille_etat))
        assertNotNull(racine.findViewById<View>(R.id.pastille_point))
        assertNotNull(racine.findViewById<View>(R.id.pastille_etat_texte))
        assertNotNull(racine.findViewById<View>(R.id.conteneur_rendu))
        // PE11i (D6) : le bandeau d'erreur et le toast sont des VUES.
        assertNotNull(racine.findViewById<View>(R.id.banniere_rendu))
        assertNotNull(racine.findViewById<View>(R.id.banniere_message))
        assertNotNull(racine.findViewById<View>(R.id.banniere_ligne))
        assertNotNull(racine.findViewById<View>(R.id.banniere_details))
        assertNotNull(racine.findViewById<View>(R.id.toast_apercu))
        // PE6d : le conteneur de la feuille inférieure (BottomSheet).
        assertNotNull(racine.findViewById<View>(R.id.conteneur_feuille))
    }

    @Test
    fun `le HUD de zoom se gonfle avec ses quatre boutons (PE6b)`() {
        val racine = gonfler()
        assertNotNull(racine.findViewById<View>(R.id.hud_zoom))
        assertNotNull(racine.findViewById<View>(R.id.bouton_zoom_avant))
        assertNotNull(racine.findViewById<View>(R.id.bouton_zoom_pct))
        assertNotNull(racine.findViewById<View>(R.id.bouton_zoom_ajuster))
        assertNotNull(racine.findViewById<View>(R.id.bouton_zoom_arriere))
        // Les puces à menu portent le caret (miroir `.caret` de la maquette).
        val appareil =
            racine.findViewById<Chip>(
                R.id.bouton_appareil_apercu,
            )
        assertTrue("la puce Appareil annonce le menu déroulant", appareil.text.contains("▾"))
    }

    @Test
    fun `la pastille de mode Design Plan Les deux se gonfle (PE10de, PE11i D2)`() {
        val racine = gonfler()
        val groupe =
            racine.findViewById<ChipGroup>(
                R.id.groupe_mode_apercu,
            )
        assertNotNull(groupe)
        assertNotNull(racine.findViewById<View>(R.id.mode_design))
        assertNotNull(racine.findViewById<View>(R.id.mode_plan))
        assertNotNull(racine.findViewById<View>(R.id.mode_les_deux))
        // Design est le mode par défaut (miroir `S.mode = 'design'`) —
        // PE11i (D2) : ChipGroup à sélection unique obligatoire.
        assertEquals(R.id.mode_design, groupe.checkedChipId)
    }

    @Test
    fun `la vue de rendu est le panneau du moteur (pas un substitut)`() {
        val racine = gonfler()
        val vue = racine.findViewById<jo.codeide.preview.xml.VuePreviewXml>(R.id.vue_preview)
        assertTrue(vue is jo.codeide.preview.xml.VuePreviewXml)
        // La description porte la chaîne du MOTEUR (:preview:xml — fr ou
        // en selon la locale de test, Robolectric tourne en en-US) : on
        // épingle la PRÉSENCE, pas la langue.
        assertNotNull("le panneau porte la description du moteur", vue.contentDescription)
    }

    @Test
    fun `la bulle de palette porte son fond et son halo (PE11i D4)`() {
        val racine = gonfler()
        val bulle = racine.findViewById<View>(R.id.bulle_palette)
        assertNotNull(bulle)
        assertNotNull("la bulle porte le layer-list violet + halo", bulle.background)
    }

    @Test
    fun `le layout de la feuille se gonfle avec son conteneur de volets (PE6d + PE9a)`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, R.style.Theme_CodeIDE_Apercu)
        val feuille = LayoutInflater.from(contexte).inflate(R.layout.fragment_feuille_apercu, null)
        assertNotNull(feuille.findViewById<View>(R.id.racine_feuille))
        assertNotNull(feuille.findViewById<View>(R.id.grab_feuille))
        assertNotNull(feuille.findViewById<View>(R.id.onglets_feuille))
        // PE9a : UN conteneur porte les quatre fragments de volet.
        assertNotNull(feuille.findViewById<View>(R.id.conteneur_volets))
    }
}
