package jo.codeide.preview.xml

/**
 * PLACEMENT PAR POPUP (PE7c, étendu PE12d — maquette
 * `docs/mockups/apercu-xml.html` § « Bulle, palette et popup de placement »,
 * miroir `posList`/`normPos`/`placeTarget`/`showPlace`) — FONCTIONS PURES
 * sur l'arbre aplati [InfoVueRendu].
 *
 * PE12d (spec § 7, ADR 0135 D4) : le jeu de positions suit la FAMILLE du
 * PARENT RÉSULTANT (recalculée à chaque changement de référence ou de
 * position) :
 * - `CONSTRAINT`, `LINEAR`, `APPBAR`, `AUTRE` : Avant · Dedans · Après ;
 * - `FRAME`, `COORDINATOR` : Derrière · Dedans · Devant (les mêmes
 *   [Position.AVANT]/[Position.APRES], les libellés changent) + grille
 *   3 × 3 de position dans le conteneur ;
 * - `RELATIVE` (référence = frère) : Dessous · Dessus · Droite · Gauche
 *   (+ Dedans si la référence est un conteneur) + marge 0/8/16/24 dp ;
 * - `RELATIVE` (« Dedans ») : Dedans + grille 3 × 3 d'ancrage + marge.
 *
 * v0.170.0 (S3, [ContraintesHierarchie]) : « Dedans » suit en plus la
 * CONTENANCE de la référence — un conteneur à ADAPTATEUR (ListView,
 * RecyclerView, ViewPager2…) ou à ENFANT ATTENDU n'offre jamais
 * « Dedans », un ENFANT UNIQUE plein (ScrollView qui porte déjà le
 * sien) non plus ; l'offre du popup reste ainsi dans les bornes que
 * la plateforme impose au XML.
 *
 * Les positions INVALIDES sont filtrées (une feuille n'offre pas
 * « Dedans », la racine n'offre que « Dedans », la contenance est
 * honorée) ; une position devenue invalide retombe sur la première
 * valide ([positionInitiale] — miroir
 * `normPos` : Dedans si possible, sinon Après, sinon la première).
 *
 * [cible] (miroir `placeTarget`) dérive la cible de dépôt RÉSULTANTE — la
 * MÊME [ZonesDepot.CibleDepot] que le dépôt PE7b, PORTANT le
 * [PlanInsertionFamille.Plan] de la famille — le repère en direct et
 * l'insertion (`InsertionXml.inserer` + `ajouterAttributs`) le consomment :
 * une seule écriture dans la session.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — la surface de
 * la spec § 4.1 (positions par famille, replis, cible par branche) vit au
 * COMPLET dans cet objet (précédent [InsertionXml]).
 */
@Suppress("TooManyFunctions")
public object PlacementPopup {
    /**
     * La position demandée par rapport à la vue de référence (miroir `.pos`
     * — les clés `before`/`in`/`after`/`below`/`above`/`end`/`start` de la
     * maquette). `AVANT`/`APRES` se lisent « Derrière »/« Devant » dans un
     * parent `FRAME`/`COORDINATOR` (les libellés sont à l'UI).
     */
    public enum class Position {
        /** Avant la référence — « Derrière » dans une pile (FRAME, COORDINATOR). */
        AVANT,

        /** Dans la référence (conteneur) ou dedans le parent (RELATIVE « Dedans »). */
        DEDANS,

        /** Après la référence — « Devant » dans une pile (FRAME, COORDINATOR). */
        APRES,

        /** `layout_below` — Dessous de la référence (parent RelativeLayout). */
        DESSOUS,

        /** `layout_above` — Dessus de la référence (parent RelativeLayout). */
        DESSUS,

        /** `layout_toEndOf` — À droite de la référence (parent RelativeLayout). */
        DROITE,

        /** `layout_toStartOf` — À gauche de la référence (parent RelativeLayout). */
        GAUCHE,
    }

    /**
     * Les positions AUTORISÉES pour la référence (miroir `posList` +
     * `normPos` + les `disabled` de `drawPlace`) : la RACINE n'offre que
     * DEDANS (si elle est conteneur — un document vide se refuse), une
     * FEUILLE n'offre pas DEDANS, un parent `RELATIVE` offre les QUATRE
     * côtés (+ DEDANS si la référence est un conteneur), les autres
     * familles offrent Avant/Dedans/Après selon la contenance — la
     * contenance HONORANT [ContraintesHierarchie] : DEDANS exige un
     * conteneur qui ACCEPTE un enfant de plus (un ScrollView plein, un
     * AdapterView ou un conteneur à enfant attendu l'excluent, v0.170.0).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est
     * une issue DISTINCTE du refus (philosophie X4, miroir normPos).
     */
    @Suppress("ReturnCount")
    public fun positionsAutorisees(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
    ): Set<Position> {
        val reference = vues.getOrNull(indiceRef) ?: return emptySet()
        if (reference.indiceParent < 0 || indiceRef == 0) {
            // Racine (ou première vue du document) : DEDANS seul si
            // conteneur qui accepte un enfant (contenance S3 comprise).
            return if (dedansAutorise(vues, indiceRef)) setOf(Position.DEDANS) else emptySet()
        }
        return when (familleDuPere(vues, reference)) {
            FamilleParent.RELATIVE -> {
                if (dedansAutorise(vues, indiceRef)) {
                    setOf(Position.DESSOUS, Position.DESSUS, Position.DROITE, Position.GAUCHE, Position.DEDANS)
                } else {
                    setOf(Position.DESSOUS, Position.DESSUS, Position.DROITE, Position.GAUCHE)
                }
            }

            else -> {
                if (dedansAutorise(vues, indiceRef)) {
                    setOf(Position.AVANT, Position.DEDANS, Position.APRES)
                } else {
                    setOf(Position.AVANT, Position.APRES)
                }
            }
        }
    }

    /**
     * La référence accepte-t-elle « Dedans » (miroir `posList` étendu
     * v0.170.0) : un conteneur du RENDU qui ACCEPTE un enfant de plus —
     * [ContraintesHierarchie.accepteEnfant] décide de la capacité par
     * le nom de classe simple (feuille, adaptateur, enfant attendu,
     * enfant unique plein).
     */
    private fun dedansAutorise(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
    ): Boolean {
        val reference = vues[indiceRef]
        return reference.conteneur &&
            ContraintesHierarchie.accepteEnfant(reference.classe, enfantsDe(vues, indiceRef).size)
    }

    /**
     * La position PAR DÉFAUT pour la référence (miroir `showPlace` puis
     * `normPos`) : DEDANS si la référence est un conteneur, sinon APRÈS si
     * l'offre le permet — sinon la PREMIÈRE position autorisée (Dessous
     * pour un frère `RelativeLayout`, miroir `ok[0]`).
     */
    public fun positionInitiale(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
    ): Position? {
        val autorisees = positionsAutorisees(vues, indiceRef)
        return when {
            autorisees.isEmpty() -> null
            Position.DEDANS in autorisees && vues.getOrNull(indiceRef)?.conteneur == true -> Position.DEDANS
            Position.APRES in autorisees -> Position.APRES
            else -> autorisees.first()
        }
    }

    /** Le jeu de positions affiché (miroir `posList` — libellés à l'UI). */
    public enum class JeuPositions {
        /** Avant · Dedans · Après (CONSTRAINT, LINEAR, APPBAR, AUTRE). */
        SEQUENCE,

        /** Derrière · Dedans · Devant (FRAME, COORDINATOR — l'ordre = le plan). */
        PILE,

        /** Dessous · Dessus · Droite · Gauche (+ Dedans si conteneur) — parent RelativeLayout. */
        COTES_RELATIFS,

        /** Dedans seul (la référence est la racine). */
        DEDANS_SEUL,
    }

    /**
     * Le JEU DE POSITIONS affiché (miroir `posList` — les LIBELLÉS et les
     * sous-contrôles du popup en dépendent) : la RACINE n'offre que
     * « Dedans » ; un parent `RELATIVE` offre les côtés (Dessous, Dessus,
     * Droite, Gauche) ; un parent `FRAME`/`COORDINATOR` offre la PILE
     * (Derrière, Dedans, Devant) ; les autres familles la SÉQUENCE
     * (Avant, Dedans, Après). Les positions INTERDITES (feuille sans
     * conteneur) restent à [positionsAutorisees].
     */
    public fun jeuPositions(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
    ): JeuPositions {
        val reference = vues.getOrNull(indiceRef)
        return when {
            reference == null -> JeuPositions.SEQUENCE

            reference.indiceParent < 0 || indiceRef == 0 -> JeuPositions.DEDANS_SEUL

            familleDuPere(vues, reference) == FamilleParent.RELATIVE -> JeuPositions.COTES_RELATIFS

            familleDuPere(vues, reference) == FamilleParent.FRAME ||
                familleDuPere(vues, reference) == FamilleParent.COORDINATOR -> JeuPositions.PILE

            else -> JeuPositions.SEQUENCE
        }
    }

    /**
     * La famille du PARENT RÉSULTANT de la position courante (miroir
     * `placeParent` : Dedans → la RÉFÉRENCE, sinon son PÈRE) — elle pilote
     * les sous-contrôles du popup (grille 3 × 3 pour `FRAME`/`COORDINATOR`
     * et `RELATIVE`-Dedans, rangée de marges pour `RELATIVE`, § 7).
     */
    public fun familleParentResultant(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        position: Position,
    ): FamilleParent {
        val reference = vues.getOrNull(indiceRef) ?: return FamilleParent.AUTRE
        return when {
            position == Position.DEDANS -> familleDe(reference)
            else -> vues.getOrNull(reference.indiceParent)?.let(::familleDe) ?: FamilleParent.AUTRE
        }
    }

    /**
     * La cible de dépôt RÉSULTANTE (miroir `placeTarget`) — la même forme
     * que le dépôt PE7b, PORTANT le plan de la famille : DEDANS → la
     * référence devient le parent (insertion en FIN de ses enfants ; plan
     * de cellule pour un conteneur `FRAME`/`COORDINATOR`, d'ancrage au
     * parent pour un `RELATIVE`) ; les autres positions → le PÈRE de la
     * référence, l'index parmi ses frères (Dessous/Droite = après la
     * référence, Dessus/Gauche = avant ; Derrière = avant, Devant = après)
     * et le plan correspondant (règle vers le frère pour `RELATIVE`,
     * cellule du père pour `FRAME`/`COORDINATOR`). `null` = référence hors
     * arbre ou position non autorisée (jamais de cible devinée).
     *
     * [cellule] et [margeDp] portent le choix du popup (grille 3 × 3 et
     * marge 0/8/16/24 dp — D6), [depose] la taille du déposé (fantôme) et
     * les conversions dp ↔ px du profil.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — la
     * signature porte le choix complet du popup, comme [InfoVueRendu] ;
     * ReturnCount — chaque garde est une issue DISTINCTE du refus.
     */
    @Suppress("ReturnCount", "LongParameterList")
    public fun cible(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        position: Position,
        cellule: PlanInsertionFamille.Cellule = PlanInsertionFamille.Cellule(ligne = 0, colonne = 0),
        margeDp: Int = PlanInsertionFamille.MARGE_DEFAUT_DP,
        depose: PlanInsertionFamille.Depose = PlanInsertionFamille.Depose(),
    ): ZonesDepot.CibleDepot? {
        val reference = vues.getOrNull(indiceRef) ?: return null
        if (position !in positionsAutorisees(vues, indiceRef)) return null
        if (position == Position.DEDANS) return cibleDedans(vues, indiceRef, cellule, margeDp, depose)
        // Autres positions : le PÈRE de la référence, l'index parmi ses frères.
        val freres = enfantsDe(vues, reference.indiceParent)
        val indexReference = freres.indexOf(indiceRef)
        if (indexReference < 0) return null
        val famillePere = familleDe(vues[reference.indiceParent])
        return when (famillePere) {
            FamilleParent.RELATIVE -> {
                cibleCoteFrere(vues, indiceRef, position, margeDp, depose)
            }

            FamilleParent.FRAME, FamilleParent.COORDINATOR -> {
                ciblePile(vues, indiceRef, position, cellule, depose)
            }

            else -> {
                ZonesDepot.CibleDepot(
                    reference.indiceParent,
                    if (position == Position.AVANT) indexReference else indexReference + 1,
                )
            }
        }
    }

    /**
     * La cible DEDANS (miroir `placeTarget` branche `in`) : la référence
     * devient le parent, l'insertion va en FIN de ses enfants ; plan de
     * cellule pour un conteneur `FRAME`/`COORDINATOR`, d'ancrage au parent
     * pour un `RELATIVE`.
     */
    private fun cibleDedans(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        cellule: PlanInsertionFamille.Cellule,
        margeDp: Int,
        depose: PlanInsertionFamille.Depose,
    ): ZonesDepot.CibleDepot? {
        val index = enfantsDe(vues, indiceRef).size
        val plan =
            when (familleDe(vues[indiceRef])) {
                FamilleParent.FRAME, FamilleParent.COORDINATOR -> {
                    PlanInsertionFamille.cellule(vues, indiceRef, cellule, null, depose)
                }

                FamilleParent.RELATIVE -> {
                    PlanInsertionFamille.versParent(vues, indiceRef, cellule, margeDp, null, depose)
                }

                else -> {
                    null
                }
            }
        return ZonesDepot.CibleDepot(indiceParent = indiceRef, index = index, plan = plan)
    }

    /**
     * La cible d'un CÔTÉ de frère `RelativeLayout` (miroir `placeTarget`
     * branche `rel`) : Dessous/Droite suivent la référence dans le
     * document, Dessus/Gauche la précèdent ; plan de règle vers le frère.
     */
    private fun cibleCoteFrere(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        position: Position,
        margeDp: Int,
        depose: PlanInsertionFamille.Depose,
    ): ZonesDepot.CibleDepot? {
        val reference = vues[indiceRef]
        val indexReference = enfantsDe(vues, reference.indiceParent).indexOf(indiceRef)
        val cote =
            when (position) {
                Position.DESSOUS -> PlanInsertionFamille.Cote.DESSOUS
                Position.DESSUS -> PlanInsertionFamille.Cote.DESSUS
                Position.DROITE -> PlanInsertionFamille.Cote.DROITE
                Position.GAUCHE -> PlanInsertionFamille.Cote.GAUCHE
                else -> return null
            }
        val decalage = if (position == Position.DESSOUS || position == Position.DROITE) 1 else 0
        val plan =
            PlanInsertionFamille.cote(
                vues,
                reference.indiceParent,
                indiceRef,
                cote,
                margeDp,
                null,
                depose,
            )
        return ZonesDepot.CibleDepot(reference.indiceParent, indexReference + decalage, plan)
    }

    /**
     * La cible d'une PILE `FRAME`/`COORDINATOR` (miroir `placeTarget`) :
     * Derrière (AVANT) la référence, Devant (APRÈS) ; le plan de cellule
     * s'applique au PÈRE.
     */
    private fun ciblePile(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        position: Position,
        cellule: PlanInsertionFamille.Cellule,
        depose: PlanInsertionFamille.Depose,
    ): ZonesDepot.CibleDepot {
        val reference = vues[indiceRef]
        val indexReference = enfantsDe(vues, reference.indiceParent).indexOf(indiceRef)
        val decalage = if (position == Position.AVANT) 0 else 1
        val plan =
            PlanInsertionFamille.cellule(vues, reference.indiceParent, cellule, null, depose)
        return ZonesDepot.CibleDepot(reference.indiceParent, indexReference + decalage, plan)
    }

    /** Les indices des enfants DIRECTS, dans l'ordre du document. */
    public fun enfantsDe(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
    ): List<Int> = vues.indices.filter { indice -> indice != indiceParent && vues[indice].indiceParent == indiceParent }

    /**
     * Les DONNÉES du résumé (miroir `res.innerHTML` et ses trois formes,
     * § 7) : le libellé du parent cible (« Classe · @+id/x » — sans id, la
     * classe seule), la position d'insertion (1-based), le total (enfants
     * + 1) et le LIBELLÉ DU PLAN quand il y en a un — le popup compose :
     * « Dans X, position n sur total » (sans plan), « {plan}, dans X »
     * (règle `RelativeLayout`) ou « Dans X, {plan}, plan n sur total »
     * (pile `FRAME`/`COORDINATOR`).
     */
    public data class ResumePlacement(
        val libelleParent: String,
        val position: Int,
        val total: Int,
        val libellePlan: String? = null,
    )

    /**
     * Le résumé de la cible (miroir `res`) — `null` si la cible est refusée.
     * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes sont
     * des refus distincts (cible absente, parent hors arbre).
     */
    @Suppress("ReturnCount")
    public fun resume(
        vues: List<InfoVueRendu>,
        cible: ZonesDepot.CibleDepot?,
    ): ResumePlacement? {
        val cibleValide = cible ?: return null
        val parent = vues.getOrNull(cibleValide.indiceParent) ?: return null
        val idNom = parent.idRes.substringAfterLast('/')
        // Miroir `label(par)` de la maquette : « Classe · @+id/x » — sans
        // id, la classe seule (même règle que le fil d'Ariane PE6d).
        val libelle = if (idNom.isEmpty()) parent.classe else parent.classe + " · @+id/" + idNom
        return ResumePlacement(
            libelleParent = libelle,
            position = cibleValide.index + 1,
            total = enfantsDe(vues, cibleValide.indiceParent).size + 1,
            libellePlan = cibleValide.plan?.libelle,
        )
    }

    /** La famille d'une VUE (nom d'enum du rendu, repli par balise — PE12a). */
    private fun familleDe(vue: InfoVueRendu): FamilleParent =
        FamilleParent.deNom(vue.famille) ?: FamilleParent.deBalise(vue.classe)

    /** La famille du PÈRE d'une vue (les positions en dépendent — miroir `famOf(f.p)`). */
    private fun familleDuPere(
        vues: List<InfoVueRendu>,
        reference: InfoVueRendu,
    ): FamilleParent = familleDe(vues[reference.indiceParent])
}
