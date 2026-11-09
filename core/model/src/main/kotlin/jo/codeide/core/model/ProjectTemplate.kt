package jo.codeide.core.model

/**
 * Modèle de projet déclaratif (étape 8 — section 11 du prompt maître, ADR 0005).
 *
 * Un modèle est décrit par un **manifeste** `assets/templates/<id>/template.json`
 * et interprété par le moteur de templates (`core:domain`) : aucune classe
 * Kotlin ne code un modèle en dur, aucun `when(templateId)` n'existe dans le
 * code — ajouter un modèle = ajouter des assets (étape 9 pour Kotlin et Java,
 * plugins plus tard via `ProjectTemplateProvider`).
 *
 * Les libellés ([nameKey], [descriptionKey], [labelKey] des paramètres) sont
 * des **clés i18n** résolues par le moteur dans les dictionnaires du modèle
 * (`i18n/en.json` requis, `i18n/fr.json` optionnel avec repli sur `en`).
 *
 * @property id identifiant stable du modèle (répertoire d'assets, persistance).
 * @property templateVersion version du modèle lui-même (SemVer) — un modèle
 * peut évoluer (fichiers, paramètres) sans changer l'application.
 * @property nameKey clé i18n du nom affiché.
 * @property descriptionKey clé i18n de la description courte affichée.
 * @property category catégorie d'affichage (ex. « jvm ») pour de futurs filtres.
 * @property iconKey clé de l'icône maison du modèle (pastilles, pas de logo
 * officiel — règle de l'étape 9).
 * @property tags étiquettes descriptives (« Kotlin · JVM », « Gradle · Maven »).
 * @property parameters paramètres du formulaire de création.
 * @property computedVariables variables calculées par expression, disponibles
 * dans les conditions et la substitution.
 * @property constraints règles de projet déclaratives évaluées par le moteur
 * sur la combinaison complète (ADR 0153 §3) : `ERREUR` bloque, les autres
 * informent.
 * @property autoDefinit dépendances implicites déclaratives (ADR 0153 §4) :
 * une règle vraie positionne des paramètres `BOOLEAN` jamais écrasés s'ils
 * ont été figés à la main.
 * @property fichiers fichiers du projet généré, dans l'ordre déclaré.
 */
public data class ProjectTemplate(
    public val id: TemplateId,
    public val templateVersion: String,
    public val nameKey: String,
    public val descriptionKey: String,
    public val category: String,
    public val iconKey: String,
    public val tags: List<String>,
    public val parameters: List<TemplateParameter>,
    public val computedVariables: List<TemplateComputedVariable>,
    public val constraints: List<TemplateRegleProjet> = emptyList(),
    public val autoDefinit: List<TemplateAutoDefinition> = emptyList(),
    public val fichiers: List<TemplateFile>,
)

/** Sévérité d'une règle de projet (ADR 0153 §3). */
public enum class SeveriteRegle {
    /** Bloquante : `isValid = false`, création refusée. */
    ERREUR,

    /** Avertissement affiché, non bloquant. */
    AVERTISSEMENT,

    /** Information affichée. */
    INFO,
}

/**
 * Règle de projet déclarative d'un manifeste (ADR 0153 §3).
 *
 * @property quand expression du mini-langage évaluée sur la combinaison
 * complète (paramètres, variables calculées, `plateformeMajeure`).
 * @property severite sévérité quand [quand] est vrai.
 * @property messageKey clé i18n du message (fr **et** en).
 */
public data class TemplateRegleProjet(
    public val quand: String,
    public val severite: SeveriteRegle,
    public val messageKey: String,
)

/**
 * Dépendance implicite déclarative d'un manifeste (ADR 0153 §4) : quand
 * [quand] est vrai, chaque entrée de [definit] (paramètre `BOOLEAN` déclaré
 * plus tôt → valeur) est positionnée — sauf paramètre figé à la main.
 *
 * @property quand expression du mini-langage évaluée après les dérivations.
 * @property definit paramètres `BOOLEAN` positionnés (une seule passe,
 * aucune propagation indirecte).
 * @property messageKey clé i18n du message « sera ajouté automatiquement »,
 * ou `null`.
 */
public data class TemplateAutoDefinition(
    public val quand: String,
    public val definit: Map<String, Boolean>,
    public val messageKey: String? = null,
)

/**
 * Paramètre d'un modèle de projet (étape 8 — section 11).
 *
 * Le wizard rend ces paramètres **dynamiquement** (section 12.2) : type de
 * composant, visibilité conditionnelle, valeur dérivée. Un paramètre
 * **masqué** ([visibleWhen] faux) est ignoré — il vaut sa valeur par défaut,
 * n'est ni validé ni persisté dans `.codeide/project.json`.
 *
 * @property id identifiant du paramètre (identifiant Java/Kotlin valide,
 * unique dans le modèle).
 * @property type type de saisie (texte, booléen, choix).
 * @property labelKey clé i18n du libellé affiché.
 * @property helpKey clé i18n de l'aide contextuelle, ou `null`.
 * @property choices valeurs possibles (type [TemplateParameterType.CHOICE]
 * uniquement, non vide dans ce cas).
 * @property defaultValue valeur par défaut littérale, ou `null`.
 * @property defaultFrom nom d'une fonction dérivée enregistrée dans le moteur
 * (`slug`, `parentPackage`, `packageFromNameAndAuthor`…) — **aucune évaluation
 * de code**, uniquement des fonctions nommées connues ; la valeur suit ses
 * champs sources tant que l'utilisateur ne l'a pas modifiée à la main.
 * @property validator nom du validateur (`project-name`, `package-name`,
 * `identifier`, `semver`, `regex:<motif>`) appliqué aux valeurs visibles.
 * @property visibleWhen expression du mini-langage ; `null` = toujours
 * visible.
 * @property enabledWhen expression du mini-langage ; `null` = toujours
 * actif. Une expression fausse place le paramètre dans l'état
 * [EtatParametre.Desactive] : rendu mais grisé, avec sa raison localisée —
 * côté génération il se comporte comme un paramètre masqué (ADR 0153 §2).
 * @property disabledReasonKey clé i18n de la raison de désactivation
 * (fr **et** en), lue quand [enabledWhen] est faux ; `null` = pas de raison
 * déclarée.
 * @property rendu indice de composant déclaré (ADR 0154 §2) ; `null` =
 * repli par traits du wizard (CHOICE → liste, BOOLEAN → interrupteur,
 * TEXT → champ).
 * @property groupe identifiant libre du groupe d'affichage (ADR 0154 §3) ;
 * `null` = hors groupe. Le titre vient de la clé `groupe.<groupe>.titre`.
 * @property avance le paramètre est rendu dans la zone « Avancé »
 * repliable de l'étape (ADR 0154 §3).
 * @property section section du wizard (configuration ou information).
 * @property persist la valeur est-elle écrite dans `.codeide/project.json` ?
 */
public data class TemplateParameter(
    public val id: String,
    public val type: TemplateParameterType,
    public val labelKey: String,
    public val helpKey: String? = null,
    public val choices: List<String> = emptyList(),
    public val defaultValue: String? = null,
    public val defaultFrom: String? = null,
    public val validator: String? = null,
    public val visibleWhen: String? = null,
    public val enabledWhen: String? = null,
    public val disabledReasonKey: String? = null,
    public val rendu: RenduParametre? = null,
    public val groupe: String? = null,
    public val avance: Boolean = false,
    public val section: TemplateSection = TemplateSection.CONFIGURATION,
    public val persist: Boolean = false,
)

/** Type de saisie d'un [TemplateParameter]. */
public enum class TemplateParameterType {
    /** Saisie texte libre. */
    TEXT,

    /** Interrupteur (`true` / `false` littéraux). */
    BOOLEAN,

    /** Choix parmi [TemplateParameter.choices]. */
    CHOICE,
}

/**
 * Indice de composant de rendu déclaré par le manifeste (ADR 0154 §2).
 *
 * L'UI ne devine plus le composant : le manifeste dit quoi rendre, la vue
 * exécute. Absent du manifeste → repli par traits du wizard.
 */
public enum class RenduParametre {
    /** Tuiles segmentées (choix compacts côte à côte). */
    TUILES_SEGMENTEES,

    /** Cartes radio (choix avec description courte). */
    CARTES_RADIO,

    /** Liste déroulante (repli historique des `CHOICE`). */
    LISTE_DEROULANTE,

    /** Interrupteur (booléens). */
    INTERRUPTEUR,

    /** Champ texte (saisies libres). */
    CHAMP_TEXTE,
}

/** Section du wizard dans laquelle un paramètre est rendu (section 12.3). */
public enum class TemplateSection {
    /** Étape « Configuration » (type de projet, build…). */
    CONFIGURATION,

    /** Étape « Informations et emplacement » (nom, package…). */
    INFORMATION,
}

/** Variable calculée d'un modèle, évaluée par expression à chaque rendu. */
public data class TemplateComputedVariable(public val name: String, public val expression: String)

/**
 * Entrée de fichier d'un manifeste de modèle (étape 8 — section 11).
 *
 * @property chemin chemin **templatisable** relatif à la racine du projet
 * généré (séparateur `/` ; les `{{…}}` sont substitués avant contrôle de
 * sécurité).
 * @property source chemin du fichier source **dans le répertoire du modèle**
 * (`.tpl` pour le texte, tel quel pour les binaires).
 * @property binary `true` = copie octet pour octet, `false` = substitution.
 * @property whenExpression expression conditionnant l'inclusion du fichier ;
 * `null` = toujours inclus.
 * @property group groupe fonctionnel du fichier (options communes du moteur).
 */
public data class TemplateFile(
    public val chemin: String,
    public val source: String,
    public val binary: Boolean = false,
    public val whenExpression: String? = null,
    public val group: TemplateFileGroup = TemplateFileGroup.CORE,
)

/** Groupe fonctionnel d'un fichier généré (options communes, section 11). */
public enum class TemplateFileGroup {
    /** Fichier du cœur du projet généré. */
    CORE,

    /** README (option `includeReadme`). */
    README,

    /** `.gitignore` (option `includeGitignore`). */
    GITIGNORE,

    /** `.editorconfig` (option `includeEditorconfig`). */
    EDITORCONFIG,

    /** Licence (option `license`). */
    LICENSE,
}
