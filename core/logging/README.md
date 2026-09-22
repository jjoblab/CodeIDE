# core/logging — Journalisation — sinks, rotation, export

> Statut étape 2 : **livré** (v0.3.0). Implémentation complète de la
> section 5.7 du prompt maître.

Implémentation maison du système de journalisation (sans Timber) : moteur
du pipeline (filtrage, expurgation, troncature, aplatissement, breadcrumbs),
sinks Logcat (`DEBUG+` en debug, `WARN+` en release) et fichier (JSON
Lines dans `filesDir/logs/`, rotation 1 Mio / 5 archives / 7 jours de
rétention), écriture asynchrone bornée (canal `DROP_OLDEST`, groupement
500 ms, flush immédiat sur `ERROR`, `flushBlocking` par verrou de
coordination), en-tête de session, dépôt et export zip
(`codeide-logs-<date>.zip` UTC dans `cache/exports/` + `device-info.txt`,
nettoyage des 5 plus récents). Seul module (avec `core:crash`) autorisé à
toucher `android.util.Log`.

Choix d'implémentation : **ADR 0009**. Guide complet :
`docs/JOURNALISATION_ET_PLANTAGES.md`. Procédures sur appareil :
`docs/TESTS_MANUELS.md`.

## Dépendances autorisées

`core:model`, `core:domain` (les fakes de `core:testing` en
`testImplementation` uniquement).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `CodeIdeAppLogger` — façade `AppLogger` (filtrage à chaud via
  `updateConfig`, `sessionId`, `flushBlocking` réservé au gestionnaire de
  plantages) ;
- `LoggingInitializer` — démarrage du pipeline + en-tête de session,
  appelé par `app` **dans le processus principal uniquement** ;
- `BuildInfo`, `DeviceSummary` — valeurs fournies par `app` ;
- les liaisons Hilt vers `AppLogger`, `LogRepository`, `LogExportWriter`
  du domaine.

Le reste (moteur, sinks, stockage, dépôt, export) est `internal` au
module.

## Vérifications du module

```bash
./gradlew :core:logging:check
```

36 tests : rotation, rétention, lignes corrompues, groupement en temps
virtuel, flush ERROR, vidage bloquant, non-blocage de l'appelant, export
zip, stress multi-threads (intégrité, FIFO par producteur).
