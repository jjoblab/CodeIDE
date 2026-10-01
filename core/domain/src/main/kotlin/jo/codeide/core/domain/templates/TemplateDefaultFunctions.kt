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
 * `packageFromNameAndAuthor` (du nom du projet et de l'auteur),
 * `packageFromAppName` (de la valeur du paramètre `appName`, convention
 * Android `com.example.<app>`), `packageFromArtifactId` (de la valeur du
 * paramètre `artifactId`, convention `com.example.<artefact>` — phase 2 du
 * roadmap, ADR 0075), `applicationIdFromPackageName` (de la valeur du
 * paramètre `packageName` — l'identifiant Android suit le package, phase 4
 * du roadmap, ADR 0077).
 */
internal object TemplateDefaultFunctions {
    /** Noms de fonctions enregistrées. */
    val NOMS: Set<String> =
        setOf(
            "slug",
            "parentPackage",
            "packageFromNameAndAuthor",
            "packageFromAppName",
            "packageFromArtifactId",
            "applicationIdFromPackageName",
        )

    /** Identifiant conventionnel du paramètre « nom de l'application » (Android). */
    private const val PARAMETRE_APP_NAME = "appName"

    /** Identifiant conventionnel du paramètre « artifactId ». */
    private const val PARAMETRE_ARTIFACT_ID = "artifactId"

    /** Identifiant conventionnel du paramètre « nom de package ». */
    private const val PARAMETRE_PACKAGE_NAME = "packageName"

    /** Préfixe de package de convention pour les projets générés (ADR 0075). */
    private const val PREFIXE_EXAMPLE = "com.example"

    /** Repli d'un segment vide dérivé du nom d'app ou de l'artefact. */
    private const val REPLI_SEGMENT = "app"

    /**
     * Sources nécessaires au calcul : nom du projet, auteur, package courant
     * et valeurs effectives des paramètres **déjà évalués** au moment de la
     * dérivation (une dérivée doit être déclarée après sa source,
     * docs/TEMPLATES.md).
     */
    data class Sources(
        val nomProjet: String,
        val auteur: String,
        val nomPackage: String,
        val valeursParametres: Map<String, String> = emptyMap(),
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
            "slug" -> {
                TemplateFilters.slug(sources.nomProjet)
            }

            "parentPackage" -> {
                parentPackage(sources.nomPackage)
            }

            "packageFromNameAndAuthor" -> {
                packageDepuisNomEtAuteur(sources.nomProjet, sources.auteur)
            }

            "packageFromAppName" -> {
                packageDepuisValeur(sources.valeursParametres[PARAMETRE_APP_NAME])
            }

            "packageFromArtifactId" -> {
                packageDepuisValeur(sources.valeursParametres[PARAMETRE_ARTIFACT_ID])
            }

            "applicationIdFromPackageName" -> {
                applicationIdDepuisPackage(sources.valeursParametres[PARAMETRE_PACKAGE_NAME])
            }

            else -> {
                throw TemplateRenderException(ligne, "fonction defaultFrom inconnue « $nom »")
            }
        }

    /**
     * Identifiant d'application Android (phase 4, ADR 0077) : **suit le nom
     * de package** saisi juste au-dessus — chaîne `appName → packageName →
     * applicationId` — et retombe sur la convention `com.example.app` tant
     * que la source est muette. La validation reste portée par le
     * paramètre (`package-name`), jamais par la dérivation.
     */
    private fun applicationIdDepuisPackage(packageName: String?): String =
        packageName?.takeIf { it.isNotBlank() } ?: packageDepuisValeur(null)

    /**
     * Package parent : retire le dernier segment ; un package à un seul
     * segment reste tel quel (valeur par défaut de `groupId`, étape 9).
     */
    private fun parentPackage(nomPackage: String): String {
        if (!nomPackage.contains('.')) return nomPackage
        return nomPackage.substringBeforeLast(".")
    }

    /**
     * Package de convention depuis le nom d'app ou l'artefact (ADR 0075) :
     * `com.example.` + segment nettoyé ; une valeur absente ou muette retombe
     * sur `com.example.app` (package toujours valide).
     */
    private fun packageDepuisValeur(valeur: String?): String {
        val segment = segment(TemplateFilters.slug(valeur ?: ""), repli = REPLI_SEGMENT)
        return "$PREFIXE_EXAMPLE.$segment"
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
