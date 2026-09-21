# feature/settings — Fonctionnalité — paramètres

> Statut étape 1 : fragment placeholder livré (barre d'outils avec retour, navigation via AppNavigator). L'écran complet arrive à l'étape 6.

Écran Paramètres personnalisé Material 3 (pas de `PreferenceFragmentCompat`), piloté par un ViewModel et DataStore : apparence, langue, projets, à propos, avancé.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (via la convention `codeide.android.feature`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`SettingsFragment`** — destination Paramètres du graphe (écran provisoire) : `BaseFragment<FragmentSettingsBinding>`, `MaterialToolbar` avec retour via `AppNavigator.goBack()`.

Prévu à l'étape 6 : `SettingsViewModel`, sections Apparence/Langue/Projets/À propos/Avancé.

## Vérifications du module

```bash
./gradlew :feature:settings:check
```
