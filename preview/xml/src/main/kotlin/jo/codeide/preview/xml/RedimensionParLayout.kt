package jo.codeide.preview.xml

/**
 * Les AJUSTEMENTS PAR-LAYOUT du relâchement d'une poignée (PE14-S2 C3,
 * ADR 0138 — miroir les « targets par layout » du designer de la
 * référence : `RelativeResizeTarget` réécrit les marges du bord tenu,
 * `ConstraintResizeTarget` pose les positions absolues du dialecte
 * `tools:`) — FONCTIONS PURES testées sans Android, DISTINCTES du
 * modèle du geste ([RedimensionApercu]) : elles parlent du PARENT, pas
 * du doigt.
 *
 * Les entrées géométriques (sélection, parent, frères par id, densité)
 * voyagent dans [CadreRedimension] — le dernier rendu EST la vérité des
 * bornes ; les règles de position se lisent dans les attributs SOURCES
 * de la vue éditée.
 */
public object RedimensionParLayout {
    /**
     * Le CADRE GÉOMÉTRIQUE du relâchement : tout ce que les ajustements
     * par-layout doivent savoir du DERNIER RENDU (px bitmap absolus) et
     * du document.
     *
     * @property attributs les attributs SOURCES de la vue redimensionnée.
     * @property selection les bornes RENDUES de la vue redimensionnée.
     * @property parent les bornes RENDUES de son parent.
     * @property freres les vues rendues du document par nom d'id (ancres).
     * @property densiteDpi la densité du PROFIL (conversion px → dp).
     */
    public data class CadreRedimension(
        public val attributs: Map<String, String>,
        public val selection: InfoVueRendu,
        public val parent: InfoVueRendu,
        public val freres: Map<String, InfoVueRendu>,
        public val densiteDpi: Int,
    )

    /** Le poids de l'axe écrit (LINEAR/APPBAR — C3 cas A). */
    private const val ATTR_POIDS: String = "android:layout_weight"

    /**
     * Les règles d'ancrage d'un côté, par PRIORITÉ de lecture (miroir
     * les listes `TOP/BOTTOM/LEFT/RIGHT/START/END_ATTRIBUTE_RULES` de
     * `RelativeUtilities.kt` de la référence).
     */
    private val REGLES_DEBUT: List<String> =
        listOf(
            "android:layout_alignParentLeft",
            "android:layout_alignParentStart",
            "android:layout_toRightOf",
            "android:layout_toEndOf",
            "android:layout_alignLeft",
            "android:layout_alignStart",
        )

    private val REGLES_FIN: List<String> =
        listOf(
            "android:layout_alignParentRight",
            "android:layout_alignParentEnd",
            "android:layout_toLeftOf",
            "android:layout_toStartOf",
            "android:layout_alignRight",
            "android:layout_alignEnd",
        )

    private val REGLES_HAUT: List<String> =
        listOf(
            "android:layout_alignParentTop",
            "android:layout_below",
            "android:layout_alignTop",
        )

    private val REGLES_BAS: List<String> =
        listOf(
            "android:layout_alignParentBottom",
            "android:layout_above",
            "android:layout_alignBottom",
        )

    /** Règles de CENTRAGE : la position est symétrique, les marges ne la contrôlent pas. */
    private val REGLES_CENTRE_HORIZONTAL: List<String> =
        listOf("android:layout_centerInParent", "android:layout_centerHorizontal")

    private val REGLES_CENTRE_VERTICAL: List<String> =
        listOf("android:layout_centerInParent", "android:layout_centerVertical")

    /** Les règles vers une VUE, et le bord de la vue alignée qu'elles prennent pour référence. */
    private val BORD_ANCRE: Map<String, (InfoVueRendu) -> Int> =
        mapOf(
            "android:layout_toRightOf" to { it.droite },
            "android:layout_toEndOf" to { it.droite },
            "android:layout_toLeftOf" to { it.gauche },
            "android:layout_toStartOf" to { it.gauche },
            "android:layout_below" to { it.bas },
            "android:layout_above" to { it.haut },
            "android:layout_alignLeft" to { it.gauche },
            "android:layout_alignStart" to { it.gauche },
            "android:layout_alignRight" to { it.droite },
            "android:layout_alignEnd" to { it.droite },
            "android:layout_alignTop" to { it.haut },
            "android:layout_alignBottom" to { it.bas },
        )

    /**
     * Les MARGES à réécrire pour qu'un enfant de RelativeLayout reste
     * SUR PLACE pendant le redimensionnement (C3, miroir
     * `RelativeResizeTarget` : le bord TENU suit le doigt par SA marge,
     * l'ancre reste fixe par construction — la taille est mesurée
     * DEPUIS elle). Règles par axe :
     * - un axe CENTRÉ ne s'écrit pas (la position est symétrique) ;
     * - un bord DÉRIVÉ de l'ancre opposée (règle opposée, aucune du
     *   côté tenu) suit SEUL : rien à écrire ;
     * - sinon la marge du bord tenu vaut la distance du bord du
     *   FANTÔME à sa référence (parent par défaut — l'ancrage implicite
     *   de RelativeLayout —, vue alignée sinon) ; une référence
     *   INTROUVABLE saute le côté (jamais d'écriture fausse) et la
     *   marge ne descend jamais SOUS ZÉRO (miroir le clamp
     *   `maxOf(parent, minOf(…))` de la référence).
     */
    public fun margesARetenir(
        cadre: CadreRedimension,
        cible: RedimensionApercu.CibleRedimension,
    ): List<Pair<EditionAttributsXml.Cote, Int>> {
        if (cadre.densiteDpi <= 0) return emptyList()
        val rect =
            RedimensionApercu.rectangle(cible.ancreX, cible.ancreY, cible.coinX, cible.coinY)
        val marges = mutableListOf<Pair<EditionAttributsXml.Cote, Int>>()
        // L'ancre DROITE tient le bord GAUCHE (et réciproquement) ; un
        // axe centré ne s'écrit jamais.
        if (regleActive(cadre.attributs, REGLES_CENTRE_HORIZONTAL) == null) {
            val tenuAGauche = cible.ancreX == cadre.selection.droite
            margeDUnAxe(
                cadre = cadre,
                cote = if (tenuAGauche) EditionAttributsXml.Cote.DEBUT else EditionAttributsXml.Cote.FIN,
                regles = if (tenuAGauche) REGLES_DEBUT else REGLES_FIN,
                reglesOpposees = if (tenuAGauche) REGLES_FIN else REGLES_DEBUT,
                bordPx = if (tenuAGauche) rect.first.first else rect.second.first,
            )?.let { marges += it }
        }
        if (regleActive(cadre.attributs, REGLES_CENTRE_VERTICAL) == null) {
            val tenuEnHaut = cible.ancreY == cadre.selection.bas
            margeDUnAxe(
                cadre = cadre,
                cote = if (tenuEnHaut) EditionAttributsXml.Cote.HAUT else EditionAttributsXml.Cote.BAS,
                regles = if (tenuEnHaut) REGLES_HAUT else REGLES_BAS,
                reglesOpposees = if (tenuEnHaut) REGLES_BAS else REGLES_HAUT,
                bordPx = if (tenuEnHaut) rect.first.second else rect.second.second,
            )?.let { marges += it }
        }
        return marges
    }

    /**
     * La marge d'UN axe : `DEBUT`/`HAUT` mesurent `bord − référence`,
     * `FIN`/`BAS` mesurent `référence − bord` (la marge se lit TOUJOURS
     * vers l'intérieur du parent). `null` : bord dérivé, référence
     * introuvable ou marge négative.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque issue
     * (bord dérivé, référence introuvable, marge négative, marge) est
     * DISTINCTE, le même patron que les refus X4 de l'éditeur.
     */
    @Suppress("ReturnCount")
    private fun margeDUnAxe(
        cadre: CadreRedimension,
        cote: EditionAttributsXml.Cote,
        regles: List<String>,
        reglesOpposees: List<String>,
        bordPx: Int,
    ): Pair<EditionAttributsXml.Cote, Int>? {
        val regle = regleActive(cadre.attributs, regles)
        // Bord DÉRIVÉ de l'ancre opposée (règle opposée, aucune du côté
        // tenu) : il suit le doigt PAR CONSTRUCTION, rien à écrire.
        if (regle == null && regleActive(cadre.attributs, reglesOpposees) != null) return null
        val coteTenu = cote == EditionAttributsXml.Cote.DEBUT || cote == EditionAttributsXml.Cote.HAUT
        val reference = reference(cadre, regle, cote) ?: return null
        val margePx = if (coteTenu) bordPx - reference else reference - bordPx
        if (margePx < 0) return null
        return cote to CalquesApercu.pxVersDp(margePx, cadre.densiteDpi)
    }

    /**
     * La RÉFÉRENCE du bord tenu : le bord du PARENT (règles
     * `alignParent*` — et l'ancrage IMPLICITE de RelativeLayout quand
     * aucune règle ne tient le côté), ou le bord de la VUE ALIGNÉE
     * pour les règles de frère (`toRightOf`, `below`, `align*`…).
     * `null` : frère INTROUVABLE ou règle inconnue (jamais d'écriture
     * fausse).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les issues
     * (bord du parent, règle inconnue, frère introuvable, bord du frère)
     * sont DISTINCTES — miroir les replis de [RenduLayoutLib].
     */
    @Suppress("ReturnCount")
    private fun reference(
        cadre: CadreRedimension,
        regle: Map.Entry<String, String>?,
        cote: EditionAttributsXml.Cote,
    ): Int? {
        val cle = regle?.key
        val bordParent =
            when (cote) {
                EditionAttributsXml.Cote.DEBUT -> cadre.parent.gauche
                EditionAttributsXml.Cote.FIN -> cadre.parent.droite
                EditionAttributsXml.Cote.HAUT -> cadre.parent.haut
                EditionAttributsXml.Cote.BAS -> cadre.parent.bas
            }
        if (cle == null || cle.startsWith("android:layout_alignParent")) return bordParent
        val bord = BORD_ANCRE[cle] ?: return null
        val frere = cadre.freres[nomId(regle.value)] ?: return null
        return bord(frere)
    }

    /**
     * Les positions ABSOLUES du dialecte `tools:` de ConstraintLayout
     * (C3, miroir `ConstraintResizeTarget.updatePositionX/Y` : le
     * concepteur d'AS enregistre où tombe le bord DÉPLACÉ) : `X` quand
     * le bord GAUCHE est tenu, `Y` quand le bord HAUT est tenu —
     * relatifs au PARENT, dp, plancher 0. Un bord ANCRÉ (côté tenu)
     * n'a pas de position à poser : `null` sur cet axe.
     *
     * @param outilsAutorises le dialecte `tools:` est-il DÉJÀ présent au
     *        source ? (arbitrage D3 : jamais de dialecte imposé.)
     */
    public fun positionsOutils(
        cadre: CadreRedimension,
        cible: RedimensionApercu.CibleRedimension,
        outilsAutorises: Boolean,
    ): Pair<Int?, Int?> {
        if (!outilsAutorises || cadre.densiteDpi <= 0) return null to null
        val rect =
            RedimensionApercu.rectangle(cible.ancreX, cible.ancreY, cible.coinX, cible.coinY)
        val x =
            if (cible.ancreX == cadre.selection.droite) {
                CalquesApercu.pxVersDp(
                    (rect.first.first - cadre.parent.gauche).coerceAtLeast(0),
                    cadre.densiteDpi,
                )
            } else {
                null
            }
        val y =
            if (cible.ancreY == cadre.selection.bas) {
                CalquesApercu.pxVersDp(
                    (rect.first.second - cadre.parent.haut).coerceAtLeast(0),
                    cadre.densiteDpi,
                )
            } else {
                null
            }
        return x to y
    }

    /**
     * Le `layout_weight` de la vue CONTREDIT-IL la taille fixe écrite ?
     * (C3 cas A : dans un LinearLayout, un enfant à poids non nul
     * absorbe l'espace RESTANT PAR-DESSUS sa taille déclarée — le rendu
     * ne changeait pas d'un pixel). Vrai ssi un poids > 0 est posé.
     */
    public fun poidsARetirer(attributs: Map<String, String>): Boolean =
        (attributs[ATTR_POIDS]?.trim()?.toFloatOrNull() ?: 0f) > 0f

    /** La première règle ACTIVE (présente et non vide) de la liste — l'ordre fixe la priorité. */
    private fun regleActive(
        attributs: Map<String, String>,
        regles: List<String>,
    ): Map.Entry<String, String>? = attributs.entries.firstOrNull { it.key in regles && it.value.isNotEmpty() }

    /** Le NOM d'entrée d'une référence `@+id/x` / `@id/x` (vide si pas une référence). */
    private fun nomId(valeur: String): String = valeur.substringAfterLast('/').ifBlank { "" }
}
