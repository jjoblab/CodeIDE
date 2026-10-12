# Cycle de vie de l'éditeur — session de projet et fermeture propre (prompt 3)

> **Statut (v0.106.0) : mission EN COURS — étapes A, B, C, D et E
> LIVRÉES**. Étape A : audit documentaire (§4). Étape B : module pur
> `:core:session` + ADR 0114 (§5, v0.103.0). Étape C : branchement
> éditeur (§6, v0.104.0). Étape D : protocole v9 « fermeture de
> projet » + libération PAR PROJET côté orchestrateur (§7, v0.105.0 —
> E9 fermé). Étape E : cohérence de navigation (§8, v0.106.0 — E10/P3/
> P4-dégénéré fermés, badge de session, SAF au retour, kit de baseline
> §4.5). Reste : **F — vérification et validation appareil**, sur les
> mesures du kit `scripts/baseline-cycle-vie.sh` (rejouable sur le TAG
> v0.103.0, état pré-correctif figé — ou sur l'état courant pour le
> suivi).
> Lecture préalable : `AGENTS.md`,
> `docs/ARCHITECTURE.md`, `docs/TOOLING.md`, `feature/editor/Module.md`,
> prompt 3 (`upload/extracted_files/03-prompt-cycle-de-vie-editeur.md`).

## 0. Ce qui est établi (vérifié dans le code v0.102.0)

Le prompt 3 posait six constats (E1–E6) « étayés par une lecture
partielle » ; l'étape A les a re-vérifiés et en ajoute dix (E7–E16),
dont plusieurs issus de la mission « LSP Java » (v0.99.0 → v0.102.0).
Détail, preuves `fichier:ligne` et verdicts complets : §4.7.

| # | Constat | Verdict v0.102.0 |
|---|---|---|
| E1 | `EditorActivity` (2 486 lignes) surcharge `onStart`/`onStop` (surveillance de l'arbre), `onDestroy` (fermeture LSP SEULE — sans garde `isFinishing`/`isChangingConfigurations`), `onSaveInstanceState` (tiroir + rail). Retour système par `OnBackPressedCallback` → `Quitter` → dialogue → `finish()` (seul chemin de sortie explicite, `EditorActivity.kt:2220`). **Aucun `onTrimMemory` dans tout le dépôt** | confirmé, enrichi (`EditorActivity.kt:345,351,1851,2367`) |
| E2 | `EditorViewModel.onCleared()` annule les sauvegardes automatiques **SANS les flusher** — debounce de 1 500 ms par onglet (`EditorViewModel.kt:4077`) : kill du processus pendant le délai ⇒ perte ≤ 1,5 s de frappe par onglet sale. La sortie par le dialogue n'est pas concernée (`EnregistrerPuisFermer` écrit avant de fermer) | confirmé par lecture (`EditorViewModel.kt:3360-3369`) ; test à écrire en étape C |
| E3 | Démon de tooling (`DaemonManager`) démarré dans `CodeIdeApplication.onCreate` (`CodeIdeApplication.kt:222`), durée de vie = processus. `arreter()` appelé UNIQUEMENT au changement d'empreinte d'outils (`:238`). Le process enfant (orchestrateur `java -Xmx256m`) est tué aux cycles de RECONNEXION (`DaemonManager.kt:284-292`), jamais à la fermeture d'un projet | confirmé |
| E4 | `GradleService` `@Singleton` process-wide (état + console, rejeu 2 000 lignes — `GradleService.kt:350-418`), `PompeBuildTooling` `@Singleton` ; `ToolingService` (premier plan) s'auto-arrête quand plus aucune activité tooling n'est observée (`ToolingService.kt:125-153`). MAIS le miroir d'état n'est alimenté que par un collecteur `viewModelScope` de l'éditeur (E13) | confirmé, nuancé |
| E5 | `GitViewModel.demarrerSurveillance()` : boucle de 2 000 ms dans `viewModelScope` (`GitViewModel.kt:106-120`), démarrée/arrêtée par `GitFragment.onStart/onStop` (`GitFragment.kt:102-111`). Sonde SANS processus git (horodatages `.git/HEAD` et `.git/index`). Meurt avec l'éditeur (ViewModel attaché au fragment) | confirmé |
| E6 | `EditorActivity` et `TerminalActivity` SANS `launchMode` (standard) — empilement d'instances possible ; `MainActivity` `singleTop` (ADR 0048). Les notifications des 3 services pointent vers le launch intent SANS projet (E10) | confirmé |
| E7 | **Nouveau (LSP)** — `SessionLsp` (contrat réel : constructeur + `close()` idempotent, `SessionLsp.kt:242-256`) appartient à `EditorViewModel` (« par espace », ouverte au projet+racine FUSE, refermée au changement de projet et dans `onCleared`). **Aucune coordination process-wide** : deux instances d'éditeur ⇒ deux sessions ; **aucun bornage de délai** de fermeture. L'accroche « s'enregistrer » (prompt §5.5) reste à construire | constaté (`EditorViewModel.kt:277-290,1091-1132`) |
| E8 | **Nouveau (LSP)** — la rotation détruit le `LspProject` cel-lsp : `onDestroy` inconditionnel → `CoordonnateurLsp.fermer()` (`EditorActivity.kt:1851-1854`), rebranchement au re-rendu. La `SessionLsp` survit (ViewModel) — coût borné mais reconnexion à chaque rotation | constaté |
| E9 | **Nouveau (tooling)** — `GradleApiImpl.fermerSession()` existe (`GradleApiImpl.kt:433-462`) mais n'est appelée que par le démon (mort du process orchestrateur) et par l'auto-remplacement de session (`:289`). **Aucune voie UI** ; aucun message protocole « fermeture de projet » (v8) | constaté |
| E10 | **Nouveau (navigation)** — les notifications (tooling id 42 002, terminal 42 001, installation) portent un PendingIntent vers le launch intent de `MainActivity`, SANS extra projet (`ToolingService.kt:322-331`) : le retour par notification rouvre l'accueil, jamais l'éditeur/projet | constaté |
| E11 | **Nouveau (terminal)** — sessions du terminal GLOBALES : `RegistreSessionsTermux` `@Singleton` (`RegistreSessionsTermux.kt:109`), un processus shell par session, service premier plan tant qu'une session vit. Elles survivent à la fermeture de l'éditeur ; mort par processus ou geste utilisateur seul | constaté |
| E12 | **Nouveau (fuite latente)** — `remplacerContenuFichier` remplace une session d'édition cel SANS disposer l'ancienne (`EditorViewModel.kt:2986`), contrairement au contrat `SessionSuivie` (`:99-101`, « fuite du thread de restyle sinon, détectée par LeakCanary ») | constaté — à corriger en étape C |
| E13 | **Nouveau (tooling)** — le miroir `GradleService.etat` (connexion, canaux) n'est alimenté que par le collecteur du `viewModelScope` de l'éditeur (`EditorViewModel.kt:474-477`) : après fermeture, l'état process-wide devient figé — `ToolingService` décide sur un état gelé | constaté |
| E14 | `onCleared()` ne purge pas `verrousEcriture` (HashMap de `Mutex` par URI, `EditorViewModel.kt:408`) — mineur, mort avec le ViewModel | constaté |
| E15 | **Infirmé** — « cache LRU de sessions cel-ui » : n'existe PAS. Les sessions d'édition vivent dans un `LinkedHashMap` non borné par onglet (`EditorViewModel.kt:405`) ; elles ne meurent qu'à la fermeture d'onglet ou dans `onCleared` (design ADR 0028 : l'onglet garde SA session). Ni éviction, ni plafond mémoire | infirmé |
| E16 | `onTrimMemory`/`onLowMemory` ABSENTS de tout le dépôt — aucune réaction à la pression mémoire côté éditeur (l'index LSP a son LRU mémoire interne de 30 Mo, `CacheSegments.kt:25`, rien d'autre) | constaté |

**Point d'accroche (prompt §0) réévalué** : la surveillance Git ne
« s'enregistre » auprès de rien (polling fragment-scoped) ; `SessionLsp`
a bien un contrat idempotent (`close()`), mais vit DANS le ViewModel —
le mécanisme d'enregistrement promis au prompt §5.5 (`demarrer/arreter`
bornés) reste à construire à l'étape B.

## 1. Plan du travail restant (étapes A–F du prompt 3, chacune validée par « GO »)

| Étape | Livrable | Contenu |
|---|---|---|
| **A — Audit et cartographie** | **LIVRÉ (présent document, §4)** — livrable écrit, pas de code de production | Table d'inventaire de TOUTES les ressources de fond ; diagrammes de séquence des parcours ; matrice de transitions (une ligne = un test) ; protocole de mesures de référence appareil (exécution par le propriétaire, baseline de l'étape F) ; détection de fuites en debug (déjà en place : StrictMode + LeakCanary 2.14, procédure §4.6) |
| **B — Contrat de session et registre de fermeture** | **LIVRÉ (v0.103.0, §5 du présent document — ADR 0114)** : module Kotlin JVM pur `:core:session` | `SessionProjet` (`ouvrir(projet)`, `fermer(raison)`, états `{Fermee, Ouverture, Ouverte, Fermeture}`), registre de ressources `Fermable` (ordre inverse, délai maximal, drapeau « ne pas annuler ») ; tests unitaires : ordre inverse, idempotence, **panne partielle**, **dépassement de délai**, fermeture pendant l'ouverture, ouverture pendant la fermeture, réentrance, annulation — 33 tests verts. Aucune dépendance Android |
| **C — Intégration éditeur et migration des ressources existantes** | **LIVRÉ (v0.104.0, §6 du présent document)** | `EditorActivity`/`EditorViewModel` : session ouverte au démarrage (I1 par le gestionnaire), flush des sauvegardes NON annulable en sortie (I7/E2, portée application), session LSP `Fermable`, `onTrimMemory` caches reconstructibles (E16), miroir connexion process-wide (E13), fuite E12 corrigée, annonce build en cours (§2.1c) ; 6 nouveaux tests, 338 au total |
| **D — Côté serveur de tooling** | **LIVRÉ (v0.105.0, §7 du présent document)** : protocole v9 | `FermerProjetRequest`/`FermerProjetResult` (idempotents, accusé corrélé) ; `GradleConnectorPool.fermer(dossier)` (connexion PAR projet), `CacheSync.purger`, branchement dispatcher ; port `fermerProjet` (E9 fermé) + Fermable « tooling-projet » (renoncement §2.1c si build/sync en vol) ; politique du démon REPORTÉE aux mesures (S6) |
| **E — Cohérence de navigation** | **LIVRÉ (v0.106.0, §8 du présent document)** | Routage des 3 notifications (E10 : tooling → le PROJET du canal, terminal → l'écran du terminal, installation → l'écran du parcours) ; MainActivity consomme l'extra projet (ouvrir X ferme Y par I1, l'espace de Y se referme de lui-même — P4 dégénéré clos) ; entrée Paramètres dans l'éditeur (P3) ; état « session ouverte » sur l'accueil (port lecture seule) ; permission SAF révoquée/stockage disparu détectés AU RETOUR ; disponibilité du tooling honnête pendant une installation ; kit de baseline §4.5 |
| **F — Vérification et validation appareil** | **OUTILLÉE (v0.107.0, §9 du présent document)** : preuves locales (tests §9.1 — dont le NOUVEAU test « la rotation ne ferme ni ne reouvre la session ») + kit `scripts/validation-f.sh` (verdicts automatiques par critère §8 : 20 cycles par `am start`+extra, GC forcé, meminfo/ps/cmdline, rotations, journaux) ; exécution sur appareil en attente d'un téléphone branché | 20 cycles ouvrir/quitter : croissance du tas < 5 %, aucune instance d'`EditorActivity` retenue (détecteur) ; sortie de l'éditeur ⇒ aucun processus enfant lié au projet au-delà de la politique ; ouvrir P, quitter, ouvrir Q ⇒ aucun vestige de P ; aucune perte de la dernière modification en sortie rapide (test automatisé) ; rotation ne ferme rien ; fermeture bornée en temps même si un élément bloque |

## 2. Décisions à soumettre au propriétaire (avec recommandation)

> **Arbitrage v0.103.0→v0.104.0 (retour propriétaire « GO étapes C et D,
> sans attendre la baseline »)** : les décisions 1, 4, 5, 6 et 7 sont
> TRANCHÉES (recommandations appliquées, réversibles, documentées
> ci-dessous) ; les décisions 2 et 3 restent EXPLICITEMENT reportées aux
> mesures (§4.5) — le protocole reste rejouable sur le tag v0.103.0
> (APK de CI « CodeIDE-v0.103.0-debug ») qui FIGE l'état pré-correctif.

1. **Build en cours à la sortie** : (a) fermeture différée jusqu'à la fin
   du build avec notification ; (b) annulation avec confirmation ; (c) le
   build continue, seule l'interface se ferme. Recommandation initiale du
   prompt : (a) ou (c), jamais d'annulation silencieuse. **Constat
   d'étape A : (c) est le comportement DE FACTO aujourd'hui** — le build
   vit dans le processus (`GradleService`/`PompeBuildTooling` singletons,
   `ToolingService` garde la notification), `finish()` ne l'interrompt
   pas ; il manque seulement l'annonce à l'utilisateur. Recommandation
   affine : valider (c) et l'expliciter (notification « build P en cours,
   interface fermée »).
   **→ TRANCHÉ (c) en v0.104.0** : expliciter = toast
   `editor_sortie_build_continue` AVANT `finish()` quand un build est en
   vol (la notification ToolingService reste le détail visible).
2. **Politique du démon de tooling** : conservé tant qu'une session
   existe / arrêté après X minutes sans session et app en arrière-plan /
   relancé à la demande — trancher sur MESURES (§4.5, scénario S6 :
   mémoire au repos du démon + temps de démarrage), pas sur l'intuition.
   **→ REPORTÉ aux mesures** (S6) : l'étape D livre la libération PAR
   PROJET dans le démon, PAS sa politique de durée de vie — le démon
   reste process-wide jusqu'aux chiffres.
3. **Temporisation en arrière-plan** avant arrêt des services lourds
   (LSP inclus) — aujourd'hui RIEN ne coupe en arrière-plan (S6) ; à
   proposer après les mesures.
   **→ REPORTÉ aux mesures** (S6) ; v0.104.0 livre la part
   INCONDITIONNELLE : `onTrimMemory` (E16) libère les caches
   reconstructibles (arbre) dès RUNNING_CRITICAL — UI_HIDDEN (arrière-
   plan) est couvert par le seuil (20 ≥ 15), la session ne bouge pas.
4. **Terminal lié au projet ou global** — politique voulue. Constat
   d'étape A : global de fait (E11), les sessions survivent à la
   fermeture de l'éditeur ; le tiroir terminal de l'éditeur et
   `TerminalActivity` partagent le même registre.
   **→ TRANCHÉ : GLOBAL conservé** — lier les shells au projet tuerait
   des sessions utilisateur à la fermeture d'un projet (une commande
   longue `top`/`gradle` ne doit pas mourir parce qu'on referme
   l'éditeur) ; le registre global reste le comportement voulu. Révisable
   en étape E si la matrice l'exige.
5. **Rotation : coût cel-lsp** (nouveau, E8) — (a) accepter le
   shutdown/rebranchement du `LspProject` à chaque rotation (simple,
   coût borné : reconnexion, la `SessionLsp` et ses caches survivent) ;
   (b) hisser la coordination cel-lsp hors de l'Activity (ViewModel)
   pour survivre au changement de configuration. Recommandation : (a)
   à court terme, mesurer le coût réel (S2) et réarbitrer.
   **→ TRANCHÉ (a)** en v0.104.0 : I3 ne requiert RIEN de plus — la
   session de projet vit hors de l'écran (le ViewModel survit à la
   rotation), le rebranchement cel-lsp est un coût de RECONNEXION borné.
   Réarbitrage après S2.
6. **I1 et launchMode** (nouveau, E6/E7) — (a) le gestionnaire de
   session garantit I1 à l'ouverture (fermer A puis ouvrir B, séquentiel)
   sans toucher au manifeste ; (b) `launchMode` + routage par extra (motif
   ADR 0048 de `MainActivity`). Recommandation : (a) à l'étape B, (b) à
   l'étape E si les mesures montrent des empilements réels.
   **→ TRANCHÉ (a) — appliqué en v0.103.0/v0.104.0** (gestionnaire
   câblé, I1 testé : « ouvrir B ferme A d'abord et flushe A ») ; (b)
   reste réservée à l'étape E.
7. **Miroir d'état tooling** (nouveau, E13) — déplacer la collecte
   d'état `GradleService.etat` vers une portée process (étape C/D) pour
   que `ToolingService` ne décide plus sur un état gelé après la
   fermeture de l'éditeur.
   **→ IMPLÉMENTÉ en v0.104.0** : le miroir de CONNEXION vit dans
   `PompeBuildTooling.pomperConnexion()` (portée process, idempotente —
   E13 corrigé côté connexion). Arbitrage des diagnostics : ils restent
   collectés par l'espace (consommation UI, `attacher()` les remet à
   zéro à la ré-ouverture — un état gelé de diagnostics est sans effet
   process-wide, contrairement à la connexion).

## 3. Invariants à garantir (contrat de la session)

I1 : au plus UNE session de projet active par processus (ou politique
explicite multi-instance testée) ; I2 : fermeture idempotente, ordonnée,
bornée, tolérante aux pannes partielles ; I3 : un changement de
configuration ne ferme JAMAIS le projet ; I4 : aucune coroutine, tâche
planifiée, écouteur, fichier mappé, poignée ni processus ne survit à son
propriétaire ; I5 : aucune fuite d'Activity/Fragment/View (vérifiée par
outil) ; I6 : aucun processus orphelin au-delà de la politique ; I7 :
aucune perte de données (flush avant libération) ; I8 : réouverture sur
un état propre ; I9 : la cohérence inter-écrans est une propriété
TESTÉE (chaque ligne de la matrice §4.4 a un test).

## 4. Étape A — audit et cartographie (v0.102.0, livrée)

### 4.1 Méthode

Relecture du code de v0.102.0 (commit `e3a6457`) sur trois axes :
éditeur + LSP (Activity, ViewModel, `CoordonnateurLsp`, `SessionLsp`,
sessions cel), tooling (app, client, serveur, démon), Git/terminal/
navigation/paramètres. Chaque constat porte sa preuve `fichier:ligne` ;
les ABSENCES ont été prouvées par recherche textuelle sur l'ensemble du
code source Kotlin (hors `build/`) et sont signalées comme telles.
Les mesures sur appareil n'ont PAS pu être exécutées dans cette session
(aucun appareil) : le protocole §4.5 est écrit pour être exécuté par le
propriétaire AVANT tout correctif — c'est la baseline de comparaison
exigée par le prompt (§6.A.4) et par le critère d'acceptation n° 1 de
l'étape F.

### 4.2 Table d'inventaire des ressources de fond

Colonnes : **P** = portée réelle, **D** = démarrage, **AA** = arrêt
actuel, **AC** = arrêt cible (étapes B–D), **R** = risque constaté.
« Process » = durée de vie du processus applicatif.

| # | Ressource (module) | P | D | AA | AC | R |
|---|---|---|---|---|---|---|
| 1 | `DaemonManager` + process orchestrateur `java -Xmx256m` (tooling:daemon) | `@Singleton` process | `CodeIdeApplication.onCreate:222` | `arreter()` si empreinte d'outils change (`:238`) ; process enfant tué aux reconnexions (`DaemonManager.kt:284-292`) ; JAMAIS à la fermeture de projet | politique mesurée §2.2 (session-conditionnée + temporisation) | ~256 Mo + un process enfant maintenus sans projet ouvert |
| 2 | `GradleConnectorPool` + `ProjectConnection` (tooling:server) | process (orchestrateur) | première sync/build du projet | `close()` global SEULEMENT à l'arrêt du serveur (`ServerMain.kt:67,90`) — **aucune libération par projet** | message protocole « fermeture de projet » (étape D) | connexions (et démons Gradle sous-jacents) de tous les projets ouverts cumulées |
| 3 | Démons Gradle par projet (gérés par Gradle, via #2) | process | Tooling API à la connexion | jamais explicitement (vivent avec la connexion) | libérés par #2 | plusieurs centaines de Mo par projet (risque fonctionnel sans fuite d'objets) |
| 4 | `CacheSync` (tooling:server) | process | première sync aboutie | RIEN — remplacé à chaque sync, **jamais purgé, non borné** (`CacheSync.kt:18-40`) | purge à la fermeture de projet (étape D) | résiduel P visible côté serveur |
| 5 | `EventBus` + `HeapMonitor` (tooling:server) | process | `ServerMain.kt:78-79` | shutdown hook + finally (`:63-70, 87-92`) | idem (correct : lié au serveur, pas au projet) | néant |
| 6 | `GradleService` (feature/editor) — état + console rejeu 2 000 lignes | `@Singleton` process | `attacher()` à l'init de l'`EditorViewModel` (`EditorViewModel.kt:438`) | RIEN — survit (console « repart vierge » au ré-attachement, builds en vol conservés) | état de session ou vidange à la fermeture (étape C) ; miroir d'état §E13 | console + état résiduels, décision `ToolingService` sur état gelé |
| 7 | `GradleApiImpl` (tooling:client) — session socket, canaux, promesses | `@Singleton` process | `ouvrirSession()` (connexion démon) | `fermerSession()` par le démon seul (E9) — **aucune voie UI** | appelée à la fermeture de session (étape C/D) | session ouverte sans consommateur |
| 8 | `PompeBuildTooling` (feature/editor) | `@Singleton` process | lancement d'un build | vidange par build, RIEN à la fermeture (`PompeBuildTooling.kt:91-130`) | conservé si (c) validé §2.1 (build continue) | cohérent avec (c) — à documenter |
| 9 | `ToolingService` (feature/editor) — service 1er plan, notification id 42 002 | service | transition d'activité tooling (`GradleService.kt:875-883`) | AUTO : `stopSelf()` quand plus d'activité observée (`ToolingService.kt:125-153`) | idem + notification « build en cours, interface fermée » (§2.1c) | s'appuie sur le miroir gelé (E13) |
| 10 | `SessionLsp` (lsp:host) — orchestrateur, moteurs, modèle | par ESPACE (ViewModel) | projet + racine FUSE connus (`EditorViewModel.kt:1091-1124`) | `close()` au changement de projet et `onCleared()` (idempotent, `SessionLsp.kt:242-256`) — SANS bornage de délai | s'enregistre au registre `Fermable` (étape B) | E7 : multi-instances non coordonnées ; fermeture synchrone non bornée |
| 11 | `LspProject`/`LspEditor` cel-lsp (feature/editor, `CoordonnateurLsp`) | par ACTIVITÉ | branchement au 1er rendu d'un `.java` | `fermer()` dans `onDestroy` INCONDITIONNEL (E8, `EditorActivity.kt:1851`) | idem + garde `isChangingConfigurations` (étape C, selon §2.5) | reconnexion à chaque rotation |
| 12 | Index LSP disque — segments persistants + LRU mémoire 30 Mo (lsp:index) | disque + process | ouverture de session (lecture paresseuse) | SURVIT VOLONTAIREMENT (cache, auto-guérison ADR 0111) ; LRU GC | idem + éviction `onTrimMemory` (I4/E16) | néant (cache reconstituable — c'est la conception) |
| 13 | Sessions d'édition cel par onglet (feature/editor) | ViewModel | ouverture d'onglet (`EditorViewModel.kt:2925-2936`) | fermeture d'onglet (`:3136-3139`) + `onCleared()` (`:3363`) ; **E12** : `remplacerContenuFichier` (`:2986`) sans `disposer()` | flush + dispose systématiques (étape C) ; plafond à arbitrer (E15) | fuite latente du thread de restyle (E12) |
| 14 | Sauvegardes automatiques (feature/editor) | ViewModel | frappe (debounce 1 500 ms, `:3000-3021`) | annulation SANS flush (`onCleared:3362`) — **E2** | flush en contexte non annulable borné avant libération (I7) | perte ≤ 1,5 s par onglet sale au kill |
| 15 | Surveillance de l'arbre (feature/editor, polling 4 s) | ViewModel | `onStart` (`EditorActivity.kt:345`) | `onStop` (`:351`) + cancel du scope (idempotent) | idem (déjà conforme : liée à la VISIBILITÉ, pas au projet) | néant |
| 16 | Surveillance Git (feature/editor, polling 2 s, sans process git) | ViewModel (fragment) | `GitFragment.onStart` | `GitFragment.onStop` + scope | idem | néant (E5 : meurt avec l'éditeur, pas de fuite) |
| 17 | Recherche (feature/editor, debounce 300 ms + streaming) | ViewModel (fragment) | saisie | annulation à chaque relance + scope (`RechercheViewModel.kt:74,205`) | idem | néant |
| 18 | Sessions terminal + `TerminalService` (core:terminal-runtime) | `@Singleton` process | création de session (tiroir éditeur ou `TerminalActivity`) | geste utilisateur SEUL (`RegistreSessionsTermux.kt:236-249`) ou mort du processus | politique §2.4 (projet ou global) | E11 : shells vivent sans projet ouvert |
| 19 | `ServiceInstallationEnvironnement` (core:bootstrap) | service | installation bootstrap | fin d'installation (auto) | idem (lié à l'installation, pas au projet) | néant |
| 20 | `ServicePontJournaux` (app) — pont Binder des apps exécutées | service | connexion d'une app exécutée | déconnexion de l'app | idem | néant (cycle lié à l'app exécutée) |
| 21 | `ObservateurOutilsTerminal` (core:bootstrap) | `@Singleton` process | collecte | arrêt de collecte (ballotage) | idem | néant |
| 22 | Journalisation `FileSink` (core:logging) | process | `Application.onCreate` (`:179`) | jamais (cycle = application, documenté) | idem | néant (conception) |
| 23 | `CrashHandler` / `ExitInfoRecorder` (core:crash) | process | avant `super.onCreate` | jamais | idem | néant (conception) |
| 24 | Données : `OptionsEditeur`, `PorteurStyleCurseur`, Room, DataStore | `@Singleton` process | injection | jamais (état de données) | idem | néant (conception — pas des ressources de fond par projet) |

Lecture en deux phrases : **tout ce qui est lié au projet et lourd vit
avec le PROCESSUS** (lignes 1–8 : démon, connexions, console, pompes) ;
**tout ce qui est scopé correctement est léger et meurt déjà bien**
(lignes 15–17 : arbre, Git, recherche — liés à la visibilité ou au
ViewModel). L'étape B doit donner aux lignes 10–11 le point d'ancrage
central (`SessionProjet`), l'étape C y branchera 13–14, l'étape D
traitera 2–4 et 7 (message protocole).

### 4.3 Parcours — diagrammes de séquence (vérifiés dans le code)

Notation : `──►` appel direct, `═►` survit à la fin du parcours,
`✕` meurt. Les numéros renvoient à l'inventaire §4.2.

**P1 — accueil → éditeur → retour (le parcours nominal)**

```
Utilisateur      Accueil (HomeFragment)      EditorActivity            EditorViewModel           Fond process-wide (#1-#8)
    │ clic projet       │                        │                         │                        │
    │──────────────────►│ marquerOuvert (Room)   │                         │                        │
    │                   │ Effet OuvrirEditeur ───┼──► openEditor(id) ──────┼──► Intent + EXTRA ─────┼─ nouvelle instance
    │                   │                        │ onCreate                │ init: observerProjet │ (standard, empilement)
    │                   │                        │ fragments tiroir ──────┤ suivre(projet)
    │                   │                        │                         │ ouvrirSessionLsp (#10)├─ Mutex, racine FUSE
    │                   │                        │                         │ restaurerOnglets      │ workspace-state.json
    │                   │                        │                         │ attacher() ───────────┼─ console vierge, builds
    │                   │                        │                         │ lancerSyncOuverture   │ en vol conservés
    │                   │                        │ onStart: surveillance ──┤ (#15, idempotent)
    │                   │                        │ rendu ─► brancherLSP ───┤ cheminFuseDe ─► #11
    │ ...édition...     │                        │                         │ frappe ─► debounce 1,5 s (#14)
    │ retour (back)     │                        │ OnBackPressedCallback  │                        │
    │──────────────────────────────────────────►│ Quitter ───────────────►│ demanderSortie
    │                   │                        │ [si onglets sales :     │ suspend auto-save +
    │                   │                        │  dialogue Enregistrer/  │ ConfirmerFermeture
    │                   │                        │  Ne pas enregistrer]    │
    │                   │                        │ Quitter ─► finish()    │                        │
    │                   │                        │ onDestroy: #11.fermer()│                        │
    │                   │                        │ ✕ #11 (LspProject)      │ onCleared:
    │                   │                        │                         │ ✕ #13 (sessions cel)
    │                   │                        │                         │ ✕ #14 (auto-saves SANS flush — E2)
    │                   │                        │                         │ ✕ #10 (SessionLsp.close)
    │◄──────────────────│ Room flow recollecté   │                         │ ✕ #15 #16 (scope)     │ ═ #1-#8 SURVIVENT
```

Verdict P1 : la fermeture du SEUL propriétaire de session est correcte
pour tout ce qui vit dans le ViewModel ; **le fond process-wide ne
connaît pas la fermeture** (connexions Gradle de P, CacheSync, console,
démon) — c'est le cœur du problème posé par le prompt.

**P2 — éditeur → terminal → retour**

```
Utilisateur      EditorActivity            TerminalActivity         RegistreSessionsTermux (#18)   TerminalService (#18)
    │ menu/tiroir            │                      │                         │                        │
    │───────────────────────►│ OuvrirTerminal ─────►│ Intent EXTRA_REPERTOIRE│                        │
    │                        │                      │ ViewModel: session ────►│ createSession(rép) ──►│ démarre à la 1re session
    │                        │ [éditeur stoppé,     │ (processus shell fork)  │                        │ (1er plan, notif 42 001)
    │                        │  session conservée]  │                         │                        │
    │ retour (toolbar)       │                      │ goBack ─► finish()      │                        │
    │◄───────────────────────┼──────────────────────┘                         │ ═ sessions SURVIVENT   │ ═ service SURVIT
    │                        │ onStart: surveillance reprise (#15)           │ (jusqu'au processus ou │ (tant qu'une session vit)
    │                        │ [aucune duplication : onStart idempotent]     │  geste utilisateur)    │
```

Verdict P2 : suspension correcte de l'éditeur (rien ne se ferme), MAIS
le terminal est GLOBAL (E11) — quitter l'ÉDITEUR ne ferme pas non plus
ses sessions ; politique à trancher (§2.4).

**P3 — éditeur → paramètres → retour : PARCOURS INEXISTANT**

Aucun accès aux paramètres depuis l'éditeur (menu `menu_editeur.xml`
sans entrée, `openSettings` jamais appelé par `EditorActivity` —
preuve : recherche textuelle). Les paramètres ne sont atteignables que
depuis l'accueil. La propagation existe déjà, elle, par trois canaux :
thème/langue → `AppCompatDelegate` (recréation des activités,
`MainActivity.kt:256-292`), palette → `recreate()` de TOUTES les
activités vivantes (`AppliquerApparence.kt:112-120`), réglages propres
à l'éditeur → flux `OptionsEditeur` appliqués au fil de l'eau
(`EditorActivity.kt:305-309`). **Écart UI à combler en étape E** : le
prompt attend ce parcours (matrice §4.4, ligne 4).

**P4 — changement de projet (P → Q)**

```
Cas nominal (aujourd'hui)                          Cas dégénéré (vérifié, non testé) — I1 en jeu
─────────────────────────                          ─────────────────────────────────────────────
éditeur P ─► retour ─► P1 (fermeture complète)      notification tooling touchée pendant que P est ouvert
accueil ─► Room à jour (flux recollecté)              ─► launch intent SANS extra (E10)
openEditor(Q) ─► NOUVELLE instance                    ─► MainActivity REORDER_TO_FRONT : l'accueil
   ViewModel Q : SessionLsp(Q) ouverte                    passe AU-DESSUS de l'éditeur P (vivant, onStop)
   attacher() : console repart vierge                 accueil ─► openEditor(Q) ─► 2e EditorActivity
   #10(P) fermée par P1                              2 ViewModels vivants ─► 2 SessionLsp (E7)
   #2 #4 : connexions P JAMAIS libérées (E9)         éditeur P empilé dessous, session P ouverte
                                                      #12 : index disque PARTAGÉ par conception
                                                      (déduplication par empreinte, ADR 0111 §2)
```

Verdict P4 : le cas nominal ferme P proprement côté session, mais pas
côté serveur (E9) ; le cas dégénéré VIOLÉ I1 — deux sessions
simultanées n'ont jamais été testées. Le changement de projet DANS un
même éditeur (relocalisation du document projet) referme bien
l'ancienne session avant la nouvelle (`EditorViewModel.kt:1878-1888`).

**P5 — rotation dans l'éditeur**

```
rotation ─► onPause ─► onStop (#15 arrêté) ─► onSaveInstanceState (tiroir + rail, :2367)
        ─► onDestroy SANS GARDE isChangingConfigurations (E8)
              ─► ✕ #11 : CoordonnateurLsp.fermer() (LspProject.shutdown, editeur.disconnect)
        ─► nouvelle instance : onCreate
              ─► ViewModel CONSERVÉ (ViewModelStore) ─► #10 SessionLsp survit
              ─► #13 sessions cel survivent, onglets inchangés
        ─► onStart (#15 repris, idempotent) ─► rendu ─► rebranchement #11 (reconnexion cel-lsp)
```

Verdict P5 : I3 est respecté pour la SESSION (rien de projectuel ne se
ferme) mais la coordination cel-lsp paie une reconnexion par rotation
(§2.5, à mesurer — S2).

**P6 — mort du processus puis restauration**

```
kill (LMK / crash) ─► TOUT meurt dans le processus
      ✕ #1 process orchestrateur (enfant : tué avec le groupe du processus — À VÉRIFIER PAR MESURE S7)
      ✕ #18 shells terminal (mêmes conditions)
      ✕ #10 #11 #13 #14 #15 #16 (tout ce qui vivait dans le processus)
      ═ #12 index disque, workspace-state.json, lsp-modele.json (fichiers)
relance ─► CrashActivity (si crash) : Redémarrer ─► launch intent NEW_TASK|CLEAR_TASK
      ─► RACINE de la tâche = accueil — AUCUN projet restauré (CrashActivity.kt:159-168)
relance ─► launcher : MainActivity ─► accueil (Room rechargé)
réouverture de l'éditeur (geste utilisateur) ─► Intent conservé (EXTRA_PROJECT_ID)
      ─► ViewModel init ─► ouvrirSessionLsp ─► nourrirDepuisModelePersiste (v0.102.0)
      ─► restaurerOnglets (workspace-state.json — contenus relus, jamais sales)
      ─► #1 redémarré par Application.onCreate (jar déjà déployé : empreinte identique)
```

Verdict P6 : la restauration par projet existe et est propre
(persistance workspace + modèle LSP, v0.102.0) ; le redémarrage post-
crash repart de l'accueil (choix à documenter — étape E : « relance
propre »).

**P7 — retour par notification (tooling, build en cours)**

```
notification id 42 002 (tooling) ─► PendingIntent = launch intent SANS extra (E10)
      ─► MainActivity (REORDER_TO_FRONT) : l'ACCUEIL remonte — pas l'éditeur, pas le projet
      ─► l'utilisateur doit rouvrir le projet à la main (P1)
notification id 42 001 (terminal) ─► même mécanisme (TerminalService.kt:125-134)
notification installation ─► idem + action Annuler (PendingIntent.getService)
```

Verdict P7 : comportement DÉFINI (retour à la racine) mais il ne
répond pas à l'attente du prompt (retour vers le projet X alors que Y
est ouvert — matrice ligne 8). À traiter en étape E (extra de routage,
motif ADR 0048).

### 4.4 Matrice de transitions inter-écrans (attendu vs observé, v0.102.0)

« Une ligne = un test » (I9) — la colonne Observé décrit le
comportement vérifié ; les écarts alimentent les étapes C–E.

| De → Vers | État attendu (prompt §6.3) | État observé v0.102.0 | Écart / étape |
|---|---|---|---|
| Accueil → Éditeur (P) | Session P ouverte ; UNE seule ; tooling lié à P ; Git/arbre démarrés | Session #10 ouverte ✓ ; unicité NON garantie (E7) ; tooling PROCESS-wide, pas lié à P ; arbre+Git démarrés ✓ | B (I1), D |
| Éditeur → Accueil | Session fermée ; plus de travail de fond lié à P ; accueil à jour | #10 #11 #13 #15 #16 fermés/arrêtés ✓ ; fond #1–#8 lié à P survit (E9) ; accueil à jour ✓ (flux Room recollecté à STARTED) | C/D |
| Éditeur → Terminal → retour | Session conservée ; état inchangé ; aucun doublon de surveillance | Conservée ✓ (activité dessous) ; sessions terminal GLOBALES (E11 — politique §2.4 tranchée : conservées) ; reprise idempotente ✓ ; notification 42 001 → ÉCRAN DU TERMINAL (v0.106.0, §8.1) | clos (politique documentée) |
| Éditeur → Paramètres → retour | Session conservée ; réglages appliqués | PARCOURS LIVRÉ (v0.106.0, §8.3) : entrée de menu + routage ADR 0048, l'espace survit dessous (I3) ; propagation par les 3 canaux déjà fonctionnelle | E ✓ |
| Éditeur (P) → Accueil → Éditeur (Q) | P fermée AVANT Q ; aucun état de P visible dans Q | P fermée (nominal) ✓ ; console repart vierge ✓ MAIS #2 #4 serveur gardent P (E9) ; cas dégénéré : 2 sessions (E7/I1) | B, D |
| Rotation dans l'éditeur | Aucune fermeture ; aucune ressource dupliquée | Session conservée ✓ (I3 côté session) ; #11 recréé+reconnecté (E8) ; aucune duplication ✓ | C (garde) ou §2.5a |
| Éditeur → arrière-plan → retour | Session conservée ; reprise propre ; caches éventuellement reconstruits | Conservée ✓ ; RIEN ne coupe en arrière-plan (E16 : pas de temporisation, pas de `onTrimMemory`) | C + §2.3 |
| Mort du processus → restauration | Ouverture propre ; aucune ressource fantôme | Restauration workspace + modèle LSP ✓ (v0.102.0) ; daemon relancé par `Application.onCreate` ✓ ; TerminalService sticky repart et s'arrête seul ✓ ; process enfants : à vérifier (S7) | mesure, puis E |
| Notification tooling (X) pendant que Y est ouvert | Comportement défini et testé | LIVRÉ (v0.106.0, §8.1–8.2) : la notification porte l'extra projet du CANAL (X) ; l'ouverture de X referme la session de Y (I1) et l'espace de Y se referme de lui-même (effet `FermerEspace`) — comportement DÉFINI et TESTÉ | E ✓ |
| Build en cours → quitter | Selon la décision §2.1 | (c) DE FACTO : build continue (singletons), notification maintenue par #9, console conserve le rejeu | valider §2.1c |

### 4.5 Protocole de mesures de référence sur appareil (AVANT tout correctif)

> **Kit rejouable (v0.106.0)** : `scripts/baseline-cycle-vie.sh` exécute
> S1–S7 + le p95 LSP avec capture automatique des journaux, `meminfo` et
> `ps` — usage et préparation en tête de script. Corrections apportées
> au protocole d'origine : les tags de journal cités initialement
> (`LspEditeurSession`, `GradleServer`) n'existent PAS dans le code —
> les tags réels sont `App`, `Editor` (cycle de session LSP compris),
> `LspEditeur` (connexion cel-lsp), `ToolingDaemon`, `gradle-server`
> (minuscule, stderr du serveur réinjectée), `ToolingService`.

Conditions : build **debug** (LeakCanary actif), appareil du
propriétaire (moto g06, Android 15 / API 35), projet réel multi-gradle
(P) et un second (Q), 10 min de repos avant le premier relevé, écran
allumé sauf S6. Chaque scénario note l'heure de début/fin et le journal
(`adb logcat`, filtre conservé par le kit). Relevés communs : `adb
shell dumpsys meminfo jo.codeide` (Java heap, native heap, TOTAL),
`adb shell ps -A -o PID,PPID,NAME | grep -i codeide` (processus
enfants : orchestrateur `java`, démons Gradle `GradleDaemon`, shells)
— consigner le tableau ci-dessous.

| Scénario | Procédure | Relevés |
|---|---|---|
| S1 — 20 cycles | Ouvrir P depuis l'accueil, attendre la fin de l'indexation (disque vert), quitter par retour (sans onglet sale) ; répéter 20× | meminfo aux cycles 1, 5, 10, 20 (croissance visée < 5 % en F) ; instances `EditorActivity` retenues (LeakCanary doit rester muet) ; nb de processus enfants (doit rester CONSTANT) |
| S2 — rotation ×5 | Dans P, onglet `.java` ouvert, rotation 5× | temps de rebranchement LSP au journal (« LSP Java connecté » ×5) ; tas avant/après |
| S3 — P puis Q | Ouvrir P, quitter, ouvrir Q, ouvrir un `.java` | journal : session P refermée ; AUCUNE ligne de P dans la session Q (classpath, console) ; processus enfants de P disparus ? (les démons Gradle peuvent persister — politique) |
| S4 — build en cours → quitter | Lancer un build, quitter l'éditeur pendant « tâches en cours » | le build TERMINE-t-il (notification finale) ? console au retour (rouvrir P) ; état cohérent |
| S5 — terminal → quitter | Ouvrir un terminal depuis l'éditeur, lancer `top`, quitter l'éditeur | session vivante ? notification 42 001 présente ? shell vivant (ps) ? |
| S6 — arrière-plan 10 min | Éditeur P ouvert, Home, 10 min | RIEN ne doit être coupé aujourd'hui (baseline) : tas au retour, session conservée, temps de reprise |
| S7 — kill + relance | Éditeur P ouvert, `adb shell am kill jo.codeide`, relancer par le lanceur, rouvrir P | processus ORPHELINS au moment du kill (ps avant relance — orchestrateur, shells) ; restauration (onglets, contenus jamais sales, session nourrie depuis le cache) |

Ces relevés constituent la BASELINE exigée par le prompt : tout
correctif des étapes C–D se comparera à ces chiffres. Le protocole
reprend le motif du §11.2 de `docs/LSP_JAVA.md` (écrit en étape 0,
exécuté aux étapes suivantes) : il est conçu pour être rejoué à
l'identique.

### 4.6 Détection de fuites en debug — déjà en place, procédure formalisée

Le prompt demandait de JUSTIFIER une dépendance de détection (règle 9 :
version vérifiée) : **elle existe déjà**, introduite avec l'ADR 0028 —
elle a détecté la fuite du thread de restyle des sessions cel (corrigée
par `dispose()`, contrat `SessionSuivie`).

- **LeakCanary 2.14**, `debugImplementation` (`app/build.gradle.kts:89`,
  version épinglée `gradle/libs.versions.toml:75`) — release exempte.
  Surveille Activity/Fragment/View et objets retenus ; toute rétention
  après les 20 cycles S1 doit être signalée comme anomalie d'étape.
- **StrictMode** actif en debug uniquement, `detectAll()` +
  `penaltyLog()` sur les DEUX politiques (thread + VM —
  `CodeIdeApplication.kt:291-306`) : couvre fuites de `Closeable`,
  d'enregistrements (listeners/receivers) et d'Activity. Jamais de crash
  en debug (les violations partent dans le logcat et sont relevées en
  recette).
- **Procédure de recette fuites** (à dérouler avec §4.5) : relever les
  lignes `StrictMode` du logcat après chaque scénario S1–S7 ; observer
  les notifications LeakCanary ; consigner toute rétention d'objets
  ET toute croissance de tas non récupérée après GC forcé
  (`adb shell am dumpheap` optionnel pour analyse).

### 4.7 Verdicts sur les constats et analyses du prompt

| Constat prompt | Verdict v0.102.0 | Preuve principale |
|---|---|---|
| §3.1 E1 — aucun `isFinishing`/`isChangingConfigurations` | **Confirmé** — et `onDestroy` existe depuis v0.100.0 mais SANS garde (fermeture LSP inconditionnelle, E8) | `EditorActivity.kt:1851-1854` |
| §3.1 E2 — sauvegardes annulées sans flush | **Confirmé** — fenêtre bornée 1,5 s (debounce), sortie par dialogue épargnée | `EditorViewModel.kt:3360-3369, 4077` |
| §3.1 E3 — démon process-wide | **Confirmé** — arrêt uniquement au changement d'empreinte ; enfant tué aux reconnexions seulement | `CodeIdeApplication.kt:222,238` ; `DaemonManager.kt:284-292` |
| §3.1 E4 — `GradleService` process-wide | **Confirmé** — + nuance E13 (miroir alimenté par l'éditeur seul) | `GradleService.kt:350-418` |
| §3.1 E5 — surveillance Git viewModelScope | **Confirmé** — meurt avec l'éditeur (pas une fuite) | `GitViewModel.kt:106-126` |
| §3.1 E6 — launchMode standard | **Confirmé** — + E7 : le cas dégénéré 2-sessions est réel (P4) | manifestes feature/editor, feature/terminal |
| §3.2 `GradleConnectorPool` : qui libère quoi ? | **Répondu** — RIEN côté projet : `close()` global au shutdown serveur uniquement (E9) | `GradleConnectorPool.kt:45-52` |
| §3.2 `CacheSync` | **Répondu** — jamais purgé, non borné (une entrée par projet ouvert) | `CacheSync.kt:18-40` |
| §3.2 `EventBus` | **Répondu** — côté SERVEUR uniquement (file bloquante 8 192 + fil unique) ; n'existe pas dans `core/` | `EventBus.kt:51-89` |
| §3.2 `HeapMonitor` | **Répondu** — côté serveur (tick 5 s, stop à l'arrêt) ; rien côté app | `HeapMonitor.kt:19-46` |
| §3.2 `fermerSession()` : qui l'appelle ? | **Répondu** — le démon (mort process) et l'auto-remplacement, PERSONNE d'autre | `GradleApiImpl.kt:433-462` + recherche exhaustive |
| §3.2 terminal lié au projet ? | **Répondu** — global (E11), répertoire de travail initial = seul lien | `RegistreSessionsTermux.kt:109` |
| §3.2 cache LRU cel-ui | **INFIRMÉ** (E15) — `LinkedHashMap` non borné par onglet, ADR 0028 ; pas d'éviction | `EditorViewModel.kt:405` |
| §3.2 portées Hilt | **Répondu** — `@Singleton` quasi universel ; UNIQUE exception : `AppNavigatorImpl` `@ActivityScoped` ; aucun `@ViewModelScoped`/`@ActivityRetainedScoped` | inventaire §4.2 |
| §3.2 SAF/FUSE, `AppNavigator`, restauration | **Répondu** — `ResolveurCheminFuse` non scopé SANS état ; restauration par Intent + SavedStateHandle + `workspace-state.json` ; notifications SANS extra (E10) | P4/P6/P7 |
| §3.3.1 pas de notion de session | **Confirmé** — l'état est éparpillé ; le seul point de fermeture est `onCleared` + `onDestroy` | §4.2 |
| §3.3.2 ressources lourdes process-wide | **Confirmé** — #2 #3 #4 #7 (risque fonctionnel) | §4.2 |
| §3.3.3 perte de données | **Confirmé** — E2, borné 1,5 s | §4.2 #14 |
| §3.3.4 état résiduel P→Q | **PARTIEL** — console repart vierge à l'attache, MAIS builds en vol + CacheSync + connexions P subsistent | `GradleService.kt:427-436` |
| §3.3.5 fermeture incohérente entre transitions | **Confirmé** — rotation ferme #11 (E8), arrière-plan ne coupe rien (E16), process death tue tout | P5/P6 |

### 4.8 Sujets d'arbitrage et risques pour l'étape B

- **Le cœur à construire est étroit** : les ressources déjà bien scopées
  (arbre, Git, recherche, sessions cel, auto-saves) n'ont PAS besoin du
  registre pour fonctionner — elles en ont besoin pour être FLUSHÉES et
  ordonnées à la FERMETURE (I2, I7). Le registre `Fermable` de l'étape B
  doit donc rester MINIMAL : ordre inverse, délai par élément, drapeau
  « ne pas annuler », rapport de durée — sans réinventer la propriété
  des ressources qui existe déjà.
- **L'accroche LSP est le premier client réel** : `SessionLsp.close()`
  est idempotent mais synchrone et non bornée — l'adapter `Fermable`
  (étape C) devra borner (délai) et journaliser la durée ; le
  `LspProject` cel-lsp suivra (garde rotation §2.5).
- **I1 d'abord par le gestionnaire, pas par le manifeste** (§2.6) :
  ouvrir B ferme A séquentiellement — le cas dégénéré P4 (notification
  → accueil par-dessus l'éditeur) devient alors inoffensif côté
  sessions même s'il reste possible côté pile d'activités.
- **Ce que l'étape A n'a pas pu faire** : les mesures (§4.5) et le test
  automatisé de perte E2 (étape C). Aucun code de production n'a été
  modifié — la livraison est strictement documentaire, conforme au
  « livrable écrit » exigé par le prompt.
- **Durée réelle des vérifications** : étape A sans code — aucune
  commande de build n'a été exécutée (chaîne inchangée) ; références de
  session pour l'étape B (machine 2 cœurs / 3,9 Go, cache Gradle
  chaud) : tests domaine ~1 min, detekt+spotless ~1 min,
  `assembleDebug` ~1,5–5 min selon fraîcheur de l'arbre.

## 5. Étape B — contrat de session et registre de fermeture (v0.103.0, LIVRÉE)

> Décision d'architecture : **ADR 0114**
> (`docs/adr/0114-session-projet-registre-fermeture.md`).
> Livrable : module **Kotlin JVM pur** `:core:session`
> (`core/session/`), câblé dans `settings.gradle.kts` et
> `ModuleRulesPlugin.kt` (modules purs + dépendances autorisées
> `:core:model`, `:core:domain` — même famille que
> `core:database`/`core:bootstrap`). Aucune dépendance Android ; les
> adaptateurs des ressources réelles vivront aux étapes C
> (feature:editor) et D (tooling).

### 5.1 Ce qui a été livré

| Élément | Fichier | Rôle |
|---|---|---|
| `Fermable` (+ `RaisonFermeture`, `ContexteFermeture`, `Desenregistrement`) | `Fermable.kt` | Contrat d'abonnement d'une ressource (§5.5) : idempotente, bornée par délai, jamais réentrante, une exception = une panne pas un arrêt. `nom` = identifiant stable (règle 15) |
| `RegistreFermeture` | `RegistreFermeture.kt` | Fermeture en **ordre inverse du démarrage**, **délai maximal par élément**, drapeau **« ne pas annuler »** (I7, `NonCancellable`), tolérante aux **pannes partielles**, rapport de durée par élément (`RapportFermeture`) |
| `SessionProjet` | `SessionProjet.kt` | Machine à états observable `{Fermee, Ouverture, Ouverte, Fermeture}` (`StateFlow`), transitions `ouvrir`/`fermer` sérialisées par verrou, idempotence de fermeture (rapport MÉMORISÉ), nettoyage non annulable après échec des travaux d'ouverture |
| `GestionnaireSessionProjet` | `GestionnaireSessionProjet.kt` | Le garant d'**I1** : ouvrir B pendant que A vit **ferme A d'abord** (raison `ChangementDeProjet`, délais compris), PUIS ouvre B ; ouvrir le projet déjà ouvert = no-op ; deux demandes simultanées = sérialisées |
| `MarqueFermeture` | `MarqueFermeture.kt` | Garde anti-réentrance : `CoroutineContext.Key` posée pendant la libération — tout appel `ouvrir`/`fermer` déclenché DEPUIS un `Fermable` reçoit `AppError.Session(Reentrance)`, jamais d'interblocage |
| `RapportFermeture` | `RapportFermeture.kt` | Rapport de fermeture : durée totale, par élément (libéré/annulé/en panne/délai dépassé), journalisable via `AppLogger` |

- **Erreurs typées** : `AppError.Session` (nouvelle variante
  `core:model`, 4 raisons `SessionReason` : `DejaOuverte`,
  `AucuneSession`, `FermetureEnCours`, `Reentrance`) — refus
  coopératifs attendus (courses entre transitions), jamais des crash.
- **UI maintenue compilable** (whens exhaustifs sur `AppError`) :
  libellé `diagnostics_erreur_session` FR/EN ajouté
  (`feature/diagnostics`) ; `TraductionsAccueil` et
  `EcranCreationFragment` complétés (Session = hors périmètre de
  l'accueil et du wizard à cette étape — replis documentés).

### 5.2 Couverture des cas limites §9 du prompt (tests)

33 tests verts (`SessionProjetTest` 12, `RegistreFermetureTest` 12,
`GestionnaireSessionProjetTest` 9) :

| Cas exigé par le prompt §9 | Test(s) |
|---|---|
| Ordre inverse de fermeture | `RegistreFermetureTest` |
| Idempotence (fermer deux fois = rapport mémorisé, ressources jamais libérées deux fois) | `SessionProjetTest`, `RegistreFermetureTest` |
| **Panne partielle** (un élément jette, les AUTRES sont libérés) | `RegistreFermetureTest` |
| **Dépassement de délai** (élément abandonné + journalisé, les autres continuent) | `RegistreFermetureTest` |
| Fermeture pendant l'ouverture | `SessionProjetTest` |
| Ouverture pendant la fermeture | `SessionProjetTest`, `GestionnaireSessionProjetTest` |
| Réentrance (fermer depuis un `Fermable` = refus typé, pas de blocage) | `SessionProjetTest`, `GestionnaireSessionProjetTest` |
| Annulation de l'appelant (I7 : non-annulables exécutés, annulation RELANCÉE) | `RegistreFermetureTest`, `SessionProjetTest` |
| I1 : ouvrir B ferme A d'abord (séquentiel, délais compris) | `GestionnaireSessionProjetTest` |
| Ouvrir le projet déjà ouvert = no-op | `GestionnaireSessionProjetTest` |
| Deux demandes d'ouverture simultanées = sérialisées | `GestionnaireSessionProjetTest` |

Qualité : detekt + spotless verts ; kover **ligne 88,3 %** (seuil
projet ≥ 80 %, classe 96,3 %, branche 77,9 % — les branches non
couvertes sont les défenses en profondeur « inatteignables »
documentées) ; `assembleDebug` complet vert (548 tâches).

### 5.3 Ce que l'étape B ne fait PAS (et pourquoi)

- **Aucune ressource réelle n'est branchée** : le module n'est importé
  nulle part hors tests — la session ne démarre RIEN elle-même
  (règle : le contrat de domaine, pas l'orchestrateur). L'étape C
  branchera les premiers adaptateurs (`SessionLsp` borné, flush des
  sauvegardes NON annulable — I7, `LspProject` avec garde rotation).
- **Aucune décision §2 tranchée dans le code** : le gestionnaire
  offre les pièces pour l'option I1-(a) (gestionnaire séquentiel),
  mais build en cours (§2.1), démon (§2.2) et terminal (§2.3)
  attendent l'arbitrage du propriétaire — recommandations §2
  inchangées.
- **La baseline §4.5 reste à exécuter** : les mesures S1–S7 sur
  appareil doivent idéalement précéder l'étape C (avant tout
  correctif de production), conjointement au p95 LSP (§11.2 de
  `LSP_JAVA.md`, mêmes séances appareil).

### 5.4 Durée réelle des vérifications (références étape C)

Machine 2 cœurs / 3,9 Go, cache Gradle chaud, JDK 21
(`/home/z/tools/jdk-21` — le JRE système seul ne fournit pas `javac`) :
`:core:session:check` ~2 min (33 tests + detekt + spotless + kover) ;
`:app:assembleDebug` ~8 min ; `check` des 5 modules touchés
(model/diagnostics/home/newproject/session) ~3 min après compilation
chaude.

## 6. Étape C — intégration éditeur et migration des ressources (v0.104.0, LIVRÉE)

> Livrable : le branchement des ressources RÉELLES de l'éditeur sur le
> contrat `:core:session` (ADR 0114). Décisions §2 arbitrées (voir
> l'en-tête §2) ; baseline §4.5 exécutable sur le TAG v0.103.0 (état
> pré-correctif figé, APK « CodeIDE-v0.103.0-debug » sur la CI).

### 6.1 Ce qui a été livré

| Sujet | Changement | Preuve |
|---|---|---|
| **Ouverture de session** | `EditorViewModel.init` → `gestionnaireSession.ouvrir(projetId)` ; les travaux enregistrent le FLUSH des sauvegardes (élément non annulable, I7) — le ViewModel OBSERVE la session (§5.1), il n'en est plus propriétaire | `EditorViewModel.kt` (init + `enregistrerFlushSauvegardes`) ; test « l'ouverture de l'espace ouvre la session de projet » |
| **Sortie propre (E2/I7)** | `onCleared()` réécrit : la fermeture part dans la **portée session application** (`@PorteeFermetureSession`, survit à l'écran) — le registre FLUSHE les onglets sales (non annulable) PUIS les sessions cel sont disposées (ADR 0028) PUIS session LSP PUIS `workspace-state.json` | test « la sortie ferme la session ET flushe les onglets sales » (frappe non sauvegardée ÉCRITE au disque) |
| **Session LSP enregistrée** | `ouvrirSessionLspSiPossible` enregistre un `Fermable` (borne 5 s, détacheur qui remet la référence observable à `null` AVANT close) ; `fermerSessionLsp` désenregistre d'abord (mort de sa propre initiative) | test « la fermeture par le registre referme la session LSP enregistrée » |
| **I1 au changement de projet (P→Q)** | Le gestionnaire sérialise : ouvrir B ferme A (flush compris) puis ouvre B — testé avec DEUX espaces sur le MÊME gestionnaire process-wide | test « ouvrir B ferme A d'abord et flushe les onglets sales de A » |
| **E13 (miroir figé)** | Collecteur de connexion déplacé du viewModelScope vers `PompeBuildTooling.pomperConnexion()` (portée process, idempotente, sans panne) | test « le miroir de connexion vit dans la pompe process wide » (connexion mise à jour APRÈS la mort de l'espace) |
| **E12 (fuite cel)** | `remplacerContenuFichier` dispose l'ancienne session AVANT de l'écraser (contrat `SessionSuivie`/ADR 0028) | revue + pattern identique à `fermer()` ; la fuite était détectable LeakCanary |
| **E16 (onTrimMemory)** | PREMIER `onTrimMemory` du dépôt : `EditorActivity` → `ActionEditor.LibererMemoire` → caches d'arbre reconstructibles vidés (énumérations, statuts, parents — les plis/ sélection ne bougent pas) ; seuil RUNNING_CRITICAL (15, constante nommée — le champ statique est déprécié au compileSdk 37) ; la session ne bouge JAMAIS (I3/I2) | test « libérer mémoire vide les caches sans fermer la session » |
| **§2.1c (build en cours)** | Toast `editor_sortie_build_continue` (FR/EN) au `EffetEditor.Quitter` quand un build est en vol — l'annonce explicite du comportement de facto | `EditorActivity.appliquer(EffetEditor.Quitter)` |
| **DI** | `di/ModuleSessionProjet.kt` : `@Singleton GestionnaireSessionProjet` + `@PorteeFermetureSession CoroutineScope` (supervisée) ; `ModuleRulesPlugin` : `:feature:editor` autorisé à `:core:session` | build vert + `checkModuleDependencies` |

### 6.2 Choix d'implémentation notables

- **Rotation (I3)** : garantie STRUCTURELLE — la session vit hors de
  l'écran, le ViewModel survit au changement de configuration ;
  `onCleared` n'intervient qu'à la sortie réelle. Le rebranchement
  cel-lsp par rotation reste (§2.5a) : coût de reconnexion mesurable
  (S2), jamais de session dupliquée.
- **La fermeture du flush est NON annulable par CONSTRUCTION** :
  enregistrée comme travaux d'ouverture (une session n'existe jamais
  sans sa garantie de flush) ; le registre l'exécute en
  `NonCancellable` avec borne 10 s (elle borne un déraisonnement, pas
  une écriture normale).
- **Ordre contractuel de sortie** : flush → sessions cel → session LSP
  → état d'espace. `enregistrer()` lit le texte de la SESSION cel —
  l'ordre est la garantie I7 elle-même (testé).
- **Défense anti-course** : le détacheur du `Fermable` LSP remet la
  référence observable à `null` avant `close()` — un usage post-close
  (`verifierOuverte`) devient un `null` observé, pas une exception.
- **E2 vs dialogue de sortie** : le dialogue (`EnregistrerPuisFermer`)
  reste le chemin interactif ; le flush de sortie est le filet
  SILENCIEUX (kill pendant le debounce, sortie non dialoguée) — borné,
  journalisé, jamais bloquant.

### 6.3 Ce que l'étape C ne fait PAS

- **GitViewModel, surveillance de fichiers** : déjà fragment-scoped
  (E5 — meurt avec l'écran, pas une fuite) — pas de Fermable nécessaire
  à ce stade ; l'inventaire §4.2 reste la référence pour l'étape D/E.
- **Temporisation d'arrière-plan (§2.3)** et **politique du démon
  (§2.2)** : reportées aux mesures (S6) — v0.104.0 ne touche PAS aux
  durées de vie process-wide du démon et des services.
- **Navigation (extra de routage, état accueil, notifications E10)** :
  étape E — hors périmètre.

### 6.4 Vérifications

`:feature:editor:check` VERT : 338 tests (332 existants + 6 nouveaux
`CycleVieEditorViewModelTest`), detekt, spotless, lint, kover ;
`:app:assembleDebug` + `checkModuleDependencies` VERTS (9 min, machine
2 cœurs / 3,9 Go, cache chaud). Durée de l'étape C : ~1,5 journée de
travail agent (audit §6.1 compris).

## 7. Étape D — libération par projet côté tooling (v0.105.0, LIVRÉE)

> Livrable : le message de protocole **v9 « fermeture de projet »** (E9 :
> la fermeture de session gagne sa voie orchestrateur — l'écart
> `GradleApiImpl.fermerSession()` « jamais appelée par l'UI » était le
> constat d'ouverture de la mission). La **politique de durée de vie du
> démon applicatif reste REPORTÉE aux mesures** (§2.2, S6) : l'étape D
> libère les ressources PAR PROJET à l'intérieur du démon, elle ne
> touche PAS au démon lui-même.

### 7.1 Protocole v9 (compatibilité ascendante)

| Message | Sens | Rôle |
|---|---|---|
| `FermerProjetRequest {id, protocolVersion, projectDir}` | app → orchestrateur | Demande la libération du projet (idempotente : un projet inconnu est un no-op accusé) |
| `FermerProjetResult {id, protocolVersion, projectDir}` | orchestrateur → app | Accusé corrélé — son EXISTENCE est le succès |

- `PROTOCOL_VERSION` v8 → **v9** (doc champ par champ dans
  `GradleProtocol.kt`, motif des v4–v8) ; le handshake exige l'égalité
  exacte — les deux bouts vivant dans le même APK, la montée est
  atomique. Un pair v8 rejette le v9 net (`PROTOCOL_VERSION_MISMATCH`),
  jamais de comportement indéfini (testé depuis longtemps).
- Dorés : 38 fichiers `golden/` (36 + 2), cardinalités du
  `ProtocoleRoundTripTest` mises à jour (15 requêtes / 23 événements).

### 7.2 Serveur (tooling:server)

- `GradleConnectorPool.fermer(dossier)` : ferme la connexion Tooling API
  du projet UNIQUEMENT (clé canonique, idempotente) — fermer une
  `ProjectConnection` ne tue PAS le démon Gradle sous-jacent (géré par
  Gradle — politique hors périmètre, reportée S6).
- `CacheSync.purger(projectDir)` : retire l'entrée du projet (clé
  canonique, idempotente).
- `MessageDispatcher.traiter` : branche `FermerProjetRequest` →
  libération + purge + ACCUSÉ publié sur le bus (corrélation par `id`).
- EventBus : **rien à purger** — le bus est un tuyau global sans
  abonnement ni notion de projet (constat d'étape A confirmé par
  lecture : les événements portent leur projet dans leur charge utile).

### 7.3 Client et éditeur (tooling:client, feature:editor)

- `GradleToolingRepository.fermerProjet(projectDir): AppResult<Unit>`
  (nouvelle méthode du PORT, E9 fermé) : `echanger` avec accusé, délai
  15 s, échec typé si l'orchestrateur est absent (jamais bloquant).
  Router client : branche `FermerProjetResult` (complétion de promesse
  — l'épuisement du `when` est la garde de compilation).
- `FermablesEspace.fermetureProjetTooling(...)` : le **Fermable
  « tooling-projet »** de la session — enregistré dans les travaux
  d'ouverture (étape C), refermé en PREMIER (ordre inverse). Politique
  de renoncement (§2.1c) : un build OU une sync en vol au moment de la
  fermeture CONTINUE — sa connexion vit avec lui (renoncement
  journalisé, la prochaine ouverture ou la mort du process refermera) ;
  un chemin non résolu : rien à libérer.
- LSP Fermable : le saut `Dispatchers.IO` est retiré de `fermer()` — le
  registre sérialise et borne déjà chaque élément, et un saut réel
  échappe au temps virtuel des tests (indéterminisme corrigé).

### 7.4 Vérifications

`:tooling:protocol:check` (38 dorés, round-trip, compat) ;
`:tooling:server:check` (117 tests, INTÉGRATION RÉELLE : sync fixture
→ fermeture accusée → idempotence projet inconnu → session vivante
(ping) → re-sync réussit = le pool ROUVRE à la demande) ;
`:tooling:client:check` (envoi + accusé corrélé + chemin canonique +
échec typé sans orchestrateur) ; `:tooling:daemon:check` ;
`:feature:editor:check` (fermeture tooling part avec le chemin FUSE ;
renoncement build en vol + session fermée quand même) ;
`:core:domain:check` (faux du port étendus) ; `:app:assembleDebug` +
`checkModuleDependencies` VERTS.

### 7.5 Ce que l'étape D ne fait PAS

- **Politique du démon** (§2.2) : durée de vie inchangée (process-wide)
  — les mesures S6 décideront (conservé / arrêt après X min / relance).
- **Build en vol** : la libération est renoncée (pas différée — la
  re-libération à la fin du build demanderait un index projectDir→build
  côté serveur, ajouté si les mesures le justifient).
- **Étape E (navigation)** : notifications E10, extra de routage,
  état accueil — hors périmètre → **LIVRÉE en v0.106.0 (§8)**.

## 8. Étape E — cohérence de navigation (v0.106.0, LIVRÉE)

Le prompt §6 E demandait la matrice de transitions « implémentée » :
état « projet ouvert » sur l'accueil, réglages au retour, SAF révoquée
détectée, installation répercutée, retour depuis notification, relance
propre après plantage. Ce qui suit détaille chaque livrable, le mécanisme,
et les tests (matrice §4.4 mise à jour en conséquence).

### 8.1 Retour depuis notification — routage par extra (E10/P7 clos)

- **Clés partagées `RoutageInterEcrans`** (`core:domain`, nouveau) :
  `EXTRA_PROJET_CIBLE` + `EXTRA_ECRAN_CIBLE`/valeurs canoniques —
  l'unique carrefour visible des 3 services (terminal-runtime,
  bootstrap, feature:editor) ET de `:app` (les règles de dépendance
  interdisent à un service de cœur de voir le module applicatif).
  `RoutageEcran` (app) y délègue : source unique.
- **Notification tooling (42 002)** : `GradleService.EtatGradle` gagne
  `projetsParCanal` (miroir par canal, publié par l'espace AU LANCEMENT
  de chaque opération sync/build/tâches — `publierProjetDuCanal`) ;
  le `PendingIntent` porte l'extra projet du canal de la notification.
  Un build parti du projet X continue sous le renoncement §2.1c :
  SA notification route vers X, même après la fermeture de X ou
  l'ouverture de Y — le canal porte l'ORIGINE de sa propre activité.
- **Notification terminal (42 001)** : extra `ECRAN_TERMINAL` — le
  commentaire du service promettait l'écran du terminal depuis T5, le
  code tient enfin la promesse.
- **Notification installation (4 2087)** : extra `ECRAN_INSTALLATION`
  (valeur déjà routée par `MainActivity`).
- **`MainActivity`** consomme l'extra projet à froid (après le routage
  racine) et à chaud (`onNewIntent`, priorité sur l'écran cible) →
  `navigator.openEditor(id)`.
- **Codes de requête = identifiants de notification** (42 001/42 002/
  4 2087) : les extras ne comptent pas dans l'identité d'un
  `PendingIntent` (`filterEquals`) — un code partagé laisserait le
  dernier créé réécrire les extras du premier (piège UPDATE_CURRENT).

### 8.2 Notification (X) pendant que Y est ouvert — P4 dégénéré clos

Ouverture de X par le routage → le gestionnaire referme la session de Y
(I1, flush I7 compris) PUIS ouvre X — c'était le contrat de l'étape B.
Ce qui manquait : l'ÉCRAN de Y restait vivant dessous (E6/E7, deux
espaces pour deux projets, le ViewModel de Y refermait à sa mort la
session de X !). Trois mécanismes livrés dans `EditorViewModel` :

- **veilleur de bascule** : l'espace observe `gestionnaireSession.etat`
  — dès que la session vit sur UN AUTRE projet, émet
  `EffetEditor.FermerEspace` (l'activité fait `finish()` SANS dialogue
  ni re-flush : le registre a déjà tout refermé) ;
- **garde de propriété à la sortie** : `onCleared` ne referme la
  session QUE si elle est encore la sienne (sinon la sortie de Y
  tuerait le travail de X) ;
- **espace né sur session déjà tenue** (double ouverture du MÊME projet
  via notification alors qu'un espace vit dessous) : le nouveau se
  referme aussitôt — le premier reste l'unique propriétaire.

Résultat : jamais deux espaces vivants sur deux projets ; un retour sur
un espace révoqué le referme immédiatement. `§2.6 (b)` (launchMode)
reste non retenue — le gestionnaire + le veilleur couvrent I1 sans
toucher au manifeste, comme l'arbitrage le prévoyait.

### 8.3 Parcours P3 — éditeur → paramètres → retour

Entrée `action_parametres` dans `menu_editeur.xml` (débordement) →
`navigateur.openSettings()` — le routage ADR 0048
(`relancerMainActivity` + `REORDER_TO_FRONT|SINGLE_TOP`) remonte l'hôte
du graphe SANS détruire l'espace dessous : la session survit (I3). La
propagation des réglages existait déjà par ses trois canaux
(`AppCompatDelegate` thème/langue, `AppliquerApparence` palette
— recreate de TOUTES les activités vivantes, éditeur compris —, flux
`OptionsEditeur` au fil de l'eau) : le parcours manquait, il est livré.

### 8.4 État « projet ouvert » sur l'accueil

Port LECTURE SEULE `ObservateurSessionProjet` + `EtatSessionProjetVue`
(`core:domain`, nouveaux) : l'accueil observe la session du processus
SANS dépendre de `:core:session` (règle de dépendance inchangée —
l'implémentation `ObservateurSessionProjetEspace` vit dans
feature:editor, auprès du gestionnaire, liaison Hilt singleton).
`EtatAccueil.session` + badge sur la carte du projet concerné
(« Session ouverte », « Fermeture en cours » pendant la transition —
l'état RÉEL, la liste n'est plus la seule source) ; le rafraîchissement
de la liste au retour existait (flux Room recollecté à STARTED +
`marquerOuvert` à l'ouverture).

### 8.5 Permission SAF révoquée / stockage disparu AU RETOUR

Le balayage périodique s'arrêtait MUET sur une permission perdue
(kdoc : « le bandeau relève de Rafraichir et du suivi du registre »).
Le contrôle manquant est livré : `verifierAccesAuRetour()` au
redémarrage de la surveillance (chaque `onStart` de l'écran) —
`VerifyProjectAccessUseCase` (permission puis existence), bandeau
EXISTANT de l'explorateur (« Résoudre » referme l'espace), surveillance
arrêtée sous le bandeau. Un échec injoignable (E/S passagère) ne
fabrique rien : le balayage reprend, `Rafraichir` relève.

### 8.6 Installation des outils → disponibilité du tooling

L'état était déjà répercuté (refus typé `MESSAGE_JDK_ABSENT` actionnable
dans l'onglet Sortie, redirection vers l'écran d'installation pour le
terminal, sync d'ouverture attendant ≤ 5 s le premier état outils).
Le mensonge restant : « JDK absent (outils non installés) » pendant
qu'une installation VIENT — le refus dit désormais l'état réel du
parcours (`MESSAGE_INSTALLATION_EN_COURS`, orchestrateur consulté) ;
le journal distingue les deux cas.

### 8.7 Écran de plantage → relance propre (choix DOCUMENTÉ, sans code)

Le mécanisme est sain et vérifié (P6) : `CrashHandler` tue le processus
AVANT relance, `CrashActivity` relance le launch intent en
`NEW_TASK|CLEAR_TASK` — la racine (accueil) repart PROPRE : aucun état
fantôme, aucune ressource ressuscitée. **Choix assumé** : après un
plantage, NE PAS rouvrir automatiquement le projet (l'état au moment du
crash est suspect ; l'utilisateur rouvre délibérément — et la
restauration par projet existe : workspace + modèle LSP persistés,
v0.102.0). La cohérence exige une relance propre, pas une amnésie
courageuse.

### 8.8 Vérifications

`:feature:editor:check` (341 tests — 6 nouveaux : miroir du canal SYNC,
bascule → `FermerEspace`, espace né sur session tenue, sortie qui
épargne la session d'un autre, permission révoquée au retour, message
d'installation) ; `ObservateurSessionProjetEspaceTest` (projection du
cycle complet) ; `:feature:home:check` (34 tests — état de session dans
l'accueil) ; `:core:domain:check` (ports purs) ;
`:core:terminal-runtime:check` ; `:core:bootstrap:check` ;
`:app:compileDebugKotlin` + `:app:assembleDebug` (28,3 Mo) +
`checkModuleDependencies` VERTS. Durées réelles (machine 2 cœurs,
démons frais) : domain 2m41s (dont téléchargements), editor check
~1m10s par passage test, assembleDebug 3m32s.

### 8.9 Ce que l'étape E ne fait PAS

- **LaunchMode §2.6 (b)** : non retenue (le veilleur couvre I1 sans
  manifeste) — à revisiter si les mesures F montrent des empilements
  résiduels.
- **Notification pendant l'espace AU PREMIER PLAN** : le tap ramène la
  TÂCHE au premier plan (comportement Android du launch intent) sans
  livrer l'extra si l'activité top n'est pas l'hôte — inchangé
  (non régressif), documenté ici.
- **Politique du démon §2.2 / temporisation §2.3** : toujours aux
  mesures S6 (kit §4.5 prêt).
- **p95 appareil §11.2** : relevés à exécuter par le propriétaire avec
  le kit (chronométrage vidéo des frappes compris).

## 9. Étape F — vérification et validation (v0.107.0, OUTILLÉE)

> **Statut** : la mission est fermée côté CODE jusqu'à E incluse ; F est
> LIVRÉE en deux volets — **preuves locales** (tests automatisés, verts
> en chaîne de vérification) et **kit d'exécution sur appareil**
> (`scripts/validation-f.sh`, verdicts AUTOMATIQUES par critère §8).
> L'exécution appareil elle-même attend un TÉLÉPHONE BRANCHÉ : la
> machine de travail n'a ni périphérique (`adb devices` vide) ni
> émulateur (pas d'image KVM, 2 cœurs/3,9 Go). Le kit fait tout ce qui
> est scriptable (cycles par `am start` + extra de routage v0.106.0,
> GC forcé, `meminfo`/`ps`/`cmdline`, rotations forcées, journaux
> filtrés) et calcule les verdicts ; le propriétaire branche, lance,
> consigne les deux observations manuelles (console de Q, notification
> finale du build).

### 9.1 Table de traçabilité — critères §8 du prompt → preuve

| Critère §8 | Preuve locale (test, vert) | Preuve appareil (kit F) |
|---|---|---|
| 1. 20 cycles, croissance < 5 %, zéro `EditorActivity` retenue | — (mesure par nature) | F1 AUTO : GC forcé + `meminfo` cycles 1/5/10/20 → croissance calculée ; `LEAK*` LeakCanary et violations StrictMode comptées au journal |
| 2. Aucun processus enfant LIÉ AU PROJET après sortie | `la fermeture de session libere le projet cote orchestrateur` (CycleVieEditorViewModelTest — protocole v9, E9) | F2 AUTO : `ps` + lignes de commande `/proc/*/cmdline` — vestige de P = ÉCHEC ; démons Gradle process-wide = OK (politique §2.2, reportée aux mesures) |
| 3. P → Q : aucun vestige de P | `ouvrir B ferme A d abord et flushe les onglets sales de A` (I1) + `la fermeture par le registre referme la session LSP enregistree` | F3 AUTO : « session fermée (P) » AVANT « session ouverte (Q) » au journal ; AUCUNE ligne de P après l'ouverture de Q ; console/onglets/classpath : observation manuelle guidée |
| 4. Aucune perte en sortie rapide (AUTOMATISÉ) | `la sortie ferme la session ET flushe les onglets sales` (I7 : la frappe est ÉCRITE avant le debounce, prouvée sur disque) | — (couvert par le test) |
| 5. Rotation : rien ne ferme, rien ne se duplique | `la rotation ne ferme ni ne reouvre la session` (NOUVEAU v0.107.0 : I3 — une seule ouverture, zéro fermeture, session LSP attachée, E8 borné) | F5 AUTO : 5 rotations forcées → process vivant, ZÉRO « session fermée » au journal, rebranchements LSP comptés (≥ 5) |
| 6. Fermeture bornée même si un élément bloque | `depassement de delai — element abandonne, les autres ferment` + `le delai borne aussi les elements non annulables` (RegistreFermetureTest — l'élément bloqué est JOURNALISÉ) | — (couvert par les tests) |
| 7. Build en vol → sortie suit §2.1c | `un travail gradle en vol fait renoncer la liberation tooling` (§2.1c : renoncement journalisé, session fermée quand même) | F7 SEMI : process VIVANT pendant le build après sortie (AUTO) ; notification finale + console au retour : observation manuelle guidée |
| 8. Matrice §6.3 entièrement verte | §4.4 colonne « Écart / étape » : toutes les lignes livrées (B/C/D/E) ; invariants I1/I2/I3/I7 par les tests cités | S1–S7 du kit §4.5 rejoués par le kit F sur les parcours équivalents |

### 9.2 Kit `scripts/validation-f.sh` — mode d'emploi

```bash
JAVA_HOME=<jdk21> ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./scripts/validation-f.sh            # uuid P/Q acquis au journal (guidé)
./scripts/validation-f.sh --projet <uuid-P> --projet-q <uuid-Q> --cycles 20
```

Préparation : build **debug** (LeakCanary + StrictMode actifs),
appareil DÉVERROUILLÉ, deux projets distincts (P multi-gradle, Q). Le
kit : force l'écran allumé pendant la session (restauré en sortant),
exécute F1 (20 cycles : `am start` avec l'extra
`jo.codeide.projet_cible` — le routage v0.106.0, à froid comme à chaud
— puis BACK ; GC par SIGUSR1 avant chaque relevé), F2, F5 (rotations
forcées `user_rotation`, `accelerometer_rotation` restauré), F3, F7
(guidé : lancement du build à la main) et assemble
`resultats-f-<date>/rapport-f.md` : verdicts `PASS`/`FAIL` calculés +
deux observations à consigner + rappel des commandes Gradle pour
rejouer les preuves locales.

### 9.3 Ce qui reste à exécuter (propriétaire, appareil branché)

1. `./scripts/validation-f.sh` (sur la version courante) — viser
   F1–F8 tous `PASS` (F2 : démons Gradle process-wide tolérés par la
   politique §2.2 jusqu'aux mesures).
2. Le kit de baseline §4.5 sur le tag figé v0.103.0 si la comparaison
   avant/après est voulue (les chiffres manquent aux deux rapports).
3. Reporter les verdicts et relevés dans le présent §9 (§4.5 pour la
   baseline) — c'est la clôture de la mission 3.
4. Les mesures S6 tranchent ensuite §2.2 (politique du démon) et §2.3
   (temporisation en arrière-plan) — éventuelle mission 3 « suite ».
