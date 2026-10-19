package jo.codeide.feature.preview

/**
 * La GLISSADE des pièces basses au-dessus de la FEUILLE inférieure
 * (PE11k — miroir `applySheet` de la maquette : le bas utile vaut
 * `ch − (S.open ? SHEET_OPEN : 72) − 10` — le HUD de zoom et la
 * pastille d'état suivent le bord COURANT de la feuille, entête
 * replié compris ; la même formule porte la réserve de la bulle
 * `sh = (S.open ? SHEET_OPEN : 72) + 10`).
 *
 * Correctif PE11k (bug rapporté par le propriétaire : « une partie de la
 * section zoom/dézoomer est cachée par l'entête de bottomsheet, la
 * section rendu · N vues aussi ») : l'ancienne translation ne portait
 * que la part DÉPLOYÉE de la feuille — repliée, l'entête (peek) reste
 * POURTANT visible et recouvrait le HUD de zoom (bas-droite) et la
 * pastille d'état « Rendu · N vues » (bas-gauche) posés au bord ; les
 * deux pièces montent désormais au-dessus du bord courant de la feuille
 * (peek + part déployée), JAMAIS derrière lui.
 *
 * Fonctions PURES (règle 1) : l'activité pose les mesures, la formule
 * vit ici — testée à part ([GlissadeFeuilleApercuTest]).
 */
internal object GlissadeFeuilleApercu {
    /**
     * La translation VERTICALE du HUD de zoom et de la pastille d'état :
     * le bord courant de la feuille (peek + part déployée). Les pièces
     * gardent leur marge de bord (10 dp) — la translation la rend en
     * haut du bord de la feuille (miroir le `− 10` de la maquette).
     */
    internal fun translationHud(
        peekPx: Int,
        deployeePx: Int,
    ): Float = -(peekPx + deployeePx).toFloat()

    /**
     * La réserve BAS de la bulle : le HUD de zoom + sa marge (l'ancienne
     * formule) AUGMENTÉS du bord courant de la feuille — la bulle ne
     * stationne jamais derrière l'entête replié (miroir le `sh` de
     * `placeBubble` : hauteur visible de la feuille + 10).
     */
    internal fun reserveBulle(
        hudPx: Int,
        margePx: Int,
        peekPx: Int,
        deployeePx: Int,
    ): Int = hudPx + margePx + peekPx + deployeePx
}
