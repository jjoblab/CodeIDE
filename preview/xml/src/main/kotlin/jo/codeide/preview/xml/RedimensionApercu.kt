package jo.codeide.preview.xml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Le REDIMENSIONNEMENT par poignée d'angle (PE11l — demandé par le
 * propriétaire : « le redimensionnement ») — fonctions PURES testées
 * sans Android (patron [ZonesDepot]).
 *
 * Sémantique (miroir la rangée de dimension de la maquette — `dimrow`,
 * `Math.max(8, …)` au passage en fixe ; le geste supplée le clavier) :
 * - saisir une poignée ANCRE le coin OPPOSÉ de la vue sélectionnée ;
 * - la cible suit le doigt : largeur = |x − ancre|, hauteur = |y − ancre|
 *   en px CONTENU, convertis en dp du PROFIL ([CalquesApercu.pxVersDp] —
 *   l'aperçu rend aux dimensions du profil, pas de l'appareil) ;
 * - chaque dimension garde le PLANCHER 8 dp (une vue à −1 dp n'existe
 *   pas dans la maquette non plus) ;
 * - le relâchement écrit les DEUX axes en UNE seule édition groupée
 *   ([EditionAttributsXml.ecrireDimensions] — un geste = une entrée
 *   d'historique, miroir `mutate`).
 */
public object RedimensionApercu {
    /**
     * Le plancher dp d'une dimension redimensionnée (miroir le
     * `Math.max(8, …)` de la maquette au passage en taille fixe).
     */
    public const val DIMENSION_MIN_DP: Int = 8

    /**
     * Le coin ANCRE de la poignée saisie : la poignée sous le point
     * (coordonnées CONTENU, rayon de saisie en unités CONTENU — la vue
     * convertit ses 18 dp écran par [VuePreviewXml.tailleEcran]) —
     * l'ancre est le coin OPPOSÉ. `null` : aucune poignée sous le
     * point (ou densité inconnue → le geste ne peut pas produire de dp).
     */
    public fun ancrePoigneeSous(
        vue: InfoVueRendu,
        xContenu: Int,
        yContenu: Int,
        rayonPx: Float,
    ): Pair<Int, Int>? {
        val coins =
            listOf(
                // (coin, ancre opposée) : haut-gauche → bas-droite, etc.
                (vue.gauche to vue.haut) to (vue.droite to vue.bas),
                (vue.droite to vue.haut) to (vue.gauche to vue.bas),
                (vue.gauche to vue.bas) to (vue.droite to vue.haut),
                (vue.droite to vue.bas) to (vue.gauche to vue.haut),
            )
        return coins
            .firstOrNull { (coin, _) ->
                val dx = xContenu - coin.first
                val dy = yContenu - coin.second
                dx * dx + dy * dy <= rayonPx * rayonPx
            }?.second
    }

    /**
     * Les dimensions CIBLES (dp, plancher 8) du coin ancre au point
     * courant. `null` si la densité du profil est inconnue (0 — repli
     * px : le geste ne produit RIEN plutôt qu'une écriture fausse).
     */
    public fun dimensions(
        ancreX: Int,
        ancreY: Int,
        pointX: Int,
        pointY: Int,
        densiteDpi: Int,
    ): Pair<Int, Int>? {
        if (densiteDpi <= 0) return null
        val largeurDp =
            CalquesApercu
                .pxVersDp(abs(pointX - ancreX), densiteDpi)
                .coerceAtLeast(DIMENSION_MIN_DP)
        val hauteurDp =
            CalquesApercu
                .pxVersDp(abs(pointY - ancreY), densiteDpi)
                .coerceAtLeast(DIMENSION_MIN_DP)
        return largeurDp to hauteurDp
    }

    /**
     * Le RECTANGLE cible du fantôme (px contenu) : du coin ancre au
     * point courant, ordonné (la drag peut croiser l'ancre — la cible
     * reste un rectangle propre).
     */
    public fun rectangle(
        ancreX: Int,
        ancreY: Int,
        pointX: Int,
        pointY: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> =
        (min(ancreX, pointX) to min(ancreY, pointY)) to
            (max(ancreX, pointX) to max(ancreY, pointY))

    /**
     * Le geste a-t-il DÉRIVÉ au-delà du seuil ? Un relâchement SANS
     * dérive est un simple contact sur la poignée : RIEN ne s'écrit
     * (miroir le tap sans dérive du canvas — pas de mutation fantôme).
     */
    public fun aDerive(
        departX: Int,
        departY: Int,
        pointX: Int,
        pointY: Int,
        seuilPx: Float,
    ): Boolean {
        val dx = pointX - departX
        val dy = pointY - departY
        return dx * dx + dy * dy > seuilPx * seuilPx
    }
}
