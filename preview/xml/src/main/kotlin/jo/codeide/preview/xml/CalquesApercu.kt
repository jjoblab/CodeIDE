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
 * frère précédent (miroir `drawConstraints` de la maquette).
 */
public object CalquesApercu {
    /**
     * Dimensions affichables de la vue : « 120×48dp » (converties du
     * px bitmap par la densité du PROFIL — l'aperçu rend aux dimensions
     * du profil, pas de l'appareil hôte) ; repli « px » si la densité
     * n'est pas connue (0).
     */
    public fun dimensions(
        largeurPx: Int,
        hauteurPx: Int,
        densiteDpi: Int,
    ): String =
        if (densiteDpi > 0) {
            val largeurDp = pxVersDp(largeurPx, densiteDpi)
            val hauteurDp = pxVersDp(hauteurPx, densiteDpi)
            "$largeurDp×${hauteurDp}dp"
        } else {
            "$largeurPx×${hauteurPx}px"
        }

    /** Conversion px → dp (arrondie — un label n'est pas un convertisseur de layout). */
    public fun pxVersDp(
        px: Int,
        densiteDpi: Int,
    ): Int = Math.round(px * DENSITE_REFERENCE.toFloat() / densiteDpi)

    /**
     * Étiquette complète de la sélection : nom (libellé existant) puis
     * dimensions — « TextView @+id/titre · 120×48dp » ; sans dimensions
     * (vue dégénérée), le nom seul.
     */
    public fun libelleSelection(
        nom: String,
        dimensions: String?,
    ): String =
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
    public fun indicesFreres(
        vues: List<InfoVueRendu>,
        indiceSelection: Int,
    ): List<Int> {
        if (indiceSelection !in vues.indices) return emptyList()
        val parent = vues[indiceSelection].indiceParent
        return vues.indices.filter { indice -> indice != indiceSelection && vues[indice].indiceParent == parent }
    }

    /**
     * L'indice du PARENT de la vue sélectionnée (calque « parent en
     * pointillés ») ; -1 si la sélection est racine ou hors bornes.
     */
    public fun indiceParent(
        vues: List<InfoVueRendu>,
        indiceSelection: Int,
    ): Int =
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

    /** Seuil px sous lequel un écart n'est PAS tracé (miroir maquette 0,5 dp). */
    private const val SEUIL_ECART_PX: Int = 1

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
    public fun lignesContraintes(
        vues: List<InfoVueRendu>,
        densiteDpi: Int,
    ): List<LigneContrainte> {
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

    /** Densité de référence Android (dpi) — le facteur de la conversion dp. */
    private const val DENSITE_REFERENCE: Int = 160
}
