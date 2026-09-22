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

## 9. Gestion des plantages (étape 3 — livrée)

Texte de référence : section 5.8 du prompt maître ; choix d'implémentation
consignés dans les ADR 0006 (processus séparé) et 0010 (FileProvider du
processus `:crash`).

### 9.1 Vue d'ensemble

```
CodeIdeApplication.onCreate — PREMIÈRE ligne, avant Hilt
   │  AppProcess.detect (API 28+ : getProcessName ; avant : /proc/self/cmdline)
   ├─ MAIN   → CrashHandler.install(app, build, device)  ← chaîné au précédent
   ├─ CRASH  → CrashHandler.installSafe()                 ← délègue, n'écrit rien
   └─ OTHER  → gestionnaire système
   ▼ (après Hilt, processus principal uniquement)
brancherJournalisation(sessionId, breadcrumbs, flush)   ← lambdas, zéro dépendance de module
RecordPendingExitInfosUseCase                            ← hors thread principal
```

Un plantage non géré suit l'enchaînement exigé, **entièrement dans un
`try/catch` global avec garde de ré-entrance** :

1. détection de boucle (≥ 3 plantages en 60 s, historique persistant
   minimal `filesDir/crashes/loop-history.txt`) ;
2. construction du `CrashReport` : exception **expurgée et bornée**
   (100 tranches, 10 causes, supprimées incluses), filons (50 derniers),
   dernier écran, durée du processus, indicateur de boucle ;
3. écriture **synchrone et atomique** (`.tmp` puis renommage), réduction
   progressive jusqu'à 256 Ko maximum ;
4. vidage borné du journal (le reste du budget, plafonné à 500 ms —
   budget total du gestionnaire : 2 s) ;
5. lancement de `CrashActivity` (`NEW_TASK | CLEAR_TASK`), puis
   `killProcess` + `exitProcess(10)` ;
6. délégation au gestionnaire précédent si : boucle détectée, lancement
   impossible, ou échec interne — **jamais** après un lancement réussi.

### 9.2 Rapport et stockage

- `filesDir/crashes/<horodatage>-<id>.json` — le préfixe horodatage rend le
  tri lexicographique chronologique (13 chiffres jusqu'en 2286).
- L'état « consulté » vit dans un **fichier témoin** à côté du rapport :
  un rapport ne se réécrit jamais.
- Réduction progressive à l'écriture : `COMPLET → REDUIT → MAIGRE →
  MINIMAL` (filons, puis tranches, puis messages) — la lecture accepte
  tout fichier valide ; la structure ne change jamais.
- Rétention : 20 rapports maximum (les plus récents), témoins orphelins et
  restes `.tmp` nettoyés à chaque écriture.
- Sérialisation par `org.json` du framework : **aucune dépendance** sur le
  chemin critique d'un plantage.

### 9.3 Détection au démarrage

`ExitInfoRecorder` (API 30+, hors thread principal, dédoublonné par
marqueur d'horodatage) convertit les **ANR** et **plantages natifs** de la
session précédente en rapports reconstruits — session, écran et filons de
la session morte sont perdus, et le rapport le dit au lieu d'inventer.
Si un rapport **non consulté** existe, `MainActivity` affiche la boîte de
dialogue « Un problème est survenu lors de la dernière session » :
**Voir le rapport** et **Ignorer** valent tous deux consultation.

### 9.4 Écran dédié

`CrashActivity` — processus `:crash` (`exported=false`,
`excludeFromRecents`, affinité dédiée), **sans Hilt, sans Room, sans
DataStore** : elle ne construit qu'un `CrashReportFileStore`. Deux modes :
`LIVE` (après un plantage) et `VIEW` (consultation). Actions :
Redémarrer (LIVE seulement, masqué en boucle), Copier, Partager (texte ou
archive zip via le FileProvider dédié — ADR 0010), Enregistrer (SAF),
Fermer — et, en cas de boucle, un conseil explicite plus « Vider le
cache » (jamais les données utilisateur, avec confirmation).

### 9.5 Menu debug (source set `debug` de `app`)

Trois actions de recette : **Provoquer un plantage**, **Exception non
fatale** (journalisée et attrapée — éprouve l'aplatissement dans les
journaux, pas le gestionnaire), **Générer des journaux** (salve de 50
entrées). La version release embarque un no-op de même signature :
`MainActivity` ne connaît pas la variante.

### 9.6 Tests

Couverts par les tests unitaires (JVM et Robolectric) : aller-retour
complet de la sérialisation, réduction, limites de taille (256 Ko),
expurgation à la construction, écriture atomique (aucun reste `.tmp`),
rétention (20 maximum), témoin de consultation, fichiers corrompus
ignorés, détection de boucle (fenêtre, seuil, persistance, corruption),
mapping `ApplicationExitInfo` (Robolectric API 30+, déduplication, autres
processus, trace bornée, garde API 29), chaînage du gestionnaire avec
tueur et lanceur **injectés** (le test ne tue jamais la JVM), délégations
(boucle, échec de lancement, échec interne), garde de ré-entrance,
écran dédié (modes, boucle, repli), intégration bout-en-bout depuis
l'application réelle (installation, dialogue du rapport non consulté).

Les procédures à dérouler **sur appareil** sont dans
`docs/TESTS_MANUELS.md` (section Plantages, P1-P8).
