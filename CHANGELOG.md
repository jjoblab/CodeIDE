# Journal des modifications

Ce journal suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/),
en français. Le versionnage suit [SemVer](https://semver.org/lang/fr/) :
`0.N.0` par étape validée, `0.N.M` pour une correction après retour utilisateur.

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
