# core/domain — Domaine — cas d'usage et interfaces

> Statut étape 2 : fondations (v0.2.0 : `DispatcherProvider`, convention des use cases) et journalisation
> (v0.3.0 : `AppLogger`, `LogRedactor`, `LogConfig`, `LogRepository`, `TimeProvider`, use cases
> `ObserveLogs`/`ExportLogs`/`ClearLogs`, `LogExportWriter`).

Module Kotlin JVM pur : cas d'usage (use cases), interfaces de repositories, `FileSystem`, `AppLogger`, `LogRedactor`, `DispatcherProvider`. Autorise `javax.inject` et Coroutines/Flow. Ne connaît ni Android ni les implémentations.

## Dépendances autorisées

`core:model` uniquement (api), `kotlinx-coroutines-core` (api — `CoroutineDispatcher` dans la signature publique), `javax.inject` (implémentation).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`DispatcherProvider`** — `io`, `default`, `main` : les dispatchers sont **injectés**, jamais codés en dur (règle 5 du prompt).
- **`DefaultDispatcherProvider`** — implémentation de référence (`@Inject`), branchée sur `Dispatchers.IO/Default/Main`.
- **Convention des use cases** — chaque use case est une classe avec `operator fun invoke`, injectable et testable unitairement ; la convention complète (signatures, `AppResult`, dispatchers) est décrite dans `docs/CONVENTIONS.md` § « Use cases ».

Interfaces prévues aux étapes suivantes : `AppLogger`/`LogRepository` (étape 2), `CrashReportRepository` (étape 3), `ProjectRepository`/`SettingsRepository`/`FileSystem` (étape 4), use cases du moteur de templates (étape 8).

## Vérifications du module

```bash
./gradlew :core:domain:check
```

Couverture exigée : ≥ 80 % (Kover, seuil vérifié par `koverVerify`).
