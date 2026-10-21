package jo.codeide.preview.xml

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LES PLANS D'INSERTION PAR FAMILLE (PE12d — `docs/PREVIEW_PARENTS.md` § 6,
 * ADR 0135 D3, miroir maquette § 16 : `defSize`, `cellAt`, `cellRect`,
 * `framePlan`, `relPlanSide`, `relPlanParent`, `relPlanPointer`, `relGhost`).
 *
 * « Le dépôt choisit des ATTRIBUTS, pas un index » : à partir de l'arbre
 * aplati du rendu ([InfoVueRendu], bornes en px bitmap) et d'un point ou
 * d'une cellule, cet objet PUR dérive le [Plan] — les attributs à écrire
 * (`android:layout_below`…), les MARGES (dp, multiples de 8, marge par
 * défaut 16 dp — D6 confirmée), le RECTANGLE FANTÔME du déposé (pour le
 * repère du calque) et le LIBELLÉ du repère (miroir du `plan.text` de la
 * maquette, réutilisé par le résumé du popup — patron [ZonesDepot] : le
 * module pur compose les pills du canvas, l'UI locale ses groupes).
 *
 * L'application n'ÉMULE PAS la mise en page (ADR 0135 D2 : LayoutLib
 * reste la vérité du rendu) : le plan ne sert qu'à CHOISIR les attributs
 * et DESSINER les repères. Une interaction reste UNE édition
 * ([InsertionXml.inserer] + [InsertionXml.ajouterAttributs] — § 4.3).
 *
 * Les coordonnées d'ENTRÉE (`x`, `y`, [PointDp]) sont en px bitmap du
 * rendu ; les MARGES de [Plan] sont en dp du profil (conversion par
 * [densiteDpi], repli mdpi — le repère est un dessin, pas une donnée
 * affirmée) ; le fantôme [Plan.fantome] est en px bitmap.
 *
 * FONCTIONS PURES, SANS ANDROID — testées en JVM (`PlanInsertionFamilleTest`,
 * les 12 scénarios en or de `docs/mockups/ref/parents/insertions_or.json`).
 *
 * Exemptions detekt ciblées (règle 16) : TooManyFunctions, LargeClass —
 * la surface de dérivation (quatre plans + géométrie de grille + fantômes)
 * vit au COMPLET dans ce seul objet, contrat de la spec § 4.1 (même
 * décision que [InsertionXml], même précédent que [CatalogueAttributs]).
 */
@Suppress("TooManyFunctions", "LargeClass")
public object PlanInsertionFamille {
    /** Le côté d'une règle `RelativeLayout` vers un frère (miroir `SIDE_ATTR`). */
    public enum class Cote {
        /** `layout_below` — le déposé passe SOUS la référence. */
        DESSOUS,

        /** `layout_above` — le déposé passe AU-DESSUS de la référence. */
        DESSUS,

        /** `layout_toEndOf` — le déposé passe À DROITE de la référence. */
        DROITE,

        /** `layout_toStartOf` — le déposé passe À GAUCHE de la référence. */
        GAUCHE,
    }

    /** Une cellule de la grille 3 × 3 (miroir `{r, c}` — ligne 0 en HAUT, colonne 0 au DÉBUT). */
    public data class Cellule(
        val ligne: Int,
        val colonne: Int,
    )

    /**
     * Le DÉPOSÉ (miroir du widget `w` de la maquette) : l'extrait de la
     * palette (TAILLE du déposé, § 6.4), son identifiant catalogue
     * (`fab`, `rv`, `nsv` — ancre et comportement lié) et la densité du
     * profil (conversions dp ↔ px). Les quatre plans le reçoivent EN UN
     * SEUL paramètre (la signature de la spec § 4.2 est « indicative, à
     * ajuster à la dette du dépôt »).
     */
    public data class Depose(
        /** L'extrait XML du widget déposé (taille du fantôme). */
        public val extrait: String? = null,
        /** L'identifiant catalogue du widget (`fab`, `rv`, `nsv`…). */
        public val idWidget: String? = null,
        /** La densité du profil de rendu (≤ 0 → repli mdpi). */
        public val densiteDpi: Int = 0,
    )

    /** Un point du rendu, en px bitmap (miroir des `{x, y}` de la maquette). */
    public data class PointDp(
        val x: Float,
        val y: Float,
    )

    /**
     * Les MARGES à écrire sur le déposé, en dp (§ 6.3-6.4 : multiples de 8,
     * marge par défaut 16 dp — D6). Zéro = côté NON touché : la conversion
     * [versAttributs] omet les côtés nuls et condense début = fin en
     * `layout_marginHorizontal` (convention du fichier d'or S9).
     */
    public data class Marges(
        public var debut: Int = 0,
        public var haut: Int = 0,
        public var fin: Int = 0,
        public var bas: Int = 0,
    ) {
        /** Les paires attribut/valeur des marges, dans l'ordre du fichier d'or (haut, bas, horizontal/start, end). */
        public fun versAttributs(): List<Pair<String, String>> =
            buildList {
                if (haut > 0) add("android:layout_marginTop" to "${haut}dp")
                if (bas > 0) add("android:layout_marginBottom" to "${bas}dp")
                if (debut > 0 && debut == fin) {
                    add("android:layout_marginHorizontal" to "${debut}dp")
                } else {
                    if (debut > 0) add("android:layout_marginStart" to "${debut}dp")
                    if (fin > 0) add("android:layout_marginEnd" to "${fin}dp")
                }
            }
    }

    /** Le GENRE d'un plan (miroir `kind` de la maquette) — pilote le résumé du popup. */
    public enum class GenrePlan {
        /** Cellule d'une pile `FrameLayout`/`CoordinatorLayout` (`framePlan`). */
        PILE,

        /** Règle vers un frère `RelativeLayout` (`relPlanSide`). */
        REGLE,

        /** Ancrage au parent `RelativeLayout` (`relPlanParent`). */
        PARENT_RELATIF,
    }

    /**
     * UN plan d'insertion (miroir du `{kind, attrs, mg, ghost, text}` de la
     * maquette) : les attributs à injecter dans l'extrait AVANT
     * [InsertionXml.inserer] (les marges voyagent à part, [marges]), le
     * rectangle fantôme du déposé, le libellé du repère et les DONNÉES du
     * repère (cellule, ancre, référence) que [VuePreviewXml] dessine.
     *
     * @property attributs les règles et gravités, noms QUALIFIÉS
     *           (`android:layout_below` à `@id/bio`), SANS les marges.
     * @property marges les marges dp du déposé (0 = côté omis).
     * @property fantome le rectangle du déposé (px bitmap), pour le calque
     *           — null quand la maquette n'en dessine pas (`framePlan`
     *           sans ancre).
     * @property libelle le texte du repère et du résumé (miroir `plan.text`).
     * @property genre le genre du plan (miroir `kind`) — résumé du popup.
     * @property cellule la cellule 3 × 3 du plan (repère violet doux).
     * @property indiceAncre l'indice de la vue ANCRE (dépôt d'un
     *           `FloatingActionButton` sur un `CoordinatorLayout`).
     * @property indiceReference l'indice du FRÈRE visé par la règle
     *           (repère + trait de règle du déposé).
     * @property traitRegle le repère porte le trait ghost → frère (règle
     *           `RelativeLayout` — miroir `relLine` de `drawDrop`).
     */
    public data class Plan(
        public val attributs: List<Pair<String, String>>,
        public val marges: Marges,
        public val fantome: ZonesDepot.RectangleDepot?,
        public val libelle: String,
        public val genre: GenrePlan,
        public val cellule: Cellule? = null,
        public val indiceAncre: Int? = null,
        public val indiceReference: Int? = null,
        public val traitRegle: Boolean = false,
    )

    // ── Constantes nommées (§ 4.2 du brief, miroir ADH/MGS/r8/G9) ──────

    /** Adhérence maximale d'un dépôt `RelativeLayout` à un frère, en dp (miroir `ADH = 28`). */
    public const val ADHERENCE_DP: Int = 28

    /** Marge par défaut du dépôt, en dp (D6 confirmée — grille de 8 dp). */
    public const val MARGE_DEFAUT_DP: Int = 16

    /** Arrondi des marges calculées, en dp (miroir `r8`). */
    public const val ARRONDI_MARGE_DP: Int = 8

    /** Plafond des marges calculées, en dp (miroir `Math.min(64, …)`). */
    public const val PLAFOND_MARGE_DP: Int = 64

    /** Tolérance d'alignement croisé / de centrage sur un axe, en dp (miroir `± 16`). */
    public const val TOLERANCE_ALIGNEMENT_DP: Int = 16

    /** Largeur par défaut d'un déposé `wrap_content`, en dp (miroir `defSize` : 96). */
    public const val LARGEUR_DEFAUT_DP: Int = 96

    /** Hauteur par défaut d'un déposé `wrap_content`, en dp (miroir `defSize` : 40). */
    public const val HAUTEUR_DEFAUT_DP: Int = 40

    /** Largeur minimale d'un déposé `match_parent`, en dp (miroir `Math.max(24, …)`). */
    public const val LARGEUR_MINIMALE_DP: Int = 24

    /** Densité de référence des conversions dp ↔ px (mdpi). */
    private const val DENSITE_REFERENCE: Int = 160

    /** La gravité de chaque cellule 3 × 3 (miroir `G9`). */
    private val G9: List<List<String>> =
        listOf(
            listOf("start|top", "center_horizontal|top", "end|top"),
            listOf("start|center_vertical", "center", "end|center_vertical"),
            listOf("start|bottom", "center_horizontal|bottom", "end|bottom"),
        )

    /** Le libellé français de chaque cellule (miroir `G9TXT` — pills du canvas). */
    private val G9_TXT: List<List<String>> =
        listOf(
            listOf("haut, début", "haut, centre", "haut, fin"),
            listOf("centre, début", "centre", "centre, fin"),
            listOf("bas, début", "bas, centre", "bas, fin"),
        )

    /** Les libellés français des côtés (miroir `SIDE_TXT`). */
    private val TEXTE_COTE: Map<Cote, String> =
        mapOf(
            Cote.DESSOUS to "Sous",
            Cote.DESSUS to "Au-dessus de",
            Cote.DROITE to "À droite de",
            Cote.GAUCHE to "À gauche de",
        )

    /** L'attribut de règle de chaque côté (miroir `SIDE_ATTR`). */
    private val REGLE_COTE: Map<Cote, String> =
        mapOf(
            Cote.DESSOUS to "android:layout_below",
            Cote.DESSUS to "android:layout_above",
            Cote.DROITE to "android:layout_toEndOf",
            Cote.GAUCHE to "android:layout_toStartOf",
        )

    /** Le comportement de défilement lié à l'AppBar (§ 6.3 — valeur LITTÉRALE, ressource du framework). */
    private const val COMPORTEMENT_APPBAR: String = "@string/appbar_scrolling_view_behavior"

    /** L'identifiant catalogue d'un widget défilant (miroir `w.id === 'rv' || w.id === 'nsv'`). */
    private val ID_DEFILANTS: Set<String> = setOf("rv", "nsv")

    /** L'identifiant catalogue du bouton d'action flottant (miroir `w.id === 'fab'`). */
    public const val ID_FAB: String = "fab"

    // ── Géométrie de la grille (miroir cellAt / cellRect) ───────────────

    /**
     * La CELLULE 3 × 3 sous le point (miroir `cellAt`) : le rectangle
     * INTÉRIEUR du parent (moins son padding — [avecPadding] faux pour la
     * vue ANCRE d'un `FloatingActionButton`, miroir `cellAt(q, PAD0, …)`)
     * est divisé en 3 × 3 ; les bornes sont COERCÉES (un point hors
     * rectangle reste dans la grille). `null` = parent inconnu.
     */
    public fun celluleSous(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        x: Float,
        y: Float,
        avecPadding: Boolean = true,
    ): Cellule? {
        val parent = vues.getOrNull(indiceParent) ?: return null
        val retraitGauche = if (avecPadding) parent.paddingGauche else 0
        val retraitHaut = if (avecPadding) parent.paddingHaut else 0
        val retraitDroite = if (avecPadding) parent.paddingDroite else 0
        val retraitBas = if (avecPadding) parent.paddingBas else 0
        val gauche = (parent.gauche + retraitGauche).toFloat()
        val haut = (parent.haut + retraitHaut).toFloat()
        val largeur = max(MIN_COTE_GRILLE, parent.largeurPx - retraitGauche - retraitDroite).toFloat()
        val hauteur = max(MIN_COTE_GRILLE, parent.hauteurPx - retraitHaut - retraitBas).toFloat()
        return Cellule(
            ligne = ligneColonne(y, haut, hauteur),
            colonne = ligneColonne(x, gauche, largeur),
        )
    }

    /**
     * Le RECTANGLE d'une cellule (miroir `cellRect`, en px bitmap) : sur le
     * rectangle intérieur du parent (moins le padding), ou SANS padding sur
     * la vue [indiceAncre] (miroir `cellRect(S.b[h.anchor], PAD0, …)`).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — le rectangle d'une cellule sur le parent ou l'ancre ; chaque
     */
    @Suppress("ReturnCount")
    public fun rectangleCellule(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        cellule: Cellule,
        indiceAncre: Int? = null,
    ): ZonesDepot.RectangleDepot? {
        val support = vues.getOrNull(indiceAncre ?: indiceParent) ?: return null
        val sansPadding = indiceAncre != null
        val retraitGauche = if (sansPadding) 0 else support.paddingGauche
        val retraitHaut = if (sansPadding) 0 else support.paddingHaut
        val retraitDroite = if (sansPadding) 0 else support.paddingDroite
        val retraitBas = if (sansPadding) 0 else support.paddingBas
        val gauche = (support.gauche + retraitGauche).toFloat()
        val haut = (support.haut + retraitHaut).toFloat()
        val largeur = (support.largeurPx - retraitGauche - retraitDroite).toFloat()
        val hauteur = (support.hauteurPx - retraitHaut - retraitBas).toFloat()
        if (largeur <= 0f || hauteur <= 0f) return null
        return ZonesDepot.RectangleDepot(
            gauche = gauche + cellule.colonne * largeur / TROIS,
            haut = haut + cellule.ligne * hauteur / TROIS,
            droite = gauche + (cellule.colonne + 1) * largeur / TROIS,
            bas = haut + (cellule.ligne + 1) * hauteur / TROIS,
        )
    }

    // ── Les quatre plans (miroir framePlan / relPlanSide / relPlanParent / relPlanPointer) ──

    /**
     * Le plan d'une CELLULE dans un parent `FrameLayout` ou
     * `CoordinatorLayout` (miroir `framePlan`) : gravité de la cellule
     * (OMISE pour la cellule par défaut `start|top`), marges de 16 dp sur
     * chaque bord que la gravité touche ; enfant ajouté au PREMIER PLAN.
     *
     * Cas particuliers (§ 6.3) :
     * - [indiceAncre] (dépôt d'un `FloatingActionButton` sur une vue frère) :
     *   `app:layout_anchor` + `app:layout_anchorGravity` (ÉCRITE même pour
     *   `start|top`), marges 16 dp sur les QUATRE côtés, fantôme = point
     *   d'ancrage − moitié de la taille, recadré à 16 dp des bords du
     *   parent (le même calcul que `constrainChildRect`) ;
     * - widget DÉFILANT (`RecyclerView`, `NestedScrollView`) dans un
     *   `CoordinatorLayout` qui contient une `AppBarLayout` :
     *   `app:layout_behavior` posé, AUCUNE marge, libellé augmenté de
     *   « · défilement lié à l'AppBar ».
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque absence (contexte,
     * dégénéré) est une issue DISTINCTE du refus (X4).
     */
    @Suppress("ReturnCount")
    public fun cellule(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        cellule: Cellule,
        indiceAncre: Int? = null,
        depose: Depose = Depose(),
    ): Plan? {
        val contexte = contexteDe(vues, indiceParent, depose.densiteDpi) ?: return null
        val gravite = G9[cellule.ligne.coerceIn(0, 2)][cellule.colonne.coerceIn(0, 2)]
        val parties = gravite.split('|')
        if (indiceAncre != null) return planAncre(contexte, cellule, indiceAncre, gravite, depose.extrait)
        val attributs = mutableListOf<Pair<String, String>>()
        if (gravite != GRAVITE_DEFAUT) attributs.add("android:layout_gravity" to gravite)
        var marges = margesDeGravite(parties)
        var suffixe = if (cellule.ligne == 0 && cellule.colonne == 0) " ($TEXTE_DEFAUT)" else ""
        if (contexte.familleParent == FamilleParent.COORDINATOR && depose.idWidget in ID_DEFILANTS &&
            contexte.contientAppbar()
        ) {
            attributs.add("app:layout_behavior" to COMPORTEMENT_APPBAR)
            marges = Marges()
            suffixe += " · $TEXTE_COMPORTEMENT"
        }
        return Plan(
            attributs = attributs,
            marges = marges,
            fantome = null,
            libelle = G9_TXT[cellule.ligne][cellule.colonne] + suffixe,
            genre = GenrePlan.PILE,
            cellule = cellule,
        )
    }

    /**
     * Le plan d'une RÈGLE vers un frère `RelativeLayout` (miroir
     * `relPlanSide`) : règle principale [cote] vers [indiceReference] +
     * marge [margeDp] du côté de la règle + ALIGNEMENT CROISÉ si la
     * CROIX du déposé ([croix] = coin début-haut du fantôme, dépôt) est
     * à ≤ 16 dp d'un bord du frère (`alignStart`/`alignEnd`, sinon
     * `alignParentStart` + marge arrondie à 8) ; sans croix (popup),
     * l'alignement DÉFAUT suit le bord début/haut du frère.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList, ReturnCount —
     * la signature est le contrat de la spec § 4.2 (`cote(parent, reference,
     * cote, margeDp, croix?, densiteDpi)`) et chaque garde (contexte,
     * référence) est une issue DISTINCTE du refus (X4), comme
     * [InfoVueRendu].
     */
    @Suppress("LongParameterList", "ReturnCount")
    public fun cote(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        indiceReference: Int,
        cote: Cote,
        margeDp: Int,
        croix: PointDp? = null,
        depose: Depose = Depose(),
    ): Plan? {
        val contexte = contexteDe(vues, indiceParent, depose.densiteDpi) ?: return null
        val reference = vues.getOrNull(indiceReference) ?: return null
        val idReference = idAffichable(reference) ?: return null
        val marges =
            when (cote) {
                Cote.DESSOUS -> Marges(haut = margeDp)
                Cote.DESSUS -> Marges(bas = margeDp)
                Cote.DROITE -> Marges(debut = margeDp)
                Cote.GAUCHE -> Marges(fin = margeDp)
            }
        val attributs = mutableListOf(REGLE_COTE.getValue(cote) to "@id/$idReference")
        if (cote == Cote.DESSOUS || cote == Cote.DESSUS) {
            attributs.addAll(alignementCroiseVertical(contexte, reference, croix, marges))
        } else {
            attributs.addAll(alignementCroiseHorizontal(contexte, reference, croix, marges))
        }
        val plan =
            Plan(
                attributs = attributs,
                marges = marges,
                fantome = null,
                libelle = "${TEXTE_COTE.getValue(cote)} @id/$idReference · marge $margeDp dp",
                genre = GenrePlan.REGLE,
                indiceReference = indiceReference,
                traitRegle = true,
            )
        return plan.copy(fantome = fantomeRegle(contexte, plan, depose.extrait))
    }

    /**
     * Le plan d'un ANCRAGE AU PARENT `RelativeLayout` (miroir
     * `relPlanParent`) : la cellule 3 × 3 donne `alignParentStart/End/Top/Bottom`
     * + marge (distance réelle du déposé arrondie à 8 pour un dépôt,
     * [m[m[margeDp] du popup), le CENTRE d'un axe à ± 16 dp centre sur cet axe
     * (`centerHorizontal`, `centerVertical`, les deux = `centerInParent`).
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod — les
     * QUATRE bords + les deux centres de la cellule sont le miroir exact
     * de `relPlanParent` (la complexité EST le contrat, comme
     * [InfoVueRendu.equals]).
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
    public fun versParent(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        cellule: Cellule,
        margeDp: Int,
        auPoint: PointDp? = null,
        depose: Depose = Depose(),
    ): Plan? {
        val contexte = contexteDe(vues, indiceParent, depose.densiteDpi) ?: return null
        val taille = tailleDepose(contexte, depose.extrait)
        val attributs = mutableListOf<Pair<String, String>>()
        val marges = Marges()
        if (cellule.colonne == 0) {
            attributs.add("android:layout_alignParentStart" to "true")
            marges.debut = margeArrondie(contexte, auPoint?.x?.minus(contexte.interieurGauche) ?: margeDp.toFloat())
        } else if (cellule.colonne == 2) {
            attributs.add("android:layout_alignParentEnd" to "true")
            marges.fin =
                margeArrondie(
                    contexte,
                    (auPoint?.let { contexte.interieurDroite - it.x + taille.largeurPx } ?: margeDp.toFloat()),
                )
        }
        if (cellule.ligne == 0) {
            attributs.add("android:layout_alignParentTop" to "true")
            marges.haut = margeArrondie(contexte, auPoint?.y?.minus(contexte.interieurHaut) ?: margeDp.toFloat())
        } else if (cellule.ligne == 2) {
            attributs.add("android:layout_alignParentBottom" to "true")
            marges.bas =
                margeArrondie(
                    contexte,
                    (auPoint?.let { contexte.interieurBas - it.y + taille.hauteurPx } ?: margeDp.toFloat()),
                )
        }
        if (cellule.colonne == 1 && cellule.ligne == 1) {
            attributs.add("android:layout_centerInParent" to "true")
        } else {
            if (cellule.colonne == 1) attributs.add("android:layout_centerHorizontal" to "true")
            if (cellule.ligne == 1) attributs.add("android:layout_centerVertical" to "true")
        }
        val plan =
            Plan(
                attributs = attributs,
                marges = marges,
                fantome = null,
                libelle = "$TEXTE_PARENT${G9_TXT[cellule.ligne][cellule.colonne]}",
                genre = GenrePlan.PARENT_RELATIF,
                cellule = cellule,
            )
        return plan.copy(fantome = fantomeRegle(contexte, plan, depose.extrait))
    }

    /**
     * Le plan d'un DÉPÔT `RelativeLayout` au pointeur (miroir
     * `relPlanPointer` — le pointeur est le CENTRE de la vue déposée) :
     * ADHÉRENCE au frère le plus proche (distance du point au rectangle
     * ≤ 28 dp) → [cote] du côté où le point dépasse le plus, marge =
     * distance du déposé au frère arrondie à 8, plafonnée à 64, et
     * alignement croisé réel ; SINON ancrage au parent par cellule 3 × 3,
     * le centre d'un axe (± 16 dp) recentrant sur cet axe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est une
     * issue DISTINCTE du refus (X4).
     */
    @Suppress("ReturnCount")
    public fun auPointeur(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        x: Float,
        y: Float,
        depose: Depose = Depose(),
    ): Plan? {
        val contexte = contexteDe(vues, indiceParent, depose.densiteDpi) ?: return null
        val taille = tailleDepose(contexte, depose.extrait)
        val gauche = x - taille.largeurPx / DEUX
        val haut = y - taille.hauteurPx / DEUX
        val frere = frereLePlusProche(contexte, x, y)
        if (frere != null && frere.second <= contexte.pxParDp * ADHERENCE_DP) {
            val reference = contexte.enfants[frere.first]
            val cote = coteLePlusProche(reference, x, y)
            val marge = min(PLAFOND_MARGE_DP, arrondi8(contexte, ecartDuDepose(reference, cote, gauche, haut, taille)))
            return cote(
                vues,
                indiceParent,
                contexte.indicesEnfants[frere.first],
                cote,
                marge,
                PointDp(gauche, haut),
                depose,
            )
        }
        val cellule = celluleCentree(contexte, x, y)
        return versParent(vues, indiceParent, cellule, MARGE_DEFAUT_DP, PointDp(gauche, haut), depose)
    }

    // ── Détails des plans (purs, privés) ────────────────────────────────

    /** Le plan d'un `FloatingActionButton` ANCRÉ (miroir `framePlan` branche `anchorRid`). */
    private fun planAncre(
        contexte: Contexte,
        cellule: Cellule,
        indiceAncre: Int,
        gravite: String,
        extrait: String?,
    ): Plan {
        val ancre = contexte.vues[indiceAncre]
        val idAncre = idAffichable(ancre) ?: SANS_ID
        val taille = tailleDepose(contexte, extrait)
        val parties = gravite.split('|')
        val pointAncre = pointDAncrage(ancre, parties)
        val fantome = fantomeAncre(contexte, pointAncre, taille)
        return Plan(
            attributs =
                listOf(
                    "app:layout_anchor" to "@id/$idAncre",
                    "app:layout_anchorGravity" to gravite,
                ),
            marges =
                Marges(
                    debut = MARGE_DEFAUT_DP,
                    haut = MARGE_DEFAUT_DP,
                    fin = MARGE_DEFAUT_DP,
                    bas = MARGE_DEFAUT_DP,
                ),
            fantome = fantome,
            libelle = "ancré à @id/$idAncre · ${G9_TXT[cellule.ligne][cellule.colonne]}",
            genre = GenrePlan.PILE,
            cellule = cellule,
            indiceAncre = indiceAncre,
        )
    }

    /** Les marges 16 dp des bords TOUCHÉS par la gravité (miroir les quatre `if (parts.includes(...))`). */
    private fun margesDeGravite(parties: List<String>): Marges =
        Marges(
            debut = if ("start" in parties) MARGE_DEFAUT_DP else 0,
            haut = if ("top" in parties) MARGE_DEFAUT_DP else 0,
            fin = if ("end" in parties) MARGE_DEFAUT_DP else 0,
            bas = if ("bottom" in parties) MARGE_DEFAUT_DP else 0,
        )

    /**
     * L'alignement croisé VERTICAL d'une règle `below`/`above` (miroir
     * `relPlanSide` branche `vert`) : fin du frère à ≤ 16 dp → `alignEnd` ;
     * début du frère à ≤ 16 dp (ou pas de croix) → `alignStart` ; sinon
     * `alignParentStart` + marge au parent arrondie à 8.
     */
    private fun alignementCroiseVertical(
        contexte: Contexte,
        reference: InfoVueRendu,
        croix: PointDp?,
        marges: Marges,
    ): List<Pair<String, String>> {
        val idReference = idAffichable(reference) ?: return emptyList()
        val tolerance = contexte.pxParDp * TOLERANCE_ALIGNEMENT_DP
        val cx = croix?.x
        val largeur = tailleDepose(contexte, null).largeurPx
        return when {
            cx != null && abs(
                cx - (reference.droite - largeur),
            ) <= tolerance && abs(cx - reference.gauche) > tolerance -> {
                listOf("android:layout_alignEnd" to "@id/$idReference")
            }

            cx == null || abs(cx - reference.gauche) <= tolerance -> {
                listOf("android:layout_alignStart" to "@id/$idReference")
            }

            else -> {
                marges.debut = arrondi8(contexte, cx - contexte.interieurGauche)
                listOf("android:layout_alignParentStart" to "true")
            }
        }
    }

    /** L'alignement croisé HORIZONTAL d'une règle `toEndOf`/`toStartOf` (miroir branche horizontale). */
    private fun alignementCroiseHorizontal(
        contexte: Contexte,
        reference: InfoVueRendu,
        croix: PointDp?,
        marges: Marges,
    ): List<Pair<String, String>> {
        val idReference = idAffichable(reference) ?: return emptyList()
        val tolerance = contexte.pxParDp * TOLERANCE_ALIGNEMENT_DP
        val cy = croix?.y
        val hauteur = tailleDepose(contexte, null).hauteurPx
        return when {
            cy != null && abs(cy - (reference.bas - hauteur)) <= tolerance && abs(cy - reference.haut) > tolerance -> {
                listOf("android:layout_alignBottom" to "@id/$idReference")
            }

            cy == null || abs(cy - reference.haut) <= tolerance -> {
                listOf("android:layout_alignTop" to "@id/$idReference")
            }

            else -> {
                marges.haut = arrondi8(contexte, cy - contexte.interieurHaut)
                listOf("android:layout_alignParentTop" to "true")
            }
        }
    }

    /**
     * Le FANTÔME d'un plan `RelativeLayout` (miroir `relGhost`) : le
     * rectangle du déposé reconstitué depuis les règles du plan — mêmes
     * sémantiques que le tri `DependencyGraph` (marges de l'ANCRE et de la
     * vue pour `below`/`toEndOf`…, marge de la vue seule pour `align*`).
     */
    private fun fantomeRegle(
        contexte: Contexte,
        plan: Plan,
        extrait: String?,
    ): ZonesDepot.RectangleDepot? {
        val taille = tailleDepose(contexte, extrait)
        val margesPx = margesEnPx(contexte, plan.marges)
        val (gauche, droite) = bornesHorizontales(contexte, plan.attributs, margesPx)
        val (haut, bas) = bornesVerticales(contexte, plan.attributs, margesPx)
        val centreHorizontal = attributBooleen(plan, "layout_centerInParent", "layout_centerHorizontal")
        val centreVertical = attributBooleen(plan, "layout_centerInParent", "layout_centerVertical")
        var gaucheCalculee = gauche
        var hautCalcule = haut
        if (gaucheCalculee == null && droite != null) gaucheCalculee = droite - taille.largeurPx
        if (gaucheCalculee == null) {
            gaucheCalculee =
                if (centreHorizontal) {
                    centre(contexte.interieurGauche, contexte.interieurDroite, taille.largeurPx)
                } else {
                    contexte.interieurGauche + margesPx.debut
                }
        }
        if (hautCalcule == null && bas != null) hautCalcule = bas - taille.hauteurPx
        if (hautCalcule == null) {
            hautCalcule =
                if (centreVertical) {
                    centre(contexte.interieurHaut, contexte.interieurBas, taille.hauteurPx)
                } else {
                    contexte.interieurHaut + margesPx.haut
                }
        }
        return ZonesDepot.RectangleDepot(
            gaucheCalculee,
            hautCalcule,
            gaucheCalculee + taille.largeurPx,
            hautCalcule + taille.hauteurPx,
        )
    }

    /**
     * Les bornes HORIZONTALES du fantôme (miroir `relGhost`) : les règles
     * `toEndOf`/`toStartOf`/`align*`/`alignParent*` posent la borne de
     * GAUCHE ou de DROITE (marges de l'ANCRE et de la vue pour les règles
     * de côté, marge de la vue seule pour les alignements).
     */
    private fun bornesHorizontales(
        contexte: Contexte,
        attributs: List<Pair<String, String>>,
        marges: MargesPx,
    ): Pair<Float?, Float?> {
        var gauche: Float? = null
        var droite: Float? = null
        for ((nom, valeur) in attributs) {
            val reference = referenceDe(contexte, valeur)
            when (nom.substringAfter(':')) {
                "layout_toEndOf" -> {
                    if (reference !=
                        null
                    ) {
                        gauche = reference.droite + reference.margesDroite + marges.debut
                    }
                }

                "layout_toStartOf" -> {
                    if (reference !=
                        null
                    ) {
                        droite = reference.gauche - reference.margesGauche - marges.fin
                    }
                }

                "layout_alignStart" -> {
                    if (reference != null) gauche = reference.gauche + marges.debut
                }

                "layout_alignEnd" -> {
                    if (reference != null) droite = reference.droite - marges.fin
                }

                "layout_alignParentStart" -> {
                    gauche = contexte.interieurGauche + marges.debut
                }

                "layout_alignParentEnd" -> {
                    droite = contexte.interieurDroite - marges.fin
                }
            }
        }
        return gauche to droite
    }

    /** Les bornes VERTICALES du fantôme (miroir `relGhost`, mêmes sémantiques que [bornesHorizontales]). */
    private fun bornesVerticales(
        contexte: Contexte,
        attributs: List<Pair<String, String>>,
        marges: MargesPx,
    ): Pair<Float?, Float?> {
        var haut: Float? = null
        var bas: Float? = null
        for ((nom, valeur) in attributs) {
            val reference = referenceDe(contexte, valeur)
            when (nom.substringAfter(':')) {
                "layout_below" -> if (reference != null) haut = reference.bas + reference.margesBas + marges.haut
                "layout_above" -> if (reference != null) bas = reference.haut - reference.margesHaut - marges.bas
                "layout_alignTop" -> if (reference != null) haut = reference.haut + marges.haut
                "layout_alignBottom" -> if (reference != null) bas = reference.bas - marges.bas
                "layout_alignParentTop" -> haut = contexte.interieurHaut + marges.haut
                "layout_alignParentBottom" -> bas = contexte.interieurBas - marges.bas
            }
        }
        return haut to bas
    }

    /** Le fantôme d'un déposé ANCRÉ : point d'ancrage − moitié de la taille,
     * recadré à 16 dp (miroir `constrainChildRect`). */
    private fun fantomeAncre(
        contexte: Contexte,
        pointAncre: PointDp,
        taille: TailleDepose,
    ): ZonesDepot.RectangleDepot {
        val marge = contexte.pxParDp * MARGE_DEFAUT_DP
        val gauche =
            (pointAncre.x - taille.largeurPx / DEUX)
                .coerceIn(contexte.interieurGauche + marge, contexte.interieurDroite - taille.largeurPx - marge)
        val haut =
            (pointAncre.y - taille.hauteurPx / DEUX)
                .coerceIn(contexte.interieurHaut + marge, contexte.interieurBas - taille.hauteurPx - marge)
        return ZonesDepot.RectangleDepot(gauche, haut, gauche + taille.largeurPx, haut + taille.hauteurPx)
    }

    /** Le point d'ancrage sur la vue ancre selon `anchorGravity` (miroir `gx`/`gy` de `framePlan`). */
    private fun pointDAncrage(
        ancre: InfoVueRendu,
        parties: List<String>,
    ): PointDp {
        val x =
            when {
                "end" in parties -> {
                    ancre.droite.toFloat()
                }

                "center_horizontal" in parties || "center" in parties -> {
                    centre(
                        ancre.gauche.toFloat(),
                        ancre.droite.toFloat(),
                        0f,
                    )
                }

                else -> {
                    ancre.gauche.toFloat()
                }
            }
        val y =
            when {
                "bottom" in parties -> {
                    ancre.bas.toFloat()
                }

                "center_vertical" in parties || "center" in parties -> {
                    centre(
                        ancre.haut.toFloat(),
                        ancre.bas.toFloat(),
                        0f,
                    )
                }

                else -> {
                    ancre.haut.toFloat()
                }
            }
        return PointDp(x, y)
    }

    // ── Adhérence et cellules du pointeur (miroir relPlanPointer) ──────

    /**
     * Le frère le plus PROCHE du point (miroir `best` : distance du point
     * au rectangle, 0 dedans) : paire (indice dans les enfants du parent,
     * distance en px), ou null.
     */
    private fun frereLePlusProche(
        contexte: Contexte,
        x: Float,
        y: Float,
    ): Pair<Int, Float>? {
        var meilleur: Pair<Int, Float>? = null
        for ((indice, enfant) in contexte.enfants.withIndex()) {
            if (enfant.droite <= enfant.gauche || enfant.bas <= enfant.haut) continue
            val dx = maxOf(enfant.gauche - x, 0f, x - enfant.droite)
            val dy = maxOf(enfant.haut - y, 0f, y - enfant.bas)
            val distance = hypot(dx, dy)
            if (meilleur == null || distance < meilleur.second) meilleur = indice to distance
        }
        return meilleur
    }

    /** Le côté où le point DÉPASSE le plus du frère (miroir `beyond` : dedans → le bord le plus proche). */
    private fun coteLePlusProche(
        reference: InfoVueRendu,
        x: Float,
        y: Float,
    ): Cote {
        val depassements =
            mapOf(
                Cote.DESSOUS to y - reference.bas,
                Cote.DESSUS to reference.haut - y,
                Cote.DROITE to x - reference.droite,
                Cote.GAUCHE to reference.gauche - x,
            )
        return depassements.entries
            .reduce { meilleur, courant ->
                if (courant.value >
                    meilleur.value
                ) {
                    courant
                } else {
                    meilleur
                }
            }.key
    }

    /** L'écart du DÉPOSÉ au frère selon le côté (miroir `gap` — peut être négatif, l'arrondi le ramène à 0). */
    private fun ecartDuDepose(
        reference: InfoVueRendu,
        cote: Cote,
        gauche: Float,
        haut: Float,
        taille: TailleDepose,
    ): Float =
        when (cote) {
            Cote.DESSOUS -> haut - reference.bas
            Cote.DESSUS -> reference.haut - (haut + taille.hauteurPx)
            Cote.DROITE -> gauche - reference.droite
            Cote.GAUCHE -> reference.gauche - (gauche + taille.largeurPx)
        }

    /**
     * La cellule d'ancrage au parent depuis le point (miroir `cellAt` +
     * recentrage ± 16 dp : le centre d'un axe FORCE la ligne/colonne 1,
     * une cellule centrale SANS centrage retombe sur le bord 0).
     */
    private fun celluleCentree(
        contexte: Contexte,
        x: Float,
        y: Float,
    ): Cellule {
        val cellule =
            celluleSous(contexte.vues, contexte.indiceParent, x, y) ?: Cellule(ligne = 0, colonne = 0)
        val tolerance = contexte.pxParDp * TOLERANCE_ALIGNEMENT_DP
        val axeHorizontal = abs(x - centre(contexte.interieurGauche, contexte.interieurDroite, 0f)) <= tolerance
        val axeVertical = abs(y - centre(contexte.interieurHaut, contexte.interieurBas, 0f)) <= tolerance
        return Cellule(
            ligne =
                if (axeVertical) {
                    1
                } else if (cellule.ligne == 1) {
                    0
                } else {
                    cellule.ligne
                },
            colonne =
                if (axeHorizontal) {
                    1
                } else if (cellule.colonne == 1) {
                    0
                } else {
                    cellule.colonne
                },
        )
    }

    // ── Briques (pures, privées) ────────────────────────────────────────

    /**
     * Le CONTEXTE d'un plan : le parent, ses enfants, sa famille (repli par
     * balise), le rectangle intérieur (moins le padding) et la conversion
     * dp ↔ px du profil. `null` = parent inconnu ou arbre vide.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est une
     * issue DISTINCTE du refus (X4).
     */
    @Suppress("ReturnCount")
    private fun contexteDe(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
        densiteDpi: Int,
    ): Contexte? {
        val parent = vues.getOrNull(indiceParent) ?: return null
        if (parent.droite <= parent.gauche || parent.bas <= parent.haut) return null
        return Contexte(vues, indiceParent, parent, densiteDpi)
    }

    /** Le contexte DÉRIVÉ d'un parent validé (constructeur privé — passer par [contexteDe]). */
    private class Contexte(
        val vues: List<InfoVueRendu>,
        val indiceParent: Int,
        val parent: InfoVueRendu,
        densiteDpi: Int,
    ) {
        val enfants: List<InfoVueRendu> = vues.filter { it.indiceParent == indiceParent }
        val indicesEnfants: List<Int> =
            vues.indices.filter {
                it != indiceParent &&
                    vues[it].indiceParent == indiceParent
            }
        val pxParDp: Float = (if (densiteDpi > 0) densiteDpi else DENSITE_REPLI).toFloat() / DENSITE_REFERENCE
        val interieurGauche: Float = (parent.gauche + parent.paddingGauche).toFloat()
        val interieurHaut: Float = (parent.haut + parent.paddingHaut).toFloat()
        val interieurDroite: Float = (parent.droite - parent.paddingDroite).toFloat()
        val interieurBas: Float = (parent.bas - parent.paddingBas).toFloat()
        val familleParent: FamilleParent = FamilleParent.deNom(parent.famille) ?: FamilleParent.deBalise(parent.classe)

        /** Le parent contient-il une `AppBarLayout` parmi ses enfants directs (§ 6.3 — comportement lié) ? */
        fun contientAppbar(): Boolean =
            enfants.any {
                (FamilleParent.deNom(it.famille) ?: FamilleParent.deBalise(it.classe)) == FamilleParent.APPBAR
            }
    }

    /** La TAILLE du déposé (miroir `defSize`) : `match_parent` → parent moins
     * padding (min 24 dp), fixe → valeur, sinon 96 dp ; hauteur ≥ `minHeight`. */
    private fun tailleDepose(
        contexte: Contexte,
        extrait: String?,
    ): TailleDepose {
        val dimensions = dimensionsDeExtrait(extrait)
        val largeurDp =
            when (dimensions.largeur) {
                is DimensionExtrait.PARENT -> {
                    max(
                        LARGEUR_MINIMALE_DP,
                        dpArrondi((contexte.interieurDroite - contexte.interieurGauche) / contexte.pxParDp),
                    )
                }

                is DimensionExtrait.FIXE -> {
                    (dimensions.largeur as DimensionExtrait.FIXE).dp
                }

                else -> {
                    LARGEUR_DEFAUT_DP
                }
            }
        var hauteurDp = (dimensions.hauteur as? DimensionExtrait.FIXE)?.dp ?: HAUTEUR_DEFAUT_DP
        hauteurDp = max(hauteurDp, dimensions.minHauteurDp)
        return TailleDepose(largeurDp * contexte.pxParDp, hauteurDp * contexte.pxParDp)
    }

    /** Les dimensions de l'EXTRAIT du déposé (`layout_width`, `layout_height`,
     * `minHeight` — tolérant, regex sur la première balise). */
    private fun dimensionsDeExtrait(extrait: String?): DimensionsExtrait {
        if (extrait.isNullOrBlank()) return DimensionsExtrait()
        val premiereBalise = extrait.substringBefore(">").substringAfter("<")
        return DimensionsExtrait(
            largeur = dimensionDe(REGEX_LARGEUR.find(premiereBalise)?.groupValues?.get(1)),
            hauteur = dimensionDe(REGEX_HAUTEUR.find(premiereBalise)?.groupValues?.get(1)),
            minHauteurDp =
                REGEX_MIN_HAUTEUR
                    .find(premiereBalise)
                    ?.groupValues
                    ?.get(1)
                    ?.substringBefore("dp")
                    ?.toIntOrNull() ?: 0,
        )
    }

    /** Une dimension d'extrait : `match_parent`/`fill_parent`, nombre en dp, ou `wrap_content`/absente. */
    private fun dimensionDe(valeur: String?): DimensionExtrait =
        when {
            valeur == null -> {
                DimensionExtrait.ENVELOPPE
            }

            valeur == "match_parent" || valeur == "fill_parent" -> {
                DimensionExtrait.PARENT
            }

            valeur.endsWith("dp") -> {
                valeur.substringBefore("dp").toIntOrNull()?.let { DimensionExtrait.FIXE(it) }
                    ?: DimensionExtrait.ENVELOPPE
            }

            else -> {
                DimensionExtrait.ENVELOPPE
            }
        }

    /** La référence `@id/x` d'une valeur d'attribut, retrouvée dans les enfants du parent (null si introuvable). */
    private fun referenceDe(
        contexte: Contexte,
        valeur: String,
    ): InfoVueRendu? {
        val nom = valeur.substringAfterLast('/', "").takeIf { valeur.startsWith("@") } ?: return null
        return contexte.enfants.firstOrNull { it.idRes == nom }
    }

    /** Un attribut BOOLÉEN du plan (`true` seulement — miroir `A(n)`). */
    private fun attributBooleen(
        plan: Plan,
        vararg noms: String,
    ): Boolean = plan.attributs.any { it.first.substringAfter(':') in noms && it.second == "true" }

    /** L'identifiant affichable d'une vue (miroir `@id/x` ; sans id, le nom de classe en repli). */
    private fun idAffichable(vue: InfoVueRendu): String? = vue.idRes.ifEmpty { null }

    /** Une marge dp ARRONDIE au multiple de 8, jamais négative, plafonnée à 64 (miroir `r8` + `Math.min(64, …)`). */
    private fun margeArrondie(
        contexte: Contexte,
        valeurPx: Float,
    ): Int = min(PLAFOND_MARGE_DP, arrondi8(contexte, valeurPx))

    /** Une valeur px CONVERTIE en dp puis arrondie au multiple de 8, jamais
     * négative (miroir `r8 = n => Math.max(0, Math.round(n / 8) * 8)`). */
    private fun arrondi8(
        contexte: Contexte,
        valeurPx: Float,
    ): Int {
        val dp = valeurPx / contexte.pxParDp
        return max(0, (dp / ARRONDI_MARGE_DP).roundToInt() * ARRONDI_MARGE_DP)
    }

    /** Les marges dp d'un plan CONVERTIES en px (fantôme). */
    private fun margesEnPx(
        contexte: Contexte,
        marges: Marges,
    ): MargesPx =
        MargesPx(
            debut = marges.debut * contexte.pxParDp,
            haut = marges.haut * contexte.pxParDp,
            fin = marges.fin * contexte.pxParDp,
            bas = marges.bas * contexte.pxParDp,
        )

    /** Le centre d'un segment de longueur [taille] (miroir `(W - w) / 2`). */
    private fun centre(
        debut: Float,
        fin: Float,
        taille: Float,
    ): Float = debut + (fin - debut - taille) / DEUX

    /** La valeur 3 (grille 3 × 3 — constante des divisions de cellule). */
    private const val TROIS: Float = 3f

    /** La valeur 2 (moitiés du déposé). */
    private const val DEUX: Float = 2f

    /** Densité de repli (mdpi) quand le profil n'en fournit pas. */
    private const val DENSITE_REPLI: Int = 160

    /** Taille minimale d'un côté de grille dégénérée, en px (miroir `Math.max(3, …)`). */
    private const val MIN_COTE_GRILLE: Int = 3

    /** La gravité de la cellule par DÉFAUT (haut-début : attribut omis — § 6.3). */
    private const val GRAVITE_DEFAUT: String = "start|top"

    /** Le libellé de la cellule par défaut (miroir `G9TXT[0][0] + ' (défaut)'`). */
    private const val TEXTE_DEFAUT: String = "défaut"

    /** Le suffixe du plan lié à l'AppBar (miroir `extra`). */
    private const val TEXTE_COMPORTEMENT: String = "défilement lié à l'AppBar"

    /** Le préfixe du plan d'ancrage au parent (miroir `'dans le parent : ' + G9TXT`). */
    private const val TEXTE_PARENT: String = "dans le parent : "

    /** Repli d'affichage quand l'ancre n'a pas d'identifiant (jamais en pratique : le FAB vise une vue nommée). */
    private const val SANS_ID: String = "vue"

    /** Les dimensions lues d'un extrait de la palette. */
    private class DimensionsExtrait(
        val largeur: DimensionExtrait = DimensionExtrait.ENVELOPPE,
        val hauteur: DimensionExtrait = DimensionExtrait.ENVELOPPE,
        val minHauteurDp: Int = 0,
    )

    /** Une dimension d'extrait : parent, fixe en dp, ou enveloppe (défaut). */
    private sealed interface DimensionExtrait {
        /** `match_parent` / `fill_parent`. */
        data object PARENT : DimensionExtrait

        /** Une valeur fixe en dp. */
        data class FIXE(
            val dp: Int,
        ) : DimensionExtrait

        /** `wrap_content` ou absente. */
        data object ENVELOPPE : DimensionExtrait
    }

    /** La taille du déposé, en px bitmap. */
    private class TailleDepose(
        val largeurPx: Float,
        val hauteurPx: Float,
    )

    /** Les marges d'un plan, en px bitmap (fantôme). */
    private class MargesPx(
        val debut: Float,
        val haut: Float,
        val fin: Float,
        val bas: Float,
    )

    /** `android:layout_width` de la première balise de l'extrait. */
    private val REGEX_LARGEUR: Regex = Regex("""android:layout_width="([^"]+)"""")

    /** `android:layout_height` de la première balise de l'extrait. */
    private val REGEX_HAUTEUR: Regex = Regex("""android:layout_height="([^"]+)"""")

    /** `android:minHeight` de la première balise de l'extrait. */
    private val REGEX_MIN_HAUTEUR: Regex = Regex("""android:minHeight="(\d+)dp"""")

    /** La colonne/ligne d'une cellule sous le point (bornée 0..2 — miroir `Math.min(2, Math.max(0, …))`). */
    private fun ligneColonne(
        coordonnee: Float,
        debut: Float,
        longueur: Float,
    ): Int = ((coordonnee - debut) / (longueur / TROIS)).toInt().coerceIn(0, 2)

    /** Conversion d'un entier px en dp arrondi (dimensions `match_parent`). */
    private fun dpArrondi(px: Float): Int = px.roundToInt()
}
