package jo.codeide.feature.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import jo.codeide.feature.settings.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Régression des écrans des Paramètres (v0.35.4, ADR 0064) : les rangées
 * des sections dédiées (ADR 0059) étaient posées en ENFANTS DIRECTS de la
 * MaterialCardView — un FrameLayout — donc toutes superposées au coin
 * haut-gauche (« les vues sont empilées », retour utilisateur v0.35.3).
 *
 * Chaque carte doit porter un UNIQUE LinearLayout vertical qui empile ses
 * rangées. Le test gonfle le layout sous le thème réel, le mesure et le
 * pose à taille d'écran, puis vérifie qu'aucune rangée visible ne
 * chevauche celle du dessus — la garantie exacte contredite par le bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SectionsParametresLayoutTest {
    private val sections =
        listOf(
            "apparence" to R.layout.fragment_settings_apparence,
            "à propos" to R.layout.fragment_settings_apropos,
            "avancé" to R.layout.fragment_settings_avance,
            "éditeur" to R.layout.fragment_settings_editeur,
            "notifications" to R.layout.fragment_settings_notifications,
            "projets" to R.layout.fragment_settings_projets,
            "terminal" to R.layout.fragment_settings_terminal,
        )

    @Test
    fun `les cartes du maitre portent un unique conteneur vertical`() {
        val contexte = contexteTheme()
        val racine = gonflerEtPoser(contexte, R.layout.fragment_settings)
        val cartes = cartesDe(racine)

        assertEquals("le maître affiche quatre cartes de domaine", 4, cartes.size)
        for (carte in cartes) {
            assertConteneurUnique("maître", carte)
        }
    }

    @Test
    fun `les cartes de section portent un unique conteneur vertical`() {
        for ((section, layout) in sections) {
            val contexte = contexteTheme()
            val racine = gonflerEtPoser(contexte, layout)
            val cartes = cartesDe(racine)

            assertTrue("$section : au moins une carte attendue", cartes.isNotEmpty())
            for (carte in cartes) {
                assertConteneurUnique(section, carte)
            }
        }
    }

    @Test
    fun `aucune rangee visible ne chevauche celle du dessus`() {
        for ((section, layout) in sections) {
            val contexte = contexteTheme()
            val racine = gonflerEtPoser(contexte, layout)
            for (carte in cartesDe(racine)) {
                assertRangeesNonEmpilees(contexte, section, conteneurDe(carte))
            }
        }
    }

    /** Contexte de gonflage sous le thème réel de l'application. */
    private fun contexteTheme(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return ContextThemeWrapper(base, R.style.Theme_CodeIDE)
    }

    /** Gonfle le layout, le mesure et le pose à taille d'écran réelle. */
    private fun gonflerEtPoser(
        contexte: Context,
        layout: Int,
    ): View {
        val racine = LayoutInflater.from(contexte).inflate(layout, null, false)
        val largeur = View.MeasureSpec.makeMeasureSpec(LARGEUR_ECRAN, View.MeasureSpec.EXACTLY)
        val hauteur = View.MeasureSpec.makeMeasureSpec(HAUTEUR_ECRAN, View.MeasureSpec.EXACTLY)
        racine.measure(largeur, hauteur)
        racine.layout(0, 0, LARGEUR_ECRAN, HAUTEUR_ECRAN)
        return racine
    }

    /** Toutes les MaterialCardView de l'arbre, dans l'ordre du document. */
    private fun cartesDe(racine: View): List<MaterialCardView> {
        val cartes = mutableListOf<MaterialCardView>()

        fun parcourir(vue: View) {
            if (vue is MaterialCardView) cartes += vue
            if (vue is ViewGroup) {
                for (enfant in 0 until vue.childCount) parcourir(vue.getChildAt(enfant))
            }
        }
        parcourir(racine)
        return cartes
    }

    /**
     * La carte (FrameLayout) ne doit porter qu'un conteneur vertical : des
     * rangées sœurs directement sous la carte se superposent au lieu de
     * s'empiler — le bug d'affichage des sections.
     */
    private fun assertConteneurUnique(
        section: String,
        carte: MaterialCardView,
    ) {
        assertEquals(
            "$section : la carte doit porter un unique enfant, pas ${carte.childCount}",
            1,
            carte.childCount,
        )
        val conteneur = carte.getChildAt(0)
        assertTrue("$section : l'enfant de carte doit être un LinearLayout", conteneur is LinearLayout)
        assertEquals(
            "$section : le conteneur de carte doit empiler verticalement",
            LinearLayout.VERTICAL,
            (conteneur as LinearLayout).orientation,
        )
    }

    /**
     * Conteneur des rangées : l'unique LinearLayout vertical de la carte
     * (structure corrigée) — ou la carte elle-même si la structure est
     * retombée dans l'ancien défaut, pour que le chevauchement soit
     * attribué à la bonne cause.
     */
    private fun conteneurDe(carte: MaterialCardView): ViewGroup =
        if (carte.childCount == 1 && carte.getChildAt(0) is LinearLayout) {
            carte.getChildAt(0) as LinearLayout
        } else {
            carte
        }

    /** Les rangées visibles du conteneur doivent descendre sans se recouvrir. */
    private fun assertRangeesNonEmpilees(
        contexte: Context,
        section: String,
        conteneur: ViewGroup,
    ) {
        val visibles =
            (0 until conteneur.childCount)
                .map { conteneur.getChildAt(it) }
                .filter { it.visibility != View.GONE }
        for (i in 1 until visibles.size) {
            val auDessus = visibles[i - 1]
            val courante = visibles[i]
            assertTrue(
                "$section : « ${nom(contexte, courante)} » chevauche « ${nom(contexte, auDessus)} » " +
                    "(bottom ${auDessus.bottom} > top ${courante.top}) — contenu de carte empilé",
                auDessus.bottom <= courante.top + TOLERANCE_PX,
            )
        }
    }

    /** Nom lisible d'une vue pour les messages d'échec. */
    private fun nom(
        contexte: Context,
        vue: View,
    ): String =
        if (vue.id != View.NO_ID) {
            try {
                contexte.resources.getResourceEntryName(vue.id)
            } catch (_: android.content.res.Resources.NotFoundException) {
                vue.javaClass.simpleName
            }
        } else {
            vue.javaClass.simpleName
        }

    private companion object {
        /** Écran plein format typique, en pixels Robolectric (mdpi). */
        const val LARGEUR_ECRAN = 1080
        const val HAUTEUR_ECRAN = 2340
        const val TOLERANCE_PX = 1
    }
}
