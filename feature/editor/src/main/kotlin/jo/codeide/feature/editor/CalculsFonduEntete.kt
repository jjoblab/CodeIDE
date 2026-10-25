package jo.codeide.feature.editor

import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Courbes de fondu de l'en-tête du panneau inférieur (v0.184.0, retour
 * propriétaire : « le header qui disparaît quand le bottomsheet
 * expand donne un mauvais rendu, n'est pas fluide en disparaissant » ;
 * v0.185.0, retour propriétaire : « le header doit SUIVRE la
 * progression du slide pour disparaître et réapparaître »).
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
 * v0.185.0 — la HAUTEUR suit le même glissement : l'effondrement de
 * l'en-tête se jouait en reflow `ChangeBounds` 250 ms UNE FOIS l'état
 * stabilisé — les onglets continuaient de bouger APRÈS le doigt. La
 * hauteur est désormais posée PAR TRAME par `onSlide` sur la MÊME
 * cosinus que l'alpha : l'en-tête se réduit À MESURE que la feuille
 * monte et reprend sa hauteur à mesure qu'elle redescend — la
 * disparition ET la réapparition suivent la progression du glissement.
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

    /**
     * Hauteur de l'en-tête pour l'alpha courant du fondu (v0.185.0) :
     * la MÊME cosinus que l'alpha, appliquée à la hauteur de repos —
     * l'en-tête garde sa hauteur PLEINE tant qu'il est opaque puis
     * s'effondre progressivement jusqu'à 0 à l'extension. Posée par
     * trame par `onSlide`, elle remplace le reflow `ChangeBounds`
     * post-glissement : les onglets suivent le doigt au lieu de rejouer
     * leur propre animation.
     *
     * @param hauteurRepos hauteur de repos de la section en pixels
     *        (vue ÉTIRÉE — inclut son rembourrage d'inset bas).
     * @param alpha alpha courant du fondu ([alpha] — 1 opaque, 0 éteint).
     * @return hauteur en pixels, JAMAIS négative (bornée 0..hauteurRepos).
     */
    fun hauteurPx(hauteurRepos: Int, alpha: Float): Int =
        (hauteurRepos * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, hauteurRepos.coerceAtLeast(0))
}
