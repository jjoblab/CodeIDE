package jo.codeide.core.database

import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId

/*
 * Conversions [ProjectEntity] ↔ [Project] (étape 4).
 *
 * La frontière base/modèle est explicite et exhaustive : les identifiants
 * valués et l'emplacement SAF sont reconstruits ici, au plus près de la
 * source — aucun convertisseur Room ne fait ce travail à notre insu (les
 * colonnes restent des primitives SQLite, le schéma reste lisible et les
 * migrations triviales à écrire).
 */

/** Reconstruit le [Project] du domaine depuis sa ligne en base. */
public fun ProjectEntity.toModel(): Project =
    Project(
        id = ProjectId(id),
        name = name,
        description = description,
        location =
            StorageLocation(
                grantUri = grantUri,
                documentUri = documentUri,
                displayPath = displayPath,
            ),
        templateId = TemplateId(templateId),
        createdAtMillis = createdAt,
        lastOpenedAtMillis = lastOpenedAt,
        isPinned = isPinned,
    )

/**
 * Projette un [Project] du domaine vers sa ligne en base.
 *
 * Le projet est censé être déjà validé (identifiant produit par le dépôt,
 * nom validé par le wizard) : toute incohérence lève ici, avant
 * l'insertion — la base ne voit jamais de ligne invalide.
 */
public fun Project.toEntity(): ProjectEntity =
    ProjectEntity(
        id = id.value,
        name = name,
        description = description,
        grantUri = location.grantUri,
        documentUri = location.documentUri,
        displayPath = location.displayPath,
        templateId = templateId.value,
        createdAt = createdAtMillis,
        lastOpenedAt = lastOpenedAtMillis,
        isPinned = isPinned,
    )
