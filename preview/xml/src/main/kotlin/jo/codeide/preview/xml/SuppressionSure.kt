package jo.codeide.preview.xml

/**
 * SUPPRESSION SÛRE SANS RÉFÉRENCE MORTE (PE12g — `docs/PREVIEW_PARENTS.md`
 * § 10) — FONCTION PURE sur le TEXTE du document, sans Android : l'élément
 * au span part avec ses descendants (comme [EditionAttributsXml.supprimer])
 * ET tout attribut du fichier dont la valeur est une référence `@id/x` ou
 * `@+id/x` vers un identifiant du sous-arbre supprimé — règles
 * `layout_below` et contraintes, ancre `app:layout_anchor`,
 * `android:labelFor`… — le tout en UNE SEULE édition atomique (plage
 * englobante, même principe que [InsertionXml.composerEdition] : un seul
 * geste d'annulation dans l'éditeur, contrat PE7d).
 *
 * Miroir `delSel` de la maquette : `gone` = les identifiants du sous-arbre
 * (l'élément ET sa descendance), puis chaque élément restant perd ses
 * attributs à valeur `@\+?id/x` avec `x` dans `gone` — le NOMBRE de
 * références retirées remonte au toast (« Button · @+id/x supprimé ·
 * 2 références retirées »). Les références portées PAR le sous-arbre
 * disparaissent avec lui : jamais comptées, jamais éditées à part.
 *
 * L'ancre du CoordinatorLayout (§ 10) : si l'élément qui portait
 * `app:layout_anchor` le perd (sa cible est supprimée),
 * `app:layout_anchorGravity` est retiré AVEC lui — une gravité sans ancre
 * ne veut rien dire. La gravité n'est PAS une référence : elle ne compte
 * pas dans le total.
 *
 * La duplication, elle, ne réécrit RIEN (§ 10 assumé) : le clone pointe
 * les mêmes cibles, c'est à l'utilisateur de les changer.
 *
 * Refus (philosophie X4 — `null`, jamais d'écriture hasardeuse) : span
 * périmé (l'élément ne se retrouve pas au span dans le texte vivant) ou
 * plages de retrait qui se chevaucheraient (défensif : elles vivent dans
 * des éléments distincts, le document serait réécrit depuis).
 */
public object SuppressionSure {
    /** Le nom LOCAL de l'ancre du CoordinatorLayout (§ 10 : sa gravité la suit dans le retrait). */
    private const val ANCRE: String = "layout_anchor"

    /** Le nom LOCAL de la gravité d'ancre (retirée quand l'ancre meurt). */
    private const val ANCRE_GRAVITE: String = "layout_anchorGravity"

    /**
     * COMPOSE la suppression sûre de l'élément au [span] : UNE édition
     * qui retire le bloc de l'élément (avec sa ligne quand il la possède,
     * [EditionAttributsXml.supprimer]) et CHAQUE référence morte du
     * fichier ([EditionAttributsXml.retirerAttributs] par élément, plages
     * fusionnées par l'union englobante). `null` = refus (cf. KDoc de
     * l'objet) ; le [Resultat.referencesRetirees] du succès nourrit le
     * toast du même nom (§ 10).
     */
    public fun composer(
        texte: String,
        span: InsertionXml.SpanParent,
    ): Resultat? {
        val arbre = ArbreAttributsXml.lire(texte)
        // Le nœud au span : les DEUX bornes (même contrat que la
        // localisation d'[EditionAttributsXml] — les spans de l'arbre et
        // de l'aligneur sont épinglés identiques, PE12b).
        val cible = arbre.firstOrNull { it.debut == span.debut && it.fin == span.fin }
        val partis = cible?.let { sousArbre(it) }.orEmpty()
        val bloc = cible?.let { EditionAttributsXml.supprimer(texte, span) }
        val editions = mutableListOf<EditionAttributsXml.EditionAttributsXmlResultat>()
        var references = 0
        if (bloc != null) {
            editions += bloc
            // Les débuts marquent le sous-arbre dans la liste aplatie (un
            // offset d'ouverture `<` n'appartient qu'à UN élément).
            val debutsPartis = partis.map { it.debut }.toSet()
            val identifiantsPartis = partis.mapNotNull { it.id }.toSet()
            for (noeud in arbre) {
                if (noeud.debut in debutsPartis) continue
                val retrait = retraitMort(texte, noeud, identifiantsPartis)
                if (retrait != null) {
                    editions += retrait.edition
                    references += retrait.references
                }
            }
        }
        return unir(texte, editions)?.let { Resultat(it, references) }
    }

    /** Le résultat de la composition : l'édition atomique + le décompte du toast. */
    public data class Resultat(
        /** L'UNIQUE édition (bloc + références mortes, plage englobante). */
        val edition: EditionAttributsXml.EditionAttributsXmlResultat,
        /** Le nombre de RÉFÉRENCES retirées hors du sous-arbre (la gravité d'ancre ne compte pas). */
        val referencesRetirees: Int,
    )

    /** Le retrait d'UN élément survivant : son édition + son décompte de mortes. */
    private class RetraitMort(
        val edition: EditionAttributsXml.EditionAttributsXmlResultat,
        val references: Int,
    )

    /**
     * Le retrait des références mortes d'UN élément survivant : `null`
     * quand il ne porte AUCUNE référence vers le sous-arbre supprimé
     * (l'immense majorité des éléments) ou que son span ne se localise
     * plus (défensif). L'ancre perdue emporte sa gravité ([nomsARetirer]).
     */
    private fun retraitMort(
        texte: String,
        noeud: ArbreAttributsXml.Noeud,
        identifiantsPartis: Set<String>,
    ): RetraitMort? =
        EditionAttributsXml
            .lire(texte, InsertionXml.SpanParent(noeud.debut, noeud.fin))
            ?.let { attributs ->
                val mortes = attributs.filter { estReferenceMorte(it.valeur, identifiantsPartis) }
                if (mortes.isEmpty()) {
                    null
                } else {
                    EditionAttributsXml
                        .retirerAttributs(
                            texte,
                            InsertionXml.SpanParent(noeud.debut, noeud.fin),
                            nomsARetirer(attributs, mortes),
                        )?.let { retrait -> RetraitMort(retrait, mortes.size) }
                }
            }

    /** Le sous-arbre de [racine] (elle-même comprise), en pré-ordre. */
    private fun sousArbre(racine: ArbreAttributsXml.Noeud): List<ArbreAttributsXml.Noeud> =
        listOf(racine) + racine.enfants.flatMap { sousArbre(it) }

    /**
     * La valeur est-elle une référence d'identifiant (`@id/x` ou `@+id/x`,
     * [ArbreAttributsXml.nomId] — même filtre que la maquette `delSel`)
     * vers un identifiant du sous-arbre supprimé ?
     */
    private fun estReferenceMorte(
        valeur: String,
        identifiantsPartis: Set<String>,
    ): Boolean {
        val nom = ArbreAttributsXml.nomId(valeur)
        return nom != null && nom in identifiantsPartis
    }

    /**
     * Les noms à retirer d'un élément : ses références mortes, PLUS la
     * gravité d'ancre quand l'ancre en fait partie (§ 10 — le nom LOCAL,
     * quel que soit le préfixe du document : `app:`, `appv:`… même
     * sémantique, la gravité suit).
     */
    private fun nomsARetirer(
        attributs: List<EditionAttributsXml.AttributSource>,
        mortes: List<EditionAttributsXml.AttributSource>,
    ): List<String> {
        val noms = mortes.map { it.nom }.toMutableList()
        if (mortes.any { nomLocal(it.nom) == ANCRE }) {
            attributs.firstOrNull { nomLocal(it.nom) == ANCRE_GRAVITE }?.let { noms += it.nom }
        }
        return noms
    }

    /** Le nom LOCAL d'un attribut qualifié (« app:layout_anchor » → « layout_anchor »). */
    private fun nomLocal(nom: String): String = nom.substringAfter(':')

    /**
     * UNIT les éditions en UNE SEULE (plage englobante, mêmes mécaniques
     * que [InsertionXml.composerEdition]) : le texte entre les plages
     * reste intact ; des plages QUI SE TOUCHENT sont admises (le retrait
     * d'une ligne propre mange le retour qui suit, la plage suivante
     * commence après), des plages CHEVAUCHANTES refusent toute écriture
     * (défensif, X4).
     */
    private fun unir(
        texte: String,
        editions: List<EditionAttributsXml.EditionAttributsXmlResultat>,
    ): EditionAttributsXml.EditionAttributsXmlResultat? {
        val triees = editions.sortedBy { it.debut }
        val chevauchent = triees.zipWithNext().any { (avant, apres) -> apres.debut < avant.fin }
        return if (triees.isEmpty() || chevauchent) null else composerPlages(texte, triees)
    }

    /** La plage englobante reconstruite : les éditions appliquées, l'entre-deux intact. */
    private fun composerPlages(
        texte: String,
        triees: List<EditionAttributsXml.EditionAttributsXmlResultat>,
    ): EditionAttributsXml.EditionAttributsXmlResultat {
        val debut = triees.first().debut
        val fin = triees.last().fin
        val compose = StringBuilder()
        var curseur = debut
        for (edition in triees) {
            compose.append(texte, curseur, edition.debut)
            compose.append(edition.remplacement)
            curseur = edition.fin
        }
        return EditionAttributsXml.EditionAttributsXmlResultat(debut, fin, compose.toString())
    }
}
