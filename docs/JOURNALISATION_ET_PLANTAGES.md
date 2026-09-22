# Journalisation et plantages de CodeIDE

Ce document décrit le socle de diagnostic de CodeIDE. La **journalisation**
(estape 2, ce document) est livrée ; la **gestion des plantages** (étape 3)
complétera le document. Le texte de référence est la section 5.7 du prompt
maître ; les choix d'implémentation sont consignés dans l'ADR 0009.

## 1. Vue d'ensemble

```
AppLogger (core:domain — interface)
   │  log(level, tag, throwable?) { message }   ← lambda évaluée seulement si active
   ▼
LogEngine (core:logging — internal)
   │  1. filtre par niveau minimal (config à chaud)
   │  2. expurgation LogRedactor (courriels, URI content://, chemins)
   │  3. troncature à 4 Kio ; exception aplatie (50 trames, 10 causes)
   │  4. tampon circulaire des 200 dernières entrées (breadcrumbs)
   ├────────────────► LogcatSink      (DEBUG+ en debug, WARN+ en release)
   ├────────────────► FileSink ──canal borné DROP_OLDEST──► consommateur
   │                    (fichiersDir/logs/, JSON Lines, rotation, groupement)
   └────────────────► SharedFlow d'entrées (dépôt : observeRecent)
```

- **API du domaine** : `AppLogger`, `LogRepository`, `LogConfig`, use cases
  `ObserveLogs` / `ExportLogs` / `ClearLogs`, `LogRedactor`, `TimeProvider`.
- **Modèle** : `LogLevel`, `LogEntry` (sérialisable — c'est la ligne JSONL),
  `FlattenedException`.
- **Doubles de test** : `FakeAppLogger`, `InMemoryLogRepository`
  (`core:testing`, `testImplementation` uniquement).

## 2. Format des fichiers

Répertoire `filesDir/logs/` :

| Fichier | Rôle |
|---|---|
| `current.jsonl` | journal du lancement courant (et des précédents non archivés) |
| `archive-1.jsonl` | archive la plus récente |
| `archive-2..5.jsonl` | archives plus anciennes (rotation par décalage) |

Une entrée par ligne, JSON compact (`LogEntry`) :

```json
{"timestampMillis":1767225600000,"sessionId":"0b6a…","level":"INFO",
 "tag":"Session","threadName":"main","message":"CodeIDE 0.3.0 (300, debug) — …"}
```

Bornes (section 5.7) : fichier courant 1 Mio, 5 archives, rétention 7 jours
(soit ~6 Mio au total) ; message tronqué à 4 Kio ; exception aplatie à 50
trames et 10 causes ; tampon mémoire de 200 entrées ; canal d'écriture de
512 entrées.

## 3. Cycle d'écriture

1. `LoggingInitializer.initialize()` est appelé par `app` **dans le
   processus principal uniquement** (détection du nom de processus ;
   l'écriture disque est close dans tout autre processus — anti-corruption).
2. L'en-tête de session est la première entrée : version, code de version,
   type de build, résumé d'appareil, niveau actif.
3. Chaque appel à `AppLogger` offre l'entrée au canal borné : retour
   immédiat, jamais de suspension, jamais de blocage (StrictMode propre).
4. Le consommateur unique écrit par lots : au plus une écriture toutes les
   500 ms ; **immédiate** dès qu'une entrée `ERROR` passe ; balayage de la
   rétention au démarrage et à chaque rotation.
5. `flushBlocking(timeoutMs)` : vidage bloquant borné (verrou de
   coordination, pas de `runBlocking`) — réservé au gestionnaire de
   plantages (étape 3) et aux tests.

## 4. Niveau à l'exécution

| Contexte | Niveau minimal | Logcat |
|---|---|---|
| build debug | `DEBUG` | `DEBUG+` |
| build release | `INFO` | `WARN+` |

À partir de l'étape 4, `AppSettings.logLevel` (`NORMAL` = INFO,
`DETAILED` = DEBUG) mettra à jour la configuration à chaud via
`CodeIdeAppLogger.updateConfig` — un unique point de bascule.

## 5. Expurgation (règle 15)

`LogRedactor` (fonctions pures, `core:domain`) s'applique **à l'écriture** —
ce qui est sur disque est déjà propre — aux messages comme aux messages
d'exception :

| Motif | Remplacement |
|---|---|
| adresse e-mail | `<courriel>` |
| `content://autorite/…` | `content://autorite/h-XXXXXXXX` (autorité conservée, identifiant haché SHA-256 sur 8 hex) |
| `file://…` | `file://<chemin>` |
| chemin absolu (`/storage/…`, `/data/…`) | `<chemin>` |

L'expurgation est idempotente (une URI déjà hachée est reconnue et
conservée) ; les URI http(s) et chemins relatifs ne sont pas touchés.
Limite connue : un chemin contenant des espaces n'est masqué que jusqu'au
premier espace.

**Convention de contenu** : on journalise des identifiants (id de projet,
id de template), jamais un nom de projet, un chemin, un nom d'auteur ni un
contenu de fichier — l'expurgation est un filet, pas une licence.

## 6. Export

`ExportLogsUseCase` produit `codeide-logs-<date>.zip` (date UTC) dans
`cacheDir/exports/` :

- `logs.jsonl` : toutes les entrées persistées ;
- `device-info.txt` : version, build, résumé d'appareil, sessions, volume ;
- (étape 3) résumés des rapports de plantage récents.

Nettoyage automatique : seules les 5 archives les plus récentes sont
conservées. Le partage passe par `FileProvider`
(`${applicationId}.fileprovider`, limité à `cache/exports/` — voir
`res/xml/file_paths.xml`) ; l'enregistrement via `ACTION_CREATE_DOCUMENT`
viendra avec l'écran de diagnostics (étape 12).

## 7. Détekt

La règle `ForbiddenMethodCall` interdit `android.util.Log`, `println` et
`printStackTrace` partout **sauf** dans `core:logging` et `core:crash`
(exemption `config/detekt/detekt-journalisation-autorisee.yml`). Toute
journalisation passe par `AppLogger` — le build échoue sinon.

## 8. Tests

Couverts par les tests unitaires (JVM et Robolectric) : rotation et
plafonnement, rétention, lignes corrompues comptées, groupement des
écritures (temps virtuel), écriture immédiate sur ERROR, vidage bloquant,
non-blocage de l'appelant, expurgation (URI, chemins, courriels,
idempotence), intégration bout-en-bout depuis `CodeIdeApplication`, stress
multi-threads (intégrité et FIFO par producteur — voir ADR 0009 sur le
taux de livraison).

Les procédures à dérouler **sur appareil** sont dans
`docs/TESTS_MANUELS.md`.

## 9. Gestion des plantages (étape 3 — à venir)

`CrashHandler` en première ligne d'`Application.onCreate`,
`CrashActivity` en processus `:crash`, rapports JSON dans
`filesDir/crashes/`, boucle de plantages, lecture d'`ApplicationExitInfo` :
décrit par la section 5.8 du prompt maître. Le présent document sera
complété à ce moment.
