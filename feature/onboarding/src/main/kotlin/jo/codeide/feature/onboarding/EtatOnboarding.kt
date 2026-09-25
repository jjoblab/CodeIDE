package jo.codeide.feature.onboarding

import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.model.AppError
import jo.codeide.core.model.License
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.ThemeMode

/**
 * Pages de l'assistant, dans l'ordre du parcours (étape 5 du plan ;
 * page Notifications ajoutée en v0.31.2, ADR 0046).
 *
 * L'ordre est contractuel : la bienvenue présente, le dossier de travail
 * est **passable**, le terminal suit (ses outils tournent en service
 * foreground → la page notifications qui suit explique ce que l'app
 * en fait), l'apparence s'applique immédiatement au fil des choix,
 * le profil est optionnel, la page finale valide l'ensemble.
 */
enum class PageOnboarding {
    /** Présentation courte de CodeIDE. */
    BIENVENUE,

    /** Sélecteur SAF du dossier de travail, avec test d'écriture. */
    DOSSIER,

    /** Outils du terminal : installation **passable** (Terminal T3). */
    TERMINAL,

    /** Permission de notification (Android 13+) — passable (v0.31.2). */
    NOTIFICATIONS,

    /** Thème, couleurs dynamiques, langue — aperçu immédiat. */
    APPARENCE,

    /** Nom d'auteur optionnel et licence par défaut. */
    PROFIL,

    /** Récapitulatif et validation (`isSetupCompleted`). */
    TERMINE,
}

/**
 * État du dossier de travail au fil de la sélection (étape 5).
 *
 * La machine est volontairement explicite : chaque échec garde sa raison
 * (dossier refusé par Android, erreur typée) pour que la page affiche un
 * message **clair et actionnable** au lieu d'un crash ou d'un silence.
 */
sealed interface EtatDossier {
    /** Aucune sélection en cours : invite initiale ou « Plus tard ». */
    data object NonConfigure : EtatDossier

    /** Sélection reçue : permission, existence et test d'écriture en cours. */
    data object Verification : EtatDossier

    /** Dossier refusé par Android 11+ (racine, Download, Android/data…). */
    data class Refuse(
        val raison: ForbiddenFolders.Reason,
    ) : EtatDossier

    /** Échec typé de la permission, du test d'écriture ou de la persistance. */
    data class Erreur(
        val erreur: AppError,
    ) : EtatDossier

    /** Dossier validé et persisté comme dossier de travail. */
    data class Configure(
        val dossier: StorageLocation,
    ) : EtatDossier
}

/**
 * État observable de l'assistant (section 5.3 : `XxxUiState` immutable
 * exposée en `StateFlow`).
 *
 * Chaque champ survit à la rotation **et** à la mort du processus : la
 * page et les champs du profil vivent dans le `SavedStateHandle` du
 * ViewModel, les choix d'apparence et le dossier validé sont déjà
 * persistés dans les paramètres applicatifs.
 *
 * @property page page courante du pager.
 * @property dossier état de la sélection du dossier de travail.
 * @property terminalInstalle les outils du terminal sont déjà en place
 * (page Terminal : les boutons d'installation disparaissent).
 * @property notificationsActivees l'autorisation de notification est
 * effective (page Notifications, v0.31.2 : état réel relevé par le
 * fragment, consigné ici pour le rendu).
 * @property stockagePartageActif l'accès OPT-IN au stockage partagé est
 * effectif (page Notifications, v0.31.3, ADR 0047 : `isExternalStorageManager`
 * sous Android 11+, permission WRITE sinon — état réel, jamais supposé).
 * @property modeTheme thème choisi (persisté dès le changement).
 * @property couleursDynamiques couleurs Material You (persistées dès le
 * changement).
 * @property langue tag BCP 47 demandé, `""` pour suivre le système.
 * @property nomAuteur nom d'auteur saisi (persisté à la fin de
 * l'assistant uniquement — pas d'écriture par frappe).
 * @property licenceDefaut licence par défaut proposée au wizard.
 * @property finalisation finalisation en cours (garde anti double-appui
 * du bouton « Terminer », désactivé le temps de l'écriture).
 * @property erreurFinalisation la dernière finalisation a échoué : la page
 * Terminé le signale à l'écran, le bouton reste actif pour réessayer.
 */
data class EtatOnboarding(
    val page: PageOnboarding = PageOnboarding.BIENVENUE,
    val dossier: EtatDossier = EtatDossier.NonConfigure,
    val terminalInstalle: Boolean = false,
    val notificationsActivees: Boolean = false,
    val stockagePartageActif: Boolean = false,
    val modeTheme: ThemeMode = ThemeMode.SYSTEM,
    val couleursDynamiques: Boolean = true,
    val langue: String = "",
    val nomAuteur: String = "",
    val licenceDefaut: License = License.MIT,
    val finalisation: Boolean = false,
    val erreurFinalisation: Boolean = false,
)
