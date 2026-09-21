# core/model — Modèle — entités et types partagés

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 1 (types de base) puis enrichi à chaque étape.

Module Kotlin JVM pur : entités immuables et types partagés de tout le domaine (`AppResult`, `AppError`, identifiants typés, `Project`, `StorageLocation`, `LogLevel`, `LogEntry`, `CrashReport`…). Aucune dépendance, ni Android ni autre module : c'est la base du graphe de dépendances.

## Dépendances autorisées

Aucune (module terminal).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- AppResult / AppError — modélisation des erreurs attendues (étape 1)
- Project, ProjectAccessState (étape 4)
- StorageLocation — URI SAF du dossier de travail (étape 4)
- LogLevel, LogEntry (étape 2)
- CrashReport, CrashReportSummary, CrashType, DeviceInfo (étape 3)

## Vérifications du module

```bash
./gradlew :core:model:check
```
