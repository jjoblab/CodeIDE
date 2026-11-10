package jo.codeide.feature.sorties

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import jo.codeide.core.ui.R as RUi

/**
 * La poignée de défilement du lecteur (spec SORTIES § 2 : « repères sur
 * la poignée de défilement — erreurs rouge, avertissements ambre,
 * correspondances bleu ») — une vue dessinée à côté de la liste : la
 * poignée (glissière) et les REPÈRES DE DÉFILEMENT, calculés sur l'INDEX
 * de projection, jamais sur les vues (ADR 0159 décision 2 : « repères de
 * défilement calculés sur l'index, pas sur les vues »).
 *
 * Les repères arrivent en positions NORMALISÉES (0..1) — le lecteur les
 * déduit de l'index (rangée / nombre de rangées) : une liste virtualisée
 * ne connaît pas les hauteurs réelles de toutes ses rangées, la
 * normalisation est la seule vérité honnête.
 *
 * Couleurs par jetons réels (`sorties_niveau_*` pour erreurs et
 * avertissements, `sorties_niveau_debogage` pour les correspondances —
 * bleu), poignée par rôle de thème.
 */
internal class PoigneeDefilement
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val peinturePoignee = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peintureErreur = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peintureAvertissement = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peintureCorrespondance = Paint(Paint.ANTI_ALIAS_FLAG)

    private var fenetreDebut = 0f
    private var fenetreFin = 1f
    private var reperesErreurs: List<Float> = emptyList()
    private var reperesAvertissements: List<Float> = emptyList()
    private var reperesCorrespondances: List<Float> = emptyList()

    init {
        peinturePoignee.color =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        peintureErreur.color = ContextCompat.getColor(context, RUi.color.sorties_niveau_erreur)
        peintureAvertissement.color = ContextCompat.getColor(context, RUi.color.sorties_niveau_avertissement)
        peintureCorrespondance.color = ContextCompat.getColor(context, RUi.color.sorties_niveau_debogage)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * La fenêtre visible (positions normalisées début/fin de liste) —
     * nourrie par l'écouteur de défilement du lecteur.
     */
    public fun definirFenetre(debut: Float, fin: Float) {
        fenetreDebut = debut.coerceIn(0f, 1f)
        fenetreFin = fin.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Les repères de défilement (positions normalisées 0..1, calculées
     * sur l'index par le lecteur).
     */
    public fun definirMarques(erreurs: List<Float>, avertissements: List<Float>, correspondances: List<Float>) {
        reperesErreurs = erreurs
        reperesAvertissements = avertissements
        reperesCorrespondances = correspondances
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rail = RAIL_DP * resources.displayMetrics.density
        val droite = width - rail / 2f

        // Repères (rouge / ambre / bleu) — jamais la couleur seule : la
        // légende du bandeau d'état porte les compteurs (règle 14).
        tracerReperes(canvas, reperesErreurs, droite, peintureErreur)
        tracerReperes(canvas, reperesAvertissements, droite, peintureAvertissement)
        tracerReperes(canvas, reperesCorrespondances, droite, peintureCorrespondance)

        // La poignée : la fenêtre VISIBLE de la liste, minimale mais
        // jamais invisible (une ligne visible se voit).
        val hauteur = height.toFloat()
        val debut = fenetreDebut * hauteur
        val fin = (fenetreFin * hauteur).coerceAtLeast(debut + MIN_POIGNEE_DP * resources.displayMetrics.density)
        val rayon = (rail / 2f)
        canvas.drawRoundRect(
            droite - rail,
            debut,
            droite + rail,
            fin,
            rayon,
            rayon,
            peinturePoignee,
        )
    }

    /** Trace une série de repères (points) sur le rail droit. */
    private fun tracerReperes(canvas: Canvas, positions: List<Float>, droite: Float, peinture: Paint) {
        if (positions.isEmpty()) return
        val rayon = MARQUE_DP * resources.displayMetrics.density / 2f
        val x = droite - RAIL_DP * resources.displayMetrics.density - rayon * 2f
        positions.forEach { position ->
            canvas.drawCircle(x, position * height, rayon, peinture)
        }
    }

    private companion object {
        /** Largeur du rail de la poignée (dp). */
        private const val RAIL_DP = 4f

        /** Hauteur minimale de la poignée visible (dp). */
        private const val MIN_POIGNEE_DP = 24f

        /** Taille d'un repère (dp). */
        private const val MARQUE_DP = 6f
    }
}
