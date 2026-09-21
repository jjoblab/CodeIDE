# core/datastore — Préférences — Preferences DataStore

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 4 (couche données).

Stockage des préférences de l'application (thème, langue, dossier de travail, niveau de journalisation, profil auteur, `isSetupCompleted`) via Preferences DataStore, avec `ReplaceFileCorruptionHandler`. Le niveau de journalisation persisté alimente `LogConfig`.

## Dépendances autorisées

`core:model`, `core:domain`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- SettingsDataStore — source unique des préférences (étape 4)

## Vérifications du module

```bash
./gradlew :core:datastore:check
```
