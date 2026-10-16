package jo.codeide.preview.xml

/**
 * Calculs des CALQUES de sélection (PE6a, maquette
 * `docs/mockups/apercu-xml.html` § « Calques ») — fonctions PURES sur
 * l'arbre aplati [InfoVueRendu] (testées sans Android, miroir
 * [SelectionPreview]).
 *
 * PE6a « sans nouvelle donnée » : TOUT part des champs existants —
 * bounds absolues, libellé, [InfoVueRendu.indiceParent] (l'arbre est
 * reconstruisible côté app). Les marges/padding/attributs viendront en
 * PE6c ([InfoVueRendu] enrichie), les contraintes en PE6e-f.
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

    /** Densité de référence Android (dpi) — le facteur de la conversion dp. */
    private const val DENSITE_REFERENCE: Int = 160
}
