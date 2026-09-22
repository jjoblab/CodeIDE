package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « observer le registre des projets » (étape 4) — alimente
 * l'accueil (étape 7).
 *
 * L'ordre est celui du dépôt : épingles d'abord, dernier ouvert d'abord,
 * puis nom croissant.
 *
 * Contexte d'exécution attendu : flot froid délégué au dépôt ; sa
 * collecte se fait depuis le cycle de vie de l'UI (`repeatOnLifecycle`).
 */
public class ObserveProjectsUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /** @return le flot des projets enregistrés, ordonnés pour l'accueil. */
        public operator fun invoke(): Flow<List<Project>> = repository.observeProjects()
    }

/**
 * Cas d'usage « ajouter un projet au registre » (section 12.4 : appelé
 * par le wizard **après** la réussite des écritures sur disque).
 *
 * Le dépôt produit l'identifiant et l'horodatage ; le wizard reçoit le
 * [Project] complet et écrit `.codeide/project.json` avec son identifiant.
 *
 * Contexte d'exécution attendu : suspendante, appelée hors thread
 * principal ; l'annulation interrompt l'attente, pas l'écriture déjà
 * commitée en base.
 */
public class AddProjectUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * @param name libellé validé (section 12.3).
         * @param description description libre, éventuellement vide.
         * @param location emplacement SAF du dossier créé sur disque.
         * @param templateId modèle générateur.
         * @return le projet enregistré, ou l'échec typé (`AlreadyExists`
         * si le dossier est déjà référencé).
         */
        public suspend operator fun invoke(
            name: String,
            description: String,
            location: StorageLocation,
            templateId: TemplateId,
        ): AppResult<Project> = repository.addProject(name, description, location, templateId)
    }

/**
 * Cas d'usage « retirer un projet du registre » (étape 4).
 *
 * Ne touche **pas** au dossier sur disque : la suppression des fichiers
 * est une action explicite et séparée.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
public class RemoveProjectUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * @param id identifiant du projet à retirer.
         * @return le succès (idempotent), ou l'échec de stockage typé.
         */
        public suspend operator fun invoke(id: ProjectId): AppResult<Unit> = repository.removeProject(id)
    }

/**
 * Cas d'usage « épingler / désépingler un projet » (étape 4).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
public class SetProjectPinnedUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * @param id identifiant du projet.
         * @param pinned `true` pour épingler en tête d'accueil.
         * @return le succès, ou `NotFound`.
         */
        public suspend operator fun invoke(
            id: ProjectId,
            pinned: Boolean,
        ): AppResult<Unit> = repository.setPinned(id, pinned)
    }

/**
 * Cas d'usage « renommer un projet » (étape 4) — libellé en base
 * uniquement, jamais le dossier sur disque (ADR 0012).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
public class RenameProjectUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * @param id identifiant du projet.
         * @param newName nouveau libellé (trimé par le dépôt).
         * @return le succès, ou `Validation` / `NotFound`.
         */
        public suspend operator fun invoke(
            id: ProjectId,
            newName: String,
        ): AppResult<Unit> = repository.renameProject(id, newName)
    }

/**
 * Cas d'usage « marquer un projet comme ouvert » (étape 4).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal —
 * appelé à l'ouverture de l'espace de travail (étape 13) et aux tests
 * d'ouverture de dossier.
 */
public class MarkProjectOpenedUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * @param id identifiant du projet.
         * @param atMillis horodatage de l'ouverture (epoch millis).
         * @return le succès, ou `NotFound`.
         */
        public suspend operator fun invoke(
            id: ProjectId,
            atMillis: Long,
        ): AppResult<Unit> = repository.markOpened(id, atMillis)
    }
