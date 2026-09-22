package jo.codeide.core.data

import android.database.sqlite.SQLiteConstraintException
import jo.codeide.core.database.ProjectDao
import jo.codeide.core.database.ProjectEntity
import jo.codeide.core.database.toEntity
import jo.codeide.core.database.toModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ProjectRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre des projets sur Room (étape 4).
 *
 * L'implémentation est volontairement mince : la base porte les
 * invariants (index unique sur `documentUri`, tri de l'accueil dans la
 * requête) et ce dépôt traduit, horodate et journalise. L'identifiant est
 * produit **ici** (UUID) — l'appelant reçoit le [Project] complet, prêt à
 * être consigné dans `.codeide/project.json` (section 12.4).
 *
 * Journalisation (règle 15) : identifiants uniquement — jamais un nom de
 * projet, jamais un libellé d'emplacement.
 *
 * Contexte d'exécution attendu : méthodes suspendantes, sûres depuis
 * n'importe quel dispatcher (Room délègue à ses propres exécuteurs) ;
 * l'annulation se propage (`CancellationException` jamais capturée).
 */
@Singleton
internal class ProjectRepositoryImpl
    @Inject
    constructor(
        private val dao: ProjectDao,
        private val horloge: TimeProvider,
        private val logger: AppLogger,
    ) : ProjectRepository {
        override fun observeProjects(): Flow<List<Project>> = dao.observeAll().map(::versProjets)

        override fun observeProject(id: ProjectId): Flow<Project?> = dao.observeById(id.value).map { it?.toModel() }

        override suspend fun getProject(id: ProjectId): AppResult<Project> =
            dao.getById(id.value)?.let { AppResult.Success(it.toModel()) }
                ?: AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))

        override suspend fun addProject(
            name: String,
            description: String,
            location: StorageLocation,
            templateId: TemplateId,
        ): AppResult<Project> {
            val projet =
                Project(
                    id = ProjectId(UUID.randomUUID().toString()),
                    name = name.trim(),
                    description = description,
                    location = location,
                    templateId = templateId,
                    createdAtMillis = horloge.nowMillis(),
                    lastOpenedAtMillis = null,
                    isPinned = false,
                )
            return try {
                dao.insert(projet.toEntity())
                logger.d(TAG) { "Projet ${projet.id.value} ajouté (modèle ${projet.templateId.value})." }
                AppResult.Success(projet)
            } catch (erreur: SQLiteConstraintException) {
                // L'index unique sur document_uri (ou la clé primaire) défend
                // le registre : le même dossier ne peut pas être référencé
                // deux fois. Le message technique reste dans [erreur] — le
                // motif est certain, l'identifiant d'origine est l'URI du
                // dossier, redonnée à l'appelant.
                logger.w(TAG, erreur) { "Ajout refusé, dossier déjà référencé (projet ${projet.id.value})." }
                AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.AlreadyExists, location.documentUri),
                )
            }
        }

        override suspend fun removeProject(id: ProjectId): AppResult<Unit> {
            dao.deleteById(id.value)
            // Idempotent par contrat : retirer un projet absent réussit.
            logger.d(TAG) { "Projet ${id.value} retiré du registre." }
            return AppResult.Success(Unit)
        }

        override suspend fun renameProject(
            id: ProjectId,
            newName: String,
        ): AppResult<Unit> {
            val nomNettoye = newName.trim()
            if (nomNettoye.isEmpty() || nomNettoye.length > Project.MAX_NAME_LENGTH) {
                return AppResult.Failure(AppError.Validation("nom de projet refusé"))
            }
            val lignes = dao.rename(id.value, nomNettoye)
            return if (lignes > 0) {
                // Le libellé seul change : le dossier sur disque ne bouge pas (ADR 0012).
                logger.d(TAG) { "Projet ${id.value} renommé (libellé en base uniquement)." }
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))
            }
        }

        override suspend fun setPinned(
            id: ProjectId,
            pinned: Boolean,
        ): AppResult<Unit> {
            val lignes = dao.setPinned(id.value, pinned)
            return if (lignes > 0) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))
            }
        }

        override suspend fun updateLocation(
            id: ProjectId,
            location: StorageLocation,
        ): AppResult<Unit> =
            try {
                val lignes =
                    dao.updateLocation(
                        id = id.value,
                        grantUri = location.grantUri,
                        documentUri = location.documentUri,
                        displayPath = location.displayPath,
                    )
                if (lignes > 0) {
                    logger.d(TAG) { "Projet ${id.value} relocalisé." }
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))
                }
            } catch (erreur: SQLiteConstraintException) {
                // L'index unique sur document_uri : le nouveau dossier est
                // déjà référencé par un autre projet.
                logger.w(TAG, erreur) { "Relocalisation refusée, dossier déjà référencé (projet ${id.value})." }
                AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists, location.documentUri))
            }

        override suspend fun markOpened(
            id: ProjectId,
            atMillis: Long,
        ): AppResult<Unit> {
            val lignes = dao.markOpened(id.value, atMillis)
            return if (lignes > 0) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))
            }
        }

        /** Mappe les lignes de registre vers les projets du domaine. */
        private fun versProjets(lignes: List<ProjectEntity>): List<Project> = lignes.map { it.toModel() }

        private companion object {
            private const val TAG = "Projects"
        }
    }
