package jo.codeide.feature.editor

import kotlin.math.cos

/**
 * Courbe de fondu de l'en-tête du panneau inférieur (v0.184.0, retour
 * propriétaire : « le header qui disparaît quand le bottomsheet
 * expand donne un mauvais rendu, n'est pas fluide en disparaissant »).
 *
 * AVANT : alpha linéaire enfoncé à partir de mi-hauteur — le fondu ne
 * démarrait QU'au-delà de 0,5 avec une PENTE CONSTANTE : l'en-tête
 * restait figé opaque pendant toute la première moitié du glissement
 * puis se mettait à couler d'un coup — le « coin » à mi-hauteur se
 * voyait, la disparition lisait hachée.
 *
 * DÉSORMAIS : la MÊME plage [FRACTION_MI_HAUTEUR, 1] (l'intent
 * « opaque jusqu'à mi-hauteur » de v0.32.5 est conservé) mais sur une
 * COSINUS — la dérivée est NULLE aux deux bornes : le fondu part sans
 * à-coup, s'accélère au milieu, s'éteint sans rebond. C'est le
 * lissage classique d'une interpolation (smoothstep équivalent), la
 * trame d'`onSlide` n'a plus qu'à poser l'alpha.
 *
 * Fonction PURE (aucune dépendance Android) — couverte par
 * [CalculsFonduEnteteTest].
 */
internal object CalculsFonduEntete {
    /**
     * Alpha de l'en-tête pour une fraction de glissement du sheet.
     *
     * @param glissement fraction de glissement (0 = replié,
     *        0,5 = mi-hauteur, 1 = étendu).
     * @param fractionDepart fraction à laquelle le fondu démarre
     *        (opaque en dessous — 0,5 : « opaque jusqu'à mi-hauteur »).
     * @return alpha entre 0 (éteint) et 1 (opaque) — cosinus lissée.
     */
    fun alpha(glissement: Float, fractionDepart: Float = 0.5f): Float {
        val progression = (glissement - fractionDepart) / (1f - fractionDepart)
        return when {
            progression <= 0f -> 1f
            progression >= 1f -> 0f
            else -> (1f + cos(progression * Math.PI.toFloat())) / 2f
        }
    }
}
