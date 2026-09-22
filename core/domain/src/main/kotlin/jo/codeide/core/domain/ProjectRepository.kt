package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.flow.Flow

/**
 * Registre des projets connus de CodeIDE (étape 4 — couche données).
 *
 * Un projet est ajouté **après** la réussite de la création sur disque
 * (section 12.4 : ne persister qu'après succès des écritures, rollback
 * sinon) ; ce dépôt ne fait donc que tenir le registre — il ne crée
 * jamais de dossier lui-même.
 *
 * L'identifiant et l'horodatage de création sont produits par
 * l'implémentation ([addProject]) : l'appelant reçoit le [Project]
 * complet, avec son identifiant, prêt à être consigné dans
 * `.codeide/project.json`.
 *
 * Convention de journalisation (règle 15) : l'implémentation journalise
 * les opérations avec des **identifiants** uniquement — jamais un nom de
 * projet, jamais un chemin.
 */
public interface ProjectRepository {
    /**
     * Observe le registre complet, ordonné pour l'accueil : épingles
     * d'abord, puis dernier ouvert d'abord, puis nom croissant
     * (insensible à la casse).
     *
     * Contexte d'exécution attendu : flot froid conservé tant que
     * collecté ; chaque changement de base relance l'émission.
     *
     * @return le flot des projets enregistrés.
     */
    public fun observeProjects(): Flow<List<Project>>

    /**
     * Observe un projet précis.
     *
     * @param id identifiant du projet.
     * @return le flot du projet, ou `null` dès qu'il n'existe (plus).
     */
    public fun observeProject(id: ProjectId): Flow<Project?>

    /**
     * Lit un projet de manière ponctuelle.
     *
     * @param id identifiant du projet.
     * @return le projet, ou `NotFound` s'il est inconnu du registre.
     */
    public suspend fun getProject(id: ProjectId): AppResult<Project>

    /**
     * Ajoute un projet au registre ; l'identifiant et la date de création
     * sont produits ici.
     *
     * @param name libellé (déjà validé par l'appelant — le wizard, section 12.3).
     * @param description description libre, éventuellement vide.
     * @param location emplacement SAF du dossier du projet.
     * @param templateId modèle générateur.
     * @return le projet enregistré (avec identifiant), ou
     * `AlreadyExists` si le dossier est déjà référencé (index unique), ou
     * un échec de stockage typé.
     */
    public suspend fun addProject(
        name: String,
        description: String,
        location: StorageLocation,
        templateId: TemplateId,
    ): AppResult<Project>

    /**
     * Retire un projet du registre — **sans toucher au dossier sur
     * disque** (la suppression des fichiers est une action explicite de
     * l'utilisateur, pas un effet de bord du retrait du registre).
     *
     * @param id identifiant du projet.
     * @return le succès (idempotent : retirer un projet absent réussit),
     * ou l'échec de stockage typé.
     */
    public suspend fun removeProject(id: ProjectId): AppResult<Unit>

    /**
     * Renomme le libellé d'un projet — en base uniquement, jamais le
     * dossier sur disque (ADR 0012).
     *
     * @param id identifiant du projet.
     * @param newName nouveau libellé (non vide après trim).
     * @return le succès, `Validation` si le nom est refusé, `NotFound` si
     * le projet est inconnu.
     */
    public suspend fun renameProject(
        id: ProjectId,
        newName: String,
    ): AppResult<Unit>

    /**
     * Épingle ou désépingle un projet (affichage en tête de l'accueil).
     *
     * @param id identifiant du projet.
     * @param pinned nouvel état de l'épingle.
     * @return le succès, ou `NotFound` si le projet est inconnu.
     */
    public suspend fun setPinned(
        id: ProjectId,
        pinned: Boolean,
    ): AppResult<Unit>

    /**
     * Marque un projet comme ouvert à l'instant donné.
     *
     * @param id identifiant du projet.
     * @param atMillis horodatage de l'ouverture (epoch millis) — fourni
     * par l'appelant pour rester testable (horloge injectée).
     * @return le succès, ou `NotFound` si le projet est inconnu.
     */
    public suspend fun markOpened(
        id: ProjectId,
        atMillis: Long,
    ): AppResult<Unit>
}
