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
 *   ([EditionAttributsXml.ecrireRedimension] — un geste = une entrée
 *   d'historique, miroir `mutate`).
 *
 * PE14-S2 (ADR 0138, arbitrage D1 de l'étude
 * `docs/PE14-redimensionnement-layoutlib-conformite-compose-etude.md`)
 * — LA COMPENSATION DE PRÉHENSION : le tactile saisit la poignée
 * quelque part dans son rayon de 18 dp écran, PAS sur le coin. Sans
 * compensation, la cible sautait d'emblée de la longueur du décalage de
 * saisie (démonstration § 1.3 de l'étude : +12 dp par axe sur une
 * saisie à 12 dp du coin — un TIERS de la dimension sur un bouton). La
 * machine d'état ([VuePreviewXml]) mémorise le décalage au `DOWN`
 * ([decalagePrehension] = coin saisi − contact) et lui passe ensuite le
 * point COURANT COMPENSÉ (contact + décalage) : le COIN VISUEL suit le
 * doigt, le fantôme part DE LA TAILLE ACTUELLE de la vue. Un contact
 * EXACT sur le coin donne un décalage nul : le modèle historique
 * (souris, maquette v6) en est le cas particulier — C1 est
 * RÉTROCOMPATIBLE, les fonctions ci-dessous ne changent PAS de
 * signature.
 *
 * Le PAR-LAYOUT au relâchement (C3 — poids, marges RelativeLayout,
 * positions `tools:` de ConstraintLayout) vit dans
 * [RedimensionParLayout] : le modèle du GESTE et les ajustements du
 * PARENT sont deux objets purs distincts.
 */
public object RedimensionApercu {
    /**
     * Le plancher dp d'une dimension redimensionnée (miroir le
     * `Math.max(8, …)` de la maquette au passage en taille fixe).
     */
    public const val DIMENSION_MIN_DP: Int = 8

    /**
     * PE14-S2 (C4) — la durée du FONDU du fantôme persistant : le
     * relâchement garde la cible affichée jusqu'au RENDU SUIVANT, puis
     * la fond sur 150 ms — le panneau n'a jamais d'instant vide (le
     * re-rendu asynchrone ne se lit plus comme « rien ne s'est passé »).
     */
    public const val DUREE_FONDU_FANTOME_MS: Long = 150L

    /**
     * La poignée sous le point : le coin SAISI et le coin ANCRE (son
     * opposé). `null` : aucune poignée sous le point.
     *
     * PE14-S2 : la saisie rapporte désormais AUSSI le coin saisi — la
     * vue en dérive la compensation de préhension
     * (`decalage = coinSaisi − pointDeContact`).
     */
    public fun poigneeSous(
        vue: InfoVueRendu,
        xContenu: Int,
        yContenu: Int,
        rayonPx: Float,
    ): PoigneeSaisie? {
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
            }?.let { (coin, ancre) -> PoigneeSaisie(coin, ancre) }
    }

    /**
     * Le coin ANCRE de la poignée saisie (le coin OPPOSÉ, historique
     * PE11l — la vue convertit ses 18 dp écran par
     * [VuePreviewXml.tailleEcran]).
     */
    public fun ancrePoigneeSous(
        vue: InfoVueRendu,
        xContenu: Int,
        yContenu: Int,
        rayonPx: Float,
    ): Pair<Int, Int>? = poigneeSous(vue, xContenu, yContenu, rayonPx)?.ancre

    /** Une saisie de poignée : le coin SAISI et le coin ANCRE (px contenu). */
    public data class PoigneeSaisie(
        /** Le coin visuel sous le doigt (px contenu). */
        public val coin: Pair<Int, Int>,
        /** Le coin opposé, tenu FIXE pendant le geste (px contenu). */
        public val ancre: Pair<Int, Int>,
    )

    /**
     * La cible dp du relâchement (PE14-S2 — portée par le geste, elle
     * voyage jusqu'à l'édition : le par-layout en dérive marges et
     * positions du dialecte `tools:`).
     *
     * @property largeurDp la largeur cible (plancher 8 dp).
     * @property hauteurDp la hauteur cible (plancher 8 dp).
     * @property ancreX le coin ANCRE horizontal (px contenu — bord tenu).
     * @property ancreY le coin ANCRE vertical (px contenu — bord tenu).
     * @property coinX le coin CIBLE horizontal au relâchement (px
     *           contenu — point de contact + compensation de préhension).
     * @property coinY le coin CIBLE vertical au relâchement (px contenu).
     */
    public data class CibleRedimension(
        public val largeurDp: Int,
        public val hauteurDp: Int,
        public val ancreX: Int,
        public val ancreY: Int,
        public val coinX: Int,
        public val coinY: Int,
    )

    /**
     * La compensation de préhension (C1) : le décalage entre le COIN
     * saisi et le POINT DE CONTACT au `DOWN` (px contenu). Ajouté au
     * point courant, il ramène le doigt sur le coin VISUEL — la machine
     * d'état stocke le point COURANT COMPENSÉ, la dérive se mesure sur
     * la même trajectoire (le décalage est CONSTANT sur tout le geste).
     */
    public fun decalagePrehension(
        coinX: Int,
        coinY: Int,
        pointX: Int,
        pointY: Int,
    ): Pair<Int, Int> = (coinX - pointX) to (coinY - pointY)

    /**
     * Les dimensions CIBLES (dp, plancher 8) du coin ancre au point
     * courant — le point est le doigt COMPENSÉ de la préhension (C1 :
     * la machine passe le coin visé, pas le contact brut). `null` si la
     * densité du profil est inconnue (0 — repli px : le geste ne
     * produit RIEN plutôt qu'une écriture fausse).
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
     * point courant COMPENSÉ (C1), ordonné (la drag peut croiser
     * l'ancre — la cible reste un rectangle propre).
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

    // ── Transition douce post-écriture (C4) ────────────────────────────

    /**
     * L'OPACITÉ du fantôme persistant (C4) : 1 jusqu'au rendu suivant,
     * puis fondu linéaire sur [DUREE_FONDU_FANTOME_MS] — pur, testé
     * sans horloge (la vue passe l'écart mesuré).
     */
    public fun alphaFantome(ecouleMs: Long): Float = (1f - ecouleMs.toFloat() / DUREE_FONDU_FANTOME_MS).coerceIn(0f, 1f)
}
