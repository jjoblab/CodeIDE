package jo.codeide.feature.home

import androidx.annotation.StringRes
import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.model.AppError
import jo.codeide.core.model.ProjectAccessState

/**
 * Traduction des objets du domaine en ressources localisées (section 5.5 :
 * l'UI traduit, le domaine ne connaît ni locale ni chaîne).
 *
 * Les messages restent **clairs et actionnables** : la raison technique
 * part dans les journaux (règle 15), l'utilisateur reçoit la marche à
 * suivre.
 */
internal object TraductionsAccueil {
    /** Message d'un échec applicatif typé. */
    @StringRes
    fun message(erreur: AppError): Int =
        when (erreur) {
            is AppError.Storage -> {
                when (erreur.reason) {
                    AppError.StorageReason.PermissionLost -> R.string.accueil_erreur_permission
                    AppError.StorageReason.NotFound -> R.string.accueil_erreur_introuvable
                    AppError.StorageReason.AlreadyExists -> R.string.accueil_erreur_deja_reference
                    AppError.StorageReason.NoSpace -> R.string.accueil_erreur_espace
                    AppError.StorageReason.NotWritable -> R.string.accueil_erreur_ecriture
                    AppError.StorageReason.Io -> R.string.accueil_erreur_stockage
                }
            }

            is AppError.Validation -> {
                R.string.accueil_erreur_saisie
            }

            is AppError.Template -> {
                R.string.accueil_erreur_modele
            }

            is AppError.Bootstrap -> {
                // Les raisons fines (réseau, espace, archive) partent dans
                // les journaux ; l'accueil donne la marche à suivre.
                R.string.accueil_erreur_bootstrap
            }

            is AppError.Unknown -> {
                R.string.accueil_erreur_stockage
            }
        }

    /** Message d'un dossier refusé par Android 11+ (étape 7). */
    @StringRes
    fun refus(raison: ForbiddenFolders.Reason): Int =
        when (raison) {
            ForbiddenFolders.Reason.STORAGE_ROOT -> R.string.accueil_refus_racine
            ForbiddenFolders.Reason.DOWNLOADS -> R.string.accueil_refus_download
            ForbiddenFolders.Reason.ANDROID_DATA -> R.string.accueil_refus_android_data
            ForbiddenFolders.Reason.ANDROID_OBB -> R.string.accueil_refus_android_obb
        }

    /** Libellé d'un état d'accès (badge d'un projet). */
    @StringRes
    fun etat(etat: ProjectAccessState): Int =
        when (etat) {
            ProjectAccessState.Available -> R.string.accueil_acces_disponible
            ProjectAccessState.Missing -> R.string.accueil_acces_introuvable
            ProjectAccessState.PermissionLost -> R.string.accueil_acces_permission
        }
}
