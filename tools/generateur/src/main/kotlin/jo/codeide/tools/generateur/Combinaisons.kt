package jo.codeide.tools.generateur

import kotlinx.serialization.Serializable

/**
 * Une combinaison à générer sur disque (étape 9, ADR 0019) : entrée du
 * fichier JSON produit par `scripts/verify-templates.sh`.
 *
 * @property id identifiant du répertoire de sortie (nom court, sans slash).
 * @property templateId modèle embarqué visé (`kotlin-jvm`, `java`).
 * @property nom nom du projet (entrée utilisateur simulée).
 * @property description description simulée.
 * @property parametres valeurs saisies des paramètres du modèle.
 * @property modifiesManuellement paramètres figés après saisie manuelle —
 * indispensables pour qu'une valeur fournie de `packageName`, `groupId` ou
 * `artifactId` ne soit pas recalculée par dérivation.
 * @property options options communes du moteur.
 */
@Serializable
public data class Combinaison(
    public val id: String,
    public val templateId: String,
    public val nom: String,
    public val description: String = "",
    public val parametres: Map<String, String> = emptyMap(),
    public val modifiesManuellement: Set<String> = emptySet(),
    public val options: OptionsCombinaison = OptionsCombinaison(),
)

/**
 * Options communes d'une combinaison (miroir de
 * [jo.codeide.core.model.TemplateOptions], en codes de licence lisibles).
 *
 * @property license code de licence (`none`, `mit`, `apache-2.0`, `gpl-3.0`,
 * `bsd-3-clause`).
 * @property contentLanguage langue du contenu généré (`fr` ou `en`).
 */
@Serializable
public data class OptionsCombinaison(
    public val includeReadme: Boolean = true,
    public val includeGitignore: Boolean = true,
    public val includeEditorconfig: Boolean = true,
    public val license: String = "none",
    public val contentLanguage: String = "en",
)
