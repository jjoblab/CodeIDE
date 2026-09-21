# core/logging — Journalisation — sinks, rotation, export

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 2 (journalisation).

Implémentation maison du système de journalisation (sans Timber) : sinks Logcat et fichier (JSON Lines, rotation 1 Mo / 5 archives / 7 jours), écriture asynchrone non bloquante, tampon circulaire de breadcrumbs, expurgation à l'écriture (`LogRedactor`), export zip via FileProvider. Seul module (avec `core:crash`) autorisé à toucher `android.util.Log`.

## Dépendances autorisées

`core:model`, `core:domain`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- LogcatSink, FileSink (étape 2)
- Tampon circulaire mémoire 200 entrées (étape 2)
- ExportLogsUseCase — zip expurgé dans cache/exports/ (étape 2)

## Vérifications du module

```bash
./gradlew :core:logging:check
```
