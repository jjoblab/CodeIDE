package jo.codeide.core.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Paddings initiaux d'une vue, capturés avant l'application des insets. */
private data class PaddingsInitiaux(
    val gauche: Int,
    val haut: Int,
    val droite: Int,
    val bas: Int,
)

/**
 * Ajoute aux paddings existants de la vue ceux des barres système (état,
 * navigation) et de l'encoche, côté [top] et/ou [bottom].
 *
 * À appeler sur la racine de l'écran (ou la barre d'outils) une fois la
 * fenêtre passée en edge-to-edge (`enableEdgeToEdge()`).
 *
 * @param top absorber la barre d'état (et l'encoche) en haut.
 * @param bottom absorber la barre de navigation en bas.
 */
public fun View.applySystemBarsInsets(
    top: Boolean,
    bottom: Boolean,
) {
    val initiaux = PaddingsInitiaux(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { vue, insets ->
        val barres =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        vue.updatePadding(
            top = initiaux.haut + if (top) barres.top else 0,
            bottom = initiaux.bas + if (bottom) barres.bottom else 0,
        )
        insets
    }
}

/**
 * Ajoute au padding bas de la vue la hauteur du clavier virtuel (IME).
 *
 * À réserver au champ de saisie ou au conteneur qui doit rester visible ;
 * le reste de l'écran reste sous le clavier. Ne pas combiner avec
 * [applySystemBarsInsets] sur la même vue (un seul écouteur d'insets).
 */
public fun View.applyImeBottomInset() {
    val initiaux = PaddingsInitiaux(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { vue, insets ->
        val clavier = insets.getInsets(WindowInsetsCompat.Type.ime())
        vue.updatePadding(bottom = initiaux.bas + clavier.bottom)
        insets
    }
}
