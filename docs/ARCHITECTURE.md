# Architecture de CodeIDE

Ce document décrit l'organisation des modules, les règles de dépendance et
les patrons applicatifs. Les décisions structurantes sont consignées dans les
ADR (`docs/adr/`). Le document complet de référence est le prompt maître,
sections 5 et 6.

## Vue d'ensemble

```
┌────────────────────────────────────────────────────────────┐
│ app — assemblage final (MainActivity, navigation, Hilt)     │
└──────┬─────────────────────────────────────────────────────┘
       │
┌──────┴───────────────┐  ┌────────────────────────────────────┐
│ feature:*            │  │ core:ui — thème, composants,       │
│ onboarding, home,    │  │ BaseFragment, AppNavigator          │
│ newproject,          │  └────────────────────────────────────┘
│ settings,            │
│ diagnostics, editor  │──► core:domain — use cases, interfaces
└──────────────────────┘         │
                          ┌──────┴─────────────────────────────┐
                          │ core:data — implémentations des    │
                          │ repositories (assemble les sources)│
                          └──────┬──────────┬──────────┬───────┘
                 ┌──────────────┤          │          │
          core:database   core:datastore  core:storage  core:logging
          (Room)           (DataStore)    (SAF)         (sinks, export)
                                 
  core:crash (plantages, processus :crash)  core:model (types purs, base)
  core:testing (fakes, testImplementation uniquement)
```

## Modules (section 5.1)

| Module | Responsabilité |
|---|---|
| `app` | Application, MainActivity, graphe de navigation, assemblage Hilt |
| `core:model` | Kotlin JVM pur — entités et types partagés (`AppResult`, `AppError`, `Project`…) |
| `core:domain` | Kotlin JVM pur — use cases, interfaces (repositories, `FileSystem`, `AppLogger`…) |
| `core:data` | Implémentations des repositories |
| `core:database` | Room : entités, DAO, convertisseurs |
| `core:datastore` | Préférences (Preferences DataStore) |
| `core:storage` | Accès fichiers via SAF (implémente `FileSystem`) |
| `core:logging` | Journalisation : sinks, rotation, export |
| `core:crash` | Capture des plantages + CrashActivity (processus séparé `:crash`) |
| `core:bootstrap` | Localisation des outils du bootstrap, environnement de sous-processus, installateur (ADR 0032/0033) |
| `core:terminal-runtime` | Sessions shell réelles : registre global + service foreground (ADR 0035) |
| `core:ui` | Thème Material 3, classes de base, composants réutilisables |
| `core:testing` | Fakes et utilitaires de test (testImplementation seulement) |
| `feature:onboarding` | Assistant de premier lancement |
| `feature:home` | Liste des projets |
| `feature:newproject` | Wizard de création de projet |
| `feature:settings` | Paramètres |
| `feature:diagnostics` | Visionneuse de journaux et rapports de plantage |
| `feature:install` | Écran d'installation du bootstrap natif (état partagé du domaine, Terminal T3) |
| `feature:editor` | Stub : futur espace de travail |
| `tools:generateur` | Harnais CLI de génération sur disque (vérification des modèles, ADR 0019) |

Chaque module possède un `README.md` (responsabilité, dépendances autorisées,
API prévue) et un `Module.md` (page Dokka).

## Règles de dépendance (section 5.2)

> Permission : `INTERNET` depuis l'étape T3 (ADR 0034) — unique usage
> réseau, l'installateur du bootstrap.

Ces règles sont **vérifiées automatiquement** par la tâche Gradle
`checkModuleDependencies` (plugin `codeide.module-rules` de `build-logic`) :
toute dépendance non autorisée **fait échouer le build**.

| Module | Peut dépendre de | Ne doit jamais dépendre de |
|---|---|---|
| `core:model` | rien | Android, tout autre module |
| `core:domain` | `core:model` | Android, `core:data`, sources de données |
| `core:database`, `core:datastore`, `core:storage`, `core:logging` | `core:model`, `core:domain` | features, `core:data`, `core:ui` |
| `core:crash` | `core:model`, `core:domain`, `core:ui` | features, `core:data`, `core:logging` (liaison par interfaces/lambdas) |
| `core:data` | `core:domain`, `core:model`, les sources de données | features, `core:ui` |
| `core:ui` | `core:model` | `core:domain`, `core:data`, features |
| `feature:*` | `core:ui`, `core:domain`, `core:model` | `core:data`, sources de données, `core:crash`, `core:logging`, **autres features** |
| `app` | tout (assemblage) | — |
| `core:testing` | `core:model`, `core:domain` | — (consommé en `testImplementation` seulement) |

Règles additionnellement vérifiées : `core:testing` ne peut apparaître que
dans les configurations de test ; `core:model` et `core:domain` ne peuvent
appliquer aucun plugin Android (modules JVM purs).

## Patron de présentation : MVVM + flux unidirectionnel (UDF)

Pour chaque écran :

- `XxxUiState` : `data class` immuable, exposée en `StateFlow` ;
- `XxxAction` : `sealed interface` des intentions ; le ViewModel expose
  `onAction(action)` ;
- `XxxEffect` : événements ponctuels (navigation, snackbar, sélecteur SAF)
  via `Channel` → `Flow` ;
- le Fragment ne fait que rendre l'état (`repeatOnLifecycle(STARTED)`) et
  émettre des actions ;
- ViewBinding nettoyé dans `onDestroyView` (`BaseFragment<VB>` de `core:ui`) ;
- l'état critique survit à la rotation **et** à la mort du processus
  (`SavedStateHandle`) ;
- un ViewModel ne référence jamais `Context`, `View` ni `Fragment`.

## Activités et navigation

- `MainActivity` héberge un `NavHostFragment` (Onboarding, Home, NewProject,
  Settings, Diagnostics en fragments) ; écran de démarrage via l'API
  SplashScreen ; la destination initiale dépend de `isSetupCompleted`.
- `EditorActivity` (étape 13, ADR 0026) : activité séparée lancée par-dessus la pile (`AppNavigator.openEditor`), espace de travail lourd — tiroir permanent sur grand écran.
- `CrashActivity` (module `core:crash`) : **processus séparé** `:crash`.
- Les features ne se connaissent pas : navigation via l'interface
  `AppNavigator` (définie dans `core:ui`, implémentée dans `app`).

## Assistant de premier lancement (étape 5 — livrée à v0.6.0)

Le premier lancement (`isSetupCompleted` faux) route vers
`feature:onboarding` **en racine de la pile** — le terminer n'est pas
une navigation réversible. L'écran de démarrage est retenu jusqu'à la
première émission des paramètres : routage et apparence se décident
sous le splash, jamais à découvert.

- **Cinq pages, pager non swipable** (`ViewPager2`, `isUserInputEnabled
  = false`) : bienvenue, dossier de travail, apparence, profil,
  terminé. Le bouton retour système **recule d'une page** (désarmé sur
  la bienvenue), transitions `MaterialSharedAxis` axe Z.
- **Dossier de travail** : sélecteur SAF (`OpenDocumentTree`), dossiers
  refusés par Android 11+ détectés **avant** toute prise de permission
  (message clair par raison), test d'écriture (fichier témoin créé,
  écrit puis supprimé), permission persistante **relâchée à tout
  échec** (plafond système). Étape passable : « Plus tard » → bandeau
  « Configurer le dossier de travail » à l'accueil.
- **Apparence à aperçu immédiat** : chaque choix (thème, couleurs
  dynamiques, langue) se persiste à l'instant ; `MainActivity` collecte
  les paramètres et recrée l'écran. Langue via
  `AppCompatDelegate.setApplicationLocales` (ADR 0013).
- **Survie rotation et mort du processus** : page et champs profil dans
  le `SavedStateHandle` du ViewModel ; l'amorçage depuis les paramètres
  réels n'a lieu qu'une fois par vie du sauvetage (drapeau interne).

## Écran Paramètres (étape 6 — livrée à v0.7.0)

Écran **personnalisé Material 3** (pas de `PreferenceFragmentCompat`),
piloté par `SettingsViewModel` et DataStore, en sections extensibles :
Apparence, Langue, Projets, À propos, Avancé (l'entrée Diagnostic arrive
à l'étape 12). Chaque réglage se persiste à l'instant — l'effet
immédiat vient de la collecte de `MainActivity` (recréation d'écran),
jamais d'un état UI divergent.

- **Dossier de travail** : changement (sélecteur SAF + validation
  partagée avec l'assistant), effacement — l'ancienne permission
  persistante n'est **libérée que si aucun projet n'en dépend**
  (ADR 0014) ; messages clairs par issue (refus Android, échec typé,
  permission conservée).
- **Port `ArborescencesSaf`** (domaine) : décomposition des URI
  d'arborescence SAF (`DocumentsContract`) isolée derrière une
  interface — `core:domain` reste Kotlin JVM pur ; implémenté par
  `core:storage`, faux déterministe dans `core:testing`.
- **Réinitialisation** : préférences par défaut, états applicatifs
  conservés (`isSetupCompleted`), registre des projets intact
  (ADR 0014).
- **À propos** : version (`VERSION_NAME`/`VERSION_CODE` via
  `CrashAppInfo` injecté), type de build, licences open source
  embarquées.

## Accueil : liste des projets (étape 7 — livrée à v0.8.0)

`feature:home` rend le registre des projets exploitable : liste
(`ListAdapter` + `DiffUtil` — le projet et son état d'accès forment
l'identité de la ligne), recherche avec délai (250 ms, insensible à la
casse et aux accents), tri Récents/Nom (épingles **toujours** en tête),
états soignés (chargement, vide, sans résultat, erreur avec réessai,
bandeau « dossier de travail non configuré » de l'étape 5).

- **Statut d'accès** (section 5.6) : `VerifyProjectAccessUseCase`
  recalculé à chaque affichage, au tirer-relâcher et après toute action
  qui déplace un dossier — jamais persisté. Un projet `Introuvable` ou
  `Permission perdue` est signalé par un badge sur sa ligne et résolu
  par « Relocaliser » / « Retirer » dans son menu d'actions, sans crash.
- **Actions par projet** : ouvrir (marquage « ouvert », placeholder
  éditeur jusqu'à l'étape 13), renommer (libellé seul, ADR 0012),
  épingler, retirer de la liste, **supprimer du disque** — confirmation
  avec rappel du nom, disque d'abord puis registre (ADR 0016).
- **Import de dossier existant** : « Ouvrir un dossier existant »
  (sélecteur SAF) ajoute le dossier au registre avec la sentinelle
  `TemplateId.IMPORTED` ; un choix dans l'arbre du dossier de travail
  hérite de sa permission — l'URI de document est réadressée dans cet
  arbre (`ArborescencesSaf.uriDocumentDansArbre`) pour rester comparable
  par la règle de libération conditionnelle (ADR 0015).
- **Adaptatif** : 1 colonne téléphone, 2 colonnes tablette/paysage
  (`layout-sw600dp`, `GridLayoutManager`).
- **Wizard** : le bouton étendu « Nouveau projet » ouvre l'assistant
  de `feature:newproject` (cinq étapes + écran de création, étapes 10-11,
  section dédiée ci-dessous) ; après une création réussie, l'accueil
  **défile jusqu'au nouveau projet** et le marque d'un contour (ADR 0024).

## Moteur de templates (étape 8 — livrée à v0.9.0)

Le cœur de la création de projet est un **moteur pur** (`core:domain`,
package `templates`) au-dessus de manifestes déclaratifs — aucun modèle
n'est codé en dur, ajouter un modèle = ajouter des assets (ADR 0005).
Le format complet (manifeste, expressions, filtres, garde des chemins)
est le contrat `docs/TEMPLATES.md`.

- **Port d'assets** : `TemplateAssetsSource` (liste, lecture de fichier de
  modèle, lecture de licence SPDX). Implémentation Android dans `app`
  (`AssetTemplateAssetsSource`, AssetManager + dispatcher d'E/S, aucune
  traversée de chemin) ; faux en mémoire dans `core:testing`.
- **Mini-langage d'expressions** (ADR 0018) : parseur **écrit à la main**
  (lexique + descente récursive), bornes avant récursion (512 caractères,
  128 jetons, 16 de profondeur), typage strict (`Chaine`/`Booleen`),
  aucune évaluation de code du manifeste. Même grammaire pour `visibleWhen`,
  `when`, `computed` et `{{#if}}`.
- **Rendu** (`TemplateRenderer`) : `{{variable|filtre}}`, conditionnels
  `{{#if}}/{{#else}}`, i18n `{{t:clé}}`, échappement `\{{` ; échec explicite
  fichier + ligne, **jamais de `{{…}}` résiduel**. Filtres d'échappement
  (`kotlinString`, `javaString`, `xml`, `json`, `tomlString`, `md`) : la
  saisie de l'utilisateur ne casse jamais le code généré.
- **Plan figé** (ADR 0017) : `PlanProjectCreationUseCase` (dry-run du
  récapitulatif) et `CreateProjectUseCase` partagent `TemplateProjectPlanner`
  — le plan contient le **contenu final** ; ce qui est planifié est ce qui
  est écrit, à l'octet près. Sécurité : garde des chemins après substitution
  (jamais de `..`, d'absolu, de nom réservé Windows), doublons refusés,
  dossier racine jamais écrasé.
- **Création** (`CreateProjectUseCase`) : revalidation systématique, dossier
  racine créé **absent** exigé, fichiers écrits avec progression
  (`CreationProgress`), registre écrit **en dernier**, rollback complet en
  `NonCancellable` (échec, annulation, échec d'insertion) avec résidus
  signalés.
- **Métadonnées** : chaque projet généré porte `.codeide/project.json`
  (schéma, modèle, version du générateur, paramètres persistés visibles) —
  aucune donnée personnelle.
- **Extension** : `ProjectTemplateProvider` en **multibinding Hilt**
  (`@IntoSet`) — le fournisseur embarqué lit `assets/templates/`, les futurs
  plugins s'ajouteront sans toucher au moteur.

## Modèles embarqués et validation réelle (étape 9 — livrée à v0.10.0)

- **Modèles `kotlin-jvm` et `java`** (`app/src/main/assets/templates/`) :
  paramètres partagés (type application/bibliothèque, build
  `gradle-kts`/`maven`/`none`, JDK 17 ou 21 — seules les LTS **entièrement
  validées** sont proposées, ADR 0019, tests JUnit 5, Gradle Wrapper sommé,
  package/group/artifact/version), exemple `Greeter` + `Main` +
  `GreeterTest` **zéro avertissement**, README dynamique, `.gitignore`/
  `.gitattributes`/`.editorconfig` adaptés, licences SPDX dans `pom.xml` et
  la publication Gradle. Versions figées et traçabilité :
  `docs/TEMPLATES.md`.
- **Tests de génération** (`app`, `ModelesEmbarquesTest`) : 192
  combinaisons structurelles avec listes de fichiers attendues, options
  communes (5 licences × interrupteurs), déterminisme, entrées hostiles,
  écriture complète sur `FakeFileSystem`.
- **Validation réelle** : `scripts/verify-templates.sh` génère 18
  combinaisons sur disque via le harnais `:tools:generateur` (module JVM
  dédié, ADR 0019 : le plan figé de `PlanProjectCreationUseCase` déversé
  tel quel), puis compile, teste, exécute et publie chaque projet avec les
  vrais Gradle/Maven/javac (projet Gradle jetable pour `none`+Kotlin) —
  tableau final `combinaison → résultat`, à garder vert à toute livraison
  touchant aux modèles (sections 8 et 11 du prompt maître).

## Wizard de création (étapes 10-11 — livré à v0.12.0)

Le cadre, les cinq étapes numérotées et l'écran de création de la
section 12 du prompt maître (ADR 0020-0024) : `feature:newproject`.

- **Hôte** (`NewProjectFragment`) : barre d'outils (✕ + dialogue
  « Abandonner la création ? » si des données sont saisies), **indicateur
  d'étapes** (progression linéaire + « Étape N sur M · Titre », annoncé
  TalkBack), conteneur de fragments d'étapes, **barre d'actions fixe**
  (Retour masqué sur la première étape, Suivant désactivé tant que l'étape
  est invalide, devenant **« Créer le projet »** sur le récapitulatif).
  Transitions `MaterialSharedAxis` axe X, coupées quand « réduire les
  animations » est actif ; retour système = étape précédente puis abandon
  confirmé ; contenu borné et centré sur tablette (`layout-sw600dp`).
- **Machine à états** (`WizardViewModel`, scopé à l'hôte, ADR 0020) :
  étapes déclarées dans une liste configurable (`WizardStep`), état unique
  `EtatWizard` (catalogue, modèle, nom, description, valeurs saisies,
  champs figés, emplacement, vérification, options communes, plan, état
  de création) survivant rotation **et mort du processus** via
  `SavedStateHandle`. À chaque changement :
  `EvaluateTemplateFormUseCase` réévalue visibilité (`visibleWhen`),
  valeurs dérivées (`defaultFrom`, figées par modification manuelle,
  resynchronisables), validité — les étapes ne font que rendre.
- **Étape 1 Modèle** : grille de cartes sélectionnables (monogramme
  maison résolu depuis l'i18n du modèle, nom, description, tags),
  sélection unique présélectionnée au retour ; recherche masquée sous
  4 modèles mais prête (état « aucun résultat » prévu).
- **Étape 2 Configuration** : rendu **dynamique** depuis le moteur
  (ADR 0021) — tuiles segmentées (type de projet, icône + sous-titre),
  cartes radio (système de build, aide dynamique « sans build »),
  liste déroulante (JDK), interrupteurs (tests, wrapper) qui
  apparaissent/disparaissent selon `visibleWhen` ; **rangée de puces
  récapitulatives** en direct.
- **Étape 3 Informations et emplacement** : nom (raisons typées →
  ressources localisées, ADR 0021), description avec compteur, champs
  dérivés (package/groupId/artifactId/version selon la visibilité,
  icône de resynchronisation) ; **carte d'emplacement** — dossier de
  travail par défaut, changement « pour cette création uniquement »
  (ADR 0022 : héritage dans l'arbre, permission propre relâchée à
  l'abandon si inutilisée), aperçu `…/<Nom>`, **vérifications
  asynchrones avec délai** (permission, joignabilité, collision de nom
  insensible à la casse) avec indicateur en cours.
- **Étape 4 Fichiers** : interrupteurs des fichiers optionnels
  (README/.gitignore/.editorconfig), licence pré-remplie des Paramètres
  (auteur et année affichés — consommés par MIT et BSD), langue du
  contenu générée (Français / English par boutons segmentés) — tout
  alimente `TemplateOptions` du moteur ; l'étape ne bloque jamais.
- **Étape 5 Récapitulatif** : résumé par section avec bouton
  « Modifier » (retour arrière direct, jamais vers l'avant) et
  **arborescence prévue repliable** (`Arborescence` : transformation pure
  du plan — dossiers d'abord, ordre stable), comptage des fichiers,
  chargement/erreur avec Réessayer.
- **Écran de création** (hors numérotation, ADR 0023) : piloté par
  `EtatCreation` dans `EtatWizard` — `EnCours` (liste des événements du
  flot froid de `CreateProjectUseCase`, bouton Annuler = `Job.cancel()`,
  le domaine roule le rollback en `NonCancellable` puis le ViewModel
  ramène au récapitulatif), `Succes` (Ouvrir le projet [marque
  `lastOpenedAt` — éditeur à l'étape 13], Retour à l'accueil, Créer un
  autre projet), `Echec` (message par erreur typée, Réessayer, Copier
  les détails expurgés, nettoyage signalé). L'indicateur et la barre
  d'actions disparaissent tant que l'état n'est pas `Inactif`.
- **Mise en évidence à l'accueil** (ADR 0024) : `AppNavigator.
  wizardCreeProjet(id)` dépose l'identifiant dans le `SavedStateHandle`
  de l'entrée d'accueil de la pile de retour ; l'accueil le consomme une
  fois — défilement jusqu'au projet + contour de la couleur primaire du
  thème.
- **Domaine** : `EvaluerNomProjetUseCase` (raison typée du nom),
  `ResolveCreationLocationUseCase` / `ReleaseCreationLocationUseCase` /
  `VerifyCreationTargetUseCase` (ADR 0022), `RaisonValidation` (type
  fermé, core:model) porté par `TemplateParameterEvaluation.errorReason`,
  `PlanProjectCreationUseCase` (dry-run du récapitulatif) et
  `CreateProjectUseCase` (algorithme de la section 12.4 — livrés à
  l'étape 8, branchés au wizard à l'étape 11).

## Gestion des erreurs et résultats

- `AppResult<out T>` : `Success(value)` | `Failure(error: AppError)`.
- `AppError` (sealed) : `Storage` (`PermissionLost`, `NotFound`,
  `AlreadyExists`, `NoSpace`, `NotWritable`, `Io`), `Validation`, `Template`,
  `Unknown`.
- L'UI traduit les `AppError` en messages localisés ; les détails techniques
  vont dans les journaux (`AppLogger`).

## Couche données (étape 4 — livrée à v0.5.0)

Le registre des projets et les paramètres vivent derrière les interfaces
du domaine (`ProjectRepository`, `SettingsRepository`) ; l'accès aux
fichiers passe **exclusivement** par le port `FileSystem`.

- **Registre** (`core:database` + `core:data`) : Room v1, table
  `projects` avec **index unique sur `documentUri`** (le même dossier ne
  peut pas être référencé deux fois), tri de l'accueil porté par la
  requête (épingles d'abord, dernier ouvert d'abord, nom insensible à la
  casse), mutations ciblées avec comptage de lignes (`0` → `NotFound`
  côté dépôt). L'identifiant (UUID) et l'horodatage sont produits par le
  dépôt à l'ajout. Renommer un projet ne change **que le libellé** —
  jamais le dossier (ADR 0012). Schémas exportés dans
  `core/database/schemas/` : référence des migrations futures, aucun
  repli destructif.
- **Paramètres** (`core:datastore` + `core:data`) : Preferences DataStore
  projeté vers `AppSettings`. Lecture **tolérante** champ par champ
  (valeur inconnue sur disque → défaut), corruption remplacée par les
  défauts (`ReplaceFileCorruptionHandler`), transformations
  lire-transformer-réécrire **atomiques**, dossier de travail en trio de
  clés (incomplet → non configuré). La verbosité persistée
  (`AppSettings.logLevel`) est appliquée au moteur de journalisation via
  `LogLevelApplier` au démarrage du processus principal (ADR 0011).
- **Stockage** (`core:storage`) : `SafFileSystem` sur `DocumentsContract`
  (détails dans la section SAF ci-dessous). Permissions persistantes
  derrière un port testable ; l'état d'accès d'un projet
  (`ProjectAccessState`) se calcule **permission d'abord, existence
  ensuite** (`VerifyProjectAccessUseCase`), jamais en crash.
- **Tests** : fakes en mémoire dans `core:testing` (`FakeFileSystem`,
  `FakeProjectRepository`, `FakeSettingsRepository`) pour les use cases
  et les ViewModels ; DAO et DataStore testés en Robolectric ;
  `SafFileSystem` testé contre un fournisseur de documents factice qui
  respecte le **vrai** protocole d'appel du framework (vérifié sur le
  bytecode d'`android-all`). Essais sur le SAF système réel :
  procédures S1-S5 de `docs/TESTS_MANUELS.md`.

## SAF (section 5.6 — points d'attention)

On obtient des **URI**, pas des chemins `File`. Le modèle `StorageLocation`
porte `grantUri` (l'arbre qui détient la permission), `documentUri` (le
dossier) et `displayPath` (libellé lisible). Permissions persistantes via
`takePersistableUriPermission` (plafonds système : 512 sur Android 11+).
`DocumentFile` est lent : l'implémentation `SafFileSystem` utilise
`DocumentsContract` et des requêtes groupées. `createDocument` peut renommer
silencieusement en cas de collision : toujours vérifier. Toute l'app accède
aux fichiers **uniquement** via l'interface `FileSystem` du domaine.

Les URI de documents suivent la **forme moderne** (API 26+) :
`content://<autorite>/tree/<arbre>/document/<id>` (et
`…/document/<id>/children` pour le listing) — segment `document`, pas
`doc`. La construction/décomposition vit dans `UrisDocuments`
(`core:storage`), unique endroit qui manipule ces formes. Les dossiers
refusés par Android 11+ (racine, `Download`, `Android/data`,
`Android/obb`) sont détectés par `ForbiddenFolders` (`core:domain`, pur),
avec les formes `raw:` ramenées au chemin relatif du volume.

## Journalisation et plantages

Spécifications complètes dans les sections 5.7 et 5.8 du prompt maître ;
résumé :

- `AppLogger` (API dans `core:domain`) avec évaluation paresseuse des
  messages, `LogRedactor` (expurgation à l'écriture : URI, chemins, e-mails),
  sinks Logcat + fichier JSONL avec rotation, écriture asynchrone non
  bloquante, tampon circulaire de breadcrumbs ;
- `CrashHandler` installé en première ligne du `Application.onCreate`,
  rapport JSON atomique, `CrashActivity` dans le processus `:crash` sans
  Hilt ni Room, détection de boucle de plantages, liaison avec la
  journalisation **par lambdas** (aucune dépendance de module) ;
- aucune donnée personnelle dans les journaux ni les rapports (identifiants
  uniquement, jamais de contenu de fichier ni de nom d'auteur).

## Build

Convention plugins dans `build-logic` (voir ADR 0007 pour la chaîne de
versions) : `codeide.kotlin.library`, `codeide.android.application`,
`codeide.android.library`, `codeide.android.feature`, `codeide.android.hilt`,
`codeide.android.room`, `codeide.module-rules`. Avertissements Kotlin en
erreurs, Lint strict sans ligne de base, detekt avec
`maxIssues: 0`, Spotless + ktlint, Kover avec seuil ≥ 80 % sur
`core:model`/`core:domain`.
