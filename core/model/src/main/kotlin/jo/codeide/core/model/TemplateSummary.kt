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
 * (section 12.2) : visibilité, états actif/désactivé (ADR 0153), valeurs
 * effectives (dérivées ou saisies), validité de chaque paramètre visible,
 * libellés résolus, messages globaux des règles de projet.
 *
 * @property parameters évaluation par paramètre, dans l'ordre du manifeste.
 * @property computedValues variables calculées du modèle, stringifiées
 * (`true`/`false` pour les booléennes) — informatives pour le récapitulatif.
 * @property messages messages globaux des règles déclenchées (contraintes
 * et dépendances automatiques), dans l'ordre de déclaration — l'UI les
 * affiche au bon endroit, le récapitulatif compris (ADR 0153 §5).
 * @property isValid tous les paramètres visibles sont-ils valides **et**
 * aucune règle `ERREUR` déclenchée ?
 */
public data class TemplateFormEvaluation(
    public val parameters: List<TemplateParameterEvaluation>,
    public val computedValues: Map<String, String>,
    public val messages: List<MessageRegle> = emptyList(),
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
 * @property label libellé résolu pour la langue demandée.
 * @property help aide contextuelle résolue (vide si absente).
 * @property type type de saisie (métadonnée de rendu).
 * @property choices valeurs possibles (type `CHOICE` uniquement).
 * @property derived le paramètre est dérivable (`defaultFrom`) : son champ
 * suit ses sources tant que l'utilisateur ne l'a pas modifié à la main.
 * @property section section du wizard rendant ce paramètre.
 * @property etat état actif/désactivé du paramètre (ADR 0153 §2) :
 * [EtatParametre.Desactive] seulement pour un paramètre **visible** et
 * rendu grisé ; un paramètre masqué garde [EtatParametre.Visible] (état
 * sans objet, il n'est pas rendu).
 * @property choix libellés résolus par valeur (ADR 0154 §1), dans l'ordre
 * du manifeste ; vide hors type `CHOICE`. Le libellé retombe sur la valeur
 * brute si la clé manque (repli théorique — testé absent du catalogue).
 * @property rendu composant déclaré par le manifeste (ADR 0154 §2), ou
 * `null` pour le repli par traits du wizard.
 * @property groupe identifiant du groupe d'affichage (ADR 0154 §3), ou
 * `null` si le paramètre est hors groupe.
 * @property groupeTitre titre résolu du groupe (`groupe.<groupe>.titre`) ;
 * vide hors groupe, retombe sur l'identifiant si la clé manque.
 * @property avance le paramètre est rendu dans la zone « Avancé »
 * repliable (ADR 0154 §3).
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
    public val etat: EtatParametre = EtatParametre.Visible,
    public val choix: List<ChoixEvalue> = emptyList(),
    public val rendu: RenduParametre? = null,
    public val groupe: String? = null,
    public val groupeTitre: String = "",
    public val avance: Boolean = false,
)

/**
 * État d'un paramètre du formulaire (ADR 0153 §2).
 *
 * Désactivé ≠ masqué : le champ reste à l'écran (affordance découverte),
 * grisé, avec sa raison localisée annoncée à TalkBack ; `visibleWhen` garde
 * son rôle de masquage pur.
 */
public sealed interface EtatParametre {
    /** Le paramètre est actif : saisie normale. */
    public data object Visible : EtatParametre

    /**
     * Le paramètre est rendu grisé : sa saisie éventuelle est conservée
     * dans l'état du wizard mais exclue de la génération tant que l'état
     * dure (il retombe sur sa valeur par défaut, non validé, non persisté).
     *
     * @property raison texte résolu dans la langue demandée
     * (`disabledReasonKey`) ; retombe sur la clé brute si elle manque
     * (repli théorique).
     */
    public data class Desactive(public val raison: String) : EtatParametre
}

/**
 * Choix d'un paramètre `CHOICE` avec ses textes résolus (ADR 0154 §1).
 *
 * @property valeur valeur brute du manifeste (envoyée au moteur).
 * @property libelle libellé affiché (`param.<id>.choix.<valeur>.libelle`).
 * @property sousTitre sous-titre court, vide si absent.
 * @property description explication longue, vide si absente.
 * @property icone nom logique d'icône (`IconesModele`, core:ui), vide si absent.
 */
public data class ChoixEvalue(
    public val valeur: String,
    public val libelle: String,
    public val sousTitre: String,
    public val description: String,
    public val icone: String,
)

/**
 * Message global résolu d'une règle déclenchée (ADR 0153 §5) : contrainte
 * de projet ou dépendance automatique. L'UI n'évalue aucune règle — elle
 * affiche ces messages (récapitulatif compris).
 *
 * @property severite sévérité de la règle (`ERREUR` bloquante incluse).
 * @property texte message résolu dans la langue demandée ; retombe sur
 * [cle] si la clé manque (repli théorique).
 * @property cle clé i18n d'origine — traçabilité et tests.
 */
public data class MessageRegle(public val severite: SeveriteRegle, public val texte: String, public val cle: String)
