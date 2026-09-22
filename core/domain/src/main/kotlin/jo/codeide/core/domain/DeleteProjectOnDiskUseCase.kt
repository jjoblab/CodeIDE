package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Cas d'usage « supprimer un projet du disque » (étape 7).
 *
 * À la différence du retrait de la liste ([RemoveProjectUseCase], qui ne
 * touche qu'au registre), la suppression du disque efface **réellement**
 * le dossier du projet et tout son contenu — c'est une action
 * destructive, d'où :
 *
 * 1. l'ordre d'exécution : **disque d'abord, registre ensuite** — si la
 *    suppression du dossier échoue (permission révoquée, E/S), le
 *    projet reste référencé et l'utilisateur peut réessayer ou
 *    retirer l'entrée ensuite ; l'inverse laisserait une entrée de
 *    registre pointant un dossier à demi-supprimé ;
 * 2. la confirmation avec rappel du nom du projet, exigée par le plan
 *    (étape 7) — elle vit dans l'UI (`feature:home`), le cas d'usage
 *    exécute une demande déjà confirmée ;
 * 3. l'équilibre des permissions : après retrait du registre, la
 *    permission du projet n'est libérée que si le dossier de travail
 *    ne la référence plus et si aucun projet restant ne vit dans le
 *    même arbre (ADR 0016).
 *
 * La suppression du dossier du projet ne touche **jamais** le dossier de
 * travail lui-même : si l'utilisateur « supprime du disque » un projet
 * qui pointe le dossier de travail (cas dégénéré de l'import), le refus
 * est explicite (`NotWritable` n'existe pas ici — l'erreur remonte telle
 * quelle du port de stockage, typée et journalisée).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : ReturnCount —
 * chaque clause de garde est une **issue** du parcours (projet inconnu,
 * dossier non effaçable, succès délégué au retrait).
 */
@Suppress("ReturnCount")
public class DeleteProjectOnDiskUseCase
    @Inject
    constructor(
        private val projets: ProjectRepository,
        private val fichiers: FileSystem,
        private val retirerDeLaListe: RemoveProjectUseCase,
    ) {
        /**
         * Supprime le dossier du projet [id] sur le disque, puis son
         * entrée de registre.
         *
         * @param id identifiant du projet à supprimer.
         * @return le succès, ou l'échec typé (`NotFound` si le projet est
         * inconnu, erreur de stockage si le dossier n'a pu être effacé —
         * le registre est alors intact).
         */
        public suspend operator fun invoke(id: ProjectId): AppResult<Unit> {
            val projet =
                projets.getProject(id).getOrNull()
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"),
                    )

            // Disque d'abord : un échec laisse le registre intact.
            val suppression = fichiers.delete(projet.location.documentUri)
            if (suppression is AppResult.Failure) return suppression

            // Registre ensuite (avec équilibre des permissions).
            return retirerDeLaListe(id)
        }
    }
