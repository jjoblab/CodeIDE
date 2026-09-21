# feature/editor — Fonctionnalité — espace de travail (stub)

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 13 (ouverture de projet).

Stub de l'espace de travail futur : `EditorActivity` séparée (activité lourde), nom du projet, arborescence racine en lecture seule via `FileSystem`, mention « éditeur à venir ». L'ouverture d'un projet met à jour `lastOpenedAt`.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- EditorActivity — stub (étape 13)

## Vérifications du module

```bash
./gradlew :feature:editor:check
```
