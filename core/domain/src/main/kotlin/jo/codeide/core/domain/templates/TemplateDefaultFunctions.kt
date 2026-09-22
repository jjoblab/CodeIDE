package jo.codeide.core.domain.templates

/**
 * Fonctions de valeurs dérivées `defaultFrom` (étape 8 — section 11).
 *
 * Une fonction **nommée** enregistrée ici produit la valeur suggérée d'un
 * paramètre — le moteur n'évalue **aucun code** fourni par le manifeste, il
 * appelle uniquement ces implémentations connues. La valeur dérivée suit ses
 * champs sources tant que l'utilisateur ne modifie pas le champ à la main
 * (section 12.2).
 *
 * Noms : `slug` (du nom du projet), `parentPackage` (du nom de package),
 * `packageFromNameAndAuthor` (du nom du projet et de l'auteur).
 */
internal object TemplateDefaultFunctions {
    /** Noms de fonctions enregistrées. */
    val NOMS: Set<String> = setOf("slug", "parentPackage", "packageFromNameAndAuthor")

    /** Sources nécessaires au calcul : nom du projet, auteur, package courant. */
    data class Sources(
        val nomProjet: String,
        val auteur: String,
        val nomPackage: String,
    )

    /**
     * Applique la fonction [nom] aux [sources].
     *
     * @return la valeur dérivée.
     * @throws TemplateRenderException si le nom est inconnu (contrôlé au
     * chargement du manifeste — resté ici comme garde finale explicite).
     */
    fun appliquer(
        nom: String,
        sources: Sources,
        ligne: Int = 0,
    ): String =
        when (nom) {
            "slug" -> TemplateFilters.slug(sources.nomProjet)
            "parentPackage" -> parentPackage(sources.nomPackage)
            "packageFromNameAndAuthor" -> packageDepuisNomEtAuteur(sources.nomProjet, sources.auteur)
            else -> throw TemplateRenderException(ligne, "fonction defaultFrom inconnue « $nom »")
        }

    /**
     * Package parent : retire le dernier segment ; un package à un seul
     * segment reste tel quel (valeur par défaut de `groupId`, étape 9).
     */
    private fun parentPackage(nomPackage: String): String {
        if (!nomPackage.contains('.')) return nomPackage
        return nomPackage.substringBeforeLast(".")
    }

    /**
     * Suggestion de package depuis le nom du projet et l'auteur (étape 9).
     *
     * Règle déterministe : deux segments — le premier dérivé de l'auteur
     * (`app` si absent), le second du nom (`projet` si le nom ne fournit
     * rien) ; chaque segment est nettoyé en minuscules sans accents, les
     * séparateurs sont concaténés ; un segment qui commencerait par un
     * chiffre est préfixé de `p` (un segment doit commencer par une lettre).
     */
    private fun packageDepuisNomEtAuteur(
        nomProjet: String,
        auteur: String,
    ): String {
        val segmentAuteur = segment(TemplateFilters.slug(auteur), repli = "app")
        val segmentNom = segment(TemplateFilters.slug(nomProjet), repli = "projet")
        return "$segmentAuteur.$segmentNom"
    }

    /** Nettoie un slug en segment de package valide. */
    @Suppress("ReturnCount") // Nettoyage d'un slug : trois issues (règle 16).
    private fun segment(
        slug: String,
        repli: String,
    ): String {
        val nettoyé = slug.replace("-", "")
        if (nettoyé.isEmpty()) return repli
        if (nettoyé[0].isDigit()) return "p$nettoyé"
        return nettoyé
    }
}
