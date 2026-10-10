package jo.codeide.feature.settings

import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.CrashAppInfo

/**
 * Retour d'opération sur le dossier de travail (étape 6) : message
 * transitoire sous la rangée du dossier — succès, refus plateforme ou
 * échec typé. `Aucun` en régime établi.
 */
sealed interface RetourDossier {
    /** Rien à signaler : le réglage suffit. */
    data object Aucun : RetourDossier

    /** Dossier changé (l'ancienne permission suit la règle des projets). */
    data object Change : RetourDossier

    /** Dossier effacé — [permissionGardee] signale qu'un projet l'utilise. */
    data class Efface(
        val permissionGardee: Boolean,
    ) : RetourDossier

    /** Dossier refusé par Android 11+ (message clair par raison). */
    data object Refuse : RetourDossier

    /** Échec typé (permission, test d'écriture, persistance). */
    data object Erreur : RetourDossier
}

/**
 * Retour de l'entretien de l'historique local (mission H6).
 */
sealed interface RetourHistorique {
    /** Rien à signaler. */
    data object Aucun : RetourHistorique

    /** Historique effacé — l'empreinte repart de zéro. */
    data object Efface : RetourHistorique
}

/**
 * État observable de l'écran Paramètres (section 5.3 : `XxxUiState`
 * immutable exposée en `StateFlow`).
 *
 * [reglages] reflète les paramètres applicatifs en continu : chaque
 * réglage persisté y passe, l'effet immédiat vient de la collecte de
 * `MainActivity` (recréation d'écran, ADR 0013) — pas d'un état local
 * divergent.
 *
 * @property reglage paramètres applicatifs courants.
 * @property retourDossier retour transitoire de la dernière opération
 * sur le dossier de travail.
 * @property verificationDossier une opération dossier est en cours
 * (sélecteur, permission, test d'écriture).
 * @property infosBuild version et type de build (section « À propos »).
 * @property empreinteHistoriqueOctets espace occupé par TOUT
 * l'historique local (mission H6 — `null` tant que la mesure n'est
 * pas revenue, E/S hors fil principal).
 * @property retourHistorique retour transitoire de l'effacement
 * d'entretien (mission H6).
 */
data class EtatParametres(
    val reglage: AppSettings = AppSettings(),
    val retourDossier: RetourDossier = RetourDossier.Aucun,
    val verificationDossier: Boolean = false,
    val infosBuild: CrashAppInfo = CrashAppInfo("", 0L, "", ""),
    val empreinteHistoriqueOctets: Long? = null,
    val retourHistorique: RetourHistorique = RetourHistorique.Aucun,
)
