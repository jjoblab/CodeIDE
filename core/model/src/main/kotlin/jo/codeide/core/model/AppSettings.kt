package jo.codeide.core.model

/**
 * Paramètres applicatifs de CodeIDE (étape 4 — couche données ; ADR 0059
 * pour l'extension Éditeur/Terminal/Notifications).
 *
 * Réglages de l'**application** (et non d'un projet) : apparence, langue,
 * notifications, éditeur, terminal, dossier de travail, profil d'auteur
 * et niveau de journalisation. La source de vérité est Preferences
 * DataStore (module `core:datastore`) ; ce modèle est la projection
 * immutable consommée par le domaine et l'UI.
 *
 * [workspace] mérite attention (section 5.6) : c'est l'emplacement du
 * dossier de travail choisi à l'onboarding (ou dans les Paramètres). Les
 * projets créés **dans** ce dossier héritent de sa permission — leur
 * `StorageLocation.grantUri` référence la même arborescence sans nouvelle
 * permission persistante. `null` signifie « non configuré » (onboarding
 * passable, bandeau « Configurer le dossier de travail » à l'accueil).
 *
 * Sections Éditeur et Terminal (ADR 0059 ; v0.37.0) : les réglages de
 * l'éditeur sont **consommés au fil de l'eau** par `OptionsEditeur`
 * (feature:editor) — chaque champ projette une API réelle de cel-ui
 * (thème, zoom, retour à la ligne, minimap…). Les anciens réglages
 * « persistés avant consommation » jamais honorés par la bibliothèque
 * (numéros de ligne, surlignage de ligne, tabulation — toujours dessinés
 * ou auto-détectés par cel-ui) ont été retirés ; le terminal consomme
 * déjà style du curseur et copie de sélection.
 *
 * Section Tooling (v3 — écran de configuration de la console) : affichage
 * des tâches pendant le build, mode hors ligne et arguments Gradle libres
 * — consultables depuis l'onglet Sortie de l'espace de travail.
 *
 * @property themeMode mode de thème (système, clair, sombre).
 * @property useDynamicColor couleurs Material You (Android 12+, ADR 0008).
 * @property paletteCouleur palette statique quand les couleurs dynamiques
 * sont désactivées (ADR 0060).
 * @property languageTag langue BCP 47 demandée, `""` pour suivre le système.
 * @property notificationsSync notifications du canal Synchronisation.
 * @property notificationsBuild notifications du canal Build.
 * @property sonNotifications son des notifications du tooling.
 * @property editorRetourLigne retour à la ligne automatique de l'éditeur
 * (`EditorView.setWordWrap`).
 * @property editorThemeEditeur thème de coloration de l'éditeur (les neuf
 * thèmes embarqués de cel-ui, ou le suivi de l'application).
 * @property editorMinimap bande minimap à droite de l'éditeur
 * (`EditorView.setMinimapEnabled`).
 * @property editorCaracteresNonImprimables affichage des espaces,
 * tabulations et fins de ligne (`EditorView.setShowNonPrintable`).
 * @property editorLigatures ligatures de la police à chasse fixe — coupe
 * la coloration syntaxique (compromis documenté de cel-ui).
 * @property editorChipsDiagnostics badges de diagnostic en fin de ligne
 * (`EditorView.setDiagnosticChipsEnabled`).
 * @property editorSauvegardeAuto sauvegarde à chaque perte de focus.
 * @property editorTaillePolice taille de police de l'éditeur.
 * @property styleCurseurTerminal style du curseur de l'émulateur.
 * @property copieSelectionAuto copier la sélection du terminal dès sa fin.
 * @property workspace dossier de travail (ou `null` si non configuré).
 * @property authorName nom d'auteur optionnel (pré-remplit README et
 * licence du wizard) ; vide si non renseigné.
 * @property defaultLicense licence proposée par défaut à la création.
 * @property logLevel verbosité de journalisation persistée (section 5.7) :
 * `NORMAL` = `INFO`, `DETAILED` = `DEBUG` ; alimente la configuration du
 * moteur au démarrage du processus principal.
 * @property taillePoliceTerminal taille de la police à chasse fixe du
 * terminal intégré (Terminal T5) — section Terminal des Paramètres.
 * @property toolingAfficherTaches afficher les tâches dans la console au
 * fil du build (v3 — une ligne par tâche, comme la vue Build d'Android
 * Studio) ; le résumé de fin reste affiché même désactivé.
 * @property toolingHorsLigne lancer les builds avec `--offline` (aucun
 * accès réseau au dépôt — les dépendances doivent déjà être en cache).
 * @property toolingArguments arguments Gradle libres (séparés par des
 * espaces, ex. `--stacktrace --info`), vidés par défaut.
 * @property isSetupCompleted l'assistant de premier lancement est terminé.
 */
@Suppress("LongParameterList") // Groupe de réglages cohérent, pas un objet métier à découper.
public data class AppSettings(
    public val themeMode: ThemeMode = ThemeMode.SYSTEM,
    public val useDynamicColor: Boolean = true,
    public val paletteCouleur: PaletteCouleur = PaletteCouleur.INDIGO,
    public val languageTag: String = "",
    public val notificationsSync: Boolean = true,
    public val notificationsBuild: Boolean = true,
    public val sonNotifications: Boolean = false,
    public val editorRetourLigne: Boolean = true,
    public val editorThemeEditeur: ThemeEditeur = ThemeEditeur.AUTO,
    public val editorMinimap: Boolean = false,
    public val editorCaracteresNonImprimables: Boolean = false,
    public val editorLigatures: Boolean = false,
    public val editorChipsDiagnostics: Boolean = true,
    public val editorSauvegardeAuto: Boolean = true,
    public val editorTaillePolice: TaillePoliceEditeur = TaillePoliceEditeur.MOYENNE,
    public val styleCurseurTerminal: StyleCurseurTerminal = StyleCurseurTerminal.BLOC,
    public val copieSelectionAuto: Boolean = false,
    public val workspace: StorageLocation? = null,
    public val authorName: String = "",
    public val defaultLicense: License = License.MIT,
    public val logLevel: LogVerbosity = LogVerbosity.NORMAL,
    public val taillePoliceTerminal: TaillePoliceTerminal = TaillePoliceTerminal.MOYENNE,
    public val toolingAfficherTaches: Boolean = true,
    public val toolingHorsLigne: Boolean = false,
    public val toolingArguments: String = "",
    /** Injection de la bibliothèque applog-runtime dans les builds debug des
     *  projets (mission « Exécuter » R2, ADR 0103) — par défaut ACTIVE ;
     *  les journaux des apps exécutées arrivent dans l'onglet Logcat. */
    public val injectionAppLog: Boolean = true,
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
