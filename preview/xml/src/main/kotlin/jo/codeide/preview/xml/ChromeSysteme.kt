@file:Suppress("ReturnCount", "MagicNumber")

package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
 * l'attribut n'est pas trouvé, on utilise les valeurs par défaut de la
 * MAQUETTE (v0.166.5 : `--s-dark`/`--s-pri` indigo, plus le teal Material) —
 * la preview reste lisible.
 *
 * **windowActionBar** : si le thème déclare `windowActionBar=false` ou
 * `windowNoTitle=true`, on saute l'action bar. Le chrome se réduit
 * alors à la status bar seule.
 *
 * v0.166.5 (retour propriétaire du 2026-10-22 : « le statutbar n'est pas
 * similaire à celui de la maquette (n'a pas les icônes) et le appbar est
 * peut-être trop gros ») :
 * - la STATUS BAR porte ENFIN son contenu maquette (`.sbar` : l'heure
 *   « 9:41 » à gauche, « 5G ▮▮▮ 100% » à droite, sans-serif-medium 11,
 *   padding 12 — [GlyphesBarreStatut], miroir de la ligne 1080 de la
 *   maquette) ; les couleurs PAR DÉFAUT prennent les jetons de la planche
 *   (`--s-dark` clair `#2B2F7A` / nuit `#0C1030`, `--s-pri` clair
 *   `#43559E` / nuit `#BAC4FF`) ;
 * - correctif C1 (ADR 0135) : [attrBool] lisait `getStyleAttribute(null, …)`
 *   qui rend TOUJOURS null — `windowNoTitle`/`windowActionBar` n'étaient
 *   jamais lus et l'action bar incrustait TOUT document (y compris ceux
 *   qui portent leur propre toolbar) : d'où l'appbar « trop gros ». La
 *   lecture passe par `?android:attr/…` ([ThemeResolver.resolveAttr]) —
 *   le fil « noBar » de la maquette rend enfin sans action bar ;
 * - le titre de l'action bar suit la maquette (`.abar` : Roboto 500, 20) et
 *   sa couleur devient LISIBLE par luminance du fond résolu (nuit : le
 *   primaire clair reçoit le sombre `#0C2264` — miroir `--s-onpri`).
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

    /**
     * Crée la vue status bar (24dp, couleur thémée) — v0.166.5 : la barre
     * porte son CONTENU maquette ([GlyphesBarreStatut] : heure à gauche,
     * réseau + batterie à droite), lisible par luminance du fond.
     */
    private fun creerStatusBar(densite: Float): View {
        val hauteurPx = (statusBarHauteurDp * densite).toInt()
        val fond = couleurStatusBar()
        return GlyphesBarreStatut(contexte).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    hauteurPx,
                )
            setBackgroundColor(fond)
            couleurGlyphes = LisibiliteChrome.couleurContenu(fond)
        }
    }

    /**
     * Crée la vue action bar (56dp — miroir `.abar` ; colorPrimary, titre
     * 20 sans-serif-medium lisible par luminance, padding 16).
     */
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
        // Titre de l'activité — v0.166.5 : miroir `.abar` (Roboto 500, 20)
        // et couleur LISIBLE par luminance du fond (nuit : primaire clair
        // → texte sombre, miroir `--s-onpri` #0C2264).
        if (!titreActivite.isNullOrEmpty()) {
            val titre =
                TextView(contexte).apply {
                    text = titreActivite
                    setTextColor(LisibiliteChrome.couleurContenu(couleurPrimary()))
                    textSize = 20f // sp
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

    /**
     * Couleur de la status bar — statusBarColor > colorPrimaryDark > défaut
     * de la MAQUETTE (`--s-dark` : clair `#2B2F7A`, nuit `#0C1030`).
     */
    private fun couleurStatusBar(): Int {
        themeResolver?.getColorAttr("statusBarColor")?.let { return it }
        themeResolver?.getColorAttr("colorPrimaryDark")?.let { return it }
        return if (nuit) COULEUR_STATUS_BAR_NUIT_DEFAUT else COULEUR_STATUS_BAR_DEFAUT
    }

    /** Couleur de l'action bar — colorPrimary > défaut maquette (`--s-pri`). */
    private fun couleurPrimary(): Int {
        themeResolver?.getColorAttr("colorPrimary")?.let { return it }
        return if (nuit) COULEUR_PRIMARY_NUIT_DEFAUT else COULEUR_PRIMARY_DEFAUT
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

    /**
     * Récupère un attribut BOOLÉEN de fenêtre du thème.
     *
     * v0.166.5 — correctif C1 (ADR 0135) : l'ancienne lecture
     * `getStyleAttribute(null, …)` rendait TOUJOURS null (l'API exige un nom
     * de style) — `windowNoTitle`/`windowActionBar`/`windowFullscreen`
     * n'étaient jamais lus et l'action bar incrustait tout document. La
     * résolution passe par `?android:attr/…` : les attributs fenêtre vivent
     * dans le namespace android (miroir d'un vrai styles.xml de projet) ; la
     * valeur booléenne arrive en chaîne « true »/« false ».
     */
    private fun attrBool(
        nom: String,
        defaut: Boolean = false,
    ): Boolean {
        val valeur = themeResolver?.resolveAttr("?android:attr/$nom") as? String ?: return defaut
        return "true".equals(valeur, ignoreCase = true)
    }

    private companion object {
        /** Fond status bar par défaut — `--s-dark` clair de la maquette. */
        private val COULEUR_STATUS_BAR_DEFAUT: Int = Color.parseColor("#2B2F7A")

        /** Fond status bar par défaut — `--s-dark` nuit de la maquette. */
        private val COULEUR_STATUS_BAR_NUIT_DEFAUT: Int = Color.parseColor("#0C1030")

        /** Fond action bar par défaut — `--s-pri` clair de la maquette. */
        private val COULEUR_PRIMARY_DEFAUT: Int = Color.parseColor("#43559E")

        /** Fond action bar par défaut — `--s-pri` nuit de la maquette. */
        private val COULEUR_PRIMARY_NUIT_DEFAUT: Int = Color.parseColor("#BAC4FF")

        /** Fond de la barre de navigation en clair (maquette `--s-sys`). */
        private val COULEUR_BARRE_NAVIGATION_JOUR: Int = Color.parseColor("#1C1B1F")

        /** Fond de la barre de navigation en nuit (maquette `.screen.night --s-sys`). */
        private val COULEUR_BARRE_NAVIGATION_NUIT: Int = Color.parseColor("#000000")
    }
}

/**
 * La LISIBILITÉ du contenu du chrome (v0.166.5) : la couleur du contenu
 * suit la LUMINANCE du fond résolu — blanc sur fond sombre, sombre sur
 * fond clair (nuit : le primaire clair `#BAC4FF` reçoit le sombre
 * `#0C2264`, miroir `--s-onpri` de la maquette ; jour : le blanc sur
 * `#43559E`). Fonctions PURES (règle 1) — testées à part
 * ([ChromeSystemeTest]).
 */
internal object LisibiliteChrome {
    /** Le contenu CLAIR (blanc — maquette `.sbar`/`.abar` jour). */
    private val CONTENU_CLAIR: Int = Color.WHITE

    /** Le contenu SOMBRE (miroir `--s-onpri` nuit `#0C2264`). */
    private val CONTENU_SOMBRE: Int = Color.parseColor("#0C2264")

    /** Le seuil de bascule (fond moitié claire → contenu sombre). */
    private const val SEUIL_LUMINANCE: Double = 0.5

    /** La couleur du contenu pour un fond donné (blanc ↔ sombre). */
    internal fun couleurContenu(fond: Int): Int =
        if (luminanceRelative(fond) >
            SEUIL_LUMINANCE
        ) {
            CONTENU_SOMBRE
        } else {
            CONTENU_CLAIR
        }

    /**
     * La luminance perçue relative d'une couleur (0..1) — Rec. 709 comme
     * `Color.luminance` de la plateforme, en calcul PUR (testable JVM).
     */
    internal fun luminanceRelative(couleur: Int): Double {
        val rouge = Color.red(couleur) / 255.0
        val vert = Color.green(couleur) / 255.0
        val bleu = Color.blue(couleur) / 255.0
        return 0.2126 * rouge + 0.7152 * vert + 0.0722 * bleu
    }
}

/**
 * Le CONTENU de la status bar (v0.166.5 — miroir `.sbar` de la maquette,
 * ligne 1080 : `el('span', '', '9:41')` à gauche et
 * `el('span', '', '5G  ▮▮▮ 100%')` à droite, `justify-content:
 * space-between; padding: 0 12px; font: 500 11px Roboto`). Dessiné en
 * code comme [GlyphesBarreNavigation] : le module `:preview:xml` n'a pas
 * de ressources — cette vue est le CONTENU du rendu, pas du chrome de
 * l'app. La couleur des glyphes est posée par [ChromeSysteme.creerStatusBar]
 * selon la luminance du fond ([LisibiliteChrome]).
 */
@Suppress("MagicNumber")
private class GlyphesBarreStatut(
    contexte: Context,
) : View(contexte) {
    /** La couleur du contenu (heure, réseau, batterie — posée par le chrome). */
    var couleurGlyphes: Int = Color.WHITE
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Le texte (sans-serif-medium 11 — miroir `font: 500 11px Roboto`). */
    private val peintureTexte =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 11f * resources.displayMetrics.density
        }

    /** Les barres de réseau (`▮▮▮` — trois rectangles verticaux pleins). */
    private val peintureBarres = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canevas: Canvas) {
        super.onDraw(canevas)
        val densite = resources.displayMetrics.density
        peintureTexte.color = couleurGlyphes
        peintureBarres.color = couleurGlyphes
        // La ligne de base d'un texte OPTIQUEMENT centré (miroir line-height 1).
        val ligne = height / 2f - (peintureTexte.descent() + peintureTexte.ascent()) / 2f
        // GAUCHE : « 9:41 » (padding 12 — miroir `padding: 0 12px`).
        canevas.drawText(HEURE, PAD_H * densite, ligne, peintureTexte)
        // DROITE : « 5G  ▮▮▮ 100% » aligné sur le bord moins le padding.
        val fin = width - PAD_H * densite
        val largeurBatterie = peintureTexte.measureText(BATTERIE)
        val largeur5G = peintureTexte.measureText(RESEAU_5G)
        val largeurBarres = NB_BARRES * LARGEUR_BARRE + (NB_BARRES - 1) * ECART_BARRE
        val ecart = ECART_BLOC * densite
        val xBarres = fin - largeurBatterie - ecart - largeurBarres
        val demiHauteur = HAUTEUR_BARRE / 2f
        for (indice in 0 until NB_BARRES) {
            val gauche = xBarres + indice * (LARGEUR_BARRE + ECART_BARRE)
            canevas.drawRect(
                gauche,
                height / 2f - demiHauteur,
                gauche + LARGEUR_BARRE,
                height / 2f + demiHauteur,
                peintureBarres,
            )
        }
        canevas.drawText(RESEAU_5G, xBarres - ecart - largeur5G, ligne, peintureTexte)
        canevas.drawText(BATTERIE, fin - largeurBatterie, ligne, peintureTexte)
    }

    private companion object {
        /** L'heure de la maquette (capture figée — jamais l'heure réelle). */
        private const val HEURE: String = "9:41"

        /** Le réseau (miroir `5G`). */
        private const val RESEAU_5G: String = "5G"

        /** La batterie (miroir `100%`). */
        private const val BATTERIE: String = "100%"

        /** Le padding horizontal (miroir `padding: 0 12px`). */
        private const val PAD_H: Float = 12f

        /** L'écart entre blocs de texte et barres (les deux espaces). */
        private const val ECART_BLOC: Float = 6f

        /** Le nombre de barres du réseau (`▮▮▮`). */
        private const val NB_BARRES: Int = 3

        /** La largeur d'une barre, en dp (le glyphe plein `▮`). */
        private const val LARGEUR_BARRE: Float = 3f

        /** La hauteur d'une barre, en dp (le glyphe plein `▮`). */
        private const val HAUTEUR_BARRE: Float = 8f

        /** L'écart entre barres, en dp. */
        private const val ECART_BARRE: Float = 2f
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
