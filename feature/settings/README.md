# feature/settings — Fonctionnalité — paramètres

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 6 (paramètres).

Écran Paramètres Material 3 personnalisé (pas de PreferenceFragmentCompat), piloté par ViewModel et DataStore : apparence, langue, projets (dossier de travail, auteur, licence), à propos, avancé (réinitialisation, relance de l'assistant, diagnostic à l'étape 12). Changer le dossier de travail ne libère l'ancienne permission que si aucun projet n'en dépend.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- SettingsFragment + SettingsViewModel (étape 6)

## Vérifications du module

```bash
./gradlew :feature:settings:check
```
