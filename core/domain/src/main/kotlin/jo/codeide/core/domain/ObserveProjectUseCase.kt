package jo.codeide.core.domain

import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « observer un projet » (étape 13) : alimente l'espace de
 * travail de l'éditeur — le titre, le chemin et les actions du tiroir
 * suivent le registre (renommage, relocalisation, suppression) sans
 * rechargement manuel.
 *
 * Contexte d'exécution attendu : flot froid délégué au dépôt, collecté
 * depuis le cycle de vie de l'UI (`repeatOnLifecycle`). Première émission
 * dès que le projet est lu ; `null` si l'identifiant ne correspond à aucun
 * projet enregistré.
 */
public class ObserveProjectUseCase
    @Inject
    constructor(
        private val repository: ProjectRepository,
    ) {
        /**
         * Observe le projet identifié.
         *
         * @param id identifiant du projet.
         * @return le flot du projet, ou d'une valeur `null` introuvable.
         */
        public operator fun invoke(id: ProjectId): Flow<Project?> = repository.observeProject(id)
    }
