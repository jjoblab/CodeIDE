@file:Suppress("ReturnCount", "MagicNumber")

package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import jo.layoutlib.themes.ThemeResolver

/**
 * Enveloppement du contenu preview dans un chrome système (status bar +
 * action bar) — G5 / Phase 3 (v0.133.0).
 *
 * **Problème résolu** : la preview actuelle dessine le layout brut sur
 * un fond blanc — pas de status bar thémée, pas d'action bar avec le
 * titre de l'activité, pas de window background. La preview ne ressemble
 * pas à un screenshot d'activité.
 *
 * **Solution** : avant le `layoutEngine.render`, on enveloppe la racine
 * inflatée dans un `LinearLayout` vertical contenant :
 * 1. **Status bar** (24dp) — couleur `statusBarColor` ou `colorPrimaryDark`
 * 2. **Action bar** (56dp) — couleur `colorPrimary`, avec le titre de
 *    l'activité en blanc
 * 3. **Contenu** — le layout inflaté, en `match_parent`
 *
 * Les couleurs viennent du `ThemeResolver` (résolution `?attr/colorPrimary`
 * etc. contre le thème activité). Si le thème n'est pas configuré ou
 * l'attribut n'est pas trouvé, on utilise des valeurs par défaut (Material
 * teal/indigo) — la preview reste lisible.
 *
 * **windowActionBar** : si le thème déclare `windowActionBar=false` ou
 * `windowNoTitle=true`, on saute l'action bar. Le chrome se réduit
 * alors à la status bar seule.
 *
 * **windowBackground** : si le thème déclare un `windowBackground`, on
 * l'applique comme background du conteneur de contenu.
 */
@Suppress("TooManyFunctions")
internal class ChromeSysteme(
    private val contexte: Context,
    private val themeResolver: ThemeResolver?,
    private val titreActivite: String?,
) {
    /**
     * Hauteur de la status bar en dp (24dp standard Android).
     * Peut être thémée via `statusBarHeight` mais la valeur par défaut
     * suffit pour la preview.
     */
    private val statusBarHauteurDp = 24

    /** Hauteur de l'action bar en dp (56dp standard Material). */
    private val actionBarHauteurDp = 56

    /**
     * Enveloppe [contenu] dans un conteneur chrome si le thème le permet.
     *
     * @param contenu la racine inflatée du layout utilisateur
     * @param densite densité d'écran courante (dp → px)
     * @return le conteneur chrome (avec status bar + action bar + contenu),
     *         ou [contenu] tel quel si pas de chrome (thème sans action bar,
     *         ou ThemeResolver non branché)
     */
    fun envelopper(
        contenu: View,
        densite: Float,
    ): View {
        if (themeResolver == null) return contenu

        // windowNoTitle / windowActionBar : si le thème dit pas d'action
        // bar, on enveloppe juste avec la status bar.
        val avecActionBar = !attrBool("windowNoTitle") && attrBool("windowActionBar", defaut = true)
        val avecStatusBar = !attrBool("windowFullscreen")

        // Si ni status bar ni action bar (fullscreen), pas de chrome.
        if (!avecStatusBar && !avecActionBar) return contenu

        // Créer le conteneur vertical.
        val conteneur =
            LinearLayout(contexte).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }

        // 1. Status bar.
        if (avecStatusBar) {
            conteneur.addView(creerStatusBar(densite))
        }

        // 2. Action bar.
        if (avecActionBar) {
            conteneur.addView(creerActionBar(densite))
        }

        // 3. Contenu — wrapper dans un FrameLayout pour appliquer le
        // windowBackground et donner une taille match_parent.
        val conteneurContenu =
            FrameLayout(contexte).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f, // weight=1 → prend l'espace restant
                    )
                appliquerWindowBackground(this)
            }
        // Le contenu original garde ses layoutParams mais est ajouté au
        // FrameLayout.
        conteneurContenu.addView(contenu)
        conteneur.addView(conteneurContenu)

        return conteneur
    }

    /** Crée la vue status bar (24dp, couleur thémée). */
    private fun creerStatusBar(densite: Float): View {
        val hauteurPx = (statusBarHauteurDp * densite).toInt()
        return View(contexte).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    hauteurPx,
                )
            setBackgroundColor(couleurStatusBar())
        }
    }

    /** Crée la vue action bar (56dp, colorPrimary, titre blanc). */
    private fun creerActionBar(densite: Float): View {
        val hauteurPx = (actionBarHauteurDp * densite).toInt()
        val bar =
            LinearLayout(contexte).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        hauteurPx,
                    )
                setBackgroundColor(couleurPrimary())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * densite).toInt(),
                    0,
                    (16 * densite).toInt(),
                    0,
                )
            }
        // Titre de l'activité.
        if (!titreActivite.isNullOrEmpty()) {
            val titre =
                TextView(contexte).apply {
                    text = titreActivite
                    setTextColor(Color.WHITE)
                    textSize = 18f // sp
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            bar.addView(titre)
        }
        return bar
    }

    /** Couleur de la status bar — statusBarColor > colorPrimaryDark > défaut. */
    private fun couleurStatusBar(): Int {
        themeResolver?.getColorAttr("statusBarColor")?.let { return it }
        themeResolver?.getColorAttr("colorPrimaryDark")?.let { return it }
        return COULEUR_STATUS_BAR_DEFAUT
    }

    /** Couleur de l'action bar — colorPrimary > défaut. */
    private fun couleurPrimary(): Int {
        themeResolver?.getColorAttr("colorPrimary")?.let { return it }
        return COULEUR_PRIMARY_DEFAUT
    }

    /** Applique le windowBackground sur [vue] si résolvable. */
    private fun appliquerWindowBackground(vue: FrameLayout) {
        // windowBackground peut être une couleur (@color/) ou un drawable.
        // On tente d'abord comme couleur, puis comme drawable via resolveAttr.
        val bg = themeResolver?.resolveAttr("?attr/windowBackground")
        if (bg is Int) {
            vue.setBackgroundColor(bg)
            return
        }
        // Pas de windowBackground thémé — fond blanc par défaut.
        vue.setBackgroundColor(Color.WHITE)
    }

    /** Récupère un attribut booléen depuis le thème. */
    private fun attrBool(
        nom: String,
        defaut: Boolean = false,
    ): Boolean {
        val valeur = themeResolver?.getStyleAttribute(null, nom) ?: return defaut
        return "true".equals(valeur, ignoreCase = true)
    }

    private companion object {
        /** Couleur status bar par défaut (Material teal foncé). */
        private val COULEUR_STATUS_BAR_DEFAUT: Int = Color.parseColor("#004D40")

        /** Couleur action bar par défaut (Material teal). */
        private val COULEUR_PRIMARY_DEFAUT: Int = Color.parseColor("#009688")
    }
}
