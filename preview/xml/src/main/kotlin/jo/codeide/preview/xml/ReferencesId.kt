package jo.codeide.preview.xml

/**
 * LES CANDIDATS d'un contrôle RÉFÉRENCE D'ID (PE12c — spec
 * `docs/PREVIEW_PARENTS.md` § 5.3, miroir `refCands` de la maquette) :
 * la moitié CALCULÉE de [CatalogueAttributs.ControleAttribut.ReferenceId]
 * — la DÉCLARATION vit au catalogue, les candidats vivent dans l'arbre
 * du document ([ArbreAttributsXml]).
 *
 * Portées (spec § 5.3) :
 *  - [CatalogueAttributs.ControleAttribut.ReferenceId.Portee.TOUT] :
 *    tous les `@id/` de l'arbre, sauf la vue (prévu `labelFor`,
 *    2e vague) ;
 *  - [CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES] :
 *    les frères DIRECTS de la vue, sauf elle-même (règles de
 *    RelativeLayout) — un candidat qui FERMERAIT une boucle est marqué
 *    [Entree.boucle] (le liage le désactive et le suffixe « (boucle) » —
 *    [RelationsParents.fermeraitBoucle]) ;
 *  - [CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE] :
 *    le sous-arbre du PARENT, hors la vue et ses descendants
 *    (`layout_anchor` — les nœuds du CoordinatorLayout) ; le sous-arbre
 *    de la VUE pour `ignoreGravity` et `liftOnScrollTargetViewId`.
 *
 * ÉCART CONSIGNÉ (ADR 0135 § 15.3, « la maquette est corrigée, pas le
 * moteur ») : le `refCands` de la maquette applique le filtre
 * « hors les descendants de la vue » AUSSI quand la base est la vue
 * elle-même — la liste d'`ignoreGravity`/`liftOnScrollTargetViewId` y
 * sort TOUJOURS vide ; la spec § 5.3 (« le sous-arbre de la vue ») fait
 * foi : les descendants de la vue sont candidats.
 *
 * FONCTIONS PURES, SANS ANDROID — testées en JVM ([ReferencesIdTest]).
 */
public object ReferencesId {
    /**
     * L'INDICE du nœud de [arbre] qui correspond à la vue sélectionnée :
     * par span EXACT (début/fin du `Noeud` = début/fin d'élément
     * d'`InfoVueRendu`, même épinglage que l'aligneur), sinon le premier
     * nœud QUI ENTOUBE le début (une vue d'un `<include>` gonflé par le
     * rendu n'a pas de nœud ici — `null`, le contrôle dégrade sans
     * candidats).
     */
    public fun indiceDe(
        arbre: List<ArbreAttributsXml.Noeud>,
        debut: Int,
        fin: Int,
    ): Int? {
        arbre.forEachIndexed { indice, noeud ->
            if (noeud.debut == debut && noeud.fin == fin) return indice
        }
        return arbre
            .indexOfFirst { noeud -> noeud.debut <= debut && (noeud.fin < 0 || debut < noeud.fin) }
            .takeIf { it >= 0 }
    }

    /**
     * Les OPTIONS d'une référence d'id : les [Entree] candidates (une
     * par identifiant — le liage préfixe `@id/`, désactive et suffixe
     * « (boucle) »), la [Note] du champ et la valeur [Options.introuvable]
     * — la valeur courante ABSENTE de l'arbre, que le liage ajoute en
     * dernière option « (introuvable) ». La première option « aucun »
     * (retrait de l'attribut) reste au liage : elle n'a pas de candidat.
     */
    public fun options(
        arbre: List<ArbreAttributsXml.Noeud>,
        indiceVue: Int,
        reference: CatalogueAttributs.ControleAttribut.ReferenceId,
        valeur: String?,
    ): Options {
        val vue = arbre.getOrNull(indiceVue)
        val parent = vue?.indiceParent?.takeIf { it >= 0 }?.let { arbre.getOrNull(it) }
        val entrees = entrees(candidats(arbre, vue, parent, reference), parent, vue, reference)
        return Options(
            entrees = entrees,
            note = noteDe(reference),
            introuvable = introuvableDe(valeur, entrees),
        )
    }

    /**
     * Les nœuds CANDIDATS selon la portée (spec § 5.3 — miroir
     * `refCands`) : TOUT l'arbre, les frères directs, ou un sous-arbre
     * (du PARENT pour l'ancre, de la VUE sinon).
     */
    private fun candidats(
        arbre: List<ArbreAttributsXml.Noeud>,
        vue: ArbreAttributsXml.Noeud?,
        parent: ArbreAttributsXml.Noeud?,
        reference: CatalogueAttributs.ControleAttribut.ReferenceId,
    ): List<ArbreAttributsXml.Noeud> =
        when (reference.portee) {
            CatalogueAttributs.ControleAttribut.ReferenceId.Portee.TOUT -> {
                arbre.filter { it !== vue && it.id != null }
            }

            CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES -> {
                parent?.enfants?.filter { it !== vue && it.id != null } ?: emptyList()
            }

            CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE -> {
                if (reference.nom == ANCRE_COORDINATEUR && parent != null) {
                    // Le sous-arbre du PARENT, hors le parent, la vue
                    // et ses descendants (miroir `refCands` — ancre).
                    sousArbre(parent, exclure = vue)
                } else if (vue != null) {
                    // Le sous-arbre de la VUE, hors la vue (spec § 5.3
                    // — écart maquette consigné en tête d'objet).
                    sousArbre(vue, exclure = null)
                } else {
                    emptyList()
                }
            }
        }

    /**
     * Les ENTRÉES : un candidat par identifiant NU, marqué [Entree.boucle]
     * quand le CHOISIR fermerait une boucle de règles (portée FRÈRES
     * seulement — [RelationsParents.fermeraitBoucle]).
     */
    private fun entrees(
        candidats: List<ArbreAttributsXml.Noeud>,
        parent: ArbreAttributsXml.Noeud?,
        vue: ArbreAttributsXml.Noeud?,
        reference: CatalogueAttributs.ControleAttribut.ReferenceId,
    ): List<Entree> =
        candidats.map { noeud ->
            Entree(
                id = noeud.id.orEmpty(),
                boucle =
                    reference.portee == CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES &&
                        parent != null &&
                        RelationsParents.fermeraitBoucle(parent, vue?.id ?: "", reference.nom, noeud.id.orEmpty()),
            )
        }

    /** La NOTE du champ (spec § 5.3) : FRÈRES pour les règles, ANCRE pour layout_anchor. */
    private fun noteDe(reference: CatalogueAttributs.ControleAttribut.ReferenceId): Note =
        when {
            reference.portee == CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES -> Note.FRERES

            reference.portee == CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE &&
                reference.nom == ANCRE_COORDINATEUR -> Note.ANCRE

            else -> Note.SANS
        }

    /** La valeur courante NORMALISÉE si AUCUN candidat ne la porte (l'option « (introuvable) » du liage). */
    private fun introuvableDe(
        valeur: String?,
        entrees: List<Entree>,
    ): String? {
        val courante = valeur?.replace("@+id/", "@id/").orEmpty()
        return courante.takeIf { it.isNotBlank() && entrees.none { entree -> "@id/${entree.id}" == it } }
    }

    /** Une option candidate : l'identifiant NU (« bio ») et son grisage. */
    public data class Entree(
        val id: String,
        val boucle: Boolean,
    )

    /** Les options calculées d'une référence d'id (voir [options]). */
    public data class Options(
        val entrees: List<Entree>,
        val note: Note,
        val introuvable: String?,
    )

    /** La note sous le champ (spec § 5.3 — « aucun » reste sans note). */
    public enum class Note {
        /** « Vues sœurs seulement. Une règle qui fermerait une boucle est grisée. » */
        FRERES,

        /** « Une vue du CoordinatorLayout qui n'est ni cette vue ni l'un de ses descendants. » */
        ANCRE,

        /** Pas de note (portée TOUT, ou sous-arbre de la vue). */
        SANS,
    }

    /** L'attribut ancre dont la base du sous-arbre est le PARENT (§ 5.3). */
    private const val ANCRE_COORDINATEUR: String = "app:layout_anchor"

    /**
     * Le sous-arbre de [base], hors [base] elle-même, hors la vue
     * [exclure] et ses descendants quand elle est donnée ([exclure]
     * null = la base EST la vue : ses descendants restent candidats).
     */
    private fun sousArbre(
        base: ArbreAttributsXml.Noeud,
        exclure: ArbreAttributsXml.Noeud?,
    ): List<ArbreAttributsXml.Noeud> {
        val sortie = mutableListOf<ArbreAttributsXml.Noeud>()

        fun parcourir(noeud: ArbreAttributsXml.Noeud) {
            for (enfant in noeud.enfants) {
                if (enfant !== exclure && enfant.id != null) sortie += enfant
                if (enfant !== exclure) parcourir(enfant)
            }
        }
        parcourir(base)
        return sortie
    }
}
