package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Résultat de l'import d'un dossier existant comme projet (étape 7).
 *
 * Machine explicite : chaque issue garde sa raison pour que l'accueil
 * affiche un message clair et actionnable.
 */
public sealed interface ImportDossier {
    /** Dossier validé et ajouté au registre. */
    public data class Ajoute(
        val projet: Project,
    ) : ImportDossier

    /** Le dossier est déjà référencé par un projet (index unique). */
    public data object DejaPresent : ImportDossier

    /** Dossier refusé par Android 11+ (racine, Download, Android/data…). */
    public data class Refuse(
        val raison: ForbiddenFolders.Reason,
    ) : ImportDossier

    /** Échec typé de permission, de test d'écriture ou de registre. */
    public data class Erreur(
        val erreur: AppError,
    ) : ImportDossier
}

/**
 * Cas d'usage « ouvrir un dossier existant » (étape 7) : ajoute au
 * registre un dossier choisi par le sélecteur SAF, **sans le créer**.
 *
 * Parcours de validation (ADR 0015) :
 * 1. décomposition de l'URI d'arborescence (illisible = échec typé) ;
 * 2. refus plateforme (racine, Download, `Android/data`…) **avant** toute
 *    prise de permission ;
 * 3. test d'écriture témoin : le dossier doit être réellement utilisable ;
 * 4. ajout au registre avec le nom du dossier comme libellé, une
 *    description vide et le modèle sentinelle [TemplateId.IMPORTED] —
 *    l'étape 13 lira `.codeide/project.json` pour reconnaître le vrai
 *    modèle s'il existe.
 *
 * Permissions (« ne persister que le nécessaire », section 5.6) :
 * - le dossier vit **dans l'arbre du dossier de travail** : aucune
 *   permission nouvelle — le projet hérite de celle du dossier de
 *   travail, comme un projet créé par le wizard ;
 * - il vit ailleurs : permission persistante propre, prise ici, et
 *   **relâchée si l'ajout au registre échoue** (y compris « déjà
 *   présent » : l'entrée existante suffit).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : ReturnCount —
 * chaque clause de garde est une **issue** du parcours d'import (URI
 * illisible, dossier refusé, permission refusée, test d'écriture
 * échoué, échec de registre, succès) ; les imbriquer ferait perdre
 * l'évidence d'un code de sécurité.
 */
@Suppress("ReturnCount")
public class ImportExistingFolderUseCase
    @Inject
    constructor(
        private val projets: ProjectRepository,
        private val parametres: SettingsRepository,
        private val fichiers: FileSystem,
        private val arborescences: ArborescencesSaf,
        private val horloge: TimeProvider,
    ) {
        /** Importe le dossier désigné par [grantUri] sans rien créer sur disque. */
        public suspend operator fun invoke(grantUri: String): ImportDossier {
            val idChoisi =
                arborescences.idDocument(grantUri)
                    ?: return ImportDossier.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI d'arborescence illisible"),
                    )

            // Refus plateforme d'abord : jamais de permission prise sur un
            // dossier interdit (message clair, section 5.6).
            ForbiddenFolders.reasonFor(idChoisi)?.let { return ImportDossier.Refuse(it) }

            val uriChoisi =
                arborescences.uriDocument(grantUri)
                    ?: return ImportDossier.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI de document illisible"),
                    )

            // Politique d'héritage (ADR 0015) : un choix dans l'arbre du
            // dossier de travail n'a pas de permission propre.
            when (val emplacement = resoudreEmplacement(arborescences, parametres, uriChoisi, idChoisi)) {
                is EmplacementSaf.Herite -> {
                    return enregistrer(
                        permissionPropre = false,
                        grantUri = emplacement.grantUri,
                        uriDocument = emplacement.uriDocument,
                        idDocument = idChoisi,
                    )
                }

                is EmplacementSaf.Illisible -> {
                    return ImportDossier.Erreur(emplacement.erreur)
                }

                is EmplacementSaf.Propre -> {
                    Unit
                }
            }

            // Hors du dossier de travail : permission propre + test
            // d'écriture, permission relâchée à tout échec.
            when (val permission = fichiers.takePersistablePermission(grantUri)) {
                is AppResult.Failure -> return ImportDossier.Erreur(permission.error)
                is AppResult.Success -> Unit
            }

            val echec = testerEcriture(fichiers, uriChoisi, horloge, PREFIXE_TEMOIN)
            if (echec != null) {
                fichiers.releasePersistablePermission(grantUri)
                return ImportDossier.Erreur(echec)
            }

            return enregistrer(
                permissionPropre = true,
                grantUri = grantUri,
                uriDocument = uriChoisi,
                idDocument = idChoisi,
            )
        }

        /**
         * Ajoute le dossier au registre ; [permissionPropre] dit si une
         * permission a été prise pour lui et doit être relâchée en cas
         * d'échec (l'entrée de registre est la seule raison de la garder).
         */
        private suspend fun enregistrer(
            permissionPropre: Boolean,
            grantUri: String,
            uriDocument: String,
            idDocument: String,
        ): ImportDossier {
            val libelle = libelleLisible(fichiers, uriDocument, idDocument)
            val ajout =
                projets.addProject(
                    name = libelle,
                    description = "",
                    location = StorageLocation(grantUri, uriDocument, libelle),
                    templateId = TemplateId.IMPORTED,
                )
            return when (ajout) {
                is AppResult.Success -> {
                    ImportDossier.Ajoute(ajout.value)
                }

                is AppResult.Failure -> {
                    // Échec (y compris « déjà présent ») : la permission propre
                    // n'est conservée que si un projet restant du registre —
                    // ou le dossier de travail — référence encore cet arbre
                    // (l'entrée existante peut vivre sur la même arborescence).
                    if (permissionPropre) {
                        libererPermissionSiInutilisee(projets, parametres, fichiers, grantUri)
                    }
                    when ((ajout.error as? AppError.Storage)?.reason) {
                        AppError.StorageReason.AlreadyExists -> ImportDossier.DejaPresent
                        else -> ImportDossier.Erreur(ajout.error)
                    }
                }
            }
        }

        private companion object {
            /** Préfixe du fichier témoin du test d'écriture. */
            const val PREFIXE_TEMOIN = "codeide-import"
        }
    }
