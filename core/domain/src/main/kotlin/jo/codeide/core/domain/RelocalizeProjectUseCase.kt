package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Résultat de la relocalisation d'un projet (étape 7 : résolution des
 * états « Permission perdue » et « Introuvable »).
 */
public sealed interface RelocalisationProjet {
    /** Nouvel emplacement validé et enregistré. */
    public data class Deplace(
        val projet: Project,
    ) : RelocalisationProjet

    /** Dossier refusé par Android 11+ (racine, Download, Android/data…). */
    public data class Refuse(
        val raison: ForbiddenFolders.Reason,
    ) : RelocalisationProjet

    /** Échec typé : projet inconnu, permission, test d'écriture, registre. */
    public data class Erreur(
        val erreur: AppError,
    ) : RelocalisationProjet
}

/**
 * Cas d'usage « relocaliser un projet » (étape 7) : l'utilisateur
 * re-sélectionne le dossier d'un projet marqué `Permission perdue`
 * (permission révoquée) ou `Introuvable` (dossier déplacé ou supprimé
 * puis recréé ailleurs).
 *
 * Même validation que l'import ([ImportExistingFolderUseCase]) : refus
 * plateforme avant toute permission, test d'écriture témoin, héritage
 * de la permission du dossier de travail si le nouveau dossier vit dans
 * son arbre. Le libellé, la description et l'épingle du projet sont
 * conservés — seul l'emplacement change (ADR 0012 : le nom est un
 * libellé, jamais une adresse).
 *
 * L'ancienne permission est libérée **seulement si** le dossier de
 * travail ne la référence plus et si aucun projet restant ne vit dans
 * son arbre (règle « ne persister que le nécessaire », section 5.6) ;
 * la nouvelle permission propre est relâchée si l'écriture au registre
 * échoue.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : ReturnCount —
 * chaque clause de garde est une **issue** du parcours de
 * relocalisation.
 */
@Suppress("ReturnCount")
public class RelocalizeProjectUseCase
    @Inject
    constructor(
        private val projets: ProjectRepository,
        private val parametres: SettingsRepository,
        private val fichiers: FileSystem,
        private val arborescences: ArborescencesSaf,
        private val horloge: TimeProvider,
    ) {
        /**
         * Relocalise le projet [id] vers le dossier désigné par [grantUri].
         *
         * @param id identifiant du projet à relocaliser.
         * @param grantUri URI d'arborescence proposée par le sélecteur SAF.
         */
        public suspend operator fun invoke(
            id: ProjectId,
            grantUri: String,
        ): RelocalisationProjet {
            val ancien =
                projets.getProject(id).getOrNull()
                    ?: return RelocalisationProjet.Erreur(
                        AppError.Storage(AppError.StorageReason.NotFound, "projet ${id.value}"),
                    )

            val idChoisi =
                arborescences.idDocument(grantUri)
                    ?: return RelocalisationProjet.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI d'arborescence illisible"),
                    )

            ForbiddenFolders.reasonFor(idChoisi)?.let { return RelocalisationProjet.Refuse(it) }

            val uriChoisi =
                arborescences.uriDocument(grantUri)
                    ?: return RelocalisationProjet.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI de document illisible"),
                    )

            // Même politique d'héritage que l'import (ADR 0015).
            when (val emplacement = resoudreEmplacement(arborescences, parametres, uriChoisi, idChoisi)) {
                is EmplacementSaf.Herite -> {
                    return enregistrer(
                        ancien,
                        permissionPropre = false,
                        grantUri = emplacement.grantUri,
                        uriDocument = emplacement.uriDocument,
                        idDocument = idChoisi,
                    )
                }

                is EmplacementSaf.Illisible -> {
                    return RelocalisationProjet.Erreur(emplacement.erreur)
                }

                is EmplacementSaf.Propre -> {
                    Unit
                }
            }

            when (val permission = fichiers.takePersistablePermission(grantUri)) {
                is AppResult.Failure -> return RelocalisationProjet.Erreur(permission.error)
                is AppResult.Success -> Unit
            }

            val echec = testerEcriture(fichiers, uriChoisi, horloge, PREFIXE_TEMOIN)
            if (echec != null) {
                fichiers.releasePersistablePermission(grantUri)
                return RelocalisationProjet.Erreur(echec)
            }

            return enregistrer(
                ancien,
                permissionPropre = true,
                grantUri = grantUri,
                uriDocument = uriChoisi,
                idDocument = idChoisi,
            )
        }

        /**
         * Écrit le nouvel emplacement au registre puis équilibre les
         * permissions : la nouvelle est relâchée si l'écriture échoue,
         * l'ancienne n'est libérée que si plus personne ne l'utilise.
         */
        private suspend fun enregistrer(
            ancien: Project,
            permissionPropre: Boolean,
            grantUri: String,
            uriDocument: String,
            idDocument: String,
        ): RelocalisationProjet {
            val libelle = libelleLisible(fichiers, uriDocument, idDocument)
            val nouvelle =
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = uriDocument,
                    displayPath = libelle,
                )

            return when (val maj = projets.updateLocation(ancien.id, nouvelle)) {
                is AppResult.Failure -> {
                    // Échec de registre : la nouvelle permission n'est
                    // conservée que si un projet restant — ou le dossier de
                    // travail — référence encore cet arbre (le dossier
                    // choisi peut être celui d'un autre projet).
                    if (permissionPropre) {
                        libererPermissionSiInutilisee(projets, parametres, fichiers, grantUri)
                    }
                    RelocalisationProjet.Erreur(maj.error)
                }

                is AppResult.Success -> {
                    if (ancien.location.grantUri != nouvelle.grantUri) {
                        libererPermissionSiInutilisee(projets, parametres, fichiers, ancien.location.grantUri)
                    }
                    RelocalisationProjet.Deplace(ancien.copy(location = nouvelle))
                }
            }
        }

        private companion object {
            /** Préfixe du fichier témoin du test d'écriture. */
            const val PREFIXE_TEMOIN = "codeide-relocalisation"
        }
    }
