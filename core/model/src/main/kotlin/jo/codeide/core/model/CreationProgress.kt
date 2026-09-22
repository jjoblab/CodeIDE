package jo.codeide.core.model

/**
 * Progression de la création d'un projet (section 12.3 — écran de création).
 *
 * `CreateProjectUseCase` émet ces événements en temps réel via un flot froid ;
 * l'événement terminal [Termine] porte le [AppResult] typé de la section 12.4
 * (succès avec le projet enregistré, ou échec avec l'état du rollback).
 *
 * Les événements intermédiaires ne contiennent que des données d'affichage
 * (chemin relatif, compteurs) : la journalisation, elle, reste aux
 * identifiants (règle 15).
 */
public sealed interface CreationProgress {
    /** Préparation : revalidation et planification (dry-run). */
    public data object Preparation : CreationProgress

    /** Création du dossier racine du projet. */
    public data class CreationDossierRacine(
        public val nomProjet: String,
    ) : CreationProgress

    /** Génération d'un fichier du plan. */
    public data class GenerationFichier(
        public val index: Int,
        public val total: Int,
        public val cheminRelatif: String,
    ) : CreationProgress

    /** Enregistrement du projet dans le registre (base). */
    public data object Enregistrement : CreationProgress

    /**
     * Événement terminal : succès ou échec typé.
     *
     * @property result le résultat typé (section 12.4, point 7).
     * @property rolledBack un rollback a-t-il été effectué (échec ou
     * annulation après le début des écritures) ?
     * @property residues chemins relatifs impossibles à supprimer lors du
     * rollback — vides si tout a été nettoyé.
     */
    public data class Termine(
        public val result: AppResult<Project>,
        public val rolledBack: Boolean,
        public val residues: List<String>,
    ) : CreationProgress
}

/**
 * Demande de création d'un projet adressée au domaine (sections 12.3 et 12.4).
 *
 * La revalidation est systématique côté domaine : l'UI peut être obsolète ou
 * contournée, on ne fait jamais confiance aux entrées déjà validées.
 *
 * @property templateId modèle générateur.
 * @property name nom du projet (validé par `project-name`).
 * @property description description libre (peut être vide).
 * @property parentLocation emplacement SAF **parent** (dossier de travail ou
 * choix ponctuel) dans lequel le dossier racine `<name>` sera créé.
 * @property parameterValues valeurs saisies des paramètres du modèle.
 * @property manuallySetParameters identifiants des paramètres modifiés à la
 * main par l'utilisateur (les autres suivent leurs sources, section 12.2).
 * @property options options communes du moteur (fichiers, licence, langue).
 */
public data class CreateProjectRequest(
    public val templateId: TemplateId,
    public val name: String,
    public val description: String,
    public val parentLocation: StorageLocation,
    public val parameterValues: Map<String, String> = emptyMap(),
    public val manuallySetParameters: Set<String> = emptySet(),
    public val options: TemplateOptions = TemplateOptions(),
)
