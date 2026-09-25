package jo.codeide.core.testing

import jo.codeide.core.domain.ProjectRepository
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.io.IOException
import java.util.UUID

/**
 * [ProjectRepository](jo.codeide.core.domain.ProjectRepository) en mémoire :
 * les projets vivent dans un [MutableStateFlow], sans base de données.
 *
 * Le contrat de tri de l'accueil est respecté (épingles d'abord, dernier
 * ouvert d'abord, nom croissant insensible à la casse) pour que les tests
 * des écrans futures voient le même ordre qu'en production.
 *
 * L'unicité de `documentUri` est assurée comme en base (index unique) :
 * ajouter deux projets sur le même dossier retourne `AlreadyExists`.
 *
 * Les propriétés `*Error` sont des robinets de défaillance pilotés par
 * le test (même idiom que [InMemoryLogRepository]) : les écritures par
 * [writeError], l'observation du registre par [flowError] — utile pour
 * éprouver l'état d'erreur de l'accueil (étape 7).
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * TooManyFunctions — le fake implémente les 11 opérations du contrat
 * [ProjectRepository], une par une, sans logique supplémentaire.
 */
@Suppress("TooManyFunctions", "ReturnCount") // Contrat complet du dépôt, clauses de garde par opération.
public class FakeProjectRepository : ProjectRepository {
    private val etat = MutableStateFlow<List<Project>>(emptyList())

    /** Robinet d'échec d'observation, réactif à l'écriture. */
    private val erreur = MutableStateFlow<RuntimeException?>(null)

    /** Compteur des identifiants produits : lisible dans les assertions. */
    private var compteur = 0

    /** Quand non nulle, toute écriture échoue. */
    public var writeError: IOException? = null

    /**
     * Quand non nulle, l'observation du registre ([observeProjects],
     * [observeProject]) lève à la collecte — simule la perte de la
     * base pour l'état d'erreur de l'accueil (étape 7). Réactif :
     * l'effacer relance une émission saine, sans attendre un
     * changement du registre.
     */
    public var flowError: RuntimeException?
        get() = erreur.value
        set(valeur) {
            erreur.value = valeur
        }

    /** Projets enregistrés, triés pour l'accueil. */
    public val projets: List<Project>
        get() = etat.value.triePourAccueil()

    /**
     * Sème un projet préexistant (v0.31.5) : éprouver le refus
     * d'`addProject` sur un dossier déjà référencé (index unique —
     * `AlreadyExists`) sans passer par une première création.
     */
    public fun seedProject(location: StorageLocation) {
        etat.value +=
            Project(
                id = ProjectId("fake-seed-${++compteur}-${UUID.randomUUID()}"),
                name = "Semé",
                description = "",
                location = location,
                templateId = TemplateId("fixture"),
                createdAtMillis = 0L,
                lastOpenedAtMillis = null,
                isPinned = false,
            )
    }

    public override fun observeProjects(): Flow<List<Project>> =
        combine(etat, erreur) { liste, echec ->
            echec?.let { throw it }
            liste.triePourAccueil()
        }

    public override fun observeProject(id: ProjectId): Flow<Project?> =
        combine(etat, erreur) { liste, echec ->
            echec?.let { throw it }
            liste.firstOrNull { it.id == id }
        }

    public override suspend fun getProject(id: ProjectId): AppResult<Project> =
        etat.value
            .firstOrNull { it.id == id }
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))

    public override suspend fun addProject(
        name: String,
        description: String,
        location: StorageLocation,
        templateId: TemplateId,
    ): AppResult<Project> {
        writeError?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        if (etat.value.any { it.location.documentUri == location.documentUri }) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists, location.documentUri))
        }

        val projet =
            Project(
                id = ProjectId("fake-${++compteur}-${UUID.randomUUID()}"),
                name = name,
                description = description,
                location = location,
                templateId = templateId,
                createdAtMillis = horloge(),
                lastOpenedAtMillis = null,
                isPinned = false,
            )
        etat.value += projet
        return AppResult.Success(projet)
    }

    public override suspend fun removeProject(id: ProjectId): AppResult<Unit> {
        writeError?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        etat.value = etat.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    public override suspend fun renameProject(
        id: ProjectId,
        newName: String,
    ): AppResult<Unit> {
        writeError?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val nomNettoye = newName.trim()
        if (nomNettoye.isEmpty() || nomNettoye.length > Project.MAX_NAME_LENGTH) {
            return AppResult.Failure(AppError.Validation("nom de projet refusé"))
        }
        return muter(id) { it.copy(name = nomNettoye) }
    }

    public override suspend fun setPinned(
        id: ProjectId,
        pinned: Boolean,
    ): AppResult<Unit> = muter(id) { it.copy(isPinned = pinned) }

    public override suspend fun updateLocation(
        id: ProjectId,
        location: StorageLocation,
    ): AppResult<Unit> {
        writeError?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        // L'unicité du dossier référencé s'applique aussi en relocalisation.
        if (etat.value.any { it.id != id && it.location.documentUri == location.documentUri }) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists, location.documentUri))
        }
        return muter(id) { it.copy(location = location) }
    }

    public override suspend fun markOpened(
        id: ProjectId,
        atMillis: Long,
    ): AppResult<Unit> = muter(id) { it.copy(lastOpenedAtMillis = atMillis) }

    /** Horloge des créations ; écrasable pour les tests temporels. */
    public var horloge: () -> Long = { System.currentTimeMillis() }

    /**
     * Applique [transformation] au projet identifié dans l'état publié.
     *
     * @return le succès, ou `NotFound` si le projet est absent.
     */
    private fun muter(
        id: ProjectId,
        transformation: (Project) -> Project,
    ): AppResult<Unit> {
        val index = etat.value.indexOfFirst { it.id == id }
        if (index < 0) return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"))

        etat.value = etat.value.toMutableList().apply { set(index, transformation(get(index))) }
        return AppResult.Success(Unit)
    }

    /**
     * Ordre de l'accueil : épingles d'abord, dernier ouvert d'abord
     * (jamais ouverts en fin), nom croissant insensible à la casse.
     */
    private fun List<Project>.triePourAccueil(): List<Project> =
        sortedWith(
            compareByDescending<Project> { it.isPinned }
                .thenByDescending { it.lastOpenedAtMillis ?: -1L }
                .thenBy { it.name.lowercase() },
        )
}
