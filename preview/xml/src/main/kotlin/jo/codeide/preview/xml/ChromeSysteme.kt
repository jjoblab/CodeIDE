@file:Suppress("ReturnCount", "MagicNumber")

package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 *
 * PE11j : la 4e pièce du chrome est la BARRE DE NAVIGATION (48 dp,
 * miroir `.nbar` de la maquette : fond sombre fixe, trois glyphes) —
 * OPTIONNELLE ([RequeteRendu.barreNavigation], interrupteur
 * « Barre de navigation » du menu Calques).
 */
@Suppress("TooManyFunctions")
internal class ChromeSysteme(
    private val contexte: Context,
    private val themeResolver: ThemeResolver?,
    private val titreActivite: String?,
    /** Régime nuit du rendu (PE11j — fond de la barre `#000`, miroir `.screen.night --s-sys`). */
    private val nuit: Boolean = false,
    /** La barre de navigation de 48 dp est-elle rendue ? (PE11j — miroir `S.nav`, défaut ON). */
    private val barreNavigation: Boolean = true,
) {
    /**
     * Hauteur de la status bar en dp (24dp standard Android).
     * Peut être thémée via `statusBarHeight` mais la valeur par défaut
     * suffit pour la preview.
     */
    private val statusBarHauteurDp = 24

    /** Hauteur de l'action bar en dp (56dp standard Material). */
    private val actionBarHauteurDp = 56

    /** Hauteur de la barre de navigation en dp (48dp — PE11j, miroir `.nbar`). */
    private val barreNavigationHauteurDp = 48

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
        // v0.133.2 — TOUJOURS envelopper (même sans themeResolver).
        // Si themeResolver est null, on utilise les couleurs par défaut.
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

        // 4. Barre de navigation (PE11j — miroir `.nbar` : `margin-top:auto`
        // la plaque en BAS du chrome ; optionnelle).
        if (barreNavigation) {
            conteneur.addView(creerBarreNavigation(densite))
        }

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

    /**
     * Crée la vue BARRE DE NAVIGATION (48 dp — PE11j, miroir `.nbar` de
     * la maquette) : fond sombre FIXE (`--s-sys` : clair `#1C1B1F`, nuit
     * `#000`) et les TROIS glyphes `#CFCFD6` — retour (chevron), accueil
     * (cercle), applications (carré arrondi). Le module n'a pas de
     * ressources : les glyphes se DESSINENT (la vue est le CONTENU du
     * rendu, pas du chrome de l'app — patron du moteur).
     */
    private fun creerBarreNavigation(densite: Float): View {
        val hauteurPx = (barreNavigationHauteurDp * densite).toInt()
        return GlyphesBarreNavigation(contexte).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    hauteurPx,
                )
            setBackgroundColor(couleurBarreNavigation())
        }
    }

    /** Le fond de la barre : `--s-sys` de la maquette (sombre, clair et nuit). */
    private fun couleurBarreNavigation(): Int =
        if (nuit) COULEUR_BARRE_NAVIGATION_NUIT else COULEUR_BARRE_NAVIGATION_JOUR

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

        /** Fond de la barre de navigation en clair (maquette `--s-sys`). */
        private val COULEUR_BARRE_NAVIGATION_JOUR: Int = Color.parseColor("#1C1B1F")

        /** Fond de la barre de navigation en nuit (maquette `.screen.night --s-sys`). */
        private val COULEUR_BARRE_NAVIGATION_NUIT: Int = Color.parseColor("#000000")
    }
}

/**
 * Les TROIS glyphes de la barre de navigation (PE11j — miroir `.nbar i` de
 * la maquette : 14 × 14, trait 2, écart 56 ; retour = chevron, accueil =
 * cercle, applications = carré arrondi `#CFCFD6`). Dessinés en code : le
 * module `:preview:xml` n'a pas de ressources — cette vue est le CONTENU
 * du rendu (patron du moteur `VuePreviewXml`), pas du chrome de l'app.
 */
@Suppress("MagicNumber")
private class GlyphesBarreNavigation(
    contexte: Context,
) : View(contexte) {
    /** Le trait des glyphes (`#CFCFD6`, trait 2 — miroir `.nbar i`). */
    private val peinture =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CFCFD6")
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

    override fun onDraw(canevas: Canvas) {
        super.onDraw(canevas)
        val densite = resources.displayMetrics.density
        val centreY = height / 2f
        // Trois glyphes espacés de 56 + 14 (miroir `.nbar{gap:56px}`),
        // centrés horizontalement.
        val ecart = 70f * densite
        val demi = 7f * densite
        dessinerRetour(canevas, width / 2f - ecart, centreY, demi)
        dessinerAccueil(canevas, width / 2f, centreY, demi)
        dessinerApplications(canevas, width / 2f + ecart, centreY, demi)
    }

    /** Le glyphe RETOUR (« < » — miroir `i:nth-child(1)`, bordures bas + gauche pivotées). */
    private fun dessinerRetour(
        canevas: Canvas,
        centreX: Float,
        centreY: Float,
        demi: Float,
    ) {
        val chemin = Path()
        chemin.moveTo(centreX + demi, centreY - demi)
        chemin.lineTo(centreX - demi, centreY)
        chemin.lineTo(centreX + demi, centreY + demi)
        canevas.drawPath(chemin, peinture)
    }

    /** Le glyphe ACCUEIL (cercle — miroir `i:nth-child(2){border-radius:50%}`). */
    private fun dessinerAccueil(
        canevas: Canvas,
        centreX: Float,
        centreY: Float,
        demi: Float,
    ) {
        canevas.drawCircle(centreX, centreY, demi, peinture)
    }

    /** Le glyphe APPLICATIONS (carré arrondi rayon 3 — miroir `i:nth-child(3)`). */
    private fun dessinerApplications(
        canevas: Canvas,
        centreX: Float,
        centreY: Float,
        demi: Float,
    ) {
        val rayon = 3f * resources.displayMetrics.density
        canevas.drawRoundRect(centreX - demi, centreY - demi, centreX + demi, centreY + demi, rayon, rayon, peinture)
    }
}
