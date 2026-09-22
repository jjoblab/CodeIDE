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
 * @property iconKey monogramme de l'icône maison, résolu depuis le
 * dictionnaire du modèle (étape 10 : ex. « kt », « jv ») — pastille texte,
 * jamais un logo officiel.
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
 * Depuis l'étape 10, porte aussi les **métadonnées de rendu** du paramètre
 * ([type], [choices], [derived], [section]) : le wizard rend les champs
 * dynamiquement depuis cette seule évaluation (section 12.2 — « généré
 * depuis les TemplateParameter »), sans accès aux manifestes bruts.
 *
 * @property parameterId identifiant du paramètre.
 * @property visible le paramètre est-il visible (expression `visibleWhen`) ?
 * Un paramètre masqué vaut sa valeur par défaut, non validée, non persistée.
 * @property effectiveValue valeur effective : saisie si modifiée à la main,
 * sinon dérivée (`defaultFrom`) ou valeur par défaut.
 * @property error message d'erreur développeur (français) du validateur, ou
 * `null` si la valeur est valide ; toujours `null` pour un paramètre masqué.
 * @property errorReason raison typée correspondant à [error] (étape 10) :
 * l'interface l'affiche via ses ressources localisées au lieu du message
 * développeur ; `null` quand la valeur est valide.
 * @property label libellé résolu pour la langue demandue.
 * @property help aide contextuelle résolue (vide si absente).
 * @property type type de saisie (métadonnée de rendu).
 * @property choices valeurs possibles (type `CHOICE` uniquement).
 * @property derived le paramètre est dérivable (`defaultFrom`) : son champ
 * suit ses sources tant que l'utilisateur ne l'a pas modifié à la main.
 * @property section section du wizard rendant ce paramètre.
 */
public data class TemplateParameterEvaluation(
    public val parameterId: String,
    public val visible: Boolean,
    public val effectiveValue: String,
    public val error: String?,
    public val label: String,
    public val help: String,
    public val errorReason: RaisonValidation? = null,
    public val type: TemplateParameterType = TemplateParameterType.TEXT,
    public val choices: List<String> = emptyList(),
    public val derived: Boolean = false,
    public val section: TemplateSection = TemplateSection.CONFIGURATION,
)
