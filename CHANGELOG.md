# Journal des modifications

Ce journal suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/),
en français. Le versionnage suit [SemVer](https://semver.org/lang/fr/) :
`0.N.0` par étape validée, `0.N.M` pour une correction après retour utilisateur.

## [Non versionné]

### Ajouté

- **Spécification de l'explorateur de fichiers v2 (étape 31)** :
  `docs/EXPLORATEUR_V2.md` décrit **exactement** la maquette interactive
  validée le 2026-09-25 (copiée dans le dépôt :
  `docs/preview/explorateur-v2.html`) pour une reproduction à l'identique —
  tiroir à **fragments** (entête propre par fragment, plus d'entête commun),
  poignée ⋮ de redimensionnement (bornes 45–98 %, aimants 55/69/85/98 %),
  arbre treeview à guides fins et **chevrons** (crochets retirés après
  retour utilisateur — les chevrons restent seuls), points d'état des
  fichiers à 4 états, vraies icônes par type, **bascule Projet/Privé
  exclusive** (remplace l'ancien bouton cadenas « afficher/masquer »),
  popover maison ancré au point d'appui avec chemin contextuel, mutations
  par nœud (créer/renommer/supprimer/copier/couper/coller/déplacer). La
  ROADMAP inscrit cette refonte comme étape 31 (v0.32.0) ; plugins,
  services d'arrière-plan et autres langages reculent d'un rang
  (32 → 0.33.0, 33 → 0.34.0, 34 → 0.35.0).

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

## [Non publié]

### Ajouté

- (à compléter)
