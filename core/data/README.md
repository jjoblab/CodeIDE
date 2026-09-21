# core/data — Données — implémentations des repositories

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 4 (couche données).

Implémente les repositories du domaine en combinant les sources de données (Room, DataStore, SAF) et en journalisant les opérations via `AppLogger` (identifiants uniquement). Ne contient aucune logique métier : il traduit et assemble.

## Dépendances autorisées

`core:domain`, `core:model`, sources de données (`core:database`, `core:datastore`, `core:storage`, `core:logging`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- ProjectRepositoryImpl (étape 4)
- SettingsRepositoryImpl (étape 4)

## Vérifications du module

```bash
./gradlew :core:data:check
```
