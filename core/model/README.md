# core/model — Modèle — entités et types partagés

> Statut étape 4 : fondations (v0.2.0 : `AppResult`, `AppError`, identifiants typés, `StorageLocation`),
> journalisation (v0.3.0 : `LogLevel`, `LogEntry` sérialisable, `FlattenedException`),
> plantages (v0.4.0 : `CrashReport`, `CrashReportSummary`, `CrashType`, `CrashAppInfo`, `DeviceInfo`),
> couche données (v0.5.0 : `Project`, `ProjectAccessState`, `AppSettings`, `ThemeMode`,
> `LogVerbosity`, `License`).

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

Couche données (étape 4, v0.5.0) : `Project` (identifiant, nom, description, emplacement SAF, modèle, horodatages, épingle), `ProjectAccessState` (calculé, jamais persisté), `AppSettings` (thème, couleurs dynamiques, langue, dossier de travail, profil auteur, licence, verbosité de journalisation, assistant terminé) et `ThemeMode`/`LogVerbosity`/`License`.

Moteur de templates (étape 8, v0.9.0) : `ProjectTemplate` et `TemplateParameter` (types `TEXT`/`BOOLEAN`/`CHOICE`, sections du wizard, `persist`), `TemplateOptions` (options communes — README, gitignore, editorconfig, licence, langue du contenu) avec `codeTemplate()` (codes de licence du mini-langage), `TemplatePlan`/`PlannedFile`/`PlannedContent` (plan figé du dry-run — égalité par valeur, y compris les octets binaires), `TemplateSummary`/`TemplateFormEvaluation` (libellés résolus, visibilité, valeurs effectives, validité) et `CreationProgress` (progression de création + `CreateProjectRequest`).

## Vérifications du module

```bash
./gradlew :core:model:check
```

Couverture exigée : ≥ 80 % (Kover, seuil vérifié par `koverVerify`).
