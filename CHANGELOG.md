# Journal des modifications

## [0.89.0] – 2026-10-11

### Ajouté

- **Étiquettes de l'historique local — utilisateur et système**
  (mission « Historique local » H4, spec HISTORIQUE_LOCAL.md § 3/§ 5,
  ADR 0106 § d) :
  - **Bouton « Poser une étiquette »** dans l'en-tête de la feuille
    Historique : dialogue au nom libre (borné à 60 caractères), posé
    sur la PORTÉE COURANTE — le fichier (mode fichier), le dossier
    LUI-MÊME (mode dossier), le projet entier (mode projet) ; nom
    vide ou blanc : AUCUN effet (l'étiqueteur nettoie, la feuille
    reste muette).
  - **Une étiquette est une ENTRÉE sans contenu** : elle paraît en
    tête des révisions et marque un instant — les révisions voisines
    se lisent « avant / après l'étiquette », comme le Local History
    d'IntelliJ. Le libellé d'une étiquette SYSTÈME est une DONNÉE
    (visible dans toute langue d'interface), les libellés
    utilisateur sont libres.
  - **Étiquette système « Avant compilation »** : le bouton
    « Exécuter » pose silencieusement l'étiquette avant de compiler
    (le Run est une action risquée — le filet marque l'état d'avant ;
    jamais bloquant, jamais visible) — point d'extension des futures
    actions risquées (bascule de branche Git, remplacement
    multi-fichiers).
- **`EtiqueteurHistorique`** (core:domain) : cas d'usage du domaine —
  les actions risquées l'appellent, jamais l'inverse (pas de
  dépendance au module historique).
- **Rendu des étiquettes** : en modes dossier/projet, une étiquette
  porte son NOM en libellé principal (le chemin d'une étiquette de
  projet est vide — le nom de fichier serait muet).

### Modifié

- `HistoriqueLocal.listerRevisionsSous` : le préfixe couvre AUSSI le
  dossier LUI-MÊME — une étiquette posée SUR un dossier paraît dans
  SON historique (moteur + faux de test alignés).
- `HistoriqueViewModel` : constructeur + `EtiqueteurHistorique`
  (H4) ; message « Étiquette posée ».

### Tests

- Moteur : une étiquette posée sur le dossier est une entrée de son
  historique ; `EtiqueteurHistoriqueTest` (4 : nom nettoyé, vide
  refusé, entrée visible, étiquette système).
- ViewModel : 4 tests H4 (étiquette fichier + rafraîchissement,
  dossier lui-même, projet entier, nom vide muet) ; Run : l'étiquette
  « Avant compilation » est posée au lancement (silencieuse).
- Recette manuelle : scénarios Y13-Y16 de `docs/TESTS_MANUELS.md`.

## [0.88.0] – 2026-10-11

### Ajouté

- **Historique de dossier, fichiers supprimés et Modifications
  récentes** (mission « Historique local » H3, spec
  HISTORIQUE_LOCAL.md § 5) :
  - **Modes dossier et projet de la feuille Historique** : « Afficher
    l'historique » sur un dossier de l'explorateur (préfixe STRICT —
    `src` ne couvre pas `srcX/`), et « Modifications récentes » sur la
    racine (tout le projet) ; rangées au NOM DU FICHIER, diff et
    restauration inchangés ; l'arbre PRIVÉ n'est pas capturé (ADR 0106)
    — pas d'entrée d'historique (fichiers, dossiers, onglets privés).
  - **Filtre « Supprimés seuls »** : les pierres tombales (fichiers
    supprimés retrouvables) d'un dossier ou du projet, en un geste.
  - **Recréation d'un fichier supprimé** : restaurer une pierre
    tombale recrée le fichier dans son dossier d'origine — dossier
    parent résolu segment par segment par `ResolveurCheminHistorique`
    (énumération des parents, JAMAIS d'URI SAF construite — leçon
    v0.64.0) ; impasse honnête si le parent a disparu ; snackbar
    « Fichier recréé » avec Annuler (resuppression immédiate) — la
    restauration reste annulable par construction.
  - **Diff « vs précédente » par chemin** : en mode dossier, la
    révision de comparaison est la PLUS ANCIENNE du MÊME fichier (les
    révisions d'un dossier mélangent les fichiers).
- **`HistoriqueLocal.listerRevisionsSous`** (port + moteur) : révisions
  sous un préfixe, bornées (200), plus récentes d'abord ; chemin vide =
  projet entier.
- **Recette manuelle** : section « Historique local » de
  `docs/TESTS_MANUELS.md` — scénarios Y1-Y12 (fichier, restauration,
  annulation, dossier, récentes, filtre, tombale, parent disparu,
  indépendance Git, secrets).

### Modifié

- Le menu contextuel d'onglet n'offre l'historique qu'aux onglets du
  projet (source Projet — les onglets privés n'ont pas d'historique) ;
  l'entrée CHANGELOG 0.87.0 laissée « (à compléter) » par erreur est
  remplie.

## [0.87.0] – 2026-10-10

### Ajouté

- **Feuille « Historique » d'un fichier + diff unifié + restauration**
  (mission « Historique local » H2, spec HISTORIQUE_LOCAL.md § 5,
  maquette historique-local.html) : révisions groupées par période
  (Aujourd'hui/Hier/Plus ancien), moments relatifs (« il y a 4 min »,
  « hier 14:02 », date complète), type, libellé, taille ; état vide
  honnête ; entrées par le popover de l'explorateur et le menu
  contextuel d'onglet.
- **Diff unifié de la révision contre le CONTENU ACTUEL ou la révision
  PRÉCÉDENTE (bascule)** : `DiffUnifie` (core:domain, pur) — rognage
  préfixe/suffixe puis Myers O((N+M)·D) borné 4096 éditions, repli
  honnête ; rendu monospace, marqueurs +/-/espace, fonds vert/rouge
  translucides (partagé avec la future vue Git).
- **Restauration avec confirmation** : écriture par le port DÉCORÉ (la
  version d'avant part automatiquement à l'historique — annulable par
  construction, ADR 0106) ; snackbar « Version restaurée » + action
  Annuler (réécriture immédiate) ; contenu indisponible honnête
  (binaire/trop grand), échec d'écriture signalé.
- `ActionEditor.RemplacerContenuFichier` : l'onglet ouvert suit le
  texte restauré (session remplacée, PROPRE, auto-sauvegarde en
  attente ANNULÉE — sinon elle écraserait la restauration).

### Modifié

- 26 nouveaux tests : `DiffUnifieTest` 10 (reconstruction exacte),
  `CalculsDatesHistoriqueTest` 8, `HistoriqueViewModelTest` 8 ;
  `FakeHistoriqueLocal` + contenus par identifiant.

## [0.86.0] – 2026-10-10

### Ajouté

- **Tests du service Binder du pont de journaux** (mission « Exécuter »
  R5 — critère § 6.5 « rejet d'un émetteur non autorisé (UID) testé ») :
  `ServicePontJournauxTest` (Robolectric + Hilt, graphe de production)
  pilote l'UID/PID appelants par `ShadowBinder` — paquet inconnu refusé
  sans session, UID USURPÉ refusé (l'appelant prétend être le paquet de
  l'IDE, le noyau dit autre chose), protocole inconnu refusé, lot SANS
  connexion préalable ignoré, connexion légitime ouvre la session et
  alimente le registre, lot de pertes seules compté, débranchement
  termine avec la raison.
- **Recette manuelle de la mission** : section « Exécuter » de
  `docs/TESTS_MANUELS.md` — 15 scénarios X1-X15 (Run, autorisation,
  Logcat vivant, filtres/regex/niveau, pause/reprise, effacement,
  sélecteur, mort, plantage + trace cliquable, réglage coupé, pertes).

### Modifié

- `VerificateurUidPont` (créé R2, resté non câblé) est désormais
  réellement utilisé par `ServicePontJournaux.connecter` — plus de code
  mort, la décision d'authenticité est une seule pièce.

## [0.85.0] – 2026-10-10

### Ajouté

- **Traces cliquables et plantages** (mission « Exécuter » R4, spec
  EXECUTER.md § 4.3) : les piles `Fichier.kt:12` de l'onglet Logcat
  deviennent NAVIGABLES, comme Android Studio :
  - **Cadres cliquables** : l'analyseur pur `LignesPile` extrait les
    cadres `.kt`/`.java` (sources inconnues, méthodes natives et
    fichiers non éditables ignorés) ; un appui simple sur une ligne
    portant une pile ouvre le SÉLECTEUR de cadres (la trace arrive en
    UN message multi-lignes — l'équivalent honnête du clic cadre par
    cadre) ; chaque choix saute au fichier source dans l'éditeur
    (défilement + curseur à la ligne, même discipline que le saut au
    diagnostic).
  - **Résolution des fichiers** : onglet ouvert → sélection (suffixe
    paquet du cadre, chute par nom de fichier) ; sinon sondes bornées
    (au plus 4 requêtes SAF) des emplacements standards
    `app/src/main/java|kotlin/<paquet>` — jamais de balayage complet.
  - **Snackbar « L'application a planté »** : le pont signale
    l'exception non interceptée (étiquette `Plantage`) — l'activité
    montre le snackbar UNE fois par session morte, l'action « Voir la
    trace » ouvre l'onglet Logcat sur la session morte et soulève la
    feuille si repliée.
- `ActionEditor.SauterVersLigneSource` + `EffetEditor.DefilementVersLigne`
  (saut après re-branchement de la vue, partagé avec le saut au
  problème) ; ViewModel Logcat porté à portée ACTIVITÉ (snackbar
  visible même replié) ; 4 chaînes fr/en.

### Tests

- `LignesPileTest` (7, JVM pur) : cadres kt/java retenus, sources
  inconnues/natives ignorées, non éditables ignorés, trace complète en
  ordre, lignes non positives, détection du plantage du pont.
- `SautPileLogcatEditorViewModelTest` (3) : onglet ouvert → sélection +
  effet, résolution par candidats + ouverture + saut, cible introuvable
  → rien.
- `LogcatViewModelTest` (+1) : le plantage paraît dans l'état, une
  seule fois par session.

## [0.84.0] – 2026-10-10

### Ajouté

- **Onglet Logcat** (mission « Exécuter » R3, spec EXECUTER.md § 4,
  ADR 0103/0107) : le vrai Logcat des applications exécutées coule
  maintenant dans l'IDE, **sans adb** — 4e onglet du panneau inférieur,
  façon Android Studio :
  - **Barre d'outils** : sélecteur de processus (puce + ampoule verte
    vivante, menu des sessions vivantes / terminées / précédentes),
    filtre texte (sous-chaîne insensible à la casse sur message ET
    étiquette), option **regex** (motif invalide signalé en ligne,
    jamais de crash), filtre de **niveau minimal** (Verbose → Assert,
    masque les inférieurs), **pause** du défilement (fige l'affichage,
    la collecte continue — reprise = rattrapage), **effacer** (vide
    l'affiché, les sessions gardées restent).
  - **Table monospace 11 sp** virtualisée (`ListAdapter` + DiffUtil,
    identité = numéro de ligne strictement croissant, couleurs de
    niveau V/D/I/W/E/F jetons § 4.2 jour/nuit) — défilement automatique
    HONNÊTE (un doigt qui touche arrête le suivi, revenir au fond le
    reprend), clic long = copie de la ligne brute au format logcat.
  - **Bandeaux d'état jamais silencieux** : « N lignes perdues »
    (orange), « Le processus s'est arrêté : raison » (rouge), « Session
    précédente » (grise, consultable), erreur de motif regex.
  - **Sessions précédentes archivées** entre deux démarrages de
    CodeIDE (JSON borné à 6, atomique, stockage PRIVÉ `filesDir/logcat`
    — identité, raison de fin, compteurs et DERNIÈRE ligne seulement ;
    un historique complet sur disque serait un trou de confidentialité
    que personne n'a demandé).
  - **Rattrapage incrémental exact** (ADR 0107) : les lots du registre
    portent leur position (`LotJournal.apres`) et le port gagne
    `lignesDepuis(id, position)` — l'afficheur rattrape par delta sous
    le verrou du registre : **aucune ligne perdue, aucune doublon**,
    filtrage incrémental (aucune allocation en rafale par lot),
    pause/reprise triviales. Corrige au passage la course
    instantané/abonnement du flux de lots.
- `editor_panneau_logcat` + 30 chaînes fr/en (barre, bandeaux, niveaux,
  menus, copie) ; 9 jetons de couleur jour/nuit ; 5 drawables
  (ampoule, fonds d'outil et d'état vide, pause, niveaux).

### Modifié

- `OngletPanneau` gagne `LOGCAT` (4e onglet, ordre des `TabItem`
  strictement aligné) ; l'en-tête du panneau porte le sous-titre de
  périmètre (« Journaux de vos applications, sans adb » — politique
  d'honnêteté § 5), sans badge.

### Tests

- `FiltreLogcatTest` (7, JVM pur) : niveaux, sous-chaîne message +
  étiquette, regex, motif invalide signalé, liste entière.
- `RegistrePontJournauxTest` (12, +3) : position du lot, delta
  intermédiaire/à jour/trop vieux, session inconnue.
- `LogcatViewModelTest` (10) : autosélection, choix qui prime, vivante
  qui déloge une archive, pause/reprise rattrapée, effacement local,
  filtres, erreur de motif, archivage unique avec dernière ligne,
  pertes.
- `ArchiveSessionsJournalFichiersTest` (8, Robolectric) :
  aller-retour JSON, borne 6, ordre, ré-archive, corruption lue comme
  vide, atomicité (pas de résidu `.tmp`), niveau inconnu replié.
- `PanneauToolingControllerTest` (+1) : Logcat = ligne tooling éteinte
  (règle des onglets non-Console). `ActivityEditorLayoutTest` : 4
  TabItem, le 4e est Logcat.

## [0.83.0] – 2026-10-10

### Ajouté

- **Pont de journaux des applications exécutées** (mission « Exécuter »
  R2, ADR 0103) : les applications lancées depuis CodeIDE font remonter
  leurs journaux dans l'IDE **sans adb, sans permission, sans
  dépendance ajoutée** — la plomberie de l'onglet Logcat (R3). Trois
  briques :
- **`applog-runtime` — la bibliothèque injectée** (module NOUVEAU, Java
  pur, zéro dépendance — pas même kotlin-stdlib, Java 8, minSdk 21) :
  amorcée par un `ContentProvider` neutre fusionné dans le manifeste
  cible ( démarre AVANT l'`Application`, les initialiseurs statiques
  sont donc vus) ; **morte hors debug** (`FLAG_DEBUGGABLE` vérifié à
  l'exécution — même fuitée dans un release, elle ne fait rien) ;
  elle lit le logcat de **SON PROPRE processus** (`logcat -v
  threadtime --pid=<pid>`, repli filtrage côté lecteur, reprise sans
  doublon ni trou par `-T <epoch>`), reflète `System.out/err`,
  intercepte les exceptions non interceptées, annonce la fin du
  processus PRÉCÉDENT (`ApplicationExitInfo`, API 30+) et expédie le
  tout par lots Binder **oneway** ; anneau borné (5 000 trames) —
  les débordements sont COMPTÉS et signalés, jamais perdus en
  silence ; trames tabulaires « message = reste » (4 000 car.
  max, tronquage marqué), analysées défensivement côté IDE.
- **`ServicePontJournaux` — le récepteur Binder côté IDE** (`app`) :
  exporté SANS permission (une permission `normal` serait
  auto-accordée à tout demandeur — le défaut mesuré d'AndroidIDE) ;
  l'authenticité est l'**UID du noyau** : `Binder.getCallingUid()`
  comparé à l'UID du paquet DÉCLARÉ à chaque connexion (un faux nom
  de paquet ne passe pas), et chaque lot est refusé sans connexion
  préalable validée ; `linkToDeath` termine la session quand
  l'application meurt ; le service vit le temps des connexions (les
  journaux coulent pendant que l'utilisateur regarde SON app, IDE en
  arrière-plan). AIDL jumelles `ILiaisonJournaux` (connecter /
  envoyerLot / deconnecter, oneway) + `IControlePont` (veille) des
  deux côtés.
- **Injection Gradle propre** (`tooling/server`) : le serveur génère
  un script d'init (`--applog-repo <chemin>`, AAR déployé en dépôt
  maven local `filesDir/applog-repo` par le daemon) qui n'utilise que
  des API PUBLIQUES de Gradle — `beforeSettings` (dépôt local au
  niveau des RÉGLAGES, compatible `RepositoriesMode.
  FAIL_ON_PROJECT_REPOS`) et ajout de `jo.codeide:applog-runtime`
  aux `*RuntimeClasspath` **debug uniquement** (PAS de conversion
  vers les classes internes d'AGP — la casse mesurée chez
  AndroidIDE) ; **interrupteur honnête** :
  `codeide.applog.isEnabled=false` dans le `gradle.properties` du
  projet coupe l'injection pour CE projet (l'utilisateur garde le
  dernier mot sur SON build). Prouvé par des tests d'intégration sur
  un VRAI Gradle (projet témoin hostile : `FAIL_ON_PROJECT_REPOS`,
  variantes nommées AGP — dépendance présente en debug, absente du
  release et des tests).
- **Réglage « Journaux des applications exécutées »** (écran de
  configuration de la chaîne d'outils, défaut ACTIF) : ajoute une
  bibliothèque de débogage à vos builds debug — le libellé le DIT ;
  coupé = aucun argument, aucun script, les builds restent intacts ;
  s'applique à la prochaine vie du process orchestrateur (dit tel
  quel).
- **Port `PontJournauxApplications` + `RegistrePontJournaux`**
  (`core:domain`, JVM pur) : sessions (paquet, pid, nom de
  processus) en `StateFlow`, lots de lignes en flux, instantané borné
  (5 000 lignes), pertes comptées des deux côtés — la base pure de
  l'onglet Logcat (R3), testable sans Android.
- **Artefacts livrés en assets** : l'AAR release + un POM écrit à la
  main (AUCUNE dépendance) sont recopiés vers `assets/applog/` par
  `copierAarVersAssets` (miroir de la convention du JAR
  orchestrateur, ADR 0040 — jamais versionnés, contrôlés par
  `controlerAarAssets` branché sur `preBuild` : aucun APK sans la
  bibliothèque).
- **Tests** : `TramesTest` + `AnneauTramesTest` +
  `LecteurLogcatParseTest` (applog-runtime) ;
  `AnalyseurTramesJournalTest` (7 — trames hostiles, géantes,
  tabulations internes) + `RegistrePontJournauxTest` (9 — sessions,
  pertes, tampon borné, processus multiples) (core:domain) ;
  `DepotAppLogDeployerTest` (4 — disposition maven exacte, marqueur,
  redéploiement) + 3 tests `DaemonManagerTest` (`--applog-repo`
  présent/dépendant du réglage, paire d'arguments)
  (tooling:daemon) ; `GenerateurScriptAppLogTest` (6) +
  `ScriptAppLogIntegrationTest` (2 — VRAI Gradle)
  (tooling:server).

## [0.82.0] – 2026-10-10

### Ajouté

- **Historique local — le filet de sécurité indépendant de Git**
  (mission H H0+H1, ADR 0104/0105/0106) : CodeIDE enregistre
  **automatiquement** les versions successives des fichiers du projet
  ouvert. Toute écriture, suppression, renommage ou création passant
  par le port `FileSystem` laisse une entrée — **sans qu'aucun appelant
  ne change** (décorateur `HistoriqueFileSystem` branché sur la liaison
  SAF dans `core:storage`).
- **Stockage à intégrité prouvée** (ADR 0104) : blobs adressés par
  empreinte SHA-256 (contenu identique = stocké UNE fois —
  déduplication naturelle) + index JSON réécrit **atomiquement**
  (`.tmp` + renommage : la mort du processus ne corrompt jamais) dans
  le stockage PRIVÉ (`files/historique/<empreinte-du-projet>/`) —
  jamais dans le dossier du projet, un dossier par projet.
- **Contenu AVANT + pierres tombales** (modèle IntelliJ établi dans le
  code) : chaque entrée conserve l'état PRÉCÉDENT (l'état courant vit
  sur le disque, jamais doublé) ; un fichier **supprimé reste
  retrouvable et lisible** dans l'historique ; une entrée EXTERNE
  capture les changements hors application (terminal, git) détectés à
  l'ouverture.
- **Politique mobile honnête** (ADR 0105) : 5 jours de rétention, **256
  Mo** de quota par projet (les entrées les plus anciennes sacrifiées
  d'abord, blobs orphelins supprimés), 2 Mo par fichier (au-delà :
  entrée sans contenu — « indisponible », jamais inventé), 5 000
  entrées ; **secrets jamais historisés** (`local.properties`, `*.jks`,
  `*.env`, `*.pem`…), artefacts exclus (`build/`, `.gradle/`, `.git/`,
  `.codeide/`) ; purge à l'ouverture du projet (E/S, jamais sur le fil
  principal) ; **aucune entrée si le contenu ne change pas**.
- **Tests** : `MoteurHistoriqueLocalTest` (14 — va-et-retour,
  déduplication, anti-bruit, pierre tombale, purge par âge/quota/nombre,
  blobs orphelins, index corrompu → historique vide, projets séparés,
  hors projet) + `HistoriqueFileSystemTest` (9 — capture de toutes les
  mutations, contenu AVANT, renommage avec ancien nom, secrets et
  `build/` exclus, échec d'écriture sans entrée, hors projet) —
  intégration RÉELLE sur dossiers temporaires.
- **H0 livré** : ADR 0104/0105/0106, `docs/HISTORIQUE_LOCAL.md`,
  maquette `docs/preview/historique-local.html` (comportement d'Android
  Studio établi dans le code source : 5 jours, contenu avant, revert
  annulable, étiquettes colorées — ré-implémentation intégrale).

## [0.81.0] – 2026-10-10

### Ajouté

- **« Exécuter l'application » — le bouton Run d'Android Studio, sans
  adb** (mission « Exécuter » R0+R1, ADR 0102/0103) : sur un projet
  possédant un module application Android (`app/build.gradle(.kts)` —
  détection à l'ouverture), le bouton Exécuter devient le runner
  d'Android Studio : **compile** (`:app:assembleDebug`, chaîne Gradle
  existante), **attend le verdict du build**, **installe l'APK produit**
  (`PackageInstaller` — session `MODE_FULL_INSTALL`, copie 8 Ko +
  `fsync`, commit par `PendingIntent` mutable vers un récepteur NON
  exporté à action unique par session), puis **lance l'application**
  (intent explicite, 10 relances × 200 ms — le `PackageManager` peut ne
  pas voir le paquet juste après l'installation). Les projets JVM
  conservent `gradle run` (fun main) — le même bouton, le bon runner.
- **Permission « sources inconnues » guidée avec reprise automatique** :
  la première installation ouvre l'écran système
  (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`) et l'installation REPREND
  D'ELLE-MÊME au retour (attente bornée 5 min, poll 300 ms) — le Run
  n'est jamais perdu ; `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
  dès Android 12 : les mises à jour peuvent devenir silencieuses si le
  système l'accepte.
- **Échecs typés en français avec action correctrice** (console BUILD +
  snackbar) : signature différente → « Désinstaller… » (boîte système
  nommant la perte de données), version plus récente installée, espace
  insuffisant, APK introuvable, identifiant illisible
  (`output-metadata.json`), annulation, message système brut sinon.
- **Progression du runner en français dans la console** (canal BUILD) :
  compilation, installation, confirmation système, application lancée —
  `GradleService.publierLigneExecution` (libellés localisables, styles
  ETAPE/ERREUR).
- **Port `ApkInstaller`** (`core:domain`, faux dans `core:testing`) +
  implémentation Android (`app`, process principal — le lancement
  d'activité est illégal depuis un service d'arrière-plan, Android 10+)
  + `ExecuterApplicationUseCase` (chemin d'APK déterministe —
  `verify-templates.sh`, `applicationId` lu des métadonnées AGP,
  **aucun changement du protocole tooling**). Manifeste :
  `REQUEST_INSTALL_PACKAGES` (permission restreinte Play — impact
  documenté dans l'ADR, distribution actuelle hors Play) + `<queries>`
  MAIN/LAUNCHER pour la visibilité du paquet installé.
- **Tests** : `ExecuterApplicationUseCaseTest` (7 — cycle complet, APK
  absent, métadonnées hostiles, échec typé + action, détection module,
  analyseur JSON pur) ; `ExecuterApplicationEditorViewModelTest` (3 —
  build→install→lance avec couture FUSE vers un dossier RÉEL, échec de
  build sans installation, détection du module) ; `FakeApkInstaller`.
- **R0 livré** : ADR 0102 (installation), ADR 0103 (pont de logs Binder
  bidirectionnel, zéro permission ajoutée — R2),
  `docs/EXECUTER.md` + maquette `docs/preview/executer-logcat.html`.

## [0.80.7] – 2026-10-10

### Corrigé

- **`core:bootstrap`** : **section Git figée sur « Ce projet n'est pas
  un dépôt Git » malgré un dépôt cloné ou initialisé** (retour
  utilisateur, récurrent depuis la v0.80.4). Cause racine ENFIN
  trouvée et prouvée : `MoteurGitCli.executer` collectait
  `stdoutLines()` une **seconde** fois après le drainage de
  `SupervisionProcessus.attendre` — or les flux du port sont froids et
  **consommables une seule fois** (le lecteur referme le tuyau à
  l'EOF). Sur un appareil réel, la seconde collecte rendait un stdout
  VIDE : `git rev-parse --is-inside-work-tree` sortait bien `true`
  mais `estDepot` répondait FAUX pour TOUT dépôt existant — la section
  restait figée sur « pas un dépôt », pendant que le clonage (qui ne
  lit que le code de sortie) réussissait. Les faux de test rejouent
  leurs flux à l'infini (`asFlow()` d'une liste) : tous les tests
  existants étaient verts et masquaient le bug. Correctif :
  `SupervisionProcessus.Sortie` porte désormais la sortie standard
  capturée pendant l'unique drainage (`sortieStandard`), et
  `MoteurGitCli.executer` lit cette capture — plus AUCUNE seconde
  collecte. Preuve rouge/vert : quatre nouveaux tests sur de VRAIS
  sous-processus JVM (`MoteurGitCliFluxUniqueTest` — « git » factice
  scripté + vrai git de la machine) échouent avec l'ancien code
  (stdout vide : 3 échecs) et passent avec le correctif. Au passage,
  toute la lecture du stdout git devient fiable : journal des commits,
  branches, branche courante, diff, stash — pas seulement `estDepot`.

## [0.80.6] – 2026-10-10

### Corrigé

- **`app` + `core:ui`** : **l'hôte de navigation et tous ses fragments
  (accueil, assistant, paramètres, nouveau projet, installation,
  diagnostic) n'appliquaient NI la palette choisie NI les couleurs
  dynamiques** — seuls `EditorActivity`, `TerminalActivity` et
  `CrashActivity` suivaient le réglage (retour utilisateur : « à part
  l'écran EditorActivity et CrashActivity, tous les autres écrans
  n'utilisent pas le thème ou palettes de couleurs choisies »). Cause
  racine : `installSplashScreen()` — appelé uniquement par
  `MainActivity` — résout `postSplashScreenTheme` puis appelle
  `Activity.setTheme()` en interne, ce qui repart d'un thème NEUF et
  EFFACE l'overlay d'apparence posé juste avant la création par
  `AppliquerApparence.onActivityPreCreated` ; les activités sans écran
  de démarrage gardaient, elles, leur overlay. Correctif :
  `AppliquerApparence.rappliquer(activity)` — l'hôte repose l'overlay
  immédiatement après `installSplashScreen()`, AVANT
  `super.onCreate()`/`setContentView()`, pour que le contenu se gonfle
  avec les couleurs réellement choisies (test d'intégration qui lance
  `MainActivity` avec une palette BLEU : échoue sans le correctif,
  passe avec).

- **`core:ui`** : distinction honnête dynamique/palette — un réglage
  « couleurs dynamiques » activé sur un appareil SANS Material You
  (Android < 12, ou fabricant non supporté par Material avant Android
  13) n'appliquait RIEN : ni couleurs dynamiques, ni palette statique,
  pendant que l'écran Apparence griséait le sélecteur de palette sur
  la foi du seul réglage. `AppliquerApparence` applique désormais les
  couleurs dynamiques seulement si l'appareil les SUPPORTE
  (`DynamicColors.isDynamicColorAvailable`) et retombe sinon sur la
  palette statique ; l'écran Apparence (même règle) ne désactive les
  rangées de palette que si les couleurs dynamiques sont réellement
  applicables — l'écran affiche ce qui s'applique vraiment, et la
  palette redevient utilisable sur les appareils sans Material You.

### Ajouté

- `AppliquerApparence.rappliquer(activity)` — API publique de
  ré-application de l'état coloré pour une activité qui vient de
  remplacer son propre thème (`installSplashScreen`) ; no-op tant que
  le point d'application n'est pas installé dans le processus.
  3 tests nouveaux (`AppliquerApparenceTest` : re-teinte après
  remplacement de thème, repli palette quand dynamiques
  indisponibles ; `ApparenceIntegrationTest` : palette appliquée à
  l'hôte après l'écran de démarrage).

## [0.80.5] – 2026-10-10

### Corrigé

- **`feature:editor`** : **la section Git du tiroir reste figée sur
  « Initialiser un dépôt » après un clonage** (retour utilisateur :
  « j'ai cloné un dépôt et la section git du drawer ne s'est pas mise
  à jour, elle affiche encore l'option initialisé »). Le statut Git
  était une photographie prise UNE fois à l'ouverture de l'éditeur :
  un dépôt apparu ensuite (clone depuis l'accueil, `git init` ou
  `git clone` dans le terminal) restait invisible indéfiniment. La
  section est maintenant VIVANTE comme la fenêtre Git d'Android
  Studio, par trois mécanismes superposés : (1) sélectionner l'onglet
  Git recharge l'état (`onHiddenChanged` — la fenêtre se rafraîchit à
  la prise de focus, le rattrapage soigne aussi la course FUSE d'un
  clone tout juste terminé) ; (2) une sonde discrète balaye le dossier
  projet toutes les 2 s — DEUX stats de fichiers, AUCUN processus git
  lancé : existence de `.git`, horodatages de `.git/HEAD` et
  `.git/index` ; un changement de signature (dépôt créé, commit,
  checkout, add) déclenche un rechargement complet, arrêté quand
  l'éditeur n'est pas visible ; (3) le bouton d'actualisation reste
  pour les modifications simples du worktree (contenu seul). Le
  ViewModel reçoit le port `ResolveurCheminFuse` (plus la classe
  concrète) et un `DispatcherProvider` — la sonde ne touche jamais le
  disque sur le fil principal. 8 nouveaux tests
  (`GitViewModelTest`), dont le scénario exact du retour : dépôt créé
  APRÈS l'ouverture → la zone « initialiser » laisse place au corps
  Git sans rouvrir l'éditeur.
- **`core:bootstrap`** : **git installé après le démarrage restait
  introuvable jusqu'au redémarrage de l'application** —
  `MoteurGitCli` gelait le chemin du binaire (`$PREFIX/bin/git`
  présent ou absent) à la création du singleton Hilt : installer git
  via `pkg install git` dans le terminal ne devenait utilisable qu'en
  tuant le processus. Le moteur reçoit désormais un RÉSOLVEUR appelé à
  chaque exécution (une stat de fichier par commande git, un coût
  invisible devant le lancement du processus) — le binaire fraîchement
  installé est découvert immédiatement, comme la fenêtre Git
  d'Android Studio découvre le sien. 3 nouveaux tests
  (`MoteurGitCliBinaireDynamiqueTest` : installation tardive
  utilisable, disparition/réapparition, `estDepot` dynamique).

### Notes techniques

- Portée du correctif : `feature:editor` (GitViewModel, GitFragment)
  et `core:bootstrap` (MoteurGitCli, MoteurGitModule) — les modules
  touchés seuls, conformément à la directive de vérification ciblée
  (AGENTS.md, v0.79.0).

## [0.80.4] – 2026-10-10

### Corrigé

- **`feature:editor`** : **crash de la feuille des tâches depuis le
  tiroir Projet** (rapport a6d72e9d, `IllegalStateException:
  Fragment FeuilleTachesFragment does not have any arguments`).
  L'onglet « Tâches » instançait `FeuilleTachesFragment()` SANS
  arguments — `requireArguments()` tuait l'activité dès
  `onViewCreated`. Le bouton passe désormais par l'action
  `OuvrirSelecteurTaches` de l'`EditorViewModel` d'activité, exactement
  comme le bouton Tâches de la console : cache de sync d'abord, repli
  listage orchestrateur, échec avec « Réessayer » — la feuille est
  toujours créée par sa fabrique `creer(taches, recents)` avec ses
  arguments. En profondeur, la feuille tolère un paquet d'arguments
  absent (`arguments` au lieu de `requireArguments`) : l'état vide
  « synchronisation en cours » remplace le crash.
- **`feature:editor`** : **« Initialiser un dépôt » ne fait plus semblant
  de ne rien faire** (retour utilisateur : « j'appuie sur initialiser
  un dépôt rien ne se passe »). `GitViewModel.initialiser()` avalait
  le résultat de `git init` — git absent du bootstrap (« Installez-le
  via pkg install git »), chemin FUSE inaccessible ou stderr de git
  restaient invisibles. L'échec est désormais surfacé (texte d'erreur
  rouge SOUS la zone « pas un dépôt », sortie du ScrollView pour être
  visible dans les DEUX états), la zone laisse place au repère de
  chargement pendant l'opération et le bouton se désactive (plus de
  martèlement pendant un git init en cours).

### Ajouté

- **`feature:home` + `core:domain`** : **bouton Git (clonage) sur
  l'écran d'accueil** — l'équivalent mobile du « Get from VCS »
  d'Android Studio, près du bouton terminal (retour utilisateur,
  ADR 0101). Dialogue URL + nom de dossier (pré-rempli depuis l'URL,
  dernier segment sans `.git`, éditable sans être récrasé), bandeau de
  progression linéaire, clones concurrents bloqués. Le nouveau cas
  d'usage `ClonerDepotUseCase` orchestre : dossier de travail résolu
  (jamais de destination inventée), nom validé par le validateur
  partagé `file-name` (mêmes raisons typées que le wizard), cible
  vérifiée (`VerifyCreationTargetUseCase`), dossier créé via SAF,
  clone via le port `MoteurGit` sur le chemin FUSE (nouveau port
  `ResolveurCheminFuse`, lié à `ResoudreRepertoireProjet` en
  production), registre en dernier. Tout échec postérieur à la création
  DÉCLENCHE LE ROLLBACK (`FileSystem.delete`) et le résultat porte
  l'issue du nettoyage (un résidu n'est jamais silencieux) ; le message
  brut de git (réseau, auth, dépôt introuvable) s'affiche tel quel.
  Succès → projet enregistré (`TemplateId.IMPORTED`, description
  « Cloné depuis un dépôt Git »), marqué ouvert, éditeur ouvert (comme
  Android Studio). `FakeMoteurGit` rejoint `core:testing` (prévu par
  l'ADR 0092) ; 9 tests de cas d'usage + 4 tests ViewModel.
- **`core:domain` + `feature:editor`** : **les scripts de build
  s'ouvrent VRAIMENT depuis l'onglet Scripts** du tiroir Projet — le
  nouveau `ResoudreFichierRelatifUseCase` descend le chemin relatif
  segment par segment (`FileSystem.list`, durcissement `.`/`..`) pour
  traduire `app/build.gradle.kts` en URI de document SAF ; l'effet
  `OuvrirScript` porte l'URI résolue et le fragment l'ouvre via
  l'`EditorViewModel` d'activité (`OuvrirFichier`) — l'onglet
  s'ouvre dans l'éditeur, le snackbar n'est plus qu'un repli
  d'erreur honnête.

## [0.80.3] – 2026-10-10

### Corrigé

- **`feature:editor`** : **toutes les touches du tiroir ne sont plus
  interceptées** (régression v0.80.2, retour utilisateur, ADR 0100).
  Le calque de la poignée (`conteneur_poignee`, plein écran sans
  gravité) vivait DANS le `DrawerLayout` comme enfant de contenu :
  tiroir ouvert, `DrawerLayout.onInterceptTouchEvent` l'identifiait
  comme l'enfant le plus haut sous chaque touche
  (`findTopChildUnder` + `isContentView`, `mScrimOpacity > 0`) et
  interceptait TOUT — l'explorateur, le rail, la recherche et la
  poignée elle-même ne répondaient plus. La racine du layout devient
  un FrameLayout de superposition : le `TiroirPoussantLayout`
  (id inchangé) en dessous, le calque de la poignée AU-DESSUS, hors
  du `DrawerLayout`. Le cheval 50 % tiroir / 50 % zone centrale est
  conservé (translation inchangée), le comportement standard du
  tiroir est restauré de bout en bout (tap sur le liseré pour
  refermer, glissement, verrouillage grand écran), et l'ombre
  d'élévation du tiroir revient (l'élévation forcée à 0 n'a plus
  d'objet). Nettoyage : le bypass `drawChild` et
  `setConteneurPoignee` de `TiroirPoussantLayout` ainsi que les
  `requestDisallowInterceptTouchEvent` de la poignée disparaissent
  (code mort). Deux régressions comportementales exécutent de vraies
  touches : un appui au milieu du tiroir OUVERT atteint désormais le
  conteneur de fragments, et l'appui posé sur le bord est consommé
  par la poignée.

## [0.80.2] – 2026-10-10

### Corrigé

- **`feature:editor`** : la **première section de l'en-tête du panneau
  disparaît aussi quand le sheet est étendu** et la **deuxième section
  ne vit plus que sur l'onglet Console** (retour utilisateur, ADR
  0099). Problèmes et Journal actifs → la ligne tooling (et sa bande
  de progression) passent GONE : seule la première section porte les
  informations de l'onglet ; sheet ÉTENDU stabilisé → les DEUX
  sections disparaissent complètement et les onglets montent au sommet
  du sheet (comme l'en-tête `ViewFlipper` d'AndroidIDE qui s'efface à
  l'extension) ; pendant le glissement, la place est conservée
  (INVISIBLE sous le seuil du fondu — jamais de saut de hauteur en
  plein geste).
- **`feature:editor`** : **l'état vide ne « saute » plus** après
  l'étirement puis le repli du panneau inférieur (retour utilisateur :
  « on dirait qu'un espace était vide »). La réserve sous la zone
  centrale est désormais CONSTANT (padding bas = peek, à la
  `marginBottom = peekHeight` d'AndroidIDE) : la zone d'édition ne
  change plus de taille entre replié, mi-hauteur et étendu, la vue
  centrée ne re-centre pas à chaque va-et-vient.
- **`feature:editor`** : **la poignée de redimensionnement du tiroir
  chevauche réellement le bord** — moitié de sa largeur (13 dp) SUR le
  tiroir, l'autre moitié SUR la zone centrale (retour utilisateur).
  Elle vit désormais dans `conteneur_poignee`, DERNIER enfant de la
  racine `TiroirPoussantLayout` (dessiné au-dessus du tiroir, servi
  avant lui aux touches), suit le bord image par image (glissement
  d'ouverture/fermeture, redimensionnement, aimants, rotation, RTL
  miroir) et se masque tiroir refermé ; le fond du tiroir est pleine
  largeur (plus de bande de débord transparente de 13 dp).

### Ajouté

- **`core:ui`** : **11 nouvelles icônes de fichiers** parité avec les
  `fileTypes` d'IntelliJ New UI (retour utilisateur : « ajoute
  d'autres icônes comme zip ») — archive (zip, jar, apk, 7z, rar,
  tar, gz, tgz, bz2, xz), image (png, jpg, gif, webp, svg…), html,
  css, js, yaml, shell, sql, csv, police (ttf, otf, woff…), binaire
  (so, dex, class…), jour et nuit. L'icône dossier `nodes/folder.svg`
  sert replié ET déplié (Android Studio New UI n'a pas de variante
  ouverte — seule la flèche tourne) et les icônes de l'arbre ne sont
  PLUS filtrées par couleur : les teintes officielles s'affichent
  telles quelles.

## [0.80.1] – 2026-10-10

### Corrigé

- **`feature:editor`** : l'explorateur de fichiers **se met enfin à
  jour tout seul** (retour utilisateur : « .gradle n'est pas affiché,
  de même pour app/build et en mode privé »). Trois causes, trois
  correctifs (ADR 0098) :
  - les dossiers de build étaient masqués PAR DÉFAUT sans AUCUNE
    interface pour les afficher — `masquerDossiersBuild` passe à
    `false` (`.gradle/` et `app/build/` visibles, comme l'attend
    l'utilisateur qui suit une sync Gradle) et les trois bascules
    existantes (compactage, fichiers cachés, dossiers de build) sont
    CÂBLÉES dans le popover « Légende » (section « Affichage », trois
    cases à cocher enchaînables sans refermer) ;
  - l'arbre ne rafraîchissait qu'à l'appui sur Actualiser — un
    **balayage périodique** (4 s, démarré à `onStart`, arrêté à
    `onStop`, borné à 25 dossiers en cache, dépliés d'abord) re-liste
    l'arbre AFFICHÉ, compare (URI + type) et reconstruit au
    changement : une création externe (Gradle, terminal, autre
    application) apparaît en moins d'une période, sur les DEUX sources
    (projet SAF et stockage privé) ; un dossier disparu est purgé avec
    ses sous-arbres ; un échec d'accès interrompt discrètement le
    balayage sans toucher l'état ;
  - SAF n'a pas d'observateur de dossier : le balayage comparatif
    traverse le port `FileSystem` et couvre les deux arbres sans
    hypothèse FUSE.
- **`feature:editor`** : le panneau inférieur étendu **ne recouvre plus
  la toolbar ni les onglets de fichiers** (alignement AndroidIDE,
  consultation du code source `EditorBottomSheet.setOffsetAnchor`) :
  `expandedOffset` est posé au haut de la zone d'édition et reposé à
  chaque layout (rotation, onglets qui apparaissent/disparaissent). La
  toolbar (titre du projet, Run, Synchroniser, Tâches) reste visible et
  utilisable console ouverte.
- **`feature:editor`** : **les deux sections de l'en-tête du panneau
  inférieur sont séparées par onglet** (retour utilisateur) :
  - onglet **Console** : la première section (poignée/titre/badge)
    disparaît — la LIGNE TOOLING (pastille de canal, « étape n/N »,
    détail, chrono, bouton Arrêt) EST l'en-tête, son appui bascule
    replié ↔ mi-hauteur ; sans activité tooling, les ONGLETS deviennent
    la poignée repliée du sheet (peek 48 dp) ;
  - onglets **Problèmes / Journal** : la première section reste et
    porte les INFORMATIONS correspondantes — nouveau sous-titre (compte
    de diagnostics / compte d'entrées affichées, pluriels fr/en) et
    badge étendu aux problèmes (compte total de l'état Gradle).
- **`feature:editor`** : le redimensionnement du tiroir **prend en
  compte la zone centrale** — `TiroirPoussantLayout.reevaluerTranslation()`
  applique la formule de poussée (largeur × facteur) à chaque
  changement de largeur (chaque trame du glissement de la poignée et de
  l'animation d'aimant) : le bord de la zone centrale suit le bord du
  tiroir OUVERT redimensionné, au lieu de rester à l'ancienne largeur.

## [0.65.0] – 2026-10-09

### Ajouté

- **`feature:editor`** : C2a + C2b — deux fonctions de l'explorateur
  reprises d'Android Studio.
  - **C2a — Copier le chemin** : nouvelle action `CopierCheminNoeud`
    accessible depuis le popover maison (§ 10) des dossiers et des
    fichiers. Copie le chemin relatif au projet dans le presse-papiers
    système via `EffetEditor.CopierChemin` (déjà existant pour les
    onglets). Utilise `cheminRelatifDe` (qui tient compte de la source
    Projet/Privé, bug A). Le chemin absolu n'est pas accessible (SAF,
    ADR 0003) — seul le relatif est copié, comme le « Copy Path »
    d'Android Studio en mode relatif.
  - **C2b — Tout déplier** : nouvelle action `DeplierTout`, accessible
    par **long-clic** sur le bouton « Replier tout » de l'entête (le
    `contentDescription` mentionne le geste). Garde-fou : si l'arbre
    contient plus de 500 dossiers connus, l'action est refusée avec un
    snackbar (OOM potentiel sur les gros projets). L'énumération est
    récursive et asynchrone (chaque dossier déplié voit ses enfants
    chargés paresseusement). `replierTout` existant inchangé.

  **Modèles** : `ActionEditor.CopierCheminNoeud` et
  `ActionEditor.DeplierTout` ajoutés. `EditorViewModel.copierCheminNoeud`
  et `deplierTout` + `deplierToutRecursif` (suspend). Constante
  `SEUIL_DEPLIER_TOUT = 500`.

## [0.64.0] – 2026-10-09

### Ajouté

- **`feature:editor`** : C1 — section « Gradle Scripts » dans l'explorateur,
  comme Android Studio. Un nœud virtuel de groupe apparaît après les
  enfants de la racine (mode Projet uniquement, jamais en mode Privé),
  pliable/dépliable. Déplié, il liste les fichiers de build du projet
  avec un qualificatif en gris entre parenthèses, dans l'ordre
  d'Android Studio :
  1. `build.gradle[.kts]` → `(Project: <nom>)`
  2. `settings.gradle[.kts]` → `(Project Settings)`
  3. `gradle.properties` → `(Project Properties)`
  4. `gradle/libs.versions.toml` → `(Version Catalog "libs")`
  5. `gradle/wrapper/gradle-wrapper.properties` → `(Gradle Version)`
  6. `local.properties` → `(SDK Location)`
  7. `proguard-rules.pro` → `(ProGuard Rules for ":app")`

  Seuls les fichiers existants sont affichés. Toucher une ligne ouvre
  le vrai fichier en onglet (même onglet que depuis l'arbre classique,
  pas de doublon). Le point d'état des onglets s'applique. L'icône du
  groupe est `ic_gradle` (IntelliJ New UI). Le pli est mémorisé avec le
  reste de l'arbre.

  **Modèles** : `NoeudExplorateur` gagne deux champs
  (`estGroupeGradle`, `qualificatif`). `EditorViewModel.ScriptGradle`
  est un modèle interne (uri, nom, qualificatif). La résolution
  (`resoudreScriptsGradle`) est une fonction `suspend` testable.

  **Tests** : 5 nouveaux dans `GradleScriptsEditorViewModelTest`
  (groupe présent en mode projet, absent en mode privé, dépliage montre
  les scripts avec qualificatif, tap ouvre le fichier, seuls les
  fichiers existants sont montrés). 4 tests existants mis à jour pour
  inclure « Gradle Scripts » dans les listes de nœuds attendues.

## [0.63.0] – 2026-10-09

### Ajouté

- **`core:ui`** : C0 — pack d'icônes officielles d'Android Studio /
  IntelliJ Platform New UI pour l'explorateur. Les drawables maison sont
  remplacés par des **VectorDrawable convertis depuis les SVG** des
  dépôts `JetBrains/intellij-community` (chemin `platform/icons/src/expui/…`)
  et `JetBrains/android` (chemin `artwork/.../filetree`), licences
  Apache 2.0 (mention ajoutée dans `docs/THIRD_PARTY_NOTICES.md`).
  Variantes claire (`drawable/`) et sombre (`drawable-night/`) pour
  chaque type — la convention IntelliJ `X.svg` / `X_dark.svg` est
  respectée.

  **Nouvelles icônes** (13 VectorDrawable, 26 fichiers avec variantes
  nuit) :
  - `ic_fichier_kotlin` (`kotlin/kotlin`), `ic_fichier_gradle_kts`
    (`kotlin/kotlinGradleScript`), `ic_fichier_gradle`
    (`fileTypes/gradle`), `ic_fichier_properties` (`fileTypes/properties`),
    `ic_fichier_toml` (`fileTypes/toml`), `ic_fichier_java`
    (`fileTypes/java`), `ic_fichier_xml` (`fileTypes/xml`),
    `ic_fichier_manifest` (`fileTypes/manifest`), `ic_fichier_markdown`
    (`fileTypes/markdown`), `ic_fichier_json` (`fileTypes/json`),
    `ic_fichier_config` (`fileTypes/config` — repli ProGuard, aucune
    icône Shrinker dédiée trouvée dans IntelliJ), `ic_dossier`
    (`nodes/folder`), `ic_gradle` (`gradle/gradle` — pour C1).

  **`IconesFichiers`** : la résolution reconnaît désormais les noms
  spécifiques d'Android Studio **avant** l'extension générique —
  `build.gradle.kts` → `ic_fichier_gradle_kts` (et non `ic_fichier_kotlin`
  du `kts` brut), `AndroidManifest.xml` → `ic_fichier_manifest` (et non
  `ic_fichier_xml`), `proguard-rules.pro` / `proguard.pro` →
  `ic_fichier_config`.

  **Tests** : 4 nouveaux tests dans `IconesFichiersTest` (gradle.kts vs
  gradle vs kts brut, AndroidManifest.xml vs *.xml, ProGuard/*.pro,
  extensions de base). `docs/EXPLORATEUR_V2.md` § 8 mis à jour avec la
  nouvelle table de correspondance.

## [0.62.0] – 2026-10-09

### Corrigé

- **`feature:editor`** : design de l'arbre — trois problèmes de la
  spécification `docs/EXPLORATEUR_V2.md` § 6.1-6.3 (partie B du prompt
  explorateur).
  - **B1 — fil vertical interrompu sous un dossier déplié** : la ligne
    d'un dossier déplié ne traçait rien entre son chevron et la ligne de
    son premier enfant (le fil des enfants démarrait seulement à
    l'enfant, d'où un trou). `VueGuides` dessine désormais un trait
    vertical supplémentaire au niveau `profondeur + 1`, de sous le
    chevron (16 dp du haut) jusqu'au bas de la ligne, pour rejoindre
    sans coupure le trait du premier enfant. Le flag
    `deplieAvecEnfants` est calculé par `ExplorateurAdapter` :
    `estDossier && deplie && nbEnfants > 0`.
  - **B2 — fond de la ligne sélectionnée trop à gauche** : le drawable
    `fond_ligne_selectionnee` s'étendait sur toute la largeur de la
    ligne, y compris l'indentation vide. Il est désormais inset à gauche
    de `max(0, 22 × profondeur − 10)` dp (le trait fin du parent) via un
    `InsetDrawable` posé par l'adapter selon la profondeur du nœud. La
    barre d'accent (2,5 dp) suit ce nouveau départ car elle est ancrée
    au bord gauche du drawable.
  - **B3 — vue centrale sous le bottom sheet** : le panneau inférieur
    replié recouvrait le bas de l'éditeur (la dernière ligne n'était
    pas visible). `EditorActivity.appliquerReservePanneau` réserve
    désormais en bas de `zone_centrale` la hauteur de peek du panneau
    (`BottomSheetBehavior.peekHeight`) quand il est replié
    (STATE_COLLAPSED), 0 à mi-hauteur/étendu, et laisse le comportement
    IME inchangé quand le clavier est ouvert. Un callback `surPeekChange`
    sur `PanneauToolingController` recale la réserve à chaque changement
    de peek (par ex. quand la ligne de build apparaît).

  **Tests** : 2 nouveaux tests purs dans `CalculsArbreExplorateurTest`
  (formule de l'inset B2, décision du trait de raccordement B1). Les
  tests de layout `ActivityEditorLayoutTest` ne sont pas cassés (le
  drawable de base reste inchangé, seul l'adapter l'wrap dans un
  InsetDrawable).

## [0.61.0] – 2026-10-09

### Corrigé

- **`feature:editor`** : bug A — impossible d'ouvrir un fichier en mode
  explorateur privé (`SourceArbre.PRIVE`). Le tiroir affichait « Ce
  fichier n'a pas pu être lu » (`EffetEditor.ErreurOuverture`) car
  `EditorViewModel.ouvrir` lisait avec `fichiers.readText(uri)` (système
  du projet SAF) au lieu de `systeme.readText(uri)` (qui bascule sur
  `fichiersPrives` en mode privé). Même défaut à la sauvegarde
  (`enregistrer`), au rechargement (`restaurer`) et au calcul du chemin
  relatif (`cheminRelatifDe` utilisait `arbreProjet` au lieu de `arbre`).

  **Correction** : la source (Projet ou Privé) est désormais
  **mémorisée dans l'onglet** (`EditorTabState.source`) à l'ouverture, et
  les opérations ultérieures (sauvegarde, rechargement, chemin relatif)
  choisissent le système de fichiers et l'arbre **par onglet**, pas
  selon l'arbre affiché à l'instant T. Un onglet privé continue de se
  sauvegarder dans le privé même si l'utilisateur rebascule sur
  « Projet ».

  **Persistance** : le format sérialisé passe de `"uri\nchemin"` à
  `"uri\nchemin\nsource"`. Le rechargement tolère l'ancien format
  (migration : `PROJET` par défaut). Les URIs des deux sources utilisant
  des schémas distincts (`content://` vs `prive:///`), la clé de session
  reste l'URI — pas de collision possible.

  **Popover** : l'action « Ouvrir » du popover maison (§ 10.4) est
  désormais activée aussi pour les fichiers privés (elle était
  désactivée par `activee = !noeud.prive`), pour cohérence avec le tap
  simple.

  **Tests** : 5 nouveaux tests dans `ExplorateurPriveEditorViewModelTest`
  (classe dédiée pour rester sous le seuil detekt `LargeClass`) :
  ouverture privée réussie, sauvegarde privée, indépendance des onglets
  après bascule, fichier binaire privé (→ « Ouvrir avec »), échec de
  lecture privé réel (→ `ErreurOuverture`).

## [0.60.1] – 2026-10-09

### Corrigé

- **`core:domain`** : l'URL par défaut du manifeste v2
  (`ToolchainCatalog.DEFAULT_MANIFEST_URL`) pointait vers
  `https://raw.githubusercontent.com/jjoblab/codeide-tools/main/manifest.v2.json`
  — un chemin inexistant à la racine du dépôt (le manifeste v2 est
  committé sous `dist/manifest.v2.json`, et la CI du dépôt
  `codeide-tools` le recopie vers `gh-pages/manifests/v2/latest.json`
  via le workflow `publish.yml`, ADR 0006 du dépôt `codeide-tools`).
  L'étape `resolution-plan` de la phase `ANDROID_SDK` échouait donc en
  **HTTP 404** dès la première exécution, bloquant tout parcours
  d'installation au-delà de la phase 3 (JAVA). L'URL est désormais
  l'URL officielle GitHub Pages
  `https://jjoblab.github.io/codeide-tools/manifests/v2/latest.json`
  (vérifiée : 200, `schemaVersion=2`, profil `default` présent). Le
  contrôle `schemaVersion` côté CodeIDE reste inchangé — un manifeste
  d'une autre génération ne peut toujours pas être consommé en silence.
- **`feature:install`** : sur téléphone, le journal en direct de l'écran
  d'installation (`InstallationFragment`, ADR 0090) ne tenait plus dans
  l'écran — sa zone `zone_journal` était un `FrameLayout` à
  `wrap_content`, et quand la sortie grossissait (jusqu'à 200 lignes,
  ADR 0046) elle poussait les cartes du stepper, le consentement licence
  et les actions contextuelles hors écran. La zone devient un
  `androidx.core.widget.NestedScrollView` à hauteur bornée
  (`@dimen/hauteur_journal_installation` = 160 dp, dimen historique
  v0.31.2 réutilisée) : la sortie défile DANS sa propre zone sans
  pousser le reste de l'écran. La variante tablette sw600dp passe de
  `ScrollView` à `NestedScrollView` pour uniformiser le type ViewBinding
  (`liaison.zoneJournal` est désormais `NestedScrollView` dans les deux
  variantes — un seul `FragmentInstallationBinding` inchangé, même si
  le type commun change de `FrameLayout` à `NestedScrollView`).

### Ajouté

- **`feature:install`** : auto-défilement intelligent du journal en
  direct (fonctionnalité attendue par ADR 0046, parité avec
  `PanneauConsoleFragment` ADR 0078). La dernière ligne reste visible
  pendant l'écoulement **tant que l'utilisateur est au bas** du
  journal ; s'il remonte pour lire l'historique, l'auto-défilement se
  met en pause et les nouvelles lignes attendent qu'il redescende.
  Déduplication des `post` de scroll (un vivant au plus), tolérance « au
  bas » de 16 dp (une ligne monospace `bodySmall`). Logique encapsulée
  dans une classe aide dédiée `SuiveurJournal` (séparation des
  responsabilités, `InstallationFragment` reste sous le seuil
  `TooManyFunctions` de detekt). Aucune logique métier touchée ; tests
  du ViewModel inchangés.

## [0.60.0] – 2026-10-06

Sixième et dernière étape (E6) de la **refonte complète du parcours
d'installation** : la migration des installations existantes et la
suppression de l'ancien code. Un appareil ayant vécu l'ancien parcours
(≤ v0.54.0) est **adopté sans retéléchargement** au premier `run()` : un
composant du SDK présent sans quadruplet persisté est vérifié **par
exécution** (le `verify` du manifeste v2) — vérifié → adopté, quadruplet
du plan reconstruit dans `install-state.json` ; en échec → réparation de
ce composant seul ; un préfixe déjà basculé dispense de l'archive du
bootstrap (l'ancien flux n'écrivait pas le cache SHA-256 du nouveau
gestionnaire). La licence du SDK n'est jamais migrée : le consentement
est un acte d'utilisateur (§ 12.5). L'ancien orchestrateur
`InstallateurBootstrap` et tout son pipeline (écrivains de scripts CLI,
`Aapt2Deployeur`, `VersionneurScriptsTerminal`, pilotage `codeide-env`
du terminal, ancien écran + mini-terminal) sont supprimés — environ
2 900 lignes —, les consommateurs (bandeau accueil, observateur d'outils,
relance du daemon Gradle) sont rebranchés sur le parcours. Décisions :
ADR 0091 ; ADR 0082/0083 marquées remplacées.

### Ajouté

- **ADR 0091 — migration des installations existantes et suppression de
  l'ancien parcours** : adoption par l'exécution (jamais depuis les
  marqueurs de fichier — un marqueur ne prouve rien), tableau des trois
  scénarios d'ADR 0085 § 6, limite assumée de la réparation
  superficielle, rebranchements documentés.
- **Tests d'adoption** (5 nouveaux, monde simulé) :
  - `PhaseBootstrapTest` : « un appareil ancien complet est adopté sans
    retélécharger l'archive » (0 requête HTTP, marqueur intact) ;
  - `PhaseAndroidSdkTest` : « une installation ancienne complète est
    adoptée sans aucun téléchargement » (0 téléchargement, quadruplets
    reconstruits, journal « adopté »), « un appareil à moitié installé
    n'adopte que les composants présents » (3 téléchargements), « un
    composant ancien dont l'exécution échoue est réparé seul, les autres
    adoptés » (1 téléchargement, binaire corrompu remplacé).

### Modifié

- **`core:bootstrap`** : `controleComposant` — un composant présent sans
  quadruplet n'est plus réparé d'office, son exécution tranche (adoption
  ou réparation seule ; la divergence d'un quadruplet ENREGISTRÉ reste
  réparée seule, § 12.4 inchangé) ; `EtapeTelechargement.verify` — un
  préfixe déjà basculé (shell + second stage) dispense du téléchargement ;
  `ExtracteurBootstrap.extraire` devient une simple `suspend fun` (le
  flux d'étapes de l'ancien pipeline n'avait plus de consommateur) ;
  `ObservateurOutilsTerminal` a pour stimulus l'état du parcours
  (`EnvironmentSetupOrchestrator.state`).
- **`feature:home`** : bandeau terminal et ouverture du terminal pilotés
  par l'état du parcours (phase `BOOTSTRAP` vérifiée, `running`) en plus
  du scan disque — l'ancien `BootstrapInstaller` n'existe plus.
- **`app`** : `CodeIdeApplication` — la relance du daemon Gradle à
  l'arrivée du JDK passe par le seul détecteur d'empreinte E4 (ADR 0089
  § 6) ; `refreshTerminalScripts()` retiré (voir Supprimé).
- **`feature:install`** : le module ne dépend plus de
  `core:terminal-runtime` ni des artefacts Termux (l'autorisation
  build-logic v0.54.0 devient inerte).

### Supprimé

- **`core:bootstrap`** : `InstallateurBootstrap` (ancien orchestrateur),
  `TelechargeurBootstrap`, `EcrivainSdkAndroidCli`,
  `EcrivainCodeideEnvCli`, `EcrivainGradleCli`, `EcrivainProfilShell`,
  `Aapt2Deployeur`, `VersionneurScriptsTerminal` et leurs tests (~2 340
  lignes de code seul).
- **`core:domain`** : ports `BootstrapInstaller`, `BootstrapAssetsSource`
  (l'`aapt2` vient du plan du manifeste depuis E4, § 12.4 — jamais d'un
  asset) et `ConfigurationEnvTerminal`.
- **`core:model`** : `EtatInstallationBootstrap`, `EtapeInstallation`,
  `OutilResume` (l'état vit dans `core:domain` :
  `EnvironmentSetupState`/`PhaseState`).
- **`core:terminal-runtime`** : `ConfigurationEnvTermux` — le terminal
  n'orchestre plus la configuration par frappe de pty (ADR 0083
  remplacée) ; le mécanisme `envoyerTexte` reste (pilotage générique).
- **`core:testing`** : `FakeBootstrapInstaller`,
  `FakeConfigurationEnvTerminal`.
- **`feature:install`** : ancien écran `InstallFragment` +
  `InstallViewModel` + `ClientTerminalMini`, layouts `fragment_install`,
  `rangee_etape_install`, `rangee_outil_install`, 45 clés de ressources
  orphelines (fr + en).
- **`app`** : `AssetsBootstrapSource` + `BootstrapAssetsModule` (le port
  n'a plus de consommateur).
- **`CodeIdeApplication.refreshTerminalScripts()`** (v0.37.3) : le
  versionnage des scripts du terminal n'a plus d'objet — l'application
  ne pose plus `etc/codeide.sh` ni les commandes `bin/gradle` /
  `bin/android-sdk` / `bin/codeide-env` ; l'environnement des sessions
  vient de `ProcessEnvironmentProvider` (JAVA_HOME, ANDROID_HOME, PATH,
  GRADLE_USER_HOME injectés par processus). **Rupture assumée** : sur un
  appareil ancien, ces scripts restent sur le disque et fonctionnent
  tels quels, mais ne sont plus maintenus par l'application.

### Non vérifié (appareil)

- L'adoption réelle d'un appareil ≤ v0.54 (notamment : les `verify` du
  manifeste v2 passent-ils sur les binaires posés par le manifeste v1 de
  `codeide-tools` — même layout, mêmes versions publiées) — scénarios
  E91–E95 ajoutés à `docs/TESTS_MANUELS.md` ; le manifeste v2 doit
  d'abord être publié côté dépôt `codeide-tools` (prompt 2, R5).


Ce journal suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/),
en français. Le versionnage suit [SemVer](https://semver.org/lang/fr/) :
`0.N.0` par étape validée, `0.N.M` pour une correction après retour utilisateur.

## [0.59.0] – 2026-10-06

Cinquième étape (E5) de la **refonte complète du parcours
d'installation** : la nouvelle interface. Maquettes statiques
(`docs/preview/installation-environnement.html` : téléphone — en cours,
consentement licence, échec, récapitulatif, écran Environnement ;
tablette sw600dp — stepper à gauche, journal à droite), puis
l'implémentation : écran d'installation à stepper de 4 cartes
(progression « étape N sur 4 », journal en direct repliable, vitesse et
temps restant **mesurés** du téléchargement courant, consentement
licence avant la phase `ANDROID_SDK`, actions contextuelles masquées
jamais grisées, récapitulatif final + « Créer mon premier projet »,
variante tablette deux panneaux) et écran **Environnement** des
Paramètres (rangées par composant avec **tailles réelles mesurées**,
rangée JDK « paquet APT », Vérifier légère/approfondie, Réparer,
Désinstaller avec confirmation, diagnostic copiable). La section
« Outils de développement — bientôt » du maître est remplacée par la
section réelle. Décisions : ADR 0090.

### Ajouté

- **ADR 0090 — nouvelle interface d'installation et écran
  Environnement** : projection pure (le ViewModel décide rien),
  divergence assumée sur l'estimation globale de temps restant (mesures
  seulement, jamais d'extrapolation — § 14 du cahier), diagnostic
  copiable en codes techniques neutres.
- **`docs/preview/installation-environnement.html`** : maquettes
  statiques des six états (liées à l'ADR 0090).
- **`core:domain`** : `DiagnosticInstallation` (diagnostic presse-papiers
  partagé : journal expurgé + récapitulatif par codes neutres, testé) ;
  port `AuditeurComposants` (tailles réelles sur disque, lecture seule) ;
  `EnvironmentSetupOrchestrator.uninstallComponent` (désinstallation
  d'un composant — retrait du quadruplet persisté par l'orchestrateur
  seul décideur).
- **`core:bootstrap`** : `DesinstalleurComposants`/`…Android`
  (suppression récursive idempotente de l'`installPath`, § 12.3) ;
  `AuditeurComposantsAndroid` (marche de l'`installPath` et du
  `JAVA_HOME` résolu, `DispatcherProvider.io`).
- **`feature:install`** : `InstallationFragment` +
  `InstallationViewModel` (stepper, journal repliable, consentement
  licence, vitesse/temps restant mesurés sur deux échantillons,
  actions masquées hors contexte, tablette sw600dp deux panneaux) ;
  layouts `fragment_installation` (téléphone + tablette) et
  `carte_phase_installation`.
- **`feature:settings`** : `EnvironnementFragment` +
  `EnvironnementViewModel` + `ComposantsEnvAdapter` (composants avec
  tailles auditées et état vérifié par présence réelle, rangée JDK
  informative non désinstallable, actions Vérifier légère/approfondie /
  Réparer / Désinstaller avec confirmation / Copier le diagnostic) ;
  destination `settings_environnement` du graphe ; rangée maître
  « Environnement de développement ».
- **`core:testing`** : `FakeAuditeurComposants` (neuf) ;
  `FakeEnvironmentSetupOrchestrator` compte aussi les vérifications
  (`verifications`).
- **Tests** : 26 nouveaux — `DiagnosticInstallationTest` (6,
  core:domain), `InstallationViewModelTest` (13, feature:install),
  `EnvironnementViewModelTest` (7, feature:settings).

### Modifié

- Graphe de navigation : la destination `installation` pointe vers le
  nouvel `InstallationFragment` ; `SectionParametres.OUTILS` (placeholder
  « bientôt ») supprimé au profit d'`ENVIRONNEMENT` (l'écran « bientôt »
  ne garde qu'IA et Sécurité) ; `AppNavigatorImpl` cartographie la
  nouvelle section.

### Non vérifié (appareil)

- Rendu TalkBack réel, pli du journal sur tablette sw600dp, boîte de
  confirmation de désinstallation, vitesse affichée pendant un vrai
  téléchargement — procédures E85–E90 ajoutées à
  `docs/TESTS_MANUELS.md`.

### Questions au propriétaire

- Maquettes livrées avec l'implémentation (poursuite sans interruption
  demandée) : toute retouche visuelle sera intégrée à l'itération
  suivante.
- Estimation **globale** de temps restant volontairement absente (ADR
  0090 § 3 : extrapolation = devinette, interdit § 14) — la valider ou
  demander une moyenne mesurée par appareil.

## [0.58.0] – 2026-10-06

Quatrième étape (E4) de la **refonte complète du parcours
d'installation** : la phase 4 `ANDROID_SDK` — plan résolu depuis le
manifeste v2 (une résolution par exécution), composants installés et
**vérifiés par exécution** avec péremption par quadruplet (réparation du
seul composant fautif), licences écrites après acceptation, câblage
Gradle (`android.aapt2FromMavenOverride` dans un bloc géré idempotent,
build-tools des templates alignés sur le catalogue), relance du daemon
Gradle par empreinte de chaîne d'outils, et vérification approfondie
(projet généré + vrai `assembleDebug`). Le parcours complet BOOTSTRAP →
PACKAGE_TOOLS → JAVA → ANDROID_SDK est exécutable de bout en bout.
Décisions : ADR 0089.

### Ajouté

- **ADR 0089 — phase 4 Outils Android** : cinq étapes (`resolution-plan`,
  `composants`, `licences`, `cablage`, `verification-sdk`), plan partagé
  par exécution, criticité (`cmdline-tools` non critique → `Degraded`),
  schéma de persistance 2 (`installPath` des composants).
- **`core:domain`** : `InstalledComponent.installPath` ;
  `EmpreinteChaineOutils` (SHA-256 de JAVA_HOME/ANDROID_HOME/aapt2/
  versions, clés triées) + `DetecteurChangementEmpreinte` ; port
  `VerificationApprofondie`.
- **`core:bootstrap`** :
  - `PhaseAndroidSdk` + `EtapesAndroidSdk` : résolution § 12.2 avec
    contrôle d'espace (plan × 2), péremption § 12.4 (quadruplet persisté
    comparé au plan — écart → réparation de CE composant seul),
    téléchargement unique (cache SHA-256), extraction `tar.xz` par les
    outils du bootstrap avec **garde anti-traversée**, bascule atomique
    de l'`installPath`, `verify` du manifeste exécuté sans shell ;
  - `EcrivainConfigurationGradle` : bloc géré **en place** (idempotence
    octet pour octet), lignes utilisateur conservées, override manuel
    hors bloc neutralisé par commentaire ;
  - `LicencesSdk` : fichiers `licenses/` écrits de façon atomique et
    idempotente après acceptation (hachages historiques — non vérifiés
    sur appareil, contre-vérification consignée) ;
  - orchestrateur : `avertissements()` → `Degraded`,
    `composantsInstalles()` persistés (ce qui est prouvé par exécution),
    `verify(deep)` délégué au port `VerificationApprofondie` (échec →
    la phase est marquée en échec dans le rapport, sortie jointe) ;
  - `LocalisationOutils.trouverAapt2` : plan d'abord (analyse tolérante
    d'`install-state.json` sans org.json — Kotlin JVM pur), scan du SDK
    ensuite, binaire hérité `$PREFIX/bin` en dernier (retiré en E6) ;
  - `MarqueursOutils.estSdkAndroidValide` : SDK cohérent dès qu'un
    répertoire attendu existe — un SDK partiel n'est plus invisible,
    `ANDROID_HOME` exporté sans attendre une plateforme.
- **App** : `VerificationApprofondieProjets` (projet de contrôle généré
  par le pipeline réel, `gradlew assembleDebug`, suppression quoi qu'il
  arrive), `InstallationModule` (liaison du port), relance du daemon
  Gradle par empreinte dans `CodeIdeApplication` (première observation
  ≠ changement).
- **Templates** : `buildToolsVersion = "35.0.2"` explicite dans
  `android-app` et `android-library` (celle du catalogue — sans elle AGP
  téléchargerait une build-tools x86_64) ; matrice AGP ↔ build-tools ↔
  compileSdk documentée dans `docs/ENVIRONNEMENT.md`.
- **Tests** (22 nouveaux) : `PhaseAndroidSdkTest` (10 — dont reprise
  zéro retéléchargement, réparation ciblée par quadruplet, dégradé non
  critique, `--list_installed` incohérent, `android.jar` illisible),
  `EcrivainConfigurationGradleTest` (5), `EmpreinteChaineOutilsTest` (4),
  `AlignementCatalogueTemplatesTest` (4), 4 tests du localisateur.

### Modifié

- `FakeDownloadManager` (core:testing) : archives semées **par somme
  SHA-256** (plans multi-composants) ; nouveau
  `FakeVerificationApprofondie`.
- `install-state.json` : schéma **2** — un fichier de schéma 1 est
  rejeté (reprise de zéro **sans retéléchargement**, verify-first
  re-vérifie chaque composant par exécution).

### Non vérifié (règle 2 du prompt)

- Extraction réelle des archives `.tar.xz` aarch64, `sdkmanager` réel
  (sortie de `--list_installed` supposée citer l'`installPath`),
  hachages de licences contre un vrai `sdkmanager --licenses`,
  `assembleDebug` sur appareil — scénarios complets consignés pour
  `docs/TESTS_MANUELS.md` en E6.

## [0.57.0] – 2026-10-06

Troisième étape (E3) de la **refonte complète du parcours
d'installation** : la phase 3 `JAVA` — installation du JDK du catalogue,
résolution unique de `JAVA_HOME`, vérification par exécution réelle et
**sonde TLS** (le truststore cassé, cause classique d'échec ultérieur
de `sdkmanager`, est détecté ici, pas en phase 4). Le parcours complet
BOOTSTRAP → PACKAGE_TOOLS → JAVA est exécutable ; il s'arrête proprement
avant `ANDROID_SDK` (E4). Décisions : ADR 0088.

### Ajouté

- **ADR 0088 — phase 3 Java** : deux étapes (`openjdk`,
  `verification-tls`), interrogation du dépôt APT journalisée
  (`apt-cache policy` — jamais un verdict), version affichée lue sur la
  sortie réelle de `java -version` (jamais en dur), sonde HTTPS réelle
  compilée et exécutée par le JDK installé (choix motivé contre
  `keytool -list -cacerts`), classification truststore/réseau des
  échecs TLS.
- **`core:bootstrap`** :
  - `PhaseJava` : étape `openjdk` — `pkg install -y <paquet du
    catalogue>` précédé d'`apt-cache policy` journalisé, contrôle
    immédiat « installé = vérifié en l'exécutant » : `JAVA_HOME` résolu
    (règle unique `LocalisationOutils`), `java -version` et
    `javac -version` exécutés, **majeure analysée** contre l'exigence du
    catalogue — une JVM posée qui ne démarre pas (mode R6 de l'ADR 0084 :
    bibliothèque manquante, code 127 muet) échoue avec sa sortie
    capturée (test de régression du constat E1) ;
  - étape `verification-tls` — classe `SondeTls.java` écrite dans
    `$PREFIX/tmp`, **compilée par `javac`** puis **exécutée par `java`**
    du `JAVA_HOME` résolu : la requête HTTPS vers `dl.google.com`
    (cible de sonde du cahier, pas une source d'artefact) prouve d'un
    seul geste JVM + `javac` + truststore + poignée de main TLS +
    réseau ; succès `TLS_OK <code>` sur stdout, toute exception capturée
    avec sa pile et **classée** (`PKIX`/`ValidatorException`/
    `SSLHandshakeException` → `Jvm` « truststore inutilisable » ;
    `UnknownHost`/`ConnectException`/délai → `Reseau`) ;
  - recensement `versions["jdk"]` lu sur `java -version` réel, pour le
    récapitulatif final et l'écran Environnement (E5).
- **Tests** : `PhaseJavaTest` (7 tests) — parcours propre (installation
  unique, dépôt interrogé, TLS vérifié), reprise sans réinstallation
  (0 appel `pkg install`, sonde rejouée), échec d'installation
  `Commande` avec sortie, JVM muette `Jvm` avec sortie R6 capturée,
  truststore cassé `Jvm`, réseau coupé `Reseau`, majeure divergente
  `Jvm`.

### Non vérifié (règle 2 du prompt)

- Installation réelle du paquet `openjdk-17` aarch64 et sonde TLS sur
  appareil — comportement de référence établi par les reproductions E1
  (ADR 0084) ; scénario complet sur appareil neuf consigné pour
  `docs/TESTS_MANUELS.md` en E6.

## [0.56.0] – 2026-10-06

Deuxième étape (E2) de la **refonte complète du parcours
d'installation** : le cadre commun d'exécution (orchestrateur concret,
`CommandRunner`, `DownloadManager` à cache SHA-256, état persisté
`install-state.json`, service de premier plan avec action Annuler) et
les phases 1 (Bootstrap) et 2 (PackageTools). L'ancien parcours reste
actif — le nouveau cadre coexiste jusqu'à E6, aucune interface ne le
déclenche avant E5. Décisions : ADR 0087.

### Ajouté

- **ADR 0087 — cadre commun d'exécution** : reprise « verify-first »
  (une étape déjà vérifiée est sautée — mécanisme unique de la reprise,
  de l'idempotence et de la réparation ciblée : `repair(phase)` rejoue
  la seule phase demandée), annulation synchrone avec persistance en
  coroutine fraîche (leçon v0.55.0), séquentialité stricte (arrêt à la
  première phase non livrée), licence SDK exigée avant `ANDROID_SDK`
  (§ 12.5).
- **`core:bootstrap` (sous-package `installation/`)** :
  - `OrchestrateurInstallation` : implémentation du port
    `EnvironmentSetupOrchestrator` — pipeline dans un scope interne
    (survit à la coroutine appelante), journal borné 200 lignes expurgé
    par `LogRedactor`, `verify(deep)` par exécution réelle (le contrôle
    approfondi complet arrive avec la phase 4, journalisé explicitement
    en attendant — jamais de repli silencieux) ;
  - `CommandRunnerProcessus` : enveloppe du `NativeProcessLauncher`,
    capture **intégrale** de stdout et stderr (l'ancien
    `SupervisionProcessus` ne gardait que 5 lignes de stderr), délai
    maximal qui détruit le processus (`CommandResult.timedOut`),
    annulation qui ne laisse pas le sous-processus vivant ;
  - `GestionnaireTelechargement` : cache `filesDir/cache/downloads`
    adressé par SHA-256 (fichier présent + somme correcte = zéro requête
    réseau — l'invariant « un composant = une version résolue = un
    téléchargement » est testé par compteur de requêtes), sources
    ordonnées avec miroirs journalisés, reprise HTTP `Range` (206) avec
    hachage du `.part` repris dans l'empreinte ;
  - `MagasinEtatInstallation` : `install-state.json` en `org.json`
    (précédent `CrashReportFileStore`), écriture atomique, `Running`
    normalisé `NotStarted` à l'écriture comme à la lecture, fichier
    corrompu ou schéma inconnu = reprise de zéro ;
  - `ClientManifesteOutils` : transport du manifeste v2 (URL unique du
    catalogue, § 12.7) avec validation de schéma — les vecteurs de
    référence officiels et la consommation arrivent en E4 ;
  - `PhaseBootstrap` (8 étapes : préalables espace/architecture,
    téléchargement, extraction gardée, bascule atomique, second stage,
    configuration APT, vérifications **exécutées** `sh -c 'echo ok'` /
    `apt --version` / `pkg help`, marqueur) et `PhaseOutilsPaquets`
    (`pkg update` retenté à délai croissant puis repli journalisé
    `apt update` — échec persistant = échec de phase en `Reseau` avec
    sortie à l'appui — puis un step par paquet du catalogue, chacun
    vérifié par exécution réelle : `curl`/`tar`/`xz`/`unzip`
    `--version`, `ca-certificates` par `dpkg -l`) ;
  - `ServiceInstallationEnvironnement` : service de premier plan
    `specialUse` (sous-type documenté, précédent ADR 0035),
    notification de progression avec **première action Annuler du
    dépôt**, décision pure `DecisionNotificationInstallation`
    (notification tant qu'une phase court, arrêt sinon), démarrage par
    port interne `DemarreurServiceInstallation` — livré et testé, pas
    encore déclenché en production (branchement UI en E5) ;
  - fabrique `FabriquePhasesParDefaut` : la carte de phases (types
    `internal`) ne transite jamais par le graphe Hilt (un `Map` générique
    y serait traité comme un multibinding) — doublable en test par
    `FabriquePhasesFausse`.
- **`core:model`** : 9ᵉ raison `EnvironmentSetupReason.
  ArchitectureNonSupportee` (l'ABI n'est pas `arm64-v8a` — ajout
  purement additif, les traducteurs branchent sur le type, pas sur la
  raison) ; `ConfigurationBootstrap.versionRelease` exposée (récapitulatif
  de la phase 1).
- **`core:domain`** : `CommandResult.timedOut` (délai maximal distingué
  d'un échec banal) ; KDoc de `ArchiveExtractor` élargi (zip du
  bootstrap en phase 1, `.tar.xz` du manifeste en phase 4).
- **`core:testing`** : `FakeCommandRunner`, `FakeDownloadManager` (avec
  compteur — l'invariant de l'unique téléchargement s'asserte),
  `FakeArchiveExtractor`, `FakeToolManifestClient`, `FakeInstallStateStore`,
  `FakeEnvironmentSetupOrchestrator`.
- **Tests (56 nouveaux, module à 190)** : machine d'états complète
  (transitions, échec avec sortie, annulation, reprise verify-first,
  double `run` sans effet, `repair` ciblé, licence, `Permissions` au
  lancement refusé, `Running` persisté normalisé) ; gestionnaire de
  téléchargements sur serveur HTTP local avec compteur (cache = 1
  requête au premier appel, 0 au second ; miroirs dans l'ordre ;
  reprise `Range` depuis un `.part` laissé par un kill ; réponse coupée
  = somme invalide, jamais d'artefact invalide au cache) ; runner
  (capture intégrale, timeout qui tue, annulation qui tue, `IOException`
  de lancement) ; magasin (aller-retour des trois états stables,
  normalisation, corrompu/schéma inconnu/champ requis absent = nul,
  écriture atomique) ; phases 1 et 2 de bout en bout (archive zip de
  test réelle, commandes scriptées, monde simulé qui évolue avec les
  installations) ; service (action Annuler, arrêt sans phase en cours,
  maintien en phase en cours) ; client manifeste (fixtures conformes
  § 12.2, schéma 1 rejeté, champ requis absent rejeté, SHA-256 mal
  formée rejetée, HTTP 404 = `Reseau`).

### Non vérifié (règle 2 du cahier des charges)

- **Sur appareil aarch64** : comportement du service de premier plan
  sous Android 15 avec cible 28 (le précédent terminal tourne, le type
  `specialUse` est accepté), idempotence du second stage relancé (son
  verrou interne est le comportement termux attendu — constaté sur le
  script, pas exécuté sur appareil), exécution directe des scripts du
  préfixe via shebang (les étapes passent par `sh`/`bash` explicites,
  éprouvé par l'ancien pipeline).
- **Vérification approfondie** (`verify(deep = true)`) : le contrôle
  complet (projet généré + `assembleDebug` réel) exige la phase
  `ANDROID_SDK` — livré en E4 ; E2 exécute les contrôles légers et le
  journalise explicitement.
- **Branchement UI** : le service et l'orchestrateur ne sont démarrés
  par aucun écran (E5) — vérifiés par tests Robolectric et tests
  d'intégration du cadre uniquement.

### Questions au propriétaire

- (Reprise des questions E1) Licences § 12.5 et divergence manifeste
  v1 → publiées par le prompt 2 en v2 ; rien de nouveau de mon côté.

## [0.55.0] – 2026-10-05

Première étape (E1) de la **refonte complète du parcours d'installation
de l'environnement** : investigation de la cause racine du « SDK non
fonctionnel » (sortie réelle capturée), décisions d'architecture,
modèle de domaine et ports, catalogue de versions. Aucun comportement
existant n'est modifié dans cette étape — les types et ports posés ici
sont le socle des étapes E2 à E6. Décisions : ADR 0084, 0085, 0086.

### Ajouté

- **ADR 0084 — investigation « SDK non fonctionnel »** : reproductions
  réelles sur Linux x86_64 (archive reconditionnée `codeide-tools` et
  zip Google rev 12.0, scripts `sdkmanager` identiques octet pour octet)
  ; mode muet établi avec preuves — une JVM présente mais incapable de
  démarrer sort en **code 127, stdout vide, diagnostic sur stderr
  uniquement** (`error while loading shared libraries: libjli.so`), et
  `sdk_fonctionnel` jetait les deux tuyaux. Analyse statique du paquet
  APT réel `openjdk-17_17.0.20_aarch64.deb` : chaîne `java` →
  `libjli.so` → `libz.so.1`, `libjvm.so` → `libandroid-shmem.so`,
  RUNPATH absolu codé en dur — toute dépendance APT absente déclenche le
  mode muet. Truststore (`ca-certificates-java`, simple `Recommends`) :
  vérifié ne PAS casser `sdkmanager --version` (code 0 sans `cacerts`) —
  d'où le test TLS exigé en phase 3. Plan de vérification sur appareil
  rédigé (points restants « non vérifiés » : pas d'appareil aarch64
  dans l'environnement de travail).
- **ADR 0085 — orchestrateur d'installation en quatre phases vérifiées**
  (BOOTSTRAP, PACKAGE_TOOLS, JAVA, ANDROID_SDK) : machine d'états
  `PhaseState` (`NotStarted`/`Running`/`Succeeded`/`Degraded`/`Failed`),
  état persisté `install-state.json` à schéma versionné, journal en
  lecture seule alimenté par le `CommandRunner` (le terminal n'est plus
  le moteur), service de premier plan hôte, migration des installations
  existantes par adoption vérifiée.
- **ADR 0086 — catalogue de versions et manifeste v2** : exigences
  (build-tools 35.0.2 — seule version reconditionnée publiée pour
  Android aarch64 —, plateforme android-37.2 des templates, JDK 17,
  outils de la phase 2, URL du manifeste en constante unique) ;
  résolution du plan en fonction pure avec règles du contrat commun
  § 12.2 (révision la plus haute, canal stable, exclusivité des
  `installPath`, exigences à version exacte — jamais de repli).
- **`core:model`** : `AppError.EnvironmentSetup` (raisons réseau, espace
  disque, somme de contrôle, commande, JVM, permissions, annulation,
  manifeste invalide) portant `CommandOutput` (commande, code de retour,
  dernières lignes) — le contrat « aucune sortie jetée » : un échec de
  commande transporte désormais sa sortie réelle.
- **`core:domain`** : `EnvironmentSetup.kt` (`InstallPhase`, `Progress`,
  `StepId`, `ComponentIssue`, `PhaseState`, `EnvironmentSetupState`,
  `VerificationReport`, port `EnvironmentSetupOrchestrator` et ports
  `InstallStep`/`StepContext`/`CommandRunner`/`DownloadManager`/
  `ArchiveExtractor`/`ToolManifestClient`/`InstallStateStore`,
  `PersistedInstallState` avec le quadruplet d'immutabilité
  `InstalledComponent`) ; `ToolManifest.kt` (types du manifeste v2 à la
  lettre du § 12.2 + `InstallPlanResolver`, fonction pure, 20 tests
  couvrant chaque règle du contrat) ; `ToolchainCatalog.kt` (exigences
  déclarées à un seul endroit, justifiées valeur par valeur).
- **Traducteurs d'erreur** : branche `EnvironmentSetup` ajoutée aux
  quatre `when` exhaustifs (accueil, diagnostic, wizard, installation) —
  le compilateur l'a exigée, l'exhaustivité reste intentionnelle.

### Non vérifié (règle 2 du cahier des charges)

- Comportements **sur appareil aarch64** : exécutions réelles de la JVM
  du préfixe, RUNPATH hors utilisateur 0, `pkg`/`apt` du bootstrap,
  installation des `Depends` d'`openjdk-17` — plan de vérification en
  cinq points à l'ADR 0084, à exécuter à la première fenêtre appareil
  (E2/E4).

### Questions au propriétaire

- Licences (§ 12.5) : l'app écrira les fichiers `licenses/` après
  acceptation explicite — défaut prudent à confirmer.
- Divergence contrat (§ 12) : le manifeste v1 ne publie ni `platform`
  ni `aapt2` ni build-tools 36.0.0 — le prompt 2 doit les publier en
  v2 ; build-tools 35.0.2 exigé en attendant (seule version aarch64
  existante).

## [0.54.0] – 2026-10-05

Retour utilisateur : « pour le journal live, il fallait le remplacer
complètement par un mini écran TerminalView et non créer une nouvelle
session terminal (si possible) ». La v0.52.0 basculait vers l'écran du
terminal complet — vécu comme une rupture de parcours. Le journal live
est désormais **un mini écran TerminalView intégré à l'écran
d'installation**. Décision : ADR 0083 (correctif v0.54.0).

### Modifié (le journal live est un mini TerminalView intégré)

- **`fragment_install.xml`** : le bouton « Ouvrir le terminal » disparaît,
  remplacé par une carte `TerminalView` (hauteur fixe ~280 dp, police
  13 dp, thème du terminal) — la session « Configuration » s'y rend
  SUR PLACE, interactive (le toucher ouvre le clavier : relancer
  `codeide-env`, Ctrl+C, répondre à une invite).
- **`InstallViewModel`** : plus d'effet de navigation — l'identifiant de
  session rendu par `ConfigurationEnvTerminal.lancer()` alimente
  `EtatInstallation.sessionConfiguration` (flux combiné état + journal +
  session) ; le déclenchement automatique de fin de base et l'ordre
  manuel `ConfigurerEnvironnement` restent inchangés (une session
  vivante est RETROUVÉE, jamais doublée).
- **`InstallFragment`** : branchement du mini terminal (anti-rebranchement
  par identifiant, thème aux indices 256/257/258, repaint au signal
  `observeSorties` — même architecture que `TerminalActivity`) ; le
  journal TextView ne survit qu'à la phase de BASE (le shell n'existe
  pas avant l'extraction du bootstrap — aucun terminal n'y serait
  rendable).
- **`ClientTerminalMini` (NOUVEAU)** : client TerminalView minimal du
  mini écran — tap → focus + clavier, journaux muets, ni zoom ni copie
  auto (le plein écran de `feature:terminal` reste LA référence).
- `feature:install` dépend de `terminal-view` + `core:terminal-runtime`
  (mêmes artefacts que `feature:terminal`, exception lint Aligned16KB
  documentée).
- Chaînes : `installation_terminal_titre` (fr/en) ; le détail du
  résultat et des outils requis parlent du mini-terminal ;
  `installation_ouvrir_terminal` retirée.

## [0.53.0] – 2026-10-05

Retour d'appareil réel : « …146,4 Mio téléchargés (~3 min 36) →
Extraction… → android-sdk: [erreur muette] » — l'installation du SDK
échouait APRÈS le téléchargement sans dire pourquoi (diagnostic mené
de bout en bout hors appareil : le zip rev 12.0 pèse exactement
146,4 Mio — l'épinglage fonctionne ; `unzip` et le `jar` du JDK sont
présents dans le bootstrap ; le sdkmanager rev 12.0 répond « 12.0 »
sur JVM saine ; le miroir `codeide-tools` publié entre-temps est
intègre). Restaient deux angles morts : la JVM n'était JAMAIS démarrée
pour être validée, et le contrôle fonctionnel avalait sa sortie.

### Corrigé (les erreurs réelles remontent, la JVM est vérifiée)

- **`android-sdk` : `resoudre_java` DÉMARRE chaque JVM candidate**
  (`java_demarre` — `java -version`) au lieu de ne vérifier que le bit
  exécutable : un JDK à la bibliothèque manquante est ÉCARTÉ au profit
  du candidat suivant (`JAVA_HOME`, puis `PATH`, puis `lib/jvm`/`opt/`
  du préfixe) ; l'échec total affiche la sortie `-version` de CHAQUE
  candidat trouvé (`diagnostiquer_java`) et propose
  `pkg install --reinstall openjdk-17` — plus jamais
  « sdkmanager non fonctionnel » sans indice.
- **`android-sdk` : le contrôle `sdkmanager --version` garde sa sortie**
  (`sdk_detail`) — l'erreur véritable (liaison dynamique cassée, JVM
  muette) accompagne le message d'échec, y compris dans la voie de
  guérison d'un cmdline-tools installé mais cassé.
- **`android-sdk` : extraction résiliente** — l'espace disque est
  revérifié AVANT l'extraction (~512 Mio : build-tools et archive ont
  pu consommer la marge depuis le contrôle initial, message avec les
  Kio libres) ; `unzip` accepte le code 1 d'Info-ZIP (AVERTISSEMENT —
  extraction effectuée) et son stderr remonte sur échec réel (≥2) ;
  `bsdtar` puis le `jar` du JDK servent de replis ; l'absence de
  `cmdline-tools/` après extraction a son message propre (archive
  inattendue).
- **`codeide-env` : `java_fonctionnel`** — un JDK présent mais dont la
  JVM ne démarre pas est RÉINSTALLÉ automatiquement
  (`pkg install --reinstall openjdk-17`) au lieu d'être « conservé » ;
  la complétude (`environnement_complet`) l'exige : relancer
  `codeide-env` répare un environnement à JVM cassée, SDK complet ou
  non.
- **Banc d'essai élargi** : `un JAVA_HOME cassé est écarté au profit du
  JDK du préfixe` et `aucun java fonctionnel rend un diagnostic
  actionnable` (PATH sanitisé sans le java du poste, comme sur
  appareil) côté `android-sdk` ; `un JDK présent mais cassé est
  réinstallé automatiquement` côté `codeide-env`.
- Scripts du terminal versionnés **7 → 8** : les appareils déjà
  installés reçoivent les commandes corrigées au prochain démarrage de
  l'app, sans réinstallation.

## [0.52.0] – 2026-10-05

Comportement demandé par l'utilisateur : « une fois que le bootstrap
installé et `pkg update`, la configuration de l'environnement avec
l'installation de java, android sdk, etc. » — la configuration démarre
donc **automatiquement** à la fin de l'installation de base, et son
journal live défile **dans le terminal** (TerminalView, pour un design
cohérent). **Git n'est plus installé** (pas vraiment urgent). Décision :
ADR 0083.

### Ajouté (la configuration de l'environnement vit dans le terminal)

- **Commande `codeide-env` (`core:bootstrap`, scripts versionnés 6 → 7)** :
  orchestrateur de la configuration automatique — pré-vol (espace disque
  ~1,5 Gio, sortie immédiate si l'environnement est complet),
  `pkg update` (repli `apt`, non fatal), **OpenJDK 17** par le dépôt
  `codeide-packages` (sauté si un `java` fonctionnel existe déjà,
  résolution `JAVA_HOME` : `lib/jvm` APT puis `opt/openjdk*`), **SDK
  Android par DÉLÉGATION à `android-sdk installer`** (ADR 0082 —
  binaires `<archi>` du manifeste `codeide-tools`, cmdline-tools rev
  12.0, plateformes via `sdkmanager` : source de vérité unique, aucune
  duplication du shell d'installation), pont
  `$PREFIX/etc/ide-environment.properties` (`JAVA_HOME`,
  `ANDROID_SDK_ROOT` en upsert, les autres lignes conservées),
  vérifications finales puis marqueur `codeide-env.terminee`.
  Sous-commandes `statut` et `refaire` (supprime le SDK puis
  reconfigure) ; POSIX sh strict (dash) ; chaque étape est idempotente
  — une interruption (Ctrl+C) reprend où elle en était.
- **`envoyerTexte` sur les sessions (`core:terminal-runtime`)** : le
  port du domaine gagne l'envoi de texte « comme si l'utilisateur le
  tapait » (`TerminalSession.write` de Termux — la voie du clavier
  logiciel) ; session inconnue ou fermée : sans effet, jamais
  d'exception.
- **Port `ConfigurationEnvTerminal` (`core:domain`, implémentation
  `core:terminal-runtime`)** : `estComplet()` lu sur le disque (une
  installation manuelle compte autant qu'une installation pilotée) et
  `lancer()` — crée une session étiquetée « Configuration », attend la
  pose du shell, y tape `codeide-env`, et garantit **une seule session
  de configuration à la fois** (une session vivante est retrouvée, une
  session morte avec environnement incomplet relance une nouvelle
  session : la reprise repart où elle en était).
- **Déclenchement automatique (`feature:install`)** : la fin de la base
  ouvre le terminal sur la session de configuration (effet
  `OuvrirTerminal`) — le TerminalView EST le journal live (couleurs
  ANSI, défilement, copie, Ctrl+C) ; l'état INITIAL « base installée,
  environnement incomplet » déclenche aussi (reprise au retour sur
  l'écran). Bouton « Ouvrir le terminal » en phase résultat ;
  relance manuelle : `codeide-env` dans n'importe quelle session.

### Modifié

- **`PAQUETS_OUTILS` perd `git`** (`core:bootstrap`) : le repli par
  paquets de l'écran Installation se réduit à `openjdk-17` — l'option
  `-g` de `codeidesetup` (voie autonome du dépôt `codeide-tools`)
  demeure, et `pkg install git` reste disponible à la demande.
- L'écran Installation célèbre un environnement complet sans proposer
  de paquets ; le journal de l'écran ne couvre plus que la phase de
  base (qui précède l'existence du shell).

### Vérifié

- Banc d'essai dash réel (`EcrivainCodeideEnvCliTest`, 5 scénarios) :
  installation depuis rien, idempotence (« rien à faire »), `statut`,
  reprise après perte du seul JDK (« déjà complet — conservé » côté
  SDK), `refaire` (désinstallation puis réinstallation) — `pkg` et
  `android-sdk` factices, PATH sanitisé sans le java du poste hôte.
- `ConfigurationEnvTermuxTest` : sans bootstrap ni environnement
  complet rien ne se crée ; session étiquetée + `codeide-env\r` tapé ;
  session vivante retrouvée sans doublon ; nouvelle session après mort
  de la précédente. `InstallViewModelTest` : déclenchement à la fin de
  la base, garde anti-doublon, environnement complet silencieux, échec
  de lancement sans effet, ordre manuel.

## [0.51.0] – 2026-10-04

Régler DÉFINITIVEMENT le problème de l'Android SDK, comme AndroidIDE : le
`sdkmanager` de Google installe des binaires Linux **x86_64**
(`aapt`, `aapt2`, `aidl`, `zipalign`, `dexdump`, `adb`…) INEXÉCUTABLES sur
un téléphone aarch64 — seule `aapt2` était contournée depuis les assets de
l'app (ADR 0032), le reste des build-tools restait mort sur disque. La
solution est le nouveau dépôt [`jjoblab/codeide-tools`](https://github.com/jjoblab/codeide-tools)
(l'équivalent CodeIDE d'`androidide-tools`, volontairement séparé de
`codeide-packages` : les paquets du bootstrap et les versions du SDK n'ont
pas le même cycle de vie) : binaires **recompilés pour Android** par
architecture (aarch64, arm, x86_64 — source `lzhiyong/android-sdk-tools`),
publiés en releases GitHub avec un manifeste d'URLs et **sommes SHA-256**.
Décision : ADR 0082.

### Ajouté (la commande android-sdk installe de VRAIS binaires Android)

- **Commande `android-sdk` (`core:bootstrap`)** : `android-sdk installer`
  lit le manifeste du dépôt (`raw.githubusercontent.com/jjoblab/
  codeide-tools/main/manifest.json` — surcharges `CODEIDE_TOOLS_REPO` /
  `CODEIDE_TOOLS_MANIFEST`), résout la version la plus récente publiée
  pour l'architecture de l'appareil (`uname -m`, surcharge `CODEIDE_ARCH`
  pour les tests), télécharge `build-tools-X.Y.Z-<arch>.tar.xz` et
  `platform-tools-X.Y.Z-<arch>.tar.xz` (~8 Mio au total — le parcours
  équivalent par le sdkmanager en pesait ~172), **vérifie chaque SHA-256**
  avant extraction sous `$HOME/android-sdk`, et reste idempotent (un
  `aapt2` déjà en place saute le téléchargement). Les **plateformes**
  (`android.jar`, pur Java) restent installées par le `sdkmanager` :
  `platforms;android-37.2` par défaut — le dépôt Google reste LA source
  des plateformes, il n'est plus celle des binaires natifs. Un manifeste
  inaccessible ou sans version publiée rend un message ACTIONNABLE
  (pointer le workflow de publication du dépôt), une somme non conforme
  est REFUSÉE net — rien n'est extrait.
- **cmdline-tools : voie reconditionnée + repli** : si le manifeste
  publie des cmdline-tools reconditionnés (miroir GitHub + SHA-256 — la
  fabrication côté dépôt REFUSE les rev 19+ au binaire natif x86_64),
  `android-sdk installer` les préfère au zip de Google ; la vérification
  fonctionnelle fait foi dans les deux cas et la GUÉRISON v0.48.0 est
  conservée (un cmdline-tools portant `bin/android` ou dont `--version`
  échoue est REMPLACÉ, l'appareil qui a déjà tenté la rev 23.0 n'est pas
  condamné à son échec).
- **Pont `ide-environment.properties` (profil shell)** : le profil
  `$PREFIX/etc/codeide.sh` respecte désormais les clés posées par
  l'installeur autonome du dépôt (`codeidesetup`, exécuté au terminal
  sans passer par l'app — README de `codeide-tools`) : chaque clé de la
  liste blanche (`JAVA_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`) ne
  passe QUE si absente de l'environnement de la session — le ballotage de
  l'app reste la source la plus fraîche, le fichier comble les blancs.
  Lecture ligne à ligne `CLE=VALEUR` (jamais de `.` sourcé : une valeur
  hostile ne serait pas évaluée).
- **Statut enrichi** : `android-sdk statut` affiche l'architecture et le
  caractère exécutable du `aapt2` installé.
- **Versionneur des scripts du terminal** : 5 → 6 — les appareils déjà
  installés reçoivent la nouvelle commande et le nouveau profil au
  démarrage suivante, SANS réinstallation du bootstrap.

### Vérifié

- Scripts du dépôt `codeide-tools` éprouvés en intégration LOCALE avec
  les archives réelles de `lzhiyong/android-sdk-tools` v35.0.2 : les six
  archives (3 architectures × 2 composants) sont fabriquées, les ELF
  sont bien aarch64, `source.properties` et permissions conformes ;
  `codeidesetup` testé de bout en bout (manifeste `file://`, `pkg`
  factice : SHA-256, extraction, JAVA_HOME résolu dynamiquement — le
  dépôt corrigé au passage : `JAVA_HOME` pointait vers `opt/openjdk`,
  un chemin inexistant du bootstrap).
- `EcrivainSdkAndroidCliTest` réécrit : le déroulé COMPLET de
  `android-sdk installer` s'exécute pour de vrai (faux `curl` routant
  selon l'URL — manifeste, archives de binaires, zip Google — avec de
  vraies mini-archives tar.xz) : installation, idempotence, refus
  SHA-256, manifeste sans version, guérison des cmdline-tools cassés,
  voie reconditionnée, statut.
- `EcrivainProfilShellTest` : le pont `ide-environment.properties` est
  éprouvé pour de vrai par bash (clés qui passent en session vierge,
  l'injection de l'app qui gagne en session pilotée, clé hostile hors
  liste blanche jamais évaluée).

## [0.50.0] – 2026-10-04

Retour utilisateur sur appareil réel (v0.49.0 installée) : « je constate
une amélioration » — et les sondes le confirment : plus AUCUN
« orchestrateur muet », aucun kill 143, aucune relance, file d'événements
à 0-1, écarts de transport passés de 19-24 s minimum (jusqu'à 28 minutes)
à 1-14 s maximum. Mais le transport garde 1 à 14 s (résumés reçus 2 s et
19 s APRÈS la fin des builds), et le détail des sondes désigne un seul
endroit : l'étendue des écarts d'un même build atteint 12 955 ms pour un
build de 2 370 ms — les lignes s'égouttent FRAME PAR FRAME au lieu
d'affluer. Décision : ADR 0081.

### Corrigé (la lecture ne dépend plus du scheduler de coroutines)

- **Le diagnostic** : la boucle de lecture v0.49.0 restait une COROUTINE
  — `withContext(Dispatchers.IO)` pour lire la frame, re-dispatch vers la
  voie de fils du collecteur pour l'émettre : DEUX changements de contexte
  de scheduler PAR FRAME. `Dispatchers.IO.limitedParallelism(3)` est une
  LIMITE de concurrence, pas une RÉSERVE de fils : les fils de la voie
  viennent du pool partagé (64 au plus) — le même que le scanning
  classpath LSP et les diagnostics post-sync de l'app. Pendant les
  builds, ce travail CPU charge le scheduler : chaque hop coûte de 0,1 à
  2 s, la sortie s'égoutte (16 lignes étalées sur 13-20 s), le retard se
  lit au fil de l'eau au lieu d'arriver en rafale. Le pong, lui, ne
  traversait qu'un à deux hops par cycle de 5 s : il passait toujours
  sous les 15 s du watchdog — cohérent avec l'absence totale de « muet »
  dans les logs. Côté orchestrateur, tout était déjà immédiat (file à
  0-1, tampon UDS jamais rempli, aucun avertissement de profondeur).
- **Client (`tooling:client`)** : la lecture du socket quitte le monde
  des coroutines — la session réelle (`SessionSocketAndroid`) lit dans UN
  FIL DÉDIÉ (« tooling-lecteur », priorité relevée d'un cran) : le
  `read()` bloquant dort en appel système, le NOYAU le réveille à
  l'arrivée des octets — zéro ordonnancement coroutine entre l'arrivée
  d'une frame et son routage. Le router (non suspendant par
  construction depuis v0.49.0 : `trySend` vers voies non bornées,
  écritures atomiques, promesses complétées) s'exécute DANS le fil, au
  vrai point de réception : le pong et la latence de transport sont
  marqués AVANT toute file coroutine, et les voies build/sync se
  remplissent au rythme du noyau. La collecte du flux d'événements ne
  route plus — elle reste le signal de fin de flux et garde son ménage.
- **Contrat de session (`SessionTooling.acheminerVia`)** : la session
  réelle reçoit le chemin rapide et confirme qu'elle route elle-même
  (`true`) ; les sessions factices des tests héritent du défaut (`false`)
  — la collecte route comme avant, aucun test n'a changé de sémantique.
  Le test bout-en-bout du daemon (`BoutEnBoutTest`) embranche le fil
  dédié via son miroir JVM (`SessionSocketJvm`) : le ping/pong, la
  sortie de build et l'arrêt sont validés sur le VRAI chemin de lecture.
- **Orchestrateur (`tooling:server`)** : inchangé (le fil écrivain
  v0.49.0 avait déjà tout écrit immédiatement — les sondes v0.49.0
  l'ont prouvé : file=0/1 en fin de build) ; `ServerVersion` aligné sur
  0.50.0 pour la livraison.

### Corrigé (échec CI : ordre des imports du test bout-en-bout)

- **Le tag v0.50.0 échouait sur `spotlessKotlinCheck` avant tout le
  reste** : `import kotlinx.coroutines.cancel` vivait APRÈS
  `kotlinx.coroutines.channels.Channel` dans `BoutEnBoutTest` alors que
  l'ordre ASCII d'ktlint 1.8.0 le veut AVANT — la vérification locale de
  la livraison avait couvert `spotless` sur `:tooling:client` mais omis
  `:tooling:daemon`, écart de couverture fermé depuis. Le correctif
  remonte l'import d'une ligne, exactement le diff attendu par la CI :
  tri d'imports dans un source de TEST uniquement, aucun changement de
  comportement (l'APK déjà livré reste valable — les sources de test
  n'entrent pas dans le paquet). Le tag est re-pointé sur le commit
  corrigé : l'ancien pointage n'avait produit ni artefact ni release
  (échec avant publication), rien n'en dépendait.

## [0.49.0] – 2026-10-03

Retour utilisateur sur appareil réel : « voici les logs, peut-être que tu
auras un indice clair pour régler DÉFINITIVEMENT mon problème de retard »
— les sondes de latence v0.43 désignaient le transport (émission →
réception : écarts de 19-24 s minimum, jusqu'à 28 minutes, rafales
arrivées ~76 s APRÈS la fin du build), et la v0.48 ajoutait le verdict
du watchdog : « orchestrateur muet (aucun pong en 15000 ms) — arrêt
forcé » en pleine sync, kill 143, relance. Décision : ADR 0080.

### Corrigé (le pong ne peut plus être retardé — des deux côtés du tuyau)

- **Le diagnostic des sondes** : « zone texte alimentée : écart min 0 ms »
  (la publication cliente est instantanée) contre un transport énorme :
  TOUT le retard vivait dans le trajet socket/pompe. Deux fragilités
  symétriques y convergeaient — la pompe cliente UNIQUE faisait ses
  `send` suspendants DANS sa boucle de lecture (un canal de console plein
  gelait la lecture du socket : plus de pongs lus, « muet », kill) ; et
  côté orchestrateur le pong partageait le moniteur `@Synchronized` de
  l'écriture d'événements (une écriture bloquée sur le tampon de
  réception plein de l'app ensevelissait le pong quand même — le
  correctif v0.37.3 l'avait sorti du bus, pas du verrou).
- **Client (`tooling:client`)** : la pompe devient un LECTEUR qui ne
  suspend JAMAIS hors de la lecture — le pong est mis à jour en CHEMIN
  RAPIDE avant tout traitement, les familles build/sync partent chacune
  dans SA voie ordonnée au consommateur dédié (les ErrorResponse qui
  concluent une sync traversent la voie sync : le terminal ne peut pas
  précéder les lignes qu'il conclut, ADR 0079 préservée), les événements
  légers (réponses, tas, diagnostics) sont traités en ligne. La portée
  vit sur une voie DÉDIÉE de fils (3 au plus) : la saturation du
  dispatcheur par défaut par le travail CPU (classpath LSP, surlignage)
  ne peut plus retarder la lecture du socket. Les pans non prévues sont
  journalisées (tag `ToolingClient`) au lieu de tuer une voie en silence.
- **Orchestrateur (`tooling:server`)** : fil écrivain UNIQUE — les frames
  d'événements traversent une file FIFO bornée (contre-pression sans
  perte conservée), le PONG dispose d'une file PRIORITAIRE distincte :
  aucune rafale de sortie, aucune écriture bloquée ne peut plus le
  retarder. La latence transport se mesure désormais à la réception
  (lecteur), le retard restant se lit côté publication.
- **Daemon (`tooling:daemon`)** : le verdict « orchestrateur muet »
  embarque les SIGNES VITAUX de la pompe (âge du dernier pong,
  profondeur des voies) : la ligne désigne le coupable — voies profondes
  = console lente côté app (tuer l'orchestrateur ne réparait rien :
  c'était le réflexe qui tuait un orchestrateur sain), voies vides + pong
  vieux = orchestrateur réellement mort.
- **Vidanges process-wide (`PompeBuildTooling`)** : chaque événement
  traverse SANS PAN (journalisée, la vidange continue) et une vidange
  morte est RELANCÉE (bornée) — sa mort silencieuse laissait le canal
  aval se remplir à jamais, puis gelait la pompe amont (c'était la
  boucle « muet → kill → relance »).
- **Retard visible en se formant** : la file d'écriture socket de
  l'orchestrateur ET les voies clientes avertissent (borné en débit,
  5 s) quand leur profondeur dépasse le seuil — un rapport de terrain
  qui contient « file d'écriture socket à N » désigne le mauvais côté
  du tuyau sans instrumenter quoi que ce soit.

## [0.48.0] – 2026-10-03

Retour utilisateur sur appareil réel : « lors d'un Sync la console manque
toujours les outputs nécessaire, la plupart est affiché dans le header du
bottomsheet et à la fin du sync l'ui n'est toujours pas à jour (la console
et le header) » — plus l'installation du SDK Android qui échouait sur un
binaire natif inexécutable. Décision : ADR 0079.

### Corrigé (sync : la console montre le VRAI flux, l'UI se conclut — ADR 0079)

- **La console Sync était réduite à ~7 lignes de transitions d'étapes**
  pendant que tout le détail vivant nourrissait le sous-titre de
  l'en-tête du panneau : la capture stdout/stderr de la sync avait été
  retirée en v0.45.1 (elle publiait avec le buildId de la REQUÊTE sync —
  un canal que le client n'ouvrait jamais, publication morte). Elle
  REVIENT sur SON message : le protocole v6 ajoute `SyncOutput`, le
  serveur branche `StreamingFluxSync` sur le stdout/stderr de l'action
  (`--console=plain`, comme le build) et republie les statuts textuels
  CHANGÉS de la fenêtre daemon (« Starting Gradle Daemon ») ; la console
  Sync affiche le flux de Gradle comme la fenêtre Sync d'Android Studio.
- **À la fin d'une sync, l'en-tête restait « étape n/N » avec chrono à
  jamais, la console sans conclusion** : le résultat n'était publié que
  par la coroutine LANÇANTE — la revalidation silencieuse (v0.40.1,
  chaque ré-ouverture à empreinte identique) ne le publiait JAMAIS alors
  que son `SyncStarted` avait armé « en cours ». Le résultat voyage
  désormais DANS le flux ordonné (`EvenementSyncFlux` : Debut → Ligne →
  Etape → Terminal) : la vidange process-wide conclut TOUTE sync sur le
  fait du serveur, même écran fermé, même sync d'un autre écran ;
  ErrorResponse répondant à une sync et rupture de session produisent
  aussi un terminal. L'ordre du câble est l'ordre de la console : la
  conclusion arrive APRÈS les lignes et étapes qu'elle conclut.
- **Un échec de sync concluait silencieusement** (état seul, console
  muette) : la console reçoit désormais « Synchronisation échouée en
  Xs » + le message du serveur en rouge — parité « SYNC FAILED »
  d'Android Studio. Une double conclusion éventuelle (terminal + échec
  local) est dédupliquée par contenu.

### Corrigé (terminal : `android-sdk` — le sdkmanager inexécutable sur aarch64)

- **« /…/cmdline-tools/latest/bin/android: not executable: 64-bit ELF
  file » puis « android-sdk: l'installation a échoué (code 1) »** : les
  cmdline-tools RÉCENTS (rev 19+, ex. 16111833 — celui que la v0.47.0
  téléchargeait) font de `sdkmanager` un relais vers un NOUVEAU binaire
  natif `android`… que Google ne publie sous Linux qu'en x86_64 — sur un
  appareil aarch64 le noyau refuse l'exécution (ENOEXEC). La commande
  épingle désormais la rev **12.0** (11076708), dont le sdkmanager est
  un script 100 % Java sans dépendance d'architecture (vérifiée de bout
  en bout : platform-tools + android-37.2 + build-tools 37.0.0
  s'installent) ; elle **GUÉRIT** les installations cassées (un
  cmdline-tools portant `bin/android` ou dont `--version` échoue est
  REMPLACÉ) et VÉRIFIE le sdkmanager après pose. AndroidIDE résout le
  même piège en ne lançant jamais le sdkmanager de Google (composants
  reconditionnés par architecture depuis leur manifeste `androidide-tools`)
  — l'épinglage pure Java est l'équivalent sobre. Script versionné
  4 → 5 (re-distribution automatique).

## [0.47.0] – 2026-10-03

Retour utilisateur sur appareil réel : deux plaintes de terminal (un
warning apt à CHAQUE `pkg install`, une installation SDK qui échouait
APRÈS le téléchargement de 172,6 Mio) et une promesse d'UI non tenue
(le bouton Tâches restait inerte un instant après « Synchronisé »).

### Corrigé (terminal : le warning `preferences.d` à chaque commande)

- **`pkg install` imprimait « W: Unable to read
  …/etc/apt/preferences.d/ - DirectoryExists (2: No such file or
  directory) » à CHAQUE commande** : l'archive du bootstrap Termux pose
  `etc/apt/sources.list` mais pas TOUS les répertoires de configuration
  qu'apt parcourt ensuite. [ConfigurateurApt] crée désormais les
  répertoires APT standard (`preferences.d`, `apt.conf.d`,
  `sources.list.d`, `trusted.gpg.d`) à la pose du `sources.list` ET à
  chaque démarrage ([InstallateurBootstrap]) : idempotent (`mkdirs`),
  les préfixes installés par une version antérieure sont guéris SANS
  réinstallation.

### Corrigé (terminal : `android-sdk` — la disposition cmdline-tools échouait après le téléchargement)

- **« mv: cannot move '…/.staging-cmdline-tools/cmdline-tools' to
  '…/android-sdk/cmdline-tools/latest': No such file or directory »
  puis « android-sdk: disposition cmdline-tools/latest impossible. »** :
  sur une PREMIÈRE installation le répertoire parent
  `android-sdk/cmdline-tools/` n'existait pas au moment du
  déplacement — le `mv` échouait APRÈS le téléchargement de 172,6 Mio,
  un gaspillage réseau. Le script `android-sdk` fait désormais
  `mkdir -p` du parent AVANT le déplacement (script versionné 3 → 4 —
  re-distribution automatique aux appareils déjà installés, aucune
  réinstallation du préfixe).

### Corrigé (sync : le bouton Tâches s'active immédiatement)

- **« Une fois la sync terminée, le bouton pour afficher le bottomsheet
  liste des tâches devrait immédiatement être activé »** : l'UI armait
  le bouton Tâches via un SECOND aller-retour (`TasksRequest` après le
  `SyncResult`) — même servi par le cache serveur, cet IPC laissait le
  bouton inerte le temps d'un échange après « Synchronisé », et
  l'armement attendait AUSSI la préparation du classpath LSP. Le
  [SyncResult] PORTE désormais les tâches résolues par l'action de sync
  (champ `taches`, optionnel — un serveur antérieur ne l'envoie pas et
  le client retombe sur l'aller-retour) : le client publie les tâches
  sur le FAIT, dans la même trame main-thread que « Synchronisé », et
  AVANT la préparation du classpath LSP qui peut travailler derrière.
  Le fichier doré du protocole suit (nouveau champ au câble), et le
  test d'intégration serveur vérifie que les tâches portées et le
  listage par cache coïncident.

## [0.46.0] – 2026-10-02

### Refonte (console flux brut unique — parité Android Studio, ADR 0078)

Retour utilisateur v0.45.3 : « dans le cas de build les outputs sont
affichés dans deux endroits. Je veux utiliser un seul, peut-être que
l'utilisation de RecyclerView contribue au problème que je rencontre, de
même pour Sync. Je veux une refonte totale pour un affichage normal comme
ce que fait Android Studio. »

- **La console EST le flux de Gradle** : Gradle écrit lui-même ses lignes
  « > Task :app:xxx », « BUILD SUCCESSFUL in 6s » et « N actionable
  tasks: … » sur le flux stdout capturé (vérifié dans les sorties réelles
  `docs/tooling-scenarios/sorties/`) — la console hybride v0.42.0
  affichait des équivalents français CONSTRUITS des mêmes événements :
  chaque tâche et chaque synthèse apparaissaient DEUX fois, dans deux
  zones, deux styles, deux langues. La console montre désormais le flux
  TEL QUEL, comme la fenêtre Build d'Android Studio : un `TextView`
  monospace PAR CANAL (Sync et Build), le chip d'action choisit la
  console visible, l'autre s'accumule en coulisses — basculer ne perd
  rien, ne rejoue rien, ne reconstruit rien.
- **Plus de RecyclerView dans la console** : `ConsoleToolingAdapter`,
  `RangeeConsole`, `construireRangeesConsole` et les cinq layouts de
  rangées sont supprimés (l'onglet Problèmes garde sa liste cliquable —
  un cas légitime). Aucun DiffUtil, aucune soumission de liste, aucune
  reconstruction : application lotie par trame (un append par console),
  O(1) par ligne — la leçon de performance de l'ADR 0074 s'applique
  désormais à TOUT l'affichage.
- **Vue Sync en lignes** : une ligne par TRANSITION d'étape (« Libellé… »
  puis « Libellé ✓ 34,1s · 129,4 Mo reçus ») et une conclusion
  (« Synchronisation terminée en 8,4s — les tâches sont disponibles. ») ;
  les ticks d'octets en vol restent dans l'en-tête du panneau (compteur
  « étape n/N », progression), la console est l'historique.
- **Téléchargements** : une ligne par artefact terminé (« Téléchargé :
  kotlin-stdlib.jar · 34,2 Mo reçus au total »), jamais par tick
  d'octets ; l'état conflaté `telechargementsBuild` disparaît.
- **Ligne d'en-tête de build au format Android Studio** :
  « Exécution des tâches : [:app:assembleDebug] dans le projet MonIP » —
  le VRAI sélecteur Gradle se lit dans la console, celui que le terminal
  attend (cf. ci-dessous).
- **Annulation** : seule l'annulation reçoit une ligne de conclusion
  propre (« Build annulé ») — Gradle conclut lui-même succès et échec
  sur son flux, les dupliquer était le problème.
- **Réglage retiré** : « Afficher les tâches pendant le build » disparaît
  de la page de configuration du tooling (le champ DataStore reste
  dormant pour la compatibilité des réglages persistés) — le flux de
  Gradle porte ses lignes de tâches, il n'y a plus rien à filtrer.
- **Canal Taches** : les événements de tâches ne publient plus ni ligne
  ni état, mais le canal reste vidé (leçon v0.45.1 : la pompe unique du
  client ne doit jamais bloquer sur un canal sans consommateur).

### Corrigé (terminal : sélecteur de tâche incompris)

- **« gradle task:assembleDebug » échouait** (« Cannot locate tasks that
  match 'task:assembleDebug' as project 'task' not found ») alors que le
  même lancement depuis la feuille des tâches réussissait : la feuille
  passe le VRAI chemin (`:app:assembleDebug`), l'utilisateur tapait un
  préfixe inventé. Le script `gradle` du terminal (v3, `VersionneurScripts`
  2 → 3 — re-distribution automatique aux appareils installés) réécrit
  désormais « task:FOO » en « FOO » avec un avertissement explicite, et
  laisse les autres arguments INTACTS mot à mot ; la console affiche par
  ailleurs le vrai sélecteur dans sa ligne d'en-tête.

## [0.45.3] – 2026-10-02

### Corrigé (échec CI intermittent : `DaemonManagerTest` sur runner à cœurs comptés)

- **Le stderr de la fenêtre de connexion ne meurt plus en vol** : le test
  `le stderr est journalisé pendant la fenêtre de connexion même sans
  session` échouait par intermittence en CI (`AssertionError` après 5 s)
  alors qu'il passait localement — diagnostic confirmé par un test de
  régression déterministe qui échoue systématiquement sans correctif :
  l'hôte de test échoue la connexion SANS la moindre suspension, et
  l'annulation immédiate de la portée de la tentative
  (`nettoyerApresConnexionManquee`) pouvait devancer la simple mise en
  file du collecteur de stderr sur le dispatcheur — la ligne était
  annulée EN VOL et n'atteignait jamais le journal, laissant l'échec de
  connexion MUET (exactement le bug v0.35.0 qu'ADR 0061 devait fermer ;
  sur l'appareil, un socket cassé qui échoue vite produisait la même
  perte du diagnostic). Le correctif :
  - `brancherSorties` rend ses collecteurs à la tentative ;
  - `nettoyerApresConnexionManquee` les VIDANGE avant d'annuler la
    portée — `withContext(NonCancellable) { withTimeoutOrNull(250 ms) {
    joinAll() } }` : les lignes déjà émises rejoignent le journal à coup
    sûr, un lecteur bloqué ne retarde jamais la relance au-delà, et
    l'appel depuis une coroutine déjà annulée reste sûr (leçon T2) ;
  - nouveau test de régression déterministe
    (`une ligne de stderr en vol pendant la fenêtre survit à l'annulation
    de la portée`, process dont le stderr n'émet qu'après 50 ms — le
    délai écrase la microseconde du lancement à l'échec : sans vidange,
    échec à tous les coups).
- **Historique Git aligné sur l'identité du dépôt** : les quatre commits
  de correctif portaient des identités de build locales — auteurs,
  committers et taggers réécrits en `jjoblab <olson12jb@gmail.com>`
  (dates et contenus inchangés), config du dépôt alignée.

## [0.45.2] – 2026-10-02

### Corrigé (retour utilisateur : « BUILD SUCCESSFUL in 10s » affiché au bout de 200-300 s)

- **Fenêtre daemon du build rendue visible** : le signalement de terrain
  (simple projet, `task:assemble` depuis la feuille des tâches, chrono à
  200-300 s pour une console qui conclut « BUILD SUCCESSFUL in 10s ») a
  été reproduit en expérience contrôlée (Gradle 9.7.1, daemon tué puis
  relancé) : `ProjectConnection.connect()` rend un objet PARESSEUX en
  ~300 ms — MÊME sans daemon vivant — et tout le démarrage du daemon
  (60 s à 4 min sur téléphone) se produit DANS `newBuild().run()`, où le
  serveur ne publiait RIEN. La Tooling API y émet pourtant des statuts
  textuels (`Starting Gradle Daemon`, `Connecting to Gradle Daemon`)
  qu'aucun écouteur ne captait :
  - nouvel `EcouteurStatutDaemonBuild` (filtré sur les statuts du daemon,
    dédupliqué, thread-safe) branché sur le lanceur : « Starting Gradle
    Daemon » s'affiche PENDANT le spawn — la parité réelle avec la
    ligne « Starting Gradle Daemon… » de la console d'Android Studio ;
  - la ligne « daemon Gradle connecté (X ms) » de la v0.45.1 mesurait un
    objet en cache (~0 ms en toutes circonstances — elle affirmait une
    connexion inexistante) : elle est désormais publiée sur le FAIT
    « Connecting to Gradle Daemon », X étant le délai RÉEL pour obtenir
    un daemon (froid : spawn complet ; chaud : aller-retour) ;
  - la SYNC suit le même traitement : les statuts du daemon alimentent
    la phase DAEMON de l'arbre de sync (l'étape montre CE qu'elle
    attend), la phase DISTRIBUTION reste ce qu'elle était.
- **Décomposition honnête des durées en fin de build** : la durée
  RAPPORTÉE PAR GRADLE (« BUILD SUCCESSFUL in 10s » — son horloge démarre
  sur un daemon PRÊT, le démarrage est EXCLU) est désormais extraite du
  stdout (`ParseurSyntheseBuild.analyserDureeMs` : `in 10s`, `in 800ms`,
  `in 1m 30s`, `in 2m 3s 456ms`, `in 1h 2m`) et la console conclut sur
  une ligne qui nomme l'écart constaté : « build Gradle : 10 s ·
  démarrage du daemon : 235 s · total : 245 s » — sans durée observée
  (échec précoce, sortie redirigée), le total seul reste honnête. Le
  verdict serveur journalisé porte désormais les trois durées
  (`gradle=… ms, file=…`).

## [0.45.1] – 2026-10-02

### Corrigé (retour utilisateur : chemin de projet + console en retard)

- **Chemin de projet (`grantUri` vs `documentUri`)** : les projets « créés
  dans le dossier de travail » portent un `grantUri` d'arbre PARENT et un
  `documentUri` de document imbriqué — `ResoudreRepertoireProjet` résolvait
  depuis `grantUri` (dossier parent au lieu du projet) et les use cases
  SAF (`LireSyncState`/`EcrireSyncState`/`CalculerEmpreinteGradle`/
  `PreparerClasspathLsp`) recevaient une URI d'arbre pure que
  `DocumentsContract.getDocumentId` rejette : sync-state jamais lu ni
  écrit, classpath LSP jamais persisté. Le contrat passe à `documentUri`
  (nouvelle méthode de port `ArborescencesSaf.idDocumentDeUriDocument`,
  l'URI d'arbre pure renvoie `null`), les quatre usages de
  `EditorViewModel` suivent — l'état « Synchronisé » se restitue au
  retour du projet sans sync manuelle.
- **Console en retard (analyse du trajet des outputs)** : le trajet BRUT
  stdout/stderr était déjà direct (ligne à ligne, append par trame) — le
  retard venait de TROUS DE ROUTAGE, corrigés pour la parité Android
  Studio (« le serveur publie, le client affiche ») :
  - les `ProgressEvent` TEXTUELS du build (« Configuration :app… »)
    étaient publiés par le serveur puis JETÉS à la réception (`?: return`
    sur le détail de téléchargement) : ils deviennent des lignes de
    console immédiates. La fenêtre pré-tâches (connexion au daemon —
    30 s à 2 min à froid sur mobile, configuration) porte enfin un état
    visible : « Exécution des tâches : … », « connexion au daemon
    Gradle… », « daemon Gradle connecté (X ms) » ;
  - le canal des téléchargements du build n'avait AUCUN consommateur en
    production : au-delà de 4096 événements la pompe unique du client se
    serait bloquée sur `send`. La `PompeBuildTooling` (process-wide) le
    draine désormais vers une rangée de progression EN PLACE en tête de
    la vue Build (barre + volume cumulé + artefact courant), effacée au
    terme du build ;
  - la capture stdout/stderr de la SYNC (v0.41.1) publiait des lignes
    avec l'identifiant de REQUÊTE SYNC comme `buildId` — le client n'ouvre
    un canal de sortie QUE pour les builds : publication morte, jamais
    affichée depuis la refonte console v0.42.0. La capture est retirée
    (l'arbre des phases réelles reste l'état affichable de la sync) ;
  - la vidange de la progression sync vivait dans le `viewModelScope` de
    l'espace : écran fermé en pleine sync, le canal borné (256) se
    remplissait puis bloquait la même pompe. Elle devient process-wide et
    idempotente (`PompeBuildTooling.pomperSync`) ;
  - extraction `PublicateurProgressionBuild` (statuts textuels, écouteur
    téléchargements/configuration, diagnostics stderr) et
    `ConversionsDomaine` (traductions protocole → domaine) quand les
    limites de complexité ont été atteintes.

### Ajouté

- **Sondes de latence console** (mesure, pas correction à l'aveugle) :
  `AccumulateurLatence` (min/moy/max thread-safe) ; latence de TRANSPORT
  par build résumée à la fin dans `GradleApiImpl` (tag `ConsoleLatence`),
  latence de PUBLICATION dans `GradleService.ajouterLigne`, verdict serveur
  « build \<id\> conclu : X ms (file=N) » avec profondeur de file
  `EventBus.taille()` ; l'onglet CONSOLE et le filtre BUILD sont posés
  AVANT la préparation du build (la console est déjà là quand les lignes
  arrivent) ; `EvenementConsoleTexte.Ligne` porte un horodatage.

## [0.45.0] – 2026-10-01

### Ajouté (phase 4 du roadmap — wizard enrichi, ADR 0077)

- **`android-app` v1.3.0 — sections Android** : `minSdk` élargi (24, 26,
  29, 34 — plancher imposé par Navigation 2.10.2), nouveau `targetSdk`
  (34–37, défaut 37 = compileSdk) et nouveau `applicationId` dérivé du
  nom de package par la sixième fonction `defaultFrom`
  **`applicationIdFromPackageName`** (chaîne `appName → packageName →
  applicationId`, libre après saisie manuelle, resynchronisable).
  `android-library` v1.1.0 aligne son `minSdk`. Le `targetSdk` 37 des
  projets générés est indépendant du 28 délibéré de CodeIDE (ADR 0045 —
  W^X concerne l'app hôte, pas les projets des utilisateurs).
- **Aperçu renommable** : chaque nœud de l'arborescence du
  récapitulatif porte un crayon (masqué pour `.codeide/`) ouvrant un
  dialogue à validation immédiate (vide, séparateur, parent, doublon de
  frère). Le plan se recalcule **avec** la carte des renommages (clé =
  chemin original du nœud, valeur = nouveau nom du segment) — l'invariant
  de l'ADR 0017 tient : ce qui est planifié est ce qui est écrit. Les
  renommages de dossier et de fichier se composent (`PlannedFile.
  cheminOriginal` = identité stable), survivent à la mort du processus
  et sont emportés par la création ; un renommage rejeté par le domaine
  est annulé et l'arbre restauré — jamais d'impasse.
- **Dépendances communes (interrupteurs)** — Android : Coroutines 1.11.0,
  Retrofit 3.0.0 + Gson, Navigation 2.10.2, Room 2.8.5, Hilt 2.59.2 avec
  les fichiers d'exemple (`BddLocale` entité/DAO/base, classe
  `{{appName|resourceName}}Application` reliée au manifeste, miroirs
  `.java` en trois fichiers) ; Spring Boot : JPA + H2, Security,
  Actuator, Validation (alias sans version, BOM 4.1.1) ; KMP :
  serialization 1.11.0 + plugin, coroutines 1.11.0, datetime 0.8.0 avec
  un fichier d'usage par dépendance.
- **KSP 2.3.12** : les lignes 2.2.x refusent le Kotlin intégré d'AGP 9.4.1
  et le contournement officiel (KGP externe) est inapplicable
  (`BaseExtension` supprimé) — la ligne 2.3.x accepte le Kotlin intégré ;
  Room et Hilt fonctionnent en Kotlin **et** en Java (génération vérifiée
  jusqu'au dex).
- **Vérification étendue** : `scripts/verify-templates.sh` passe à
  **37 combinaisons** (`andr-deps`, `andr-deps-java`, `sb-deps`,
  `kmp-deps`, `kt-app-renoms` — renommage README → NOTES avec build et
  exécution réels), contrôles structurels par dépendance et langage.

### Modifié

- `CreateProjectRequest` accepte `cheminsRenommes` (défaut vide —
  compatible avec les appelants existants) ; `PlannedFile` porte
  `cheminOriginal` (renseigné seulement quand un renommage s'applique).
- La validation des renommages est en trois couches : dialogue (locale),
  ViewModel (métadonnées `.codeide/`), moteur (échec explicite sur nom
  multi-segments, doublon, métadonnées — les clés obsolètes sont ignorées
  comme les paramètres périmés).

## [0.44.0] – 2026-10-01

### Ajouté (phase 3 du roadmap — modèles avancés, ADR 0076)

- **`android-app` v1.2.0 — types de projet** : paramètre `projectType`
  (`empty-activity` défaut, `no-activity`, `basic-activity`). La variante
  tiroir livre `MainActivity` avec `ActionBarDrawerToggle` +
  `MaterialToolbar`, `FragmentAccueil` à arguments, les layouts
  `activity_main_tiroir.xml`/`fragment_accueil.xml`, `menu/tiroir.xml`,
  un thème `Theme.Material3.DayNight.NoActionBar` (conditionnel) et la
  dépendance `androidx.drawerlayout:drawerlayout:1.2.0` ; la variante
  sans activité retire l'`<activity>` du manifeste (`{{#if}}`) et ne
  génère aucune classe d'écran.
- **`android-app` — langage Java** : paramètre `language`
  (`kotlin` défaut, `java`). `MainActivity`, `FragmentAccueil`, `Greeter`
  et les deux tests sont déclinés en `.java` (ViewBinding par champs
  publics, `import static` des assertions JUnit) ; le bloc
  `kotlin { compilerOptions }` disparaît du build en Java pur.
- **Nouveau modèle `android-library`** : bibliothèque Android (.aar) —
  module `:library` (`com.android.library`), `consumer-rules.pro`,
  manifeste minimal, API publique `Greeter`, tests JUnit 4, README
  d'intégration (`:library:assembleRelease` →
  `library/build/outputs/aar/`), package `com.example.<nom>`.
- **Nouveau modèle `gradle-plugin`** : plugin Gradle en Kotlin —
  `kotlin-dsl` (compilateur **embarqué** dans la distribution : un KGP
  externe 2.2.21 se heurte au `kotlin-reflect` 2.4.0 du `gradleApi()`),
  `java-gradle-plugin` + `maven-publish` (artifactId de publication fixé
  à l'`artifactId`, le nom de projet pouvant contenir des espaces),
  extension `greeting` + tâche `greet` annotée `@DisableCachingByDefault`
  (exigée par `validatePlugins`), tests ProjectBuilder (`gradleTestKit()`),
  `pluginId` et `packageName` dérivés de l'`artifactId`
  (`packageFromArtifactId` réutilisée), classe principale
  `{{projectName|resourceName}}Plugin`.
- **Wizard** : les paramètres à plus de deux valeurs (`projectType`
  Android) passent des tuiles segmentées aux **cartes radio empilées**
  (chaque valeur garde son explication) ; `language` rejoint les tuiles.
  Libellés, sous-titres, icônes et explications bilingues fr/en.
- **Vérification** : `scripts/verify-templates.sh` passe de 24 à **32
  combinaisons** — 8 nouvelles (4 variantes Android avec APK + tests,
  2 bibliothèques avec AAR de release, 2 plugins avec
  `validatePlugins`/testkit) et deux contrôles E2E : publication maven
  locale + marqueur de plugin, et application du plugin depuis un
  projet consommateur jetable (`./gradlew greet` affiche le message
  configuré). Contrôles structurels par variante (aucun `.kt` en Java,
  aucun `MainActivity` sans activité, menu du tiroir présent).
- **Tests** : `ModelesPhase3Test` (9 tests — variantes, dérivation
  com.example, identifiants du plugin, i18n fr/en, noms hostiles) ;
  catalogues de `ModelesEmbarquesTest` et `TemplatesIntegrationTest`
  ancrent les 7 modèles ; 1415 tests au total, 0 échec.
- **Différé** : `compose-app` (ADR 0002 — Compose interdit jusqu'à
  réévaluation explicite).

## [0.43.0] – 2026-09-30

### Corrigé (phase 2 du roadmap — modèles : les trois nouveaux modèles
### fonctionnent enfin)

- **`android-app`, `spring-boot` et `kotlin-multiplatform` étaient cassés à
  la génération** (ADR 0075) : la clé i18n `gitattributes.entete` manquait
  dans leurs dictionnaires fr/en (le moteur échoue explicitement sur toute
  clé inconnue — la génération renvoyait `ECHEC … clé i18n manquante
  « gitattributes.entete »`),
  leurs chemins de sources étaient codés en dur
  (`src/…/jo/codeide/template/…` au lieu de `{{packageName|packagePath}}` :
  la déclaration `package` ne correspondait jamais au répertoire), le
  `Greeter.jvm.kt` KMP n'était pas câblé au manifeste (le `expect` restait
  sans `actual`) et les options communes (README, .gitignore,
  .editorconfig) étaient ignorées (aucune condition `when`).
- **Conventions de package par modèle** : deux fonctions `defaultFrom`
  nommées s'ajoutent au moteur — `packageFromAppName`
  (`com.example.<appName>` pour Android) et `packageFromArtifactId`
  (`com.example.<artifactId>` pour Spring Boot et KMP) ; `kotlin-jvm` et
  `java` conservent `packageFromNameAndAuthor`. Les valeurs dérivées
  peuvent lire les paramètres déclarés avant elles (la source précède sa
  dérivée : `appName`/`artifactId` avant `packageName` dans les manifestes)
  et suivent leurs sources à chaque frappe tant que l'utilisateur ne fige
  pas le champ. Repli déterministe : `com.example.app`.
- **Nouveau filtre `resourceName`** : normalise n'importe quelle saisie en
  nom de ressource Android sûr (NFD, marques éliminées, mots capitalisés,
  repli `App`, préfixe si chiffre en tête) — `« mon éclat & 2048 »` →
  `MonEclat2048`. Alimente le nom du thème (`Theme.<Nom>` dans
  `themes.xml` et le manifeste Android).
- **Chaînes d'outils des projets générés, alignées sur le Gradle 9.7.1 du
  wrapper** (versions vérifiées sur Maven Central / Google Maven) :
  Android passe à AGP 9.4.1 avec Kotlin INTÉGRÉ (plus de plugin
  `kotlin-android`, `kotlin { compilerOptions }`), `compileSdk 37.2`,
  JUnit 4 pour les tests (le `kotlin("test")` nu ne résout pas
  `kotlin.test.Test` sans KGP) ; Spring Boot passe à Boot 4.1.1 + Kotlin
  2.2.21 + plugin `kotlin-spring` (CGLIB ne proxyfie pas les classes
  finales) + BOM via `platform()` native ; KMP passe à Kotlin 2.2.21 avec
  une tâche `run` JavaExec (le plugin `application` est incompatible KMP
  sur Gradle 9).
- **`scripts/verify-templates.sh` couvre désormais les 24 combinaisons** :
  les 18 historiques + 6 nouvelles (Spring Boot fr/en, KMP fr/en avec
  exécution réelle `run` et salutation contrôlée, Android fr/en avec
  `assembleDebug` + `testDebugUnitTest` + APK contrôlé, SDK Android via
  `CODEIDE_ANDROID_SDK`). Le harnais avait révélé que les trois modèles
  n'avaient jamais été vérifiés faute d'y figurer.

### Ajouté (enrichissements des trois modèles)

- **Android** : `res/values/strings.xml` (`app_name`), `colors.xml`,
  `themes.xml` (`Theme.Material3.DayNight` + couleurs primaires),
  `proguard-rules.pro` (release minifiée), `ExampleUnitTest.kt`,
  `app/.gitignore` ; le manifeste référence `@string/app_name` et
  `@style/Theme.<Nom>`, gagne `allowBackup`/`supportsRtl` ;
  `settings.gradle.kts` échappe le nom du projet (`kotlinString`).
- **Spring Boot** : `GreeterRepository.kt` (patron repository),
  `ApplicationTests.kt` (`@SpringBootTest` — le contexte démarre),
  `application.yml` remplace `application.properties` (une seule source
  de configuration) ; le contrôleur gagne `POST /salutations` et
  `GET /salutations` ; le contenu est traduit fr/en comme les autres
  modèles.
- **KMP** : `commonTest` enrichi (ordre de `greetAll`, plateforme nommée).
- **Les trois README sont i18n (fr/en)** avec arbre de structure aux
  chemins réels (`{{packageName|packagePath}}`) ; les `.gitignore` et
  catalogues de versions suivent la même convention de clés que
  `kotlin-jvm`.

### Modifié (moteur)

- `TemplateDefaultFunctions.Sources` expose `valeursParametres` (copie
  figée des valeurs effectives de la passe 2) ; cinq fonctions sont
  enregistrées au lieu de trois ; `TemplateFilters` en compte onze
  (`resourceName`).

## [0.42.0] – 2026-09-30

### Modifié (phase 1 du roadmap — performance console : architecture hybride)

- **Console hybride comme Android Studio** (ADR 0074) : le corps de l'onglet
  Sortie se scinde en deux zones empilées. La zone STRUCTURÉE (RecyclerView :
  arbre d'étapes de sync, tâches de build, synthèse) garde DiffUtil sur peu
  de rangées ; la zone TEXTE (TextView monospace scrollable dans un
  ScrollView, séparée par un filet, visible en vue Build) reçoit les lignes
  stdout/stderr brutes de Gradle par `append()` direct — O(1) par ligne.
  Objectif du roadmap atteint : un build de 725 ms s'affiche en moins d'une
  seconde (2 minutes avant — cause : chaque ligne émettait l'état complet,
  reconstruisait toutes les rangées et passait DiffUtil, O(N²) cumulé).
- **`GradleService` : plus d'émission d'état par ligne.** `ajouterLigne`
  publie sur un flux dédié `lignesBrutes` (`SharedFlow` avec rejeu 2 000 =
  le tampon borné, tête tronquée, `DROP_OLDEST` : la pompe n'est jamais
  bloquée par un abonné lent). `EvenementConsoleTexte` (genre `Ligne` /
  `Vider`) remplace `LigneConsole.Sortie`, supprimé : `EtatGradle.lignes`
  ne porte plus que les genres typés (tâches, étapes). Les gardes
  historiques (buildId, annulation) et l'avertissement bénin apaisé (C5)
  sont conservés. `Vider` suit le même cycle de vie que la fenêtre : nouveau
  build suivi (`suivreBuild`) et rattachement d'un espace (`attacher`).
- **Lotissement par trame dans `PanneauConsoleFragment`** : les lignes
  arrivées dans la même passe de la boucle de messages partent en UN seul
  `append` (une notification, une passe de layout par trame — la
  reconstruction après rotation rejoue jusqu'à 2 000 lignes en un lot). Le
  tampon du `TextView` est un `Editable` : l'ajout est en place et les spans
  de couleurs (stderr rouge, apaisé atténué) voyagent avec les lignes.
- **Auto-défilement honnête de la zone texte** : le bas est suivi tant que
  l'utilisateur y est resté (intention capturée AVANT l'ajout) — un lecteur
  remonté dans l'historique n'est jamais rabattu en bas ; un build fini ne
  défile plus (rien ne le déclenche). Défilements dédupliqués (un post
  vivant au plus).
- **Collecte du flux sur le `viewLifecycleOwner`** : elle survit à un
  `onStop` (le retour d'un onglet du panneau ne rejoue PAS l'historique —
  pas de doublement) et meurt avec la vue (une rotation se ré-abonne et
  reconstruit exactement depuis le rejeu : un `Vider` tombé de la fenêtre
  emporte tout ce qui le précédait, une ligne d'avant le dernier vidage ne
  peut jamais rester seule en scène).
- **Suppressions** : `RangeeConsole.LigneGradle` et `LigneGradleHolder`
  (les rangées brutes n'ont plus de raison d'être dans le RecyclerView),
  `layout/ligne_gradle.xml`, `NB_LIGNES_GRADLE_SOUS_ETAPE` et le bloc mort
  des sorties SYNC dans `arbreEtapesSync` (aucune pompe de sortie de sync
  n'existait en production — seul le build alimente la zone texte).
- **`EditorViewModel.lignesBrutesConsole`** : exposition du flux dédié à
  côté de `etatGradle` (le fragment ne parle qu'au ViewModel).

### Tests

- `GradleServiceTest` : les lignes brutes se vérifient désormais sur le
  rejeu (`replayCache`) — accumulation ordonnée, bornage 2 000 (tête
  tronquée), gardes buildId/annulation, apaisement C5, `Vider` à
  `suivreBuild` et `attacher`, et PLUS AUCUNE ligne brute dans
  `etat.value.lignes`. Nouveau test de garde annulation.
- `RangeesConsoleTest` : la vue Build garde les tâches + la synthèse seules
  (une étape résiduelle ne fuit pas) ; la vue Sync ne mélange ni tâches ni
  lignes brutes.
- `ToolingEditorViewModelTest` : le câblage sortie/état vérifie le rejeu
  APRÈS le dernier `Vider` (helper `lignesZoneTexteApresDernierVider`) —
  rattachement d'un build en vol compris.

## [0.41.0] – 2026-09-29

### Ajouté (tooling Gradle — étapes dynamiques, sync suivante immédiate, chip d'action, stats classpath — prompt de suivi)

- **Phase 0 — Scénarios de sync réels** (étape A) : 10 scénarios Gradle
  réels exécutés (Kotlin/JVM pur, avec deps, multi-modules, Groovy, buildSrc,
  échec config, offline, deps non cachées + résolution forcée). Sorties
  brutes dans `docs/tooling-scenarios/sorties/`, RAPPORT.md (368 lignes)
  analyse les signaux Tooling API observés, invalide 3 hypothèses (DAEMON
  non détectable via Tooling API, DISTRIBUTION à chaque changement de
  wrapper, plugins téléchargés même sans dépendances runtime) et propose
  le catalogue d'étapes dynamique. Script `scripts/scenarios-tooling.sh`
  rejouable.
- **Correctif du progress circulaire des étapes** (étape B, ADR 0072) :
  remplacement du `CircularProgressIndicator` Material (écrasé/rogné à 16 dp
  + clignotement à chaque rebind) par un `AnneauTournant` dédié (drawable
  vectoriel 16 dp / trait 2 dp + `ObjectAnimator` global UNIQUE partagé via
  `AnneauTournantState` — zéro fuite d'animateur). `DiffUtil.getChangePayload`
  granulaire + `DefaultItemAnimator.supportsChangeAnimations = false` —
  plus de clignotement sur tick de durée. 6 tests Robolectric.
- **Suppression du concept « sautée / En cache »** (étape C1, ADR 0073) :
  `SyncProgress.sautee` supprimé du protocole ; `ConteurPhasesSync.sauter()`
  supprimé ; la distribution en cache n'est plus émise du tout par le
  serveur — le client ne l'affiche pas. `StatutEtapeSync.SAUTEE`,
  `EtapeSyncAffichee.sautee`, `EtatEtapeArbre.sautee`, `R.string.editor_
  console_etape_en_cache`, `point_etape_sautee.xml` supprimés. Une étape
  non concernée n'existe plus dans la liste.
- **Sync suivante immédiate** (étape C2, ADR 0073) : nouveau fichier
  `.codeide/local/sync-state.json` (comme `lsp-classpath.json`) stocke
  empreinte SHA-256 des fichiers Gradle + tâches + durées + stats. Au
  retour d'un projet dont l'empreinte n'a pas changé, l'UI affiche
  immédiatement « Synchronisé · il y a X » + les tâches. Revalidation
  silencieuse en arrière-plan (pas de déroulé visible). En cas d'échec,
  l'état est conservé (pas de rouge). En cas d'échec de sync manuelle,
  le state précédent reste valide pour le retour.
- **Chip d'action unique** (étape D, ADR 0073) : un UNIQUE chip reflète
  l'action Gradle courante (Sync / Build / Tâches / Classpaths…), NON
  cliquable — plus de bascule. La console montre toujours l'action courante
  (ou la dernière). Aucun chip s'il n'y a eu aucune action.
  `ActionEditor.BasculerFiltreConsole` supprimée. Layout
  `fragment_panneau_console.xml` : `ChipGroup` Sync/Build remplacé par un
  seul `Chip` non cliquable.
- **Stats classpath par module** (étape E, ADR 0073) : `ClasspathModule`
  (protocole) et `ModuleClasspath` (domaine) étendus avec 10 champs
  optionnels (nbJars, nbAars, nbSources, varianteAndroid, nbDependancesProjet,
  fichiersGeneres, androidJar, ignore, raisonIgnore, avertissements).
  `ClasspathHandler` calcule les stats côté serveur. Le pied de sync
  affiche un récapitulatif (« 3 modules · 312 jars · 4 sources · 12 AARs »).
- **Aperçu v3 versionné** (étape F) : `docs/preview/apercu-tooling.html`
  (24 Ko, 153 lignes) — source de vérité visuelle du tooling (§6 du prompt
  de suivi). 8 palettes (Indigo, Bleu, Turquoise, Vert, Ambre, Rouge,
  Violet, Rose) × clair/sombre, 6 scènes (sync 1er lancement / suivante /
  Gradle modifié / build / config / tâches), mode tablette.

### Corrigé (tooling Gradle professionnel — comme Android Studio)

- **Faux négatif JDK à l'ouverture d'un projet** (correctif n°1, race
  condition) : la sync d'ouverture partait DÈS la première connaissance du
  projet, AVANT que l'observateur d'outils (`ObservateurOutilsTerminal`)
  n'ait émis son premier état réel. La garde JDK lisait alors la valeur
  PAR DÉFAUT (`EtatOutilsTerminal()` — tout `false`) et refusait la sync
  avec « JDK absent — les outils du terminal ne sont pas installés. »
  alors que le JDK ÉTAIT installé. Nouveau drapeau `EtatOutilsTerminal.
  initialise` (passé à `true` dès le premier scan réel) ; la sync
  d'ouverture attend désormais le premier état initialisé (garde bornée
  à 5 s — un appareil lent ne bloque pas) avant de démarrer. Plus de
  refus JDK mensonger.
- **Bouton « Tâches » resté grisé après une sync réussie** (correctif n°2)
  : après une sync utile, un second `TasksRequest` partait vers le
  serveur pour remplir `tachesDisponibles`. En cas d'échec silencieux de
  ce second appel (connexion passagère, timeout), `tachesDisponibles`
  restait `null` et le bouton restait grisé — l'utilisateur voyait «
  Synchronisé en X s » dans l'en-tête mais ne pouvait pas ouvrir le
  sélecteur. Désormais, la sync réussie publie `tachesDisponibles =
  emptyList()` en cas d'échec du listage différé — le bouton s'active
  quand même, le clic retombe sur `listerTachesProjet` côté orchestrateur
  (avec son indicateur de vol) au lieu d'ouvrir un sélecteur vide qui ment.
- **Lignes `> Task :app:xxx` non affichées pendant un build** (correctif
  n°3) : la console basculait sur l'onglet Sortie mais PAS sur la vue
  BUILD — l'utilisateur restait sur la vue SYNC (l'arbre des étapes) et
  ne voyait pas les tâches. Nouvel état `EtatEditor.filtreConsole`
  (persisté pour la rotation), piloté par le ViewModel : `executerTaches
  Gradle` bascule vers BUILD automatiquement, `synchroniserProjetGradle`
  bascule vers SYNC. L'utilisateur peut revenir à SYNC à la main via les
  chips (nouvelle action `ActionEditor.BasculerFiltreConsole`). Le
  fragment lit la vérité du ViewModel au lieu de porter l'état.
- **Synthèse `BUILD SUCCESSFUL in Xs + N actionable tasks: M executed
  (K up-to-date)` manquante** (correctif n°4) : la dernière ligne stdout
  de Gradle (« N actionable tasks: M executed[, K up-to-date] ») était
  capturée mais pas affichée — seule la synthèse « Build réussi en X »
  s'affichait. Nouveau `ParseurSyntheseBuild` côté serveur extrait les
  comptes au fil de l'eau ; nouveaux champs `BuildFinished.
  actionableTasks/executedTasks/upToDateTasks` voyagent dans le
  protocole ; `EtatBuild` les propage ; `RangeeConsole.SyntheseBuild`
  les porte ; `ligne_synthese_build.xml` gagne une seconde ligne
  monospace pour les compter (« 37 tâches actionnables : 2 exécutées,
  35 à jour »), comme la console d'Android Studio. Fichiers dorés
  régénérés.
- **En-tête du BottomSheet non mis à jour pendant/après un build** et
  confusion « annulé » vs « échoué » (correctif n°5) : un build ANNULÉ
  était affiché en rouge `ECHOUE` (le serveur était muet sur la cause —
  toujours `succeeded=false` avec le message « annulé »). Nouveau champ
  `BuildFinished.cancelled` distingue l'annulation de l'échec ;
  `pomperFin` (client) et `versEtatBuild` (api) mappent à
  `StatutBuild.ANNULE` quand `cancelled=true`. `PanneauToolingController.
  rendre` ré-initialise proprement l'en-tête (pastille, titre, sous-titre,
  minuteur) quand `canal` est `null` — plus de résidu visuel d'un build
  précédent à la ré-ouverture d'un espace dont l'état a été nettoyé.
- **État des outils (JDK/Gradle/SDK) non exposé à l'UI** (correctif n°6) :
  `EditorViewModel.etatOutilsTerminal` devient public (était `private`
  par oubli) — la carte terminal du tiroir n'exposait que
  `bootstrapInstalle`, laissant l'UI incapable de distinguer « JDK
  absent » de « sync refusée par cache non peuplé ». Tout écran de
  diagnostic ou bandeau futur peut désormais s'abonner à l'état réel
  des outils.

## [0.40.0] – 2026-09-29

### Modifié (correspondance avec l'aperçu du tooling — retour utilisateur
sur la 0.39.0)

- **Les anciens écrans de la console disparaissent** (retour : « tu n'as pas
  enlevé les anciennes écrans du console ») : le filtre de canal devient un
  choix EXCLUSIF à deux états — les chips Sync/Build vivent dans un
  `ChipGroup` à sélection unique EXIGÉE (`singleSelection` +
  `selectionRequired`), l'une des deux est toujours active, Sync par défaut
  comme l'aperçu. La CHRONOLOGIE BRUTE (mode « aucune chip » : sorties
  stdout/stderr en liste plate, étapes en lignes de texte) n'est plus un
  écran ; la vue Sync ne mélange plus les sorties brutes sous l'arbre ; la
  vue Build ne montre plus QUE les tâches et leur synthèse (les diagnostics
  restent dans l'onglet Problèmes, le journal applicatif dans l'onglet
  Journal). La rangée de tâche perd son étiquette de canal par ligne (le
  chip dit déjà qui parle) — `ligne_sortie.xml` devient
  `ligne_tache_console.xml`.
- **Le téléchargement de la distribution ne s'affiche que s'il a LIEU**
  (protocole v5, `PROTOCOL_VERSION` 5) : nouvelle phase SAUTÉE
  (`SyncProgress.sautee`) — quand la distribution Gradle est déjà en cache
  (marqueur `.ok` sondé par `EtatsDistribution`), le serveur publie la phase
  DISTRIBUTION comme SAUTÉE (conclue, durée 0, aucun travail) au lieu d'un
  « ✓ 0 s » mensonger d'un téléchargement qui n'a pas eu lieu ; la rangée de
  l'arbre porte un point gris plein, un libellé atténué et « En cache » à la
  place de la durée. La phase ouverte et sondée (barre, octets, artefact)
  n'apparaît que si la distribution MANQUE. Fichiers dorés régénérés.
- **Plan d'affichage à 7 étapes comme l'aperçu** (l'aperçu déroule 7
  rangées) : `MODELE_IDE` et `DEPENDANCES` se produisent pendant la MÊME
  résolution (les téléchargements alimentent le modèle) et l'ancienne
  rangée DEPENDANCES restait « ○ à vie » sur une sync sans téléchargement —
  les deux phases partagent désormais la rangée « Dépendances et modèle
  IDE » (état consolidé, durée cumulée, compteur des dépendances conservé à
  la fin) ; le compteur de l'en-tête suit le plan AFFICHÉ (« étape n/7 »).
  Les libellés passent au nominatif (« Distribution Gradle », « Modèle des
  tâches »…) — le marqueur porte l'état, le libellé ne le répète plus.
- **Pied de conclusion de la sync** (l'aperçu clôt le déroulé) : «
  Synchronisation terminée — les tâches sont disponibles. » ou, honnête
  quand AUCUN octet n'a été reçu (distribution en cache, dépendances en
  cache) : « Projet à jour, rien à télécharger — les tâches sont
  disponibles. »
- **Sous-titre de succès détaillé** : « N modules · N tâches · aucun
  téléchargement / classpaths prêts » (le compte des modules vient du
  compteur final de CLASSPATHS) — repli sur « Tâches disponibles : N »
  si le compte manque.

### Ajouté

- Marqueur « sautée » de l'arbre (`point_etape_sautee.xml`, jeton
  `?attr/colorOutline` — jamais de couleur dure) et chaînes FR/EN des sept
  étapes, « En cache » et les pieds de sync.

### Non livré

- « Daemon réutilisé » en rangée sautée (l'aperçu chaud montre « réutilisé ») :
  la Tooling API n'expose AUCUN signal honnête de réutilisation du daemon —
  la durée MESURÉE de la phase DAEMON reste affichée plutôt qu'un statut
  deviné.
- Les téléchargements visibles DANS la vue Build (rangée « Téléchargement
  des dépendances n/N » + artefacts, addendum §6) : le canal
  `observeTelechargementsBuild` existe côté domaine mais n'est pas encore
  consommé par la console — inchangé depuis 0.39.0.
- Écran de configuration enrichi §7 (commande effective, recherche,
  conflits, 2 colonnes) — toujours différé depuis 0.39.0.

## [0.39.1] – 2026-09-29

### Corrigé

- **Échec CI (`lintDebug`)** : `UselessParent` dans
  `activity_editor.xml` — `ligne_tooling` (vertical) ne contenait qu'un
  seul enfant, la rangée horizontale de l'en-tête enrichi (introduite en
  0.39.0, étape 5a). Les deux `LinearLayout` sont fusionnés en une seule
  rangée horizontale portant l'id `ligne_tooling` : même position dans le
  panneau, mêmes vues enfants (pastille, titre, sous-titre, chrono,
  Arrêter), même visibilité par défaut — hiérarchie plus plate, un vue
  de moins. Aucun changement fonctionnel ; le test de layout
  (`ActivityEditorLayoutTest`) reste vert sans modification.

## [0.39.0] – 2026-09-29

### Ajouté (tooling professionnel v4 — UI complète, prompt « tooling Gradle
professionnel » étape 5, §3.3)

- **En-tête enrichi du panneau** (étape 5a) : pastille de canal colorée
  (teal Sync / bleu Build / violet Tâches harmonisées au primaire — ADR
  0059) avec SPINNER animé en vol, coche verte de succès, croix rouge
  d'échec (`colorSucces`/`colorError` suivent les 8 palettes et la nuit) ;
  titre « Synchronisation Gradle · étape n/N » pendant la sync ; sous-titre
  = étape courante + détail (« 42 Mo · 3 éléments — kotlin-stdlib.jar »)
  annoncé à TalkBack (`accessibilityLiveRegion`) ; progression
  DÉTERMINÉE quand les octets totaux sont connus (recus/total), pleine au
  succès, indéterminée sinon ; peek du sheet élargi. Le présentateur pur
  s'étend (`StatutEntete`, `libelleEtape`, sous-titre structuré) —
  `EtatEnteteTooling` reste testé sans cadre Android.
- **Console en arbre d'étapes avec chips Sync/Build** (étape 5b) : barre
  d'outils à chips de filtre EXCLUSIVES (vue Sync = ARBRE, vue Build =
  tâches + synthèse, aucune chip = chronologie brute — l'état survit à la
  rotation) ; l'arbre montre les 8 phases du plan v4 avec marqueurs
  ✓/spinner/○ (les non-annoncées restent visibles : le chemin complet se
  lit, durée MESURÉE seulement — jamais devinée, règle 9) ; détail de
  téléchargement indenté sous l'étape active (barre déterminée,
  « 42 Mo / 130 Mo · 3 / 37 », artefact courant) ; synthèse de fin de
  build (réussi/échoué/annulé) ; BANDEAU d'échec avec « Voir les
  problèmes » (onglet dédié) et « Réessayer » (relance des mêmes tâches) —
  plus d'échec muet. Bouton Tâches armé par le cache de la sync,
  info-bulle honnête tant qu'il est éteint. Constructeur de rangées PUR
  (`construireRangeesConsole`) testé ; adaptateur réécrit
  (`ConsoleToolingAdapter`, 4 genres de rangées).
- **Configuration intégrée au conteneur de la console** (étape 5c) : le
  `DialogueConfigToolingFragment` plein écran disparaît — la page vit DANS
  le panneau inférieur (`PanneauConfigToolingFragment`, fragment enfant
  ajouté une fois puis montré/caché), flèche retour en tête, retour
  système referme la configuration avant l'espace (LIFO) ; bouton d'accès
  LIBELLÉ dans la barre d'outils ; fond = jeton du panneau (§8) ; même
  contrat de réglages (rendu idempotent DataStore, persisté à l'instant,
  correctif n°10).
- **Feuille Material 3 de sélection des tâches** (étape 5d) : BottomSheet
  avec champ de RECHERCHE (filtre en direct, insensible à la casse, nom ou
  chemin), tâches RÉCENTES en chips (la dernière exécution suivie),
  GROUPES Gradle dans l'ordre d'apparition (« autres » pour les sans
  groupe), module d'origine par tâche, lancement au clic — remplace la
  liste plate du `MaterialAlertDialog`. **`ouvrirSelecteurTaches` répond
  d'abord depuis le cache de la sync (`tachesDisponibles`) : aucun
  aller-retour, aucune latence** (correctif n°6 — plus de 30 s d'attente).
  Tâches dans les ARGUMENTS : la feuille survit à la mort du processus.
  L'échec de listage n'est plus AVALÉ : `EffetEditor.ErreurListageTaches`
  → snackbar + action « Réessayer » (correctif n°6). États vides distincts
  (« synchronisation en cours » vs « aucune correspondance »).
- **Mappeur de libellés de phases partagé** (`LibellesEtapesSync`) : fin de
  la duplication présentateur/console ; `OctetsLisibles` formate les
  tailles (« 1,2 Mo », « 340 Ko »).

### Corrigé

- **`scripts/bump-version.sh`** : un `grep -qF` non ancré matchait la
  PROSE du journal (le CHANGELOG documente le correctif v0.37.1 et cite
  le marqueur `s|^## [Non publié]|…|`) — le script prenait la branche
  « marqueur ouvert » sans rien insérer, avec un message de succès
  mensonger. Le grep est désormais ancré (`^`), découvert à la livraison
  de cette version même.

### Non livré dans cette version (différé, documenté)

- L'écran de configuration ENRICHI du §7 (affichage complet : masquer
  UP-TO-DATE, horodatage, retour à la ligne, taille de police ; sync à
  l'ouverture, bandeau de changement de `build.gradle*`,
  `--refresh-dependencies` ; exécution `--parallel/--build-cache/
  --configuration-cache/--continue`, niveaux de journal, traces,
  `--max-workers`, `-Xmx`, conflits détectés ; bloc « Commande effective »
  recalculé en direct ; recherche de réglages ; 2 colonnes dès sw600dp) et
  le `ConstructeurArgumentsGradle` unique — l'écran INTÉGRÉ livré ici
  garde les réglages v3 (affichage des tâches, hors ligne, arguments
  libres, orchestrateur vivant).
- La règle lint « aucun `#RRGGBB` dans feature:editor » (§8) : les
  nouveaux layouts/drawables n'introduisent AUCUNE couleur dure (jetons
  du thème uniquement), la rétro-vérification des fichiers historiques
  reste à poser.

## [0.38.0] – 2026-09-29

### Ajouté (tooling professionnel v4 — fondations, prompt « tooling Gradle
professionnel » étapes 1-4/6)

- **Protocole v4** : phases de sync RÉELLES (`OUTILS`, `DISTRIBUTION`,
  `DAEMON`, `CONFIGURATION`, `MODELE_TACHES`, `MODELE_IDE`, `DEPENDANCES`,
  `CLASSPATHS` — l'ancienne `CONNEXION` mentait : `connect()` ne télécharge
  rien), `SyncProgress` enrichi de détails (octets reçus/total, élément,
  compteur — champs à défaut, compatibles), `ProgressEvent` porteur d'un
  `DetailTelechargement` structuré, arguments (`--offline`, libres)
  embarqués dans `SyncRequest`/`ClasspathRequest`. Dorés régénérés (28) par
  le nouveau `RegenerateurDoresTest` (`REGENERER_DORES=1` — régénération
  VOLONTAIRE, relue en diff, jamais en CI). ADR 0069.
- **API Tooling 9.7.1 vérifiée par `javap` avant tout code** (règle 9) :
  quatre suppositions corrigées par les faits (paquet `events.download` et
  non `events.file` ; `GENERIC` et non `GENERIC_PROGRESS` ; octets d'un
  téléchargement connus seulement à SA FIN ; `setStreamedValueListener`
  retourne `void` — le liait à `kotlin.run`, trouvé par fichier témoin).
- **Action unique de sync** (`ActionSyncModeles`) : une SEULE requête
  résout `GradleProject` puis `IdeaProject` (l'ancienne double suite de
  `model().get()` configurait le build DEUX fois) — les transitions de
  phases streament par `BuildController.send()` vers le
  `StreamedValueListener` (marqueur java-sérialisable : l'action s'exécute
  DANS le daemon, jamais de lambda capturé).
- **Téléchargements visibles pour TOUTE action Gradle** (addendum §6) :
  `EcouteurProgressionCommun` (FILE_DOWNLOAD + PROJECT_CONFIGURATION)
  alimente la sync (phases DEPENDANCES/CONFIGURATION) ET le build
  (`ProgressEvent` structuré, canal `observeTelechargementsBuild` du
  domaine) — débit borné (5 événements/s par élément), nom d'artefact
  réduit au dernier segment d'URI (règle 15). Écouteur HISTORIQUE de
  statut pour les descriptions textuelles de la distribution.
- **Sondes honnêtes de la distribution** : installée = marqueur
  `wrapper/dists/<nom>/<hash>/*.zip.ok` (layout vérifié sur un
  `GRADLE_USER_HOME` réel) ; en cours = taille des fichiers `.part`
  sondée toutes les 500 ms (la Tooling API ne donne AUCUN octet pour la
  distribution) ; phases opportunistes ouvertes SEULEMENT si Gradle émet.
- **Cache serveur** (`CacheSync`) : `taches()` et `classpath()` répondent
  sans re-résolution après une sync (mesuré en intégration : quelques ms).
- **Délai d'inactivité de la sync** : 90 s SANS événement (fenêtre RÉARMÉE
  à chaque signe de vie — `SyncStarted`, `SyncProgress`) au lieu de
  5 minutes de total : un premier lancement sur réseau mobile qui
  télécharge lentement ne meurt plus, seul le silence tue.
- **État client v4** : `etapesAffichees` DÉRIVÉES des lignes de console
  (l'arbre EST l'état), `etapeCourante`/`numeroEtape`/`totalEtapes`
  (compteur d'en-tête), `tachesDisponibles` remplies à la fin d'une sync
  utile (listage instantané) et invalidées à la suivante ;
  `EtatEnteteTooling` (titre, sous-titre d'étape, progression déterminée,
  couleur, chrono, arrêt) sorti du présentateur pur `PresentationTooling`
  + `DetailsEtapesSync`.

### Changé

- **`PanneauToolingController`** : le rendu tooling de l'en-tête du panneau
  inférieur quitte `EditorActivity` (107 lignes) — l'activité ne garde que
  la collecte et le cycle de vie ; le contrôleur porte le ticker
  `repeatOnLifecycle(STARTED)` et l'horloge injectée (correctif n°12),
  le fondu et le peek.
- **Les arguments réglés s'appliquent à la sync et au classpath** : la
  chaîne `AppSettings` → `OptionsTooling.argumentsBuild()` → use cases →
  requêtes → `withArguments` est complète.

### Corrigé

- **Correctif n°9** : plus aucun `!!` dans `feature:editor` — les six
  fragments passent à `checkNotNull(liaisonAmorce)` à diagnostic lisible,
  `DecisionNotificationTooling` au smart-cast de branche.
- **Correctif n°10** : le `doAfterTextChanged` du champ d'arguments de la
  configuration tooling s'armait AUSSI sur le `setText` programmatique du
  rendu (champ figé sur une valeur périmée, ré-écriture au prochain flou).
  La décision vit dans `SaisieArguments` (pur, 5 tests) ; le rendu signale
  ses écritures par le même drapeau `renduEnCours` que les interrupteurs.
- **Correctif n°11** : `PresentationTooling` (pur, 23 tests FR/EN)
  remplace les DEUX cascades dupliquées `EditorActivity
  .libelleActiviteTooling` et `PanneauConsoleFragment
  .libelleStatutTooling` ; `EditorActivity.dureeLisible` disparaît au
  profit du formateur partagé `DureesLisibles`.
- **Correctif n°12** : le chrono de l'en-tête ne tourne plus en
  arrière-plan (`repeatOnLifecycle(STARTED)` au lieu du `while (true)`
  nu) et lit l'horloge INJECTÉE `TimeProvider` au lieu de
  `System.currentTimeMillis()`.

### Non livré dans cette version (resté à l'étape UI 5)

- L'UI complète du prompt §3.3/§7 : en-tête enrichi (sous-titre et
  progression déterminée affichés), arbre de console avec chips
  Sync/Build, configuration intégrée AU CONTENEUR de la console (le
  dialogue plein écran existe toujours), sélecteur de tâches en bottom
  sheet (recherche, groupes, récentes), écran de configuration enrichi
  (§7 : affichage, exécution, environnement, orchestrateur, commande
  effective, recherche de réglages). Les fondations (état, présentateur,
  contrôleur, `tachesDisponibles`, canaux) sont posées et testées — le
  branchement visuel est le travail de l'étape suivante.

## [0.37.6] – 2026-09-29

### Corrigé (layout XML — ViewBinding pointait toujours sur l'ancien FQN `jo.codeeditor.view.SymbolBarView`)

- **`Cannot access class 'SymbolBarView'` persistait** malgré la
  migration des imports Kotlin vers `jo.codeeditor.view.chrome.*`
  (v0.37.5). Cause racine : le layout XML
  `feature/editor/src/main/res/layout/activity_editor.xml` (ligne 219)
  référençait encore la classe par son **FQN pré-v3.38.0**
  `<jo.codeeditor.view.SymbolBarView>` — or, en Android, **le générateur
  ViewBinding dérive le type des champs de binding depuis l'attribut
  `android:class` (ou le tag racine) du XML**. Tant que le XML portait
  l'ancien FQN, `liaison.barreSymboles` était typée
  `jo.codeeditor.view.SymbolBarView` (classe introuvable depuis la
  3.38.0) — Kotlin ne pouvait ni résoudre `setOnSymbolTap` ni appliquer
  l'extension `View.isVisible` : « None of the following candidates is
  applicable because of a receiver type mismatch ».

- **Fix** : la balise du layout est migrée vers
  `<jo.codeeditor.view.chrome.SymbolBarView>`. Une fois ViewBinding
  régénéré, `liaison.barreSymboles` est typée
  `jo.codeeditor.view.chrome.SymbolBarView` (classe bien présente dans
  l'AAR `cel-ui-3.38.0`) — `setOnSymbolTap(OnSymbolTap)` et
  `View.isVisible` redeviennent applicables. Aucun autre layout ne
  référence de classe déplacée (vérification
  `find . -name "*.xml" -path "*/res/*" -exec grep -l jo\.codeeditor`)
  — `<jo.codeeditor.view.EditorView>` (ligne 56 du même fichier) est
  resté valide car `EditorView` n'a pas bougé.

- **Leçon** : le déménagement de package d'une bibliothèque de vues
  personnalisées Android casse DEUX sources — les imports Kotlin
  **ET** les FQN dans les layouts XML. Vérifier les deux :

  ```bash
  grep -rn "jo\.codeeditor\.view\.\(SymbolBarView\|EditorTheme\|BreadcrumbBar\)" \
    --include="*.kt" --include="*.xml"
  ```

## [0.37.5] – 2026-09-29

### Corrigé (compilation `:feature:editor` cassée par le déménagement de package de la v3.38.0)

- **`EditorTheme` et `SymbolBarView` introuvables** après la montée
  `code-editor` 3.37.0 → 3.38.0 (v0.37.4). La v3.38.0 de la bibliothèque
  `jjoblab/code-editor` réorganise ses packages : `EditorTheme`,
  `SymbolBarView` et `BreadcrumbBar` quittent `jo.codeeditor.view` pour
  `jo.codeeditor.view.chrome` (les claviers, popups, préviews et
  peintres suivent en `input`, `popup`, `preview`, `render`). CodeIDE
  n'importe que `EditorTheme` et `SymbolBarView` — les 5 références
  (deux imports dans `EditorActivity.kt`, un import dans
  `OptionsEditeur.kt`, un import dans `OptionsEditeurTest.kt`, une
  référence qualifiée dans `ActivityEditorLayoutTest.kt`) sont migrées
  vers `jo.codeeditor.view.chrome.*`. `EditorView` reste dans
  `jo.codeeditor.view` — aucun changement. Aucune autre cassure pour
  CodeIDE : les symboles LSP déplacés (`jo.codeeditor.lang.*` →
  `jo.codeeditor.lang.model.*`) et les fournisseurs de connexion LSP
  (`jo.codeeditor.lsp.*` → `jo.codeeditor.lsp.connection.*`) ne sont
  pas importés.
- **API publique `SymbolBarView` inchangée** : l'interface
  `OnSymbolTap` (`onSymbol(String)`, `onAction(String)`) et la méthode
  `setOnSymbolTap(OnSymbolTap)` sont restées identiques — seul le
  package change. Idem pour `EditorTheme` : les fabriques statiques
  `dark()`, `light()`, `dracula()`, `oneDark()`, `monokai()`,
  `solarizedDark()`, `gitHubLight()`, `gitHubDark()`, `nord()` et le
  champ public `editorBg` n'ont pas bougé.
- **Vérification du JAR publié** : l'AAR `cel-ui-3.38.0` récupéré depuis
  JitPack contient bien `jo/codeeditor/view/chrome/EditorTheme.class` et
  `jo/codeeditor/view/chrome/SymbolBarView.class` (et toujours
  `jo/codeeditor/view/EditorView.class`) — les imports migrés pointent
  sur des classes réellement présentes dans l'artefact livré.

## [0.37.4] – 2026-09-29

### Corrigé (CI : tests de régression alignés sur la v0.37.3, montée code-editor 3.38.0)

- **`EcrivainProfilShellTest.le profil pose le PS1 codeide la branche git et la bienvenue`**
  échouait sur le runner GitHub : deux assertions étaient restées calées
  sur l'ancien profil pré-v0.37.3 (`contenu.contains("ANDROID_HOME:-non installé")`
  et `contenu.contains("command -v gradle")`). Or la v0.37.3 (ADR 0068) a
  **explicitement remplacé** ces littéraux par les fonctions dynamiques
  `__codeide_android_info()` et `__codeide_gradle_info()` (vrais chemins du
  SDK et de la distribution Gradle — fin du script de découverte trompeur
  `$PREFIX/bin/gradle`). Les assertions vérifient désormais que les deux
  fonctions sont **définies** ET **appelées** dans le `case $- in *i*)` de
  bienvenue — même couverture sémantique que les assertions historiques, sans
  régresser sur le correctif v0.37.3.
- **`ObservateurOutilsTerminalTest.transition de l installateur - rescan immediate sans attendre le ballotage`**
  échouait : le test déposait `usr/bin/sh` + `.codeide-installation-terminee`
  **avant** la souscription, puis assertait que le premier scan renvoyait
  `EtatOutilsTerminal()` (bootstrap absent). Cela contredisait l'implémentation
  v0.37.3 : `combine(installateur.etat, horlogeBallotage())` appelle
  `scanner()` dès la première émission — le disque portait déjà le bootstrap,
  la première émission était donc `EtatOutilsTerminal(bootstrapInstalle = true)`,
  et `distinctUntilChanged` étouffait ensuite la transition de l'installateur.
  Le scénario corrigé dépose les marqueurs **après** le premier scan (bootstrap
  absent au départ), puis bascule `installateur.etat` vers `Terminee` — le
  re-scan immédiat voit le bootstrap, sans attendre le ballotage de 2 s.
  Le contrat testé (« la transition déclenche un rescan immédiat ») est
  préservé, l'assertion finale `listOf(EtatOutilsTerminal(), EtatOutilsTerminal(bootstrapInstalle = true))`
  tient.
- **Montée de la bibliothèque d'édition `code-editor` 3.37.0 → 3.38.0** (tag
  stable vérifié sur github.com/jjoblab/code-editor le 2026-09-29, artefact
  `cel-ui` publié sur JitPack : aar + pom + gradle-metadata, résolution OK).
  La coordonnée `com.github.jjoblab.code-editor:cel-ui:3.38.0` est alignée
  sur la dernière amont — le `version.ref` du catalogue porte la mise à jour,
  aucun autre module à toucher.

## [0.37.3] – 2026-09-28

### Corrigé (vrais chemins Gradle/SDK, scripts versionnés, connexion orchestrateur, UI du tooling — ADR 0068)

- **Gradle enfin trouvé à sa vraie maison** (retour d'appareil réel : « ce
  n'est pas le vrai chemin de gradle ») : le tooling télécharge SA
  distribution dans `files/home/.gradle/wrapper/dists/gradle-9.7.1/<empreinte>/gradle-9.7.1/`
  — or `LocalisationOutils.trouverGradleHome` ne scannait que
  `$PREFIX/opt/gradle*` et le symlink `bin/gradle` : `gradleHome()` nul,
  `isGradleInstalled()` faux, bannière trompeuse. Le scan balaye désormais le
  cache wrapper du HOME (même code que le wrapper), et la commande `gradle`
  du terminal corrige son glob à TROIS niveaux (l'ancien `dists/*/*/`
  s'arrêtait au niveau de l'empreinte : la découverte échouait TOUJOURS).
- **SDK Android installable depuis le terminal** (demande explicite) :
  nouvelle commande `$PREFIX/bin/android-sdk` — cmdline-tools officiels
  téléchargés sous le HOME du shell, `sdkmanager` piloté (plateforme,
  platform-tools, build-tools, licences acceptées), sous-commandes
  `statut`/`installer`/`desinstaller` ; la bannière d'ouverture du profil
  affiche l'état réel (`__codeide_android_info`), et `trouverAndroidHome`
  connaît les candidats du HOME (`home/android-sdk`, `.android-sdk`, `sdk`).
- **Scripts du terminal VERSIONNÉS** (demande : « ne pas être obligé de
  réinstaller l'application ») : un marqueur `$PREFIX/etc/codeide-scripts.version`
  retient la version posée ; `BootstrapInstaller.refreshTerminalScripts()`
  (câblé au démarrage de l'app) réécrit profil + `gradle` + `android-sdk`
  SEULEMENT en cas d'écart avec la version embarquée — une montée de version
  applique les corrections de scripts sans toucher au reste du bootstrap.
- **« Connexion avec l'orchestrateur perdue » en plein build éliminée**
  (deux moitiés, ADR 0068) : côté serveur, le `PongMessage` était publié dans
  le bus borné (8192) DERRIÈRE les `BuildOutput` — sous contre-pression d'un
  build bavard, le pong arrivait en retard, le bilan de santé (15 s) tuait
  l'orchestrateur : le pong est désormais écrit DIRECTEMENT sur le socket
  (frame atomique `@Synchronized`, jamais ensevelie). Côté client, la vidange
  des canaux de sortie vivait dans le `viewModelScope` de l'éditeur — fermer
  l'éditeur en plein build remplissait le canal (4096), bloquait la lecture du
  socket et faisait échouer le même bilan : la nouvelle pompe process-wide
  `PompeBuildTooling` vide les canaux dans une portée de singleton — le build
  quitté continue d'alimenter l'état process-wide (notification honnête,
  console rejouée au ré-attachement).
- **Les points d'UI du tooling se mettent enfin à jour** (retour : « la
  plupart ne se mettent pas à jour correctement ») : nouveau port observable
  `ObserveToolchainStateUseCase` (`EtatOutilsTerminal` : bootstrap, JDK,
  Gradle, SDK, aapt2), implémenté par `ObservateurOutilsTerminal` — les
  transitions de l'installateur déclenchent un re-scan immédiat, un ballotage
  léger (2 s, quelques stat de fichiers) couvre les outils posés HORS
  installateur (distribution Gradle de l'orchestrateur, SDK depuis le
  terminal, `aapt2` déployé). Les quatre consommateurs passent du pull figé à
  l'état poussé : carte terminal du tiroir, bandeau/bouton terminal de
  l'accueil, page terminal de l'onboarding, carte terminal de l'éditeur — et
  la garde JDK de l'éditeur lit le cache poussé (plus de scan disque sur le
  thread principal à chaque build, installation visible en pleine session).
- Tests de régression pour chaque famille : observation du tiroir/accueil/
  onboarding/éditeur sur transition poussée (sans recréer le ViewModel),
  garde JDK ouverte en pleine session, observateur réel (Robolectric,
  horloge virtuelle), localisation des nouveaux chemins.

## [0.37.2] – 2026-09-28

### Corrigé (signature des APK : fin des « conflits de package » — ADR 0067)

- **Installer la nouvelle version par-dessus la précédente échouait**
  (« conflit de package », désinstallation obligatoire) sur les APK du CI
  GitHub — p.ex. v0.37.1 par-dessus v0.37.0. Cause racine : le runner
  GitHub n'embarque AUCUN `~/.android/debug.keystore` stable (vérifié
  sur le manifeste de l'image runner) et l'atténuation par cache de
  l'ADR 0063 ne tient pas — les caches `actions/cache` sont scopés par
  ref (les runs des tags poussés ensemble ne voient pas la même entrée)
  et évictables (7 jours d'inactivité, pression des caches Gradle) :
  chaque APK repartait d'une clé aléatoire. D'où la référence étudiée :
  **CodeAssist (tyron12233)** versionne son `debug.keystore` dans le
  dépôt et résout la clé de release par une échelle hors dépôt
  (`keystore.properties` gitignoré → propriété Gradle → variable
  d'environnement).
- **Identité debug publique versionnée** : `config/signature/debug.keystore`
  (identifiants publics par convention Android — magasin et clé
  `android`, alias `androiddebugkey`, sujet `CN=Android Debug,O=Android,C=US`,
  RSA 2048, validité 10 000 jours, PKCS12 legacy comme le
  `DebugKeystore` de CodeAssist) est commis dans le dépôt et câblé comme
  signature de la variante debug par le convention plugin
  `codeide.android.application`. Une clé debug n'est pas un secret —
  c'est le patron des clés de test d'AOSP, publiques par design : la
  versionner fixe l'identité pour la CI, les contributeurs et toute
  machine locale. Les APK successifs (CI ou locaux) se mettent à jour
  les uns sur les autres, partout. La règle « keystores jamais dans le
  dépôt » est amendée (AGENTS.md règle 7, .gitignore, ADR 0067) : seule
  l'identité debug publique fait exception ; les clés RELEASE restent
  interdites au dépôt.
- **Échelle de signature release hors dépôt** (patron CodeAssist) :
  `config/signature/keystore.properties` (gitignoré, modèle
  `keystore.properties.example`) → propriété Gradle `-PRELEASE_*` →
  variable d'environnement `RELEASE_*`. Sans keystore résolu, la
  variante release reste non signée — comportement inchangé.
- **Verrou CI** : nouvelle étape « Signature = keystore versionné » —
  `scripts/verify-signature.sh` compare l'empreinte SHA-256 du
  certificat signataire de l'APK produit à celle du keystore versionné
  et fait échouer le run en cas d'écart : le conflit de package ne peut
  plus revenir silencieusement. L'artefact APK est désormais nommé avec
  la version (`CodeIDE-v0.37.2-debug`) — les téléchargements successifs
  se distinguent sans ouvrir l'archive. Le cache keystore de l'ADR 0063
  est retiré du workflow : la clé voyage avec les sources.
- **`verify-archive.sh`** : l'identité debug versionnée devient du
  contenu OBLIGATOIRE de l'archive (le build autonome la consomme pour
  signer) — tous les autres keystores restent interdits.
- **Dernière transition** : les APK v0.37.0/v0.37.1 déjà installés
  portent des signatures historiques aléatoires — UNE dernière
  désinstallation est requise avant d'installer v0.37.2 ; à partir de
  là, toutes les versions suivantes s'installent par-dessus sans conflit.

### Corrigé (outils de version)

- **`bump-version.sh` : le marqueur « [Non publié] » n'était jamais
  renommé** — dans un motif sed, `[Non publié]` est une classe de
  caractères, donc `s|^## [Non publié]|…|` ne matchait jamais (bug
  préexistant documenté, contourné manuellement à chaque livraison). Les
  crochets du motif sont désormais échappés. Corrigé dans la foulée :
  l'entrée était AJOUTÉE en fin de fichier (position la plus ancienne
  d'un journal antéchronologique) — elle est désormais insérée avant la
  première version journalisée, et le cas « marqueur déjà ouvert » est
  daté en place. Testé sur les trois cas (insertion, marqueur, journal
  vierge).
- `version.properties` : 0.37.1 → 0.37.2 (VERSION_CODE 3701 → 3702,
  strictement croissant). Manuels E83-E84.

## [0.37.1] – 2026-09-28

### Corrigé (lint CI : TypographyDashes sur les drapeaux Gradle)

- **Le CI GitHub échouait sur `:feature:editor:lintDebug`** : le check
  `TypographyDashes`, promu en erreur par `warningsAsErrors = true` du
  build-logic, demandait de remplacer par des tirets cadratins les doubles
  tirets des drapeaux Gradle cités dans six chaînes FR/EN de la
  configuration du tooling — `--offline` (mode hors ligne), `--stacktrace`
  et `--info` (aide aux arguments), `--console=plain` (note console).
  Appliquer la suggestion aurait rendu les textes faux : ces drapeaux sont
  littéraux et doivent s'afficher à l'utilisateur exactement comme ils se
  tapent.
- **Correction** : `tools:ignore="TypographyDashes"` sur les six chaînes
  (`editor_config_hors_ligne_desc`, `editor_config_arguments_aide`,
  `editor_config_note_console` — chacune en FR et EN), `xmlns:tools` ajouté
  aux deux `<resources>` de feature:editor et le choix documenté dans les
  commentaires de section — même patron que `tools:ignore="Typos"` de
  feature:home pour les faux positifs volontaires du lint.
- **Vérification** : `lintDebug` complet exécuté localement sur ce code
  pour la première fois (le lint est délégué au CI par le canon de
  vérification du 2026-09-25) : BUILD SUCCESSFUL — app, core:\*, feature:\*
  et tooling:\* tous verts. Aucun texte affiché ne change ; manuels E81-E82.

## [0.37.0] – 2026-09-27

### Ajouté (section Éditeur des Paramètres consommée — ADR 0066)

- **Thème de coloration de l'éditeur** : sélecteur (dialogue à choix unique)
  parmi les neuf thèmes embarqués de cel-ui — VS Code Sombre, VS Code Clair,
  Dracula, One Dark, Monokai, Solarized Sombre, GitHub Clair, GitHub Sombre,
  Nord — plus « Automatique » (suit le mode clair/sombre de l'application,
  comportement historique, défaut). Nouvel enum `ThemeEditeur` (core:model),
  persisté dans `editor_theme`, appliqué au fil de l'eau par
  `OptionsEditeur.themePour()` (thèmes cel mis en cache — une instance par
  valeur, `EditorTheme.dark()` & co allouant à chaque appel).
- **Minimap** (`setMinimapEnabled`), **caractères non imprimables**
  (`setShowNonPrintable`), **ligatures de police** (`setFontLigatures`, avec
  l'avertissement visible « désactive la coloration syntaxique » — compromis
  documenté de la bibliothèque) et **badges de diagnostic**
  (`setDiagnosticChipsEnabled`) : nouveaux interrupteurs, tous réellement
  branchés sur des API publiques d'`EditorView`.
- **`OptionsEditeur`** (feature:editor) : détenteur process-wide des réglages
  de l'éditeur, même patron que `OptionsTooling` (ADR 0059/0057) — une seule
  collecte des paramètres en tâche de fond, `StateFlow` observable par
  l'activité, lecture ponctuelle pour le ViewModel. `EditorActivity` y
  applique chaque changement À L'ÉDITEUR OUVERT en direct (collecte
  `Lifecycle.State.STARTED`, setters idempotents) : taille de police
  (`setFontScale` : 0,85 / 1,0 / 1,2 sur la base 14 sp de la bibliothèque),
  retour à la ligne (`setWordWrap`), et les quatre bascules ci-dessus.
- **Sauvegarde automatique honnête** : `EditorViewModel.marquerModifie` lit
  la garde `editorSauvegardeAuto` — coupée, l'onglet reste sale jusqu'à un
  enregistrement manuel (toolbar / Ctrl+S) au lieu d'écrire quand même après
  le délai d'inactivité.

### Modifié

- **L'écran Éditeur est refait en trois cartes à libellé de section**
  (Apparence de l'éditeur / Affichage / Édition — même langage que le maître
  des Paramètres) : rangée cliquable « Thème de coloration » avec la valeur
  courante en sous-titre, trio de boutons pour la taille de police,
  interrupteurs avec légendes explicatives.
- **Les réglages fantômes sont retirés** : numéros de ligne, surlignage de la
  ligne actuelle et taille de tabulation étaient « persistés avant
  consommation » (ADR 0059) sans consommateur possible — cel-ui dessine
  TOUJOURS la gouttière et le bandeau de ligne courante, et l'indentation est
  auto-détectée par fichier (`IndentDetection`). Champs, actions, clés
  DataStore, chaînes et l'enum `TailleTabulation` supprimés ; les clés
  orphelines sur disque sont simplement ignorées (projection tolérante).
  À réintroduire quand la bibliothèque exposera les setters correspondants.
- **Sous-titre dynamique de la rangée maître Éditeur** (pattern Terminal) :
  « {thème} · {taille de police} » au lieu du libellé statique.
- Trois nouvelles icônes vectorielles maison (core/ui) : `ic_minimap`,
  `ic_non_imprimables`, `ic_ligatures`.

### Tests

- `OptionsEditeurTest` (feature:editor, JUnit pur) : collecte des changements,
  facteur de police par taille, thème automatique clair/sombre (comparaison
  des couleurs publiques — `EditorTheme` n'implémente pas `equals`), thème
  forcé insensible au mode + cache d'instance.
- `OngletsEditorViewModelTest` : l'auto-sauvegarde coupée laisse l'onglet
  sale jusqu'à l'enregistrement manuel.
- `SettingsViewModelTest` : chaque nouveau réglage éditeur se persiste
  immédiatement (thème, minimap, non-imprimables, ligatures, badges,
  sauvegarde, taille de police).
- `SettingsDataStoreTest` : aller-retour exact des six nouveaux champs +
  valeur de thème inconnue retombant sur le défaut.
- Tests manuels E78-E80 dans `docs/TESTS_MANUELS.md`.

## [0.36.4] – 2026-09-27

### Modifié (correctif C5 du prompt Terminal — avertissement du daemon Gradle)

- **L'avertissement « Unable to set daemon's environment variables… There
  is no native integration with this operating environment. » est documenté
  comme CONNU et bénin** : nouvelle section dédiée dans `docs/TOOLING.md`
  (diagnostic littéral de la bibliothèque `native-platform` de Gradle — pas
  de binding compilé pour Android/bionic ; le daemon garde l'environnement
  de son premier démarrage, le build n'échoue pas à cause de ça) et test
  manuel E77 dans `docs/TESTS_MANUELS.md` — un futur diagnostic ne repart
  plus de zéro.
- **Vérification tracée** : l'app fournit l'environnement COMPLET et
  canonique dès le TOUT PREMIER lancement du process orchestrateur
  (`LanceurProcessusNatifs.launch` repart de
  `ProcessEnvironmentProvider.baseEnvironment()` à chaque lancement, couvert
  par `LanceurProcessusNatifsTest`) — c'est précisément le seul moment où
  l'environnement compte, le daemon ne le resynchronisant jamais ensuite :
  la garantie exigée par le correctif était déjà en place, elle est
  maintenant documentée.
- Rappel : depuis v0.36.0, la console rend cette ligne en style INFORMATIF
  au lieu du rouge d'erreur (`GradleService.AVERTISSEMENT_DAEMON_BENIN`,
  testé dans `GradleServiceTest`).

### Notes techniques

- Aucun test automatisé requis par le prompt (comportement de Gradle, pas
  du projet) — documentation et test manuel uniquement.

## [0.36.3] – 2026-09-27

### Ajouté (correctif C4 du prompt Terminal — retour utilisateur)

- **Commande `gradle` du terminal** (`$PREFIX/bin/gradle`, posée à
  l'installation de base, 0755) : le bootstrap n'installe JAMAIS de paquet
  `gradle` — chaque projet peut exiger une version différente (c'est
  l'intérêt du wrapper). À la place, la commande DÉCOUVRE Gradle au moment
  de l'appel : `./gradlew` du répertoire courant d'abord (le projet décide
  de sa version), sinon la distribution du wrapper la plus récemment
  utilisée dans `$GRADLE_USER_HOME/wrapper/dists` (posée par un build/sync
  lancés depuis l'app), sinon un message qui EXPLIQUE quoi faire (rejoindre
  un projet avec wrapper / lancer une sync depuis l'app) et sortie **127** —
  jamais « command not found » sans explication.
- Le shebang pointe vers le `sh` DU bootstrap (chemin absolu résolu à
  l'écriture : un shebang ne connaît pas `$PREFIX`) ; comportement repris de
  l'ancien projet (`BootstrapScripts.java`), adapté au bootstrap natif.

### Notes techniques

- Tests (`EcrivainGradleCliTest`) : pose (shebang absolu, bit d'exécution,
  idempotence) puis EXÉCUTION RÉELLE des trois scénarios — `./gradlew` du
  projet prioritaire même avec cache présent, distribution la plus récente
  choisie entre deux (`ls -dt`), échec explicite en 127. Manuels E74-E76
  dans `docs/TESTS_MANUELS.md`.

## [0.36.2] – 2026-09-27

### Corrigé (correctif C3 du prompt Terminal — retour utilisateur)

- **« Copier » la sélection fonctionne** : `ClientTermux.onCopyTextToClipboard`
  était un no-op — le commentaire prétendait que « l'écran traitera via
  `TerminalView` », FAUX : `TerminalViewClient` ne déclare PAS cette méthode,
  elle n'appartient qu'à `TerminalSessionClient`, et c'est par elle que Termux
  signale la copie demandée par l'utilisateur (sélection + « Copier » de la
  barre d'action native) — l'action était silencieusement ignorée. La vraie
  écriture vit désormais dans `CopieurPressePapiersAndroid` (port
  `CopieurPressePapiers` injecté dans `FabriqueCoquillesTermux`, même patron
  lambda que le style de curseur) : `ClipboardManager.setPrimaryClip` avec
  garde texte vide/nul.
- Confirmation adaptée à l'API : sur Android 13+, le système affiche déjà son
  propre bandeau à chaque copie — aucun Toast maison (pas de doublon) ; en
  dessous, un Toast court « Copié » reste utile.
- La copie automatique en sortie de mode sélection (ADR 0059) est INTACTE :
  les deux mécanismes coexistent (complémentaires, pas concurrents).

### Notes techniques

- Tests (`CopieurPressePapiersAndroidTest`, Robolectric) : texte normal /
  vide / nul, remplacement du clip courant, Toast présent en API 28 et absent
  en API 34 (compté par `ShadowToast`). Test manuel E73 (sélection réelle sur
  appareil) dans `docs/TESTS_MANUELS.md`.
- `core:terminal-runtime` étant Android-aware (il porte `TerminalService`),
  l'injection `@ApplicationContext` du copieur y est directe — rien ne remonte
  à `feature:terminal`.

## [0.36.1] – 2026-09-27

### Ajouté (correctifs C1/C2 du prompt Terminal — retour utilisateur)

- **Profil shell de CodeIDE** (`EcrivainProfilShell`, posé à l'installation de
  base du bootstrap, avant tout paquet optionnel) : `$PREFIX/etc/codeide.sh`
  régénéré ENTIÈREMENT à chaque installation (idempotent par construction,
  écriture atomique) — `PS1` personnalisé `codeide:<répertoire> (<branche Git>)$`
  (couleurs, branche silencieuse hors dépôt — C1) et message de bienvenue
  affichant l'état RÉEL des outils (JDK / SDK Android / Gradle — C2 :
  `JAVA_HOME`/`ANDROID_HOME` étaient déjà injectés dans chaque session, ils
  sont enfin VISIBLES).
- La ligne d'inclusion `[ -f "$PREFIX/etc/codeide.sh" ] && . "$PREFIX/etc/codeide.sh"`
  est ajoutée au `.bashrc` du HOME du bootstrap **seulement si absente**
  (jamais dupliquée, contenu existant préservé).

### Notes techniques

- La garde de la bienvenue est `case $- in *i*)` (test POSIX canonique
  d'interactivité) et NON `[ -n "$PS1" ]` comme dans le plan initial : le
  profil POSE `PS1` juste avant, la garde `[ -n "$PS1" ]` aurait toujours
  affiché la bannière — y compris pour les shells non interactifs du tooling.
- Aucun changement côté `TerminalSession`/`core:terminal-runtime` : le
  mécanisme passe entièrement par les fichiers shell sourcés par bash.
- Tests (`ProfilShellTest`) : contenu généré (PS1 exact, branche Git, état des
  outils), idempotence (réécriture entière + inclusion unique au second
  passage), `.bashrc` préservé, et syntaxe/sourcing exécutés par le VRAI
  `bash` du poste (`sh -n`, sourcing non interactif : PS1 posé, bienvenue
  muette). Tests manuels E69-E72 dans `docs/TESTS_MANUELS.md`.

## [0.36.0] – 2026-09-27

### Ajouté (G8 — affichage des tâches, fin de la boîte noire de sync, écran de configuration — ADR 0065)

- **Tâches au fil du build dans la console** : les événements `TaskStarted`/`TaskFinished`
  ne sont plus jetés par `GradleApiImpl.pomper` — `observeTachesBuild` (canal borné par
  build, tamponné avant le lancement, fermé à la fin) alimente l'onglet Sortie : une ligne
  par tâche (`> Tâche :app:xxx…`), mise à jour EN PLACE à sa fin (durée MESURÉE par
  l'opération Gradle, sautée grisée, échec rouge — comme la vue Build d'Android Studio).
- **Progression de la synchronisation** : protocole **v3**, nouveau `SyncProgress` par
  phase (`CONNEXION` / `MODELE_GRADLE` / `MODELE_IDEA`, annoncé au départ puis à la fin
  avec sa durée) — la sync déroule ses étapes dans la console du canal Sync au lieu d'un
  « en cours » muet ; la première connexion (distribution + daemon Gradle) est enfin
  visible. `TaskFinished` enrichi de `durationMs` et `skipped` (champs à défauts,
  compatibles v2). Fichiers dorés régénérés (28 messages).
- **Écran de configuration du tooling** (engrenage de l'onglet Sortie, dialogue plein
  écran `Theme.CodeIDE.PleinEcran` — accessible DEPUIS la console sans quitter
  l'espace de travail) : affichage des tâches (filtrage en vol, relu à chaque événement),
  mode hors ligne (`--offline`), arguments Gradle libres (persistés à la fin de saisie),
  état vivant de l'orchestrateur (connexion + tas) — réglages persistés à l'instant dans
  DataStore (`toolingAfficherTaches`, `toolingHorsLigne`, `toolingArguments`), consommés
  par le nouvel `OptionsTooling` (patron `PorteurStyleCurseur`).

### Modifié

- `build()` du port tooling porte les **arguments Gradle supplémentaires**
  (`BuildRequest.arguments`, vides par défaut) — la chaîne use case → client →
  orchestrateur est complète.
- **`--console=plain` est forcé** sur tout build de l'orchestrateur (ajouté en DERNIER
  argument : l'occurrence finale d'une option Gradle gagne, un client qui passerait son
  propre `--console` resterait maître) — la sortie texte ne repose plus sur la seule
  détection TTY de Gradle.
- La console affiche l'avertissement bénin du daemon Gradle (« Unable to set daemon's
  environment variables… no native integration ») en style INFORMATIF au lieu du rouge
  d'erreur — comportement connu et documenté (préparation du correctif C5 du prompt
  Terminal ; la documentation TOOLING/TESTS_MANUELS suit à la livraison C5).
- Les lignes de console deviennent TYPIÉES (`LigneConsole` scellée : `Sortie`/`Tache`/
  `Etape`, identité stable — mise à jour en place sans scintillement), `PanneauConsoleFragment`
  gagne l'engrenage de configuration ; durées partagées `DureesLisibles`.

### Notes techniques

- Protocole v3 : égalité EXACTE exigée au handshake (inchangé) — un orchestrateur v2
  refusera de parler à une app v3 avec un message clair, jamais de décodage raté en
  pleine session ; app et orchestrateur sont livrés ensemble (JAR redéployé au SHA-256).
- `SyncHandler` hoiste la connexion AVANT les modèles (sa propre phase, la plus longue
  d'une première sync) ; son échec sec publie « connexion Gradle impossible : … » au lieu
  de deux « modèles non résolus » qui ne disent pas la cause.
- Tests : protocole (catalogue 28 + dorés v3), `ProgressBridgeTest` (durée/sauté),
  intégration serveur (phases de sync entre `SyncStarted` et `SyncResult`, tâches
  traversantes), client (dispatch réparé, étapes jamais conflattées, arguments),
  `GradleServiceTest` (mise à jour en place, filtrage du réglage, avertissement apaisé),
  `ToolingEditorViewModelTest` (câblage des nouveaux flux), `ConfigToolingViewModelTest`,
  layout Robolectric de l'écran de configuration ; kover ≥ 80 % vert sur les modules à
  seuil. ADR 0065.

## [0.33.0] – 2026-09-26

### Ajouté (étape 32 — tooling professionnel, ADR 0057)

- **Sync à l'ouverture du projet** — comme dans Android Studio : dès la
  première connaissance du projet, la synchronisation part SANS attendre
  un geste. La résolution des modèles (`GradleProject` : tâches ;
  `IdeaProject` : structure IDE, dépendances, classpaths) force la
  configuration du projet — le socle des fonctionnalités LSP à venir. La
  garde JDK (ADR 0048) répond dans le canal Sync dès l'ouverture si les
  outils manquent : un refus actionnable, jamais une erreur opaque.
- **`SyncStarted` diffusé PAR le serveur** — 25e message du protocole
  (fichier doré inclus) : l'orchestrateur annonce le départ d'une sync
  AVANT la résolution, symétrique du `BuildStarted` des builds. L'en-tête
  et la notification se posent sur un fait du serveur, pas sur la
  présomption du geste ; le client l'observe par le port
  (`observeSyncState`), le marquage devient idempotent (le chrono ne se
  remet pas à zéro à la confirmation), et la perte de session conclut
  proprement tout « en cours ».
- **Canal Taches** — `CanalTooling.TACHES` (violet, `ic_liste_taches`) :
  le listage du sélecteur « Exécuter » vit sur SON canal — indicateur de
  vol dans l'en-tête (« Chargement des tâches… » + chrono), le sélecteur
  est le résultat. Priorité du canal actif : Sync > Build > Taches.
- **Service de notification du tooling** — `ToolingService` (foreground,
  type `specialUse` documenté) : le `GradleService` pilote le service
  d'Android par le port `DemarreurServiceTooling` au premier départ
  d'activité ; la décision de contenu est pure
  (`decisionNotificationTooling`) — notification en cours pendant
  l'activité, notification finale au résultat (reste dans le tiroir),
  arrêt de soi-même au repos. Même contrat que le service du terminal :
  honnête, jamais collant.
- **État tooling process-wide** — `GradleService` devient `@Singleton`
  (le ViewModel l'injecte) : `attacher()` ouvre une session d'espace
  (console et problèmes vierges, activités en vol conservées),
  `rattacherBuildEnVol()` reprend l'observation d'un build parti avant la
  fermeture — ses sorties continuent d'arriver à la ré-ouverture.

### Modifié

- `GradleToolingRepository` gagne `observeSyncState` (modèle domaine
  `EtatSyncTooling`, zéro type tooling — règle §2.2) ; les faux des tests
  suivent.
- `EditorViewModel` n'a plus d'horloge propre (supprimée) : les chronos
  vivent dans le `GradleService` singleton injecté.
- `ServeurIntegrationTest` gèle l'ordre `SyncStarted` → `SyncResult` ;
  `ServerVersion.CURRENT` passe à 0.33.0 (annoncée au handshake).
- Vérification légère complète : `tooling:protocol`, `core:domain`,
  `tooling:client`, `tooling:server`, `feature:editor` verts (112 tests
  d'espace), `spotlessCheck` + `detekt` globaux verts ; situation réelle
  validée par harnais contre le VRAI jar (sync ~1,1 s sur daemon réel,
  32 tâches listées, build exécuté avec sortie, ping/pong, sortie propre
  code 0 — ADR 0057 décision 6).

## [0.32.5] – 2026-09-26

### Modifié (retour appareil réel sur l'étape 31, tooling à canaux)

- **Fil d'Ariane retiré (ADR 0056)** — retour utilisateur : « Tu peux
  enlever le breadcrumb pour le moment, cela ne me donne pas le design
  espéré. » La `BreadcrumbBar` de la bibliothèque disparaît du layout
  d'espace de travail, et le scanner maison `SymbolesEnglobants` (son
  unique producteur, 339 lignes + 11 tests) part avec : aucun code mort
  ne reste pour un « pour le moment ». Une future reprise partira d'un
  design validé, pas d'une transcription.
- **Barre de symboles VISIBLE et collée au clavier, patron CodeAssist
  (ADR 0056)** — cause racine enfin trouvée : `enableEdgeToEdge()` rend
  `adjustResize` inerte sur API 30+ (la fenêtre ne rétrécit pas), le
  panneau et sa barre restaient donc DERRIÈRE le clavier — l'astuce de
  peek de la v0.32.4 élargissait un panneau que rien ne remontait. Le
  dépôt CodeAssist (tyron12233) a été analysé comme demandé : la barre y
  est la DERNIÈRE vue de la colonne éditeur, remontée par les insets
  IME, le dock se cachant pendant la frappe. Transposition : la
  `SymbolBarView` passe du sheet au bas de `zone_centrale` ; le bas de
  la colonne est remonté de la hauteur IME par padding
  (`updatePadding(bottom = insets.ime().bottom)`), synchronisé IMAGE
  PAR IMAGE avec l'animation du clavier
  (`WindowInsetsAnimationCompat` + `DISPATCH_MODE_CONTINUE_ON_SUBTREE`)
  ; le sheet se masque pendant la frappe et se restaure à l'état du
  ViewModel à la fermeture ; fond opaque + filet supérieur pour lire la
  barre comme un prolongement du clavier. Double détection IME
  conservée (insets API 30+, rétrécissement du root avant).
- **En-tête du panneau qui s'efface à l'extension (ADR 0056)** — retour
  utilisateur : « Lorsque bottomsheet behavior est expand le header
  devrait progressivement disparaitre. » L'alpha de l'en-tête suit le
  glissement TRAME PAR TRAME (`onSlide`) : opaque jusqu'à mi-hauteur,
  entièrement fondu à l'extension (INVISIBLE, pas GONE — pas de saut de
  hauteur, plus d'appuis fantômes). Les onglets restent : la console
  étendue garde sa navigation.
- **Fond opaque du panneau inférieur (ADR 0056)** — retour : « Le
  background du bottomsheet behavior est complètement transparent. »
  `fond_panneau_inferieur` : coins supérieurs arrondis 12 dp, couleur
  jour/nuit (mêmes jetons que le tiroir), élévation 8 dp — la feuille se
  POSE sur l'éditeur au lieu de flotter en transparence.
- **Tooling à canaux uniques (ADR 0056)** — la ligne d'activité de
  l'en-tête affiche build, sync et tâches : icône et couleur SIGNATURE
  du canal (Sync teal / Build bleu — `CanalTooling`), libellé de
  l'activité en cours (« Build — assembleDebug », « Synchronisation du
  projet… » ou le dernier résultat), chrono en vol (500 ms, horloge
  injectée `TimeProvider`), bouton Arrêter pendant un build, progression
  indéterminée. Le peek s'élargit pour l'accueillir : l'activité se voit
  même panneau replié, façon barre de build d'Android Studio. Chaque
  ligne de la console porte SON étiquette de canal en tête (colonne de
  tag façon logcat), le statut porte l'icône du canal qu'il décrit ; le
  Journal et les Problèmes restent des onglets distincts — leurs
  propres canaux. `EtatGradle` gagne `taches`, `debutBuildMs`,
  `debutSyncMs`, `canalActif` ; l'état reste pur (aucun libellé codé en
  dur, le rendu localise).

### Ajouté

- **Script de nettoyage du dépôt (`scripts/nettoyage-depot.sh`)** —
  retour : « Je veux aussi que tu me créé un script pour que je puisse
  nettoyer le dépôt de mon côté. » Simulation par défaut,
  `--appliquer` pour effacer : retire exactement ce qu'aucune archive de
  livraison ne porte (mêmes exclusions que `package.sh` — build/,
  .gradle/, .kotlin/, .idea/, captures/, .cxx/, dist/, *.iml,
  .DS_Store), refuse de tourner hors racine de dépôt, ne touche jamais
  .git/, épargne local.properties sauf drapeau explicite (régénéré par
  Android Studio) et termine par l'état git non suivi. Le dépôt local
  redevient comparable à l'archive (568 Mo d'artefacts retirés sur la
  copie de développement).

## [0.32.4] – 2026-09-26

### Modifié (retour appareil réel sur l'étape 31, suite)

- **Vraies classes de la bibliothèque code-editor (ADR 0055)** — retour
  utilisateur : « Pourquoi tu n'as pas utilisé les classes de
  code-editor (symbolview et breadcrumb) ? » Vérification refaite : les
  deux classes existent BIEN dans le tag v3.37.0 consommé (paquet
  `jo.codeeditor.view`, présentes depuis v3.2/v3.3 — la v0.32.3 les
  avait cherchées dans `view.chrome`, un déplacement postérieur du
  dépôt amont). Les transcriptions maison (`VueFilArianeEditeur`,
  `BarreSymbolesEditeur`, ~460 lignes) sont SUPPRIMÉES au profit des
  classes réelles de `cel-ui` : `BreadcrumbBar` (segments posés par
  `setSegments()` — API « usage manuel » documentée — depuis le scanner
  [SymbolesEnglobants] et le chemin relatif de l'onglet ; `bind()` et
  son SPI `SymbolProvider` ne connaissent ni le chemin ni le scanner)
  et `SymbolBarView` (écouteur `OnSymbolTap` : Tab indente, //
  bascule le commentaire, ↑/↓ déplacent la ligne, Dup duplique, les 28
  symboles passent par `typeChar`). Rendu et comportement = ceux de la
  bibliothèque (couleurs sombres, pas de défilement du fil — assumé).
- **Barre de symboles enfin VISIBLE et collée au clavier (ADR 0055)** —
  cause racine du « la touche virtuelle n'est pas visible » : à
  l'ouverture de l'IME le panneau passait `STATE_COLLAPSED` (peek 48 dp
  = en-tête seul) — la barre, placée SOUS l'en-tête, restait hors
  écran. Correctif : le peek s'élargit à en-tête + barre (48 + 38 dp)
  quand l'IME est ouvert, le panneau replié étant déjà posé sur le haut
  du clavier (`adjustResize`) — la barre paraît collée au clavier ;
  peek de repos restauré à la fermeture. Détection IME doublée :
  insets natifs (API 30+) ET rétrécissement du root (toutes API) en OU
  — un seul détecteur se taisait sur certains appareils.
- **Panneau inférieur à fragments (ADR 0055)** — retour utilisateur :
  « Pour le bottom sheet behavior, il faut aussi utiliser des fragments
  au lieu d'empiler les vues dans le layout. » Le chrome (en-tête à
  poignée/titre/badge/actions, barre de symboles, onglets) reste à
  l'hôte ; le contenu des trois onglets vit dans `PanneauConsoleFragment`,
  `PanneauProblemesFragment` et `PanneauJournalFragment`, ajoutés UNE
  fois au `FragmentContainerView` puis montrés/cachés (même pattern que
  le tiroir, ADR 0052 — l'état de défilement survit aux changements
  d'onglet). Chaque fragment collecte l'état qu'il rend
  (`activityViewModels`) ; le saut à un diagnostic passe par le contrat
  `ControleurPanneauEditeur` (implémenté par l'activité, cast
  contrôlé dans `onAttach` — même pattern que le Terminal du tiroir,
  ADR 0053). Correction au passage : les show/hide du tiroir et du
  panneau ciblent désormais leurs fragments PAR TAG (un `forEach` sur
  les fragments du manager aurait caché les fragments de l'autre
  conteneur).

## [0.32.3] – 2026-09-26

### Ajouté (retour appareil réel sur l'étape 31, suite)

- **Fil d'Ariane de l'éditeur (ADR 0054)** — « dossier › … › fichier ›
  classe › fonction » sous les onglets, au-dessus de la zone d'édition,
  transposé de la `BreadcrumbBar` de la bibliothèque code-editor
  (`view/chrome/BreadcrumbBar.java`, « à la CodeAssist/IntelliJ ») :
  barre de 28 dp, monospace 12 sp, chevrons ›, dernier segment en gras
  accent. Le SPI `SymbolProvider` n'étant pas encore câblé dans
  cel-ui 3.37.0, le scanner [SymbolesEnglobants] est maison — portées
  par profondeur d'accolades (Kotlin ET Java, annotations et
  modificateurs tolérés, lignes de commentaire ignorées, `fun x() = …`
  refermé par la déclaration suivante) ; il suit le caret avec le même
  débounce de 200 ms que la bibliothèque, se décompose depuis le chemin
  relatif de l'onglet, renonce aux symboles sur les très gros documents
  (`EditorDocument.isLarge`) et défile vers le segment courant.
- **Barre de symboles au-dessus du clavier (ADR 0054)** — transposée de
  la `SymbolBarView` de la bibliothèque code-editor : touches épinglées
  (Tab, //, ↑, ↓, Dup) + rangée défilante de 28 symboles, sous l'en-tête
  du panneau inférieur, **visible uniquement quand l'IME est ouvert**.
  Touches en `onTouchEvent` brut (à la CodeAssist) : jamais de vol de
  focus, l'éditeur garde le sien et le clavier reste ouvert ; Tab
  indente, // bascule le commentaire de ligne, ↑/↓ déplacent la ligne,
  Dup duplique la sélection, chaque symbole s'insère par `typeChar`
  (fermeture automatique des paires conservée). Détection de l'IME :
  insets natifs (API 30+) avec repli par la hauteur du root
  (`adjustResize`, API 26-29). Quand elle apparaît, le panneau se
  replie — son en-tête et la barre montent au-dessus du clavier.

### Corrigé (retour appareil réel)

- **Les onglets de fichiers changent enfin de fichier** : la vue racine
  d'un onglet porte un écouteur d'appui long (menu contextuel) — or une
  telle vue CONSOMME aussi les taps simples sans agir (leçon ADR 0051
  fixée pour le terminal en v0.31.7, jamais appliquée aux onglets de
  l'ÉDITEUR) : le `TabLayout` ne voyait jamais le geste, l'appui ne
  changeait rien. La racine agit désormais sur son propre tap — elle
  sélectionne l'onglet (`brancherInteractionsOnglet` du terminal,
  même architecture que Termux).
- **Ouvrir un fichier depuis l'explorateur referme le tiroir** —
  nouvel effet `EffetEditor.FichierOuvert` émis dans les deux chemins de
  l'ouverture (onglet déjà présent sélectionné, nouvel onglet ajouté) ;
  le grand écran garde son tiroir ancré (ADR 0026).

### Modifié

- **État vide de l'éditeur enrichi** : illustration « fenêtre de code
  `</>` » (80 dp, accent), titre et message réécrits, deux actions
  directes — « Parcourir les fichiers » (ouvre le tiroir) et
  « Terminal » — et trois astuces de découverte (appui long sur un
  fichier, poignée ⋮ du tiroir, split view du terminal) : les gestes
  de l'étape 31 ne se devinent pas.

## [0.32.2] – 2026-09-26

### Ajouté (retour appareil réel sur l'étape 31)

- **Terminal du tiroir : rendu réel, split view et plein écran intégré
  (ADR 0053)** — le fragment Terminal déménage dans `feature:terminal`
  (seul feature autorisé au runtime Termux) et rend de **vraies
  sessions** : entête à la maquette (emblème violet, « Terminal »,
  sous-titre « N sessions actives · termux », ligne d'actions) avec
  trois modes — **liste** (une carte par session : chemin · heure,
  badge ● vivante/● terminée, boutons « agrandir dans le tiroir » et
  « plein écran », toucher la carte l'agrandit), **split vertical**
  (panneaux empilés) et **split en colonnes** (côte à côte) actifs dès
  deux sessions, et **plein écran dans le tiroir** (un panneau remplit
  le tiroir, retour liste). L'appui sur une carte ne navigue plus
  d'office : les boutons explicites agrandissent dans le tiroir OU
  ouvrent l'écran plein écran. Clavier étendu partagé (la séquence
  part au panneau focalisé), pincement zoom par panneau, attachement
  rattrapé au fil des sorties. L'activité d'édition crée le fragment
  par **fabrique Hilt** (`FabriqueFragmentTerminalTiroir`, core:ui) et
  sert un contrat `ControleurTerminalTiroir` (plein écran, créer dans le
  dossier du projet **sans naviguer** — nouvelle action
  `CreerSessionTerminal`, installation) — les features ne se référencent
  toujours pas.

### Corrigé (retour appareil réel sur l'étape 31)

- **Animation de la poignée ⋮** : l'écouteur tactile consommant tout,
  l'état pressé du sélecteur ne s'activait jamais — la poignée restait
  figée pendant le glissement. Le glissement pose désormais
  explicitement l'état « pendant » de la maquette : fond actif + bordure
  accent-fort (sélecteur), points ⋮ accent, **grossissement 1.08 animé
  sur 150 ms** (pivot au centre, à cheval sur le rebord du tiroir —
  moitié dedans, moitié dehors), retour symétrique au relâchement.
- **Tiroir au-dessus de la barre de navigation** :
  `applySystemBarsInsetsTopMargin` devient `applySystemBarsInsetsMargins`
  (core:ui) — la barre de navigation est désormais une MARGE basse du
  tiroir (comme la barre de statut en haut, v0.32.1) : le tiroir ne
  peint plus rien derrière elle, son rail s'arrête au-dessus des gestes.
- **Lint `RtlSymmetry` du champ Destination du popover « Déplacer
  vers… »** (défaut latent v0.32.1, révélé par le passage lint) :
  `paddingStart` explicite posé à côté du `paddingEnd` de la place du
  chevron — la symétrie RTL de la boîte de saisie est maintenant
  déclarée complète, rendu inchangé.

## [0.32.1] – 2026-09-26

### Corrigé (retour appareil réel sur l'étape 31)

- **Fond de la ligne sélectionnée de l'arbre** : la « barre » 2,5 dp du
  drawable `fond_ligne_selectionnee` s'étirait en réalité PLEINE LARGEUR
  (un `<size>` sur le shape ne borne pas une couche de `layer-list`)
  — la ligne sélectionnée apparaissait comme un bloc plein au lieu du
  dégradé `accent-doux → transparent` avec fine barre `accent-fort` au
  bord. La barre est désormais un calque de largeur fixe (`android:width`
  sur l'item) ancré au bord de départ sur toute la hauteur, coins
  suivant le rayon 7 dp — la réplique de `.ligne.sel` de la maquette.
- **Liste déroulante de destination du popover « Déplacer vers… »** :
  le champ Destination porte désormais une icône chevron ; un appui
  ouvre un SECOND popover maison par-dessus le premier, listant les
  dossiers énumérés de la racine active (racine incluse — ligne
  « Racine » à la maison, nœud déplacé et ses descendants exclus),
  indentés par profondeur, hauteur bornée à ~8 lignes. Le choix remplit
  le champ (et efface l'erreur en ligne) ; le popover « Déplacer
  vers… » reste ouvert en dessous — le chemin reste éditable au clavier.
- **Fragments ajustés au tiroir + poignée à cheval** : le fond du tiroir
  est borné à la largeur VISIBLE (`fond_tiroir`, inset fin de 13 dp) —
  la bande de débord reste transparente, la moitié externe de la
  poignée ⋮ flotte sur l'éditeur assombri exactement comme `right:-13px`
  dans la maquette ; les fragments et le rail remplissent le tiroir
  visible d'un bord à l'autre (plus de liseré de fond sous la poignée).
- **Tiroir sous la barre de statut** : nouvelle extension
  `applySystemBarsInsetsTopMargin` (core:ui) — la barre d'état devient
  une MARGE haute du tiroir (il ne peint plus rien derrière elle), la
  barre de navigation reste un rembourrage bas. `DrawerLayout` respecte
  la marge verticale de ses tiroirs (vérifié dans le bytecode
  androidx 1.1.1 : `onLayout` pose `childTop = lp.topMargin`).

## [0.32.0] – 2026-09-25

### Ajouté

- **Explorateur de fichiers v2 (étape 31, ADR 0052)** — implémentation de
  la spécification `docs/EXPLORATEUR_V2.md` (reproduction à l'identique de
  la maquette validée) :
  - **Tiroir à fragments** : chaque destination du tiroir devient un
    fragment portant son **propre entête** (emblème coloré, titre,
    sous-titre = chemin de la racine affichée) — plus d'entête commun ;
    rail de fragments commun en bas (Fichiers, Recherche, Git, Terminal),
    fragments ajoutés une fois puis montrés/cachés (plis et défilement
    conservés). Recherche et Git sont des aperçus d'accueil (lots
    dédiés à venir) ; la carte du terminal migre TEL QUELLE dans son
    fragment. « Fermer le projet » rejoint le débordement de la toolbar,
    le type de projet devient le sous-titre de la toolbar.
  - **Poignée ⋮ de redimensionnement** du tiroir : glissement horizontal
    borné 45-98 % de l'écran, aimants 55/69/85/98 % (±12 dp au
    relâchement), pastille de taille « NN % » fondue 380 ms après le
    relâchement, largeur mémorisée par session (instance sauvegardée).
  - **Arbre treeview** : guides fins verticaux + coudes **dessinés**
    (22 dp par niveau, trait du dernier enfant raccourci, masque
    d'ancêtres finis), chevrons de dépliage seuls (crochets retirés
    après retour utilisateur), **points d'état** à 4 états pilotés par
    les onglets de l'éditeur (défaut / ouvert creux / actif plein /
    sélectionné — la sélection prime sur la bordure, anneau combiné),
    compteur d'enfants des dossiers, badge de chemin de la racine,
    ligne haute 38 dp.
  - **Vraies icônes par type** : 13 nouvelles marques (properties,
    toml, git, db, log, script, texte, dossier privé…) + 4 icônes
    d'action (ouvrir, renommer, supprimer, replier tout, maison,
    presse-papiers, poignée, légende, déplacer).
  - **Bascule Projet/Privé exclusive** (segments stylés, remplace
    l'ancien bouton cadenas) : l'arbre du **stockage privé** de
    l'application (`filesDir`, `cacheDir`, `codeCacheDir`,
    `databases`, `shared_prefs`) vit derrière le même port `FileSystem`
    via le qualifier Hilt `@FileSystemPrive` et l'adaptateur
    `prive:///` (ADR 0003 inchangée) ; la sélection se réinitialise au
    basculement, les onglets de l'éditeur ne sont pas touchés.
  - **Popover maison** (jamais de menu système) : actions du nœud ancrées
    à la **position exacte du doigt** (flèche 12 dp, retournement près du
    bas, zoom .94→1, un seul popover à la fois, Échap/clic extérieur),
    tête à chemin contextuel (« Cible — », « Créer dans — »), variantes
    fichier/dossier/racine, actions dangereuses rouges, « Coller »
    désactivé avec note du presse-papiers ; popover « Déplacer vers… » à
    validation locale (erreurs en ligne), confirmation « Supprimer »,
    « Légende » (pastilles + notation), « Nouveau » ancré au bouton.
  - **Mutations par nœud** (aucun rechargement d'arbre entier) :
    éditeur **inline** de création/renommage (repère selon le type,
    Enter/Échap, secousse au refus), suppression avec snackbar
    **annulable** (restaure l'élément ET rouvre ses onglets via
    instantané mémoire), presse-papiers d'arbre copier/couper/coller
    avec suffixe anti-collision « (copie) » / « (copie N) » (insensible
    à la casse), collage interdit source→descendant, « Déplacer vers… »
    par chemin relatif.
  - **Snackbar maison** au-dessus du rail : message + chemin en seconde
    ligne, action « Annuler », apparition fondu + montée 18 dp,
    expiration 4 600 ms pilotée par le ViewModel.
  - **Port `FileSystem` étendu** : `readBytes`/`writeBytes` (octets
    bruts — copier un `.jar` ou une `.db` sans corrompre le texte) ;
    use cases d'arbre purs dans `core:domain` (Copier/Deplacer/Lire/
    Restaurer + `NomsCopies`).
  - **Fils d'Ariane** de la sélection (maison + ancêtres, clic =
    sélection), **barre presse-papiers** conditionnelle sous l'arbre.

### Modifié

- `activity_editor.xml` : l'empilement de vues du tiroir (~350 lignes)
  disparaît au profit du `FragmentContainerView` + rail ; suppression de
  `menu_tiroir.xml`, `dialogue_nom_fichier.xml` et de l'entête commun ;
  le label du rail passe d'« Explorateur » à « Fichiers ».
- `NoeudExplorateur` porte son état de présentation (sélection, coupe,
  onglet actif/ouvert, dernier enfant, masque d'ancêtres, compteur,
  flash) ; les menus contextuels système (PopupMenu/AlertDialog) de
  l'explorateur disparaissent au profit des popovers maison.
- `FakeFileSystem` (core:testing) : le calcul des enfants ampute SA
  barre finale de l'URI de racine (« prive:/// ») — comportement
  inchangé pour les racines SAF.

### Tests

- 91 tests : `NomsCopies` (suffixes, casse, trous), use cases d'arbre
  (domaine), `ExplorateurV2EditorViewModelTest` (tri ADR 0027, validation
  inline, anti-collision, coller interdit, annulation + onglets rouverts,
  bascule exclusive, points d'état des onglets), layouts v2 gonflés sous
  Robolectric ; attentes historiques actualisées (la racine est
  désormais une ligne visible).

## [0.31.7] – 2026-09-25

Septième lot de corrections après **retour d'appareil réel** (moto g06 /
Android 15, suite du rapport 4a4526aa) : l'écran d'échec v0.31.6 a fait
son travail — les détails techniques pointaient désormais
`Storage(reason=AlreadyExists, details=README.md)` : le piège
`.gitattributes` était corrigé (premier fichier du plan passé), l'échec
avait **progressé au fichier suivant**, preuve que la complétion
d'extension SAF frappe aussi les noms **avec** extension ; et le tap sur
un onglet de session du terminal ne changeait **rien** (« j'appuie sur
le tab layout l'onglet pour changer de session, rien ne se passe »).
Version corrective (SemVer `0.N.M`). ADR 0051.

### Corrigé

- **Création de projet : la complétion SAF frappe aussi les extensions
  développeur** (`details=README.md`) : le fournisseur complète par
  l'extension canonique du type demandé tout nom dont l'extension est
  **absente de la table système** (`FileUtils.splitFileName`) — et
  `md`, `kts`, `kt`, `properties`, `pro`, `gradle`, `toml`… n'y figurent
  pas : `README.md` + `text/plain` était créé `README.md.txt`,
  exactement la famille du piège `.gitattributes` de v0.31.6, mais sur
  un nom AVEC extension (intolérable à tolérer : un projet généré avec
  `build.gradle.kts.txt` casserait Gradle en silence). La parade est
  désormais universelle : `mimeFichierTexte` répond le type privé
  `text/x-codeide` pour **TOUT fichier texte** (aucune extension de code
  n'est garantie dans la table, qui varie par version et par OEM) — le
  fournisseur ne complète jamais un type sans extension canonique,
  quel que soit le nom. L'éditeur suit (création de `notes.md` depuis
  le tiroir : même piège latent). La complétion d'un nom **sans**
  extension réelle reste tolérée en filet (v0.31.6 inchangé).
- **Terminal : le tap sur un onglet de session ouvre enfin la session**
  : la vue racine d'onglet portait un écouteur d'appui LONG seul (menu
  renommer/dupliquer/fermer) — or une vue `longClickable` CONSOMME aussi
  les taps simples (`View.onTouchEvent` retourne `true` pour clickable
  **ou** longClickable, et le `performClick()` sans écouteur ne fait
  rien) : le `TabView` parent ne voyait jamais le geste, aucune
  sélection, « rien ne se passe ». La racine prend désormais son propre
  écouteur de clic (même architecture que Termux, dont les vues d'onglet
  gèrent elles-mêmes leur clic) ; l'appui long garde son menu, le
  bouton ferme, l'écouteur du TabLayout reste pour les sélections
  extérieures à la vue.

### Tests

- `MimeFichierTexteTest` : attentes durcies — TOUT fichier texte
  (avec ou sans extension, caché ou pas) prend le type privé (5 + 4) ;
- `SafFileSystemTest` 23 (+2) : `README.md` + `text/plain` complété
  puis rejeté honnêtement (nettoyage + `AlreadyExists` au nom exact —
  documente pourquoi `text/plain` est interdit) ; `README.md` et
  `build.gradle.kts` avec le MIME conseillé préservés exactement ;
- `FauxFournisseurDocuments` : la complétion suit la règle RÉELLE du
  fournisseur (extension inconnue de la table → complétion, type privé
  → jamais) au lieu de la règle « sans extension réelle » de v0.31.6 ;
- `OngletsSessionsTest` 4 (+1) : le câblage de production rend la
  racine cliquable — tap → ouvrir, bouton → fermer, appui long → menu.

## [0.31.6] – 2026-09-25

Sixième lot de corrections après **retour d'appareil réel** (moto g06 /
Android 15) : la création de projet échouait **à chaque tentative** avec
« un dossier porte déjà ce nom à l'emplacement choisi — rien n'a été
écrasé » (le dossier apparaissait puis disparaissait, l'écran d'échec
affichant « .gitattributes » en détails techniques) ; et l'écran du
terminal **plantait** 60 ms après la création de la première session
(`NullPointerException : Missing required view with ID:
bouton_fermer_session`). Version corrective (SemVer `0.N.M`). ADR 0050.

### Corrigé

- **Création de projet : le piège `.gitattributes`** (« le dossier créé
  a été supprimé… toutes mes tentatives sont vaines ») : le point
  INITIAL d'un fichier caché n'est **pas** une extension pour le
  fournisseur SAF — `.gitattributes` (premier fichier du plan des
  modèles JVM, avec `.gitignore` et `.editorconfig`) partait en
  `text/plain` (l'ancien test `contains('.')` voyait « une extension »)
  et le fournisseur le complétait en `.gitattributes.txt` ; le contrôle
  du nom retourné y lisait un **renommage hostile** : fichier
  fraîchement créé SUPPRIMÉ, `AlreadyExists` de pure invention, rollback
  complet — d'où le dossier vu naître puis disparaître, et le message
  mensonger « choisis un autre nom » (aucun nom, aucun emplacement ne
  pouvait marcher). La règle MIME vit désormais dans une fonction
  partagée (`mimeFichierTexte`) : tout nom **sans extension réelle**
  (caché ou sans point — `gradlew`, `LICENSE`) part en type privé
  `text/x-codeide`, inconnu de la table système : le fournisseur ne
  complète RIEN et le nom demandé est préservé exactement. Filet de
  sécurité : une complétion de ce type de nom, si un fournisseur
  l'impose malgré tout, est désormais tolérée (le document créé reste le
  nôtre) au lieu d'être lue comme une collision. L'éditeur profite de la
  même règle (création d'un `.gitignore`/`Makefile` depuis le tiroir).
- **Terminal : plus de plantage à la première session** (rapport
  4a4526aa, `NullPointerException: Missing required view with ID:
  bouton_fermer_session` dans `VueOngletSessionBinding.bind`) : la
  resynchronisation d'onglets par diff (v0.31.5) bindait en binding de
  session l'onglet existant à la position visée — or quand la liste
  **grandit**, cette position est occupée par le « + » (cas minimal :
  zéro session → « + » seul en position 0 → première création), dont la
  vue est un simple `ImageView` sans `bouton_fermer_session`. La
  décision « bordable » exclut désormais explicitement le « + » (helper
  `vueOngletSessionBordable`, verrouillé par une régression sur le vrai
  `TabLayout` du layout) ; l'insertion fraîche prend place avant le
  « + », comme prévu.

## [0.31.5] – 2026-09-25

Cinquième lot de corrections après **retour d'appareil réel** (moto g06 /
Android 15) : l'écran du terminal « n'est pas à jour immédiatement »,
« le pinch zoom ne fait rien », « impossible de naviguer entre les
sessions par les onglets » ; la création de projet « un dossier porte
déjà ce nom — toutes mes tentatives sont vaines » ; et CI rouge sur
`feature:install:lintDebug` (8 erreurs lint, v0.31.4). Version
corrective (SemVer `0.N.M`). ADR 0049.

### Corrigé

- **Terminal : le texte apparaît maintenant au frame suivant**
  (« quand j'écris ou tape une cmd, le terminal n'est pas à jour
  immédiatement ») : dans l'architecture Termux, c'est le client de
  session de l'ACTIVITÉ qui repeint la vue — ici personne n'appelait
  `onScreenUpdated()`, le registre ne servant que l'aperçu throttlé
  (250 ms) des métadonnées. Le runtime expose désormais un **signal de
  repeint immédiat** (`TerminalRuntime.observeSorties()` — tampon 1,
  dernier gagnant, sans throttle) que l'écran collecte ; le
  rebranchement de session repeint explicitement après
  `attachSession`. Diagnostic établi sur le bytecode désassemblé de
  `terminal-view` v0.118.3.
- **Terminal : le pincement zoome la police** (« quand je fais un pinch
  zoom rien ne se passe ») : le contrat Termux (vérifié sur le
  bytecode) passe à `onScale` le facteur ACCUMULÉ du geste et
  n'applique JAMAIS lui-même — notre client retournait le facteur
  intact, pincement inerte par construction. Le client applique
  désormais la taille (bornes 10–30 dp, pincements négligeables < ±10 %
  ignorés, facteur consommé comme Termux) et un drapeau `zoomManuel`
  empêche les émissions d'état d'écraser la taille pincée par celle du
  réglage.
- **Terminal : les onglets répondent, même pendant une commande**
  (« je ne peux pas naviguer entre les sessions quand j'appuie sur les
  onglets ») : les onglets étaient RECONSTRUITS en entier
  (`removeAllTabs` + `addTab`) à chaque émission d'état — soit toutes
  les 250 ms pendant qu'une commande débite : les vues d'onglets
  étaient détruites sous le doigt, un tap n'atterrissait jamais. La
  resynchronisation est désormais **par diff** (mise à jour en place,
  insertions avant le « + », sélection déplacée seulement si elle
  diffère). Et une session créée devient **toujours** la session
  active — l'onglet « + » et le bouton de création partent de ce
  postulat (l'ancien code ne l'activait que si aucune n'était active,
  l'écran retombait sur l'ancienne).
- **Création de projet : pré-vol à l'appui sur « Créer »** (« un
  dossier porte déjà ce nom… toutes mes tentatives sont vaines ») :
  la vérification de l'étape Informations (délai 400 ms) peut être
  périmée au moment de créer (résidu d'une tentative échouée, dossier
  déposé entre-temps) — la cible est **re-vérifiée juste avant
  d'écrire** ; une collision détectée publie l'échec typé SANS lancer
  une création condamnée (aucune écriture). `CreateProjectUseCase`
  relaye en outre l'erreur **réelle** de l'insertion en base
  (dossier déjà référencé = `AlreadyExists`, base verrouillée = `Io`)
  au lieu d'un `Io` générique — même principe honnête que v0.31.1.
  Et `SafFileSystem` tolère une **normalisation fournisseur** du nom
  créé (espaces/points finaux rabotés — couches compatibles Windows) :
  le document créé au nom normalisé est le nôtre, pas une collision de
  pure invention.
- **Création de projet : sortie du piège de collision** : l'écran
  d'échec **affiche désormais les détails techniques** portés par
  l'erreur (URI de l'homonyme, pré-vol, insertion refusée — monospace,
  bornés, toujours copiables) et un bouton **« Changer de nom ou
  d'emplacement »** ramène directement à l'étape Informations — le
  message le réclamait depuis v0.31.0, l'écran le propose enfin
  (« Réessayer » relançait exactement la même requête condamnée).
- **CI lint verte** (`feature:install:lintDebug` échouait avec 8
  erreurs sur v0.31.4) : le journal de l'écran d'installation
  devient `NestedScrollView` (hauteur fixe et auto-défilement
  inchangés — le conteneur externe reste LE défilement de l'écran) ;
  « %d fichiers extraits » devient un vrai `<plurals>` (FR un/plusieurs,
  EN one/other) ; « paquet %2$d sur %3$d » devient l'indice
  « %2$d/%3$d » (un indice n'est pas une quantité, le pluriel serait
  sémantiquement faux). Au passage, trois `UseKtx` latents
  d'`onboarding` (`Uri.parse` → `toUri()`) — la CI les aurait vus dès
  qu'`install` serait réparé.

### Tests

- `RegistreSessionsTermuxTest` (17, +3) : le signal de repeint part
  **sans attendre la fenêtre de throttle** (`runCurrent` ne livre que
  le prêt-maintenant — un signal livré prouve l'absence de délai) ;
  la fin naturelle émet aussi le signal ; une session créée active
  toujours la nouvelle même si une autre vivait.
- `WizardViewModelTest` (32, +1) : le pré-vol de collision n'écrit
  **rien** (compteur `createDirectory` à zéro sur un système de
  fichiers instrumenté) et publie l'échec `AlreadyExists` typé.
- `CreateProjectUseCaseTest` (11, +1) : l'insertion refusée (dossier
  déjà référencé) remonte `AlreadyExists` — pas un `Io` masqué — avec
  rollback complet et registre intact.
- `SafFileSystemTest` (19, +1) : une normalisation fournisseur des
  espaces/points finaux (« MonProjet.. » → « MonProjet ») est ACCEPTÉE
  (réussite, URI réelle) — pas une collision, pas de nettoyage du
  document fraîchement créé.
- `FakeProjectRepository.seedProject` (core:testing) : semer un projet
  préexistant pour éprouver le refus d'`addProject` (index unique).

### Découvertes d'ingénierie

- **Le contrat `TerminalViewClient.onScale` n'est pas celui qu'on
  croit** : la vue (bytecode v0.118.3) accumule un facteur, le passe
  au client, et **n'applique jamais** — retourner le facteur intact
  rend le pincement inerte sans AUCUN autre symptôme. Un client doit
  appliquer la taille lui-même puis retourner `1.0f`.
- **Un TabLayout reconstruit mange les taps** : `removeAllTabs` +
  `addTab` à chaque émission d'un état qui change toutes les 250 ms
  détruit les vues d'onglets sous le doigt — la sélection programmatique
  garde son anti-réentrance, mais le GESTE utilisateur, lui, n'atterrit
  jamais. Diff ou rien.
- **La vérification asynchrone n'est pas une garantie** : un état de
  vérification « Valide » peut être périmé au moment de l'action — une
  action destructrice (ou simplement coûteuse) doit re-vérifier sa
  précondition dans l'instant, pas se fier à un cache daté.
- **`lintDebug` local doit couvrir les modules touchés, pas seulement
  `:app`** : la CI lance le lint de CHAQUE module — v0.31.4 n'avait
  vérifié que `:app:lintDebug`, la CI a vu les 8 erreurs
  d'`feature:install` que personne n'avait regardées.

## [0.31.4] – 2026-09-25

Quatrième lot de corrections après **retour d'appareil réel** (app
v0.31.3, rapport de plantage `f2699ac5` : retour depuis l'écran du
terminal → `IllegalStateException: Le conteneur de navigation est
introuvable dans MainActivity`), écran d'installation « qui ne se met
pas à jour correctement », et demande explicite : **`apt update`
obligatoire, paquets d'outils optionnels** pour la première
configuration (proposés plus tard par les fonctionnalités). Version
corrective (SemVer `0.N.M`). ADR 0048.

### Corrigé

- **Plantage déterministe au retour depuis le terminal** (rapport
  `f2699ac5` — l'app s'effondrait juste après l'ouverture d'un projet
  et un passage au terminal, d'où l'impression que « le problème de
  création de projet persiste ») : `AppNavigatorImpl` est scopé à
  l'activité qui l'injecte — injecté par `TerminalActivity` (plein
  écran, SANS conteneur de navigation), son accès au contrôleur
  levait à chaque flèche retour. Le navigateur est désormais **robuste
  hors graphe** : `goBack()` referme l'activité plein écran (les
  sessions du terminal survivent via le service foreground —
  l'intention documentée de l'écran), et les navigations vers le
  graphe appelées depuis le terminal ou l'éditeur relancent
  `MainActivity` (désormais `singleTop`) avec un **routage d'écran**
  `EXTRA_ECRAN_CIBLE` + `REORDER_TO_FRONT` — l'instance existante
  reçoit `onNewIntent` sans recréation, l'activité appelante survit
  dessous (retour système = retour à l'éditeur, état intact). Trois
  chemins latents de la même classe corrigés au passage : flèche
  retour du terminal, « journal complet » de l'éditeur
  (diagnostics), carte Terminal de l'éditeur (installation).
- **Écran d'installation : journal effacé et boutons terminaux
  manquants** (rapport « ne se met pas à jour correctement ») :
  chaque émission d'état reconstruisait un état de rendu **sans
  journal** — la sortie en direct clignotait puis restait vide entre
  les tics de progression ; et depuis v0.31.2, les phases réussite et
  échec n'affichaient AUCUN bouton (le « Fermer » n'était jamais rendu
  visible, le « Réessayer » restait masqué après lancement) — seul le
  geste système permettait de sortir. L'état et le journal sont
  désormais **combinés** dans un seul flux, et chaque phase rend ses
  actions explicitement.

### Ajouté

- **Outils de développement optionnels et différés** (demande
  utilisateur, ADR 0048) : la première configuration couvre
  l'**environnement de base** — shell, apt, dépôt mis à jour
  (`apt update` OBLIGATOIRE, la correction v0.31.3 rendue permanente)
  — sans les paquets d'outils. Ceux-ci (`openjdk-17`, `git` ; SDK
  Android et cmdline-tools quand le dépôt les publiera) s'installent
  **à la demande** depuis le même écran (bouton dédié, statuts par
  paquet) ou plus tard : la synchronisation, l'exécution de tâches et
  le sélecteur de l'éditeur refusent AVANT toute tentative quand le
  JDK manque, avec un message actionnable dans l'onglet Sortie
  (« installez les outils depuis la carte Terminal du tiroir ») au
  lieu d'une connexion perdue opaque après reprises. L'échec des
  outils est un état distinct (`OutilsEchoues`) : l'environnement de
  base reste installé, seule la phase d'outils est reprise ; son
  annulation conserve aussi la base. Au redémarrage de l'application,
  un bootstrap déjà installé (marqueur) démarre l'état à « terminé »
  — proposition d'outils, jamais une réinstallation destructrice.
- **Écran d'installation refondu** (design « plus professionnel,
  plus propre ») : progression structurée en deux sections —
  « Environnement de base » (huit étapes du pipeline) et « Outils de
  développement » (statuts par paquet : en attente optionnelle, en
  cours avec rotation, installé, absent) —, invite à carte
  découpée (inclus / optionnel), résultat en deux temps (environnement
  prêt, puis proposition des outils) et compteurs d'étape inchangés.

### Tests

- `InstallateurBootstrapTest` : pipeline de base arrêté après
  `apt update` (aucun `apt install`), `installerOutils` à la demande
  (paquets, échec global → `OutilsEchoues` base conservée, échec
  partiel rapporté), annulation en pleine phase d'outils → retour à
  `Terminee` base intacte, `installerOutils` sans base terminée sans
  effet, redémarrage avec marqueur → état initial `Terminee`.
- `InstallViewModelTest` : journal **persistant à travers les
  changements d'état** (régression du rapport d'appareil), phase
  `OUTILS_ECHEC`, relais de l'ordre d'installation des outils,
  paquets proposés dans l'état de rendu.
- `MainActivityTest` : routage d'écran à froid (intention de
  lancement) et à chaud (`onNewIntent`, `singleTop`).
- `ToolingEditorViewModelTest` : refus JDK typé (synchronisation et
  exécution) sans aucun appel tooling.
- `BootstrapInstallationTest` (modèle) : six états de la machine,
  `OutilsEchoues` distinct de `Echouee`.

### Découvertes

- `@ActivityScoped` (Hilt) lie l'implémentation à l'activité qui
  l'injecte — une implémentation pensée « pour MainActivity » devient
  fautive dès qu'une seconde activité l'injecte : le défaut ne se
  voyait pas dans le graphe, uniquement sur l'appareil.
- Deux bugs pouvaient coexister dans le même écran sans jamais se
  rencontrer : l'effacement du journal (v0.31.2) masquait l'absence
  des boutons terminaux — le journal clignotant retenait toute
  l'attention du retour utilisateur.

## [0.31.3] – 2026-09-25

Troisième lot de corrections après **retour d'appareil réel** (app
v0.31.2 : `apt update` sort en **code 100** à l'installation — `E:
Unable to mkstemp $PREFIX/tmp/clearsigned.message… - GetTempFile (2:
No such file or directory)`), plus l'échec `:app:lintDebug` de la CI
GitHub depuis v0.31.1 (`ExpiredTargetSdkVersion`). Version corrective
(SemVer `0.N.M`). ADR 0047.

### Corrigé

- **`apt update` code 100 — répertoire `tmp` du préfixe absent** :
  l'archive publiée par `codeide-packages` **n'embarque pas l'entrée
  `tmp/`** (constat du 2026-09-25 par comparaison avec le bootstrap
  officiel Termux : 280 répertoires dont `tmp/` contre 107 sans), or
  `TMPDIR` pointe sur `$PREFIX/tmp` — chaque fichier temporaire d'apt
  vise donc un répertoire inexistant (`mkstemp` ENOENT, errno 2 : PAS
  un refus de permission, errno 13 — le stockage privé de
  l'application ne demande rien). Deux couches de défense :
  l'extracteur crée désormais explicitement `staging/tmp` (porté dans
  `$PREFIX` par la bascule, idempotent si une future archive l'embarque
  — test de régression sur archive sans l'entrée), et
  `assurerRepertoiresProcessus` recrée `$PREFIX/tmp` et `$HOME` à
  CHAQUE construction d'environnement de sous-processus (lanceur natif,
  sessions du terminal, daemon du tooling) — couvrant aussi les
  préfixes posés avant cette version et le `tmp` supprimé à la main
  (panne documentée par la FAQ Termux : `rm -rf $PREFIX/tmp` rend apt
  inutilisable). Le warning « Conflicting distribution (expected
  stable but got) » accompagnant la panne est un artefact du même
  pipeline cassé : le `Release` servi en ligne porte bien
  `Suite: stable` / `Codename: stable` (re-vérifié).
- **CI rouge sur `:app:lintDebug` depuis v0.31.1**
  (`ExpiredTargetSdkVersion` — « Google Play requires that apps target
  API level 33 or higher ») : l'exception v0.31.1 n'avait désactivé
  que `ExpiringTargetSdkVersion` (le CONSEIL, sévérité avertissement
  montée en erreur par `warningsAsErrors`) — l'ERREUR directe est une
  issue lint DISTINCTE. Les deux ID sont désormais désactivés, même
  justification (cible 28 délibérée pour W^X, ADR 0045 ; application
  chargée par side-loading, l'exigence Play ne s'applique pas).

### Ajouté

- **Accès au stockage partagé, OPT-IN, pour le terminal** (répond à la
  demande utilisateur « demander les permissions de lecture et
  d'écriture de stockage », réinterprétée techniquement : la panne apt
  ne relevait d'aucune permission, mais lire/écrire la mémoire partagée
  depuis le terminal est un besoin légitime — modèle Termux, même
  contrainte cible 28) : trio READ/WRITE_EXTERNAL_STORAGE (Android <
  11) + MANAGE_EXTERNAL_STORAGE (« Tous les fichiers », Android 11+)
  au manifeste avec `requestLegacyExternalStorage` ; section
  « facultative » sur la page Notifications de l'assistant — bouton de
  demande (requête runtime sous Android 11, réglage système
  au-delà), état RÉEL relu au retour sur la page
  (`isExternalStorageManager` / permission WRITE — jamais supposé),
  repli par les réglages de l'application. Aucun parcours ne l'exige :
  « Suivant » passe sans rien accorder, le droit reste révocable dans
  les réglages Android. Le fonctionnement de base, lui, n'exige
  toujours RIEN (stockage privé + SAF, ADR 0003/0034).

### Tests

- `ExtracteurBootstrapTest` : une archive sans entrée `tmp/` produit
  quand même un répertoire `tmp` dans le staging (régression code 100).
- `AssurerRepertoiresProcessusTest` (nouveau) : création depuis racine
  vide, idempotence avec contenu conservé, recréation après
  suppression sous un préfixe existant.
- `OnboardingViewModelTest` : `DemanderStockage` émet la requête sans
  avancer la page (opt-in), `DemanderReglagesStockage` ouvre les
  réglages, `ConsignerStockage` reflète l'état réel.

### Découvertes

- `ExpiringTargetSdkVersion` (avertissement) et
  `ExpiredTargetSdkVersion` (erreur) sont DEUX issues lint distinctes :
  désactiver la première ne couvre pas la seconde — la CI est restée
  rouge un mois de release avant que quelqu'un la lise.
- L'archive publiée par `codeide-packages` diverge du bootstrap
  officiel Termux sur les entrées de répertoires vides (`tmp/` inclus)
  — un tar(zip) bien formé n'est pas garanti équivalent entrée par
  entrée ; les répertoires attendus par l'environnement doivent être
  garantis côté applicatif.
- errno fait la différence entre « permission refusée » (13, EACCES)
  et « n'existe pas » (2, ENOENT) : lire le code d'erreur avant
  d'en conclure une cause permission — sur stockage privé applicatif,
  aucune permission ne s'applique de toute façon (ADR 0003/0034).
- Les chaînes de la page Notifications (v0.31.2) n'avaient jamais été
  traduites en anglais — comblées au passage.

## [0.31.2] – 2026-09-25

Deuxième lot de corrections après **retour d'appareil réel** (rapport de
plantage 511e1c7f, moto g06, Android 15 / API 35, fr-HT — v0.31.1) :
l'écran Terminal plantait à la première session (« Can't create handler
inside thread that has not called Looper.prepare() »), l'écran
d'installation n'affichait pas ce qui se faisait réellement et l'échec
« la configuration des paquets a échoué » arrivait sans le moindre
indice, et aucune page de l'assistant ne parlait des permissions de
notification. Version corrective (SemVer `0.N.M`). ADR 0046.

### Corrigé

- **Plantage de l'écran Terminal à la création de la première session**
  (`RuntimeException: Can't create handler inside thread
  DefaultDispatcher-worker-1 that has not called Looper.prepare()`) :
  le constructeur de `TerminalSession` (Termux) crée son
  `MainThreadHandler` — un `Handler` SANS Looper explicite — qui exige
  d'être construit sur un thread doté d'un `Looper`, donc le thread
  principal. `RegistreSessionsTermux.createSession` tournait sur
  `Dispatchers.Default` (worker sans Looper) : plantage déterministe à
  l'ouverture du terminal. La création de la coquille bascule désormais
  par `withContext(dispatchers.main)` (le fork/exec du pty est bref,
  comme dans Termux qui crée ses sessions sur l'UI) ; la dépendance
  `kotlinx-coroutines-android` rejoint `core:terminal-runtime` — aucun
  module ne fournissait le dispatcher principal réel. Test de
  régression : un dispatcher instrumenté prouve le saut de contexte
  vers `main` pendant la création.

### Ajouté

- **Journal d'installation en direct** (motivation : « la configuration
  des paquets a échoué » sans indice — l'écran montrait un libellé figé
  et une barre indéterminée) : le port `BootstrapInstaller` expose un
  flux `journal` (lignes bornées à 200) alimenté par les transitions
  d'étapes ET par la **sortie réelle des sous-processus** — le drainage
  parallèle stdout/stderr de `SupervisionProcessus` achemine chaque
  ligne au fil de l'eau (second stage, `apt update`, `apt install`) ;
  stdout n'est plus jeté silencieusement. À l'échec, la sortie en échec
  reste visible : la prochaine panne d'apt sur l'appareil est
  diagnostiquable depuis l'écran (cause racine probable : dépôt,
  DNS, signature — le dépôt et les paquets ont été re-vérifiés sains en
  ligne côté serveur).
- **Écran d'installation refondu** : checklist des neuf étapes du
  pipeline (terminée ✓ / en cours avec rotation / en attente), compteur
  de l'étape courante (« 12,4 Mo sur 43,0 Mo », « 1 234 fichiers
  extraits », « openjdk-17 — paquet 1 sur 2 »), barre de progression
  déterminée dès que le serveur annonce la taille, et **console de
  journal en direct** (monospace, auto-défilement) affichée pendant la
  progression et conservée à l'échec.
- **Détails techniques dépliables à l'échec** : le message actionnable
  reste court, le code de sortie et les dernières lignes d'erreur de
  l'étape fautive (`apt update → code 100 — E: …`) se déplient sous pli
  — plus jamais « la configuration des paquets a échoué » sans dire
  laquelle ni pourquoi.
- **Page « Notifications et stockage » dans l'assistant de premier
  lancement** (entre Terminal et Apparence) : explique ce que
  l'application fait des notifications (service foreground du
  terminal, installations longues), propose la demande directe
  (`POST_NOTIFICATIONS`, Android 13+) avec repli vers les réglages, et
  **documente pourquoi aucune permission de stockage n'est demandée**
  (environnement Linux dans le stockage privé + dossier de travail par
  le sélecteur du système SAF — `READ/WRITE_EXTERNAL_STORAGE` comme
  `MANAGE_EXTERNAL_STORAGE` restent inutiles, ADR 0003/0034/0046). La
  demande n'est jamais bloquante, l'état réel
  (`areNotificationsEnabled`) fait foi et est relu au retour sur la
  page.
- **Journalisation du pipeline d'installation** : l'installateur consigne
  désormais ses transitions d'étapes et ses échecs typés dans l'AppLogger
  (tag `Installateur`) — les prochains rapports de plantage contiendront
  la raison d'un échec d'installation au lieu d'un silence.

### Tests

- `RegistreSessionsTermuxTest` : 14 tests (+1 : création de la coquille
  sur le dispatcher principal — régression 511e1c7f, dispatcher
  instrumenté comptant les dispatchs).
- `InstallateurBootstrapTest` : 13 tests (+2 : le journal suit les
  étapes et la sortie des sous-processus ; un échec d'apt laisse la
  sortie en échec dans le journal) — constructeur enrichi de l'AppLogger
  double, `FakeNativeProcessLauncher`/`ProcessusScripte` inchangés.
- `ConfigurateurAptTest` : 8 tests (+1 : la sortie d'apt alimente le
  consommateur en direct, stdout et stderr).
- `InstallViewModelTest` : 14 tests (+3 : journal dans l'état de rendu,
  détails techniques de l'échec exposés, absence de détails sans
  contenu) ; `FakeBootstrapInstaller` gagne un journal pilotable.
- `OnboardingViewModelTest` : 19 tests (+6 : ordre Terminal →
  Notifications → Apparence, effets de demande d'autorisation et de
  réglages, consignation de l'état réel, bornes de navigation recalées
  sur sept pages).

### Découvertes d'ingénierie

- **`TerminalSession` (Termux) exige le thread principal** : son
  `MainThreadHandler` est un `Handler` sans Looper explicite — construit
  hors du thread principal, il lève immédiatement. Termux crée ses
  sessions sur l'UI ; toute intégration du terminal-emulator doit en
  faire autant. Et sans artefact `kotlinx-coroutines-android` quelque
  part dans le graphe, `Dispatchers.Main` n'existe pas à l'exécution
  (le dispatcher est chargé par ServiceLoader) — un module qui l'utilise
  doit déclarer la dépendance lui-même.
- **L'archive réelle du bootstrap (vérifiée par téléchargement et
  empreinte)** : `bin/apt` et `bin/dpkg` sont des fichiers réguliers, la
  base dpkg déclare 149 paquets « install ok installed », le
  `sources.list` embarqué porte la bonne URL sans `[trusted=yes]`
  (corrigé par le configurateur) et le script de second stage construit
  pour `jo.codeide` no-oppe (`TERMUX_PACKAGE_MANAGER` vide dans cette
  publication) — l'échec « la configuration des paquets » vient donc
  d'`apt update`/`apt install` eux-mêmes ; sa sortie exacte sera
  désormais visible dans le journal et les détails.
- **stdout des sous-processus doit être drainé ET affiché** : un tuyau
  non lu bloque le processus (piège ProcessBuilder classique, déjà
  connu) — mais le drainer en le jetant coûte le diagnostic : la sortie
  d'apt est le seul moyen de comprendre un échec de dépôt sur l'appareil.

## [0.31.1] – 2026-09-25

Premier lot de corrections après **retour d'appareil réel** (rapport de
plantage 7842f130, moto g06, Android 15 / API 35, fr-HT — v0.29.0) :
l'écran Terminal plantait à l'ouverture, l'installation du bootstrap
échouait « de façon inattendue » après l'extraction, et la création de
projet rapportait des collisions imaginaires. Version corrective
(SemVer `0.N.M`), la numérotation des étapes suit son cours. ADR 0045.

### Corrigé

- **Plantage de l'écran Terminal à chaque ouverture**
  (`NullPointerException` sur `List.iterator()` dans
  `ClavierEtenduView.construire`, remontée en `InflateException` sur
  `activity_terminal`) : la liste des touches était déclarée APRÈS le
  bloc `init` qui l'itère — Kotlin exécute les initialisateurs dans
  l'ordre de déclaration, la liste valait encore `null` pendant la
  construction. Réordonnée avant le bloc, avec avertissement KDoc ;
  test de régression `ActivityTerminalLayoutTest` gonflant le VRAI
  layout sous Robolectric (même précédent que le correctif v0.19.0 de
  l'éditeur).
- **Bootstrap : « erreur inattendue » après l'extraction** — cause
  racine W^X : Android 10 interdit à une app ciblant `targetSdk` 29+
  d'exécuter un binaire écrit dans ses données ; le second stage
  (`bin/bash` extrait dans `filesDir/usr`) était refusé par le noyau
  (EACCES) et son `IOException` tombait dans le fourre-tout
  `AppError.Unknown`. **`targetSdk` passe à 28** (précédent Termux,
  même raison ; compileSdk 37 inchangé ; ADR 0045 — le point T7 « ADR
  targetSdk sur appareil réel » est tranché) ; par ailleurs le refus
  de lancement du second stage est désormais typé
  `Bootstrap(PermissionRefusee, "second stage non exécutable : …")`
  (même traduction que `ConfigurateurApt` pour `apt`) au lieu du
  message muet.
- **« Bootstrap déjà installé » après un échec d'installation** : la
  bascule atomique pose le préfixe AVANT le second stage, et
  `bootstrapInstalle` ne testait que « un shell exécutable sous
  `bin/` » — vrai dès la bascule. L'installateur dépose désormais un
  marqueur d'installation terminée
  (`$PREFIX/.codeide-installation-terminee`) juste avant l'état
  `Terminee`, et la localisation exige le shell ET le marqueur : un
  préfixe extrait n'est plus « installé ». Le marqueur vit sous le
  préfixe : une reprise le détruit avec lui, cohérent avec le contrat
  de reprise.
- **Création de projet : « un dossier porte déjà ce nom » mensonger** :
  `CreateProjectUseCase.ecrirePlan` mappait TOUT échec de
  `createFile` sur `Storage(AlreadyExists)` — un refus d'E/S, une
  permission perdue ou un emplacement parti affichaient la collision.
  L'erreur typée réelle remonte désormais telle quelle (l'écran
  distingue déjà introuvable/écriture/collision). Durcissement
  compagnon dans `SafFileSystem.creer` : la pré-vérification
  d'homonyme vit dans le `try` (un listing refusé devient une erreur
  de stockage typée, plus une exception non traduite) et une
  vérification de nom retourné ILLISIBLE ne détruit plus le document
  créé au motif d'une collision de pure invention (la décision ne se
  prend que sur un nom lisible et différent).

### Ajouté

- **Directive de vérification standard** (utilisateur, 2026-09-25) :
  chaque étape/correctif est vérifié en **légère + `assembleDebug`** —
  hygiène (`spotlessCheck detekt`), compilation et tests des modules
  touchés, APK toujours assemblé ; `koverVerify`, `lintDebug`,
  `checkModuleDependencies` et les modules non touchés restent garantis
  par la CI à chaque push (chaîne complète = référence des audits de
  fin de phase). AGENTS.md § Commandes et CONVENTIONS.md § Livraison
  mis à jour.
- `FakeFileSystem.fileCreateFailure` (core:testing) : robinet de
  défaillance propre à `createFile` — éprouver l'échec d'un fichier du
  plan sans faire tomber la création du dossier racine.

### Tests

- `ActivityTerminalLayoutTest` (feature:terminal, 2) : le layout
  terminal se gonfle sans exception (régression 7842f130) et le
  clavier étendu porte ses huit touches ;
- `InstallateurBootstrapTest` (+2) : succès dépose le marqueur
  d'installation (et `bootstrapInstalle` le voit), second stage non
  exécutable → `PermissionRefusee` typée ET marqueur absent ;
- `LocalisationOutilsTest` (3 réécrits/ajoutés) : le double marqueur
  (shell + installation terminée), refus du shell non exécutable, refus
  du marqueur sans shell ;
- `ToolchainBootstrapTest` (+1, fixture ajustée) : un préfixe extrait
  sans marqueur n'est pas « bootstrap installé » ;
- `CreateProjectUseCaseTest` (+1) : un échec de création de fichier
  remonte sa raison réelle (`Io`), pas une collision, avec rollback
  complet.

### Découvertes d'ingénierie

- W^X (SELinux) : `ProcessBuilder.start()` ne dit PAS « permission
  refusé d'exécuter des données d'app » — il lève une `IOException`
  générique ; le seul indice est le contexte (binaire extrait par
  l'app). Termux cible 28 pour exactement cette raison depuis
  Android 10.
- L'ordre de déclaration Kotlin (propriétés et blocs `init`) est un
  piège à test JVM : le NPE n'apparaissait ni à la compilation ni dans
  les tests du ViewModel — seulement à l'inflation réelle du layout.
  Deux leçons consolidées dans AGENTS.md § Leçons.

## [0.31.0] – 2026-09-25

Étape 30 (= G6 du prompt compagnon « Tooling Gradle (client-serveur) »,
dernière) : robustesse éprouvée au **chaos réel** et audit final. Le
chaos a révélé un vrai trou : un build EN COURS pendait à jamais à la
perte de l'orchestrateur — il se conclut désormais proprement. Le
prompt est couvert de bout en bout : G1 protocole, G2 orchestrateur,
G3 client, G4 daemon, G5 éditeur, G6 robustesse. ADR 0044,
`docs/TOOLING.md` (final).

### Corrigé

- **Builds orphelins à la perte de session** (découvert par le chaos
  §7.5) : quand l'orchestrateur meurt en plein build (kill, crash,
  socket perdue), `romprePromesses` résolvait les requêtes en attente
  mais aucun `BuildFinished` n'arriverait jamais — l'état du build
  restait EN COURS à vie et son canal de sortie restait ouvert (collect
  de l'onglet Sortie suspendu indéfiniment). `GradleApiImpl
  .rompreBuildsEnCours()` : chaque build EN COURS passe `ECHOUE`
  (« connexion avec l'orchestrateur perdue ») et son canal se ferme —
  même sémantique de clôture que la fin normale, un collecteur tardif
  draine le tampon puis complète. Prouvé unitairement (EOF factice) et
  en réel (`kill -9` en plein build).

### Ajouté

- **Chaos réel §7.5** (`ChaosToolingTest`, tooling:daemon) sur le
  harnais du bout-en-bout G4 (harnais passé `internal` et instrumenté
  — registre des process lancés, dernière session acceptée) :
  - **process tué en plein build** (`kill -9` sur la tâche longue) :
    le build se conclut `ECHOUE` en quelques secondes, le canal se
    ferme, le daemon détecte la mort et relance borné, la connexion
    remonte avec un secret neuf, aucun process ne survit à l'arrêt ;
  - **socket perdue côté app** (rupture du canal côté hôte) : le
    process orchestrateur voit l'EOF et sort SEUL (code 0, avant même
    le health check) — aucun orphelin ;
  - version incompatible et JDK introuvable : déjà prouvés
    (`HandshakeAppTest`, `DaemonManagerTest`) — le tableau de chaos de
    `docs/TOOLING.md` consolide les six pannes et leurs preuves.
- **`docs/TOOLING.md` final** : architecture livrée (schéma des
  processus), table des délais de garde §7.5 (client + serveur + health
  check, effet au dépassement), tableau du comportement au chaos,
  journalisation `gradle-server`, ce que la CI garantit.

### Audité

- Délais de garde §7.5 inventoriés aux deux frontières — déjà en place
  depuis G2/G3 (rien à réécrire, tout documenté) : client sync 5 min /
  tâches 30 s / connexion 10 s ; serveur build 30 min (annulation
  forcée) / sync 5 min / tâches et dépendances 30 s / modèle 5 min ;
  health check ping 5 s / muet 15 s.
- Aucun TODO/FIXME, detekt strict vert, `ServerVersion` inchangée
  (0.30.0 — G6 ne redélivre pas l'orchestrateur : le correctif vit
  côté client).
- Points T7 exigeant l'appareil (ADR targetSdk, revue mémoire
  LeakCanary) : explicitement **différés** à l'appareil réel — aucun
  faux « terminé ».

### Tests

- `ChaosToolingTest` (2, réels) : kill -9 en plein build + socket
  perdue ;
- `GradleApiImplTest` étendu (17) : « une déconnexion échoue les builds
  en cours » ;
- suites daemon (10 + 4 + 1) et client (17) intégralement au vert sur
  le harnais modifié.

## [0.30.0] – 2026-09-24

Étape 29 (= G5 du prompt compagnon « Tooling Gradle (client-serveur) ») :
la boucle UI du tooling se referme — l'espace de travail parle à
l'orchestrateur. L'onglet **Sortie** devient fonctionnel (lignes du build
en direct, statut et durée en en-tête, annulation en vol), l'onglet
**Problèmes** affiche les diagnostics de compilation groupés par fichier
avec saut à la ligne, les **diagnostics inline** arrivent dans les onglets
ouverts (`session.setDiagnostics`, point d'ancrage posé en 0.17.0 — ADR
0029), et les actions **Synchroniser** / **Exécuter** rejoignent la
toolbar de l'éditeur. ADR 0043, `docs/TOOLING.md`.

### Ajouté

- **Producteur de diagnostics serveur** (`tooling:server`) : les
  compilateurs écrivent leurs positions sur **stderr**, ligne par ligne —
  jamais dans le message d'échec final. `StreamingOutputStream` expose un
  observateur par ligne décodée (AVANT publication), `BuildHandler` y
  branche `ParseurDiagnostics` (formats javac `fichier:ligne[:col]:
  error: message` et kotlinc `e: file://fichier:ligne:col message`) :
  chaque ligne reconnue devient un événement `Diagnostic` du protocole
  G1 ; une ligne sans position complète (contexte, carets, notes de
  tâches) est ignorée — jamais de demi-renseignement (§1.6).
  `ServerVersion.CURRENT` passe à 0.30.0.
- **Use cases du domaine** (`core:domain`) : `SynchroniserProjetUseCase`,
  `ExecuterTachesUseCase`, `AnnulerBuildUseCase` et
  `ListerTachesProjetUseCase` — délégations pures au port
  `GradleToolingRepository` (zéro type tooling, règle §2.2), le dossier
  réel étant résolu par l'appelant via `ResoudreRepertoireProjet`
  (SAF → FUSE, même traduction que le terminal T6 — une seule source de
  vérité), journalisation identifiante (le dossier n'apparaît jamais
  dans le journal).
- **`GradleService` et panneaux de l'éditeur** (`feature:editor`) :
  - `GradleService` : détenteur d'état pur (précédent
    `FiltrageProjets` de l'accueil) — connexion (daemon G4), build suivi
    (statut, durée, message d'échec), **fenêtre de sortie bornée à
    2 000 lignes** (tête tronquée : la sortie complète vit dans le canal
    rejouable du client, ADR 0041 — la console n'est qu'une vue, un
    build bavard ne mange pas la mémoire), diagnostics groupés par
    fichier (tri par ligne), état de synchronisation ;
  - onglet **Sortie** : lignes colorées par flux (stdout/stderr),
    auto-défilement tant que la fenêtre grandit (un build fini ne
    défile plus), statut en en-tête (synchronisation en cours / réussie
    en X s / build en cours / réussi / échec / annulé), bouton Arrêter
    visible en vol seul ;
  - onglet **Problèmes** : groupes repliés par fichier (nom + compte),
    pastilles par sévérité, appui = sélection de l'onglet concerné +
    `scrollToLine` + curseur au début de ligne (offsets bornés au
    document) ;
  - **diagnostics inline** : chaque onglet ouvert dont le chemin relatif
    est le suffixe d'un fichier diagnostiqué reçoit ses soulignés
    cel-ui (`DiagnosticShift`, sévérités erreur/avertissement/info,
    colonne et offsets bornés au document) ; les autres onglets sont
    nettoyés à chaque publication ;
  - actions **Synchroniser** et **Exécuter…** dans la toolbar (icônes
    maison), sélecteur de tâches (dialogue alimenté par
    `ListerTachesProjetUseCase`, exécution au choix), connexion
    débranchée = libellé dédié, libellés FR/EN.
- **Journal unifié tag `gradle-server`** : stderr/stdout du process déjà
  journalisés par le daemon G4 (onglet Journal, écran Diagnostic) — le
  point du prompt est couvert par construction, rien de nouveau à
  câbler.

### Tests

- `GradleUseCasesTest` (5) : délégation au port, contexte IO,
  journalisation identifiante ;
- `ParseurDiagnosticsTest` (6) : javac avec/sans colonne, niveaux,
  kotlinc, bruit ignoré (caret, ligne de contexte, note de tâche) ;
- `GradleServiceTest` (6) : fenêtre bornée, build suivi seul, groupes
  triés, échec de synchronisation typé ;
- `ToolingEditorViewModelTest` (6) : actions → use cases, annulation,
  sélecteur de tâches, diagnostics → état + session inline ;
- `ServeurIntegrationTest` étendu (16) : la fixture « erreur de
  compilation » émet les diagnostics du build, parsés depuis stderr.

### Découvertes d'ingénierie (leçons)

- **Les diagnostics de compilation ne sont PAS dans le message d'échec
  du build** : le résumé final ne porte aucune position ; les
  `fichier:ligne:colonne` vivent sur stderr aux formats stables de
  javac/kotlinc — les extraire du flux (et non du message) rend le
  producteur indépendant du dialecte de l'échec final.
- **L'appariement onglet ↔ diagnostic par SUFFIXE de chemin relatif** :
  le dossier FUSE réel d'un projet peut être encore inconnu quand
  l'onglet est déjà ouvert (résolution différée) ; l'espace ne construit
  qu'un projet à la fois — le suffixe du chemin relatif suffit, un
  préfixe exigerait une résolution qu'on n'a pas encore.

## [0.29.0] – 2026-09-24

Étape 28 (= G4 du prompt compagnon « Tooling Gradle (client-serveur) ») :
module `tooling:daemon` — le composant qui fait vivre l'orchestrateur :
déploiement du JAR depuis les assets, lancement du process JVM via le port
`NativeProcessLauncher` (jamais redéfini), health check ping/pong et
relances bornées. **Premier bout-en-bout réel (§7.4)** : le daemon lance le
VRAI orchestrateur en sous-processus et exécute un VRAI build Gradle. Le
client de G3 n'est plus muet : les états `EN_CONNEXION`/`ECHOUEE` du port
sont animés. ADR 0042, `docs/TOOLING.md`.

### Ajouté (procédure)

- **Logique graduée de `koverVerify`** (prompt Vérification-1, §2.6) :
  comme `verify-templates.sh`, il ne tourne en fin d'étape que si l'étape
  modifie au moins un module soumis au seuil de 80 % (`core:model`,
  `core:domain`, `core:bootstrap`, `core:terminal-runtime`,
  `tooling:client`, `tooling:server`) — la CI GitHub, elle, l'exécute
  toujours (garantie from-scratch, ADR 0037). Documentation :
  `docs/CONVENTIONS.md` et `AGENTS.md`.

### Ajouté

- **Module `tooling:daemon`** (bibliothèque Android + Hilt) :
  - `DaemonManager` : machine d'états complète — déploiement → écoute
    AVANT le lancement (§5.1, élimine tout fichier de découverte) →
    lancement `java -Xmx256m -jar` sur le port `NativeProcessLauncher`
    (environnement canonique construit par `core:bootstrap`) → handshake
    au secret frais (`SecureRandom` 32 octets, par tentative, jamais
    écrit/journalisé) → surveillance → mort → relance bornée
    (`MAX_RECONNECT_ATTEMPTS` 5, repli exponentiel 1 s → 10 s) ;
    épuisement → `ECHOUEE` (échec définitif jusqu'à un nouveau
    `demarrer`) ; échecs DÉFINITIFS sans relance : handshake refusé
    (`EchecHandshakeClient`), code de sortie 2 (arguments invalides —
    notre bug), JAR indisponible ; **JDK absent = état `DECONNECTEE`
    sans aucun lancement** (le bootstrap peut s'installer ensuite) ;
  - `JarDeployer` (§5.4 « marqueur de version ») : copie atomique
    (`.tmp` + renommage) du JAR des assets vers `filesDir/tooling/`,
    recopie SEULEMENT si l'empreinte SHA-256 de la source diffère du
    marqueur — un redémarrage sur un JAR inchangé ne recopie rien ;
  - health check (§5.4) : `PingMessage` toutes les 5 s
    (`HEARTBEAT_INTERVAL_MS`), orchestrateur déclaré muet si le repère
    `dernierPongMs` vieillit au-delà de 15 s — arrêt forcé puis relance ;
  - stderr/stdout du process → journal applicatif (tag `gradle-server`,
    WARN/INFO — règle 14/ADR 0040) : l'onglet Journal et l'écran
    Diagnostic voient l'orchestrateur comme tout producteur applicatif ;
  - `HoteSocketTooling` (couture) : production = enveloppe du
    `GradleSocketServer` de G3 (la colle `LocalSocket` reste concentrée
    dans tooling:client), tests = hôte JVM sur vrai socket Unix ;
  - câblage Hilt (`ModuleDaemon`) + agrégation dans `:app`.
- **APIs publiques ciblées dans `tooling:client`** (visibilité G4, ADR
  0041 décision 8) : `GradleApiImpl.marquerEnConnexion()`/`marquerEchouee()`
  animent les états intermédiaires du port, `GradleApiImpl.dernierPongMs`
  expose le repère de santé (rafraîchi par le pompe à chaque
  `PongMessage`, initialisé à l'ouverture de session),
  `GradleApiImpl.etatConnexion` lecture directe ; `GradleSocketServer`,
  `SessionTooling` et `EchecHandshakeClient` deviennent publics pour le
  daemon.
- **Démarrage du daemon** : `CodeIdeApplication` (processus principal) —
  `demarrer` à la création, re-déclenchement idempotent quand
  l'installation du bootstrap aboutit (`BootstrapInstaller.etat` →
  `Terminee`) ; la mort de l'app ferme le socket → l'orchestrateur voit
  l'EOF et s'arrête SEUL (code 0) : aucun process orphelin.
- **Exception de dépendance documentée** (`ModuleRulesPlugin`) :
  `:tooling:server` n'entre dans `:tooling:daemon` qu'en configuration de
  TEST (bout-en-bout §7.4 — le VRAI orchestrateur relancé en
  sous-processus depuis la JVM de test ; en production le daemon ne voit
  du serveur que le JAR déployé).

### Découvertes d'ingénierie (leçons)

- **android.jar éclipse les API java.* récentes à la COMPILATION des tests
  unitaires Android** : le classpath de compilation porte android.jar, et
  pour les classes java.* couvertes par les builtins Kotlin c'est la
  version JDK 8 qui gagne — `Process.onExit()` (JDK 9) et
  `ServerSocketChannel.open(ProtocolFamily)` (JDK 15) ne résolvent PAS
  alors qu'elles existent au runtime. Contournements : réflexion ciblée
  (même précédent que le `pid` de `ProcessusGere`) et sondage `isAlive`
  (miroir du port production).

### Tests

- **13 tests tooling:daemon** :
  - `DaemonManagerTest` (11) sur fakes : ordre écoute-avant-lancement,
    secret transmis et capté, stderr → journal, relance avec secret neuf,
    épuisement des 5 tentatives → `ECHOUEE`, JDK absent sans lancement,
    handshake refusé définitif, orchestrateur muet tué par le health
    check, `arreter` sans relance, commande par défaut, JAR indisponible
    définitif, code 2 définitif ;
  - `JarDeployerTest` (5) : première copie + marqueur, pas de recopie
    sans changement, recopie au changement, source absente,
    remplacement impossible ;
  - `BoutEnBoutTest` (1) — **§7.4** : le daemon lance le VRAI orchestrateur
    (sous-processus `java`, `ServerMain` par classpath) sur un VRAI
    socket Unix JDK, handshake au secret vérifié, pong réel du health
    check, VRAI build Gradle sur `minimal-java` (sortie ligne à ligne,
    état `REUSSI`), arrêt propre. Couverture kover ≥ 80 % (colle Android
    filtrée).

## [0.28.0] – 2026-09-24

Étape 27 (= G3 du prompt compagnon « Tooling Gradle (client-serveur) ») :
module `tooling:client` — le côté Android du dialogue. L'app est le SERVEUR
du socket Unix (l'écoute s'ouvrira avant le lancement du process, G4), le
handshake au secret valide toute connexion, et la façade publique
`GradleToolingRepository` entre dans `core:domain`. ADR 0041,
`docs/TOOLING.md`.

### Ajouté

- **Port `GradleToolingRepository` (core:domain)** (§5.3) : modèles du
  domaine sans AUCUN type du protocole ni de `tooling:api` (règle §2.2 —
  miroir de `TerminalSessionRepository`) : `LigneSortieBuild`,
  `EtatBuild`/`StatutBuild`, `ResultatSynchronisation` (partielle = succès
  Resilient Sync), `InfoTache`, `DiagnosticBuild`/`SeveriteDiagnostic`,
  `InstantaneTas`, `EtatConnexion` (4 états, les intermédiaires animés
  par G4).
- **Module `tooling:client`** (bibliothèque Android + Hilt) :
  - `GradleSocketServer` (§5.1) : écoute `gradle.sock` dans un répertoire
    privé `0700`, résidu retiré, **namespace FICHIER** —
    `LocalSocket.bind(FILESYSTEM)` + `LocalServerSocket(FileDescriptor)`
    (tout public depuis l'API 8) car le client JDK 17 de l'orchestrateur ne
    sait joindre que des chemins de fichiers ; accepte UNE connexion avec
    délai de garde (§7.5) ;
  - `HandshakeApp` (§4.4) : secret ou version invalide → `ErrorResponse`
    typée envoyée à l'orchestrateur PUIS fermeture — AUCUNE requête
    n'atteint un handler avant validation (§8) ;
  - `SessionTooling`/`SessionSocketAndroid` : couture de test §7.3 /
    session réelle (écritures sérialisées, lectures en flux froid, EOF =
    déconnexion) ;
  - `GradleApiImpl` (§5.3) : corrélation par livre de promesses
    (`CompletableDeferred` par identifiant, écho §3.2), **sorties de build
    en canaux bornés 4096 à envoi suspendant** (§5.2 — contre-pression,
    jamais `DROP_OLDEST`, tampon pré-abonnement et rejouable après
    `BuildFinished` : onglet ouvert tardivement ou rotation ne perd rien),
    états en `StateFlow` (conflation légitime), `cancel` feu-and-forget,
    sans session = échecs typés (jamais de blocage silencieux, §7.5) ;
  - câblage Hilt + agrégation dans `:app`.
- **`AppError.Tooling`/`ToolingReason` (core:model)** : miroir domaine des
  `ErrorCode` du protocole — l'UI traduit le code, jamais le texte brut ;
  traductions fr/en (accueil, diagnostics, assistant de création).

### Corrigé

- **Écho d'identifiant des réponses (corrélation §3.2)** — défaut de G2
  découvert en écrivant le client : `TasksHandler`, `DependenciesHandler`,
  `SyncHandler` et `ModelHandler` généraient un NOUVEL identifiant au lieu
  d'échoyer celui de la requête ; la promesse du client n'était jamais
  résolue (délai systématique). `id = requete.id` + assertions d'écho
  ajoutées aux 3 tests d'intégration concernés.

### Tests

- **19 tests client** sur `SessionFactice` (le « SocketClient fake » §7.3) :
  diffusion dans l'ordre + état final, **non-conflation sous forte charge
  (12 000 lignes d'un trait, aucune perdue — LE test §7.3)**, corrélation,
  erreur typée, sync partielle, annulation, tas/connexion, sans session,
  échec d'envoi, stderr distinct, remplacement de session, déconnexion
  rompt les promesses, handshake (secret/version/refus). Couverture kover
  ≥ 80 % (colle `LocalSocket` filtrée — aucune shadow Robolectric, même
  précédent que la colle Termux/JNI).

## [0.27.0] – 2026-09-24

Étape 26 (= G2 du prompt compagnon « Tooling Gradle (client-serveur) ») :
modules `tooling:api` (modèles partagés) et `tooling:server`
(l'orchestrateur JVM) — testé en isolation sur JVM de bureau contre les
fixtures réelles, livré en JAR unique exécutable embarqué dans les assets
de l'app. ADR 0040, `docs/TOOLING.md`.

### Ajouté

- **Module `tooling:api`** (Kotlin JVM pur, dépend de `tooling:protocol`
  uniquement) : modèles du projet indépendants du format câble —
  `LigneSortieBuild`, `StatutBuild`/`EtatBuild`, `InstantaneTas`,
  `EtatConnexion`, `InfoTache`, `Diagnostic`, `ModeleProjet`/`ModeleModule`
  — et mappers protocol → api, frontière unique (le client Android
  n'interprétera jamais un message brut).
- **Module `tooling:server`** (convention `codeide.tooling.server`) :
  - `ServerMain` (`--socket`/`--secret`/`--log-level`/`--heap-intervalle-ms`,
    codes de sortie 0/2/3/4), `ServerConfig`, `SocketClient` (UDS JDK 16+,
    `runInterruptible` + délai de connexion) ;
  - `Handshake` : secret + version négociée, incompatibilité = erreur
    claire `PROTOCOL_VERSION_MISMATCH`, jamais un comportement indéfini ;
  - `MessageDispatcher` : boucle de lecture + aiguillage dans une portée
    bornée (`limitedParallelism(6)`), requête indécodable = `ErrorResponse`
    typée sans couper la session, EOF propre distinguée de la corruption ;
  - `EventBusSocket` : file bornée 8192, `put()` bloquant
    (contre-pression = contrôle de flux, aucune perte), unique écrivain du
    socket ;
  - `BuildHandler` : pont `suspendCancellableCoroutine` sur
    `BuildLauncher.run(ResultHandler)`, annulation propagée vers le
    `CancellationTokenSource`, sortie stdout/stderr diffusée ligne à ligne
    (`StreamingOutputStream` : UTF-8 réassemblé, `\r` retiré), tâches en
    événements (`ProgressBridge`) ;
  - `SyncHandler` (Resilient Sync : modèles résolus un par un,
    `PartialSyncResult` si échec partiel), `TasksHandler` (arbre complet
    des sous-projets), `DependenciesHandler` (dépendances inter-projets),
    `ModelHandler` (`IdeaProject` → `ModeleProjet`, réponse `SyncResult` —
    ADR 0040), `HeapMonitor` (tick coroutine + à la demande) ;
  - timeouts de garde partout (build 30 min, sync 5 min, tâches 30 s…) ;
  - `Journal` : sortie d'erreur = canal du process séparé (tag
    `gradle-server`, exemption detekt ciblée).
- **Assemblage** : `com.gradleup.shadow` 9.6.1 → `gradle-server.jar`
  (7,6 Mo, `mergeServiceFiles`), recopié vers `app/src/main/assets/tooling/`
  et contrôlé avant tout packaging (`preBuild` de l'app — §4.7) ; dépôt
  `repo.gradle.org` ajouté (métadonnées Maven Central de la Tooling API
  périmées).
- **Tests** : 8 unitaires serveur (config, streaming, pont de progression),
  9 mappers api, **15 tests d'intégration RÉELS** (`ServeurIntegrationTest`)
  — l'orchestrateur complet sur un vrai socket Unix contre les 4 fixtures :
  handshake (secret/version/refus), builds minimal/erreur/multi-module,
  annulation d'une tâche de 60 s (~2,6 s), tâches/sync/dépendances/modèle,
  ping/pong, tas, requête inconnue, arrêt propre. Couverture kover ≥ 80 %
  sur le module.

### Corrigé

- `FixturesGradle` : les ressources d'une dépendance de test vivent dans
  un jar (URI `jar:`) — montage explicite du système de fichiers zip,
  sinon `FileSystemNotFoundException` (attrapé par les tests d'intégration).
- Plantage 3d8ede67 (v0.25.0 sur appareil) : la destination initiale de la
  barre du tiroir était affectée APRÈS l'écouteur — distribution synchrone
  de `rendre()` pendant `onCreate`, avant l'inflation du menu de la
  toolbar. Ordre inversé + deux tests de régression
  (`ActivityEditorLayoutTest`).

### Changé

- Prompt compagnon Vérification-1 appliqué : vérification graduée sans
  `clean` par défaut (fiabilité incrémentale prouvée empiriquement —
  violation de dépendance attrapée par `checkModuleDependencies` en 6 s
  sans clean), `scripts/verify-archive.sh` en `GRADLE_USER_HOME` isolé +
  daemon actif, tags Git annotés uniquement (v0.15.0 à v0.26.0 convertis
  rétroactivement), durées réelles des commandes dans le rapport de fin
  d'étape.

## [0.26.0] – 2026-09-24

Étape 25 (= G1 du prompt compagnon « Tooling Gradle (client-serveur) ») :
modules `tooling:protocol` et `tooling:testing` — le protocole JSON de
l'orchestrateur Gradle, avec ses tests de sortie de phase au vert avant
toute ligne de server/client (exigence §3). ADR 0039, `docs/TOOLING.md`.

### Ajouté

- **Module `tooling:protocol`** (Kotlin JVM pur, seule dépendance
  `kotlinx-serialization-json`, aucune dépendance interne) :
  - **Framing `FrameCodec`** : 4 octets gros-boutiste + payload ;
    rejet des frames annoncées au-delà de 16 Mo **avant allocation**
    (garde DoS), troncatures typées (en-tête et payload, avec diagnostic
    précis), longueur invalide rejetée, **EOF propre distinguée** de la
    troncature (déconnexion ≠ corruption pour le futur dispatcher) ;
  - **Catalogue des 24 messages** : 9 requêtes et 15 événements
    (`@SerialName` + discriminant `type`), `ErrorResponse` à `ErrorCode`
    typé (9 codes machine-lisibles — jamais une chaîne libre), enums
    `StreamKind`/`DiagnosticSeverity` ;
  - **`ProtocolJson`** : `ignoreUnknownKeys` (compatibilité ascendante
    éprouvée — un champ du futur ne casse pas le décodage),
    `encodeDefaults` pour un format câble stable ;
  - **24 fichiers dorés** commis (`golden/*.json`) : le format câble
    est **figé** — renommer, retirer ou changer le type d'un champ fait
    échouer le build (double test : décodage depuis le doré + adéquation
    sémantique de l'encodage).
- **Module `tooling:testing`** (dépendance de test uniquement — règle
  vérifiée par `checkModuleDependencies`) : quatre mini-projets Gradle
  **réels** en ressources (Java minimal sans réseau, erreur de
  compilation, multi-module avec dépendance inter-projets, tâche longue
  bornée pilotable par `-PdureeMs` pour l'annulation) et
  `FixturesGradle` qui les **copie** en répertoire temporaire — jamais
  construits en place (un build corromprait la ressource).
- **Règles de dépendance du tooling gelées** dans `ModuleRulesPlugin`
  (protocol sans dépendance interne, api/server/client/daemon en
  cascade, testing en configuration de test uniquement) ; modules
  protocol/api/server déclarés Kotlin JVM purs.
- **`docs/TOOLING.md`** : versions **vérifiées** (Tooling API 9.7.1 sur
  repo.gradle.org — Maven Central périmé sur cette coordonnée ; daemon
  Java 17 min, bootstrap openjdk-17 compatible ; fat jar
  `com.gradleup.shadow` 9.6.1), plan G1-G6, décision d'ordre (tooling
  lancé après T6 à la demande de l'utilisateur, points restants de T7
  absorbés par l'audit G6).
- **Tests** : 21 au total (8 round-trip/compatibilité, 10 framing dont
  borne exacte 16 Mo et rejet avant allocation, 3 fixtures) —
  critère bloquant §3 satisfait.

## [0.25.0] – 2026-09-24

Étape 24 (= T6 du prompt compagnon « Terminal intégré et bootstrap
natif ») : intégration du terminal à l'accueil et au tiroir de l'espace
de travail (sections 7 et 8). Corrigé : plantage de l'écran
d'installation rapporté sur appareil (rapport 30e81ee0). Ajouté :
intégration continue GitHub Actions. ADR 0037, ADR 0038.

### Corrigé

- **Plantage `InstallFragment` (rapport 30e81ee0, appareil réel)** :
  l'annotation `@AndroidEntryPoint` manquait sur le fragment — la
  factory par défaut tentait la réflexion sans constructeur vide
  (`NoSuchMethodException: InstallViewModel.<init> []`) dès l'ouverture
  de « Installer les outils du terminal ». L'annotation installe la
  factory Hilt ; test de régression par réflexion (le plantage n'était
  visible ni au compile time ni dans les tests JVM du ViewModel).

### Ajouté

- **Action « Terminal » dans la toolbar de l'accueil** (section 7) :
  bouton icône dédié (téléphone et sw600dp) qui ouvre l'écran plein
  écran (`openTerminal(null)` — répertoire général, `HOME` canonique
  pour une nouvelle session) **ou** l'écran d'installation si le
  bootstrap est absent : jamais un terminal non fonctionnel. La décision
  vit dans `HomeViewModel` (`ActionAccueil.OuvrirTerminal` → effet
  typé), l'état porte `bootstrapInstalle`.
- **Carte d'aperçu du terminal dans le tiroir** (section 8) :
  quatrième destination « Terminal » de la barre de navigation basse
  (active) — carte de métadonnées (nombre de sessions actives en
  pluriels, libellé + dernière sortie monospace de la session active,
  pastille d'état vivante/terminée, bouton d'agrandissement), état vide
  (« Aucune session active » + « Nouvelle session dans ce projet » qui
  crée la session dans le dossier réel du projet puis ouvre l'écran
  dessus), garde-fou bootstrap (« Installer les outils »). Mise à jour
  **en direct** : même liste de sessions que l'écran plein écran, quel
  que soit le point d'entrée. `feature:editor` ne gagne **aucune**
  dépendance (critère d'acceptation section 11 vérifié par
  `checkModuleDependencies`).
- **Pont SAF → FUSE du domaine** : `ResoudreRepertoireProjet`
  (`core:domain`) traduit l'arborescence SAF du projet en chemin FUSE
  réel (`/storage/emulated/0/…`, volumes amovibles par UUID) —
  durcissement anti-traversée, garde « répertoire fantôme » (volume
  démonté → repli `HOME`), heuristiques pures testées en JVM. Le futur
  tooling (exécution sur l'appareil) réutilise ce cas d'usage.
- **Icône `ic_terminal`** partagée (`core:ui`, contour) : toolbar de
  l'accueil et destination du tiroir.
- **Intégration continue GitHub Actions** (`.github/workflows/ci.yml`)
  : push (main + tags `v*`), pull request et manuel — JDK 21 Temurin,
  cache Gradle, SDK du runner ; la vérification complète (spotless,
  detekt, `checkModuleDependencies`, lint, tests, kover, APK debug)
  tourne **depuis GitHub**, APK téléchargeable en artefact. ADR 0037.

### Modifié

- **Procédure de vérification locale** (ADR 0037) : le `clean`
  systématique disparaît — mesures à l'appui (detekt 1,6 s sans
  changement, 27 s après modification d'un module, 6,5 s après `clean`
  via le build cache), vérifications ciblées par module en cours
  d'étape, chaîne complète sans `clean` avant livraison, from-scratch
  garanti par la CI au push.
- Test Robolectric du menu du tiroir : quatre destinations attendues
  (Terminal active).

## [0.24.0] – 2026-09-24

Étape 23 (= T5 du prompt compagnon « Terminal intégré et bootstrap
natif ») : module `feature:terminal` — écran plein écran du terminal
(toolbar, onglets de sessions, `TerminalView` unique rebranché, clavier
étendu interne, thèmes clair/sombre, réglage de police dédié). ADR 0036.

### Ajouté

- **Module `feature:terminal`** : `TerminalActivity` selon la
  disposition de la section 5.1 — toolbar « Terminal » + nouvelle
  session, onglets défilants (pastille d'état vivante/terminée, libellé
  court, fermeture, « + » final, appui long : renommer/dupliquer/
  fermer), **un seul `TerminalView` rebranché** sur la session active
  (`attachSession`, même principe que l'éditeur), état vide centré,
  rangée de touches étendues qui remonte au-dessus du clavier virtuel.
- **Clavier étendu interne** (`ClavierEtenduView`, `termux-shared`
  refusé pour licence — ADR 0035/0036) : rangée déclarative Tab, Ctrl,
  Alt, Échap, flèches ; touches directes par séquences de contrôle,
  **Ctrl/Alt bascules persistantes** lues par le client de la vue
  (`readControlKey`/`readAltKey` — mécanisme officiel de Termux).
- **Fermeture d'onglet** : heuristique « shell au prompt » (fin de
  l'aperçu avec `$`/`#`/`%`/`>`) → confirmation si une commande semble
  en cours, fermeture directe sinon — le shell est réellement terminé,
  jamais seulement masqué.
- **Thèmes clair/sombre** suivant l'app : couleurs du rendu réécrites
  dans l'émulateur (indices 256/257/258, disposition jackpal) depuis
  les ressources `values`/`values-night`.
- **Réglage dédié de police** (`TaillePoliceTerminal` PETITE/MOYENNE/
  GRANDE) : modèle, clé DataStore, section Apparence des Paramètres,
  appliqué via `setTextSize` avec garde anti-re-création de fonte.
- **Navigation** : `AppNavigator.openTerminal(suggestedWorkingDirectory)`
  (interface `core:ui`, implémentation `app`, extra d'intent lu via
  `SavedStateHandle` — survit à la rotation) ; les points d'entrée UI
  arrivent en T6.
- **Retour système** : ferme l'écran, jamais une session (jamais mappé
  sur Échap) — les sessions survivent via le service foreground.

### Tests

- `TerminalViewModelTest` (11 tests, fakes du domaine — aucune session
  Termux réelle) : répertoire suggéré/repli `HOME` canonique,
  sélection, fermeture directe au prompt, confirmation exigée si
  occupée puis fermeture après accord, session terminée sans
  confirmation, renommage nettoyé/ignoré, duplication au même
  répertoire, taille de police exposée, heuristique prompt/sortie.

## [0.23.0] – 2026-09-24

Étape 22 (= T4 du prompt compagnon « Terminal intégré et bootstrap
natif ») : module `core:terminal-runtime` — registre global des
sessions shell réelles (Termux `terminal-emulator`), service
*foreground*, traduction vers `TerminalSessionSummary`. ADR 0035.

### Ajouté

- **Ports du domaine** (`core:domain`, `TerminalSession.kt`) :
  `TerminalSessionSummary` (métadonnées **sans type Termux** —
  consommables par `feature:editor` sans dépendance `com.termux:*`) et
  `TerminalSessionRepository` (liste observable, session active
  observable, création avec répertoire de travail et libellé
  optionnel, renommage, fermeture **réelle**).
- **Module `core:terminal-runtime`** : `RegistreSessionsTermux`
  (singleton Hilt) implémente les **deux** ports — le repository du
  domaine **et** `TerminalRuntime.sessionFor(id)` (vraie
  `TerminalSession` Termux, API réservée au rendu de `feature:terminal`,
  section 4.4 du prompt). Création via le constructeur Termux avec
  l'environnement exact de `ProcessEnvironmentProvider` complété de
  `TERM=xterm-256color`, shell de `ToolchainLocator.defaultShell()`,
  identifiant UUID stable et libellé « Session N ». Traduction
  throttlée (fenêtre 250 ms, aperçu borné à 160 caractères replatés) ;
  terminaison naturelle (`exit`) → entrée **visible morte** jusqu'à
  fermeture explicite.
- **`TerminalService`** (foreground, `START_STICKY`, type
  `specialUse` documenté) : unique responsabilité de garder les
  sessions vivantes hors écran avec une notification honnête (« N
  session(s) de terminal active(s) », canal dédié, ouverture de l'app au
  toucher) ; décision pure testée (`DecisionServiceTerminal`) :
  notification tant qu'au moins une session vit, arrêt de soi-même
  sinon. Permissions `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` déclarées dans
  le manifeste du module (fusion dans l'app, branchée via la dépendance
  `:core:terminal-runtime`).
- **Indirection de test `CoquilleSession`** : le registre ne dialogue
  jamais avec `TerminalSession` en direct — les coquilles scriptées
  évitent toute exécution réelle de pty dans la suite JVM.
- **`FakeTerminalSessionRepository`** dans `core:testing` (section 2.2
  du prompt) pour la carte d'aperçu du tiroir (T6).
- **`THIRD_PARTY_NOTICES.md`** (créé) : `terminal-emulator` v0.118.3
  Apache-2.0 (exception explicite du dépôt GPLv3) ; **`termux-shared`
  refusé** — ses exceptions MIT ne couvrent pas
  `terminal/io/extrakeys` (vérifié sur le `LICENSE.md` de v0.118.3) :
  le clavier étendu de T5 sera implémenté en interne (ADR 0035).

### Tests

- `RegistreSessionsTermuxTest` (13 tests, coquilles scriptées + temps
  virtuel) : traduction état réel → métadonnées, environnement/shell
  canoniques transmis, session active, renommage, fermeture réelle +
  rebascule, terminaison naturelle visible morte, sorties
  bornées/replatées/throttlées (rafale de 50 lignes → une publication),
  `sessionFor`, numérotation continue, redémarrage du service.
- `TerminalServiceTest` (3 tests, service **réel** sous Robolectric —
  critère d'acceptation « cycle de vie du service ») : arrêt de
  soi-même quand aucune session ne vit ; notification foreground
  **persistante** tant qu'une session vit puis arrêt après la fermeture
  de la dernière ; le démarreur réel demande bien le service foreground
  du terminal. Colle Termux/JNI exclue de la couverture (exécution
  impossible en JVM, filtre kover documenté).
- `DecisionServiceTerminalTest` (3 tests) : arrêt si aucune vivante,
  notification du nombre sinon.

## [0.22.0] – 2026-09-23

Étape 21 (= T3 du prompt compagnon « Terminal intégré et bootstrap
natif ») : écran d'installation autonome, étape « Terminal » de
l'assistant, bandeau d'invitation à l'accueil, permission `INTERNET`
(ADR 0034). Premier branchement de `core:bootstrap` dans l'application.

### Ajouté

- **Module `feature:install`** : écran d'installation déclenchable à la
  demande — `InstallViewModel` traduit l'état **partagé** du port
  `BootstrapInstaller` en état de rendu (phase, étape, progression
  bornée du téléchargement, erreur typée, état par outil) ; l'ouvrir
  pendant une installation lancée ailleurs affiche la même progression,
  le refermer ne l'interrompt jamais ; annulation explicite, erreurs
  **actionnables** (réseau, espace, archive, permission, paquets,
  asset, architecture). Dépend uniquement de `core:ui`/`core:domain`/
  `core:model` — aucune dépendance Termux.
- **Navigation** : `AppNavigator.openBootstrapInstall()` (interface
  `core:ui`, implémentation `app`) ; destination `installation` du
  graphe, actions depuis l'accueil et l'assistant ; garde anti
  double-toucher.
- **Assistant de premier lancement** : étape « Terminal » insérée entre
  « Dossier de travail » et « Apparence » (six pages) — explication du
  gain (JDK, shell complet, outils) et du poids (≈ 40 Mo + paquets) ;
  « Installer maintenant » ouvre l'écran de progression partagé, « Plus
  tard » passe sans bloquer (bandeau à l'accueil) ; au retour, la
  présence des outils est revérifiée (« déjà installé ») ; aucune
  régression : parcours, rotation et mort du processus inchangés.
- **Accueil** : bandeau « Installer les outils du terminal » quand
  l'assistant est terminé sans terminal installé — l'état combine les
  paramètres **et** l'état partagé de l'installation : la fin d'une
  installation lancée depuis l'écran fait disparaître le bandeau sans
  repasser par l'accueil.
- **Permission `INTERNET`** (ADR 0034) : unique usage réseau de
  l'application — téléchargement du bootstrap et paquets d'outils.
- **Implémentation `AssetsBootstrapSource`** dans `app` (AssetManager)
  pour le port ouvert à l'étape T2.

### Tests

- `InstallViewModelTest` (fake `core:testing`) : traduction de chaque
  état partagé, progression bornée/indéterminée (y compris serveur
  menteur sur le total), temps réel, relais des ordres.
- Onboarding : quatre tests nouveaux (insertion de l'étape, « Plus
  tard » non bloquant, effet d'ouverture, revérification de la présence
  des outils) et mise à jour des parcours (six pages) — les tests
  existants restent verts.
- Accueil : bandeau piloté par paramètres + état d'installation
  (constructeur enrichi des deux fakes du domaine).

## [0.21.0] – 2026-09-23

Étape 20 (= T2 du prompt compagnon « Terminal intégré et bootstrap
natif ») : lanceur de sous-processus non interactifs, installateur
complet du bootstrap en pipeline coroutine à état partagé, déploiement
`aapt2`. ADR 0033.

### Ajouté

- **Ports du domaine** (`core:domain`) : `NativeProcessLauncher` /
  `ManagedProcess` (lancement de sous-processus **non interactifs** dans
  l'environnement canonique de `ProcessEnvironmentProvider` — flux de
  lignes stdout/stderr, attente de sortie annulable, terminaison
  explicite ; les sessions shell interactives restent hors périmètre,
  section 1.5 du prompt) et `BootstrapInstaller` (état partagé
  `StateFlow` de l'installation, démarrage idempotent, annulation) ;
  `BootstrapAssetsSource` pour les binaires d'outils embarqués.
- **Types du modèle** (`core:model`) : `EtapeInstallation` (progression
  complète : espace disque, téléchargement octets/total, extraction,
  liens symboliques, bascule, second stage, `sources.list`, `apt`),
  `EtatInstallationBootstrap` (machine à cinq états, dont `Annulee`),
  `OutilResume` (état par paquet) et `AppError.Bootstrap` avec neuf
  raisons typées (réseau, espace disque, archive corrompue, empreinte,
  permission, second stage, `apt`, asset absent, architecture non
  supportée).
- **Installateur** (`core:bootstrap`) : vérifications préalables
  (espace disque ≥ 1 Gio, architecture `aarch64` — seul bootstrap
  publié, erreur typée sinon), téléchargement `HttpURLConnection` avec
  progression et **vérification de l'empreinte SHA-256** de la release
  `bootstrap-2026.08.14-r3`, extraction vers `usr-staging` (permissions
  `0700` sur `bin/`, `libexec`, assistants `apt` et second stage —
  chemin réel constaté dans l'archive), **liens symboliques du manifeste
  `SYMLINKS.txt`** (séparateur « ← »), garde anti-traversée sur entrées
  **et** manifeste, bascule atomique (le préfixe existant est remplacé :
  une reprise rejoue tout, y compris le second stage dont le verrou vit
  sous le préfixe), exécution du second stage via le lanceur canonique
  avec drainage parallèle des deux tuyaux, écriture atomique du
  `sources.list` **avec `[trusted=yes]`** (correction automatique de
  l'URL antérieure — la ligne embarquée par l'archive n'en dispose pas),
  `apt update` puis installation des paquets **un par un** (état
  rapporté par outil, échec global seulement si aucun n'est installé).
- **`Aapt2Deployeur`** : déploiement du binaire cross-compilé depuis les
  assets vers `$PREFIX/bin` (idempotent, bit d'exécution posé) —
  l'absence de l'asset à ce jour est une erreur typée signalée, non
  contournée.
- **Fakes de test** (`core:testing`) : `FakeToolchainLocator`,
  `FakeProcessEnvironmentProvider`, `FakeNativeProcessLauncher` (+
  `ProcessusScripte`) et `FakeBootstrapInstaller` (exigés par le prompt,
  section 2.2 — consommés par les ViewModel dès T3).
- **Traduction de la nouvelle erreur** : branche `AppError.Bootstrap`
  dans les traducteurs existants (accueil, wizard, diagnostic), chaînes
  fr/en.

### Tests

- **49 nouveaux tests** : lanceur sur **vrais** processus `/bin/sh`
  (lignes stdout/stderr, code de sortie, terminaison, répertoire de
  travail, environnement exact fournisseur + `extraEnv`) ; téléchargeur
  contre un **faux serveur HTTP** (progression totale annoncée/indéterminée,
  HTTP 404, empreinte invalide, flux tronqué, espacement des émissions) ;
  extracteur sur **vraies archives zip** construites à la volée
  (extraction, permissions sélectives, liens symboliques des deux formes
  de chemin, manifeste absent/malformé, traversée, archive non zip,
  bascule avec remplacement) ; configurateur (sources.list absent/à
  corriger/conforme, commandes `apt` exactes, codes de sortie traduits) ;
  **installateur de bout en bout sous Robolectric** (faux serveur + vraie
  archive extraite réellement + faux lanceur : succès complet, double
  démarrage, reprise après échec, échec réseau/second stage/apt,
  architecture, espace disque, **annulation pendant le téléchargement**
  avec staging nettoyé) ; déployeur `aapt2` (déploiement, remplacement,
  asset absent, nom personnalisé).

## [0.20.0] – 2026-09-23

Étape 19 (= T1 du prompt compagnon « Terminal intégré et bootstrap
natif ») — ouverture de la Phase 2 par le terminal, à la demande de
l'utilisateur : nouveau module `core:bootstrap`, ports du domaine et
heuristiques de localisation entièrement en JVM pur. ADR 0032.

### Ajouté

- **Ports du domaine** (`core:domain`) : `ToolchainLocator` (13 méthodes
  — périmètre exact du prompt Terminal-1, section 2.2) et
  `ProcessEnvironmentProvider` (source unique de l'environnement des
  sous-processus, consommée par le terminal **et** le futur tooling).
  Exception assumée et bornée à l'ADR 0003 : ces ports exposent des
  `File` du stockage privé (`filesDir`) — on ne peut pas `exec` une URI
  SAF (ADR 0032).
- **Module `core:bootstrap`** (étape T1) : disposition type Termux
  (`filesDir/usr` + `filesDir/home`), localisation du JDK (emplacement
  réel du paquet `openjdk-17` du dépôt APT : `usr/lib/jvm/…`, constaté
  sur `Contents-aarch64`), des distributions Gradle (marqueur
  `lib/gradle-launcher-*.jar` ou apparenté), du SDK Android
  (plateformes `android.jar`), de `aapt2` (bit d'exécution), du shell
  par défaut et du **cache du wrapper Gradle**
  (`~/.gradle/wrapper/dists/…`, évite un retéléchargement).
- **Environnement de sous-processus** : retrait de `CLASSPATH` et
  `LD_PRELOAD` hérités, fixation de `HOME`, `TMPDIR`, `PREFIX`,
  `LANG`, `LD_LIBRARY_PATH`, **`GRADLE_USER_HOME` explicite** (bug
  `getpwuid` rejoué par les tests), composition du `PATH`, export
  conditionnel de `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- **37 tests** en JVM pur (`core:bootstrap`) rejouant les bugs
  historiques documentés par le prompt : répertoire « Gradle » sans JAR
  de lancement, `bin/gradle` régulier confondu en symlink, distribution
  du wrapper non retrouvée, cache Gradle hors de `HOME`.
- Règle de dépendance `:core:bootstrap` → (`core:model`, `core:domain`)
  dans `checkModuleDependencies` (module inscrit dans `settings.gradle.kts`).

### Notes techniques

- `core:bootstrap` n'est pas encore référencé par `app` : aucun écran ne
  le consomme à ce stade — le branchement arrive avec l'écran
  d'installation (T3) et `core:terminal-runtime` (T4). Les tests du
  module valident les heuristiques indépendamment.
- **Signalé au dépôt `codeide-packages`** : aucun paquet `gradle` ni
  `android-sdk` dans le dépôt APT à ce jour (seuls `openjdk-17`, `git`
  et les paquets de base) ; seul `bootstrap-aarch64.zip` est publié en
  release. L'installateur (T2) en tiendra compte — le manque est
  signalé, pas contourné côté app (prompt Terminal-1, section 1.1).

## [0.19.0] – 2026-09-23

Étape 18 — Audit final de Phase 1 (prompt compagnon, section 6, ADR 0031) :
reconnaissance du **vrai type de projet** à l'ouverture, build release R8
vérifié, documentation d'API, audit de dépendances — et correctif d'un
**plantage à l'ouverture de l'espace de travail** signalé sur appareil.

### Corrigé

- **Plantage à l'ouverture de l'espace de travail** (rapport 8b5b73f1,
  v0.16.0 debug, `InflateException` ligne #376 d'`activity_editor`) : le
  menu de la barre de navigation basse du tiroir vivait en `<menu>` **inline
  dans le layout** — `LayoutInflater` cherchait la classe `android.view.menu`
  (inexistante) et l'activité plantait avant `onCreate`. Le menu vit
  désormais dans `res/menu/menu_tiroir.xml`, référencé par `app:menu`.
  Introduit à l'étape 14, indétectable à la compilation (AAPT2 ne valide pas
  les éléments d'un layout) et invisible des tests, qui n'inflataient pas le
  layout — désormais si : **test de régression Robolectric** gonflant le
  vrai `activity_editor.xml` sous le thème de l'application
  (`ActivityEditorLayoutTest`).

### Ajouté

- **Ligne « type de projet » dans l'en-tête du tiroir** (ADR 0031) :
  `.codeide/project.json` est lu à l'ouverture et le **vrai modèle**
  s'affiche — nom i18n résolu depuis le catalogue (« Modèle Kotlin · JVM »)
  avec repli sur l'identifiant brut, et version du modèle si présente. Un
  dossier importé reconnu affiche son modèle réel ; sans métadonnées :
  « Dossier importé » ; un projet créé dont le fichier a disparu :
  « Type de projet non reconnu ». Lecture **totalement tolérante**
  (absent, illisible, corrompu, schéma futur → rien, jamais de blocage) ;
  le nom suit la langue de l'application (`PreciserLangue` à chaque
  re-création, ADR 0013).
- **Dokka** sur les modules purs `explicitApi` (`core:model`,
  `core:domain`) : contrat public documenté, tâche `dokkaHtml` vérifiée
  dans la chaîne de livraison.

### Modifié

- **Audit de dépendances** : retrait des entrées du catalogue jamais
  consommées — `androidx-constraintlayout`, `kotlinx-coroutines-android`,
  `mockk`/`mockk-android`, `androidx-test-runner`, `androidx-test-junit`,
  `androidx-espresso` (aucune instrumentation ni mock n'était en usage) ;
  `robolectric`/`androidx-test-core` restent et s'ajoutent à
  `feature:editor` pour la régression de layout.
- Routage des actions de l'espace scindé (`onActionOnglets`) : le seuil
  detekt de complexité cyclomatique est respecté malgré l'action
  `PreciserLangue` (15 → 8 branches au point d'entrée).
- Décompte de paresse de l'explorateur ajusté : la racine est listée trois
  fois à l'ouverture (arborescence, reprise d'espace, reconnaissance du
  type — documenté dans les tests).

### Vérifié (audit final)

- `assembleRelease` **R8 vert** avec les règles ProGuard de la
  bibliothèque d'édition (cel-ui) — parcours de l'espace de travail
  testé sur l'APK minifié (E44).
- Aucun `TODO`/`FIXME` vivant dans le code, aucun code mort signalé par
  detekt strict (maxIssues = 0), aucune donnée personnelle dans journaux
  ni rapports (`LogRedactor` actif, tests de non-fuite).
- `scripts/verify-templates.sh` vert (18 combinaisons générées, compilées,
  testées, exécutées, publiées).
- Plan détaillé de la Phase 2 rédigé dans `docs/ROADMAP.md`.

## [0.18.0] – 2026-09-23

Étape 17 — Actions du tiroir et finitions de l'espace de travail (prompt
compagnon, sections 5.2/5.3 et 6, ADR 0030) : l'explorateur **agit sur
les fichiers**, le projet **rouvre ses onglets**, l'accessibilité est
affinée.

### Ajouté

- **Menu contextuel de l'explorateur** (appui long sur un nœud) :
  Nouveau fichier, Nouveau dossier (dans le dossier visé), Renommer,
  Supprimer (confirmation avec rappel du nom et mention « action
  définitive »), Actualiser. Un bouton dédié de l'en-tête du tiroir
  couvre la création **à la racine** (un projet vide reste utilisable) ;
  un fichier créé **s'ouvre en onglet** immédiatement.
- **Validation de nom partagée avec le wizard** : nouveau validateur
  `file-name` (mêmes règles que le nom de projet, section 12.3 —
  longueur 1-64 après trim, caractères interdits, « . »/« .. », fin
  interdite, noms réservés Windows), consommé par
  `EvaluerNomFichierUseCase` ; le dialogue montre la raison localisée
  et reste ouvert tant que le nom est invalide.
- **`FileSystem.rename`** (14ᵉ opération du port) : `SafFileSystem`
  l'implémente par `DocumentsContract.renameDocument` et **retourne la
  nouvelle URI** (SAF la change — contrat explicite) ; `FakeFileSystem`
  déplace le sous-arbre et refuse la collision insensible à la casse.
- **Renvoi des onglets à la réouverture du projet** :
  `.codeide/local/workspace-state.json` (non synchronisé — exclu par les
  `.gitignore` générés dès l'étape 9), écrit asynchrone à chaque
  changement d'onglets (échec journalisé, jamais bloquant), lecture
  tolérante (absent/illisible/corrompu → rien). Le `SavedStateHandle`
  garde la priorité (rotation, mort du processus) ; le dossier
  `.codeide/local/` est créé au besoin (projet importé sans `.codeide`).
- **L'onglet suit le renommage** : session, auto-sauvegarde en attente
  et verrou d'écriture migrés vers la nouvelle URI, nom/chemin/langage
  mis à jour ; la suppression d'un document ouvert ferme l'onglet et
  libère la session (`dispose`, ADR 0028).
- **Accessibilité des onglets** : `contentDescription` = nom + état de
  modification (TalkBack annonce l'onglet complet) ; procédure
  d'audit TalkBack complète documentée (E32-E39).

### Modifié

- Après une opération de fichier, seul le dossier parent est
  ré-énuméré — il **reste déplié** ; les sous-arbres obsolètes voient
  caches et plis oubliés (rafraîchissement ciblé, ADR 0030).
- Tests : `ActionsFichiersEditorViewModelTest` (création, renommage
  avec suivi d'onglet, suppression avec libération, échec typé,
  reprise par projet, corruption ignorée), `EspaceTravailUseCasesTest`
  (round-trip, ré-écriture sans doublon, tolérance), décompte de
  paresse de l'explorateur ajusté (la reprise liste la racine une fois
  de plus).

## [0.17.0] – 2026-09-23

Étape 16 — Panneau inférieur (prompt compagnon, section 5.5, ADR 0029) :
la zone basse de l'espace de travail devient **fonctionnelle** — trois
états d'ouverture, journal applicatif compact branché sur le moteur de
journalisation existant, stubs explicites pour le tooling futur.

### Ajouté

- **Trois états d'ouverture** pilotés par `BottomSheetBehavior`
  (`behavior_fitToContents=false`, `halfExpandedRatio=0,5`) : **replié**
  (seul l'en-tête de 48 dp est visible), **mi-hauteur** et **étendu** —
  au doigt (glissement), par l'en-tête (bascule replié ↔ mi-hauteur), par
  les boutons **agrandir** (replié → mi-hauteur → étendu) et **réduire**,
  et par le retour système (le panneau étendu se réduit avant toute autre
  action — prompt compagnon 5.1). L'état et l'onglet actif vivent dans
  `EditorUiState`/`SavedStateHandle` : ils **survivent à la rotation**.
- **Onglet Journal applicatif fonctionnel** : fenêtre mémoire des 200
  dernières entrées (`ObserveLogsUseCase` — ADR 0029 : pas de lecture
  disque, l'historique complet reste à l'écran Diagnostic), mise à jour
  **en direct** avec défilement vers la plus récente, **filtres par
  niveau** identiques à l'étape 12 (ensemble vide = tous les niveaux,
  puces Débug/Info/Avert./Erreur persistées), ligne compacte (niveau
  coloré jour/nuit, heure, étiquette, message tronqué), état vide après
  filtres et **badge de compte** dans l'en-tête. Le lien
  « Ouvrir le journal complet » navigue vers l'écran Diagnostic
  (`AppNavigator.openDiagnostics`) — exports et effacement restent là.
- **Onglets Sortie et Problèmes en stub explicite** : message clair (« La
  console apparaîtra ici une fois le tooling de compilation disponible » /
  « Les problèmes de compilation et d'analyse apparaîtront ici ») — jamais
  l'apparence d'une fonctionnalité cassée ; le point d'ancrage des
  diagnostics inline (`session.setDiagnostics`, section 2.4 du prompt
  compagnon) est documenté en commentaire, non câblé (tooling = Phase 2).

### Modifié

- Le titre de l'en-tête du panneau suit l'onglet actif (Console ·
  Problèmes · Journal) au lieu d'un libellé statique.
- Tests du ViewModel étendus (`PanneauEditorViewModelTest`) : états
  d'ouverture et onglet actif propagés et persistés, rejou de l'état
  courant sans effet, fenêtre du journal en direct, filtres par niveau,
  effet du lien « journal complet », restauration après rotation.

## [0.16.0] – 2026-09-23

Étape 15 — Intégration de l'éditeur et onglets de fichiers (prompt
compagnon, sections 2.4, 5.2 et 5.4, ADR 0028) : la zone centrale de
l'espace de travail **édite** maintenant les fichiers du projet, avec la
coloration syntaxique de cel-ui.

### Ajouté

- **Ouverture d'un fichier en onglet** (tiroir → onglet) : lecture via
  `FileSystem.readText`, création d'`EditorDocument`/`EditorSession`
  (classes pures de cel-core), `setLanguage` déduit de l'extension
  (kotlin, java, xml, json, markdown, yaml, toml, properties — repli
  **neutre** pour un langage inconnu, jamais un blocage). Une extension
  binaire connue (png, jar, zip…) ne s'ouvre pas en onglet : l'action
  « Ouvrir avec » propose le fichier au système (`ACTION_VIEW` avec
  lecture accordée), avec message clair si aucune application ne sait
  faire.
- **Onglets dynamiques** (`TabLayout` défilant) : icône du langage, nom,
  **point de modification** qui remplace le bouton de fermeture tant que
  l'onglet est sale, menu contextuel (Fermer, Fermer les autres, Fermer
  tout, Déplacer à gauche/droite, Copier le chemin dans le
  presse-papiers).
- **Un seul `EditorView`, rebranché** sur la session de l'onglet actif
  à chaque changement (jamais une vue par onglet), thème clair/sombre
  suivant le mode de l'application (`EditorTheme.light()`/`dark()`).
- **Sauvegarde automatique** (1,5 s d'inactivité après une modification)
  **et manuelle** (action de la toolbar), toujours via
  `FileSystem.writeText` hors thread principal, avec **verrou par
  fichier** — jamais deux écritures concurrentes du même fichier. Un
  échec d'écriture laisse l'onglet sale et est signalé (snackbar) :
  jamais de perte silencieuse.
- **Dialogue de fermeture avec modifications non enregistrées** :
  Enregistrer / Ne pas enregistrer / Annuler — déclenché par la
  fermeture d'un onglet sale, « Fermer les autres », « Fermer tout » et
  par le **retour système avec des onglets sales** (confirmation
  agrégée, pluriel authentique). « Fermer les autres » et « Fermer
  tout » ferment **immédiatement les onglets propres**, seuls les sales
  confirment ; l'auto-sauvegarde des onglets sous confirmation est
  **suspendue** — « Ne pas enregistrer » doit pouvoir gagner, jamais
  écrire sous la question.
- **`session.dispose()` systématique** : à la fermeture de chaque onglet
  et à la destruction de l'activité (`onCleared` libère tout) — sinon
  fuite du thread de restyle de cel-core. L'enveloppe interne
  `SessionSuivie` rend la libération **observable par test**, et
  `SessionEditionTest` éprouve de vraies sessions pures (aller-retour
  du texte, langue, repli neutre, notification d'édition, `dispose`
  idempotent) — critère d'acceptation de l'étape.
- **Mort du processus** : les chemins des onglets ouverts et l'onglet
  actif vivent dans le `SavedStateHandle` ; le ViewModel recréé rouvre
  chaque onglet en relisant le fichier (jamais sale a priori). Le
  fichier de reprise par projet (`workspace-state.json`) arrive à
  l'étape 17.

### Modifié

- L'explorateur : un appui sur un **fichier** demande désormais son
  ouverture en onglet (les dossiers déplient comme avant).
- Le retour système de l'espace de travail : ferme le tiroir s'il est
  ouvert, sinon demande la sortie — avec confirmation agrégée si des
  onglets sont sales.
- La zone centrale « Aucun fichier ouvert » n'apparaît que sans onglet
  ouvert.

## [0.15.0] – 2026-09-23

Étape 14 — Explorateur de fichiers (prompt compagnon, section 5.3,
ADR 0027) : l'arborescence paresseuse du tiroir de l'espace de travail.
Cette livraison corrige aussi **deux bugs bloquants remontés sur appareil
réel** par l'utilisateur.

### Corrigé

- **Le dossier de travail n'était plus vérifiable** (« le test d'écriture
  a échoué », tout dossier, à chaque tentative). Cause racine : les
  fournisseurs SAF complètent un nom **sans extension** par
  l'extension canonique du type MIME demandé — le fichier témoin
  `codeide-temoin-<millis>` créé en `text/plain` devenait
  `…​.txt`, et le contrôle strict du nom retourné de `SafFileSystem`
  le prenait pour un renommage de collision (`AlreadyExists`).
  Triple correction : le témoin porte désormais l'extension `.txt`
  (`testerEcriture`) ; `SafFileSystem` tolère la complétion
  d'extension canonique (le motif de collision « nom (1) » reste
  refusé, documenté et testé) ; `mimePour` du moteur de templates
  donne aux fichiers texte **sans extension** (`gradlew`, `LICENSE`)
  un type privé inconnu de la table système — sans quoi la création
  de projet sur appareil réel aurait produit `gradlew.txt`.
- **« Terminer » de l'assistant ne faisait rien** — `isSetupCompleted`
  restait faux et l'assistant revenait à chaque lancement. Cause
  racine : le bouton unique de l'hôte (dont le libellé devient
  « Terminer » sur la dernière page) émettait `PageSuivante`, action
  bornée qui ne faisait rien sur la page finale — l'action `Terminer`
  n'était jamais émise par l'UI. Correction : `PageSuivante` sur la
  page finale **finalise l'installation** ; garde anti double-appui
  pendant l'écriture ; un échec d'écriture est désormais **signalé à
  l'écran** (page Terminé) au lieu d'un silence, le bouton redevient
  actif pour réessayer.
- NB : aucune permission de stockage « classique »
  (`READ/WRITE_EXTERNAL_STORAGE`) n'est requise — l'accès aux fichiers
  passe exclusivement par les permissions persistantes SAF
  (`takePersistableUriPermission`, ADR 0003) ; les symptômes
  constatés venaient du bug d'extension ci-dessus.

### Ajouté

- **Explorateur de fichiers du tiroir** (`feature:editor`, ADR 0027) :
  arborescence **paresseuse** — un dossier n'énumère ses enfants
  (`FileSystem.list`) qu'à son premier dépliement, résultat mis en
  cache dans le `EditorViewModel` pour la vie de l'écran (refermer/
  rouvrir est instantané, testé par compteur d'appels) ; liste aplatie
  en `ListAdapter` + `DiffUtil`, chevron qui tourne au dépliement,
  indicateur de chargement par nœud (latence SAF réelle).
- **Tri de l'explorateur** : dossiers d'abord, puis fichiers, puis
  ordre alphabétique (insensible à la casse) — appliqué au moment du
  cache, jamais re-testé à l'affichage.
- **Icônes par extension** (`core:ui`, `IconesFichiers`) : ressources
  vectorielles maison en badges colorés — Kotlin, Java, Gradle, XML,
  Markdown, JSON, dossier et fichier générique (repli qui ne bloque
  jamais une ligne).
- **Bandeau d'accès du tiroir** : une permission perdue ou un dossier
  racine introuvable (`ProjectAccessState`, revérifié au bouton
  **Actualiser** de l'en-tête) remplace l'arborescence par un bandeau
  explicite avec action « Résoudre à l'accueil » — la résolution réelle
  (Relocaliser/Retirer) vit sur la carte du projet. Un dossier non
  racine défaillant (supprimé entre deux énumérations) est signalé par
  sa ligne : replié et marqué, l'appui **réessaie**.
- **Barre de navigation basse du tiroir** : trois destinations façon
  `BottomNavigationView` (rendu Material3) — **Explorateur** active,
  **Recherche** et **Git** visibles mais désactivées, avec la mention
  « Bientôt disponible » en description accessible.
- Accessibilité : lignes ≥ 48 dp, `contentDescription` complet par nœud
  (nom, type, profondeur, état de pli ou d'échec) et par destination.

### Modifié

- La zone centrale « Aucun fichier ouvert » n'annonce plus
  l'explorateur pour l'étape suivante : il est livré.
- `FakeFileSystem` (core:testing) expose un compteur d'appels `list`
  — le contrat « une seule énumération par dossier » est verrouillé
  par les tests du ViewModel.
- Les fichiers de projet générés sans extension conservent leur nom
  exact sur l'appareil (`gradlew`, `LICENSE`).

## [0.14.0] – 2026-09-23

Étape 13 — Fondations de l'espace de travail (prompt compagnon,
section 6) : `EditorActivity` et ses trois zones, sans logique d'édition
(ADR 0026). Première dépendance externe d'une fonctionnalité : la
bibliothèque d'édition `cel-ui`.

### Ajouté

- **`EditorActivity`** (`feature:editor`, ADR 0026) : trois zones —
  **tiroir gauche** (en-tête : nom du projet, chemin lisible,
  « Fermer le projet » ; **permanent verrouillé ouvert sur grand écran**
  sw600dp+, façon IDE de bureau — sous ce seuil : ouvert par ☰ ou geste de
  bord), **zone centrale** (barre d'outils au nom du projet, onglets de
  fichiers vides en attente de l'étape 15, états « Aucun fichier ouvert »
  et « Projet introuvable » si l'identifiant n'est plus au registre) et
  **panneau inférieur replié** (en-tête à poignée cliquable — replié ↔
  mi-hauteur — et trois onglets vides Console · Problèmes · Journal, en
  attente de l'étape 16). Contenu edge-to-edge (insets toolbar/tiroir).
  Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte.
- **Navigation** : `AppNavigator.openEditor(projectId)` lance l'espace
  de travail par-dessus la pile — l'accueil survit en dessous, y revenir
  ne recharge rien. Servi par « Ouvrir » sur une carte de l'accueil et
  par « Ouvrir le projet » de l'écran de succès du wizard ; dans les deux
  cas `lastOpenedAt` est marqué **avant** de naviguer (le tri des récents
  est déjà à jour au retour).
- **`EditorViewModel`** : charge le projet dont l'identifiant est arrivé
  par l'intention (transmis par `SavedStateHandle` — survit à la rotation
  et à la mort du processus) et le **suit au registre** : renommage,
  relocalisation ou suppression depuis l'accueil se répercutent sans
  rechargement ; un projet supprimé fait passer l'écran en état
  « introuvable », jamais de crash.
- **Domaine** : `ObserveProjectUseCase` (observation d'un projet par
  identifiant, pour l'espace de travail).
- **Bibliothèque d'édition** (`cel-ui` 3.37.0 via JitPack, ADR 0026) :
  dernière version stable vérifiée sur `github.com/jjoblab/code-editor` ;
  coordonnées réelles `com.github.jjoblab.code-editor:cel-ui` (cel-core et
  cel-lsp-api transitifs — `cel-lsp` volontairement exclu, Phase 2) ;
  dépôt JitPack ajouté au gestionnaire de résolution ; règles ProGuard
  de la bibliothèque ajoutées à `app/proguard-rules.pro` (vérification
  release à l'étape 18).

### Modifié

- L'accueil remplace le snackbar « l'éditeur arrive dans une prochaine
  version » par la **navigation réelle** vers l'espace de travail
  (l'effet `EditeurIndisponible` devient `OuvrirEditeur(id)`).
- Le wizard : « Ouvrir le projet » de l'écran de succès ouvre
  directement l'éditeur (nouvel effet `OuvrirProjetEditeur`) au lieu de
  revenir à l'accueil ; « Retour à l'accueil » conserve la mise en
  évidence du projet créé (ADR 0024).

## [0.13.0] – 2026-09-23

Étape 12 — Diagnostic : la visionneuse des journaux applicatifs et des
rapports de plantage (ADR 0025), accessible depuis
Paramètres › Avancé › Diagnostic. Le menu debug quitte l'accueil pour
y vivre.

### Ajouté

- **Écran Diagnostic** (`feature:diagnostics`, ADR 0025) : barre d'outils
  avec retour, section **Informations** (version, appareil non
  identifiant — fabricant, modèle, Android/API —, identifiant de session
  de journalisation), deux onglets (`TabLayout` + `ViewPager2`).
- **Onglet Journaux** : visionneuse performante — fenêtre initiale des
  **500 dernières entrées**, entrées plus anciennes **révélées au
  défilement** (pagination en mémoire, ADR 0025 : l'historique est lu
  une fois, le tampon mémoire fusionne les nouveautés sans doublon) ;
  **filtres par niveau** (chips Débogage/Info/Avertissement/Erreur,
  combinables) ; **recherche avec délai de 250 ms** insensible à la casse
  **et aux accents** (message, étiquette, classe d'exception — même
  règle que l'accueil) ; **suivi en direct** (bascule : les nouvelles
  entrées font défiler la liste) ; détail d'une entrée en dialogue avec
  **exception repliable** ; **taille occupée sur disque** (octets lisibles
  + pluriel authentique du nombre de fichiers) ; actions **Partager**
  (archive zip via la feuille de partage système), **Enregistrer**
  (sélecteur SAF, archive écrite directement à la destination) et
  **Effacer** (confirmation explicite) ; **réglage du niveau de
  journalisation** Normal / Détaillé — persisté dans les Paramètres
  **puis** appliqué immédiatement au moteur (port `LogVerbosityApplier`,
  en cas d'échec d'écriture le moteur reste intact).
- **Onglet Plantages** : historique vivant des rapports conservés (date,
  type, classe d'exception, résumé expurgé, pastille « Non consulté ») —
  l'appui ouvre l'écran dédié en consultation via `AppNavigator` ; actions
  **supprimer** par rapport, **tout supprimer** (confirmations explicites)
  et **Exporter** (archive zip de tous les rapports, JSON complet + mise
  en forme + index, partagée par la feuille système).
- **Domaine** : `ReadAllLogsUseCase` (lecture complète), 
  `MeasureLogDiskUsageUseCase` (occupation disque), `SetLogVerbosityUseCase`
  (persistance + application), `ExportCrashReportsUseCase` + port
  `CrashReportsExportWriter` (implémentation `core:crash`), extension des
  writers d'export pour l'**écriture directe à une destination SAF**
  (journaux comme plantages, sans fichier intermédiaire) ;
  `AppLogger.sessionId` exposé par l'interface.
- **Navigation** : `AppNavigator.openDiagnostics()` (entrée
  Paramètres › Avancé) et `AppNavigator.partagerArchive(nomFichier,
  emplacementInterne)` — l'implémentation applicative confine le partage au
  répertoire d'export du cache (seul exposé par le FileProvider) et
  ouvre la feuille de partage.
- **Fourniture `DeviceInfo`** par `app` (mêmes champs non identifiants
  que les rapports de plantage, photographiés à l'ouverture de l'écran).

### Modifié

- **Menu debug déplacé** de `MainActivity` (bouton flottant, étape 3)
  vers l'écran Diagnostic — bouton dans la section Informations, source
  set `debug` de `feature:diagnostics` (no-op de même signature en
  release) : plantage de test, exception non fatale, salve de journaux et
  essais SAF S1-S5 déménagent avec lui, plus aucune trace des outils de
  développement sur l'accueil.
- `LogLevelApplier` (`core:logging`) devient `internal` et **sous-type**
  du port `LogVerbosityApplier` du domaine ; `CodeIdeApplication` injecte
  le port — l'application ne touche plus la façade du moteur.
- `app` dépend de `feature:diagnostics` (destination du graphe de
  navigation).

### Sécurité

- Le partage d'archive exige que le fichier vive dans `cache/exports/`
  (contrôle du chemin canonique dans `AppNavigatorImpl`) : aucune autre
  zone du stockage interne n'est exposée, même par erreur.
- Aucune donnée personnelle affichée ni exportée : les entrées et rapports
  sont expurgés à la construction (règle 15), l'appareil est décrit par
  des champs non identifiants, la session reste un UUID opaque.

## [0.12.0] – 2026-09-23

Étape 11 — Wizard de création, partie 2 : les étapes 4 et 5, l'écran de
création complet et la mise en évidence à l'accueil (ADR 0023-0024). Les
correctifs d'insets répondent aux retours d'exécution sur appareil.

### Ajouté

- **Étape 4 Fichiers** (`feature:newproject`, section 12.3) : interrupteurs
  des fichiers optionnels (README.md, .gitignore, .editorconfig) chacun
  avec sa courte explication ; **licence** pré-remplie depuis les
  Paramètres (liste déroulante : Aucune, MIT, Apache-2.0, GPL-3.0,
  BSD-3-Clause) avec l'**auteur et l'année des Paramètres** affichés
  (consommés par MIT et BSD) ; **langue du contenu généré** par boutons
  segmentés Français / English (README, commentaires, messages des
  fichiers) ; toute combinaison est valide — l'étape ne bloque jamais.
- **Étape 5 Récapitulatif** : résumé lisible par section (modèle,
  configuration, informations, fichiers) avec un bouton **« Modifier »**
  par section qui ramène à l'étape concernée (retour arrière direct,
  jamais de raccourci vers l'avant) ; **aperçu de l'arborescence prévue**
  issu du dry-run du plan (`PlanProjectCreationUseCase` — ce qui est
  planifié est ce qui sera écrit, à l'octet près) : dossiers **repliables**
  au toucher (décrits pour TalkBack), comptage des fichiers, état
  chargement et erreur avec Réessayer.
- **Écran de création** (hors numérotation, ADR 0023) : **liste de
  progression en temps réel** (« Préparation… », « Création du dossier… »,
  « Génération des fichiers (3/8) : … », « Enregistrement… ») alimentée par
  le flot froid de `CreateProjectUseCase`, bouton **Annuler** (le domaine
  roule le rollback en `NonCancellable`, puis retour au récapitulatif — le
  retour système fait de même) ; **succès** : message, boutons « Ouvrir le
  projet » (marque `lastOpenedAt` — l'éditeur arrive à l'étape 13),
  « Retour à l'accueil » et « Créer un autre projet » (remise à zéro
  conservant le modèle, permission éphémère relâchée) ; **échec** : message
  compréhensible par erreur typée (collision, dossier injoignable,
  écriture, validation, modèle, inattendu), **Réessayer**, **Copier les
  détails** (expurgés — erreurs typées), information sur le nettoyage
  effectué (complet ou résidus).
- **Bouton « Créer le projet »** : le bouton principal du wizard devient
  « Créer le projet » sur le récapitulatif, gardé par la **revalidation
  globale** (le domaine revalide de toute façon à l'exécution, section 12.4
  point 1) ; l'indicateur affiche désormais « Étape N sur 5 ».
- **Mise en évidence du projet créé à l'accueil** (ADR 0024) : le wizard
  refermé, l'accueil **défile jusqu'au nouveau projet** et marque sa carte
  d'un **contour de la couleur primaire du thème** (suit le mode sombre) ;
  l'identifiant transite par le `SavedStateHandle` de l'entrée d'accueil
  de la pile de retour — survit à la recréation, consommé une seule fois.
- `core:ui` : extension `applySystemBarsAndImeInsets` (barres système et
  clavier en un seul écouteur) pour les écrans à barre d'actions
  inférieure qui ne doit jamais passer sous le clavier.

### Corrigé

- **Insets edge-to-edge manquants** (retour d'exécution sur appareil) :
  l'assistant de premier lancement, l'écran Paramètres et l'écran de
  plantage (processus `:crash`) laissaient leur contenu passer sous la
  barre d'état et la barre de navigation — conséquence bloquante : la
  barre d'actions de l'assistant (Commencer/Suivant) était **sous la
  barre de navigation, impossible d'avancer depuis la page de
  bienvenue**. La racine de chaque écran absorbe désormais barres
  système et clavier ; `MainActivity` déclare
  `android:windowSoftInputMode="adjustResize"` (insets IME sur
  API < 30).
- **Bouton du menu debug obstructif** (builds debug seulement) : le
  bouton flottant, ancré en bas à droite, recouvrait le bouton d'action
  principal des écrans (Commencer, Suivant, Créer…) et passait sous la
  barre de navigation. Il devient une **icône seule semi-transparente**
  (glyphe bug, style bouton icône Material) ancrée **en bas à gauche**,
  au-dessus de la barre de navigation via les insets — plus aucun
  recouvrement ; il sera déplacé dans l'écran Diagnostic à l'étape 12.

### Références

- ADR 0023 — L'écran de création vit dans le wizard, piloté par l'état.
- ADR 0024 — Mise en évidence du projet créé via la pile de retour.

## [0.11.0] – 2026-09-23

Étape 10 — Wizard de création, partie 1 : le cadre complet et les étapes
1 à 3 de la section 12 du prompt maître (ADR 0020-0022).

### Ajouté

- **Cadre du wizard** (`feature:newproject`, ADR 0020) : hôte
  `NewProjectFragment` — barre d'outils (✕ avec dialogue « Abandonner la
  création ? » quand des données sont saisies), **indicateur d'étapes**
  (`LinearProgressIndicator` + libellé « Étape N sur M · Titre » annoncé
  TalkBack), conteneur de fragments d'étapes, **barre d'actions fixe**
  (Retour masqué sur la première étape ; Suivant **désactivé tant que
  l'étape est invalide**, masqué sur la dernière livrée) ; transitions
  `MaterialSharedAxis` axe X, coupées quand le réglage « réduire les
  animations » est actif ; retour système = étape précédente puis
  abandon confirmé ; contenu borné et centré sur tablette
  (`layout-sw600dp`, sans poids imbriqués).
- **Machine à états** (`WizardViewModel`, scopé à l'hôte — ADR 0020) :
  étapes déclarées dans une **liste configurable** (`WizardStep`) — pas
  de `when` dispersés, l'étape 11 insérera Fichiers et Récapitulatif
  sans toucher au cadre ; état unique `EtatWizard` survivant **rotation
  et mort du processus** (`SavedStateHandle` : étape, modèle, nom,
  description, valeurs saisies, champs figés, emplacement éphémère) ;
  réévaluation du formulaire **à chaque changement** via
  `EvaluateTemplateFormUseCase` (visibilité, valeurs dérivées, validité).
- **Étape 1 Modèle** : grille de cartes sélectionnables (monogramme
  maison résolu depuis l'i18n, nom, description, tags), sélection unique
  présélectionnée au retour arrière ; barre de recherche masquée tant
  que le catalogue ne dépasse pas 4 modèles — interface prête pour la
  croissance, état « aucun résultat » prévu ; erreurs de catalogue avec
  Réessayer.
- **Étape 2 Configuration** : rendu **dynamique** des paramètres depuis
  le moteur (ADR 0021) — deux grandes **tuiles segmentées** avec icône
  et sous-titre (type de projet), **cartes radio** avec explication et
  aide dynamique « Sans système de build… » (système de build), **liste
  déroulante** (JDK), **interrupteurs** (tests JUnit 5, wrapper)
  apparaissant/disparaissant selon `visibleWhen` avec animation ;
  **rangée de puces récapitulatives** (« Application · Gradle · JDK 21 »)
  mise à jour en direct.
- **Étape 3 Informations et emplacement** : nom du projet (raisons
  **typées** → ressources localisées, ADR 0021), description avec
  compteur, champs dérivés (package, `groupId`, `artifactId`, `version`
  selon la visibilité) qui **suivent leurs sources tant qu'ils ne sont
  pas modifiés à la main**, avec icône de **resynchronisation** ;
  **carte d'emplacement** (ADR 0022) — dossier de travail des Paramètres
  par défaut, bouton « Changer de dossier » **pour cette création
  uniquement** (même validation que l'onboarding : dossiers refusés
  Android 11+ avant toute permission, test d'écriture témoin ;
  héritage de la permission du dossier de travail pour un choix dans
  son arbre — ADR 0015), aperçu lisible `…/<Nom>`, et **vérifications
  asynchrones avec délai 400 ms** : permission valide, dossier
  joignable, `<NomDuProjet>` n'existe pas déjà (insensible à la casse)
  — indicateur de vérification en cours, erreurs inline actionnables.
- **Domaine** : `EvaluerNomProjetUseCase` (raison typée du nom),
  `ResolveCreationLocationUseCase`, `ReleaseCreationLocationUseCase`
  (permission propre de l'override relâchée à l'abandon si elle ne sert
  plus — ni dossier de travail, ni arbre d'un projet) et
  `VerifyCreationTargetUseCase` (ADR 0022) ; `RaisonValidation` (type
  fermé dans `core:model`) porté par `TemplateParameterEvaluation` avec
  les métadonnées de rendu (`type`, `choices`, `derived`, `section`).
- **Composants UI** (`core:ui`) : `SimpleTextWatcher` (écouteur de saisie
  sans boilerplate) et style `Widget.CodeIDE.TextField.Dropdown`.

### Modifié

- `TemplateEngine.resumer` résout désormais `iconKey` via le
  dictionnaire i18n du modèle (monogramme maison, ex. « kt », « jv ») —
  l'interface n'a jamais à connaître les dictionnaires ; le champ
  `iconKey` de `TemplateSummary` porte le monogramme résolu.
- `TemplateValidators` retourne un échec **structuré** (raison typée +
  message français) ; les messages restent destinés aux journaux,
  l'interface affiche les ressources (section 11 du prompt maître) —
  comportement des messages inchangé pour `valider` (journaux).

## [0.10.0] – 2026-09-22

Étape 9 — Modèles de projet Kotlin et Java : les deux modèles embarqués
`kotlin-jvm` et `java`, complets et propres, avec leur **validation réelle**
(build, test, exécution, publication des projets générés) — section 11.

### Ajouté

- **Modèle `kotlin-jvm`** (`app/src/main/assets/templates/kotlin-jvm/`,
  manifeste déclaratif + i18n fr/en + 16 fichiers `files/`) : application
  console ou bibliothèque, build `gradle-kts`/`maven`/`none`, JDK **17 ou 21**
  (seules les LTS entièrement validées sont proposées — Kotlin 2.2.21 ne
  supporte pas encore la cible JVM 25, ADR 0019), tests JUnit 5, Gradle
  Wrapper avec `distributionSha256Sum`, package/group/artifact/version
  dérivés et modifiables. L'exemple généré (`Greeter` + `Main` +
  `GreeterTest`) compile, passe ses tests et s'exécute **sans aucune
  modification, avec zéro avertissement** (Kotlin `-Werror`, Maven
  équivalent) ; bibliothèque avec `explicitApi()`, jar de sources et
  `publishToMavenLocal` fonctionnel ; Maven via `kotlin-maven-plugin` avec
  `exec:java` et jar exécutable ; README dynamique (prérequis, commandes
  exactes selon les choix, structure, licence) ; `.gitignore`/`.gitattributes`/
  `.editorconfig` adaptés ; licences SPDX dans `pom.xml` et la publication.
- **Modèle `java`** (même logique de paramètres, sources/Javadoc Java) :
  `-Xlint:all -Werror`, bibliothèque avec jars de sources **et** Javadoc
  (`failOnWarnings`), `maven-publish` avec coordonnées explicites.
- **`scripts/verify-templates.sh`** : validation réelle des modèles — 18
  combinaisons couvrantes (tous les triplets langage × type × build, chaque
  JDK, avec/sans tests, avec/sans wrapper, les deux langues, 5 licences, noms
  et descriptions hostiles) générées sur disque puis **compilées, testées,
  exécutées et publiées** avec les vrais outils (wrapper Gradle, Maven,
  `javac`, projet Gradle jetable pour `none`+Kotlin) ; vérifications
  structurelles (aucun `{{` résiduel, pas de BOM, LF/CRLF, checksum du
  `gradle-wrapper.jar`) ; tableau final `combinaison → résultat`, exigé vert
  à toute livraison touchant aux modèles.
- **Harnais `:tools:generateur`** (ADR 0019) : module JVM dédié qui produit
  les projets sur disque depuis les **vrais** assets du dépôt en réutilisant
  le moteur de `core:domain` — le plan figé de `PlanProjectCreationUseCase`
  déversé tel quel (ADR 0017) ; port d'assets sur fichiers avec les mêmes
  garanties anti-traversée que l'implémentation Android ; protocole de
  sortie lisible par le script ; exemption detekt ciblée pour l'impression
  console (la sortie standard est le résultat de l'outil, pas une
  journalisation).
- **Tests exhaustifs de génération** (`app`, `ModelesEmbarquesTest`) :
  192 combinaisons structurelles (langage × type × build × JDK × tests ×
  wrapper × langue) avec **listes de fichiers attendues**, options communes
  (5 licences, interrupteurs README/.gitignore/.editorconfig),
  déterminisme à l'octet près, entrées hostiles (guillemets, `\`, `$`,
  `</project>`, emojis — échappements vérifiés dans le code généré),
  `.codeide/project.json` exact sans donnée personnelle, et création
  complète sur `FakeFileSystem` (écrit = plan, registre en dernier).
- **Documentation** : `docs/TEMPLATES.md` enrichi (modèles embarqués,
  tableau des versions figées **vérifiées sur les dépôts officiels** le
  2026-09-22 — Gradle 9.7.1 sommé, Kotlin 2.2.21, JUnit 5.14.4, plugins
  Maven, foojay 1.0.0 —, procédure de mise à jour puis revalidation,
  procédure d'ajout d'un modèle) ; ADR 0019 (choix du harnais JVM dédié,
  JDK 25 testé et écarté) ; `ARCHITECTURE.md`, `ROADMAP.md`, `README.md`,
  `AGENTS.md`, README/Module.md des modules touchés.

## [0.9.0] – 2026-09-22

Étape 8 — Moteur de templates : logique pure + assets, sans UI et sans
les vrais modèles Kotlin/Java (étape 9) — section 11 du prompt maître.

### Ajouté

- **Modèle déclaratif** (`core:model`) : `ProjectTemplate` /
  `TemplateParameter` (types `TEXT`/`BOOLEAN`/`CHOICE`, sections
  `CONFIGURATION`/`INFORMATION`, validateurs nommés, dérivations
  `defaultFrom`, visibilité `visibleWhen`, `persist`),
  `TemplateOptions` (options communes du moteur : README, `.gitignore`,
  `.editorconfig`, licence SPDX, langue du contenu) et
  `TemplatePlan`/`PlannedFile`/`PlannedContent` — le **plan figé** du
  dry-run, égalité par valeur y compris les octets binaires.
- **Manifestes déclaratifs** (`core:domain`, `TemplateManifestParser`) :
  `assets/templates/<id>/template.json` analysé par kotlinx.serialization,
  **validé complètement** au chargement (schéma, `id` = répertoire,
  SemVer, clés i18n, validateurs et fonctions dérivées **enregistrées**,
  expressions analysables, chemins sûrs, bornes 32/32/256/12). Un
  manifeste présent mais invalide échoue explicitement — jamais de
  catalogue amputé en silence ; un répertoire sans manifeste est ignoré.
- **Mini-langage d'expressions** (`ExpressionParser` — parseur écrit à
  la main, ADR 0018) : identifiants, littéraux chaîne/booléen, `==`,
  `!=`, `&&`, `||`, `!`, parenthèses ; bornes vérifiées avant la moindre
  récursion (512 caractères, 128 jetons, 16 de profondeur), curseur sûr
  en fin de flux, typage strict à l'évaluation, **aucune évaluation de
  code** fourni par le manifeste. Même grammaire pour `visibleWhen`,
  `when`, `computed` et `{{#if}}`.
- **Moteur de substitution** (`TemplateRenderer`) : `{{variable|filtre}}`,
  `{{#if expr}}…{{#else}}…{{/if}}` (imbrication ≤ 16), `{{t:clé}}` (i18n
  du modèle, repli anglais), échappement `\{{` ; **échec explicite fichier
  + ligne** (variable inconnue, filtre inconnu, clé i18n manquante, balise
  mal formée) — jamais de `{{…}}` résiduel ; fins de ligne normalisées
  (LF, CRLF pour `.bat`).
- **Filtres d'échappement** (`TemplateFilters` — la saisie de
  l'utilisateur ne casse **jamais** le code généré) : `kotlinString`,
  `javaString`, `xml`, `json`, `tomlString`, `md` éprouvés avec des
  entrées hostiles (guillemets, `\`, `$`, `</project>`, retours à la
  ligne, emojis, Unicode) ; transformations `slug` (accents repliés par
  NFD), `lower`, `upper`, `packagePath`.
- **Sécurité des chemins** (`TemplatePathGuard`, après substitution) :
  rejet de `..`, des chemins absolus (y compris lettres de lecteur), des
  antislashs, segments vides/`.`/contrôle/espaces de bord/point final,
  noms réservés Windows (`CON`, `COM1`…), longueurs bornées, doublons
  insensible à la casse (FAT/NTFS) ; **jamais d'écrasement** d'un
  dossier racine existant.
- **Plan figé — le dry-run est l'écriture** (ADR 0017) :
  `TemplateEngine.planifier` produit le plan complet (chemins substitués,
  textes rendus, binaires lus, licence, métadonnées) ;
  `PlanProjectCreationUseCase` (récapitulatif) et `CreateProjectUseCase`
  (écriture) partagent le même `TemplateProjectPlanner` — ce qui est
  planifié est ce qui est écrit, à l'octet près ; déterminisme garanti
  (horloge injectée).
- **`CreateProjectUseCase`** : revalidation systématique côté domaine,
  progression temps réel (`CreationProgress` : préparation, dossier
  racine, fichier par fichier, enregistrement, terminal typé), registre
  écrit **en dernier**, **rollback complet en `NonCancellable`** (échec
  d'écriture, d'insertion en base, ou annulation de la collecte —
  l'annulation est ensuite relayée), résidus impossibles à supprimer
  signalés, erreurs typées relayées sans effacement de contexte.
- **Métadonnées du projet** : chaque projet généré contient
  `.codeide/project.json` (`schemaVersion`, `templateId`,
  `templateVersion`, `generator` = `CodeIDE <version>`, paramètres
  `persist` visibles) — **aucune donnée personnelle** (ni auteur, ni
  chemin local).
- **API domaine** : `ListTemplatesUseCase` (catalogue agrégé, trié,
  libellés résolus, doublons inter-fournisseurs refusés),
  `ValidateProjectNameUseCase`, `ValidatePackageNameUseCase`,
  `EvaluateTemplateFormUseCase` (visibilité, valeurs dérivées suivant
  leurs sources, validité de tous les paramètres, libellés),
  `PlanProjectCreationUseCase` (dry-run).
- **Extension par multibinding** : `ProjectTemplateProvider` en `@IntoSet`
  Hilt ; `EmbeddedTemplatesProvider` lit `assets/templates/` via le port
  `TemplateAssetsSource` — les futurs plugins ajouteront des modèles sans
  toucher au moteur, **aucun `when(templateId)` en dur**.
- `app` : `AssetTemplateAssetsSource` (AssetManager + dispatcher d'E/S,
  aucune traversée de chemin), `GeneratorVersionImpl`
  (`BuildConfig.VERSION_NAME`), `di/TemplatesModule` (`@Binds` + `@IntoSet`),
  `assets/licenses/` — textes **officiels SPDX** (`mit.txt`,
  `bsd-3-clause.txt` substituent `{{year}}`/`{{author}}`,
  `apache-2.0.txt`, `gpl-3.0.txt`).
- `core:testing` : `FakeTemplateAssetsSource` (semis de modèles et de
  licences, robinets d'échec, garde anti-traversée identique à
  l'implémentation Android).
- **Tests** : 635 tests verts au total (+224 depuis v0.8.0) — parseur d'expressions
  exhaustif (grammaire, précédences, erreurs positionnées, bornes
  exactes des deux côtés), filtres avec entrées hostiles, rendu
  (conditionnels, i18n, échappement), sécurité des chemins, manifestes
  (chaque règle de validation), fournisseur, moteur (formulaire dynamique,
  plan, options, licence, métadonnées sans fuite, déterminisme, hostile),
  use cases (catalogue, doublons, NotFound) et création (progression,
  **plan = disque byte à byte**, rollback sur échec/annulation/base,
  résidus, jamais d'écrasement) sur un **fixture de test** dédié ;
  intégration Hilt du câblage `app` (licences réelles, traversées
  refusées, répertoire `templates/` vide légitime).
- **Documentation** : `docs/TEMPLATES.md` (contrat des concepteurs de
  modèles — format complet, expressions, filtres, garde), section
  « Moteur de templates » d'ARCHITECTURE.md, ADR 0017 (plan figé,
  dry-run = écriture) et ADR 0018 (mini-langage à parseur maison borné),
  READMEs/Module.md de `core:model`, `core:domain`, `core:testing`,
  `app`.

### Corrigé

- `ExpressionParser` : une expression tronquée (ex. `(a`) levait
  `IndexOutOfBoundsException` au lieu d'une `ExpressionException`
  positionnée — le curseur de jetons tient désormais la fin pour acquise
  au-delà du dernier jeton.
- `ExpressionParser` : le comptage de profondeur doublait chaque
  parenthèse (la 8e imbriquée échouait à tort) — chaque parenthèse ou
  négation consomme exactement un cran (15 imbriquées passent, la 16e
  échoue, tests des deux côtés de la borne).
- `TemplateFilters.slug` : les marques combinantes issues de la
  décomposition NFD (accents) étaient traitées comme des séparateurs
  (« Éclair » → « e-clair ») — elles sont désormais éliminées
  silencieusement (« Éclair » → « eclair »).
- `CreateProjectUseCase` : l'erreur typée de planification
  (`Validation`, `NotFound`…) était écrasée par un générique
  `Template("planification impossible")` — elle est désormais relayée
  telle quelle jusqu'à l'événement terminal.

## [0.8.0] – 2026-09-22

Étape 7 — Accueil : liste des projets (section 11 du prompt maître).

### Ajouté

- `feature:home` : la **liste des projets** (`ListAdapter` + `DiffUtil`)
  — nom, description courte, emplacement lisible, date d'ouverture
  **relative** (jamais ouverts : date de création), pastille du type
  de projet, épingle.
- **Tri et recherche** : Récents / Nom (les épingles flottent toujours
  en tête), recherche avec **délai de fusion des frappes (250 ms)**,
  insensible à la casse et aux accents (nom, description et
  emplacement parcourus) ; recherche et tri survivent à la rotation et
  à la mort du processus (`SavedStateHandle`).
- **États soignés** : chargement, **vide** (illustration + bouton
  « Nouveau projet »), **sans résultat** (bouton « Effacer la
  recherche »), **erreur** de lecture du registre avec « Réessayer »,
  bandeau « dossier de travail non configuré » (étape 5).
- **Statut d'accès** (section 5.6) : un projet `Introuvable` ou
  `Permission perdue` est signalé par un badge, avec actions de
  résolution (« Relocaliser » / « Retirer ») dans le menu — jamais un
  crash ; états recalculés à chaque affichage, au **tirer-relâcher**
  et après les actions qui déplacent un dossier, jamais persistés.
- **Actions par projet** (menu contextuel) : ouvrir (marquage
  « ouvert », placeholder éditeur jusqu'à l'étape 13), renommer
  (dialogue validé, libellé en base uniquement — ADR 0012),
  épingler/désépingler, **retirer de la liste** (dossier intact),
  **supprimer du disque** avec confirmation rappelant le nom.
- **Actions flottantes** : bouton étendu « Nouveau projet » (vers le
  placeholder du wizard) et « Ouvrir un dossier existant » (sélecteur
  SAF, ajoute le projet au registre).
- **Adaptatif** : 1 colonne téléphone, 2 colonnes tablette/paysage
  (`layout-sw600dp`).
- `feature:newproject` : destination **placeholder** du wizard
  (« Nouveau projet » y mène depuis l'accueil et l'état vide ;
  l'assistant complet arrive à l'étape 10).
- `core:domain` : `ImportExistingFolderUseCase` (« ouvrir un dossier
  existant » : refus plateforme avant permission, test d'écriture
  témoin, **héritage de la permission du dossier de travail** pour un
  choix dans son arbre — l'URI de document est réadressée dans cet
  arbre, sentinelle `TemplateId.IMPORTED`, ADR 0015),
  `RelocalizeProjectUseCase` (résolution d'un accès rompu en
  re-sélectionnant le dossier, même validation), et
  `DeleteProjectOnDiskUseCase` (disque d'abord, registre ensuite,
  ADR 0016).
- `core:domain` : port étendu `ArborescencesSaf.uriDocumentDansArbre`
  (réadressage d'un document dans l'arbre d'une permission tenue) —
  implémenté par `SafArborescences`, faux déterministe dans
  `core:testing`.
- `ProjectRepository.updateLocation` : relocalisation d'un projet
  (Room — `UPDATE` ciblé, index unique défendu, traduit `NotFound` /
  `AlreadyExists`).
- `core:ui` : styles `Widget.CodeIDE.Button.Outlined.Compact`,
  `Widget.CodeIDE.Button.Icon` et `Widget.CodeIDE.TextField`.

### Modifié

- `RemoveProjectUseCase` (étape 4) : le retrait applique désormais la
  règle « ne persister que le nécessaire » — la permission de l'arbre
  est **libérée uniquement si** le dossier de travail ne la référence
  plus et si aucun projet restant n'y vit (ADR 0016 ; sans cela,
  retirer le dernier projet importé laisserait une permission
  orpheline).
- `ValidateWorkspaceUseCase` : le test d'écriture témoin et le
  libellé lisible sont extraits en aides partagées du domaine
  (`testerEcriture`, `libelleLisible`) — même contrat pour le dossier
  de travail, l'import et la relocalisation.
- L'entrée « Paramètres » de l'accueil devient un bouton icône (rôle
  porté par la description d'accessibilité).
- `FakeProjectRepository` : robinet `flowError` (échec d'observation,
  réactif) pour éprouver l'état d'erreur de l'accueil.

### Livré

- ADR 0015 (import de dossier existant : héritage de la permission),
  ADR 0016 (suppression du disque : ordre des opérations et équilibre
  des permissions).
- Procédures manuelles A1-A10 (`docs/TESTS_MANUELS.md`).
- 411 tests verts (62 de plus) ; couverture Kover ≥ 80 % sur
  `core:model` et `core:domain` respectée.

## [0.7.0] – 2026-09-22

Étape 6 — Écran Paramètres (section 11 du prompt maître).

### Ajouté

- `feature:settings` : écran **personnalisé Material 3** (pas de
  `PreferenceFragmentCompat`), piloté par `SettingsViewModel` et
  DataStore, en sections extensibles — Apparence (thème, couleurs
  dynamiques), Langue, Projets (dossier de travail, nom d'auteur,
  licence par défaut), À propos, Avancé. Chaque réglage se
  **persiste à l'instant** et prend effet immédiatement (recréation
  d'écran par `MainActivity`, réémission de l'état).
- `core:domain` : cas d'usage du dossier de travail —
  `ValidateWorkspaceUseCase` (validation partagée avec l'assistant :
  dossiers refusés Android 11+ avant permission, test d'écriture
  témoin, permission relâchée à tout échec), `ChangeWorkspaceUseCase`
  et `ClearWorkspaceUseCase` (**l'ancienne permission persistante
  n'est libérée que si aucun projet n'en dépend** — un projet dépend
  du dossier si son URI de document vit dans son arbre),
  `ResetPreferencesUseCase` (préférences par défaut, drapeau
  d'installation et registre des projets conservés, ADR 0014).
- `core:domain` : port `ArborescencesSaf` (décomposition des URI
  d'arborescence SAF) — `core:domain` reste Kotlin JVM pur ;
  implémenté par `core:storage` (`SafArborescences`, délégation à
  `UrisDocuments`), faux déterministe dans `core:testing`
  (`FakeArborescencesSaf`).
- `feature:onboarding` : le ViewModel délègue désormais la validation
  du dossier au `ValidateWorkspaceUseCase` partagé (même
  comportement, tests inchangés — la logique n'est plus dupliquée
  entre l'assistant et les paramètres).
- Section Projets : changement de dossier (sélecteur SAF, messages
  clairs par issue — refus Android, échec typé, permission conservée
  signalée quand des projets l'utilisent), effacement, nom d'auteur
  rogné à la perte de focus (pas d'écriture par frappe), licence par
  défaut à choix unique.
- Section À propos : version (`VERSION_NAME`/`VERSION_CODE` via
  `CrashAppInfo` injecté), type de build, licences open source
  embarquées (dialogue).
- Section Avancé : « Réinitialiser les préférences » (confirmation
  obligatoire, ADR 0014) et « Relancer l'assistant » (drapeau
  repassé à faux puis navigation).
- `app` : action de navigation paramètres → assistant ;
  `AppNavigator.openOnboarding` accepte l'origine paramètres.
- Documentation : ADR 0014 (réinitialisation : états vs préférences,
  permissions conditionnelles), section « Écran Paramètres » de
  `docs/ARCHITECTURE.md`, procédures manuelles M1-M5 dans
  `docs/TESTS_MANUELS.md`, README du module `feature:settings`.

## [0.6.0] – 2026-09-22

Étape 5 — Assistant de premier lancement (section 11 du prompt maître).

### Ajouté

- `feature:onboarding` : assistant en cinq pages — bienvenue, dossier
  de travail, apparence, profil, terminé — `ViewPager2` **non
  swipable** (avance par boutons uniquement), indicateur de
  progression, transitions `MaterialSharedAxis` (axe Z, sens du
  parcours), bouton retour système qui **recule d'une page** au lieu
  de quitter l'assistant (désarmé sur la bienvenue).
- `OnboardingViewModel` : UDF complet (actions en entrée, état +
  effets ponctuels en sortie). Navigation bornée dans les deux sens ;
  **test d'écriture** du dossier de travail (création d'un fichier
  témoin, écriture, suppression — prouver que le dossier est
  réellement utilisable) ; permission persistante prise **puis
  relâchée à tout échec** (le système plafonne les permissions
  persistantes, on n'en gaspille pas une) ; persistance du dossier
  validé comme `StorageLocation` (libellé lisible via `stat`,
  repli sur l'identifiant de document).
- Dossiers refusés par Android 11+ : détection `ForbiddenFolders`
  avant toute prise de permission, message **clair et actionnable**
  par raison (racine du stockage, `Download`, `Android/data`,
  `Android/obb`) — jamais de crash ni de permission abandonnée.
- Étape dossier **passable** (« Plus tard ») : l'accueil affiche
  alors un bandeau « Configurer le dossier de travail »
  (`feature:home` `HomeViewModel` + `bandeau_dossier.xml`) qui
  rouvre l'assistant.
- Page apparence à **aperçu immédiat** : thème (système/clair/sombre
  via `AppCompatDelegate.setDefaultNightMode`), couleurs dynamiques
  (Android 12+, ADR 0008), langue FR/EN/système via
  `AppCompatDelegate.setApplicationLocales` (ADR 0013) — chaque
  choix se **persiste à l'instant**, la collecte des paramètres dans
  `MainActivity` recrée l'écran avec la nouvelle apparence.
- Page profil : nom d'auteur optionnel (conservé dans le
  `SavedStateHandle` à chaque frappe, écrit une seule fois à la fin —
  pas d'écriture DataStore par touche) et licence par défaut.
- Survie **rotation et mort du processus** : page et champs profil
  dans le `SavedStateHandle` ; l'état s'amorce une seule fois depuis
  les paramètres réels (dossier déjà validé, apparence choisie).
- `app` : routage du premier lancement — `isSetupCompleted` faux →
  l'assistant remplace l'accueil **en racine de la pile** (terminer
  n'est pas réversible) ; vrai → accueil. L'écran de démarrage est
  retenu jusqu'à la première émission des paramètres : routage et
  apparence se décident **sous le splash**, jamais à découvert.
  `android:localeConfig` déclaré (`locales_config.xml`, fr + en)
  pour le réglage système Android 13+.
- `core:ui` : `AppNavigator.openOnboarding` / `openHome` (retour de
  fin d'assistant qui retire l'assistant de la pile) ; destination
  `onboarding` dans le graphe de navigation.
- Documentation : ADR 0013 (langue par application via AppCompat),
  procédures manuelles O1-O6 dans `docs/TESTS_MANUELS.md`, README
  du module `feature:onboarding`, section « Assistant de premier
  lancement » de `docs/ARCHITECTURE.md`.

### Corrigé

- `app` : les couleurs dynamiques n'étaient appliquables qu'une fois
  au démarrage (`applyDynamicColorsIfAvailable` dans `onCreate`) ;
  elles suivent désormais le réglage utilisateur persisté, changent
  à chaud et surviennent après restauration d'une installation
  existante.

## [0.5.0] – 2026-09-22

Étape 4 — Couche données (section 11 du prompt maître).

### Ajouté

- `core:model` : `Project` (identifiant, nom, description, emplacement
  SAF, modèle générateur, horodatages de création et de dernière
  ouverture, épingle — bornes validées, `toString` en identifiant seul),
  `ProjectAccessState` (Disponible / Introuvable / Permission perdue —
  calculé, jamais persisté), `AppSettings` (thème, couleurs dynamiques,
  langue, dossier de travail, nom d'auteur, licence par défaut,
  verbosité de journalisation, assistant terminé), `ThemeMode`,
  `LogVerbosity` (projection `NORMAL`→`INFO`, `DETAILED`→`DEBUG`) et
  `License` (les cinq choix du wizard, conversion tolérante).
- `core:domain` : contrats de la couche données — `FileSystem`
  (existence, description, listing groupé et trié, création de dossier
  et de fichier avec `writeBytes` pour les fichiers binaires des
  templates, lecture/écriture texte, suppression, permissions
  persistantes — erreurs systématiquement typées en `AppResult`,
  jamais d'exception vers l'appelant), `FileStat`, `ProjectRepository`
  (observation ordonnée pour l'accueil, ajout avec identifiant produit
  par le dépôt, retrait idempotent, renommage du libellé, épingle,
  marquage d'ouverture), `SettingsRepository` (observation, lecture,
  transformation atomique, dossier de travail), `ForbiddenFolders`
  (détection pure des dossiers refusés par Android 11+ : racine,
  `Download`, `Android/data`, `Android/obb`, formes `raw:` ramenées au
  chemin relatif du volume) ; use cases `ObserveProjects`, `AddProject`,
  `RemoveProject`, `SetProjectPinned`, `RenameProject`,
  `MarkProjectOpened`, `VerifyProjectAccess` (permission d'abord,
  existence ensuite — jamais un crash), `ObserveSettings`,
  `UpdateSettings`, `SetWorkspace`.
- `core:database` : Room v1 — table `projects` avec **index unique sur
  `document_uri`**, tri de l'accueil dans la requête (épingles,
  dernier ouvert — les jamais ouverts ferment la marche —, nom
  insensible à la casse), mutations ciblées avec comptage de lignes,
  schéma exporté dans `schemas/` (référence des migrations futures,
  aucun repli destructif), mappeurs exhaustifs vers le modèle.
- `core:datastore` : `SettingsDataStore` — projection Preferences
  DataStore vers `AppSettings`, lecture tolérante champ par champ
  (valeur inconnue → défaut), dossier de travail en trio de clés
  (incomplet → non configuré), corruption remplacée par les défauts,
  transformations lire-transformer-réécrire atomiques, défauts par
  type de build (`FLAG_DEBUGGABLE`).
- `core:storage` : `SafFileSystem` sur `DocumentsContract` +
  `ContentResolver` — listing en **requête groupée** (jamais de boucle
  sur `DocumentFile`), pré-contrôle d'homonyme insensible à la casse et
  **contrôle du nom retourné** à la création (renommage silencieux
  détecté, document créé nettoyé, `AlreadyExists`), exceptions traduites
  (`SecurityException` → permission perdue, `FileNotFoundException` →
  introuvable, indices « disque plein » → plus d'espace), permissions
  persistantes derrière un port testable (lecture + écriture en une
  prise), `UrisDocuments` (concentration des constructions/décompositions
  d'URI SAF modernes).
- `core:data` : implémentations — `ProjectRepositoryImpl` (identifiant
  UUID et horodatage produits à l'ajout, `SQLiteConstraintException`
  traduite en `AlreadyExists`, retrait sans toucher au disque,
  renommage du libellé uniquement) et `SettingsRepositoryImpl`
  (délégation pure) ; journalisation des opérations **par identifiants
  uniquement** (règle 15, verrouillée par les tests).
- `core:logging` : `LogLevelApplier` — façade publique du branchement
  du niveau persisté (met à jour le niveau minimal du moteur à chaud,
  sans toucher aux autres bornes).
- `core:testing` : `FakeFileSystem` (arborescence d'URI en mémoire,
  collision insensible à la casse, permissions simulées, robinets de
  défaillance), `FakeProjectRepository` (ordre de l'accueil, unicité
  de dossier, horloge injectable), `FakeSettingsRepository`.
- `app` : assemblage de `core:data` et **branchement du niveau de
  journalisation persisté** — collecte des paramètres au démarrage du
  processus principal et application via `LogLevelApplier`
  (ADR 0011) ; test d'intégration de la couche données sur le graphe
  de production (Room et DataStore réels).
- Documentation : ADR 0011 (niveau de journalisation persisté), ADR
  0012 (renommage = libellé en base, jamais le dossier), procédures
  manuelles SAF S1-S5 dans `docs/TESTS_MANUELS.md`, section « Couche
  données » de `docs/ARCHITECTURE.md`.

### Corrigé

- `core:logging` : la configuration initiale du moteur était
  `debugDefault()` **quelle que soit la variante** — en release, le
  fichier journalisait donc des entrées `DEBUG`+ jusqu'à la première
  émission des paramètres, contredisant la section 5.7 (« défaut
  `NORMAL` »). La fourniture initiale lit désormais `FLAG_DEBUGGABLE`
  et démarre à `INFO` en release (ADR 0011).
- `app` (tests) : le test d'intégration du dialogue « rapport non
  consulté » supposait l'écriture synchrone du témoin consulté ; il
  l'attend désormais de façon déterministe (le démarrage mène d'autres
  E/S réelles en parallèle depuis l'étape 4).

## [0.4.0] – 2026-09-22

Étape 3 — Gestion des plantages (section 5.8).

### Ajouté

- `core:model` : `CrashType` (EXCEPTION, ANR, NATIVE), `CrashReport`
  (rapport complet : build, appareil non identifiant, chaîne d'exceptions
  aplatie, filons de pain, dernier écran, durée du processus, indicateur
  de boucle), `CrashReportSummary`, `CrashAppInfo`, `DeviceInfo`
  (photographie non identifiante, repli `inconnu`) ; `FlattenedException`
  conserve désormais les exceptions supprimées (5 par niveau, section 5.8).
- `core:domain` : `CrashReportRepository` (observer, lire, marquer
  consulté, supprimer), `PendingExitInfoRecorder` (port « détection au
  démarrage »), use cases `ObserveCrashReports`, `GetCrashReport`,
  `GetLatestUnreviewedCrashReport`, `MarkCrashReportReviewed`,
  `DeleteCrashReport`, `DeleteAllCrashReports`, `HasUnreviewed`,
  `RecordPendingExitInfos`.
- `core:crash` : `CrashHandler` (enchaînement complet dans un `try/catch`
  global avec garde de ré-entrance : boucle → rapport → écriture
  atomique → vidage borné ≤ 500 ms → écran dédié → mort du processus ;
  délégation au système sur boucle, échec de lancement ou échec interne —
  jamais après un lancement réussi ; budget total ≤ 2 s), `AppProcess`
  (détection du processus, repli `/proc` API 26-27), `CrashReportFileStore`
  (JSON `org.json` du framework sur le chemin critique, écriture `.tmp`
  puis renommage, réduction progressive jusqu'à 256 Ko, rétention 20
  rapports, état « consulté » par fichier témoin), `CrashLoopDetector`
  (≥ 3 plantages en 60 s, historique persistant minimal, corruption
  tolérée), `ExitInfoRecorder` (ANR et plantages natifs d'`ApplicationExitInfo`
  API 30+, hors thread principal, dédoublonnés par marqueur),
  `LastScreenTracker`, `CrashActivity` (processus `:crash`, sans Hilt/Room/
  DataStore ; modes LIVE/VIEW ; Redémarrer masqué en boucle ; Copier,
  Partager texte, Partager archive zip, Enregistrer SAF, Vider le cache
  avec confirmation — jamais les données utilisateur), `CrashFileProvider`
  du processus `:crash` (ADR 0010), `DeviceSnapshot`, modules Hilt.
- `core:logging` : `CodeIdeAppLogger.breadcrumbs(limit)` — instantané du
  tampon circulaire pour les filons d'un rapport (liaison 5.8 par lambdas,
  aucune dépendance de module).
- `core:testing` : `FakeCrashReportRepository`, `FakePendingExitInfoRecorder`.
- `app` : `CrashHandler.install` en **première ligne** d'`onCreate` (avant
  Hilt) dans le processus principal, `installSafe` dans `:crash`
  (initialisation minimale) ; liaison journalisation (session, filons,
  vidage) branchée après Hilt ; boîte de dialogue « Un problème est survenu
  lors de la dernière session » (Voir le rapport / Ignorer — les deux
  valent consultation) ; suivi du dernier écran par destination de
  navigation ; `openCrashReport(id)` dans `AppNavigator` ; menu debug
  (source set `debug`) : provoquer un plantage, exception non fatale
  journalisée, générer des journaux — no-op en release.
- Tests : 95 nouveaux (199 verts au total) dont sérialisation
  aller-retour, réduction et limite 256 Ko, expurgation à la construction,
  écriture atomique (aucun reste `.tmp`), rétention 20, mapping
  `ApplicationExitInfo` (Robolectric API 30+, déduplication, trace bornée,
  garde API 29), chaînage du gestionnaire avec tueur et lanceur **injectés**
  (le test ne tue jamais la JVM), écran dédié (modes, boucle, repli) et
  intégration bout-en-bout (installation, dialogue du rapport non consulté).
- Docs : ADR 0010 (FileProvider du processus `:crash`), guide complet
  `docs/JOURNALISATION_ET_PLANTAGES.md` § 9, procédures P1-P8 dans
  `docs/TESTS_MANUELS.md`.

### Corrigé

- `CrashReportFileStore` : la recherche d'un rapport par identifiant
  retranche désormais l'identifiant du nom de fichier au lieu d'un
  suffixe — un suffixe était ambigu quand un identifiant se termine par
  celui d'un autre (« pas-vu » finit en « vu »), et le témoin « consulté »
  pouvait être posé sur le mauvais rapport (découvert par les tests
  avant toute livraison).

## [0.3.0] – 2026-09-22

Étape 2 — Journalisation de l'application (section 5.7).

### Ajouté

- `core:model` : `LogLevel` (DEBUG…ERROR, comparaison `isAtLeast`),
  `LogEntry` sérialisable (c'est la ligne JSON Lines persistée) et
  `FlattenedException` (exception aplanie bornée — 50 trames, 10 causes,
  chaînes cycliques tolérées, messages transformables pour l'expurgation).
- `core:domain` : `AppLogger` (lambda de message évaluée **seulement** si
  le niveau est actif), `LogRedactor` (expurgation idempotente : courriels
  → `<courriel>`, URI `content://` → autorité conservée + identifiant
  haché, chemins absolus → `<chemin>`), `LogConfig` (bornes 5.7 :
  1 Mio / 5 archives / 7 jours), `LogRepository` (observer, lire, mesurer,
  effacer), `TimeProvider` (horloge injectée), use cases `ObserveLogs`,
  `ExportLogs`, `ClearLogs` (erreurs d'I/O typées en `AppError.Storage`,
  `CancellationException` toujours relancée) et `LogExportWriter`
  (couture d'implémentation). Couverture 88 %.
- `core:testing` : `FakeAppLogger` (évalue immédiatement, pour observer)
  et `InMemoryLogRepository` (robinets d'erreur pilotables).
- `core:logging` : moteur du pipeline (filtrage, expurgation, troncature
  4 Kio, aplatissement, breadcrumbs 200, émission), `LogcatSink`
  (DEBUG+ en debug, WARN+ en release), `FileSink` asynchrone (canal borné
  `DROP_OLDEST`, écriture groupée ≤ 500 ms, flush immédiat sur `ERROR`,
  `flushBlocking` par verrou de coordination — jamais de `runBlocking`),
  `JsonlLogStore` (rotation par décalage, rétention, lignes corrompues
  comptées), dépôt, export zip `codeide-logs-<date>.zip` (UTC,
  `logs.jsonl` + `device-info.txt`, nettoyage des 5 plus récents),
  en-tête de session, module Hilt. ADR 0009.
- `app` : initialisation de la journalisation dans le **processus
  principal uniquement** (détection du nom de processus, repli `/proc`
  pour API 26-27), `BuildInfo`/`DeviceSummary` (résumé non identifiant),
  `DispatcherProvider` lié, FileProvider limité à `cache/exports/`,
  premiers journaux (démarrage, navigation).
- Tests : 67 nouveaux (104 verts au total) dont stress multi-threads
  (intégrité, FIFO par producteur), rotation/rétention, temps virtuel du
  groupement, export zip valide, expurgation exhaustive et intégration
  bout-en-bout depuis l'application réelle.
- Documentation : `docs/JOURNALISATION_ET_PLANTAGES.md`,
  `docs/TESTS_MANUELS.md`, ADR 0009.

### Corrigé

- `checkModuleDependencies` : la consommation de `core:testing` en
  `testImplementation` tombait à tort dans le contrôle de la liste
  d'autorisation (les doubles de test sont précisément l'usage prévu par
  la section 5.2) — le `continue` manquant est posé.

### Notes techniques

- La boucle de consommation retire par `tryReceive` en rafale : un
  `withTimeoutOrNull` par entrée plafonnait le consommateur à ~13 000
  entrées/s sur la machine de build et saturait le canal (perte ~60 %) ;
  le goulot est documenté dans l'ADR 0009.
- Le test de stress vérifie l'intégrité (aucune corruption, FIFO par
  producteur, zéro échec d'écriture) et non un taux de livraison : la
  perte sous surcharge est le comportement spécifié de `DROP_OLDEST`.
- L'export et le vidage bloquant s'exécutent hors du thread principal ;
  StrictMode (actif en debug) n'a rien relevé sur les parcours testés.

## [0.2.0] – 2026-09-22

Étape 1 — Fondations transverses.

### Ajouté

- `core:model` : `AppResult` (`Success`/`Failure` + extensions `getOrNull`,
  `onSuccess`, `onFailure`), `AppError` scellé (`Storage` avec six raisons,
  `Validation`, `Template`, `Unknown`), identifiants typés (`EntityId`,
  `ProjectId`, `TemplateId`, `CrashReportId`) et `StorageLocation` (contrat
  SAF à trois champs, `toString` sûr pour les journaux). Couverture 100 %.
- `core:domain` : `DispatcherProvider` (+ implémentation de référence
  injectable) et convention des use cases documentée (`operator fun invoke`,
  `docs/CONVENTIONS.md`). Couverture 100 %.
- `core:testing` : `MainDispatcherRule` (installation/retrait de `Main`,
  dispatcher exposé) et `TestDispatcherProvider` — premier double de test.
- `core:ui` : thème Material 3 complet clair/sombre en tokens (couleurs,
  typographie, formes, espacements, styles de composants), thème SplashScreen,
  couleurs dynamiques optionnelles Android 12+ (ADR 0008), `BaseFragment<VB>`
  (ViewBinding libéré dans `onDestroyView`), `collectWithLifecycle`,
  `EmptyStateView`/`LoadingView`/`ErrorStateView` (attributs XML + bouton
  Réessayer), helpers insets edge-to-edge et `AppNavigator`.
- `app` : `CodeIdeApplication` (Hilt, StrictMode en debug via
  `FLAG_DEBUGGABLE`, LeakCanary en `debugImplementation`), `MainActivity`
  (SplashScreen, couleurs dynamiques, edge-to-edge, `NavHostFragment`),
  graphe de navigation Accueil ↔ Paramètres et `AppNavigatorImpl` (lien
  `@Binds` dans l'ActivityComponent).
- `feature:home` et `feature:settings` : fragments placeholder — première
  application de la convention `codeide.android.feature` (ViewBinding +
  Hilt + dépendances autorisées).
- Tests : 37 tests verts au total (20 modèle, 3 domaine, 3 testing, 6 ui,
  1+1 features, 3 app) dont un test d'intégration Robolectric + Hilt qui
  démarre `MainActivity` et vérifie l'aller-retour complet.

### Changé

- Le thème déménage de `app` vers `core:ui` (source unique des tokens) ;
  l'ancien layout d'accueil minimal est remplacé par le conteneur de
  navigation.
- `gradle.properties` : build **séquentiel** et compilation Kotlin
  **in-process** — l'exécution parallèle empilait plusieurs démons Kotlin et
  déclenchait l'OOM killer du noyau (4 Go de RAM). Tas des tests bornés à
  640 Mo dans les conventions. Détails dans `docs/ENVIRONNEMENT.md`.

### Corrigé

- `version.properties` : `VERSION_CODE` de l'étape 0 valait `10000` pour
  `0.1.0`, au lieu de `100` exigé par la formule
  `major × 10000 + minor × 100 + patch` (section 9.1 du prompt maître).
  La valeur est réalignée sur la formule : `0.2.0` → `200`. Conséquence
  pratique : l'APK 0.2.0 (code 200) refuse de remplacer un APK 0.1.0
  (code 10000) — désinstaller l'ancien d'abord ; sans incidence tant que
  l'application n'est pas distribuée.

### Notes techniques

- L'interface `ViewBinding` vit dans `androidx.databinding:viewbinding:9.4.1`
  (paquet `androidx.viewbinding`) ; les classes générées sont dans le
  sous-paquet `<namespace>.databinding` du module.
- Hilt 2.60 : `ActivityComponent` a déménagé vers `dagger.hilt.android.components`
  et l'activité s'injecte **sans** qualificateur `@ActivityContext`
  (le builder ne le porte plus).
- Material 1.14 : ni l'attr `colorSurfaceTint`, ni
  `shapeAppearanceExtraLargeComponent`, ni le style
  `ShapeAppearance.Material3.ExtraLargeComponent` n'existent — le thème
  s'en passe (teinte de surface gérée par la bibliothèque).

## [0.1.0] – 2026-09-22

Étape 0 — Environnement, squelette Gradle et outillage de livraison.

### Ajouté

- Squelette Gradle multi-modules complet : `app`, `build-logic`, 10 modules
  `core:*` et 6 modules `feature:*`, tous compilables avec leur `README.md`
  et `Module.md`.
- `build-logic` avec les six convention plugins (`codeide.kotlin.library`,
  `codeide.android.application`, `codeide.android.library`,
  `codeide.android.feature`, `codeide.android.hilt`, `codeide.android.room`)
  et le plugin `codeide.module-rules` qui enregistre la tâche
  `checkModuleDependencies` — vérification automatique des règles de
  dépendance de la section 5.2, le build échoue en cas de violation.
- Chaîne d'outils vérifiée sur les dépôts officiels (voir `docs/ENVIRONNEMENT.md`) :
  Gradle 9.7.1 via wrapper, AGP 9.4.1 avec Kotlin intégré 2.2.10, KSP 2.3.12,
  Hilt 2.60.1, Room 2.8.5, Material 1.14.0, compileSdk 37.2, minSdk 26.
- Qualité configurée et verte : Spotless + ktlint 1.8.0, detekt 1.23.8 (avec
  règle d'interdiction de `android.util.Log`/`println`/`printStackTrace` hors
  `core:logging` et `core:crash`), Android Lint strict (avertissements en
  erreurs, sans ligne de base — exception ciblée documentée pour les conseils
  de fraîcheur de versions), Kover avec seuil ≥ 80 % sur `core:model` et
  `core:domain`.
- `version.properties` (source unique de version) et les scripts
  `bump-version.sh`, `package.sh`, `verify-archive.sh`.
- `scripts/setup-env.sh` (installation/validation idempotente de
  l'environnement) et `scripts/env.sh`.
- Application minimale : `MainActivity` Material 3, thème clair/sombre,
  ressources localisées français (défaut) + anglais, icône adaptative avec
  couche monochrome — et son test Robolectric.
- Documentation : `README.md`, ce journal, `AGENTS.md`, `docs/ARCHITECTURE.md`,
  `docs/CONVENTIONS.md`, `docs/ENVIRONNEMENT.md`, `docs/ROADMAP.md` et les
  ADR 0001 à 0007.

### Corrigé

- `scripts/verify-archive.sh` : la détection du contenu obligatoire était non
  déterministe (SIGPIPE sur `unzip` quand `grep -q` sort à la première
  correspondance, combiné à `pipefail`) ; le listing est désormais capturé
  puis sondé sans tube producteur vivant.

### Notes techniques

- AGP 9 active le support Kotlin **intégré** : le plugin `kotlin-android` n'est
  plus appliqué, kapt est incompatible (KSP requis) — décision et conséquences
  dans l'ADR 0007.
- Les conventions `codeide.android.feature`, `codeide.android.hilt` et
  `codeide.android.room` sont prêtes et compilées mais ne s'appliquent à aucun
  module à ce stade : KSP crée des répertoires de sortie vides qui font échouer
  la détection de tests de Gradle 9 sur un module sans test. Elles seront
  appliquées dès que le contenu fonctionnel (étapes 1 et suivantes) les
  justifiera.
