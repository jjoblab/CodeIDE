package jo.codeide.core.model

/**
 * Paramètres applicatifs de CodeIDE (étape 4 — couche données).
 *
 * Réglages de l'**application** (et non d'un projet) : apparence, langue,
 * dossier de travail, profil d'auteur et niveau de journalisation. La
 * source de vérité est Preferences DataStore (module `core:datastore`) ;
 * ce modèle est la projection immutable consommée par le domaine et l'UI.
 *
 * [workspace] mérite attention (section 5.6) : c'est l'emplacement du
 * dossier de travail choisi à l'onboarding (ou dans les Paramètres). Les
 * projets créés **dans** ce dossier héritent de sa permission — leur
 * `StorageLocation.grantUri` référence la même arborescence sans nouvelle
 * permission persistante. `null` signifie « non configuré » (onboarding
 * passable, bandeau « Configurer le dossier de travail » à l'accueil).
 *
 * @property themeMode mode de thème (système, clair, sombre).
 * @property useDynamicColor couleurs Material You (Android 12+, ADR 0008).
 * @property languageTag langue BCP 47 demandée, `""` pour suivre le système.
 * @property workspace dossier de travail (ou `null` si non configuré).
 * @property authorName nom d'auteur optionnel (pré-remplit README et
 * licence du wizard) ; vide si non renseigné.
 * @property defaultLicense licence proposée par défaut à la création.
 * @property logLevel verbosité de journalisation persistée (section 5.7) :
 * `NORMAL` = `INFO`, `DETAILED` = `DEBUG` ; alimente la configuration du
 * moteur au démarrage du processus principal.
 * @property isSetupCompleted l'assistant de premier lancement est terminé.
 */
@Suppress("LongParameterList") // Groupe de réglages cohérent, pas un objet métier à découper.
public data class AppSettings(
    public val themeMode: ThemeMode = ThemeMode.SYSTEM,
    public val useDynamicColor: Boolean = true,
    public val languageTag: String = "",
    public val workspace: StorageLocation? = null,
    public val authorName: String = "",
    public val defaultLicense: License = License.MIT,
    public val logLevel: LogVerbosity = LogVerbosity.NORMAL,
    public val isSetupCompleted: Boolean = false,
) {
    public companion object {
        /**
         * Valeurs par défaut d'une installation neuve.
         *
         * Seule la verbosité de journalisation dépend du type de build
         * (section 5.7 : défaut `NORMAL`, mais `DEBUG` en build debug pour
         * que les diagnostics internes soient visibles pendant le
         * développement) ; tout le reste est identique quelle que soit la
         * variante.
         *
         * @param buildDebuggable le build installé est-il déboguable ?
         * @return les paramètres par défaut correspondants.
         */
        public fun defaults(buildDebuggable: Boolean): AppSettings =
            AppSettings(
                logLevel = if (buildDebuggable) LogVerbosity.DETAILED else LogVerbosity.NORMAL,
            )
    }
}
