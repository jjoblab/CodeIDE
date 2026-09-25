package jo.codeide.core.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
 * Comme [applySystemBarsInsets], mais les barres système deviennent des
 * **marges** au lieu de paddings : la vue RACCOURCIT — rien d'elle ne se
 * peint derrière la barre de statut ni derrière la barre de navigation —
 * au lieu d'y étendre son fond.
 *
 * Destiné au tiroir de l'espace de travail (ADR 0052, maquette
 * EXPLORATEUR_V2 § 2 : le tiroir s'ouvre SOUS la barre de statut ;
 * v0.32.2 : il s'arrête AUSSI au-dessus de la barre de navigation —
 * retour d'appareil réel, « le tiroir chevauche la navbar »). Un tiroir
 * plein écran paddingé montrerait son entête sous les icônes de la barre
 * de statut et son rail sous les gestes de navigation. Nécessite un
 * parent dont les LayoutParams portent des marges (DrawerLayout gère les
 * marges verticales des tiroirs — vérifié au bytecode, v0.32.1). Ne pas
 * combiner avec [applySystemBarsInsets] sur la même vue (un seul
 * écouteur d'insets).
 *
 * @param top absorber la barre d'état (et l'encoche) en marge haute.
 * @param bottom absorber la barre de navigation en marge basse.
 */
public fun View.applySystemBarsInsetsMargins(
    top: Boolean = true,
    bottom: Boolean = true,
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { vue, insets ->
        val barres =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        vue.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (top) topMargin = barres.top
            if (bottom) bottomMargin = barres.bottom
        }
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

/**
 * Comme [applySystemBarsInsets], mais le padding bas suit **aussi** le
 * clavier virtuel : la vue remonte quand l'IME s'ouvre (et redescend à sa
 * fermeture, en gardant la marge de la barre de navigation).
 *
 * Destiné aux écrans à barre d'actions inférieure qui ne doit jamais
 * passer sous le clavier (assistant, wizard). Nécessite
 * `android:windowSoftInputMode="adjustResize"` pour recevoir les insets
 * IME sur les API antérieures à 30.
 *
 * @param top absorber la barre d'état (et l'encoche) en haut.
 * @param bottom absorber le maximum de (barre de navigation, clavier) en bas.
 */
public fun View.applySystemBarsAndImeInsets(
    top: Boolean,
    bottom: Boolean,
) {
    val initiaux = PaddingsInitiaux(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { vue, insets ->
        val barres =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        val clavier = insets.getInsets(WindowInsetsCompat.Type.ime())
        vue.updatePadding(
            top = initiaux.haut + if (top) barres.top else 0,
            bottom = initiaux.bas + if (bottom) maxOf(barres.bottom, clavier.bottom) else 0,
        )
        insets
    }
}
