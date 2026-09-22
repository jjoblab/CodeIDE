# feature/settings — Fonctionnalité — paramètres

> Statut étape 6 (v0.7.0) : complet et testé.

Écran Paramètres **personnalisé Material 3** (pas de
`PreferenceFragmentCompat`), piloté par `SettingsViewModel` et
DataStore, en sections extensibles — chaque réglage se persiste à
l'instant et prend effet immédiatement (recréation d'écran par
`MainActivity`).

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (via la convention
`codeide.android.feature`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## Sections

- **Apparence** : thème (système/clair/sombre), couleurs dynamiques.
- **Langue** : système, FR, EN (`setApplicationLocales`, ADR 0013).
- **Projets** : dossier de travail (changer via sélecteur SAF,
  effacer), nom d'auteur (écrit à la perte de focus), licence par
  défaut. Changer ou effacer le dossier **ne libère l'ancienne
  permission persistante que si aucun projet n'en dépend**
  (use cases du domaine, ADR 0014) ; messages clairs par issue.
- **À propos** : version (`CrashAppInfo` injecté), type de build,
  licences open source embarquées.
- **Avancé** : réinitialiser les préférences (confirmation, ADR 0014),
  relancer l'assistant. L'entrée Diagnostic arrive à l'étape 12.

## API publique

- **`SettingsFragment`** — destination `R.id.settings` du graphe de
  navigation ; `BaseFragment<FragmentSettingsBinding>`, `MaterialToolbar`
  avec retour via `AppNavigator.goBack()`.
- **`SettingsViewModel`** — UDF (section 5.3) : `onAction(ActionParametres)`
  en entrée, `etat: StateFlow<EtatParametres>` et
  `effets: Flow<EffetParametres>` en sortie.

## Vérifications du module

```bash
./gradlew :feature:settings:check
```

Procédures manuelles sur appareil : M1-M5 dans `docs/TESTS_MANUELS.md`.
