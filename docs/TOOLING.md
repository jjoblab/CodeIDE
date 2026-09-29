# Tooling Gradle (client-serveur)

Référence du prompt compagnon « Tooling Gradle (client-serveur) » v1.0 —
addendum des prompts maître, EditorActivity et Terminal. Ce document
trace les versions **vérifiées** (exigence §8 : jamais mémorisées) et
l'avancement des étapes G1-G6 (G8 : affichage des tâches, v3).

## Versions vérifiées (2026-09-24, jour de G1 — protocole v3 le 2026-09-27)

| Composant | Version retenue | Vérification |
|---|---|---|
| Protocole client ↔ orchestrateur | **v4** (v0.38.0) | `GradleProtocol.PROTOCOL_VERSION` — égalité EXACTE exigée au handshake ; la v4 remplace les phases de sync par les phases RÉELLES (OUTILS/DISTRIBUTION/DAEMON/CONFIGURATION/MODELE_TACHES/MODELE_IDE/DEPENDANCES/CLASSPATHS — l'ancienne CONNEXION mentait : `connect()` ne télécharge rien), enrichit `SyncProgress` de détails (octets reçus/total, élément, compteur — défauts compatibles), fait porter `DetailTelechargement` par `ProgressEvent` (téléchargements visibles pour TOUTE action, §6) et embarque les arguments dans `SyncRequest`/`ClasspathRequest`. Fichiers dorés régénérés (28) via `RegenerateurDoresTest` (`REGENERER_DORES=1`). |
| `org.gradle:gradle-tooling-api` | **9.7.1** | `repo.gradle.org/gradle/libs-releases` — dernière stable (9.8.0 encore en RC), **exactement alignée** sur le Gradle du wrapper du projet (9.7.1). Attention : les métadonnées Maven Central de cette coordonnée sont périmées (dernière « release » affichée : 7.3-snapshot de 2021) — le dépôt de référence est celui de Gradle. |
| Dépôt à ajouter (G2) | `https://repo.gradle.org/gradle/libs-releases/` | `dependencyResolutionManagement` de `settings.gradle.kts` — Maven Central ne suffit pas. |
| JDK minimal du **daemon Gradle réel** | **Java 17** (Gradle 9.x) | Le bootstrap installe `openjdk-17` : compatible sans changement. L'orchestrateur (`tooling:server`) sera compilé jvmTarget 17. |
| Plugin JAR unique (fat jar, G2) | **`com.gradleup.shadow` 9.6.1** | Successeur communautaire maintenu de `com.github.johnrengelman.shadow` (fin de vie), vérifié sur le portail de plugins Gradle. |
| `kotlinx-serialization-json` | **1.9.0** | Catalogue du projet (compagnon du Kotlin 2.2.10) — utilisée par `tooling:protocol` dès G1. |
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

## Architecture livrée (fin G6 — affichage des tâches en G8/v3)

```
┌──── App Android (processus principal) ─────────────────────────────┐
│  feature:editor (GradleService, panneaux Sortie/Problèmes,         │
│      lignes de TÂCHE mises à jour en place, étapes de SYNC,        │
│      écran de configuration du tooling — engrenage de l'onglet)    │
│      │ use cases core:domain (Synchroniser/Exécuter/Annuler/Lister) │
│      ▼                                                              │
│  tooling:client — GradleApiImpl (façade, promesses, canaux 4096,    │
│      observeTachesBuild / observeSyncProgress — v3)                 │
│      ▲ événements pompés        GradleSocketServer (ÉCOUTE, §5.1)  │
│  tooling:daemon — DaemonManager (déploie, lance, surveille, relance)│
│      │ java -Xmx256m -jar gradle-server.jar (secret frais)          │
└──────┼──────────────────────────────────────────────────────────────┘
       ▼ socket Unix (namespace fichier, répertoire privé 0700)
┌──── Orchestrateur (sous-processus JVM, tooling:server) ────────────┐
│  ServerMain → Handshake (secret + version v3) → MessageDispatcher   │
│  BuildHandler (ParseurDiagnostics sur stderr, --console=plain) ·    │
│  SyncHandler (phases SyncProgress) · TasksHandler · ModelHandler ·  │
│  HeapMonitor · EventBusSocket · ProgressBridge (durée + skipped)    │
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


## v4 — tooling professionnel : phases réelles, action unique, téléchargements visibles (2026-09-29)

**API Tooling 9.7.1 vérifiée par `javap` AVANT tout code (règle 9)** — quatre
suppositions du prompt corrigées par les faits :

| Supposition | Vérification sur le JAR |
|---|---|
| `events.file.FileDownload*` | FAUX — le paquet est `org.gradle.tooling.events.download.*` |
| `OperationType.GENERIC_PROGRESS` | FAUX — la valeur est `GENERIC` |
| octets de téléchargement en continu | FAUX — `FileDownloadResult.getBytesDownloaded()` n'existe qu'à la FIN ; le descripteur ne porte que l'URI |
| `setStreamedValueListener` chaînable | FAUX — retourne `void` (le compilateur le liait à `kotlin.run` : diagnostic par fichier témoin) |

**Le déroulé ne ment plus** (§3.1 ; v5 : la distribution en cache ne se
déroule PAS) : `SyncHandler` annonce OUTILS
(vérifications locales : dossier, wrapper, distribution en cache par le
marqueur `wrapper/dists/<nom>/<hash>/*.zip.ok` — layout vérifié sur un
`GRADLE_USER_HOME` réel) → DISTRIBUTION (sa PROPRE phase : installée =
SAUTÉE « en cache » v5, aucun travail annoncé ; à résoudre = ouverte puis
sondée toutes les 500 ms par la taille des
fichiers `.part` — la Tooling API ne donne AUCUN octet pour la
distribution, le sondeur est la seule vérité) → DAEMON (conclu au premier
événement `PROJECT_CONFIGURATION`) → CONFIGURATION / DEPENDANCES
(OPPORTUNISTES et honnêtes : ouvertes seulement si Gradle émet — une sync
en cache ne reconfigure pas, une sync hors ligne ne télécharge pas) →
MODELE_TACHES puis MODELE_IDE dans l'action UNIQUE → CLASSPATHS publiée
AVANT le `SyncResult` (« Synchronisé » ne s'affiche qu'après).

**L'action unique** (`ActionSyncModeles`) : une SEULE requête résout
`GradleProject` puis `IdeaProject` (`BuildAction`/`BuildController`) —
l'ancienne double suite de `model().get()` configurait le build DEUX fois.
Les transitions de phases streament par `BuildController.send()` vers le
`StreamedValueListener` (vérifié : le marqueur est un `MarqueurPhaseModele`
java-sérialisable, jamais un lambda — l'action s'exécute DANS le daemon).
Le résultat (DTO sérialisables, `serialVersionUID`) alimente le **CacheSync**
serveur : `taches()` et `classpath()` répondent ensuite SANS re-résolution
(mesuré : quelques ms contre plusieurs secondes).

**Les téléchargements se voient pour TOUTE action** (§6) :
`EcouteurProgressionCommun` (FILE_DOWNLOAD + PROJECT_CONFIGURATION, débit
borné à 5 événements/s par élément, nom d'artefact = dernier segment d'URI,
règle 15) alimente la sync (phases DEPENDANCES/CONFIGURATION) ET le build
(`ProgressEvent` structuré, canal `observeTelechargementsBuild` côté client).
L'écouteur HISTORIQUE de statut (`org.gradle.tooling.ProgressListener.statusChanged`)
forward les descriptions textuelles de la distribution, borné pareil.

**La sync reste vivante tant qu'elle progresse** (§3.1) : le client n'attend
plus 5 minutes en TOTAL mais 90 s SANS ÉVÉNEMENT (`echangerAvecInactivite`
— la fenêtre se réarme à chaque `SyncStarted`/`SyncProgress`) ; seul le
silence tue, un réseau mobile lent qui télécharge n'est plus un échec.

**Les arguments réglés s'appliquent à la sync et au classpath** : la chaîne
`AppSettings` → `OptionsTooling.argumentsBuild()` → use cases →
`SyncRequest`/`ClasspathRequest` → `withArguments` est complète (l'UI de
configuration enrichie §7 reste à livrer — voir ROADMAP).

**Délais de garde v4** : client sync = 90 s d'INACTIVITÉ (réarmable) ;
les autres délais inchangés (table §7.5 ci-dessus).

### v4 — UI (0.39.0, étape 5 du prompt §3.3 — ADR 0070)

L'UI « raconte » le déroulé v4 en continu (critère §5 : aucune période
muette > 2 s) : l'en-tête enrichi porte la pastille de canal (spinner en
vol, coche/croix de verdict), le titre numéroté « étape n/8 », le
sous-titre d'étape + détail (TalkBack liveRegion) et la progression
DÉTERMINÉE octets recus/total ; la console devient un ARBRE APLATI de
rangées typées (`RangeeConsole`) filtrable par chips Sync/Build exclusives
(vue Sync = 8 phases avec marqueurs ✓/spinner/○ — les non-annoncées
restent visibles, durée MESURÉE seulement ; vue Build = tâches + synthèse ;
aucune chip = chronologie) ; le bandeau d'échec porte « Voir les
problèmes » et « Réessayer » ; la configuration vit DANS le conteneur de
la console (plus de dialogue plein écran, retour système LIFO) ; la
feuille des tâches (BottomSheet M3 : recherche, récentes, groupes) est
alimentée par le CACHE de la sync — `ouvrirSelecteurTaches` répond depuis
`tachesDisponibles` sans aller-retour, l'échec de listage remonte par
effet (snackbar + « Réessayer »). Constructeurs de rangées PURS testés
(`construireRangeesConsole`, `construireRangeesTaches`).

### v5 — UI de l'APERÇU (0.40.0 — retour utilisateur, ADR 0071)

La console correspond à l'aperçu interactif du prompt : **deux écrans
EXCLUSIFS** (chips Sync/Build dans un `ChipGroup` à sélection unique
EXIGÉE, Sync par défaut — la chronologie brute n'est plus un écran, la vue
Sync ne mélange plus de sorties brutes, la vue Build ne montre QUE les
tâches et leur synthèse) ; **le plan d'affichage compte 7 étapes**
(`EtapeConsoleSync`, UI seulement — les 8 phases du câble restent
réelles) : « Dépendances et modèle IDE » fusionne MODELE_IDE et
DEPENDANCES (état consolidé, durée cumulée, plus de rangée « ○ à vie »
sur une sync sans téléchargement), le compteur d'en-tête suit le plan
affiché (« étape n/7 ») ; **la distribution en cache est SAUTÉE**
(`SyncProgress.sautee`, protocole v5) : point gris plein, libellé
atténué, « En cache » à la place de la durée — le téléchargement (barre,
octets, artefact) n'apparaît que si elle MANQUE ; **pied de conclusion**
(« Synchronisation terminée… » ou « Projet à jour, rien à télécharger… »
quand aucun octet n'a été reçu) et sous-titre de succès « N modules ·
N tâches · aucun téléchargement / classpaths prêts ». Libellés au
nominatif — le marqueur porte l'état.

## Avertissement bénin du daemon Gradle (correctif C5 — comportement CONNU, pas un bug)

Pendant l'exécution de tâches Gradle, cette ligne peut apparaître sur
stderr (elle rejoint alors la console du build) :

```
Unable to set daemon's environment variables to match the client because:
There is no native integration with this operating environment.
```

**C'est un diagnostic de Gradle lui-même, littéral** : sa bibliothèque
`native-platform` n'a pas de binding compilé pour cette combinaison
OS/architecture (Android, libc bionic). Gradle continue avec
l'environnement du daemon **tel qu'il était à son premier démarrage** pour
un `GRADLE_USER_HOME` donné, au lieu de le resynchroniser sur le client —
le build n'échoue pas à cause de ça (comportement connu et documenté sur
les plateformes non standard, pas spécifique à CodeIDE).

**Pourquoi c'est sans conséquence ici** : l'app fournit l'environnement
COMPLET et canonique (`ProcessEnvironmentProvider.baseEnvironment()` :
`HOME`, `PREFIX`, `PATH`, `JAVA_HOME`, `ANDROID_HOME`,
`GRADLE_USER_HOME`…) dès le **tout premier** lancement du process
orchestrateur — `LanceurProcessusNatifs.launch` repart de cet environnement
à CHAQUE lancement, sans jamais hériter du processus de l'app (couvert par
`LanceurProcessusNatifsTest`). Or c'est précisément au premier démarrage
du daemon Gradle que l'environnement compte, puisqu'il ne sera plus
resynchronisé ensuite : la garantie est déjà en place, l'avertissement ne
signale que l'absence de resynchronisation ULTÉRIEURE — sans objet ici.

**Affichage** (v0.36.0) : la console du panneau Sortie détecte ce préfixe
exact et rend la ligne en style INFORMATIF (couleur de sortie standard) au
lieu du rouge d'erreur de stderr — l'utilisateur n'est pas alarmé pour un
diagnostic bénin (`GradleService`, constante
`AVERTISSEMENT_DAEMON_BENIN`, testé dans `GradleServiceTest`).

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
| G1 | `tooling:protocol` + `tooling:testing` | 0.26.0 | **Terminé** | Framing (garde DoS 16 Mo, troncature typée, EOF propre distinguée), catalogue des 24 messages (25 depuis l'étape 32 : `SyncStarted`), `ProtocolJson` (`ignoreUnknownKeys`), constantes ; **24 fichiers dorés** figeant le format câble (tout renommage/retrait de champ fait échouer le test d'adéquation) ; 4 fixtures Gradle réelles (minimal, erreur de compilation, multi-module, tâche longue annulable) copiées en temporaire, jamais construites en place. Tests bloquants au vert avant toute ligne server/client (§3) : 8 round-trip, 10 framing, 3 fixtures. ADR 0039. |
| G2 | `tooling:server` (JVM) | 0.27.0 | **Terminé** | `tooling:api` créé (modèles partagés + mappers protocol → api, frontière unique). Orchestrateur : `ServerMain`/`ServerConfig`/`SocketClient` (UDS JDK 16+, `runInterruptible`), `Handshake` (secret + version, `PROTOCOL_VERSION_MISMATCH` clair), `MessageDispatcher` (portée bornée `limitedParallelism(6)`, EOF ≠ corruption), `EventBusSocket` (file 8192, `put()` bloquant — aucune perte, unique écrivain), `BuildHandler` (`suspendCancellableCoroutine` + `CancellationTokenSource`, `StreamingOutputStream` UTF-8, `ProgressBridge`), `SyncHandler` Resilient (`PartialSyncResult`), `TasksHandler` (arbre), `DependenciesHandler` (inter-projets), `ModelHandler` (`IdeaProject` → `ModeleProjet`, répond `SyncResult` — ADR 0040), `HeapMonitor`, timeouts §7.5. Fat jar `com.gradleup.shadow` 9.6.1 (package historique `com.github.jengelman` conservé par le fork — leçon) → `gradle-server.jar` 7,6 Mo dans les assets, contrôlé par `preBuild` (§4.7). Tests : 8 unitaires + **15 d'intégration RÉELS** sur vrai socket Unix contre les 4 fixtures (annulation d'une tâche de 60 s en ~2,6 s), kover ≥ 80 %. `repo.gradle.org` ajouté (Maven Central périmé pour cette coordonnée). |
| G3 | `tooling:client` | 0.28.0 | **Terminé** | L'app EST le serveur du socket : `GradleSocketServer` (namespace FICHIER via `LocalSocket.bind` + `LocalServerSocket(FileDescriptor)` — le client JDK 17 ne joint que des chemins de fichiers ; répertoire privé `0700`, résidu retiré, §5.1), `HandshakeApp` (§4.4 : secret/version validés AVANT tout handler, refus = `ErrorResponse` typée puis fermeture), `SessionTooling` (couture §7.3) + `SessionSocketAndroid`, `GradleApiImpl` (façade §5.3 : corrélation par promesses, **canaux bornés 4096 par build à envoi suspendant** — tampon pré-abonnement, rejouable après fin, §5.2 ; états `StateFlow`), port `GradleToolingRepository` dans core:domain (zéro type tooling, règle §2.2), `AppError.Tooling` typé. Écho d'identifiant corrigé côté serveur (corrélation §3.2 — défaut G2 découvert par le client). 19 tests (non-conflation 12 000 lignes §7.3). ADR 0041. |
| G4 | `tooling:daemon` | 0.29.0 | **Terminé** | `DaemonManager` (cycle de vie complet : déploiement → écoute AVANT lancement §5.1 → lancement via le port `NativeProcessLauncher` jamais redéfini → handshake au secret frais → surveillance → relance bornée 5 tentatives), `JarDeployer` à marqueur SHA-256 (copie atomique, recopie seulement au changement), health check ping/pong 5 s/15 s (repère `dernierPongMs` tenu par le pompe du client), stderr/stdout du process → `AppLogger` tag `gradle-server`, JDK absent = `DECONNECTEE` sans boucle, échecs définitifs typés (handshake refusé, code 2, JAR absent), démarrage au processus principal + re-déclenchement à l'installation du bootstrap, `java -Xmx256m -jar`. **Bout-en-bout réel §7.4** : VRAI sous-processus `java` (ServerMain par classpath), VRAI socket Unix JDK, VRAI build Gradle sur fixture. 13 tests (8 manager sur fakes, 4 deployeur, 1 bout-en-bout). `tooling:server` en test uniquement depuis le daemon (exception ModuleRules documentée). ADR 0042. |
| G5 | `GradleService` + intégration éditeur | 0.30.0 | **Terminé** | Producteur serveur : `ParseurDiagnostics` extrait les positions des lignes stderr (javac `f:l[:c]: error:` / kotlinc `e: file://f:l:c`), publiées en événements `Diagnostic` (observateur de `StreamingOutputStream`, branché par `BuildHandler` — une ligne sans position complète est ignorée). Domaine : use cases Synchroniser/Exécuter/Annuler/Lister (port existant, dossier résolu par l'appelant). Éditeur : `GradleService` (détenteur d'état pur, fenêtre de sortie bornée 2 000 lignes — la sortie complète reste dans le canal rejouable du client), onglets Sortie (auto-défilement, annulation) et Problèmes (groupes par fichier, saut à la ligne), diagnostics inline `session.setDiagnostics` (suffixe de chemin relatif), actions toolbar + sélecteur de tâches. 23 tests + intégration serveur étendue (16 — diagnostics émis sur la fixture d'erreur de compilation). ADR 0043. |
| G6 | Robustesse et audit | 0.31.0 | **Terminé** | **Chaos réel §7.5** (`ChaosToolingTest` : process `kill -9` en plein build → builds EN COURS conclus `ECHOUE` « connexion perdue » + canaux fermés, correctif `GradleApiImpl.rompreBuildsEnCours()` ; socket perdu côté app → le process sort SEUL code 0, aucun orphelin ; le daemon relance borné et la connexion remonte) ; version incompatible et JDK introuvable déjà prouvés (`HandshakeAppTest`, `DaemonManagerTest`) ; délais de garde vérifiés partout (table dédiée ci-dessus : client sync 5 min/tâches 30 s, serveur build 30 min/sync 5 min/tâches 30 s/dépendances 30 s/modèle 5 min, health check 5 s/15 s) ; `docs/TOOLING.md` **final** (architecture livrée, délais, chaos, journalisation, CI) ; audit sans TODO ni code mort (detekt strict vert) ; les points T7 exigeant l'appareil (ADR targetSdk, revue mémoire LeakCanary) restent explicitement différés à l'appareil réel. ADR 0044. |
| G7 | Tooling professionnel à la Android Studio | 0.33.0 | **Terminé** | Sync à l'ouverture du projet (sans geste, garde JDK d'abord — résolution `GradleProject` + `IdeaProject` : dépendances et classpaths), `SyncStarted` diffusé PAR le serveur avant la résolution (symétrique du `BuildStarted`, marquage client idempotent, perte de session = état au repos), canal Taches (indicateur de vol du listage), `GradleService` process-wide (`@Singleton`, `attacher` par espace, `rattacherBuildEnVol`), service de notification `ToolingService` (foreground `specialUse`, port `DemarreurServiceTooling` piloté aux transitions, décision pure `decisionNotificationTooling`, stopSelf au repos) ; vérification légère complète + situation réelle par harnais contre le VRAI jar. ADR 0057. |
| G8 | Affichage des tâches, étapes de sync, configuration | 0.36.0 | **Terminé** |
| G9 | Tooling professionnel v4 : phases réelles, action unique, téléchargements visibles, inactivité, cache | 0.38.0 | **Terminé** (l'UI complète §3.3 livrée en G10/0.39.0 ; l'écran de config enrichi §7 reste différé) | **Le trou est réparé** : `TaskStarted`/`TaskFinished` ne sont plus jetés par `GradleApiImpl.pomper` — `observeTachesBuild` (canal borné par build, fermé à la fin, même sémantique que la sortie) alimente la console : une ligne par tâche (`> Tâche :app:xxx…`), mise à jour EN PLACE à sa fin (statut + durée MESURÉE côté serveur, sautée grisée, échec rouge — vue Build d'Android Studio). **Fin de la boîte noire de sync** : protocole v3, `SyncProgress` par phase (`CONNEXION`/`MODELE_GRADLE`/`MODELE_IDEA`, départ puis durée — la connexion est hoistée avant les modèles, sa phase la plus longue est enfin visible), lignes du canal Sync conclues en place. **`--console=plain` forcé** en dernier argument de tout build (l'occurrence finale gagne). **Écran de configuration du tooling** (engrenage de l'onglet Sortie, dialogue plein écran `Theme.CodeIDE.PleinEcran`) : affichage des tâches (filtrage en vol), mode hors ligne, arguments Gradle libres, état vivant de l'orchestrateur — réglages persistés à l'instant (DataStore, ADR 0059), consommation via `OptionsTooling`. L'avertissement bénin du daemon Gradle voyage apaisé (style informatif, correctif C5 du prompt Terminal). Fichiers dorés v3 (28). ADR 0065. |
| G10 | Tooling professionnel v4 — UI complète (§3.3) : en-tête enrichi, arbre de console avec chips, configuration intégrée, feuille des tâches | 0.39.0 | **Terminé** (§7 enrichi différé, documenté au CHANGELOG) | En-tête enrichi (pastille de canal avec spinner/coche/croix, titre « étape n/8 », sous-titre d'étape + détail annoncé à TalkBack, progression déterminée) ; console en ARBRE APLATI filtrable (chips Sync/Build exclusives, 8 phases toujours visibles, détail de téléchargement indenté, synthèse de build, bandeau d'échec avec actions) ; configuration intégrée AU CONTENEUR de la console (retour système LIFO, bouton libellé) ; feuille des tâches M3 (recherche, récentes, groupes) alimentée par le CACHE sans aller-retour, échec de listage affiché + « Réessayer » (correctif n°6). Constructeurs de rangées purs testés. ADR 0070. |
| G11 | v5 (aperçu) — correspondance visuelle demandée par l'utilisateur : fin des anciens écrans de console, distribution « en cache » sautée, plan d'affichage à 7 étapes | 0.40.0 | **Terminé** (« Daemon réutilisé » non détectable honnêtement, téléchargements dans la vue Build et config §7 toujours différés — CHANGELOG) | Les deux écrans deviennent EXCLUSIFS (ChipGroup `selectionRequired`, Sync par défaut) : plus de chronologie brute ni de sorties brutes mélangées (vue Build = tâches seules, rangée sans étiquette de canal). Protocole v5 : `SyncProgress.sautee` — la distribution installée se publie SAUTÉE (durée 0, « En cache », point gris `?attr/colorOutline`) au lieu d'un « ✓ 0 s » mensonger. `EtapeConsoleSync` : plan d'affichage à 7 étapes (« Dépendances et modèle IDE » fusionnée, durée cumulée, compteur conservé), compteur « étape n/7 ». Pied de sync (« Synchronisation terminée… » / « Projet à jour, rien à télécharger… ») et sous-titre de succès « modules · tâches · … ». Dorés régénérés (28). ADR 0071. |
| G12 | v6 (prompt de suivi) — étapes dynamiques, sync suivante immédiate, chip d'action unique, stats classpath, correctif progress circulaire | 0.41.0 | **Terminé** | Phase 0 : 10 scénarios Gradle réels + RAPPORT.md (signaux Tooling API observés, 3 hypothèses invalidées). Correctif progress circulaire (ADR 0072) : `AnneauTournant` (drawable vectoriel 16 dp / trait 2 dp + `ObjectAnimator` global UNIQUE partagé via `AnneauTournantState` — zéro fuite d'animateur) + `DiffUtil.getChangePayload` granulaire + `supportsChangeAnimations = false`. Suppression concept « sautée / En cache » (ADR 0073) : `SyncProgress.sautee` supprimé, la distribution en cache n'est plus émise du tout par le serveur — une étape non concernée n'existe pas dans la liste. Sync suivante immédiate : `.codeide/local/sync-state.json` (empreinte SHA-256 + tâches + durées + stats) → au retour d'un projet sans changement, l'UI affiche immédiatement « Synchronisé · il y a X » + les tâches, revalidation silencieuse en arrière-plan. Chip d'action UNIQUE non cliquable (refonte filtreConsole). Stats classpath par module (10 champs optionnels dans `ClasspathModule` / `ModuleClasspath`) + sous-lignes par module sous l'étape CLASSPATHS + récapitulatif au pied de sync. Aperçu v3 versionné `docs/preview/apercu-tooling.html`. ADR 0072, 0073. |

> Ordre révisé le 2026-09-24 à la demande de l'utilisateur : le tooling
> démarre après T6 (le prompt exigeait « Terminal terminé » ; T7 est un
> audit finitions dont les points ouverts exigent l'appareil — ils sont
> absorbés par G6). Les anciennes étapes 26-29 du plan générique
> (diagnostics, exécution, LSP, formatage) : diagnostics/Sortie couverts
> par G5, exécution par G2-G4, LSP et formatage gardent leurs prompts
> compagnons dédiés après le tooling Gradle.
