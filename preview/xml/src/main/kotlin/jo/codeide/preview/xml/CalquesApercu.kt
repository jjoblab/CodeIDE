package jo.codeide.preview.xml

/**
 * Calculs des CALQUES de sélection (PE6a, maquette
 * `docs/mockups/apercu-xml.html` § « Calques ») — fonctions PURES sur
 * l'arbre aplati [InfoVueRendu] (testées sans Android, miroir
 * [SelectionPreview]).
 *
 * PE6a « sans nouvelle donnée » : TOUT part des champs existants —
 * bounds absolues, libellé, [InfoVueRendu.indiceParent] (l'arbre est
 * reconstruisible côté app).
 *
 * PE6b/c : les données enrichies ([InfoVueRendu.margesGauche]…,
 * [InfoVueRendu.paddingGauche]…) alimentent le box-model, et
 * [lignesContraintes] calcule les écarts aux bords du parent et au
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — les calques
 * PE6c (contraintes) + PE12e (relations) vivent au complet dans cet
 * objet, contrat du fichier (précédent [InsertionXml]).
 */
@Suppress("TooManyFunctions")
public object CalquesApercu {
    /**
     * Dimensions affichables de la vue : « 120×48dp » (converties du
     * px bitmap par la densité du PROFIL — l'aperçu rend aux dimensions
     * du profil, pas de l'appareil hôte) ; repli « px » si la densité
     * n'est pas connue (0).
     */
    public fun dimensions(largeurPx: Int, hauteurPx: Int, densiteDpi: Int): String = if (densiteDpi > 0) {
        val largeurDp = pxVersDp(largeurPx, densiteDpi)
        val hauteurDp = pxVersDp(hauteurPx, densiteDpi)
        "$largeurDp×${hauteurDp}dp"
    } else {
        "$largeurPx×${hauteurPx}px"
    }

    /** Conversion px → dp (arrondie — un label n'est pas un convertisseur de layout). */
    public fun pxVersDp(px: Int, densiteDpi: Int): Int = Math.round(px * DENSITE_REFERENCE.toFloat() / densiteDpi)

    /**
     * Étiquette complète de la sélection : nom (libellé existant) puis
     * dimensions — « TextView @+id/titre · 120×48dp » ; sans dimensions
     * (vue dégénérée), le nom seul.
     */
    public fun libelleSelection(nom: String, dimensions: String?): String =
        if (dimensions.isNullOrBlank() || nom.isBlank()) {
            nom
        } else {
            "$nom · $dimensions"
        }

    /**
     * Les indices des FRÈRES de la vue sélectionnée (même parent, elle
     * exclue) — le calque « frères en filigrane ». La racine (-1)
     * compte : les autres racines du tableau en sont les frères.
     */
    public fun indicesFreres(vues: List<InfoVueRendu>, indiceSelection: Int): List<Int> {
        if (indiceSelection !in vues.indices) return emptyList()
        val parent = vues[indiceSelection].indiceParent
        return vues.indices.filter { indice -> indice != indiceSelection && vues[indice].indiceParent == parent }
    }

    /**
     * L'indice du PARENT de la vue sélectionnée (calque « parent en
     * pointillés ») ; -1 si la sélection est racine ou hors bornes.
     */
    public fun indiceParent(vues: List<InfoVueRendu>, indiceSelection: Int): Int =
        if (indiceSelection in vues.indices) {
            vues[indiceSelection].indiceParent
        } else {
            -1
        }

    /**
     * UNE ligne de contrainte à dessiner (calque « Contraintes »,
     * PE6c — miroir `drawConstraints` de la maquette) : segment en
     * coordonnées bitmap (px) + valeur de l'écart en dp (affichée).
     */
    public data class LigneContrainte(
        /** Abscisse du début du segment (px bitmap). */
        val x1: Int,
        /** Ordonnée du début du segment (px bitmap). */
        val y1: Int,
        /** Abscisse de la fin du segment (px bitmap). */
        val x2: Int,
        /** Ordonnée de la fin du segment (px bitmap). */
        val y2: Int,
        /** Écart mesuré, en dp du profil (affiché dans la pill). */
        val valeurDp: Int,
        /** La ligne est-elle verticale (mesure un écart en Y) ? */
        val verticale: Boolean,
    )

    /**
     * UNE ligne de relation du calque « Relations » (PE12e — miroir
     * `drawRelations` de la maquette) : segment en coordonnées bitmap
     * (px), plein pour les règles de CÔTÉ et les bords du parent,
     * pointillé pour les alignements, les centres et les ancres.
     */
    public data class LigneRelation(
        /** Abscisse du début du segment (px bitmap). */
        val x1: Int,
        /** Ordonnée du début du segment (px bitmap). */
        val y1: Int,
        /** Abscisse de la fin du segment (px bitmap). */
        val x2: Int,
        /** Ordonnée de la fin du segment (px bitmap). */
        val y2: Int,
        /** Trait POINTILLÉ (alignement, centre, ancre) — sinon plein. */
        val pointillee: Boolean,
        /** Étiquette de la ligne (nom de la règle, « parent », « anchor · … ») —
         *  dessinée pour la vue SÉLECTIONNÉE seulement (§ 8). */
        val etiquette: String?,
        /** Indice de la vue SOURCE de la relation (porte l'étiquette). */
        val indiceVue: Int,
    )

    /**
     * Les lignes de RELATIONS de chaque parent `RelativeLayout` ou
     * `CoordinatorLayout` du document (PE12e — spec § 8, miroir
     * `drawRelations`) : règles de côté en trait PLEIN (du milieu de la
     * vue au bord du frère), alignements en POINTILLÉ le long du bord
     * partagé, bords du parent en trait PLEIN, centres en pointillé au
     * milieu de la vue, ancre `CoordinatorLayout` en pointillé du point
     * d'ancrage au centre de la vue ancrée.
     *
     * Les règles se lisent sur l'ARBRE DE TEXTE [arbre]
     * ([RelationsParents] — elles restent justes quand le rendu
     * s'interrompt), les BORNES viennent du rendu [vues] : le pont se
     * fait par IDENTIFIANT (une vue sans id ne porte pas de règles).
     * Le parent `ConstraintLayout` racine garde ses lignes de contraintes
     * EXISTANTES ([lignesContraintes], miroir `drawConstraints` appelé
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * les gardes (non-conteneur, famille, texte absent) sont des issues DISTINCTES.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    public fun lignesRelations(vues: List<InfoVueRendu>, arbre: List<ArbreAttributsXml.Noeud>): List<LigneRelation> {
        val lignes = mutableListOf<LigneRelation>()
        for (parent in vues) {
            if (!parent.conteneur) continue
            val famille = FamilleParent.deNom(parent.famille) ?: FamilleParent.deBalise(parent.classe)
            if (famille != FamilleParent.RELATIVE && famille != FamilleParent.COORDINATOR) continue
            val noeudParent = noeudDe(arbre, parent) ?: continue
            for (relation in RelationsParents.relations(noeudParent)) {
                lignes.addAll(lignesDeRelation(vues, noeudParent, relation))
            }
        }
        return lignes
    }

    /**
     * Les lignes de contrainte des ENFANTS DIRECTS de la racine
     * (PE6c — miroir `drawConstraints` : écart au frère précédent en
     * haut, écarts aux bords gauche/droit de la racine) — géométrie
     * PURE sur les bounds, aucune donnée hors [InfoVueRendu].
     *
     * Frère précédent : en pré-ordre, la DERNIÈRE vue de même parent
     * rencontrée avant la vue courante (les sous-arbres des frères ont
     * un autre parent — la propriété pré-ordre garantit l'ordre).
     */
    public fun lignesContraintes(vues: List<InfoVueRendu>, densiteDpi: Int): List<LigneContrainte> {
        val racine = vues.firstOrNull { it.indiceParent == -1 }
        // Densité inconnue : la conversion dp est impossible — rien.
        if (racine == null || densiteDpi <= 0) return emptyList()
        val lignes = mutableListOf<LigneContrainte>()
        var precedent: InfoVueRendu? = null
        for (vue in vues) {
            if (vue.indiceParent != 0) continue
            val centreX = (vue.gauche + vue.droite) / 2
            val centreY = (vue.haut + vue.bas) / 2
            // Écart vertical : bas du frère précédent (ou haut de la racine) → haut de la vue.
            val bordPrecedent = precedent?.bas ?: racine.haut
            val ecartHaut = vue.haut - bordPrecedent
            if (ecartHaut >= SEUIL_ECART_PX) {
                lignes.add(
                    LigneContrainte(
                        x1 = centreX,
                        y1 = bordPrecedent,
                        x2 = centreX,
                        y2 = vue.haut,
                        valeurDp = pxVersDp(ecartHaut, densiteDpi),
                        verticale = true,
                    ),
                )
            }
            // Écarts horizontaux : bords gauche/droit de la racine → la vue.
            val ecartGauche = vue.gauche - racine.gauche
            if (ecartGauche >= SEUIL_ECART_PX) {
                lignes.add(
                    LigneContrainte(
                        x1 = racine.gauche,
                        y1 = centreY,
                        x2 = vue.gauche,
                        y2 = centreY,
                        valeurDp = pxVersDp(ecartGauche, densiteDpi),
                        verticale = false,
                    ),
                )
            }
            val ecartDroite = racine.droite - vue.droite
            if (ecartDroite >= SEUIL_ECART_PX) {
                lignes.add(
                    LigneContrainte(
                        x1 = vue.droite,
                        y1 = centreY,
                        x2 = racine.droite,
                        y2 = centreY,
                        valeurDp = pxVersDp(ecartDroite, densiteDpi),
                        verticale = false,
                    ),
                )
            }
            precedent = vue
        }
        return lignes
    }

    /**
     * Les lignes d'UNE relation (miroir le `switch` de règle de
     * `drawRelations`).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (vue, cible, ancre introuvables) est une issue DISTINCTE du refus (X4).
     */
    @Suppress("ReturnCount")
    private fun lignesDeRelation(
        vues: List<InfoVueRendu>,
        noeudParent: ArbreAttributsXml.Noeud,
        relation: RelationsParents.Relation,
    ): List<LigneRelation> {
        val indiceVue = vues.indexOfFirst { it.idRes == relation.source }
        if (indiceVue < 0) return emptyList()
        val vue = vues[indiceVue]
        // Le NOM DE RÈGLE sans préfixe (« android:layout_below » → « below »,
        // miroir `REL_REFS` de la maquette).
        val regle = relation.attribut.substringAfterLast(':').removePrefix("layout_")
        return when (relation.type) {
            RelationsParents.TypeRelation.REGLE_FRERE -> {
                val indiceCible = vues.indexOfFirst { it.idRes == relation.cible }
                if (indiceCible < 0) return emptyList()
                lignesVersFrere(vue, vues[indiceCible], regle, regle, indiceVue)
            }

            RelationsParents.TypeRelation.REGLE_PARENT -> {
                lignesVersParent(vue, vueParente(vues, indiceVue), regle, "parent", indiceVue)
            }

            RelationsParents.TypeRelation.ANCRE -> {
                val indiceAncre = vues.indexOfFirst { it.idRes == relation.cible }
                if (indiceAncre < 0) return emptyList()
                // La gravité de l'ancre se lit sur le TEXTE (miroir
                // `ex(k, 'app:layout_anchorGravity') || 'center'`).
                val gravite =
                    noeudParent.enfants
                        .firstOrNull { it.id == relation.source }
                        ?.attributs
                        ?.get("app:layout_anchorGravity")
                        .orEmpty()
                        .ifEmpty { "center" }
                lignesDAncre(vue, vues[indiceAncre], gravite, "anchor · $gravite", indiceVue)
            }

            RelationsParents.TypeRelation.CONTRAINTE -> {
                // Les contraintes gardent leurs lignes DÉDIÉES
                // ([lignesContraintes], miroir `drawConstraints`).
                emptyList()
            }
        }
    }

    /** La vue PARENT d'une vue (par indice de parenté du rendu). */
    private fun vueParente(vues: List<InfoVueRendu>, indiceVue: Int): InfoVueRendu = vues[vues[indiceVue].indiceParent]

    /** Les lignes d'une règle VERS UN FRÈRE (miroir `REL_REFS.forEach` de `drawRelations`). */
    private fun lignesVersFrere(
        vue: InfoVueRendu,
        cible: InfoVueRendu,
        regle: String,
        etiquette: String,
        indiceVue: Int,
    ): List<LigneRelation> {
        val centreX = (vue.gauche + vue.droite) / DEUX_I
        val centreY = (vue.haut + vue.bas) / DEUX_I
        val ligne: List<Int> =
            when (regle) {
                "below" -> {
                    listOf(centreX, vue.haut, centreX, cible.bas)
                }

                "above" -> {
                    listOf(centreX, vue.bas, centreX, cible.haut)
                }

                "toEndOf", "toRightOf" -> {
                    listOf(vue.gauche, centreY, cible.droite, centreY)
                }

                "toStartOf", "toLeftOf" -> {
                    listOf(vue.droite, centreY, cible.gauche, centreY)
                }

                "alignStart", "alignLeft" -> {
                    listOf(cible.gauche, minOf(vue.haut, cible.haut), cible.gauche, maxOf(vue.bas, cible.bas))
                }

                "alignEnd", "alignRight" -> {
                    listOf(cible.droite, minOf(vue.haut, cible.haut), cible.droite, maxOf(vue.bas, cible.bas))
                }

                "alignTop" -> {
                    listOf(minOf(vue.gauche, cible.gauche), cible.haut, maxOf(vue.droite, cible.droite), cible.haut)
                }

                "alignBottom" -> {
                    listOf(minOf(vue.gauche, cible.gauche), cible.bas, maxOf(vue.droite, cible.droite), cible.bas)
                }

                else -> {
                    listOf(vue.gauche, centreY, cible.gauche, centreY)
                }
            }
        // Règles de CÔTÉ en trait plein, ALIGNEMENTS en pointillé (§ 8).
        val pointillee = regle.startsWith("align")
        return listOf(ligne(vue = indiceVue, points = ligne, pointillee = pointillee, etiquette = etiquette))
    }

    /**
     * Les lignes d'une règle VERS LE PARENT (miroir `alignParent*` +
     * `center*`) : les bords en trait PLEIN, les centres en POINTILLÉ —
     * chaque drapeau est INDEPENDANT (une vue peut cumuler
     * `alignParentStart` et `alignParentTop`, `centerInParent` trace les
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — les SIX drapeaux
     * indépendants de la maquette (quatre bords + deux centres) sont le
     * contrat du dessin, pas une accumulation.
     */
    @Suppress("LongMethod")
    private fun lignesVersParent(
        vue: InfoVueRendu,
        parent: InfoVueRendu,
        regle: String,
        etiquette: String,
        indiceVue: Int,
    ): List<LigneRelation> {
        val centreX = (vue.gauche + vue.droite) / DEUX_I
        val centreY = (vue.haut + vue.bas) / DEUX_I
        val lignes = mutableListOf<LigneRelation>()
        if (regle == "alignParentStart" || regle == "alignParentLeft") {
            lignes.add(
                ligne(
                    indiceVue,
                    listOf(parent.gauche + parent.paddingGauche, centreY, vue.gauche, centreY),
                    false,
                    etiquette,
                ),
            )
        }
        if (regle == "alignParentEnd" || regle == "alignParentRight") {
            lignes.add(
                ligne(
                    indiceVue,
                    listOf(vue.droite, centreY, parent.droite - parent.paddingDroite, centreY),
                    false,
                    etiquette,
                ),
            )
        }
        if (regle == "alignParentTop") {
            lignes.add(
                ligne(
                    indiceVue,
                    listOf(centreX, parent.haut + parent.paddingHaut, centreX, vue.haut),
                    false,
                    etiquette,
                ),
            )
        }
        if (regle == "alignParentBottom") {
            lignes.add(
                ligne(indiceVue, listOf(centreX, vue.bas, centreX, parent.bas - parent.paddingBas), false, etiquette),
            )
        }
        // Les CENTRES ne portent PAS d'étiquette (miroir : `labels.push`
        // n'existe que pour `alignParent*`).
        if (regle == "centerHorizontal" || regle == "centerInParent") {
            lignes.add(
                ligne(
                    indiceVue,
                    listOf(
                        (parent.gauche + parent.droite) / DEUX_I,
                        vue.haut,
                        (parent.gauche + parent.droite) / DEUX_I,
                        vue.bas,
                    ),
                    true,
                    null,
                ),
            )
        }
        if (regle == "centerVertical" || regle == "centerInParent") {
            lignes.add(
                ligne(
                    indiceVue,
                    listOf(
                        vue.gauche,
                        (parent.haut + parent.bas) / DEUX_I,
                        vue.droite,
                        (parent.haut + parent.bas) / DEUX_I,
                    ),
                    true,
                    null,
                ),
            )
        }
        return lignes
    }

    /** La ligne d'une ANCRE `CoordinatorLayout` : point d'ancrage (selon [gravite]) → centre de la vue. */
    private fun lignesDAncre(
        vue: InfoVueRendu,
        ancre: InfoVueRendu,
        gravite: String,
        etiquette: String,
        indiceVue: Int,
    ): List<LigneRelation> {
        // Le point d'ancrage suit `anchorGravity` (miroir `px`/`py` :
        // fin/droite → bord droit, début/gauche → bord gauche, sinon
        // centre ; bas → bas, haut → haut, sinon centre).
        // La gravité est un ENSEMBLE de drapeaux (« bottom|end ») — le
        // point d'ancrage suit le drapeau présent (miroir `ag.has(…)`).
        val x =
            when {
                "end" in gravite || "right" in gravite -> ancre.droite
                "start" in gravite || "left" in gravite -> ancre.gauche
                else -> (ancre.gauche + ancre.droite) / DEUX_I
            }
        val y =
            when {
                "bottom" in gravite -> ancre.bas
                "top" in gravite -> ancre.haut
                else -> (ancre.haut + ancre.bas) / DEUX_I
            }
        return listOf(
            ligne(
                indiceVue,
                listOf(x, y, (vue.gauche + vue.droite) / DEUX_I, (vue.haut + vue.bas) / DEUX_I),
                true,
                etiquette,
            ),
        )
    }

    /** Une ligne depuis quatre points (px bitmap). */
    private fun ligne(vue: Int, points: List<Int>, pointillee: Boolean, etiquette: String?): LigneRelation =
        LigneRelation(
            x1 = points[0],
            y1 = points[1],
            x2 = points[2],
            y2 = points[3],
            pointillee = pointillee,
            etiquette = etiquette,
            indiceVue = vue,
        )

    /** Le nœud de l'arbre de texte correspondant à une VUE (pont par identifiant). */
    private fun noeudDe(arbre: List<ArbreAttributsXml.Noeud>, vue: InfoVueRendu): ArbreAttributsXml.Noeud? =
        arbre.firstOrNull { it.id != null && it.id == vue.idRes }

    /** La moitié d'une somme (centres et milieux des segments). */
    private const val DEUX_I: Int = 2

    /** Seuil px sous lequel un écart n'est PAS tracé (miroir maquette 0,5 dp). */
    private const val SEUIL_ECART_PX: Int = 1

    /** Densité de référence Android (dpi) — le facteur de la conversion dp. */
    private const val DENSITE_REFERENCE: Int = 160
}
