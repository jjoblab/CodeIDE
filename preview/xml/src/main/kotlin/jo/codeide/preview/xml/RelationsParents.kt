package jo.codeide.preview.xml

/**
 * RELATIONS ENTRE LES ENFANTS D'UN PARENT (PE12b —
 * `docs/PREVIEW_PARENTS.md` § 4.2, miroir maquette § 16 : `relEdges`,
 * `relCycle`, `wouldCycle`, `pathTo`, `FAM_ATTRS`) : les ARÊTES que les
 * paramètres de layout tissent entre les vues d'un même conteneur, les
 * CYCLES que `RelativeLayout` refuse (« Circular dependencies cannot
 * exist in RelativeLayout », spec § 3.2 / § 12) et les attributs
 * ORPHELINS qu'un parent d'une autre famille ignore (spec § 9).
 *
 * Tout se calcule sur l'ARBRE DE TEXTE ([ArbreAttributsXml]), jamais
 * sur le rendu : les relations restent disponibles quand le rendu
 * ÉCHOUE (dépendance circulaire, classe introuvable…). La FAMILLE du
 * parent — donc les arêtes qui LUI sont sémantiques — reste à
 * l'appelant, qui la tient du rendu ([InfoVueRendu.famille]) ou de la
 * balise ([FamilleParent.deBalise]) : [relations] extrait, [cycle] et
 * [fermeraitBoucle] répondent.
 *
 * FONCTIONS PURES, SANS ANDROID — testées en JVM (`RelationsParentsTest`).
 */
public object RelationsParents {
    /** Le TYPE d'arête (spec § 4.2). */
    public enum class TypeRelation {
        /** Règle `RelativeLayout` vers un FRÈRE (`layout_below`, `alignTop`…). */
        REGLE_FRERE,

        /** Règle `RelativeLayout` vers le PARENT (`layout_alignParentStart`, `centerInParent`…). */
        REGLE_PARENT,

        /** Ancre `CoordinatorLayout` (`app:layout_anchor`). */
        ANCRE,

        /** Contrainte `ConstraintLayout` (`app:layout_constraint*_to*Of`). */
        CONTRAINTE,
    }

    /**
     * UNE arête : la vue [source] vise [cible] par l'attribut [attribut]
     * (nom canoniquement qualifié, « android:layout_below »). [cible]
     * est null pour les règles vers le parent et les contraintes posées
     * sur le parent (`"parent"`, marge littérale).
     */
    public data class Relation(
        val source: String,
        val cible: String?,
        val attribut: String,
        val type: TypeRelation,
    )

    /**
     * Les arêtes VALIDES des enfants de [parent] (miroir `relEdges`) :
     * - règle vers un frère : la cible RÉSOUT un frère connu, jamais la
     *   vue elle-même (les règles vers un id absent sont des problèmes
     *   `REFERENCE_INTROUVABLE`, pas des arêtes — spec § 9) ;
     * - drapeau vers le parent : valeur `"true"` seulement ;
     * - ancre : la cible résout une vue du SOUS-ARBRE du parent, hors la
     *   vue elle-même et sa descendance (spec § 3.3, miroir du contrôle
     *   `ANCRE_INVALIDE` § 9) ;
     * - contrainte : toute cible `*_to*Of` (vers `@id/x` ou `"parent"`).
     *
     * L'ordre suit le document (enfants puis attributs).
     */
    public fun relations(parent: ArbreAttributsXml.Noeud): List<Relation> {
        val freres = HashSet<String>()
        val sousArbre = HashSet<String>()
        parent.enfants.forEach { enfant ->
            enfant.id?.let { freres.add(it) }
            collecterIds(enfant, sousArbre)
        }
        val contexte = ContexteParent(freres, sousArbre)
        val relations = mutableListOf<Relation>()
        for (enfant in parent.enfants) {
            val source = enfant.id ?: continue
            for ((cle, valeur) in enfant.attributs) {
                relation(source, cle, valeur, enfant, contexte)?.let { relations.add(it) }
            }
        }
        return relations
    }

    /**
     * Le PREMIER cycle de règles vers un frère parmi les enfants de
     * [parent] (miroir `relCycle`) : chemin d'identifiants FERMÉ —
     * `[@id/a, @id/b, @id/a]`, la première vue en tête, retour sur
     * elle-même en fin (miroir du bandeau « @id/a → @id/b → @id/a »,
     * spec § 12) — ou null. Les enfants SANS identifiant ne peuvent
     * être ni visés ni référencés : ils ne ferment aucun cycle.
     */
    public fun cycle(parent: ArbreAttributsXml.Noeud): List<String>? {
        val aretes = aretesFreres(parent)
        for ((source, cibles) in aretes) {
            for (cible in cibles) {
                val chemin = cheminVers(aretes, cible, source, HashSet())
                if (chemin != null) {
                    return listOf(source) + chemin
                }
            }
        }
        return null
    }

    /**
     * L'édition de [attribut] sur la vue [vue] vers la cible [cible]
     * FERMERAIT-ELLE une boucle de règles (miroir `wouldCycle`) ?
     * Seule une règle vers un FRÈRE peut fermer une boucle : tout autre
     * attribut répond faux. La cible ACTUELLE de la règle éditée est
     * REMPLACÉE (une vue qui déplace son `below` ne garde pas l'ancien
     * frère parmi ses règles).
     */
    public fun fermeraitBoucle(
        parent: ArbreAttributsXml.Noeud,
        vue: String,
        attribut: String,
        cible: String,
    ): Boolean {
        val regle = nomRegle(attribut)
        if (regle !in REGLES_VERS_FRERE) return false
        val aretes = aretesFreres(parent)
        val cibles = LinkedHashSet(aretes[vue].orEmpty())
        ArbreAttributsXml
            .nomId(
                parent.enfants
                    .firstOrNull { it.id == vue }
                    ?.attributs
                    ?.get("android:$regle"),
            )?.let { cibles.remove(it) }
        cibles.add(cible)
        aretes[vue] = cibles
        return cheminVers(aretes, cible, vue, HashSet()) != null
    }

    /**
     * Les attributs de layout ORPHELINS pour la famille [famille] du
     * parent (spec § 9, miroir `FAM_ATTRS`) : ceux qui matchent une
     * ligne de la table SANS que [famille] y soit autorisée — la
     * correspondance est PARTIELLE comme le `test()` de la maquette
     * (les motifs portent leurs propres ancres `^`/`$`). Un attribut
     * HORS table (marges, `layout_width`…) n'est JAMAIS signalé. L'ordre
     * suit [attributs] (l'ordre du document).
     */
    public fun attributsOrphelins(
        famille: FamilleParent,
        attributs: Map<String, String>,
    ): List<String> =
        attributs.keys.filter { cle ->
            TABLE_ORPHELINS.any { (motif, autorisees) -> motif.containsMatchIn(cle) && famille !in autorisees }
        }

    // ── Calcul des arêtes et des cycles ────────────────────────────────

    /**
     * Les vues CONNUES d'un parent : identifiants des frères directs
     * (règles `RelativeLayout`) et du sous-arbre ENTIER (ancres de
     * `CoordinatorLayout`, qui cherchent dans toute la descendance).
     */
    private class ContexteParent(
        val freres: Set<String>,
        val sousArbre: Set<String>,
    )

    /** La relation de l'attribut [cle] = [valeur] porté par [enfant], ou null si ce n'est pas une arête. */
    private fun relation(
        source: String,
        cle: String,
        valeur: String,
        enfant: ArbreAttributsXml.Noeud,
        contexte: ContexteParent,
    ): Relation? {
        val cible = ArbreAttributsXml.nomId(valeur)
        val nomLocal = cle.substringAfter(':')
        return when {
            nomLocal in REGLES_VERS_FRERE -> {
                if (cible != null && cible != source && cible in contexte.freres) {
                    Relation(source, cible, cle, TypeRelation.REGLE_FRERE)
                } else {
                    null
                }
            }

            nomLocal in REGLES_VERS_PARENT -> {
                if (valeur == "true") {
                    Relation(source, null, cle, TypeRelation.REGLE_PARENT)
                } else {
                    null
                }
            }

            cle == ATTRIBUT_ANCRE -> {
                // Ancre valide : une vue du sous-arbre du parent, hors la
                // vue ancrée ET sa descendance (spec § 3.3 / § 9
                // ANCRE_INVALIDE) — [contientId] couvre la vue elle-même
                // (source = enfant.id), inutile de retester.
                if (cible != null && cible in contexte.sousArbre && !contientId(enfant, cible)) {
                    Relation(source, cible, cle, TypeRelation.ANCRE)
                } else {
                    null
                }
            }

            REGEX_CIBLE_CONTRAINTE.matches(cle) -> {
                Relation(source, cible, cle, TypeRelation.CONTRAINTE)
            }

            else -> {
                null
            }
        }
    }

    /**
     * Le sous-arbre de [noeud] contient-il une vue d'identifiant [id] ?
     * PARTAGÉ avec [ProblemesPreview] (PE12f : l'ancre invalide interroge
     * le sous-arbre du parent ET celui de la vue ancrée — même parcours).
     */
    internal fun contientId(
        noeud: ArbreAttributsXml.Noeud,
        id: String,
    ): Boolean = noeud.id == id || noeud.enfants.any { contientId(it, id) }

    /** Collecte les identifiants du sous-arbre de [noeud] dans [dans]. */
    private fun collecterIds(
        noeud: ArbreAttributsXml.Noeud,
        dans: MutableSet<String>,
    ) {
        noeud.id?.let { dans.add(it) }
        noeud.enfants.forEach { collecterIds(it, dans) }
    }

    /**
     * Les arêtes « règles vers un frère » de [parent] (miroir exact de
     * `relEdges`) : source → ensemble de cibles ; cibles RÉSOLUES parmi
     * les frères, jamais la source elle-même ; ordre des enfants du
     * document, cibles dans l'ordre des règles de la plateforme.
     */
    private fun aretesFreres(parent: ArbreAttributsXml.Noeud): LinkedHashMap<String, LinkedHashSet<String>> {
        val aretes = LinkedHashMap<String, LinkedHashSet<String>>()
        val freres = parent.enfants.mapNotNull { it.id }.toHashSet()
        for (enfant in parent.enfants) {
            val source = enfant.id ?: continue
            val cibles = LinkedHashSet<String>()
            for (regle in REGLES_VERS_FRERE) {
                val cible = ArbreAttributsXml.nomId(enfant.attributs["android:$regle"])
                if (cible != null && cible != source && cible in freres) {
                    cibles.add(cible)
                }
            }
            aretes[source] = cibles
        }
        return aretes
    }

    /**
     * Chemin de [de] vers [vers] dans le graphe des règles ([vus] garde
     * les sources déjà explorées du parcours), ou null — miroir de
     * `pathTo` : trouvé = `[de, …, vers]`.
     */
    private fun cheminVers(
        aretes: Map<String, Set<String>>,
        de: String,
        vers: String,
        vus: MutableSet<String>,
    ): List<String>? =
        when {
            de == vers -> {
                listOf(de)
            }

            !vus.add(de) -> {
                null
            }

            else -> {
                aretes[de].orEmpty().firstNotNullOfOrNull { suivant ->
                    cheminVers(aretes, suivant, vers, vus)?.let { chemin -> listOf(de) + chemin }
                }
            }
        }

    /**
     * Le nom de règle canonique (« android:layout_below », « layout_below »
     * ou « below » → « layout_below »).
     */
    private fun nomRegle(attribut: String): String {
        val sansAndroid = attribut.removePrefix("android:")
        return if (sansAndroid.startsWith(PREFIXE_LAYOUT)) sansAndroid else "$PREFIXE_LAYOUT$sansAndroid"
    }

    // ── Tables de référence (spec § 3, § 9 — miroir REL_REFS, REL_FLAGS,
    //    FAM_ATTRS de la maquette) ──────────────────────────────────────

    /**
     * Règles `RelativeLayout` vers un FRÈRE (miroir `REL_REFS` : l'ordre
     * H puis V reproduit `RelativeLayout.applyHorizontalSizeRules` /
     * `applyVerticalSizeRules`). PARTAGÉE avec [ProblemesPreview] (PE12f :
     * références introuvables et boucle à casser parcourent la MÊME table
     * — une seule source de vérité du miroir).
     */
    internal val REGLES_VERS_FRERE: List<String> =
        listOf(
            "layout_below",
            "layout_above",
            "layout_toEndOf",
            "layout_toStartOf",
            "layout_toLeftOf",
            "layout_toRightOf",
            "layout_alignTop",
            "layout_alignBottom",
            "layout_alignStart",
            "layout_alignEnd",
            "layout_alignLeft",
            "layout_alignRight",
            "layout_alignBaseline",
        )

    /** Drapeaux `RelativeLayout` vers le PARENT (miroir `REL_FLAGS`). */
    private val REGLES_VERS_PARENT: List<String> =
        listOf(
            "layout_alignParentStart",
            "layout_alignParentEnd",
            "layout_alignParentLeft",
            "layout_alignParentRight",
            "layout_alignParentTop",
            "layout_alignParentBottom",
            "layout_centerInParent",
            "layout_centerHorizontal",
            "layout_centerVertical",
        )

    /** L'attribut d'ancre de `CoordinatorLayout`. */
    private const val ATTRIBUT_ANCRE: String = "app:layout_anchor"

    /** Une contrainte `ConstraintLayout` qui VISE (`_toTopOf`…), hors tailles et biais. */
    private val REGEX_CIBLE_CONTRAINTE: Regex = Regex("""^app:layout_constraint\w+_to\w+Of$""")

    /** Préfixe des règles de `RelativeLayout` (nom local). */
    private const val PREFIXE_LAYOUT: String = "layout_"

    /**
     * Table des attributs orphelins (spec § 9, miroir `FAM_ATTRS`) :
     * motif d'attribut → familles de parent qui le COMPRENNENT. Un
     * attribut absent de cette table n'est jamais signalé.
     */
    private val TABLE_ORPHELINS: List<Pair<Regex, Set<FamilleParent>>> =
        listOf(
            Regex("""^app:layout_constraint""") to
                setOf(FamilleParent.CONSTRAINT),
            Regex(
                """^android:layout_(below|above|toStartOf|toEndOf|toLeftOf|toRightOf|""" +
                    """align(Top|Bottom|Start|End|Left|Right|Baseline|Parent(Top|Bottom|Start|End|Left|Right)|""" +
                    """WithParentIfMissing)|centerInParent|centerHorizontal|centerVertical)$""",
            ) to setOf(FamilleParent.RELATIVE),
            Regex("""^android:layout_weight$""") to
                setOf(FamilleParent.LINEAR, FamilleParent.APPBAR),
            Regex("""^android:layout_gravity$""") to
                setOf(FamilleParent.LINEAR, FamilleParent.APPBAR, FamilleParent.FRAME, FamilleParent.COORDINATOR),
            Regex("""^app:layout_(anchor|anchorGravity|behavior|insetEdge|dodgeInsetEdges|keyline)$""") to
                setOf(FamilleParent.COORDINATOR),
            Regex("""^app:layout_scroll(Flags|Interpolator)$""") to
                setOf(FamilleParent.APPBAR),
        )
}
