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
| `core:ui` | Thème Material 3, classes de base, composants réutilisables |
| `core:testing` | Fakes et utilitaires de test (testImplementation seulement) |
| `feature:onboarding` | Assistant de premier lancement |
| `feature:home` | Liste des projets |
| `feature:newproject` | Wizard de création de projet |
| `feature:settings` | Paramètres |
| `feature:diagnostics` | Visionneuse de journaux et rapports de plantage |
| `feature:editor` | Stub : futur espace de travail |

Chaque module possède un `README.md` (responsabilité, dépendances autorisées,
API prévue) et un `Module.md` (page Dokka).

## Règles de dépendance (section 5.2)

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
  Settings en fragments) ; écran de démarrage via l'API SplashScreen ; la
  destination initiale dépend de `isSetupCompleted`.
- `EditorActivity` (étape 13) : activité séparée, espace de travail lourd.
- `CrashActivity` (module `core:crash`) : **processus séparé** `:crash`.
- Les features ne se connaissent pas : navigation via l'interface
  `AppNavigator` (définie dans `core:ui`, implémentée dans `app`).

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
