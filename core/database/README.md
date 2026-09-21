# core/database — Base de données — Room

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 4 (couche données).

Persistance Room v1 : entêtes de table, DAO, convertisseurs, mappers. Schémas exportés dans `schemas/` pour des migrations testables. La table `projects` porte un index unique sur `documentUri` (section 11, étape 4).

## Dépendances autorisées

`core:model`, `core:domain`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- CodeIdeDatabase, ProjectDao (étape 4)
- Convertisseurs et mappers d'entités (étape 4)

## Vérifications du module

```bash
./gradlew :core:database:check
```
