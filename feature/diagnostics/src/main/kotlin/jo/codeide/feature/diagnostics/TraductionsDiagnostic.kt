package jo.codeide.feature.diagnostics

import androidx.annotation.StringRes
import jo.codeide.core.model.AppError
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity

/**
 * Traduction des objets du domaine en ressources localisées (section 5.5 :
 * l'UI traduit, le domaine ne connaît ni locale ni chaîne).
 */
internal object TraductionsDiagnostic {
    /** Message d'un échec applicatif typé. */
    @StringRes
    fun message(erreur: AppError): Int =
        when (erreur) {
            is AppError.Storage -> R.string.diagnostics_erreur_stockage
            is AppError.Validation -> R.string.diagnostics_erreur_saisie
            is AppError.Template -> R.string.diagnostics_erreur_modele
            is AppError.Bootstrap -> R.string.diagnostics_erreur_outils
            is AppError.Tooling -> R.string.diagnostics_erreur_outils
            is AppError.Unknown -> R.string.diagnostics_erreur_stockage
        }

    /** Libellé court d'un niveau de journal (filtre et détail). */
    @StringRes
    fun niveau(niveau: LogLevel): Int =
        when (niveau) {
            LogLevel.DEBUG -> R.string.diagnostics_niveau_debug
            LogLevel.INFO -> R.string.diagnostics_niveau_info
            LogLevel.WARN -> R.string.diagnostics_niveau_warn
            LogLevel.ERROR -> R.string.diagnostics_niveau_error
        }

    /** Libellé d'une verbosité proposée à l'utilisateur. */
    @StringRes
    fun verbosite(verbosite: LogVerbosity): Int =
        when (verbosite) {
            LogVerbosity.NORMAL -> R.string.diagnostics_verbosite_normale
            LogVerbosity.DETAILED -> R.string.diagnostics_verbosite_detaillee
        }

    /** Libellé d'un type de plantage. */
    @StringRes
    fun type(type: CrashType): Int =
        when (type) {
            CrashType.EXCEPTION -> R.string.diagnostics_type_exception
            CrashType.ANR -> R.string.diagnostics_type_anr
            CrashType.NATIVE -> R.string.diagnostics_type_natif
        }
}
