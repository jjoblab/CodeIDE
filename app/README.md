# app — Application — assemblage final

> Statut étape 1 : fondations livrées (`CodeIdeApplication`, `MainActivity` avec SplashScreen/NavHost/edge-to-edge, graphe Accueil ↔ Paramètres, `AppNavigatorImpl`). Chaque étape suivante ajoute ses écrans.

Point d'entrée de CodeIDE : héberge `MainActivity`, le graphe de navigation (Onboarding, Home, NewProject, Settings), l'assemblage Hilt et la configuration globale. C'est le seul module autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

## Dépendances autorisées

Tous les modules (assemblage uniquement). Actuellement : `core:ui`, `feature:home`, `feature:settings` + bibliothèques AndroidX.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`CodeIdeApplication`** — `@HiltAndroidApp` ; StrictMode en debug (détection via `FLAG_DEBUGGABLE`, sans buildConfig) ; LeakCanary en `debugImplementation`.
- **`MainActivity`** — `installSplashScreen()` avant `super.onCreate`, couleurs dynamiques optionnelles, `enableEdgeToEdge()`, `NavHostFragment` unique.
- **Graphe de navigation** — `res/navigation/nav_graph.xml` : destinations placeholder `home` ↔ `settings` ; la destination initiale dépendra d'`isSetupCompleted` à l'étape 5.
- **`AppNavigatorImpl` + `NavigationModule`** — implémentation de `AppNavigator` (`@ActivityScoped`, lien `@Binds` dans l'`ActivityComponent`).

Prévu ensuite : installation du gestionnaire de plantages (étape 3), `FileProvider` (étape 2), destination initiale conditionnelle (étape 5).

## Vérifications du module

```bash
./gradlew :app:check
```
