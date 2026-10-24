package jo.codeide.feature.editor

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régressions v0.183.0 (retour propriétaire : le BottomSheet hérité
 * d'une tentative antérieure masquait l'éditeur replié et torpillait la
 * frappe du panneau) — le [GestionnaireInsetsEditeur] sur le VRAI
 * layout gonflé (même patron que [PanneauToolingControllerTest]) :
 *
 * - **la réserve sous l'éditeur est posée DÈS le branchement** : le
 *   premier correctif racine — l'ancien gestionnaire posait son
 *   listener d'insets sur la racine AVANT `observerClavier`, qui le
 *   remplaçait silencieusement ; plus AUCUN inset de navigation
 *   n'arrivait et la réserve n'était appliquée qu'aux changements de
 *   peek — au premier rendu, l'éditeur naissait SOUS le panneau
 *   replié (« en collapse il masque une partie de la section
 *   éditeur ») ;
 * - **les insets de navigation nourrissent la réserve** via
 *   [GestionnaireInsetsEditeur.surInsetsBarres] (l'ingestion du
 *   listener unique de l'activité) ;
 * - **le clavier du PANNEAU** (saisie console, filtre logcat) remonte
 *   son contenu au-dessus de l'IME via
 *   [GestionnaireInsetsEditeur.surClavierPanneau] — l'ancien code
 *   masquait le panneau DÈS le premier inset IME : la frappe se
 *   faisait à l'aveugle ;
 * - **le clavier de l'éditeur** ne touche PAS le padding IME du
 *   panneau (la remontée n'appartient qu'aux saisies du panneau).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class GestionnaireInsetsPanneauClavierTest {
    /** Inset de barre de navigation typique (48 dp ≈ 144 px xxhdpi). */
    private val insetNav = 144

    /** Inset IME typique (260 dp ≈ 780 px xxhdpi). */
    private val insetIme = 780

    /** Construit le gestionnaire sur le VRAI layout gonflé (le peek du
     *  sheet est lu depuis le XML, comme en production). */
    private fun construire(clavierVisible: () -> Boolean = { false }, hauteurIme: () -> Int = { 0 }): Trio {
        val pilote = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activite = pilote.get()
        activite.setTheme(RUi.style.Theme_CodeIDE)
        pilote.setup()
        val liaison =
            ActivityEditorBinding.inflate(LayoutInflater.from(activite))
        val comportement = BottomSheetBehavior.from(liaison.panneauInferieur)
        val gestionnaire =
            GestionnaireInsetsEditeur(
                liaison = liaison,
                comportementPanneau = comportement,
                clavierVisible = clavierVisible,
                hauteurIme = hauteurIme,
            )
        return Trio(liaison, comportement, gestionnaire)
    }

    /** Liaison + comportement + gestionnaire pour les assertions. */
    private data class Trio(
        val liaison: ActivityEditorBinding,
        val comportement: BottomSheetBehavior<*>,
        val gestionnaire: GestionnaireInsetsEditeur,
    )

    /** Insets d'une barre de navigation basse [insetBas] px. */
    private fun insetsBarres(insetBas: Int): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, insetBas))
        .build()

    @Test
    fun `la reserve sous l editeur est posee des le branchement — jamais sous le panneau replie`() {
        val (liaison, comportement, gestionnaire) = construire()

        gestionnaire.brancher()

        assertEquals(
            "régression v0.183.0 : au PREMIER rendu, sans aucun inset " +
                "encore distribué ni changement de peek, la colonne " +
                "centrale doit déjà réserver la hauteur du peek",
            comportement.peekHeight,
            liaison.zoneCentrale.paddingBottom,
        )
    }

    @Test
    fun `les insets de navigation nourrissent la reserve et le padding du panneau`() {
        val (liaison, comportement, gestionnaire) = construire()
        gestionnaire.brancher()

        gestionnaire.surInsetsBarres(insetsBarres(insetNav))

        assertEquals(
            "l'éditeur réserve peek + inset de navigation",
            comportement.peekHeight + insetNav,
            liaison.zoneCentrale.paddingBottom,
        )
        assertEquals(
            "le contenu du panneau reste au-dessus de la barre",
            insetNav,
            liaison.panneauInferieur.paddingBottom,
        )
    }

    @Test
    fun `le clavier du panneau remonte son contenu au dessus de l IME`() {
        val (liaison, comportement, gestionnaire) = construire()
        gestionnaire.brancher()
        gestionnaire.surInsetsBarres(insetsBarres(insetNav))

        gestionnaire.surClavierPanneau(insetIme)

        assertEquals(
            "le contenu du panneau remonte au-dessus de la barre ET du clavier",
            insetNav + insetIme,
            liaison.panneauInferieur.paddingBottom,
        )
        assertEquals(
            "la colonne de l'éditeur n'est PAS concernée par le clavier du panneau " +
                "(dans ce montage le clavier n'est pas « visible » pour la réserve)",
            comportement.peekHeight + insetNav,
            liaison.zoneCentrale.paddingBottom,
        )
    }

    @Test
    fun `surClavierPanneau zero libere le padding IME du panneau`() {
        val (liaison, _, gestionnaire) = construire()
        gestionnaire.brancher()
        gestionnaire.surInsetsBarres(insetsBarres(insetNav))
        gestionnaire.surClavierPanneau(insetIme)

        gestionnaire.surClavierPanneau(0)

        assertEquals(
            "clavier fermé : le panneau ne retient plus de padding IME",
            insetNav,
            liaison.panneauInferieur.paddingBottom,
        )
    }

    @Test
    fun `le clavier de l editeur ne pose JAMAIS le padding IME du panneau`() {
        val (liaison, _, gestionnaire) = construire(
            clavierVisible = { true },
            hauteurIme = { insetIme },
        )
        gestionnaire.brancher()
        gestionnaire.surInsetsBarres(insetsBarres(insetNav))

        assertEquals(
            "clavier de l'ÉDITEUR : la colonne centrale remonte de l'IME",
            insetIme,
            liaison.zoneCentrale.paddingBottom,
        )
        assertEquals(
            "mais le panneau ne reçoit PAS de padding IME (masqué, il " +
                "n'a rien à remonter — seule la saisie du PANNEAU la pose)",
            insetNav,
            liaison.panneauInferieur.paddingBottom,
        )
    }
}
