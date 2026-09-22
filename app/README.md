# app — Application — assemblage final

> Statut étape 2 : fondations (v0.2.0) et journalisation (v0.3.0 : initialisation dans le
> processus principal, `BuildInfo`/`DeviceSummary`, FileProvider des exports, premiers journaux) ;
> étape 3 : plantages (v0.4.0 : gestionnaire en première ligne d'`onCreate`, `Application` sensible
> au processus, dialogue « rapport non consulté », menu debug).

Point d'entrée de CodeIDE : héberge `MainActivity`, le graphe de navigation (Onboarding, Home, NewProject, Settings), l'assemblage Hilt et la configuration globale. C'est le seul module autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

## Dépendances autorisées

Tous les modules (assemblage uniquement). Actuellement : `core:ui`, `feature:home`, `feature:settings` + bibliothèques AndroidX.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étapes 1-3)

- **`CodeIdeApplication`** — `@HiltAndroidApp` **sensible au processus** (section 5.8) : `CrashHandler.install` en toute première ligne d'`onCreate` (avant Hilt) dans le processus principal, `installSafe` dans `:crash` ; StrictMode en debug ; journalisation et liaison `session`/`filons`/`vidage` branchées après Hilt, processus principal uniquement ; enregistrement des sorties non traitées (ANR, natifs) hors thread principal ; LeakCanary en `debugImplementation`.
- **`MainActivity`** — `installSplashScreen()` avant `super.onCreate`, couleurs dynamiques optionnelles, `enableEdgeToEdge()`, `NavHostFragment` unique ; suivi du dernier écran (destination de navigation) pour les rapports ; boîte de dialogue « Un problème est survenu lors de la dernière session » quand un rapport n'est pas consulté (Voir / Ignorer valent consultation).
- **Graphe de navigation** — `res/navigation/nav_graph.xml` : destinations placeholder `home` ↔ `settings` ; la destination initiale dépendra d'`isSetupCompleted` à l'étape 5.
- **`AppNavigatorImpl` + `NavigationModule`** — implémentation de `AppNavigator` (`@ActivityScoped`, lien `@Binds` dans l'`ActivityComponent`), dont `openCrashReport(id)` vers l'écran dédié en consultation.
- **Menu debug** — `debug/…/MenuDebug.kt` (variante debug) : provoquer un plantage, exception non fatale journalisée, salve de journaux ; no-op de même signature en release (`release/…/MenuDebug.kt`).

Étape 2 : `di/AppLoggingModule` (`BuildInfo`, `DeviceSummary`), `di/DomainBindingsModule`
(`DispatcherProvider`), `res/xml/file_paths.xml` (FileProvider limité à `cache/exports/`).
Étape 3 : `di/AppCrashModule` (`CrashAppInfo` du détecteur de démarrage).

Prévu ensuite : destination initiale conditionnelle (étape 5).

## Vérifications du module

```bash
./gradlew :app:check
```
