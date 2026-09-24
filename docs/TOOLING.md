# Tooling Gradle (client-serveur)

Référence du prompt compagnon « Tooling Gradle (client-serveur) » v1.0 —
addendum des prompts maître, EditorActivity et Terminal. Ce document
trace les versions **vérifiées** (exigence §8 : jamais mémorisées) et
l'avancement des étapes G1-G6.

## Versions vérifiées (2026-09-24, jour de G1)

| Composant | Version retenue | Vérification |
|---|---|---|
| `org.gradle:gradle-tooling-api` | **9.7.1** | `repo.gradle.org/gradle/libs-releases` — dernière stable (9.8.0 encore en RC), **exactement alignée** sur le Gradle du wrapper du projet (9.7.1). Attention : les métadonnées Maven Central de cette coordonnée sont périmées (dernière « release » affichée : 7.3-snapshot de 2021) — le dépôt de référence est celui de Gradle. |
| Dépôt à ajouter (G2) | `https://repo.gradle.org/gradle/libs-releases/` | `dependencyResolutionManagement` de `settings.gradle.kts` — Maven Central ne suffit pas. |
| JDK minimal du **daemon Gradle réel** | **Java 17** (Gradle 9.x) | Le bootstrap installe `openjdk-17` : compatible sans changement. L'orchestrateur (`tooling:server`) sera compilé jvmTarget 17. |
| Plugin JAR unique (fat jar, G2) | **`com.gradleup.shadow` 9.6.1** | Successeur communautaire maintenu de `com.github.johnrengelman.shadow` (fin de vie), vérifié sur le portail de plugins Gradle. |
| `kotlinx-serialization-json` | **1.9.0** | Cataloge du projet (compagnon du Kotlin 2.2.10) — utilisée par `tooling:protocol` dès G1. |
| `kotlinx-coroutines-core` (JVM) | celle du catalogue | Variante **JVM** pour `tooling:server` (§4.7) — pas la variante Android. |

## Décisions d'architecture (reprises du prompt, §1 — validées par
l'expérience de la version antérieure)

- L'app Android est le **serveur** du socket, le process JVM le
  **client** — écoute ouverte *avant* le lancement du process, aucun
  fichier de découverte, aucun polling.
- **Unix Domain Socket** (`LocalSocket`/`LocalServerSocket`), pas TCP ;
  repli TCP `127.0.0.1` documenté mais non retenu.
- **JSON `kotlinx.serialization`** avec discriminant de type natif —
  gRPC/Protobuf écarté (transport Netty-epoll natif par ABI, coût
  disproportionné).
- **Négociation de version au handshake** + secret aléatoire échangé
  en argument de lancement (namespace abstrait Android non protégé par
  permissions — §4.4 du prompt).

## Architecture livrée (fin G6)

```
┌──── App Android (processus principal) ─────────────────────────────┐
│  feature:editor (GradleService, panneaux Sortie/Problèmes)         │
│      │ use cases core:domain (Synchroniser/Exécuter/Annuler/Lister) │
│      ▼                                                              │
│  tooling:client — GradleApiImpl (façade, promesses, canaux 4096)    │
│      ▲ événements pompés        GradleSocketServer (ÉCOUTE, §5.1)  │
│  tooling:daemon — DaemonManager (déploie, lance, surveille, relance)│
│      │ java -Xmx256m -jar gradle-server.jar (secret frais)          │
└──────┼──────────────────────────────────────────────────────────────┘
       ▼ socket Unix (namespace fichier, répertoire privé 0700)
┌──── Orchestrateur (sous-processus JVM, tooling:server) ────────────┐
│  ServerMain → Handshake (secret + version) → MessageDispatcher      │
│  BuildHandler (ParseurDiagnostics sur stderr) · SyncHandler ·       │
│  TasksHandler · ModelHandler · HeapMonitor · EventBusSocket         │
└──────┼──────────────────────────────────────────────────────────────┘
       ▼ Tooling API 9.7.1
   Gradle daemon (processus séparé, JDK 17+ du bootstrap)
```

- **L'app est le serveur du socket, l'orchestrateur le client** : écoute
  ouverte avant lancement (aucun fichier de découverte, aucun polling).
- **L'orchestrateur meurt avec l'app** : la fermeture du socket à la mort
  de l'app se traduit par un EOF côté orchestrateur, qui sort SEUL
  (code 0 — aucun orphelin).
- **Le JAR orchestrateur est un artefact de build** (`preBuild`, jamais
  versionné — ADR 0040) déployé par `JarDeployer` à marqueur SHA-256.

## Délais de garde (§7.5 — « jamais absents »)

| Frontière | Délai | Effet au dépassement |
|---|---|---|
| Client — synchronisation | 5 min | `AppError.Tooling(Timeout)` typé |
| Client — liste des tâches | 30 s | idem |
| Client — connexion du process | 10 s | relance bornée par le daemon |
| Serveur — build complet | 30 min | annulation forcée (jeton Gradle) + `BuildFinished` échoué |
| Serveur — synchronisation | 5 min | `ErrorResponse` TIMEOUT |
| Serveur — tâches / dépendances | 30 s | idem |
| Serveur — modèle de projet | 5 min | idem |
| Daemon — health check | ping 5 s, muet 15 s | kill + relance bornée (5, repli 1 s → 10 s) |

## Comportement au chaos (§7.5 — éprouvé par `ChaosToolingTest`)

| Panne | Garantie | Preuve |
|---|---|---|
| **Process tué en plein build** (`kill -9`) | EOF côté app : les builds EN COURS se concluent `ECHOUE` (« connexion avec l'orchestrateur perdue », correctif G6) et leurs canaux de sortie se ferment — jamais de suspension infinie de l'onglet Sortie ; le daemon détecte la mort (sonde 100 ms) et relance borné ; la connexion remonte avec un secret neuf | `ChaosToolingTest` (réel) |
| **Socket perdu côté app** (app morte) | l'orchestrateur voit l'EOF et sort SEUL, code 0, avant même le health check — aucun orphelin | `ChaosToolingTest` (réel) |
| **Version incompatible** | refus au handshake, AVANT tout handler : `PROTOCOL_VERSION_MISMATCH` clair puis fermeture — échec définitif sans relance | `HandshakeAppTest`, handshake G2 |
| **JDK introuvable** | `DECONNECTEE` sans AUCUN lancement (jamais de boucle de relance vouée à l'échec) ; re-déclenchement quand le bootstrap s'installe | `DaemonManagerTest` |
| **Orchestrateur muet** (vivant mais bloqué) | health check ping/pong : muet 15 s → kill forcé → relance bornée | `DaemonManagerTest` |
| **Épuisement des relances** (5) | `ECHOUEE` — échec définitif jusqu'à un nouveau `demarrer` (jamais de boucle infinie) | `DaemonManagerTest` |

## Journalisation

stderr/stdout du process orchestrateur → `AppLogger` tag **`gradle-server`**
(WARN/INFO) : l'onglet Journal et l'écran Diagnostic voient l'orchestrateur
comme tout producteur applicatif (règle 14/ADR 0040). Aucune donnée
personnelle : les chemins de projets n'y figurent jamais (règle 15 —
journalisation identifiante, `LogRedactor` à l'écriture).

## Ce que la CI garantit (ADR 0037)

Chaîne complète from-scratch à chaque poussée : `spotlessCheck detekt
checkModuleDependencies lintDebug testDebugUnitTest koverVerify
assembleDebug` — kover ≥ 80 % sur les six modules à seuil, bout-en-bout
réel (§7.4) et chaos (§7.5) inclus. La vérification locale graduée
(Vérification-1 §2.6) n'est qu'un raccourci : la CI reste la garantie.

## Étapes (§9 — ordre non négociable)

| # | Étape | Version | État | Contenu |
|---|---|---|---|---|
| G1 | `tooling:protocol` + `tooling:testing` | 0.26.0 | **Terminé** | Framing (garde DoS 16 Mo, troncature typée, EOF propre distinguée), catalogue des 24 messages (requêtes + événements, `ErrorCode` typé), `ProtocolJson` (`ignoreUnknownKeys`), constantes ; **24 fichiers dorés** figeant le format câble (tout renommage/retrait de champ fait échouer le test d'adéquation) ; 4 fixtures Gradle réelles (minimal, erreur de compilation, multi-module, tâche longue annulable) copiées en temporaire, jamais construites en place. Tests bloquants au vert avant toute ligne server/client (§3) : 8 round-trip, 10 framing, 3 fixtures. ADR 0039. |
| G2 | `tooling:server` (JVM) | 0.27.0 | **Terminé** | `tooling:api` créé (modèles partagés + mappers protocol → api, frontière unique). Orchestrateur : `ServerMain`/`ServerConfig`/`SocketClient` (UDS JDK 16+, `runInterruptible`), `Handshake` (secret + version, `PROTOCOL_VERSION_MISMATCH` clair), `MessageDispatcher` (portée bornée `limitedParallelism(6)`, EOF ≠ corruption), `EventBusSocket` (file 8192, `put()` bloquant — aucune perte, unique écrivain), `BuildHandler` (`suspendCancellableCoroutine` + `CancellationTokenSource`, `StreamingOutputStream` UTF-8, `ProgressBridge`), `SyncHandler` Resilient (`PartialSyncResult`), `TasksHandler` (arbre), `DependenciesHandler` (inter-projets), `ModelHandler` (`IdeaProject` → `ModeleProjet`, répond `SyncResult` — ADR 0040), `HeapMonitor`, timeouts §7.5. Fat jar `com.gradleup.shadow` 9.6.1 (package historique `com.github.jengelman` conservé par le fork — leçon) → `gradle-server.jar` 7,6 Mo dans les assets, contrôlé par `preBuild` (§4.7). Tests : 8 unitaires + **15 d'intégration RÉELS** sur vrai socket Unix contre les 4 fixtures (annulation d'une tâche de 60 s en ~2,6 s), kover ≥ 80 %. `repo.gradle.org` ajouté (Maven Central périmé pour cette coordonnée). |
| G3 | `tooling:client` | 0.28.0 | **Terminé** | L'app EST le serveur du socket : `GradleSocketServer` (namespace FICHIER via `LocalSocket.bind` + `LocalServerSocket(FileDescriptor)` — le client JDK 17 ne joint que des chemins de fichiers ; répertoire privé `0700`, résidu retiré, §5.1), `HandshakeApp` (§4.4 : secret/version validés AVANT tout handler, refus = `ErrorResponse` typée puis fermeture), `SessionTooling` (couture §7.3) + `SessionSocketAndroid`, `GradleApiImpl` (façade §5.3 : corrélation par promesses, **canaux bornés 4096 par build à envoi suspendant** — tampon pré-abonnement, rejouable après fin, §5.2 ; états `StateFlow`), port `GradleToolingRepository` dans core:domain (zéro type tooling, règle §2.2), `AppError.Tooling` typé. Écho d'identifiant corrigé côté serveur (corrélation §3.2 — défaut G2 découvert par le client). 19 tests (non-conflation 12 000 lignes §7.3). ADR 0041. |
| G4 | `tooling:daemon` | 0.29.0 | **Terminé** | `DaemonManager` (cycle de vie complet : déploiement → écoute AVANT lancement §5.1 → lancement via le port `NativeProcessLauncher` jamais redéfini → handshake au secret frais → surveillance → relance bornée 5 tentatives), `JarDeployer` à marqueur SHA-256 (copie atomique, recopie seulement au changement), health check ping/pong 5 s/15 s (repère `dernierPongMs` tenu par le pompe du client), stderr/stdout du process → `AppLogger` tag `gradle-server`, JDK absent = `DECONNECTEE` sans boucle, échecs définitifs typés (handshake refusé, code 2, JAR absent), démarrage au processus principal + re-déclenchement à l'installation du bootstrap, `java -Xmx256m -jar`. **Bout-en-bout réel §7.4** : VRAI sous-processus `java` (ServerMain par classpath), VRAI socket Unix JDK, VRAI build Gradle sur fixture. 13 tests (8 manager sur fakes, 4 deployeur, 1 bout-en-bout). `tooling:server` en test uniquement depuis le daemon (exception ModuleRules documentée). ADR 0042. |
| G5 | `GradleService` + intégration éditeur | 0.30.0 | **Terminé** | Producteur serveur : `ParseurDiagnostics` extrait les positions des lignes stderr (javac `f:l[:c]: error:` / kotlinc `e: file://f:l:c`), publiées en événements `Diagnostic` (observateur de `StreamingOutputStream`, branché par `BuildHandler` — une ligne sans position complète est ignorée). Domaine : use cases Synchroniser/Exécuter/Annuler/Lister (port existant, dossier résolu par l'appelant). Éditeur : `GradleService` (détenteur d'état pur, fenêtre de sortie bornée 2 000 lignes — la sortie complète reste dans le canal rejouable du client), onglets Sortie (auto-défilement, annulation) et Problèmes (groupes par fichier, saut à la ligne), diagnostics inline `session.setDiagnostics` (suffixe de chemin relatif), actions toolbar + sélecteur de tâches. 23 tests + intégration serveur étendue (16 — diagnostics émis sur la fixture d'erreur de compilation). ADR 0043. |
| G6 | Robustesse et audit | 0.31.0 | **Terminé** | **Chaos réel §7.5** (`ChaosToolingTest` : process `kill -9` en plein build → builds EN COURS conclus `ECHOUE` « connexion perdue » + canaux fermés, correctif `GradleApiImpl.rompreBuildsEnCours()` ; socket perdu côté app → le process sort SEUL code 0, aucun orphelin ; le daemon relance borné et la connexion remonte) ; version incompatible et JDK introuvable déjà prouvés (`HandshakeAppTest`, `DaemonManagerTest`) ; délais de garde vérifiés partout (table dédiée ci-dessus : client sync 5 min/tâches 30 s, serveur build 30 min/sync 5 min/tâches 30 s/dépendances 30 s/modèle 5 min, health check 5 s/15 s) ; `docs/TOOLING.md` **final** (architecture livrée, délais, chaos, journalisation, CI) ; audit sans TODO ni code mort (detekt strict vert) ; les points T7 exigeant l'appareil (ADR targetSdk, revue mémoire LeakCanary) restent explicitement différés à l'appareil réel. ADR 0044. |

> Ordre révisé le 2026-09-24 à la demande de l'utilisateur : le tooling
> démarre après T6 (le prompt exigeait « Terminal terminé » ; T7 est un
> audit finitions dont les points ouverts exigent l'appareil — ils sont
> absorbés par G6). Les anciennes étapes 26-29 du plan générique
> (diagnostics, exécution, LSP, formatage) : diagnostics/Sortie couverts
> par G5, exécution par G2-G4, LSP et formatage gardent leurs prompts
> compagnons dédiés après le tooling Gradle.
