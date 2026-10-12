# Cycle de vie de l'éditeur — session de projet et fermeture propre (prompt 3)

> **Statut (v0.102.0) : mission ENTAMÉE — étape A LIVRÉE** (audit et
> cartographie, §4 du présent document). La mission démarre au retour du
> propriétaire après la mission « LSP Java » ; l'application a évolué
> depuis la rédaction du plan initial (v0.92.0 → v0.102.0 : sessions LSP
> par espace, index disque, coordination éditeur/LSP, montée
> code-editor 3.49.2) — chaque constat a été RE-VÉRIFIÉ dans le code de
> cette version. Prochaine étape : **B — contrat de session et registre
> de fermeture**, attente du « GO ». Lecture préalable : `AGENTS.md`,
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
| **B — Contrat de session et registre de fermeture** | Module de domaine Kotlin JVM pur : `SessionProjet` (`ouvrir(projet)`, `fermer(raison)`, états `{Fermee, Ouverture, Ouverte, Fermeture}`), registre de ressources `Fermable` (priorité d'ordre, délai maximal, drapeau « ne pas annuler ») ; tests unitaires : ordre inverse, idempotence, **panne partielle**, **dépassement de délai**, fermeture pendant l'ouverture, ouverture pendant la fermeture, réentrance, annulation. Aucune dépendance Android | |
| **C — Intégration éditeur et migration des ressources existantes** | `EditorActivity`/`EditorViewModel` : distinguer `isChangingConfigurations` (ne rien fermer) / `isFinishing` (fermeture de session) / `onStop` (suspension) / `onTrimMemory` (libération de caches) ; flush des sauvegardes en contexte NON annulable avant libération des sessions d'édition (I7) ; GitViewModel, GradleService/client tooling, surveillance de fichiers, caches — retrait des durées de vie process-wide injustifiées | |
| **D — Côté serveur de tooling** | Libération PAR PROJET (connexions Tooling API du `GradleConnectorPool`, démons Gradle sous-jacents, caches du `CacheSync`, abonnements du bus) ; message de protocole de « fermeture de projet » si nécessaire (compatibilité ascendante : `PROTOCOL_VERSION`, champs optionnels) ; politique du démon applicatif selon les mesures (conservé tant qu'une session existe ; arrêté après X min sans session et app en arrière-plan) | |
| **E — Cohérence de navigation** | Matrice de transitions implémentée : état « projet ouvert » sur l'accueil (liste récente, dernier ouvert), rafraîchissement au retour, réglages appliqués au retour (thème, palette, langue), permission SAF révoquée / stockage disparu détectés au retour, état de l'installation d'outils répercuté, retour depuis notification, écran de plantage → relance propre | |
| **F — Vérification et validation appareil** | 20 cycles ouvrir/quitter : croissance du tas < 5 %, aucune instance d'`EditorActivity` retenue (détecteur) ; sortie de l'éditeur ⇒ aucun processus enfant lié au projet au-delà de la politique ; ouvrir P, quitter, ouvrir Q ⇒ aucun vestige de P ; aucune perte de la dernière modification en sortie rapide (test automatisé) ; rotation ne ferme rien ; fermeture bornée en temps même si un élément bloque | |

## 2. Décisions à soumettre au propriétaire (avec recommandation)

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
2. **Politique du démon de tooling** : conservé tant qu'une session
   existe / arrêté après X minutes sans session et app en arrière-plan /
   relancé à la demande — trancher sur MESURES (§4.5, scénario S6 :
   mémoire au repos du démon + temps de démarrage), pas sur l'intuition.
3. **Temporisation en arrière-plan** avant arrêt des services lourds
   (LSP inclus) — aujourd'hui RIEN ne coupe en arrière-plan (S6) ; à
   proposer après les mesures.
4. **Terminal lié au projet ou global** — politique voulue. Constat
   d'étape A : global de fait (E11), les sessions survivent à la
   fermeture de l'éditeur ; le tiroir terminal de l'éditeur et
   `TerminalActivity` partagent le même registre.
5. **Rotation : coût cel-lsp** (nouveau, E8) — (a) accepter le
   shutdown/rebranchement du `LspProject` à chaque rotation (simple,
   coût borné : reconnexion, la `SessionLsp` et ses caches survivent) ;
   (b) hisser la coordination cel-lsp hors de l'Activity (ViewModel)
   pour survivre au changement de configuration. Recommandation : (a)
   à court terme, mesurer le coût réel (S2) et réarbitrer.
6. **I1 et launchMode** (nouveau, E6/E7) — (a) le gestionnaire de
   session garantit I1 à l'ouverture (fermer A puis ouvrir B, séquentiel)
   sans toucher au manifeste ; (b) `launchMode` + routage par extra (motif
   ADR 0048 de `MainActivity`). Recommandation : (a) à l'étape B, (b) à
   l'étape E si les mesures montrent des empilements réels.
7. **Miroir d'état tooling** (nouveau, E13) — déplacer la collecte
   d'état `GradleService.etat` vers une portée process (étape C/D) pour
   que `ToolingService` ne décide plus sur un état gelé après la
   fermeture de l'éditeur.

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
| Éditeur → Terminal → retour | Session conservée ; état inchangé ; aucun doublon de surveillance | Conservée ✓ (activité dessous) ; sessions terminal GLOBALES (E11) ; reprise idempotente ✓ | E (politique §2.4) |
| Éditeur → Paramètres → retour | Session conservée ; réglages appliqués | **PARCOURS INEXISTANT** (P3) ; propagation des réglages déjà fonctionnelle par les 3 canaux | E (ajouter l'entrée) |
| Éditeur (P) → Accueil → Éditeur (Q) | P fermée AVANT Q ; aucun état de P visible dans Q | P fermée (nominal) ✓ ; console repart vierge ✓ MAIS #2 #4 serveur gardent P (E9) ; cas dégénéré : 2 sessions (E7/I1) | B, D |
| Rotation dans l'éditeur | Aucune fermeture ; aucune ressource dupliquée | Session conservée ✓ (I3 côté session) ; #11 recréé+reconnecté (E8) ; aucune duplication ✓ | C (garde) ou §2.5a |
| Éditeur → arrière-plan → retour | Session conservée ; reprise propre ; caches éventuellement reconstruits | Conservée ✓ ; RIEN ne coupe en arrière-plan (E16 : pas de temporisation, pas de `onTrimMemory`) | C + §2.3 |
| Mort du processus → restauration | Ouverture propre ; aucune ressource fantôme | Restauration workspace + modèle LSP ✓ (v0.102.0) ; daemon relancé par `Application.onCreate` ✓ ; TerminalService sticky repart et s'arrête seul ✓ ; process enfants : à vérifier (S7) | mesure, puis E |
| Notification tooling (X) pendant que Y est ouvert | Comportement défini et testé | Retour à l'ACCUEIL (E10) — ni X ni Y ; le cas dégénéré P4 existe | E |
| Build en cours → quitter | Selon la décision §2.1 | (c) DE FACTO : build continue (singletons), notification maintenue par #9, console conserve le rejeu | valider §2.1c |

### 4.5 Protocole de mesures de référence sur appareil (AVANT tout correctif)

Conditions : build **debug** v0.102.0 (LeakCanary actif), appareil du
propriétaire (moto g06, Android 15 / API 35), projet réel multi-gradle
(P) et un second (Q), 10 min de repos avant le premier relevé, écran
allumé sauf S6. Chaque scénario note l'heure de début/fin et le journal
(`adb logcat` filtré `App|Editor|LspEditeur|LspEditeurSession`,
`GradleServer`). Relevés communs : `adb shell dumpsys meminfo
jo.codeide` (Java heap, native heap, TOTAL), `adb shell ps -A -o
PID,PPID,NAME | grep -i codeide` (processus enfants : orchestrateur
`java`, démons Gradle `GradleDaemon`, shells) — consigner le tableau
ci-dessous.

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
