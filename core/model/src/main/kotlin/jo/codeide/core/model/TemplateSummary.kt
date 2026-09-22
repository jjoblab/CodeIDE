package jo.codeide.core.model

/**
 * Résumé affichable d'un modèle de projet (étape 10 du plan — grille de cartes).
 *
 * Le catalogue expose des libellés **résolus** pour la langue demandée : les
 * clés i18n du [ProjectTemplate] brut ne sont pas affichables telles quelles,
 * et l'UI ne doit pas connaître les dictionnaires du moteur.
 *
 * @property id identifiant du modèle.
 * @property nom nom affiché résolu (i18n).
 * @property description description courte résolue (i18n).
 * @property category catégorie d'affichage.
 * @property iconKey clé de l'icône maison.
 * @property tags étiquettes descriptives.
 */
public data class TemplateSummary(
    public val id: TemplateId,
    public val nom: String,
    public val description: String,
    public val category: String,
    public val iconKey: String,
    public val tags: List<String>,
)

/**
 * Évaluation d'un formulaire de modèle (étape 8 — `EvaluateTemplateFormUseCase`).
 *
 * Sortie unique du moteur pour piloter le rendu dynamique du wizard
 * (section 12.2) : visibilité, valeurs effectives (dérivées ou saisies),
 * validité de chaque paramètre visible, libellés résolus.
 *
 * @property parameters évaluation par paramètre, dans l'ordre du manifeste.
 * @property computedValues variables calculées du modèle, stringifiées
 * (`true`/`false` pour les booléennes) — informatives pour le récapitulatif.
 * @property isValid tous les paramètres visibles sont-ils valides ?
 */
public data class TemplateFormEvaluation(
    public val parameters: List<TemplateParameterEvaluation>,
    public val computedValues: Map<String, String>,
    public val isValid: Boolean,
)

/**
 * Évaluation d'un paramètre individuel.
 *
 * @property parameterId identifiant du paramètre.
 * @property visible le paramètre est-il visible (expression `visibleWhen`) ?
 * Un paramètre masqué vaut sa valeur par défaut, non validée, non persistée.
 * @property effectiveValue valeur effective : saisie si modifiée à la main,
 * sinon dérivée (`defaultFrom`) ou valeur par défaut.
 * @property error message d'erreur développeur (français) du validateur, ou
 * `null` si la valeur est valide ; toujours `null` pour un paramètre masqué.
 * @property label libellé résolu pour la langue demandue.
 * @property help aide contextuelle résolue (vide si absente).
 */
public data class TemplateParameterEvaluation(
    public val parameterId: String,
    public val visible: Boolean,
    public val effectiveValue: String,
    public val error: String?,
    public val label: String,
    public val help: String,
)
