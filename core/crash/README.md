# core/crash — Plantages — capture et écran dédié

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 3 (gestion des plantages).

Capture des plantages non gérés (`CrashHandler`, installé en première ligne du `Application.onCreate`), écriture atomique de rapports JSON, détection de boucle de plantages, `CrashActivity` dans un processus séparé `:crash` sans Hilt ni Room ni DataStore. Liaison avec `core:logging` par lambdas uniquement (jamais de dépendance de module).

## Dépendances autorisées

`core:model`, `core:domain`, `core:ui` (jamais `core:logging`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- CrashHandler + CrashReportFileStore (étape 3)
- CrashActivity — modes LIVE/VIEW, processus :crash (étape 3)
- Détection ApplicationExitInfo, ANR et plantages natifs (étape 3)

## Vérifications du module

```bash
./gradlew :core:crash:check
```
