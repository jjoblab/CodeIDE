# core/crash — Plantages — capture et écran dédié

> Statut étape 3 : **livré** (v0.4.0). Implémentation complète de la
> section 5.8 du prompt maître.

Gestion des plantages maison (sans service réseau, aucun envoi
automatique) : `CrashHandler` installé en **première ligne** de
`Application.onCreate` (avant Hilt, chaîné au gestionnaire précédent),
rapport JSON écrit **synchrone et atomique** (`.tmp` puis renommage,
réduction progressive jusqu'à 256 Ko, 20 rapports maximum, état « consulté »
par fichier témoin), détection de boucle (≥ 3 plantages en 60 s —
l'écran dédié ne propose plus de redémarrage et on délègue au système),
détection au démarrage des **ANR** et **plantages natifs** via
`ApplicationExitInfo` (API 30+, hors thread principal, dédoublonnés), et
`CrashActivity` en **processus séparé `:crash`** (sans Hilt, sans Room,
sans DataStore) : modes LIVE/VIEW, copie, partage texte/archive (FileProvider
dédié — ADR 0010), enregistrement SAF, vidage du cache en cas de boucle.

Liaison avec `core:logging` **par lambdas uniquement** (identifiant de
session, filons de pain, vidage borné) — jamais de dépendance de module :
`app` fournit les lambdas après l'injection Hilt.

Choix d'implémentation : **ADR 0006** (processus séparé et délégation) et
**ADR 0010** (FileProvider du processus `:crash`). Guide complet :
`docs/JOURNALISATION_ET_PLANTAGES.md` § 9. Procédures sur appareil :
`docs/TESTS_MANUELS.md` (P1-P8).

## Dépendances autorisées

`core:model`, `core:domain`, `core:ui` (jamais `core:logging` — liaison
par interfaces/lambdas ; les fakes de `core:testing` en `testImplementation`
uniquement).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `CrashHandler.install(application, appInfo, deviceInfo)` — installation
  dans le processus principal ; `installSafe()` pour le processus `:crash` ;
- `CrashHandler.brancherJournalisation(sessionId, breadcrumbs, flush)` —
  liaison avec `core:logging` après Hilt (lambdas) ;
- `CrashReportFileStore` — persistance des rapports (utilisée aussi bien
  par le gestionnaire, avant Hilt, que par l'écran dédié, sans Hilt) ;
- `AppProcess` — détection du processus courant (MAIN/CRASH/OTHER) ;
- `DeviceSnapshot.depuisContext(context)` — photographie non
  identifiante de l'appareil ;
- `CrashActivity` + constantes d'intent (modes LIVE/VIEW) ;
- les liaisons Hilt vers `CrashReportRepository` et
  `PendingExitInfoRecorder` du domaine.

Le reste (gestionnaire, fabrique, mapping JSON, détecteur de boucle,
détecteur de démarrage, formateur, zip) est `internal` au module.

## Vérifications du module

```bash
./gradlew :core:crash:check
```

Tests : sérialisation aller-retour, réduction et limites (256 Ko),
expurgation à la construction, écriture atomique, rétention 20,
fichiers corrompus, boucle, `ApplicationExitInfo` (Robolectric API 30+),
chaînage du gestionnaire (tueur et lanceur injectés), écran dédié.
