# core/model — Modèle — entités et types partagés

> Statut étape 1 : fondations livrées (`AppResult`, `AppError`, identifiants typés, `StorageLocation`). Le module s'enrichit à chaque étape suivante.

Module Kotlin JVM pur : entités immuables et types partagés de tout le domaine. Aucune dépendance, ni Android ni autre module : c'est la base du graphe de dépendances.

## Dépendances autorisées

Aucune (module terminal).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`AppResult<out T>`** — `Success(value)` / `Failure(error: AppError)` avec extensions `getOrNull`, `onSuccess`, `onFailure` (ADR 0004).
- **`AppError`** — erreurs applicatives modélisées : `Storage` (raisons `PermissionLost`, `NotFound`, `AlreadyExists`, `NoSpace`, `NotWritable`, `Io`), `Validation`, `Template`, `Unknown` (section 5.5 du prompt).
- **Identifiants typés** — `EntityId` (interface commune), `ProjectId`, `TemplateId`, `CrashReportId` (`@JvmInline value class`, non vides) ; seules données métier autorisées dans les journaux.
- **`StorageLocation`** — emplacement SAF : `grantUri` (URI de permission), `documentUri` (URI du dossier), `displayPath` (libellé lisible) ; `toString` n'expose volontairement que le libellé (règle 15).

Types prévus aux étapes suivantes : `Project`/`ProjectAccessState` (étape 4), `LogLevel`/`LogEntry` (étape 2), `CrashReport`… (étape 3).

## Vérifications du module

```bash
./gradlew :core:model:check
```

Couverture exigée : ≥ 80 % (Kover, seuil vérifié par `koverVerify`).
