package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/**
 * Panneau de rendu de la preview XML (PE1 — PREVIEW.md §3.3) : dessine le
 * bitmap off-screen du [MoteurPreviewXml] avec **zoom/pan** (pincer,
 * glisser, double-tap pour réajuster) et damier de transparence, PLUS les
 * états du panneau (bandeau d'erreur en haut, signal discret d'édition,
 * accueil) — TOUT est dessiné ici (aucune vue enfant : la vue EST le
 * panneau, la bibliothèque Material n'entre pas dans ce module).
 *
 * Le bitmap n'est jamais recyclé par la vue (le moteur peut en émettre un
 * nouveau pendant un dessin) : le GC d'ART collecte.
 *
 * Le calcul d'échelle « ajuster à la vue » vit dans [CalculsVuePreview]
 * (fonctions pures, testées sans Android).
 */
@Suppress("TooManyFunctions", "LargeClass") // Vue auto-dessinée (états, damier, bandeau, zoom) : un panneau UNE vue.
public class VuePreviewXml : View {
    private var bitmap: Bitmap? = null
    private var echelle = 1f
    private var decalageX = 0f
    private var decalageY = 0f
    private var messageErreur: String? = null
    private var enEdition = false
    private var accueilAffiche = true

    private val peintDamier =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_DAMIER_A
        }
    private val peintDamierB =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_DAMIER_B
        }
    private val peintBandeau =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_BANDEAU
        }
    private val peintTexteBandeau =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
        }
    private val peintTexteEtat =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    private val detecteurZoom =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val ancienne = echelle
                    echelle = CalculsVuePreview.bornerEchelle(echelle * detector.scaleFactor)
                    // Le contenu reste sous les doigts : le décalage suit le
                    // point focal rapporté à la nouvelle échelle.
                    val rapport = echelle / ancienne
                    decalageX = detector.focusX - rapport * (detector.focusX - decalageX)
                    decalageY = detector.focusY - rapport * (detector.focusY - decalageY)
                    bornerPan()
                    invalidate()
                    return true
                }
            },
        )
    private val detecteurGestes =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    decalageX -= distanceX
                    decalageY -= distanceY
                    bornerPan()
                    invalidate()
                    return true
                }

                override fun onDoubleTap(evenement: MotionEvent): Boolean {
                    ajusterALaVue()
                    invalidate()
                    return true
                }
            },
        )

    public constructor(contexte: Context) : this(contexte, null)

    public constructor(
        contexte: Context,
        attributs: AttributeSet?,
    ) : super(contexte, attributs) {
        peintTexteEtat.textSize = echellePolice() * TAILLE_TEXTE_ETAT_DP
        peintTexteEtat.color = couleurTheme(android.R.attr.textColorSecondary)
        contentDescription = contexte.getString(R.string.preview_cd)
    }

    /** Affiche un rendu valide (remplace le bitmap, réajuste le zoom). */
    public fun afficherRendu(bitmap: Bitmap) {
        this.bitmap = bitmap
        messageErreur = null
        enEdition = false
        accueilAffiche = false
        ajusterALaVue()
        invalidate()
    }

    /** Affiche l'erreur sémantique en bandeau (le bitmap précédent reste). */
    public fun afficherErreur(message: String) {
        messageErreur = message
        enEdition = false
        accueilAffiche = false
        invalidate()
    }

    /** Signale l'édition en cours (XML malformé) — discret. */
    public fun afficherEnEdition() {
        enEdition = true
        invalidate()
    }

    /** Réinitialise (fermeture du panneau, plus de document). */
    public fun reinitialiser() {
        bitmap = null
        messageErreur = null
        enEdition = false
        accueilAffiche = true
        invalidate()
    }

    override fun onSizeChanged(
        largeur: Int,
        hauteur: Int,
        ancienneLargeur: Int,
        ancienneHauteur: Int,
    ) {
        super.onSizeChanged(largeur, hauteur, ancienneLargeur, ancienneHauteur)
        if (largeur != ancienneLargeur || hauteur != ancienneHauteur) {
            ajusterALaVue()
        }
    }

    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        val geste = detecteurGestes.onTouchEvent(evenement)
        val zoom = detecteurZoom.onTouchEvent(evenement)
        // ClickableViewAccessibility : un appui SIMPLE se signale via
        // performClick (le double-tap de réajustement reste au détecteur).
        if (evenement.actionMasked == MotionEvent.ACTION_UP) performClick()
        return zoom || geste || super.onTouchEvent(evenement)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canevas: Canvas) {
        super.onDraw(canevas)
        val bmp = bitmap
        if (bmp == null) {
            dessinerAccueil(canevas)
            return
        }
        dessinerDamier(canevas, hauteurZoneContenu())
        canevas.save()
        canevas.translate(decalageX, decalageY)
        canevas.scale(echelle, echelle)
        canevas.drawBitmap(bmp, 0f, 0f, null)
        canevas.restore()
        if (messageErreur != null) dessinerBandeauErreur(canevas)
        if (enEdition) dessinerSignalEdition(canevas)
    }

    /** Hauteur disponible au contenu : le bandeau d'erreur la grignote. */
    private fun hauteurZoneContenu(): Float =
        if (messageErreur == null) {
            height.toFloat()
        } else {
            (height - hauteurBandeau()).coerceAtLeast(1f)
        }

    private fun dessinerDamier(
        canevas: Canvas,
        zoneHauteur: Float,
    ) {
        var y = 0f
        var rangee = 0
        while (y < zoneHauteur) {
            var x = 0f
            var colonne = 0
            while (x < width) {
                val peinture = if ((rangee + colonne) % 2 == 0) peintDamier else peintDamierB
                canevas.drawRect(x, y, x + TAILLE_CASE_DAMIER, min(y + TAILLE_CASE_DAMIER, zoneHauteur), peinture)
                x += TAILLE_CASE_DAMIER
                colonne += 1
            }
            y += TAILLE_CASE_DAMIER
            rangee += 1
        }
    }

    private fun dessinerBandeauErreur(canevas: Canvas) {
        val message = messageErreur ?: return
        peintTexteBandeau.textSize = echellePolice() * TAILLE_TEXTE_BANDEAU_DP
        canevas.drawRect(0f, 0f, width.toFloat(), hauteurBandeau(), peintBandeau)
        val texte = tronquer(message, largeurTexteDisponible())
        canevas.drawText(texte, MARGE_TEXTE, centreVerticalBandeau(), peintTexteBandeau)
    }

    private fun dessinerSignalEdition(canevas: Canvas) {
        val libelle = context.getString(R.string.preview_en_edition)
        val largeurTexte = peintTexteEtat.measureText(libelle)
        canevas.drawText(
            libelle,
            (width - largeurTexte) / 2f,
            height - MARGE_TEXTE,
            peintTexteEtat,
        )
    }

    private fun dessinerAccueil(canevas: Canvas) {
        val libelle = context.getString(R.string.preview_accueil)
        peintTexteEtat.textAlign = Paint.Align.CENTER
        canevas.drawText(libelle, width / 2f, height / 2f, peintTexteEtat)
        peintTexteEtat.textAlign = Paint.Align.LEFT
    }

    /** Réajuste zoom et décalage pour que le bitmap tienne dans la vue. */
    private fun ajusterALaVue() {
        val bmp = bitmap ?: return
        echelle =
            CalculsVuePreview.echelleAjustee(
                largeurContenu = bmp.width,
                hauteurContenu = bmp.height,
                largeurVue = width,
                hauteurVue = height,
            )
        decalageX = (width - bmp.width * echelle) / 2f
        decalageY = (height - bmp.height * echelle) / 2f
    }

    /** Maintient le contenu visible pendant le pan. */
    private fun bornerPan() {
        val bmp = bitmap ?: return
        decalageX = CalculsVuePreview.bornerDecalage(decalageX, width.toFloat(), bmp.width * echelle)
        decalageY = CalculsVuePreview.bornerDecalage(decalageY, height.toFloat(), bmp.height * echelle)
    }

    /** Échelle de police (densité × fontScale — remplace scaledDensity déprécié API 34). */
    private fun echellePolice(): Float = resources.displayMetrics.density * resources.configuration.fontScale

    private fun hauteurBandeau(): Float = echellePolice() * HAUTEUR_BANDEAU_DP

    private fun centreVerticalBandeau(): Float =
        hauteurBandeau() / 2f - (peintTexteBandeau.descent() + peintTexteBandeau.ascent()) / 2f

    private fun largeurTexteDisponible(): Float = width - 2 * MARGE_TEXTE

    private fun tronquer(
        texte: String,
        largeurMax: Float,
    ): String {
        if (peintTexteBandeau.measureText(texte) <= largeurMax) return texte
        var coupure = texte.length
        while (coupure > 1 && tropLarge(texte, coupure, largeurMax)) {
            coupure -= 1
        }
        return texte.substring(0, coupure) + SUFFIXE_TRONQUE
    }

    private fun tropLarge(
        texte: String,
        coupure: Int,
        largeurMax: Float,
    ): Boolean = peintTexteBandeau.measureText(texte.substring(0, coupure) + SUFFIXE_TRONQUE) > largeurMax

    private fun couleurTheme(attribut: Int): Int {
        val valeur = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribut, valeur, true)) {
            valeur.data
        } else {
            Color.GRAY
        }
    }

    public companion object {
        private const val COULEUR_DAMIER_A = 0xFFE8E8EC.toInt()
        private const val COULEUR_DAMIER_B = 0xFFF6F6F9.toInt()
        private const val COULEUR_BANDEAU = 0xFFB3261E.toInt()
        private const val TAILLE_CASE_DAMIER = 16f
        private const val MARGE_TEXTE = 16f
        private const val TAILLE_TEXTE_ETAT_DP = 12f
        private const val TAILLE_TEXTE_BANDEAU_DP = 13f
        private const val HAUTEUR_BANDEAU_DP = 36f
        private const val SUFFIXE_TRONQUE = "…"
    }
}

/** Calculs de zoom/pan de la vue — fonctions pures (testées sans Android). */
internal object CalculsVuePreview {
    /** Échelle ajustée (fit) : le contenu ENTIER tient dans la vue ; ≥ 0,1. */
    internal fun echelleAjustee(
        largeurContenu: Int,
        hauteurContenu: Int,
        largeurVue: Int,
        hauteurVue: Int,
    ): Float {
        val contenuInvalide = largeurContenu <= 0 || hauteurContenu <= 0
        val vueInvalide = largeurVue <= 0 || hauteurVue <= 0
        if (contenuInvalide || vueInvalide) return 1f
        return min(
            largeurVue.toFloat() / largeurContenu,
            hauteurVue.toFloat() / hauteurContenu,
        ).coerceAtLeast(ECHELLE_MINIMALE)
    }

    /** Échelle bornée (pincement) : 0,25× … 6×. */
    internal fun bornerEchelle(echelle: Float): Float = echelle.coerceIn(ECHELLE_MINIMALE_PINCEMENT, ECHELLE_MAXIMALE)

    /**
     * Décalage borné : le contenu ne fuit pas hors de la vue — contenu
     * plus PETIT que la vue : centré (décalage fixé) ; plus grand : au
     * moins un bord reste visible.
     */
    internal fun bornerDecalage(
        decalage: Float,
        tailleVue: Float,
        tailleContenu: Float,
    ): Float =
        if (tailleContenu <= tailleVue) {
            (tailleVue - tailleContenu) / 2f
        } else {
            decalage.coerceIn(tailleVue - tailleContenu, 0f)
        }

    private const val ECHELLE_MINIMALE = 0.1f
    private const val ECHELLE_MINIMALE_PINCEMENT = 0.25f
    private const val ECHELLE_MAXIMALE = 6f
}
