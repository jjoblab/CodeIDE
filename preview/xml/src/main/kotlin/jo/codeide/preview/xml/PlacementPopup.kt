package jo.codeide.preview.xml

/**
 * PLACEMENT PAR POPUP (PE7c, maquette `docs/mockups/apercu-xml.html` §
 * « Bulle, palette et popup de placement ») — FONCTIONS PURES sur l'arbre
 * aplati [InfoVueRendu] : « Le popup de placement affiche l'arbre des
 * vues rendues, les positions Avant, Dedans, Après, et dessine le repère
 * en direct sur l'aperçu. »
 *
 * Miroir fidèle de la maquette :
 * - `positionsAutorisees` = `normPos` + `pos.disabled` — DEDANS exige un
 *   CONTENEUR, AVANT/APRÈS se refusent sur la RACINE (elle n'a pas de
 *   frère) ;
 * - [positionInitiale] = `showPlace` — la sélection courante devient la
 *   référence, DEDANS si elle porte des enfants sinon APRÈS ;
 * - [cible] = `placeTarget` — DEDANS → (référence, fin de ses enfants) ;
 *   AVANT/APRÈS → (parent de la référence, index parmi ses frères) ;
 * - [libelleResume] = la ligne `res` — « Dans X, position n sur total »
 *   (les libellés FR/EN restent au chrome, la fonction porte les DONNÉES).
 *
 * La cible produite est la MÊME [ZonesDepot.CibleDepot] que le dépôt
 * PE7b : le repère en direct réutilise les zones de dépôt dessinées par
 * [VuePreviewXml], et l'insertion passe par [InsertionXml.inserer] —
 * une seule écriture dans la session.
 */
public object PlacementPopup {
    /** La position demandée par rapport à la vue de référence (miroir `.pos`). */
    public enum class Position {
        AVANT,
        DEDANS,
        APRES,
    }

    /**
     * Les positions AUTORISÉES pour la référence (miroir `normPos` + les
     * `disabled` de `drawPlace`) : la RACINE n'offre que DEDANS (si elle
     * est conteneur — un document vide se refuse), une FEUILLE n'offre
     * pas DEDANS, un conteneur intermédiaire offre les trois.
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
            // Racine (ou première vue du document) : DEDANS seul si conteneur.
            return if (reference.conteneur) setOf(Position.DEDANS) else emptySet()
        }
        return if (reference.conteneur) {
            setOf(Position.AVANT, Position.DEDANS, Position.APRES)
        } else {
            setOf(Position.AVANT, Position.APRES)
        }
    }

    /**
     * La position PAR DÉFAUT pour la référence (miroir `showPlace` :
     * `pos = viewOf(ref).children ? 'in' : 'after'` ; la racine retombe
     * sur DEDANS par [positionsAutorisees]).
     */
    public fun positionInitiale(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
    ): Position? {
        val autorisees = positionsAutorisees(vues, indiceRef)
        if (Position.DEDANS in autorisees) return Position.DEDANS
        return Position.APRES.takeIf { it in autorisees }
    }

    /**
     * La cible de dépôt RÉSULTANTE (miroir `placeTarget`) — la même forme
     * que le dépôt PE7b : le contrôleur pose le repère
     * ([VuePreviewXml.cibleDepot]) et compose l'insertion
     * ([InsertionXml.inserer]) avec elle. `null` = référence hors arbre
     * ou position non autorisée (jamais de cible devinée).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est
     * une issue DISTINCTE du refus (miroir placeTarget).
     */
    @Suppress("ReturnCount")
    public fun cible(
        vues: List<InfoVueRendu>,
        indiceRef: Int,
        position: Position,
    ): ZonesDepot.CibleDepot? {
        val reference = vues.getOrNull(indiceRef) ?: return null
        if (position !in positionsAutorisees(vues, indiceRef)) return null
        if (position == Position.DEDANS) {
            // DEDANS : la référence devient le parent, l'insertion va en
            // FIN de ses enfants (miroir `index: f.n.children.length`).
            return ZonesDepot.CibleDepot(indiceParent = indiceRef, index = enfantsDe(vues, indiceRef).size)
        }
        // AVANT/APRÈS : le parent de la référence, l'index = la position
        // de la référence parmi ses frères (+1 pour APRÈS).
        val freres = enfantsDe(vues, reference.indiceParent)
        val indexReference = freres.indexOf(indiceRef)
        if (indexReference < 0) return null
        val decalage = if (position == Position.APRES) 1 else 0
        return ZonesDepot.CibleDepot(
            indiceParent = reference.indiceParent,
            index = indexReference + decalage,
        )
    }

    /** Les indices des enfants DIRECTS, dans l'ordre du document. */
    public fun enfantsDe(
        vues: List<InfoVueRendu>,
        indiceParent: Int,
    ): List<Int> = vues.indices.filter { indice -> indice != indiceParent && vues[indice].indiceParent == indiceParent }

    /**
     * Les DONNÉES du résumé (miroir `res.innerHTML`) : le libellé du
     * parent cible (« Classe · @+id/x » — sans id, la classe seule), la
     * position d'insertion (1-based) et le total (miroir « position n sur
     * total » où total = enfants + 1).
     */
    public data class ResumePlacement(
        val libelleParent: String,
        val position: Int,
        val total: Int,
    )

    /** Le résumé de la cible (miroir `res`) — `null` si la cible est refusée.
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
        )
    }
}
