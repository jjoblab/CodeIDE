package jo.codeide.preview.xml

/**
 * LES PROBLÈMES DE FAMILLE DE L'APERÇU (PE12f — `docs/PREVIEW_PARENTS.md`
 * § 9, miroir de la moitié famille de `computeProblems` de la maquette) :
 * les CINQ règles qui se calculent sur le TEXTE du document
 * ([ArbreAttributsXml]), contrairement aux règles de RENDU
 * ([ProblemesPreview], PE6c/PE11g — évaluées sur `InfoVueRendu`).
 *
 * Pourquoi le TEXTE (spec § 9) : ces problèmes restent disponibles quand
 * le rendu ÉCHOUE — la DÉPENDANCE CIRCULAIRE se lit alors que le dernier
 * rendu valide est conservé (§ 12) — et leurs CORRECTIFS se posent sur le
 * span du nœud fautif LU DANS LE TEXTE VIVANT, jamais sur un span de
 * rendu périmé. La famille du parent vient de la BALISE
 * ([FamilleParent.deBalise], repli du texte — la chaîne de
 * superclasses du rendu n'est pas sollicitée).
 *
 * FONCTIONS PURES, SANS ANDROID — testées en JVM sous Robolectric
 * ([ProblemesFamillesTest], comme [RelationsParentsTest]).
 */
public object ProblemesFamilles {
    /**
     * UN problème de famille détecté DANS LE TEXTE (spec § 9).
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — un CHAMP
     * = un paramètre, miroir des entrées plates de `computeProblems` de
     * la maquette (même patron qu'[ArbreAttributsXml.Noeud]).
     */
    @Suppress("LongParameterList")
    public data class Probleme(
        /** Genre — clé du libellé localisé et du correctif. */
        public val genre: ProblemesPreview.GenreProbleme,
        /** Sévérité (miroir de la table § 9 : deux erreurs, trois avertissements). */
        public val severite: ProblemesPreview.Severite,
        /** Balise de la vue fautive (telle que lue — jamais traduite). */
        public val balise: String,
        /** Identifiant de la vue fautive (NOM, sans `@id/` — null si absent). */
        public val id: String?,
        /** Début de l'élément fautif dans le texte (le correctif s'y pose). */
        public val debut: Int,
        /** Fin de l'élément fautif (-1 = jamais fermé, frappe en cours). */
        public val fin: Int,
        /**
         * Les attributs en cause, QUALIFIÉS (« android:layout_weight ») —
         * le correctif les retire en UNE édition ; vide pour
         * [ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT] (qui
         * POSE un attribut).
         */
        public val attributs: List<String>,
        /** La cible VISÉE, valeur brute (« @id/x ») — règle ou ancre ; null sinon. */
        public val cible: String?,
        /**
         * Le libellé du PARENT pour le message orphelin (nom de classe,
         * jamais traduit — miroir `FAM_LABEL`) ; vide pour les autres genres.
         */
        public val libelleParent: String,
        /**
         * Le CHEMIN FERMÉ du cycle (NOMS, première vue en tête, retour en
         * fin — miroir `[@id/a, @id/b, @id/a]` du bandeau § 12) ;
         * [ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE] seul, vide sinon.
         */
        public val cycle: List<String>,
        /** L'identité du CONTENEUR de la boucle (« @id/x » ou balise) ; vide sinon. */
        public val conteneur: String,
    )

    /**
     * Évalue les CINQ règles sur le TEXTE du document (miroir de
     * `computeProblems`, DANS SON ORDRE) : par vue du document
     * (pré-ordre), orphelin puis référence introuvable puis ancre
     * invalide puis comportement manquant ; la DÉPENDANCE CIRCULAIRE en
     * dernier, rattachée à la PREMIÈRE vue de la boucle.
     *
     * Un document vide, étranger au XML ou interrompu ne lève JAMAIS
     * d'exception (tolérance du lecteur) : la liste sort vide ou
     * tronquée au préfixe déjà lu.
     */
    public fun evaluer(texte: String): List<Probleme> {
        val noeuds = ArbreAttributsXml.lire(texte)
        val problemes = mutableListOf<Probleme>()
        for (indice in noeuds.indices) {
            val noeud = noeuds[indice]
            val parent =
                noeud.indiceParent
                    .takeIf { it >= 0 }
                    ?.let { noeuds.getOrNull(it) } ?: continue
            val famille = FamilleParent.deBalise(parent.balise)
            problemes += orphelinsDe(noeud, famille, parent)
            if (famille == FamilleParent.RELATIVE) {
                problemes += referencesIntrouvables(noeud, parent)
            }
            if (famille == FamilleParent.COORDINATOR) {
                problemes += problemesDuCoordonnateur(noeud, parent)
            }
        }
        boucleDuDocument(noeuds)?.let { problemes.add(it) }
        return problemes
    }

    /**
     * La première DÉPENDANCE CIRCULAIRE du texte (spec § 12 — le bandeau
     * « Dépendance circulaire dans … » et son bouton « Ligne N »), ou null.
     */
    public fun boucleSurTexte(texte: String): Probleme? =
        evaluer(texte).firstOrNull { it.genre == ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE }

    /**
     * Le message de rendu porte-t-il la signature de l'exception de boucle
     * `RelativeLayout` (LayoutLib — « Circular dependencies cannot exist
     * in RelativeLayout », spec § 12) ? Le bandeau y compose le cycle lu
     * dans le TEXTE au lieu du message brut.
     */
    public fun estBoucleRelativeLayout(messageRendu: String): Boolean = SIGNATURE_BOUCLE in messageRendu

    /** La valeur posée par le correctif « Ajouter le comportement » (miroir `run`). */
    public const val COMPORTEMENT_DEFILEMENT: String = "@string/appbar_scrolling_view_behavior"

    /**
     * La LIGNE (1-based) de la première vue de la boucle (spec § 12 —
     * bouton « Ligne N » du bandeau) : l'aligneur qui calcule les numéros
     * de ligne est INTERNE au module, le bandeau passe par ce passeur.
     */
    public fun ligneDeLaBoucle(
        texte: String,
        probleme: Probleme,
    ): Int = AligneurElementsXml.numeroLigne(texte, probleme.debut)

    /**
     * L'ATTRIBUT_ORPHELIN d'un nœud (spec § 9 — miroir `FAM_ATTRS`) : UN
     * problème quand il y a au moins un orphelin, TOUS les attributs en
     * cause dedans (le correctif les retire ensemble).
     */
    private fun orphelinsDe(
        noeud: ArbreAttributsXml.Noeud,
        famille: FamilleParent,
        parent: ArbreAttributsXml.Noeud,
    ): List<Probleme> {
        val orphelins = RelationsParents.attributsOrphelins(famille, noeud.attributs)
        if (orphelins.isEmpty()) return emptyList()
        return listOf(
            Probleme(
                genre = ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN,
                severite = ProblemesPreview.Severite.AVERTISSEMENT,
                balise = noeud.balise,
                id = noeud.id,
                debut = noeud.debut,
                fin = noeud.fin,
                attributs = orphelins,
                cible = null,
                libelleParent = libelleDuParent(famille, parent.balise),
                cycle = emptyList(),
                conteneur = "",
            ),
        )
    }

    /** Les règles `RelativeLayout` vers un FRÈRE ABSENT des frères connus (spec § 9). */
    private fun referencesIntrouvables(
        noeud: ArbreAttributsXml.Noeud,
        parent: ArbreAttributsXml.Noeud,
    ): List<Probleme> {
        val freres = parent.enfants.mapNotNull { it.id }.toHashSet()
        // L'ordre des règles de la plateforme (miroir `REL_REFS.forEach`),
        // pas celui du document — le même pour toutes les vues.
        return RelationsParents.REGLES_VERS_FRERE.mapNotNull { regle ->
            val valeur = noeud.attributs["android:$regle"]
            val cible = ArbreAttributsXml.nomId(valeur)
            if (valeur == null || cible == null || cible in freres) {
                null
            } else {
                Probleme(
                    genre = ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE,
                    severite = ProblemesPreview.Severite.AVERTISSEMENT,
                    balise = noeud.balise,
                    id = noeud.id,
                    debut = noeud.debut,
                    fin = noeud.fin,
                    attributs = listOf("android:$regle"),
                    cible = valeur,
                    libelleParent = "",
                    cycle = emptyList(),
                    conteneur = "",
                )
            }
        }
    }

    /** L'ancre invalide et le comportement manquant d'un enfant de `CoordinatorLayout` (spec § 9). */
    private fun problemesDuCoordonnateur(
        noeud: ArbreAttributsXml.Noeud,
        parent: ArbreAttributsXml.Noeud,
    ): List<Probleme> {
        val problemes = mutableListOf<Probleme>()
        // Valeur VIDE = absent (miroir `ex()` falsy de la maquette : une
        // frappe en cours ne signale pas une ancre à demi effacée).
        val ancre = noeud.attributs[ATTRIBUT_ANCRE]?.takeUnless { it.isEmpty() }
        if (ancre != null) {
            // INVALIDE : une vue inconnue ou hors du sous-arbre du parent,
            // la vue ancrée elle-même, ou un de ses descendants (spec § 3.3
            // — [RelationsParents.contientId] couvre la vue elle-même, les
            // trois refus de la maquette se lisent dessus).
            val cible = ArbreAttributsXml.nomId(ancre)
            val invalide =
                cible == null ||
                    cible == noeud.id ||
                    !RelationsParents.contientId(parent, cible) ||
                    RelationsParents.contientId(noeud, cible)
            if (invalide) {
                problemes.add(
                    Probleme(
                        genre = ProblemesPreview.GenreProbleme.ANCRE_INVALIDE,
                        severite = ProblemesPreview.Severite.ERREUR,
                        balise = noeud.balise,
                        id = noeud.id,
                        debut = noeud.debut,
                        fin = noeud.fin,
                        // Le correctif retire l'ancre ET sa gravité (spec § 9).
                        attributs = listOf(ATTRIBUT_ANCRE, ATTRIBUT_ANCRE_GRAVITE),
                        cible = ancre,
                        libelleParent = "",
                        cycle = emptyList(),
                        conteneur = "",
                    ),
                )
            }
        }
        val barrePresente =
            parent.enfants.any { FamilleParent.deBalise(it.balise) == FamilleParent.APPBAR }
        // Vue DÉFILANTE : `RecyclerView` ou `*ScrollView` (miroir
        // `v.kind === 'recycler' || /ScrollView$/.test(v.cls)`).
        val nomBalise = noeud.balise.substringAfterLast('.')
        val defilante = nomBalise == BALISE_RECYCLER || nomBalise.endsWith(SUFFIXE_SCROLL_VIEW)
        if (barrePresente && defilante && noeud.attributs[ATTRIBUT_COMPORTEMENT].isNullOrEmpty()) {
            problemes.add(
                Probleme(
                    genre = ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT,
                    severite = ProblemesPreview.Severite.AVERTISSEMENT,
                    balise = noeud.balise,
                    id = noeud.id,
                    debut = noeud.debut,
                    fin = noeud.fin,
                    attributs = emptyList(),
                    cible = null,
                    libelleParent = "",
                    cycle = emptyList(),
                    conteneur = "",
                ),
            )
        }
        return problemes
    }

    /**
     * La première boucle de règles du document (spec § 12) : PREMIER
     * `RelativeLayout` porteur (ordre du document), rattachée à la
     * PREMIÈRE vue du cycle — le correctif « Casser la boucle » retire
     * les règles de CETTE vue qui visent le SECOND maillon (miroir
     * `REL_REFS.forEach(r => … cy.via)`).
     */
    private fun boucleDuDocument(noeuds: List<ArbreAttributsXml.Noeud>): Probleme? =
        noeuds
            .asSequence()
            .filter { FamilleParent.deBalise(it.balise) == FamilleParent.RELATIVE }
            .mapNotNull(::boucleDuParent)
            .firstOrNull()

    /** La boucle d'UN parent `RelativeLayout` (null : saine ou sans règle à casser).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les CINQ gardes
     * de la boucle (cycle, deux maillons, premier nœud, règles à casser)
     * sont autant d'issues NATURELLES (même patron que
     * `MoteurPreviewXml.traiter`, v0.133.3 — anti-boucle + gardes). */
    @Suppress("ReturnCount")
    private fun boucleDuParent(parent: ArbreAttributsXml.Noeud): Probleme? {
        val cycle = RelationsParents.cycle(parent) ?: return null
        val premier = cycle.firstOrNull() ?: return null
        val via = cycle.getOrNull(1) ?: return null
        val noeudPremier = parent.enfants.firstOrNull { it.id == premier } ?: return null
        val aCasser =
            noeudPremier.attributs.entries
                .filter { (cle, valeur) ->
                    cle.substringAfter(':') in RelationsParents.REGLES_VERS_FRERE &&
                        ArbreAttributsXml.nomId(valeur) == via
                }.map { it.key }
        if (aCasser.isEmpty()) return null
        return Probleme(
            genre = ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            severite = ProblemesPreview.Severite.ERREUR,
            balise = noeudPremier.balise,
            id = noeudPremier.id,
            debut = noeudPremier.debut,
            fin = noeudPremier.fin,
            attributs = aCasser,
            cible = null,
            libelleParent = "",
            cycle = cycle,
            conteneur = parent.id?.let { "@id/$it" } ?: parent.balise,
        )
    }

    /** Le libellé du parent du message orphelin (nom de classe — miroir `FAM_LABEL`, jamais traduit). */
    private fun libelleDuParent(
        famille: FamilleParent,
        balise: String,
    ): String =
        when (famille) {
            FamilleParent.CONSTRAINT -> "ConstraintLayout"

            FamilleParent.LINEAR -> "LinearLayout"

            FamilleParent.FRAME -> "FrameLayout"

            FamilleParent.RELATIVE -> "RelativeLayout"

            FamilleParent.COORDINATOR -> "CoordinatorLayout"

            FamilleParent.APPBAR -> "AppBarLayout"

            // AUCUN ne peut être un parent lu ; AUTRE ne nomme aucune
            // classe de la table — la BALISE du parent est le libellé
            // honnête (GridLayout, TableLayout…).
            FamilleParent.AUCUN, FamilleParent.AUTRE -> balise.substringAfterLast('.')
        }

    /** La signature de l'exception de boucle dans un message de rendu LayoutLib. */
    private const val SIGNATURE_BOUCLE: String = "Circular dependencies"

    /** L'attribut d'ancre de `CoordinatorLayout`. */
    private const val ATTRIBUT_ANCRE: String = "app:layout_anchor"

    /** La gravité d'ancrage — retirée AVEC l'ancre (spec § 9). */
    private const val ATTRIBUT_ANCRE_GRAVITE: String = "app:layout_anchorGravity"

    /** L'attribut de comportement de défilement. */
    private const val ATTRIBUT_COMPORTEMENT: String = "app:layout_behavior"

    /** La balise du composant liste recyclée. */
    private const val BALISE_RECYCLER: String = "RecyclerView"

    /** Le suffixe des composants défilants verticaux. */
    private const val SUFFIXE_SCROLL_VIEW: String = "ScrollView"
}
