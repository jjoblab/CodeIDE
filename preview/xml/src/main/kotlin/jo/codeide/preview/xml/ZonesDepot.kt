package jo.codeide.preview.xml

import kotlin.math.max
import kotlin.math.min

/**
 * Zones de dépôt de la palette (PE7b, maquette
 * `docs/mockups/apercu-xml.html` § « Zones de dépôt et glisser-déposer
 * natif ») — FONCTIONS PURES sur l'arbre aplati [InfoVueRendu] :
 * « [ZonesDepot] calcule, depuis la liste de [InfoVueRendu], les
 * conteneurs, les emplacements entre frères et la cible sous un point. »
 *
 * Miroir du comportement maquette (`targetAt`, `slotRect`, `drawDrop`) :
 * - [cibleSous] : le CONTENEUR le plus PROFOND sous le point, et
 *   l'INDEX d'insertion entre ses frères (un frère compte quand son
 *   MILIEU — horizontal ? x : y — précède le point) ;
 * - [emplacement] : le rectangle de la BARRE D'INSERTION (traversante
 *   dans le conteneur, épaisseur ÉCRAN constante) ;
 * - [libelleCible] / [libelleInsertion] : les libellés des pills
 *   violettes (« Dans LinearLayout · @+id/x », « + Button · 2/4 »).
 *
 * Le dessin Canvas et l'`OnDragListener` vivent dans [VuePreviewXml] ;
 * le dépôt remonte par [VuePreviewXml.auDepot] et l'écriture passe par
 * `InsertionXml` → port `SourceApercu.remplacer` — le texte du document
 * reste la seule source.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
 * (arbre vide, conteneur absent, vue dégénérée, hors bornes) est une
 * issue DISTINCTE du hit-test (patron [SelectionPreview]).
 */
@Suppress("ReturnCount")
public object ZonesDepot {
    /**
     * La cible de dépôt sous le point (coordonnées bitmap).
     *
     * @property indiceParent indice du CONTENEUR visé dans l'arbre aplati.
     * @property index position d'insertion entre ses frères (0 = premier).
     */
    public data class CibleDepot(
        val indiceParent: Int,
        val index: Int,
    )

    /**
     * Le rectangle d'une BARRE D'INSERTION (coordonnées bitmap, px du
     * profil) — dessinée par la vue comme le `.slot` de la maquette.
     */
    public data class RectangleDepot(
        val gauche: Float,
        val haut: Float,
        val droite: Float,
        val bas: Float,
    ) {
        /** Largeur du rectangle (px). */
        public val largeurPx: Float get() = droite - gauche

        /** Hauteur du rectangle (px). */
        public val hauteurPx: Float get() = bas - haut
    }

    /**
     * Le CONTENU glissé (localState du `View.startDragAndDrop` natif —
     * PE7c lance le drag, PE7b le reçoit) : l'extrait du catalogue
     * [CataloguePalette.EntreePalette.extraitXml] et le nom affiché des
     * pills. Classe SCELLÉE volontairement (aucun Bitmap ni ClipData :
     * le contenu transite par le localState du moteur de drag d'Android —
     * « sans qu'il faille les relayer à la main », maquette PE7b).
     */
    public data class ContenuDepot(
        val extraitXml: String,
        val nomAffiche: String,
    )

    /** Densité de repli (mdpi) quand le profil n'en fournit pas — conversion des constantes dp. */
    private const val DENSITE_REPLI: Int = 160

    /** Densité de référence Android (dpi) — facteur des conversions dp. */
    private const val DENSITE_REFERENCE: Int = 160

    /** Retrait de la barre aux bords du conteneur, en dp (miroir `pq.x + 8`). */
    private const val RETRAIT_DP: Int = 8

    /** Longueur minimale de la barre, en dp (miroir `Math.max(8, …)`). */
    private const val LONGUEUR_MIN_DP: Int = 8

    /** Plafond d'absorption de la marge du premier frère, en dp (miroir `Math.min(mt, 12) / 2`). */
    private const val ABSORPTION_DEBUT_DP: Int = 12

    /** Plafond d'absorption de la marge basse du dernier frère, en dp (miroir `Math.min(mb, 8) / 2`). */
    private const val ABSORPTION_FIN_DP: Int = 8

    /** Résiduel de position en fin de conteneur VERTICAL, en dp (miroir `+ 2`). */
    private const val RESIDUEL_FIN_VERTICAL_DP: Int = 2

    /** Résiduel de position en fin de conteneur HORIZONTAL, en dp (miroir `+ 4`). */
    private const val RESIDUEL_FIN_HORIZONTAL_DP: Int = 4

    /**
     * La cible de dépôt sous le point (coordonnées BITMAP) — le conteneur
     * le plus PROFOND contenant le point (miroir `targetAt` : profondeur
     * maximale), hors de l'écran rendu → `null`. L'INDEX entre frères se
     * calcule sur l'ordre du document (frères disjoints dans l'ordre du
     * tableau aplati) : un frère compte quand son MILIEU — horizontal ?
     * centre x : centre y — précède le point ; une vue sans orientation
     * (ConstraintLayout racine) se traite en colonne (miroir maquette,
     * `orient === 'h'` sinon vertical).
     */
    public fun cibleSous(
        vues: List<InfoVueRendu>,
        x: Float,
        y: Float,
    ): CibleDepot? {
        if (vues.isEmpty()) return null
        val conteneur = conteneurLePlusProfond(vues, x, y)
        if (conteneur < 0) return null
        val horizontal = vues[conteneur].orientation == InfoVueRendu.ORIENTATION_HORIZONTALE
        val coordonnee = if (horizontal) x else y
        return CibleDepot(indiceParent = conteneur, index = indexEntreFreres(vues, conteneur, horizontal, coordonnee))
    }

    /** Le conteneur le plus PROFOND contenant le point (-1 si aucun — miroir `targetAt`). */
    private fun conteneurLePlusProfond(
        vues: List<InfoVueRendu>,
        x: Float,
        y: Float,
    ): Int {
        var meilleur = -1
        for ((indice, vue) in vues.withIndex()) {
            if (!vue.conteneur || vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            if (vue.contient(x.toInt(), y.toInt()) && (meilleur < 0 || vue.profondeur > vues[meilleur].profondeur)) {
                meilleur = indice
            }
        }
        return meilleur
    }

    /** L'index entre frères : le compte de milieux DÉPASSÉS le long de l'axe (miroir boucle `targetAt`). */
    private fun indexEntreFreres(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        horizontal: Boolean,
        coordonnee: Float,
    ): Int {
        var index = 0
        for ((indice, vue) in vues.withIndex()) {
            if (indice == indiceParent || vue.indiceParent != indiceParent) continue
            val milieu = if (horizontal) (vue.gauche + vue.droite) / 2f else (vue.haut + vue.bas) / 2f
            if (coordonnee > milieu) index += 1
        }
        return index
    }

    /**
     * Le rectangle de la BARRE D'INSERTION pour la cible (miroir
     * `slotRect`) : traversante dans le conteneur (retrait de 8 dp aux
     * bords, longueur minimale de 8 dp), positionnée au milieu des écarts
     * entre frères (les marges du premier/dernier frère sont absorbées à
     * moitié, plafonnées — miroir `Math.min(mt, 12) / 2` et
     * `Math.min(mb, 8) / 2 + 2`) ; conteneur vide → au centre.
     *
     * @param densiteDpi densité du PROFIL de rendu (conversion dp → px ;
     *        ≤ 0 → repli mdpi, le calque est un dessin).
     * @param epaisseurPx épaisseur de la barre en px CONTENU (la vue
     *        passe son épaisseur ÉCRAN constante convertie — miroir
     *        `4 / S.z`).
     */
    public fun emplacement(
        vues: List<InfoVueRendu>,
        cible: CibleDepot,
        densiteDpi: Int,
        epaisseurPx: Float,
    ): RectangleDepot? {
        if (cible.indiceParent !in vues.indices) return null
        val parent = vues[cible.indiceParent]
        if (parent.droite <= parent.gauche || parent.bas <= parent.haut) return null
        val enfants = vues.filter { it.indiceParent == cible.indiceParent }
        val pxParDp = densiteEffective(densiteDpi).toFloat() / DENSITE_REFERENCE
        val retrait = RETRAIT_DP * pxParDp
        val longueurMin = LONGUEUR_MIN_DP * pxParDp
        val demiEpaisseur = epaisseurPx / 2f
        val horizontal = parent.orientation == InfoVueRendu.ORIENTATION_HORIZONTALE

        val position: Float =
            if (enfants.isEmpty()) {
                if (horizontal) (parent.gauche + parent.droite) / 2f else (parent.haut + parent.bas) / 2f
            } else if (horizontal) {
                positionHorizontale(enfants, cible.index, pxParDp)
            } else {
                positionVerticale(enfants, cible.index, pxParDp)
            }

        return if (horizontal) {
            RectangleDepot(
                gauche = position - demiEpaisseur,
                haut = parent.haut + retrait,
                droite = position + demiEpaisseur,
                bas = max(parent.haut + retrait + longueurMin, parent.bas - retrait),
            )
        } else {
            RectangleDepot(
                gauche = parent.gauche + retrait,
                haut = position - demiEpaisseur,
                droite = max(parent.gauche + retrait + longueurMin, parent.droite - retrait),
                bas = position + demiEpaisseur,
            )
        }
    }

    /** Libellé du conteneur visé (miroir `'Dans ' + label(par)` — pill violette). */
    public fun libelleCible(
        vues: List<InfoVueRendu>,
        cible: CibleDepot,
    ): String {
        val parent = vues.getOrNull(cible.indiceParent) ?: return ""
        return "Dans " + libelle(parent)
    }

    /**
     * Libellé de la barre d'insertion (miroir `'+ ' + dv.w.name + ' · ' +
     * (t.index + 1) + '/' + (par.children.length + 1)`).
     */
    public fun libelleInsertion(
        vues: List<InfoVueRendu>,
        cible: CibleDepot,
        nomWidget: String,
    ): String {
        val parent = vues.getOrNull(cible.indiceParent) ?: return ""
        val nombre = vues.count { it.indiceParent == cible.indiceParent } + 1
        return "+ $nomWidget · ${cible.index + 1}/$nombre"
    }

    /** Libellé d'une vue (miroir `label(v)` : « Classe · @+id/id » ; sans id, la classe seule). */
    private fun libelle(vue: InfoVueRendu): String {
        val idNom = vue.idRes.substringAfterLast('/')
        return if (idNom.isEmpty()) vue.classe else "${vue.classe} · @+id/$idNom"
    }

    /**
     * La position HORIZONTALE de la barre (miroir `slotRect` branche
     * `horiz`) : milieu de l'écart entre le frère précédent et le frère
     * visé ; en début (marge gauche du premier frère absorbée à moitié) ;
     * en fin (bord droit du dernier frère + 4 px) ; conteneur vide →
     * centre (traité par l'appelant).
     */
    private fun positionHorizontale(
        enfants: List<InfoVueRendu>,
        index: Int,
        pxParDp: Float,
    ): Float {
        if (index == 0) {
            return enfants[0].gauche - min(enfants[0].margesGauche.toFloat(), ABSORPTION_DEBUT_DP * pxParDp) / 2f
        }
        if (index >= enfants.size) {
            return enfants.last().droite + RESIDUEL_FIN_HORIZONTAL_DP * pxParDp
        }
        return (enfants[index - 1].droite + enfants[index].gauche) / 2f
    }

    /**
     * La position VERTICALE de la barre (miroir `slotRect` branche
     * verticale) : milieu de l'écart entre le frère précédent et le frère
     * visé ; en début (marge haute du premier frère absorbée à moitié) ;
     * en fin (marge basse du dernier frère absorbée à moitié + 2 px) ;
     * conteneur vide → centre (traité par l'appelant).
     */
    private fun positionVerticale(
        enfants: List<InfoVueRendu>,
        index: Int,
        pxParDp: Float,
    ): Float {
        if (index == 0) {
            return enfants[0].haut - min(enfants[0].margesHaut.toFloat(), ABSORPTION_DEBUT_DP * pxParDp) / 2f
        }
        if (index >= enfants.size) {
            val dernier = enfants.last()
            val absorption = min(dernier.margesBas.toFloat(), ABSORPTION_FIN_DP * pxParDp) / 2f
            return dernier.bas + absorption + RESIDUEL_FIN_VERTICAL_DP * pxParDp
        }
        return (enfants[index - 1].bas + enfants[index].haut) / 2f
    }

    /** Conversion dp → px du profil (repli mdpi — le calque est un dessin, jamais une donnée affirmée). */
    private fun densiteEffective(densiteDpi: Int): Int = if (densiteDpi > 0) densiteDpi else DENSITE_REPLI
}
