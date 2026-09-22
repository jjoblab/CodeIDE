# app — Application — assemblage final

> Statut étape 2 : fondations (v0.2.0) et journalisation (v0.3.0 : initialisation dans le
> processus principal, `BuildInfo`/`DeviceSummary`, FileProvider des exports, premiers journaux).

Point d'entrée de CodeIDE : héberge `MainActivity`, le graphe de navigation (Onboarding, Home, NewProject, Settings), l'assemblage Hilt et la configuration globale. C'est le seul module autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

## Dépendances autorisées

Tous les modules (assemblage uniquement). Actuellement : `core:ui`, `feature:home`, `feature:settings` + bibliothèques AndroidX.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étapes 1-2)

- **`CodeIdeApplication`** — `@HiltAndroidApp` ; StrictMode et journalisation en debug ;
  `LoggingInitializer` appelé dans le processus principal uniquement ; LeakCanary en
  `debugImplementation`.
- **`MainActivity`** — `installSplashScreen()` avant `super.onCreate`, couleurs dynamiques optionnelles, `enableEdgeToEdge()`, `NavHostFragment` unique.
- **Graphe de navigation** — `res/navigation/nav_graph.xml` : destinations placeholder `home` ↔ `settings` ; la destination initiale dépendra d'`isSetupCompleted` à l'étape 5.
- **`AppNavigatorImpl` + `NavigationModule`** — implémentation de `AppNavigator` (`@ActivityScoped`, lien `@Binds` dans l'`ActivityComponent`).

Étape 2 : `di/AppLoggingModule` (`BuildInfo`, `DeviceSummary`), `di/DomainBindingsModule`
(`DispatcherProvider`), `res/xml/file_paths.xml` (FileProvider limité à `cache/exports/`).

Prévu ensuite : gestionnaire de plantages (étape 3), destination initiale conditionnelle (étape 5).

## Vérifications du module

```bash
./gradlew :app:check
```
