# app — Application — assemblage final

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 1 (fondations) puis chaque étape ajoute ses écrans.

Point d'entrée de CodeIDE : héberge `MainActivity`, le graphe de navigation (Onboarding, Home, NewProject, Settings), l'assemblage Hilt et la configuration globale. C'est le seul module autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

## Dépendances autorisées

Tous les modules (assemblage uniquement).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- MainActivity — hôte unique des fragments
- CodeIdeApplication — initialisation Hilt, StrictMode/LeakCanary en debug (étape 1)
- AppNavigatorImpl — implémentation concrète de l'interface `core:ui`
- CrashHandlerApp — installation du gestionnaire de plantages (étape 3)

## Vérifications du module

```bash
./gradlew :app:check
```
