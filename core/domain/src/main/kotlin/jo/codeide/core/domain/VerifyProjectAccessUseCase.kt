package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import javax.inject.Inject

/**
 * Cas d'usage « vérifier l'accès à un projet » (section 5.6 : état
 * `Introuvable` / `Permission perdue`, **jamais un crash** ; étape 4 du
 * plan).
 *
 * L'état est **calculé, jamais persisté** ([ProjectAccessState]) — il
 * reflète le stockage au moment de l'appel :
 * 1. la permission persistante d'écriture sur l'arborescence
 *    ([FileSystem.hasPersistablePermission]) — sinon `PermissionLost` ;
 * 2. l'existence du dossier ([FileSystem.stat]) — sinon `Missing` ;
 * 3. sinon `Available`.
 *
 * Un projet inconnu du registre est un échec `NotFound`, pas un état
 * d'accès : l'appelant ne saurait rien afficher pour lui.
 *
 * Contexte d'exécution attendu : suspendante, appelée hors thread
 * principal (l'accueil la déclenche à l'affichage, étape 7) ; les
 * requêtes SAF sont regroupées par l'implémentation et annulables.
 */
public class VerifyProjectAccessUseCase
    @Inject
    constructor(
        private val projects: ProjectRepository,
        private val files: FileSystem,
    ) {
        /**
         * Calcule l'état d'accès d'un projet.
         *
         * @param id identifiant du projet.
         * @return l'état calculé, ou l'échec typé (`NotFound` si le
         * projet est inconnu, `Storage` si le stockage lui-même est
         * injoignable au-delà de la permission).
         */
        public suspend operator fun invoke(id: ProjectId): AppResult<ProjectAccessState> =
            when (val lecture = projects.getProject(id)) {
                is AppResult.Success -> etatDAcces(lecture.value)
                is AppResult.Failure -> lecture
            }

        /**
         * Calcule l'état d'accès d'un projet **connu** du registre :
         * permission d'abord, existence ensuite.
         */
        private suspend fun etatDAcces(projet: Project): AppResult<ProjectAccessState> {
            // Permission d'abord : c'est elle qui se révoque silencieusement
            // (redémarrage, restore sans permissions).
            if (!files.hasPersistablePermission(projet.location.grantUri)) {
                return AppResult.Success(ProjectAccessState.PermissionLost)
            }

            return when (val dossier = files.stat(projet.location.documentUri)) {
                // Dossier joignable et décrit : accès complet.
                is AppResult.Success -> {
                    AppResult.Success(ProjectAccessState.Available)
                }

                // Dossier disparu alors que la permission tient toujours.
                is AppResult.Failure -> {
                    when ((dossier.error as? AppError.Storage)?.reason) {
                        AppError.StorageReason.NotFound -> {
                            AppResult.Success(ProjectAccessState.Missing)
                        }

                        // La permission a pu sauter entre les deux requêtes :
                        // l'état PermissionLost reste la réponse honnête.
                        AppError.StorageReason.PermissionLost -> {
                            AppResult.Success(ProjectAccessState.PermissionLost)
                        }

                        // Erreur d'E/S non classée : on ne préjuge pas d'une
                        // disparition — on remonte l'échec à l'appelant.
                        else -> {
                            dossier
                        }
                    }
                }
            }
        }
    }
