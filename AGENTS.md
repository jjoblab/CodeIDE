# AGENTS.md — Repère pour les sessions futures

Ce fichier résume les règles de travail du projet CodeIDE pour qu'un agent
(ou un développeur) reprenne **sans perte de contexte**. Il est tenu à jour à
chaque étape. Le document de référence complet est le prompt maître
(« CodeIDE — Prompt maître pour agent IA », version 1.0), complété par le
**prompt compagnon** « EditorActivity, GitHub et bibliothèque d'édition »
(addendum fusionné le 2026-09-23 : publication GitHub, bibliothèque
`code-editor`, étapes 13 à 18 redéfinies) ; en cas de contradiction, le prompt
maître prime, sauf les points qu'il modifie explicitement.

## Rôle

Ingénieur Android/Kotlin senior. Reconstruire CodeIDE **de zéro** : base saine,
modulaire, testée, maintenable. Une étape à la fois, livraison validée par
l'utilisateur à chaque fin d'étape (« GO étape N+1 »).

## Paramètres du projet (ne pas changer sans accord)

- `applicationId` = **`jo.codeide`** (imposé).
- Kotlin 100 % (aucun Java écrit à la main) ; identifiants en anglais.
- **KDoc, commentaires, commits, documentation, messages d'erreur : français.**
- minSdk 26 ; compileSdk = dernière API stable (37.2 à ce jour) ;
  **targetSdk = 28, délibéré** (Android 10 interdit à une app ciblant 29+
  d'exécuter les binaires du bootstrap extraits dans `filesDir` — W^X ;
  ADR 0045, garde après retour d'appareil réel).
- UI : vues XML + ViewBinding, Activities + Fragments, Material 3.
  **Pas de Jetpack Compose** (ADR 0002).
- Langues de l'interface : français (`values/`, défaut) + anglais (`values-en/`).
- Stockage des projets : SAF (URI, jamais `File`) — ADR 0003.
- SemVer : `0.N.0` par étape validée, `0.N.M` par correction.
- Aucune permission `INTERNET` ni `MANAGE_EXTERNAL_STORAGE` en Phase 1.

## Identité Git (règle permanente — v0.34.2)

Toute écriture git — commit, tag annoté, merge — se fait
**exclusivement** sous l'identité du propriétaire du dépôt :

- `user.name` = `jjoblab`
- `user.email` = `olson12jb@gmail.com`

Avant de committer, vérifier la configuration ; si elle manque, la
poser au niveau du dépôt :

```bash
git config user.name "jjoblab"
git config user.email "olson12jb@gmail.com"
```

Aucune identité d'agent (« Agent CodeIDE », « Z User » ou autre) ni
d'adresse `noreply` GitHub ne doit jamais apparaître dans
l'historique : l'intégralité de celui-ci a été réécrite en v0.34.2
pour n'offrir que `jjoblab <olson12jb@gmail.com>` (auteurs,
committers et taggers de tags). Contrôle en fin de session :

```bash
git log --format='%an <%ae> | %cn <%ce>' | sort -u
git for-each-ref refs/tags --format='%(taggername) <%(taggeremail)>' | sort -u
```

→ chaque commande ne doit renvoyer que `jjoblab <olson12jb@gmail.com>`.

## Règles impératives (abrégées — section 3 du prompt maître)

1. Aucune logique métier dans Activity/Fragment/ViewModel : elle vit dans des
   use cases du domaine.
2. Règles de dépendance entre modules vérifiées par `./gradlew
   checkModuleDependencies` (le build échoue en cas de violation).
3. Aucune ressource en dur : tout passe par les ressources et les tokens.
4. Interdits : `!!`, `GlobalScope`, `runBlocking` (hors tests), `Thread.sleep`,
   `catch` qui avale l'erreur, `@Suppress` sans justification commentée.
5. I/O hors du thread principal ; dispatchers **injectés**
   (`DispatcherProvider`).
6. Erreurs attendues modélisées (`AppResult`/`AppError`), jamais des
   exceptions jusqu'à l'UI ; `CancellationException` toujours relancée.
7. Aucun secret ni `local.properties` dans le dépôt ni dans l'archive.
   Exception unique (ADR 0067) : l'identité debug PUBLIQUE
   `config/signature/debug.keystore` est versionnée — identifiants de
   convention Android, pas un secret, patrons AOSP/CodeAssist — pour que
   tous les APK debug (CI, contributeurs, machines locales) partagent la
   même signature. Les clés RELEASE restent interdites au dépôt
   (échelle hors dépôt : `keystore.properties` gitignoré → `-PRELEASE_*`
   → env `RELEASE_*`).
8. Aucune vérification désactivée pour faire passer le build (exceptions
   ciblées, minimales et **commentées** uniquement).
9. Dépendances justifiées ; versions uniquement via `gradle/libs.versions.toml`.
   **Ne jamais deviner un numéro de version** : le vérifier sur Google Maven,
   Maven Central ou le Plugin Portal.
10. Commits conventionnels en français (`feat(newproject): ajoute la validation du nom`).
11. Journalisation via `AppLogger` (implémentée étape 2 — v0.3.0). `android.util.Log`,
    `println`, `printStackTrace` interdits hors `core:logging` et `core:crash`
    (règle detekt active). **Aucune donnée personnelle dans les journaux ni les
    rapports de plantage** (expurgation `LogRedactor` à la construction).
    Voir `docs/JOURNALISATION_ET_PLANTAGES.md` et l'ADR 0009 (bornes, expurgation
    à l'écriture, DROP_OLDEST assumé).
12. Le gestionnaire de plantages (implémenté étape 3 — v0.4.0, ADR 0006 et 0010)
    ne doit jamais lui-même planter ni bloquer : `try/catch` global, garde de
    ré-entrance, délégation au système sur boucle ou échec, aucune injection
    sur le chemin critique, liaison avec `core:logging` par lambdas.

## Architecture (sections 5 et 6 du prompt)

Modules : `app`, `core:{model, domain, data, database, datastore, storage,
logging, crash, ui, testing}`, `feature:{onboarding, home, newproject,
settings, diagnostics, editor}`, `tools:{generateur}` (harnais de
vérification des modèles, hors application — ADR 0019). `core:model` et
`core:domain` sont des modules **Kotlin JVM purs** avec `explicitApi()`.

Tableau des dépendances autorisées : `docs/ARCHITECTURE.md`. Patron de
présentation : MVVM + flux unidirectionnel (UiState/Action/Effect via
StateFlow/Channel). Navigation inter-features via `AppNavigator` (interface
dans `core:ui`, implémentée dans `app`).

## Chaîne de build (vérifiée — voir ADR 0007 et docs/ENVIRONNEMENT.md)

- JDK Temurin 21, Gradle **9.7.1 via wrapper uniquement**, AGP **9.4.1**.
- **AGP 9 = Kotlin intégré** : ne PAS appliquer `org.jetbrains.kotlin.android` ;
  configurer via `kotlin { compilerOptions { } }`. kapt interdit → KSP 2.3.12.
- Kotlin **2.2.10** (embarqué par AGP 9.4.1 — ne pas monter à 2.4.x sans
  revalidation complète, les métadonnées compilées 2.4 sont illisibles par 2.2).
- kotlinx-serialization **1.9.0** (1.10+ exige Kotlin 2.3 — incompatible).
- Robolectric 4.17 exige `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`
  (déjà configuré dans les conventions) et `isIncludeAndroidResources = true`.
- Tests : JUnit 4 (choix du prompt), noms de test en français avec accents graves.
- Étape G2+ : `tooling:server` dépend de `org.gradle:gradle-tooling-api`
  (9.7.1, alignée sur le wrapper) via **`repo.gradle.org/gradle/libs-releases`**
  — les métadonnées Maven Central de cette coordonnée sont périmées (voir
  `docs/TOOLING.md`). Le JAR orchestrateur (7,6 Mo) est un artefact de build
  régénéré dans `app/src/main/assets/tooling/` par `preBuild` — jamais
  versionné ni archivé (ADR 0040).
- Étape 13+ : `feature:editor` dépend de `com.github.jjoblab:cel-ui` via
  **JitPack** (dépôt `maven { url = uri("https://jitpack.io") }` à ajouter —
  seule dépendance externe autorisée dans une feature, exception documentée ;
  accès réseau à `jitpack.io` vérifié le 2026-09-23).

## Commandes

```bash
source scripts/env.sh                      # JAVA_HOME, ANDROID_HOME, PATH

# VÉRIFICATION STANDARD (directive utilisateur du 2026-09-25, v0.31.1) :
# légère + assembleDebug, à chaque étape / correctif —
./gradlew spotlessCheck detekt            # hygiène (format + statique)
./gradlew :<module>:compileDebugKotlin …  # compilation des modules TOUCHÉS
./gradlew :<module>:testDebugUnitTest …   # tests des modules TOUCHÉS
                                           # (--max-workers=1 sur 4 Go)
./gradlew :app:assembleDebug              # TOUJOURS — l'APK est le produit
# koverVerify, lintDebug, checkModuleDependencies, tests des modules
# non touchés : la CI GitHub les exécute à chaque push (garantie
# from-scratch, ADR 0037) — la chaîne complète reste LA référence des
# audits de fin de phase :
#   ./gradlew spotlessCheck detekt checkModuleDependencies lintDebug \
#     testDebugUnitTest koverVerify assembleDebug   (SANS clean, ADR 0037)
#
# DIRECTIVE OBLIGATOIRE (v0.79.0, retour CI) : pour chaque commit, lancer
# la chaîne COMPLÈTE de vérification sur TOUS les modules touchés —
# pas seulement compileDebugKotlin. Les tests unitaires, lint, kover et
# Hilt doivent passer localement avant le push. La CI exécute la chaîne
# complète (spotlessCheck detekt checkModuleDependencies lintDebug
# testDebugUnitTest koverVerify assembleDebug) et tout échec bloque.
# En pratique : identifier les modules touchés (git diff --name-only),
# puis pour chacun lancer spotlessCheck detekt testDebugUnitTest
# lintDebug koverVerify (si applicable), puis :app:assembleDebug.
# Les changements de catalogues/constantes partagés (ToolchainCatalog,
# ToolManifest, etc.) peuvent casser des tests dans des modules non
# directement modifiés — toujours lancer les tests des modules qui
# référencent les constantes changées.
./gradlew spotlessApply                    # formatage avant commit
scripts/bump-version.sh minor|patch        # incrémente la version
                                            # (patch = correction après retour utilisateur)
git tag -a vX.Y.Z -m "vX.Y.Z"              # tag ANNOTÉ, jamais léger (Vérification-1 §2.5)
scripts/package.sh 0                       # dist/ : archive + APK + SHA256SUMS
scripts/verify-archive.sh dist/CodeIDE-v0.1.0-etape00.zip   # archive autonome ?
                                            # (GRADLE_USER_HOME isolé + daemon actif, Vérification-1 §2.2)
```

## Définition de « terminé » (par étape)

Fonctionnalités de l'étape sans débordement ; **vérification standard
verte** (directive 2026-09-25 : légère + `assembleDebug` — hygiène,
compilation et tests des modules touchés, APK assemblé ; la CI GitHub
garantit par ailleurs la chaîne complète à chaque push) ; tests de la
logique ajoutée (seuil Kover ≥ 80 % sur les modules qui en ont
une — `core:model`, `core:domain`, `core:bootstrap`,
`core:terminal-runtime`, `tooling:client`, `tooling:server` ; le domaine
reste le contrat nominal, les autres seuils suivent leurs build.gradle.kts) ; KDoc et
docs à jour ; `CHANGELOG.md`, `ROADMAP.md`, `AGENTS.md` à jour ; aucun TODO non
tracé ; version incrémentée, tag Git **annoté** (`git tag -a`, jamais léger),
archive créée **et vérifiée** ; commits et tag **poussés sur `origin`**
(`https://github.com/jjoblab/CodeIDE.git`, `git push origin main &&
git push origin vX.Y.Z` — chaque tag explicitement, `--follow-tags` ignore
silencieusement les tags légers : prompt Vérification-1, section 2.5) ;
rapport remis (format section 14, avec la **durée réelle** de chaque commande
de vérification — Vérification-1, section 2.4) puis attente du « GO ».

## État d'avancement

- [x] Étape 0 — Environnement, squelette, outillage → v0.1.0
- [x] Étape 1 — Fondations transverses → v0.2.0 (`core:model` AppResult/AppError/identifiants/
      StorageLocation, `core:domain` DispatcherProvider, `core:testing` MainDispatcherRule/
      TestDispatcherProvider, `core:ui` thème M3 complet + BaseFragment + composants d'état +
      insets + AppNavigator, `app` Hilt/SplashScreen/NavHost avec navigation Home ↔ Settings,
      features placeholder, ADR 0008)
- [x] Étape 2 — Journalisation → v0.3.0 (`core:model` LogLevel/LogEntry/FlattenedException sérialisables,
      `core:domain` AppLogger/LogRedactor/LogConfig/LogRepository + use cases, `core:logging` moteur asynchrone
      borné (canal DROP_OLDEST, groupement 500 ms, flush ERROR, flushBlocking), JSONL + rotation + rétention,
      breadcrumbs 200, export zip UTC + FileProvider cache/exports, en-tête de session, ADR 0009 ; app initialise
      dans le processus principal uniquement ; core:testing FakeAppLogger + InMemoryLogRepository)
- [x] Étape 3 — Gestion des plantages → v0.4.0 (`core:model` CrashType/CrashReport/CrashReportSummary/CrashAppInfo/DeviceInfo +
      FlattenedException.suppressed, `core:domain` CrashReportRepository/PendingExitInfoRecorder + use cases,
      `core:crash` CrashHandler (1re ligne d'onCreate avant Hilt, chaîné, budget ≤ 2 s, garde de ré-entrance),
      CrashReportFileStore (JSON org.json, atomique, réduction ≤ 256 Ko, rétention 20, témoin consulté),
      boucle 3/60 s, ExitInfoRecorder (ANR/natifs API 30+, dédoublonnés), CrashActivity processus :crash
      (LIVE/VIEW, copier/partager zip/enregistrer SAF/vider cache), FileProvider dédié ADR 0010 ; liaison
      core:logging par lambdas (breadcrumbs/flush) ; app sensible au processus, dialogue rapport non consulté,
      menu debug source set debug (no-op release) ; core:testing FakeCrashReportRepository + FakePendingExitInfoRecorder)
- [x] Étape 4 — Couche données → v0.5.0 (`core:model` Project/ProjectAccessState/AppSettings + ThemeMode/LogVerbosity/License,
      `core:domain` contrats FileSystem (13 opérations typées) + FileStat + ProjectRepository/SettingsRepository + ForbiddenFolders
      (dossiers refusés Android 11+, formes raw: ramenées au volume) + use cases du registre/de l'accès/des paramètres,
      `core:database` Room v1 (index unique document_uri, tri de l'accueil dans la requête, schéma exporté, mappeurs —
      public CodeIdeDatabase/ProjectDao/ProjectEntity), `core:datastore` SettingsDataStore (lecture tolérante champ par champ,
      corruption → défauts, trio de clés du dossier de travail, transformations atomiques, défauts par FLAG_DEBUGGABLE),
      `core:storage` SafFileSystem (DocumentsContract + requêtes groupées, pré-contrôle d'homonyme et contrôle du nom retourné,
      exceptions traduites, port PersistableUriPermissions testable, UrisDocuments) + fournisseur factice de test sur le vrai
      protocole d'appel vérifié sur le bytecode android-all, `core:data` ProjectRepositoryImpl (UUID + horodatage à l'ajout,
      SQLiteConstraintException → AlreadyExists) + SettingsRepositoryImpl (journalisation identifiants uniquement — règle 15),
      `core:logging` LogLevelApplier + correction de la config initiale release (ADR 0011), app branchement du niveau persisté
      au démarrage du processus principal + test d'intégration graphe de production ; core:testing FakeFileSystem +
      FakeProjectRepository + FakeSettingsRepository ; ADR 0012 : renommer = libellé en base uniquement)
- [x] Étape 5 — Onboarding → v0.6.0 (`feature:onboarding` : pager non swipable 5 pages + MaterialSharedAxis Z, OnboardingViewModel UDF complet
      (actions/effets), dossier de travail SAF avec dossiers refusés Android 11+ détectés avant permission + test d'écriture témoin + permission
      relâchée à tout échec + étape passable « Plus tard », apparence à aperçu immédiat (thème/dynamique/langue persistés à l'instant,
      setApplicationLocales ADR 0013 + locales_config), profil (SavedStateHandle, écrit en fin de parcours), `isSetupCompleted` routé sous splash
      en racine de pile ; feature:home bandeau « Configurer le dossier de travail » + HomeViewModel ; AppNavigator.openOnboarding/openHome)
- [x] Étape 6 — Paramètres → v0.7.0 (`feature:settings` écran personnalisé M3 piloté SettingsViewModel+DataStore : apparence/langue/projets/à propos/avancé,
      chaque réglage persisté à l'instant ; core:domain ValidateWorkspaceUseCase (validation dossier partagée avec l'assistant) + ChangeWorkspaceUseCase/
      ClearWorkspaceUseCase (ancienne permission libérée seulement si aucun projet n'en dépend) + ResetPreferencesUseCase (états conservés, ADR 0014) +
      port ArborescencesSaf (SafArborescences dans core:storage, FakeArborescencesSaf dans core:testing) ; onboarding délègue la validation au use case partagé ;
      navigation paramètres → assistant)
- [x] Étape 7 — Accueil → v0.8.0 (`feature:home` liste complète : HomeViewModel UDF (EtatAccueil/ActionAccueil/EffetAccueil, recherche à délai 250 ms + tri
      SavedStateHandle, états chargement/vide/sans résultat/erreur+réessai), ProjetsAccueilAdapter ListAdapter+DiffUtil (ligne = projet + état d'accès),
      statut d'accès recalculé à l'affichage/au tirer-relâcher/jamais persisté avec résolution Relocaliser/Retirer, actions par projet (ouvrir/renommer
      validé/épingler/retirer/supprimer du disque avec rappel du nom), FAB étendu Nouveau projet + Ouvrir un dossier existant, sw600dp 2 colonnes ;
      core:domain ImportExistingFolderUseCase + RelocalizeProjectUseCase (héritage de la permission du dossier de travail via
      ArborescencesSaf.uriDocumentDansArbre, sentinelle TemplateId.IMPORTED, ADR 0015) + DeleteProjectOnDiskUseCase + RemoveProjectUseCase enrichi
      (libération conditionnelle, ADR 0016) + aides partagées testerEcriture/libelleLisible/libererPermissionSiInutilisee ;
      ProjectRepository.updateLocation (Room + fake) ; feature:newproject placeholder ; AppNavigator.openNewProjectWizard ; ADR 0015-0016)
- [x] Étape 8 — Moteur de templates → v0.9.0 (`core:model` ProjectTemplate/TemplateParameter/TemplateOptions/TemplatePlan/TemplateSummary/CreationProgress +
      codeTemplate() ; `core:domain` package templates : TemplateAssetsSource (port) + GeneratorVersion, EmbeddedTemplatesProvider (multibinding @IntoSet,
      répertoires sans manifeste ignorés, manifeste invalide = échec explicite), TemplateManifestParser (validation complète : schéma, id=répertoire, SemVer,
      i18n, validateurs/defaultFrom enregistrés, expressions analysables, bornes), ExpressionParser **maison** (lexique + descente récursive, bornes
      512/128/16, curseur sûr en fin de flux — ADR 0018) + ExpressionEvaluator typé, TemplateRenderer ({{var|filtre}}, {{#if}}/{{#else}}, {{t:clé}},
      \\{{ , échec fichier+ligne, jamais de résiduel), TemplateFilters (10 filtres, slug replie les accents NFD), TemplateValidators (5 validateurs + regex:),
      TemplateDefaultFunctions (3 dérivées), TemplatePathGuard (garde après substitution : .., absolu, antislash, contrôle, réservés Windows, doublons
      insensible à la casse), TemplateEngine (évaluation formulaire 5 passes, contexte + options communes, plan figé complet — dry-run = écriture, ADR 0017,
      licence SPDX rendue, .codeide/project.json sans donnée personnelle) + use cases ListTemplates/ValidateProjectName/ValidatePackageName/
      EvaluateTemplateForm/PlanProjectCreation + TemplateProjectPlanner partagé + CreateProjectUseCase (progression, registre en dernier, rollback
      NonCancellable avec résidus, erreur typée relayée) ; `app` AssetTemplateAssetsSource (AssetManager + dispatchers, aucune traversée) +
      GeneratorVersionImpl (BuildConfig) + TemplatesModule (@Binds + @IntoSet) + assets/licenses/ SPDX officiels (mit, bsd-3-clause avec {{year}}/{{author}},
      apache-2.0, gpl-3.0) ; `core:testing` FakeTemplateAssetsSource ; fixture de test templates/fixture (hostile : guillemets, antislash, $, </project>,
      retours ligne, emojis, Unicode) ; docs/TEMPLATES.md (contrat concepteurs) ; ADR 0017-0018)
- [x] Étape 9 — Modèles Kotlin/Java → v0.10.0 (`app/src/main/assets/templates/{kotlin-jvm,java}` : manifestes déclaratifs à 9 paramètres partagés + 6 variables
      calculées + 16 fichiers par modèle (Greeter/Main/GreeterTest zéro avertissement, build Gradle + catalogue + wrapper sommé, pom Maven complet, README
      dynamique, gitignore/gitattributes/editorconfig) + i18n fr/en complètes ; `:tools:generateur` harnais JVM (ADR 0019, plan figé déversé) ;
      scripts/verify-templates.sh 18 combinaisons réelles vertes ; ModelesEmbarquesTest 192 combinaisons structurelles + hostiles + déterminisme ;
      ADR 0019, TEMPLATES.md enrichi)
- [x] Étape 10 — Wizard (partie 1) → v0.11.0 (`feature:newproject` complet : NewProjectFragment hôte [barre d'outils ✕, indicateur « Étape N sur M »,
      barre d'actions Retour/Suivant gardé par validité, dialogue d'abandon, transitions MaterialSharedAxis X coupées si animations réduites, sw600dp borné],
      WizardViewModel scopé à l'hôte [ADR 0020 : SavedStateHandle — rotation + mort du processus, liste configurable WizardStep], étapes = fragments enfants
      sans état propre ; EtapeModeleFragment [grille de cartes sélectionnables, monogramme i18n, recherche masquée sous 4 modèles], EtapeConfigurationFragment
      + EtapeInformationsFragment [rendu dynamique RenduParametres, ADR 0021 : tuiles segmentées/cartes radio/liste déroulante/interrupteurs/champs dérivés
      resynchronisables, puces récapitulatives en direct], carte d'emplacement [ADR 0022 : dossier éphémère « pour cette création uniquement »,
      héritage arbre de travail, vérifications asynchrones avec délai 400 ms : permission/joignabilité/collision insensible à la casse] ;
      core:model RaisonValidation fermé + TemplateParameterEvaluation élargi (type/choices/derived/section/errorReason) ; core:domain
      EvaluerNomProjetUseCase + CreationLocationUseCases [Resolve/Release/VerifyCreationTarget] + TemplateValidators → échecs structurés
      raison typée + message ; core:ui SimpleTextWatcher + style TextField.Dropdown ; 60 nouveaux tests ; ADR 0020-0022)
- [x] Étape 11 — Wizard (partie 2) → v0.12.0 (étapes 4 Fichiers [options communes TemplateOptions : README/.gitignore/.editorconfig, licence
      pré-remplie auteur+année, langue du contenu FR/EN], 5 Récapitulatif [résumé par section + « Modifier », arborescence sèche repliable
      Arborescence.kt] ; écran de création dans le wizard piloté par EtatCreation [ADR 0023] : progression, annulation = rollback domaine,
      succès/échec typés ; CreateProjectRequest branché de bout en bout ; bouton « Créer le projet » ; mise en évidence à l'accueil via
      AppNavigator [ADR 0024 : contour + défilement, identifiant dans la pile de retour] ; correctifs insets edge-to-edge onboarding/paramètres/
      plantage + bouton debug non obstructif ; ADR 0023-0024)
- [x] Étape 12 — Diagnostic → v0.13.0 (`feature:diagnostics` complet : onglet Journaux [fenêtre 500 + pagination mémoire, filtres, recherche à délai, suivi direct, partage/enregistrement/effacement,
      réglage Normal/Détaillé persisté puis appliqué via le port LogVerbosityApplier], onglet Plantages [liste vivante, ouverture VIEW, suppression, export], section Informations, menu debug
      déplacé depuis MainActivity ; domaine : ReadAllLogs/MeasureLogDiskUsage/SetLogVerbosity/ExportCrashReports + LogExportWriter.write direct SAF ; AppNavigator.openDiagnostics + partagerArchive
      [FileProvider confinement cache/exports] ; ADR 0025)
- [x] Étape 13 — Fondations de l'espace de travail → v0.14.0 (EditorActivity trois zones sans logique [tiroir permanent sw600dp+ — ADR 0026, zone centrale à états vides, panneau inférieur replié
      Console/Problèmes/Journal], navigation openEditor par-dessus la pile depuis l'accueil et le succès du wizard [lastOpenedAt marqué avant], EditorViewModel suit le registre via
      SavedStateHandle, cel-ui 3.37.0 via JitPack [exception documentée + règles ProGuard, résolution vérifiée] ; domaine : ObserveProjectUseCase ; ADR 0026)
- [x] Étape 14 — Explorateur de fichiers → v0.15.0 (arborescence paresseuse `ExplorateurAdapter` + cache ViewModel [ADR 0027], tri dossiers/fichiers/alpha,
  icônes `core:ui` `IconesFichiers`, bandeau `ProjectAccessState` [résolution à l'accueil], Actualiser, barre basse Explorateur/Recherche/Git désactivées,
  fixes appareil réel : extension canonique SAF [témoin + `mimePour` sans point + tolérance] et bouton Terminer de l'onboarding [PageSuivante finalise])
- [x] Étape 15 — Intégration de l'éditeur et onglets de fichiers → v0.16.0 (sessions EditorSession au ViewModel [SessionSuivie testée],
      un seul EditorView rebranché + thème clair/sombre, TabLayout dynamique [menu contextuel complet, point de modification],
      sauvegarde auto debounce + manuelle [Mutex par fichier], confirmation de fermeture agrégée avec auto-sauvegarde suspendue,
      binaires → Ouvrir avec, mort du processus → onglets rouverts ; ADR 0028)
- [x] Étape 16 — Panneau inférieur → v0.17.0 (BottomSheetBehavior trois états [replié/mi-hauteur/étendu, fitToContents=false,
      halfExpandedRatio 0,5] piloté par l'état du ViewModel — transitions stabilisées seulement, en-tête poignée/titre/badge/agrandir/réduire,
      onglet Journal applicatif compact [fenêtre mémoire ObserveLogsUseCase 200, filtres par niveau persistés, suivi direct, badge de compte,
      lien vers l'écran Diagnostic via AppNavigator], onglets Sortie et Problèmes en stub explicite [session.setDiagnostics documenté non câblé],
      retour système réduit le panneau étendu d'abord, état + onglet actif survivent à la rotation ; PanneauEditorViewModelTest ; ADR 0029)
- [x] Étape 17 — Actions du tiroir et finitions de l'espace de travail → v0.18.0 (menu contextuel de l'explorateur [créer/renommer/supprimer/actualiser]
      + création à la racine par bouton dédié [fichier créé ouvert en onglet] ; validation partagée wizard [validateur file-name + EvaluerNomFichierUseCase] ;
      FileSystem.rename [nouvelle URI retournée] avec migration d'onglet [session/verrou/auto-sauvegarde] ; suppression ferme l'onglet et libère ;
      reprise par projet [.codeide/local/workspace-state.json, tolérante, gitignore des modèles déjà présent] ; accessibilité onglets + E32-E39 ; ADR 0030)
- [x] Étape 18 — Audit final de Phase 1 → v0.19.0 (reconnaissance du type de projet à l'ouverture
      [.codeide/project.json, ReconnaitreTypeProjetUseCase tolérant, nom i18n catalogue + repli identifiant, PreciserLangue re-résout, ADR 0031] ;
      correctif plantage d'ouverture de l'espace [<menu> inline du tiroir → res/menu/menu_tiroir.xml + app:menu,
      régression Robolectric gonflant le vrai activity_editor.xml — rapport 8b5b73f1] ; assembleRelease R8 vert
      [règles ProGuard cel-ui vérifiées sur APK minifié, E44] ; Dokka core:model/core:domain ;
      audit dépendances [6 entrées retirées] / TODO [aucun] / code mort [detekt strict vert] / données personnelles [LogRedactor, aucune fuite] ;
      verify-templates.sh vert ; plan détaillé Phase 2 dans ROADMAP)
- Phase 1 terminée — v0.19.0. Phase 2 ouverte par le **terminal intégré**
  (prompt Terminal-1) à la demande de l'utilisateur.
- [x] Étape 19 (= Terminal T1) — `core:bootstrap` : localisation et environnement → v0.20.0
      (ports ToolchainLocator [13 méthodes, périmètre exact du prompt] et ProcessEnvironmentProvider dans
      core:domain ; disposition Termux filesDir/usr + filesDir/home ; JDK usr/lib/jvm/… du dépôt APT
      [constaté sur Contents-aarch64] ; distribution Gradle = marqueur lib/gradle-launcher-*.jar,
      symlink bin/gradle remonté seulement si la canonicalisation change ; SDK Android par plateformes
      android.jar ; aapt2 = bit d'exécution ; cache wrapper ~/.gradle/wrapper/dists ; shell bash/sh ;
      environnement : retrait CLASSPATH/LD_PRELOAD, HOME/TMPDIR/PREFIX/LANG/LD_LIBRARY_PATH,
      GRADLE_USER_HOME explicite [bug getpwuid], PATH JAVA_HOME+prefix+hérité, exports conditionnels ;
      37 tests JVM pur rejouant les bugs historiques ; règle de dépendance :core:bootstrap ajoutée à
      checkModuleDependencies ; ADR 0032 [exception bornée à l'ADR 0003 : File du stockage privé] ;
      module non encore référencé par app — branchement en T3/T4 ; signalé : ni paquet gradle ni
      android-sdk dans le dépôt APT à ce jour, seul bootstrap-aarch64.zip en release)
- [x] Étape 20 (= Terminal T2) — lanceur de sous-processus et installateur → v0.21.0
      (ports NativeProcessLauncher/ManagedProcess + BootstrapInstaller + BootstrapAssetsSource dans
      core:domain ; EtapeInstallation/EtatInstallationBootstrap/OutilResume + AppError.Bootstrap [9 raisons]
      dans core:model ; installateur pipeline coroutine à StateFlow partagé : espace disque ≥ 1 Gio,
      architecture aarch64 [erreur typée], téléchargement HttpURLConnection + empreinte SHA-256
      [release bootstrap-2026.08.14-r3 épinglée], extraction usr-staging + permissions 0700 + liens
      SYMLINKS.txt [séparateur « ← », sans fermer le flux], bascule atomique avec remplacement,
      second stage via lanceur [chemin réel etc/termux/termux-bootstrap/second-stage/…, drainage
      parallèle des deux tuyaux], sources.list atomique avec [trusted=yes] [ligne embarquée corrigée],
      apt update + paquets un à un avec OutilResume ; Aapt2Deployeur [asset absent = erreur typée] ;
      fakes core:testing [FakeToolchainLocator, FakeProcessEnvironmentProvider, FakeNativeProcessLauncher
      + ProcessusScripte, FakeBootstrapInstaller] ; 49 tests dont bout en bout sous Robolectric avec
      faux serveur HTTP et vraie archive ; traducteurs AppError étendus [accueil/wizard/diagnostic] ;
      ADR 0033 ; INTERNET toujours absent — ajout et branchement app en T3 ; signalé : aapt2 absent des
      assets et du dépôt APT [paquet aapt existe en amont, non publié])
- [x] Étape 21 (= Terminal T3) — écran d'installation + onboarding → v0.22.0
      (feature:install : écran autonome à état partagé du port BootstrapInstaller [traduction pure,
      progression bornée, erreurs actionnables, annulation] ; AppNavigator.openBootstrapInstall +
      destination installation + garde double-toucher ; onboarding : étape Terminal insérée entre
      Dossier et Apparence [six pages, jamais bloquante, Plus tard → bandeau, revérification au
      retour d'écran, scission onAction→onActionTerminal→onActionSaisie pour detekt] ; accueil :
      bandeau terminal piloté par combine[paramètres, état d'installation] — disparaît sans repasser
      par l'accueil ; INTERNET + ADR 0034 ; app branche core:bootstrap + AssetsBootstrapSource
      [AssetManager, absence aapt2 traduite null→AssetAbsent] ; ViewBinding include nullable :
      appels sûrs, jamais !! ; tests : InstallViewModelTest [11], onboarding +4 et parcours six
      pages, home constructeur enrichi)
- [x] Étape 22 (= Terminal T4) — `core:terminal-runtime` → v0.23.0
      (registre global `RegistreSessionsTermux` singleton Hilt servant les deux ports —
      `TerminalSessionRepository` [domaine, métadonnées sans type Termux] et `TerminalRuntime.sessionFor`
      [vraie session Termux, réservé au rendu] ; création via constructeur Termux avec environnement canonique
      + `TERM=xterm-256color`, UUID + « Session N » ; throttle 250 ms / aperçu 160 caractères replatés ;
      terminaison naturelle visible morte ; `TerminalService` foreground `specialUse` [sous-type documenté,
      START_STICKY, arrêt de soi-même sans vivante] à décision pure `DecisionServiceTerminal` ; indirection
      `CoquilleSession`/`FabriqueCoquilles` → coquilles scriptées dans les tests, aucun pty réel ;
      `FakeTerminalSessionRepository` dans core:testing ; THIRD_PARTY_NOTICES créé [terminal-emulator
      Apache-2.0, termux-shared REFUSÉ — exceptions MIT ne couvrent pas extrakeys, clavier T5 interne] ;
      app branche :core:terminal-runtime [agrégation Hilt + fusion manifeste service] ; 19 tests module [registre 13,
      service réel Robolectric 3 : arrêt automatique/notification persistante/démarreur, décision 3] ; filtres kover
      documentés [colle Termux/JNI + code généré Hilt/Dagger] ; exception lint Aligned16KB [libtermux.so amont
      non alignée, vérifié v0.118.3 ET v0.119.0-beta.3 : p_align 4096])
- [x] Étape 23 (= Terminal T5) — `feature:terminal` écran plein écran → v0.24.0
      (TerminalActivity section 5.1 : toolbar + nouvelle session, onglets TabLayout à vues personnalisées
      [pastille d'état, libellé, fermeture « + », appui long renommer/dupliquer/fermer], UN SEUL TerminalView
      rebranché par identifiant de session active — jamais un rendu par onglet ; état vide ; clavier étendu
      INTERNE ClavierEtenduView [termux-shared refusé GPLv3, ADR 0035/0036] déclaratif : Tab/Échap/flèches
      par séquences, Ctrl/Alt bascules persistantes lues par readControlKey/readAltKey [mécanisme officiel
      Termux] ; ClientVueTerminal : toucher = focus+IME, retour jamais mappé sur Échap, logs muets ;
      fermeture à heuristique « au prompt » [dialogue si occupée] ; thèmes clair/sombre par couleurs de
      l'émulateur [indices 256/257/258, disposition jackpal 259] ; réglage dédié TaillePoliceTerminal
      [modèle + DataStore + Paramètres/Apparence + setTextSize gardé] ; AppNavigator.openTerminal
      [extra intent → SavedStateHandle, survit rotation] ; app branche feature:terminal ; 11 tests ViewModel ;
      POM terminal-view sans dépendance émulateur → les 2 artefacts déclarés ; Aligned16KB même exception)
- [x] Étape 24 (= Terminal T6) — intégration accueil et tiroir → v0.25.0
      (action « Terminal » dans la toolbar de l'accueil [ic_terminal core:ui, téléphone + sw600dp] :
      openTerminal(null) si bootstrap installé SINON openBootstrapInstall — jamais un terminal non
      fonctionnel, décision au ViewModel [ActionAccueil.OuvrirTerminal → 2 effets typés], état
      bootstrapInstalle ; carte d'aperçu du tiroir : 4e destination « Terminal » ACTIVE de la barre
      basse, compteur pluriel de sessions actives, libellé + dernière sortie monospace + pastille
      vivante/terminée de la session active, état vide « Nouvelle session dans ce projet » qui
      CRÉE la session dans le dossier réel puis ouvre l'écran plein écran dessus, garde-fou
      « Installer les outils » si bootstrap absent ; mise à jour EN DIRECT via
      TerminalSessionRepository [core:domain] — AUCUNE dépendance ajoutée à feature:editor ;
      pont SAF → FUSE ResoudreRepertoireProjet [core:domain : ExternalStorageProvider, primary →
      /storage/emulated/0, UUID amovibles, anti-traversée . / .. / vides rejetés, garde répertoire
      fantôme via isDirectory, pure fonction JVM 15 tests + garde-fous] réutilisable par le futur
      tooling ; CORRECTIF plantage InstallFragment [rapport 30e81ee0 : @AndroidEntryPoint manquant
      → NoSuchMethodException <init> [] SUR APPAREIL SEULEMENT, test de régression par réflexion
      InstallFragmentHiltTest] ; CI GitHub Actions .github/workflows/ci.yml [push main+tags,
      PR, manuel : JDK 21 Temurin, setup-gradle cache, licences SDK, chaîne complète SANS clean,
      APK debug en artefact, rapports seulement si échec] ; ADR 0037 [CI + procédure sans clean,
      mesures detekt empiriques] ADR 0038 [carte + pont FUSE] ; 3 tests HomeViewModel + 8 tests
      CarteTerminalEditorViewModelTest [4 états de la carte + effets, FakeTerminalSessionRepository
      — zéro dépendance Termux nécessaire] ; test Robolectric menu tiroir → 4 destinations)
- [x] Étape 25 (= Tooling G1) — tooling:protocol + tooling:testing → v0.26.0
      (lancé à la demande de l'utilisateur juste après T6 — T7 absorbé par l'audit G6) :
      framing FrameCodec [4 octets BE octet par octet — PIÈGE OutputStream.write(Int) n'écrit
      QUE l'octet de poids faible, attrapé par les tests ; garde DoS 16 Mo rejet AVANT allocation,
      troncatures typées avec diagnostic précis, EOF propre distinguée de la corruption] ;
      catalogue 24 messages [9 requêtes + 15 événements, ErrorCode typé, @SerialName + discriminant
      « type »] ; ProtocolJson [ignoreUnknownKeys éprouvé par golden enrichi d'un champ du futur] ;
      24 FICHIERS DORÉS commis — le format câble est un contrat, renommer/retirer un champ fait
      échouer le build [double test : décodage depuis le doré + adéquation sémantique JsonElement] ;
      tooling:testing [4 fixtures Gradle réelles en ressources : minimal-java, erreur-compilation,
      multi-module, tache-longue bornée -PdureeMs ; FixturesGradle.copier en temporaire — JAMAIS
      construites en place] ; règles de dépendance tooling gelées dans ModuleRulesPlugin [protocol
      sans dépendance interne, testing en config de test] ; versions VÉRIFIÉES dans docs/TOOLING.md
      [Tooling API 9.7.1 sur repo.gradle.org — Maven Central PÉRIMÉ sur cette coordonnée ; daemon
      Java 17 min = bootstrap openjdk-17 ✓ ; com.gradleup.shadow 9.6.1] ; 21 tests bloquants §3 au
      vert AVANT toute ligne server/client ; ADR 0039)
- [x] Étape 26 (= Tooling G2) — tooling:api + tooling:server → v0.27.0
      (`tooling:api` modèles partagés + mappers protocol → api ; `tooling:server`
      orchestrateur Tooling API complet : handshake secret/version, dispatcher borné,
      bus d'événements sans perte (file bloquante 8192), builds avec annulation +
      sortie ligne à ligne, Resilient Sync, tâches/dépendances/modèle, HeapMonitor,
      timeouts §7.5 ; fat jar shadow 9.6.1 → gradle-server.jar dans les assets
      contrôlé par preBuild §4.7 ; 15 tests d'intégration RÉELS sur vrai socket
      Unix contre les fixtures — annulation d'une tâche de 60 s en ~2,6 s ; kover
      ≥ 80 % ; correctif plantage 3d8ede67 + prompt Vérification-1 appliqué.
      ADR 0040)
- [x] Étape 27 (= Tooling G3) — tooling:client → v0.28.0
      (port `GradleToolingRepository` dans core:domain — zéro type tooling,
      règle §2.2 ; `GradleSocketServer` namespace FICHIER
      [`LocalSocket.bind(FILESYSTEM)` + `LocalServerSocket(FileDescriptor)`,
      tout public API 8 — le client JDK 17 ne joint que des chemins de
      fichiers] ; `HandshakeApp` validé AVANT tout handler §4.4 ; `GradleApiImpl`
      corrélation par promesses + canaux bornés 4096 par build à envoi
      suspendant [tampon pré-abonnement, rejouable après `BuildFinished`] ;
      écho d'identifiant corrigé côté serveur [corrélation §3.2 — défaut G2
      découvert en écrivant le client] ; `AppError.Tooling` typé jusqu'à
      l'UI ; 19 tests dont non-conflation 12 000 lignes §7.3. ADR 0041)
- [x] Étape 28 (= Tooling G4) — tooling:daemon → v0.29.0
      (module Android+Hilt sur l'allow-list gelée G1 [protocol, domain,
      client] ; `DaemonManager` : déploiement → écoute AVANT lancement §5.1 →
      lancement `java -Xmx256m -jar` sur le port `NativeProcessLauncher`
      JAMAIS redéfini [environnement canonique core:bootstrap] → handshake
      secret frais [SecureRandom 32 octets/tentative, jamais écrit] →
      surveillance [ping/pong 5 s/15 s — repère `dernierPongMs` tenu par le
      pompe du client, stderr/stdout → journal tag `gradle-server`] →
      relance bornée 5 tentatives [repli exponentiel 1 s→10 s, épuisement →
      ECHOUEE] ; échecs DÉFINITIFS : handshake refusé, code 2, JAR absent ;
      JDK absent = DECONNECTEE sans lancement + re-déclenchement à
      l'installation du bootstrap [BootstrapInstaller.etat → Terminee] ;
      `JarDeployer` marqueur SHA-256, copie atomique, recopie seulement au
      changement ; coutures publiques dans tooling:client
      [marquerEnConnexion/marquerEchouee — ADR 0041 décision 8 — et
      GradleSocketServer/SessionTooling/EchecHandshakeClient publics] ;
      démarrage au processus principal, mort de l'app = EOF → arrêt SEUL du
      process [code 0, aucun orphelin] ; **BOUT-EN-BOUT RÉEL §7.4** : VRAI
      sous-processus java [ServerMain par classpath — l'artefact shadowJar
      n'existe pas en JVM de test], VRAI socket Unix JDK, VRAI build Gradle
      sur fixture [connexion, pong, sortie ligne à ligne, REUSSI, arrêt
      propre] ; exception ModuleRules : tooling:server en test SEULEMENT
      depuis tooling:daemon ; 13 tests + kover ≥ 80 %. ADR 0042)
- Étape 29 (v0.30.0, G5) : la boucle UI du tooling se referme
      [producteur serveur : `ParseurDiagnostics` sur stderr ligne à ligne
      — javac `f:l[:c]: error:` et kotlinc `e: file://f:l:c`, ligne sans
      position complète ignorée, observateur de `StreamingOutputStream`
      branché par `BuildHandler`, événements `Diagnostic` du protocole
      G1 ; use cases domaine SynchroniserProjet/ExecuterTaches/
      AnnulerBuild/ListerTachesProjet [délégations pures au port §2.2,
      dossier résolu par l'APPELANT via ResoudreRepertoireProjet — une
      seule source de vérité T6] ; `GradleService` détenteur d'état pur
      [fenêtre de sortie BORNÉE 2 000 lignes — la sortie complète vit
      dans le canal rejouable du client, ADR 0041] ; onglet Sortie
      [auto-défilement tant que la fenêtre grandit, statut sync+build en
      en-tête, bouton Arrêter en vol] ; onglet Problèmes [groupes par
      fichier repliés, saut scrollToLine + curseur] ; diagnostics
      inline `session.setDiagnostics` [point d'ancrage ADR 0029,
      appariement par SUFFIXE de chemin relatif — le dossier FUSE peut
      être encore inconnu, sévérités 1/2/3 cel-ui, offsets bornés au
      document] ; actions toolbar Synchroniser/Exécuter + sélecteur de
      tâches [Effet → dialogue, exécution au choix] ; 23 tests +
      intégration serveur étendue (16) — ADR 0043]
- Étape 30 (v0.31.0, G6) : robustesse éprouvée au chaos RÉEL et audit
      final [chaos sur le harnais bout-en-bout rendu `internal` +
      instrumenté (registre des process, dernière session) : kill -9 en
      plein build long → build EN COURS conclu ECHOUE « connexion
      perdue » + canal fermé [correctif `rompreBuildsEnCours()` — le
      trou a été TROUVÉ par le chaos : l'onglet Sortie pendait à
      jamais], daemon relance borné, connexion remonte, aucun orphelin ;
      socket perdue côté app → process sort SEUL code 0 avant le health
      check ; version/JDK déjà prouvés] ; délais §7.5 inventoriés aux
      deux frontières [déjà posés G2/G3 — documentés en table dans
      docs/TOOLING.md, rien réécrit] ; docs/TOOLING.md FINAL
      [architecture livrée + schéma, table des délais, tableau du chaos
      à six pannes, journalisation gradle-server, garantie CI] ; audit
      [zéro TODO, detekt strict vert, ServerVersion inchangée 0.30.0 —
      G6 ne redélivre pas l'orchestrateur] ; points T7 appareil
      [targetSdk, LeakCanary] explicitement différés — 2 tests chaos
      réels + 1 test client, ADR 0044]
- v0.31.1 : **premier lot de corrections d'appareil réel** (rapport
      7842f130, moto g06 / Android 15) — plantage de l'écran Terminal
      [`ClavierEtenduView` : liste de touches déclarée APRÈS le bloc init
      qui l'itère — ordre d'initialisation Kotlin, NPE à l'inflation,
      test de régression `ActivityTerminalLayoutTest` gonfle le vrai
      layout] ; bootstrap « erreur inattendue » après extraction
      [**targetSdk 28** — W^X : Android 10 interdit l'exécution des
      binaires extraits dans `filesDir` aux apps ciblant 29+ ; ADR 0045,
      IOException du second stage typée `PermissionRefusee`] ; marqueur
      d'installation terminée [`bootstrapInstalle` exige le shell ET le
      marqueur déposé en fin de pipeline — un préfixe extrait n'est plus
      « installé »] ; création de projet honnête [l'échec de `createFile`
      remonte sa raison RÉELLE — toute défaillance passait « un dossier
      porte déjà ce nom » ; `SafFileSystem.creer` : pré-vérification dans
      le try + vérification illisible non destructive] ; directive
      utilisateur : **vérification standard = légère + assembleDebug**
      (§ Commandes)]
- v0.31.2 : **deuxième lot de corrections d'appareil réel** (rapport
      511e1c7f, moto g06 / Android 15) — plantage du terminal à la
      première session [`TerminalSession` (Termux) crée un `Handler`
      dans son constructeur : thread principal EXIGÉ ; création de la
      coquille via `withContext(dispatchers.main)`,
      `kotlinx-coroutines-android` ajouté à `core:terminal-runtime`,
      test au dispatcher instrumenté ; ADR 0046] ; installation sous
      surveillance [port `BootstrapInstaller.journal` : lignes d'étapes
      + sortie RÉELLE stdout/stderr du second stage et d'apt au fil de
      l'eau (`SupervisionProcessus` consommateur), écran refondu :
      checklist des 9 étapes, compteurs octets/fichiers/paquet, console
      en direct conservée à l'échec, détails techniques dépliables —
      « la configuration des paquets a échoué » ne sera plus muette ;
      installateur journalisé tag `Installateur`] ; page onboarding
      « Notifications et stockage » [`POST_NOTIFICATIONS` demandé sur
      Android 13+ avec repli réglages, état réel relu au retour ;
      documenté : AUCUNE permission de stockage nécessaire — SAF +
      stockage privé, ADR 0046] ; ADR 0046]
- v0.31.3 : **troisième lot de corrections d'appareil réel** (`apt
      update` code 100 : `mkstemp $PREFIX/tmp/… ENOENT`) — répertoire
      `tmp` du préfixe GARANTI [l'archive publiée par `codeide-packages`
      n'embarque pas l'entrée `tmp/` (280 répertoires côté bootstrap
      officiel Termux dont `tmp/`, 107 sans côté publié) : créé à
      l'extraction (test de régression) ET recréé à chaque environnement
      de sous-processus (`assurerRepertoiresProcessus`, défense en
      profondeur — couvre aussi `rm -rf $PREFIX/tmp`, panne documentée
      par la FAQ Termux) ; errno 2 = ENOENT n'est PAS un refus de
      permission (errno 13) ; ADR 0047] ; stockage partagé OPT-IN pour
      le terminal [trio READ/WRITE + `MANAGE_EXTERNAL_STORAGE` au
      manifeste, `requestLegacyExternalStorage`, section facultative de
      la page Notifications : requête runtime sous Android 11, réglage
      « Tous les fichiers » au-delà, état réel relu au retour, jamais
      exigé — répond à la demande utilisateur dans le cadre des ADR
      0003/0034 ; ADR 0047] ; CI réparée [`ExpiredTargetSdkVersion`
      (ERREUR) rejoint `ExpiringTargetSdkVersion` (avertissement) dans
      les désactivations lint — DEUX issues distinctes, la v0.31.1
      n'avait couvert que la seconde, ADR 0047] ; chaînes EN de la page
      Notifications comblées
- v0.31.4 : **quatrième lot de corrections d'appareil réel** (rapport
      `f2699ac5` : retour depuis le terminal → conteneur de navigation
      introuvable) — navigateur robuste hors graphe [`@ActivityScoped`
      lie `AppNavigatorImpl` à l'activité qui l'INJECTE : injecté par
      `TerminalActivity` (sans conteneur), chaque flèche retour
      plantait ; `goBack()` referme désormais l'activité plein écran,
      les navigations vers le graphe depuis terminal/éditeur relaient
      `MainActivity` `singleTop` avec routage `EXTRA_ECRAN_CIBLE` +
      `REORDER_TO_FRONT` (onNewIntent sans recréation, appelante
      conservée dessous) — 3 chemins latents corrigés au passage dont
      la carte Terminal de l'éditeur ; ADR 0048] ; écran d'installation
      réparé puis refondu [journal combiné à l'état dans UN flux (il
      s'effaçait à chaque étape) + boutons terminaux enfin rendus
      (« Fermer »/« Réessayer » invisibles depuis v0.31.2) + deux
      sections : base (huit étapes) / outils (statuts par paquet) ;
      ADR 0048] ; outils de développement OPTIONNELS et différés
      [demande utilisateur : `apt update` obligatoire en fin de
      première configuration, `openjdk`/`git` installés à la demande
      (`installerOutils()`, état `OutilsEchoues` base conservée,
      redémarrage à `Terminee` si marqueur) ; garde `isJdkInstalled()`
      dans l'éditeur AVANT sync/build : message actionnable au lieu
      d'une connexion perdue ; ADR 0048]
- v0.31.5 : **cinquième lot de corrections d'appareil réel** (terminal
      « pas à jour immédiatement »/pinch-zoom inerte/onglets
      inopérants, création « un dossier porte déjà ce nom — toutes mes
      tentatives sont vaines », CI lint rouge sur `feature:install`)
      — terminal vivant [signal de repeint IMMÉDIAT
      `TerminalRuntime.observeSorties()` (tampon 1, dernier gagnant)
      collecté par l'activité → `onScreenUpdated()` : dans l'architecture
      Termux c'est le client de session de l'ACTIVITÉ qui repeint, ici
      personne ne le faisait ; zoom pincé APPLIQUÉ par le client (le
      bytecode v0.118.3 accumule le facteur et ne l'applique JAMAIS
      lui-même — bornes 10–30 dp, consommé comme Termux, drapeau
      `zoomManuel` anti-écrasement) ; onglets par DIFF (plus de
      `removeAllTabs` toutes les 250 ms — les taps atterrissaient sur des
      vues détruites sous le doigt) et session créée TOUJOURS active ;
      ADR 0049] ; création de projet honnête jusqu'au bout [PRÉ-VOL à
      l'appui sur « Créer » : la cible est re-vérifiée avant toute
      écriture (l'état « Valide » de l'étape Informations, délai
      400 ms, peut être périmé) ; `addProject` relaye son erreur
      RÉELLE (déjà référencé = AlreadyExists) au lieu d'un Io
      générique ; `SafFileSystem` tolère la normalisation
      fournisseur des espaces/points finaux ; écran d'échec : détails
      techniques VISIBLES + bouton « Changer de nom ou
      d'emplacement » (sortie du piège « Réessayer » en boucle) ;
      ADR 0049] ; lint CI réparé à la source [`NestedScrollView` du
      journal, `<plurals>` d'extraction, indice « n/total » du paquet,
      `toUri()` dans l'onboarding — et leçon : `lintDebug` local doit
      couvrir TOUS les modules touchés, pas seulement `:app` ; ADR 0049]
- v0.31.6 : **sixième lot de corrections d'appareil réel** (rapport
      4a4526aa : création « le dossier créé a été supprimé,
      .gitattributes » — l'échec `AlreadyExists` se fabriquait PENDANT
      l'écriture, le pré-vol de v0.31.5 était vert ; plantage
      `NullPointerException: bouton_fermer_session` de l'écran Terminal
      à la première session) — fichiers cachés SAF [leçon : le point
      INITIAL d'un nom n'est PAS une extension — ni pour le fournisseur
      (« .gitattributes » + `text/plain` → « .gitattributes.txt »),
      ni pour nos contrôles (`contains('.')` voyait une extension) ;
      le premier fichier du plan des modèles JVM déclenchait un
      « renommage hostile » de pure invention → fichier créé SUPPRIMÉ +
      `AlreadyExists` + rollback → « un dossier porte déjà ce nom »
      AUCUN nom ne pouvait marcher ; règle partagée
      `mimeFichierTexte`/`sansExtensionReelle` (`core:domain`) : nom
      sans extension réelle → type privé `text/x-codeide` sans
      complétion, filet `estAchevementExtension` élargi aux cachés,
      l'éditeur suit la même règle ; ADR 0050] ; onglets du terminal
      [leçon : quand la liste de sessions GRANDIT, la position visée
      par le diff est occupée par le « + » (zéro session → « + » seul
      en 0) — binder sa vue (un `ImageView`) en `VueOngletSessionBinding`
      plantait ; la décision « bordable » exclut désormais le « + » par
      IDENTITÉ de tab (`vueOngletSessionBordable`, testable sur le vrai
      `TabLayout`) ; ADR 0050]
- v0.31.7 : **septième lot de corrections d'appareil réel** (suite du
      rapport 4a4526aa : `Storage(AlreadyExists, details=README.md)` —
      l'écran d'échec v0.31.6 a fait son travail, le piège
      `.gitattributes` est corrigé et l'échec a PROGRESSÉ au fichier
      suivant ; « j'appuie sur le tab layout l'onglet pour changer de
      session, rien ne se passe ») — création de projet [leçons : (1)
      la complétion d'extension SAF frappe AUSSI les noms AVEC
      extension — la table système `MimeTypeMap` ne connaît pas
      `md`/`kts`/`kt`/`properties`/`pro` (elle varie par version et
      par OEM : AUCUNE extension de code n'y est garantie), « README.md »
      + `text/plain` → « README.md.txt » ; (2) un diagnostic
      d'appareil qui « avance » d'un fichier est une VICTOIRE —
      l'écran de détails v0.31.5/0050 a transformé un échec opaque en
      preuve ; décision : `mimeFichierTexte` répond le type privé
      `text/x-codeide` pour TOUT fichier texte (le nom ne décide plus
      — un type sans extension canonique n'est JAMAIS complété), la
      tolérance de complétion reste bornée aux noms sans extension
      réelle (tolérer sinon = corruption silencieuse :
      `build.gradle.kts.txt` casserait Gradle) ; ADR 0051] ; onglets
      du terminal [leçon : une vue qui porte un écouteur d'appui long
      SEUL consomme les taps simples — `View.onTouchEvent` retourne
      `true` pour clickable OU longClickable, et le `performClick()`
      sans écouteur ne fait rien, le parent ne voit jamais le geste ;
      toute vue qui consomme un tap doit AGIR sur ce tap — la racine
      d'onglet prend son propre écouteur de clic
      (`brancherInteractionsOnglet`, même architecture que Termux) ;
      ADR 0051]
- v0.36.0 : **G8 — affichage des tâches, fin de la boîte noire de sync,
      écran de configuration du tooling** (retour utilisateur : les
      événements `TaskStarted`/`TaskFinished` étaient JETÉS par
      `GradleApiImpl.pomper` ; la sync n'avait aucune étape intermédiaire ;
      aucun `--console=plain`) : protocole **v3** (`SyncProgress` par phase
      CONNEXION/MODELE_GRADLE/MODELE_IDEA, `TaskFinished.durationMs`/
      `skipped`, dorés régénérés), `observeTachesBuild`/`observeSyncProgress`
      côté client, console à lignes TYPIÉES (`LigneConsole` scellée — une
      ligne par tâche mise à jour EN PLACE, étapes de sync conclues avec
      leur durée, avertissement bénin du daemon apaisé), `--console=plain`
      forcé en dernier argument, écran de configuration plein écran ouvert
      par l'engrenage de l'onglet Sortie (affichage des tâches, hors ligne,
      arguments libres, état vivant — DataStore, `OptionsTooling`) ;
      ADR 0065, journal détaillé dans docs/ROADMAP.md.
- v0.38.0 : **G9 — fondations du tooling professionnel v4** (prompt
      « tooling Gradle professionnel », étapes 1-4/6, ADR 0069) : correctifs
      9-12 (!! / bug de saisie d'arguments / présentateur UNIQUE
      PresentationTooling+DetailsEtapesSync / chrono repeatOnLifecycle+Time
      Provider) ; protocole **v4** (phases RÉELLES OUTILS→DISTRIBUTION→
      DAEMON→CONFIGURATION→MODELE_TACHES/MODELE_IDE→DEPENDANCES→CLASSPATHS,
      détails octets/élément/compteur, DetailTelechargement, arguments dans
      sync+classpath, dorés régénérés par RegenerateurDoresTest
      REGENERER_DORES=1) ; API TAPI 9.7.1 VÉRIFIÉE par javap (quatre pièges
      corrigés : events.download, GENERIC, octets en fin seulement,
      setStreamedValueListener void) ; ActionSyncModeles UNIQUE
      (BuildController.send streamé, marqueurs sérialisables) ;
      EcouteurProgressionCommun (sync ET build, 5 évts/s/élément, dernier
      segment d'URI) ; EtatsDistribution (marqueur .ok, sondeur .part) ;
      CacheSync serveur (taches/classpath instantanés après sync) ; délai
      d'INACTIVITÉ client 90 s réarmable ; canal
      observeTelechargementsBuild ; EtatGradle v4 (etapesAffichees
      dérivées, numeroEtape, tachesDisponibles) ; EtatEnteteTooling ;
      PanneauToolingController (le rendu quitte EditorActivity). 19
      intégrations serveur RÉELLES vertes. L'UI §3.3 est livrée en 0.39.0
      (ci-dessous) ; seul l'écran de configuration ENRICHI §7 reste différé.
- v0.39.0 : **G10 — UI complète du tooling v4** (prompt « tooling Gradle
      professionnel », étape 5 §3.3, ADR 0070) : en-tête ENRICHI (pastille
      de canal colorée avec spinner en vol / coche de succès / croix
      d'échec, titre « Synchronisation Gradle · étape n/8 », sous-titre =
      étape courante + détail annoncé à TalkBack, progression DÉTERMINÉE
      octets recus/total, pleine au succès) ; console en ARBRE APLATI
      filtrable par chips Sync/Build exclusives (8 phases du plan toujours
      visibles ✓/spinner/○, durée MESURÉE seulement, détail de
      téléchargement indenté sous l'étape active, synthèse de build,
      bandeau d'échec « Voir les problèmes » / « Réessayer ») ;
      configuration INTÉGRÉE au conteneur de la console
      (PanneauConfigToolingFragment remplace le dialogue plein écran,
      retour système LIFO, bouton d'accès libellé) ; feuille des tâches
      Material 3 (recherche en direct, récentes en chips, groupes)
      alimentée par le CACHE de la sync — ouvrirSelecteurTaches répond
      sans aller-retour, échec de listage AFFICHÉ + « Réessayer »
      (correctif n°6) ; constructeurs de rangées PURS testés
      (construireRangeesConsole, construireRangeesTaches) ; correctif
      bump-version.sh (grep ancré — la prose du journal détournait le
      script). RESTE à livrer : l'écran de configuration enrichi §7
      (commande effective, recherche de réglages, conflits d'arguments,
      2 colonnes) et la règle lint « aucun #RRGGBB dans feature:editor ».
- v0.39.1 : **correctif CI** — `lintDebug` `UselessParent`
      (activity_editor.xml fusionné, une vue de moins) + couverture kover
      de `tooling:server` sous le seuil (16 tests : ConteurPhasesSync,
      CacheSync, EtatsDistribution — 83,4 %).
- v0.40.0 : **G11 — correspondance avec l'APERÇU du tooling** (retour
      utilisateur sur la 0.39.0, ADR 0071, protocole v5) : la console n'a
      plus que DEUX écrans EXCLUSIFS (ChipGroup `selectionRequired`,
      Sync par défaut — la chronologie BRUTE et les sorties brutes
      mélangées disparaissent, la vue Build ne montre que les tâches) ;
      `SyncProgress.sautee` — la distribution Gradle déjà en cache se
      publie SAUTÉE (point gris, « En cache », durée 0 : plus de « ✓ 0 s »
      mensonger), le téléchargement ne se déroule que si elle MANQUE ;
      plan d'AFFICHAGE à 7 étapes (`EtapeConsoleSync` — « Dépendances et
      modèle IDE » fusionnées : plus de rangée ○ à vie sur une sync
      chaude), compteur « étape n/7 » ; pied de sync (« Synchronisation
      terminée… » / « Projet à jour, rien à télécharger… ») ; sous-titre
      de succès « N modules · N tâches · … ». Dorés v5 régénérés (28).
      RESTE à livrer (CHANGELOG honnête) : « Daemon réutilisé » (aucun
      signal honnête dans la Tooling API — durée mesurée conservée), les
      téléchargements DANS la vue Build (§6) et l'écran de config enrichi
      §7.
- v0.42.0 : **phase 1 du roadmap — PERFORMANCE CONSOLE (critique)** : un
      build de 725 ms s'affichait en 2 minutes (chaque ligne stdout
      émettait l'état, reconstruisait toutes les rangées et passait
      DiffUtil — O(N²)) → **console HYBRIDE comme Android Studio**
      [corps de l'onglet Sortie scindé : zone STRUCTURÉE (RecyclerView :
      arbre d'étapes, tâches, synthèse — DiffUtil O(1) par mise à jour) +
      zone TEXTE annexée (TextView monospace 11sp dans un ScrollView,
      filet `colorOutlineVariant`, visible en vue Build SEULEMENT — la
      vue Sync reste l'arbre seul de l'aperçu v5)] ; `GradleService` :
      `lignesBrutes` `SharedFlow` DÉDIÉ [rejeu 2 000 = le tampon borné
      TÊTE-tronquée, `extraBufferCapacity` 2 048, `DROP_OLDEST` — la
      pompe n'est JAMAIS bloquée par un abonné lent, leçon ADR 0057] ;
      `EvenementConsoleTexte` (`Ligne` flux/texte/apaisee + `Vider`) ;
      `ajouterLigne` sans émission d'état [gardes buildId/annulation et
      apaisement C5 conservés] ; `Vider` émis à `suivreBuild`/`attacher`
      [même cycle de vie que la fenêtre `lignes`] ; `LigneConsole.Sortie`
      SUPPRIMÉ — `etat.lignes` ne porte que les genres typés ;
      `RangeeConsole.LigneGradle`/`LigneGradleHolder`/`ligne_gradle.xml`
      SUPPRIMÉS [bloc mort SYNC-Sortie emporté : aucune pompe de sortie
      de sync n'existe] ; `PanneauConsoleFragment` : collecte sur
      `viewLifecycleOwner.lifecycleScope` [survit à onStop — un onglet du
      panneau ne REJOUE PAS à son retour (doublement) ; meurt avec la
      vue — une rotation reconstruit depuis le rejeu : un `Vider` tombé
      de la fenêtre emporte tout ce qui le précédait, reconstitution
      correcte PAR CONSTRUCTION] ; vidage LOTI par trame [un post
      dédupliqué, UN seul `append` par trame, tampon `Editable` posé
      vierge, spans de couleurs `SpannableString`+`ForegroundColorSpan`
      — stderr rouge, apaisé alpha 140] ; auto-défilement honnête
      [intention capturée AVANT l'ajout, tolérance 16 dp, jamais rabattu,
      défilements dédupliqués] ; `EditorViewModel.lignesBrutesConsole`
      exposé ; état vide honnête [rangées vides ET zone texte vide] ;
      tests : GradleServiceTest sur le `replayCache` [bornage, gardes,
      Vider, « plus aucune ligne brute dans l'état », garde annulation
      nouvelle], RangeesConsoleTest [Build = tâches+synthèse seules],
      ToolingEditorViewModelTest [helper `lignesZoneTexteApresDernierVider`]
      — ADR 0074]
- v0.43.0 : **phase 2 du roadmap — FIX TEMPLATES** : les trois modèles de la
      v0.41.1 (android-app, spring-boot, kotlin-multiplatform) étaient CASSÉS
      à la génération [clé i18n `gitattributes.entete` manquante →
      `ECHEC … clé i18n manquante` ; chemins codés en dur
      `src/…/jo/codeide/template/` au lieu de `{{packageName|packagePath}}` ;
      `Greeter.jvm.kt` KMP présent dans les assets mais NON câblé (expect
      sans actual) ; options communes ignorées ; versions Gradle 9.7.1 +
      AGP 8.7.3/Kotlin 2.0.21/Boot 3.4.1 incompatibles] → **conventions de
      package** [fonctions `packageFromAppName`
      (`com.example.<appName>`, android-app) et `packageFromArtifactId`
      (`com.example.<artifactId>`, spring-boot/KMP) ; `kotlin-jvm`/`java`
      gardent `packageFromNameAndAuthor` ; `Sources.valeursParametres` : une
      dérivée lit les paramètres déclarés AVANT elle — manifestes
      réordonnés `appName`/`artifactId` avant `packageName` ; le wizard n'a
      besoin d'AUCUNE modification, rendu dynamique] ; **filtre
      `resourceName`** [nom de ressource Android sûr : NFD, mots
      capitalisés, repli `App`, préfixe si chiffre — `« mon éclat & 2048 »`
      → `MonEclat2048` ; alimente `Theme.{{appName|resourceName}}`] ;
      **enrichissements** [android : strings/colors/themes.xml,
      proguard-rules.pro + release minifiée, ExampleUnitTest, app/.gitignore,
      manifeste `@string/app_name`+`@style/Theme.<Nom>`+allowBackup+
      supportsRtl ; spring : GreeterRepository (patron repository),
      ApplicationTests `@SpringBootTest`, `application.yml` REMPLACE
      application.properties, `POST/GET /salutations` ; KMP : commonTest
      enrichi (ordre, plateforme) ; les trois README/.gitignore/catalogues
      i18n fr/en] ; **chaînes d'outils alignées sur le wrapper Gradle 9.7.1,
      versions vérifiées une à une** [android : AGP 9.4.1 Kotlin INTÉGRÉ
      (plus de plugin kotlin-android, `kotlin{compilerOptions}`),
      compileSdk 37+minor 2, core-ktx 1.19.0, appcompat 1.8.0, material
      1.14.0, tests JUnit 4 (kotlin-test nu ne résout pas `kotlin.test.Test`
      sans KGP) ; spring : Boot 4.1.1 (exige Gradle 9), Kotlin 2.2.21,
      plugin kotlin-spring (CGLIB ne proxyfie pas les classes finales), BOM
      `platform()` native (dependency-management inutile) ; KMP : Kotlin
      2.2.21, tâche `run` JavaExec CC-compatible branchée sur la sortie
      jvmMain (plugin `application` INCOMPATIBLE KMP/Gradle 9 :
      verrouillage `:apiElements`)] ; **vérification** :
      `scripts/verify-templates.sh` 24 combinaisons (18 + 6 nouvelles
      sb/kmp/andr — build réel, tests, `run` KMP avec salutation contrôlée,
      Android `assembleDebug`+`testDebugUnitTest`+APK via
      `CODEIDE_ANDROID_SDK`) ; tests ModelesEmbarquesTest 15 → 22 —
      ADR 0075]
- v0.44.0 : **phase 3 du roadmap — TEMPLATES AVANCÉS** : le modèle
      `android-app` passe en v1.2.0 avec **variantes par paramètres**
      [`projectType` CHOICE : `empty-activity` (défaut) / `no-activity`
      (manifeste `{{#if}}` sans `<activity>`, aucune classe d'écran) /
      `basic-activity` (tiroir Material 3 : `ActionBarDrawerToggle` +
      `MaterialToolbar` + `FragmentAccueil` à arguments + `menu/tiroir.xml`
      + thème `NoActionBar` conditionnel + `drawerlayout` 1.2.0) ;
      `language` CHOICE kotlin/java : sources miroir `.java` (ViewBinding
      par champs publics, `import static`), bloc `kotlin{}` retiré en Java
      pur — chemins statiques uniques, chemins TEMPLATISÉS autorisés en
      doublon à `when` exclusifs] ; **nouveaux modèles** [`android-library`
      (.aar : module `:library`, `consumer-rules.pro`, `com.example.<nom>`)
      ; `gradle-plugin` : `kotlin-dsl` OBLIGATOIRE (Gradle 9.7.1 embarque
      Kotlin 2.4.0 — un KGP externe 2.2.21 échoue sur les métadonnées du
      `gradleApi()`), `@DisableCachingByDefault` exigée par
      `validatePlugins`, `maven-publish` explicite + artifactId de
      publication = `{{artifactId}}` (nom de projet à espaces invalide en
      Maven), Kotlin 2.4 convertit `Action<T>` en lambda À RÉCEPTEUR,
      `pluginId` dérive de `packageFromArtifactId` réutilisée] ; **wizard**
      [rendu adaptatif : > 2 choix → cartes radio empilées (projectType
      Android), `language` en tuiles ; libellés bilingues] ; **compose-app
      différé** (ADR 0002) ; **vérification** :
      `scripts/verify-templates.sh` 24 → 32 combinaisons (APK par variante,
      AAR de release, `validatePlugins` + testkit, publication maven locale
      + marqueur, E2E consommateur `./gradlew greet`) ; tests
      ModelesPhase3Test (9), catalogues ancrés à 7 modèles, 1415 tests /
      0 échec — ADR 0076]
- v0.45.0 : **phase 4 du roadmap — WIZARD ENRICHI** : sections Android
      [`minSdk` 24–34 (plancher Navigation 2.10.2, merger manifeste) ;
      `targetSdk` 34–37 DÉFAUT 37 (indépendant du 28 de l'app hôte, ADR
      0045 = W^X bootstrap ; projets générés = Play Store) ;
      `applicationId` dérivé par la sixième fonction `defaultFrom`
      `applicationIdFromPackageName` — chaîne appName→packageName→
      applicationId, libre après saisie] ; **aperçu RENOMMABLE** [crayon
      par ligne, masqué `.codeide/` ; carte clé=chemin ORIGINAL (fichier
      ou préfixe dossier) → valeur=NOM de segment ; le moteur substitue
      chaque segment dont le préfixe (lu sur les segments ORIGINAUX) est
      une clé → dossier+fichier se COMPOSENT ; `PlannedFile.
      cheminOriginal` = identité stable ; 3 couches de garde (dialogue /
      VM `.codeide` / moteur échec explicite) ; rejet domaine → annulation
      + replan, jamais d'impasse ; clés obsolètes ignorées comme les
      paramètres périmés ; SavedStateHandle + création emportée] ; **deps
      en interrupteurs** [Android : coroutines 1.11.0, retrofit 3.0.0+gson,
      navigation 2.10.2, room 2.8.5, hilt 2.59.2 — `BddLocale` +
      `{{appName|resourceName}}Application` (manifeste), Java = 3 FICHIERS
      (une classe publique par fichier, le code KSP généré expose les
      types) ; Spring : JPA+H2 runtimeOnly, security, actuator,
      validation — alias de catalogue SANS version (BOM 4.1.1) ; KMP :
      serialization 1.11.0+plugin 2.2.21, coroutines 1.11.0, datetime
      0.8.0 (Instant ponté kotlin.time EXPERIMENTAL → @OptIn, artefacts
      -compat écartés), un fichier d'usage PAR dépendance] ; **KSP
      2.3.12** : les 2.2.x REFUSENT le Kotlin intégré AGP ; le workaround
      officiel (`builtInKotlin=false` + KGP externe) est mort sous AGP
      9.4.1 (classpath sans version → `BaseExtension` ClassCastException)
      — la ligne 2.3.x l'accepte, génération vérifiée jusqu'au dex, KT et
      JAVA ; `avecKsp = avecRoom || avecHilt` (expression `||`) ; **verif
      37 combos** (andr-deps, andr-deps-java, sb-deps, kmp-deps,
      kt-app-renoms : README→NOTES avec build+run) ; ModelesPhase4Test
      (10), six fonctions ancrées, 1445 tests / 0 échec — ADR 0077]
- v0.60.0 : **E6 de la refonte — dernière étape, fin de la phase R**
  (ADR 0091) : **migration des installations existantes** — adoption par
  l'EXÉCUTION au premier `run()`, jamais depuis les marqueurs de fichier
  (`controleComposant` : composant présent SANS quadruplet = installation
  antérieure au parcours → le `verify` du manifeste tranche (passe =
  adopté, quadruplet du plan reconstruit en fin de phase, zéro
  retéléchargement ; échoue = réparation de CE composant seul ; un
  quadruplet ENREGISTRÉ divergent reste réparé seul, § 12.4 inchangé) ;
  `EtapeTelechargement.verify` : préfixe déjà basculé = archive
  dispensée — l'ancien flux n'écrivait pas le cache SHA-256) ; licence
  SDK jamais migrée (consentement = acte d'utilisateur, § 12.5) ; 3
  scénarios testés (ancien complet / neuf / à moitié installé — 5 tests
  d'adoption) ; **suppression de l'ancien code** (~2 900 lignes :
  `InstallateurBootstrap`, `TelechargeurBootstrap`, `EcrivainSdkAndroidCli`,
  `EcrivainCodeideEnvCli`, `EcrivainGradleCli`, `EcrivainProfilShell`,
  `Aapt2Deployeur`, `VersionneurScriptsTerminal`, ports
  `BootstrapInstaller`/`BootstrapAssetsSource`/`ConfigurationEnvTerminal`,
  modèles `EtatInstallationBootstrap`/`EtapeInstallation`/`OutilResume`,
  `ConfigurationEnvTermux`, ancien écran `InstallFragment` + `ClientTerminalMini`
  + layouts + 45 clés orphelines + deps terminal-view du module,
  `AssetsBootstrapSource`/`BootstrapAssetsModule`) ; rebranchements :
  bandeau accueil + observateur d'outils sur l'état du parcours, daemon
  Gradle relancé par la SEULE empreinte E4 (l'empreinte change quand
  `java`/`javac` existent sur disque) ; `refreshTerminalScripts()` retiré
  (l'app ne pose plus `codeide.sh`/`bin/gradle`/`bin/android-sdk`/
  `bin/codeide-env` — l'env vient de `ProcessEnvironmentProvider` ;
  scripts anciens laissés en place, non maintenus — rupture assumée au
  CHANGELOG) ; ADR 0082/0083 marquées **remplacées** ; `ExtracteurBootstrap.
  extraire` redevenue `suspend fun` (le flux d'étapes n'avait plus de
  consommateur). Leçons : l'adoption d'un composant sans quadruplet doit
  tomber sur l'exécution, pas sur le disque (un marqueur ne prouve
  rien — rapport 7842f130) ; une vérification « légère » qui exécute
  `pkg update` (réseau) n'est JAMAIS une tâche de démarrage silencieuse.
- v0.59.0 : **E5 de la refonte** (ADR 0090) : nouvelle interface —
  `InstallationFragment`/`InstallationViewModel` (feature:install ;
  projection pure de `EnvironmentSetupOrchestrator.state` : stepper 4
  cartes, « étape N sur 4 », journal repliable monospace, consentement
  licence réinitialisé à l'APPARITION de la carte seulement, vitesse +
  temps restant MESURÉS sur deux échantillons (`estimer` pur — jamais
  d'extrapolation : divergence ADR 0090 § 3), actions masquées hors
  contexte jamais grisées, récapitulatif + « Créer mon premier projet »,
  `layout-sw600dp` deux panneaux mêmes identifiants — journal
  structurel, bouton bascule masqué) ; `EnvironnementFragment`/
  `EnvironnementViewModel`/`ComposantsEnvAdapter` (feature:settings ;
  composants du magasin + tailles RÉELLES par port `AuditeurComposants`
  (état vérifié = présence du disque, jamais un booléen déduit), rangée
  JDK « paquet APT » non désinstallable, Vérifier légère/approfondie /
  Réparer (première phase non vérifiée) / Désinstaller avec confirmation
  → `uninstallComponent` (nouveau port orchestrateur : retrait du
  quadruplet par le seul décideur, `DesinstalleurComposants` supprime
  l'installPath § 12.3) / Copier le diagnostic) ;
  `DiagnosticInstallation` (core:domain, partagé, codes techniques
  NEUTRES — aucun libellé localisé codé en dur) ; maquette
  `docs/preview/installation-environnement.html` ; `SectionParametres.
  OUTILS` (« bientôt ») SUPPRIMÉ remplacé par `ENVIRONNEMENT` (l'écran
  bientôt ne garde qu'IA/Sécurité) ; destination `installation` →
  `InstallationFragment` (l'ancien écran devient inatteignable,
  suppression en E6) ; fakes : `FakeAuditeurComposants` neuf,
  `FakeEnvironmentSetupOrchestrator.verifications` ; 26 tests nouveaux
  (Diagnostic 6, InstallationVM 13, EnvironnementVM 7) ; leçon : le
  ViewBinding des deux variantes téléphone/tablette de
  `fragment_installation` expose `zoneJournal` en type commun
  (FrameLayout ancêtre du ScrollView) — mêmes identifiants, une seule
  classe de liaison.
- v0.58.0 : **E4 de la refonte** (ADR 0089) : phase 4 `ANDROID_SDK`
      complète — cinq étapes (`resolution-plan` : manifeste v2 + résolution
      § 12.2 + espace (plan × 2) ; `composants` : péremption par
      **quadruplet** persisté (§ 12.4 — écart = réparation du SEUL
      composant fautif), téléchargement unique cache SHA-256, extraction
      `tar.xz` par les outils du bootstrap + garde anti-traversée,
      bascule atomique, `verify` du manifeste exécuté sans shell,
      criticité (non critique tenté-échoué = `Degraded`, l'étape
      CONTINUE) ; `licences` : fichiers écrits après acceptation
      (hachages historiques, non vérifiés appareil) ; `cablage` :
      `EcrivainConfigurationGradle` bloc géré **en place** (idempotence
      octet pour octet, override manuel neutralisé par commentaire) ;
      `verification-sdk` : `sdkmanager --version` (JAVA_HOME explicite +
      `--sdk_root`), `--list_installed` cohérent avec le plan,
      `android.jar` ouvrable) ; orchestrateur étendu
      (`avertissements()` → `Degraded`, `composantsInstalles()` persistés
      prouvés par exécution, `verify(deep)` → port
      `VerificationApprofondie` — implémenté côté app par
      `VerificationApprofondieProjets` : projet de contrôle réel +
      `gradlew assembleDebug` + suppression) ; `install-state.json`
      **schéma 2** (`installPath` — un fichier v1 est rejeté, reprise
      sans retéléchargement) ; `aapt2` résolu plan d'abord (analyse
      tolérante regex — PAS d'org.json dans `LocalisationOutils`, Kotlin
      JVM pur) puis scan puis héritage `$PREFIX/bin` (retiré E6) ;
      `estSdkAndroidValide` : SDK cohérent sans plateforme (constat § 1
      corrigé) ; `buildToolsVersion = "35.0.2"` explicite dans les
      templates + matrice AGP↔build-tools↔compileSdk dans
      ENVIRONNEMENT.md + `AlignementCatalogueTemplatesTest` ; relance
      daemon Gradle par `EmpreinteChaineOutils` +
      `DetecteurChangementEmpreinte` (première observation ≠ changement)
      câblée dans `CodeIdeApplication` ; 22 tests nouveaux (module
      bootstrap 219) ; leçons E4 : `renameTo` exige le parent de la
      cible posé ; un bloc géré doit être remplacé EN PLACE pour être
      idempotent ; org.json indisponible en test JVM pur (regex
      tolérante ou Robolectric).
- v0.57.0 : **E3 de la refonte** (ADR 0088) : phase 3 `JAVA` livrée dans
      `core:bootstrap/installation/PhaseJava.kt` — étape `openjdk`
      (`apt-cache policy` journalisé, jamais un verdict ; `pkg install`
      du paquet catalogue ; contrôle immédiat : `JAVA_HOME` par la règle
      unique `LocalisationOutils`, `java -version` (bannière sur
      **stderr**) + `javac -version` exécutés, majeure comparée au
      catalogue ; une JVM posée qui ne démarre pas échoue avec sa sortie
      capturée — régression du mode muet R6, ADR 0084) et étape
      `verification-tls` (sonde `SondeTls.java` compilée par `javac`
      puis exécutée par `java` : JVM + truststore + poignée TLS + réseau
      en un contrôle ; échecs **classés** truststore → `Jvm` /
      réseau → `Reseau`, pile capturée) ; `versions["jdk"]` lu sur
      `java -version` réel (jamais en dur) ; 7 tests `PhaseJavaTest`
      (module 197) ; parcours BOOTSTRAP → PACKAGE_TOOLS → JAVA complet,
      arrêt propre avant `ANDROID_SDK` (E4) ; leçon E3 : la bannière de
      `java -version` est sur **stderr** — toujours lire stdout ET
      stderr (helper `sortieComplete`).
- v0.56.0 : **E2 de la refonte** (ADR 0087) : cadre commun livré dans
      `core:bootstrap/installation/` — orchestrateur concret (reprise
      « verify-first », annulation synchrone + persistance en coroutine
      fraîche, licence exigée avant `ANDROID_SDK`), `CommandRunnerProcessus`
      (capture intégrale, `timedOut`), `GestionnaireTelechargement`
      (cache SHA-256, miroirs ordonnés, reprise `Range` — invariant
      « un téléchargement par artefact » testé par compteur),
      `MagasinEtatInstallation` (`install-state.json` org.json atomique,
      `Running` normalisé), `ClientManifesteOutils` (transport v2, la
      consommation arrive en E4), phases `Bootstrap` (8 étapes, logique
      éprouvée portée) et `PackageTools` (retries croissants + repli
      `apt`, un step par paquet vérifié par exécution),
      `ServiceInstallationEnvironnement` (specialUse, action Annuler,
      NON démarré en prod avant E5), `FabriquePhasesParDefaut` (la
      carte de phases internal ne traverse jamais Hilt — un `Map`
      générique y serait un multibinding : leçon E2), fakes
      `core:testing` (CommandRunner/DownloadManager/ArchiveExtractor/
      ToolManifestClient/InstallStateStore/EnvironmentSetupOrchestrator),
      9ᵉ raison `ArchitectureNonSupportee` (traducteurs sur le TYPE,
      ajout additif), `CommandResult.timedOut` ; 56 tests nouveaux
      (module 190), gate complète verte ; étapes suivantes E3 (Java+TLS),
      E4 (manifeste v2 + licences + câblage), E5 (maquettes + UI), E6
      (migration + suppression).
- v0.55.0 : **E1 de la refonte du parcours d'installation** (prompt
      « Refonte complète du parcours d'installation de l'environnement »,
      ADR 0084/0085/0086) : cause racine du « SDK non fonctionnel »
      ÉTABLIE par reproduction (JVM du préfixe incapable de démarrer →
      code 127, stdout vide, diagnostic stderr jeté par
      `sdk_fonctionnel` ; chaîne `java`→`libjli.so`→`libz.so.1`,
      `libjvm.so`→`libandroid-shmem.so` constatée sur le `.deb` APT
      réel ; truststore vérifié ne PAS casser `--version` → test TLS
      en phase 3) ; modèle de domaine et ports posés dans `core:domain`
      (`InstallPhase`/`PhaseState`/`EnvironmentSetupOrchestrator`,
      `CommandRunner`/`DownloadManager`/`ArchiveExtractor`/
      `ToolManifestClient`/`InstallStateStore`, `InstallPlanResolver`
      pur — 20 tests), `AppError.EnvironmentSetup`+`CommandOutput` dans
      `core:model`, `ToolchainCatalog` (build-tools 35.0.2 aarch64,
      platform android-37.2, JDK 17, URL manifeste constante unique) ;
      branche `EnvironmentSetup` ajoutée aux 4 traducteurs exhaustifs ;
      étapes suivantes E2 (cadre commun + phases 1-2), E3 (Java+TLS),
      E4 (manifeste v2 + licences + câblage Gradle/aapt2 + daemon),
      E5 (maquettes + nouvelle UI), E6 (migration + suppression).
- v0.80.1 : **correctifs d'espace de travail** (retour utilisateur,
      ADR 0098) — explorateur VIVANT [bascules d'affichage enfin CÂBLÉES
      au popover Légende (section Affichage : compactage, fichiers
      cachés, dossiers de build) ; `masquerDossiersBuild=false` par
      défaut (.gradle et app/build visibles — l'utilisateur se sert de ces
      dossiers) ; BALAYAGE périodique de l'arbre affiché (4 s,
      onStart/onStop, borné 25 dossiers, comparaison URI+type, purge
      NotFound, échec d'accès silencieux) — les créations externes de
      Gradle/terminal apparaissent SANS Actualiser, projet SAF et
      stockage privé couverts] ; panneau inférieur aligné AndroidIDE
      [expandedOffset = haut de conteneur_editeur, reposé au layout —
      le sheet étendu ne recouvre PLUS la toolbar ni les onglets de
      fichiers] ; en-tête du panneau SECTIONNÉ par onglet [Console :
      première section GONE, la ligne tooling EST l'en-tête (appui =
      bascule replié↔mi-hauteur, onglets = poignée repliée sans
      tooling, peek composé) ; Problèmes/Journal : sous-titre
      d'informations (comptes plurialisés) + badge étendu aux
      problèmes] ; redimensionnement du tiroir PREND EN COMPTE la zone
      centrale [TiroirPoussantLayout.reevaluerTranslation() à chaque
      trame du glissement et de l'aimant] ; 6 tests SurveillanceArbre +
      défauts retournés + vues nouvelles.
- v0.80.2 : **maturage du panneau et du tiroir** (retour utilisateur,
      ADR 0099) — sections de l'en-tête CONDITIONNELLES [Problèmes/
      Journal : ligne tooling + progression ÉTEINTES (seule la
      première section porte les informations) ; sheet ÉTENDU stable :
      les DEUX sections disparaissent (onglets au sommet, comme le
      ViewFlipper d'AndroidIDE) ; INVISIBLE sous le seuil du fondu en
      plein glissement — jamais de saut de hauteur ; peek sans ligne
      hors Console] ; état vide STABLE [réserve CONSTANTE padding=
      peekHeight à la marginBottom d'AndroidIDE — la zone d'édition ne
      change plus de taille replié/mi-hauteur/étendu, la vue centrée ne
      re-centre pas] ; poignée de redimensionnement À CHEVAL RÉEL
      [conteneur_poignee DERNIER enfant de la racine + drawerElevation
      0 + drawChild sans rognage : moitié 13 dp sur le tiroir, moitié
      sur la zone centrale, suit le bord image par image (glissement,
      redimensionnement, aimants, rotation), miroir RTL, masquée
      refermée ; fond_tiroir pleine largeur (plus de débord)] ; icônes
      parité Android Studio [11 fileTypes IntelliJ nouveaux (archive
      zip/jar/apk…, image, html, css, js, yaml, shell, sql, csv,
      police, binaire) jour/nuit ; folder.svg replié ET déplié ; PLUS
      AUCUN setColorFilter sur les icônes de l'arbre — couleurs
      officielles, 4 teintes de dossier retirées] ; tests
      PanneauToolingControllerTest (matrice complète) + structure
      racine de la poignée + 3 tests d'extensions.
- v0.80.3 : **touches du tiroir restaurées** (régression v0.80.2,
      ADR 0100) — le calque de la poignée vivait DANS le DrawerLayout
      comme enfant de contenu plein écran : tiroir ouvert,
      onInterceptTouchEvent l'identifiait sous CHAQUE touche
      (findTopChildUnder + isContentView, mScrimOpacity > 0) et tout
      interceptait. La racine du layout devient un FrameLayout de
      superposition [TiroirPoussantLayout (id inchangé — binding
      inchangé), calque conteneur_poignee AU-DESSUS] ; cheval 50/50
      conservé (recalerPoignee inchangé), comportement DrawerLayout
      standard restauré (tap-liseré, glissement, verrouillage), ombre
      d'élévation du tiroir de retour ; hacks v0.80.2 supprimés
      (drawChild bypass, setConteneurPoignee, setDrawerElevation(0),
      requestDisallowInterceptTouchEvent de la poignée) ; DEUX tests
      comportementaux à vraies touches verrouillent la régression
      (corps du tiroir ouvert + poignée à cheval).
- v0.80.4 : **crash de la feuille des tâches, git init muet, clonage
      depuis l'accueil** (retour utilisateur, ADR 0101) — onglet
      « Tâches » du tiroir Projet : l'action passe par
      `OuvrirSelecteurTaches` de l'EditorViewModel d'activité (cache
      de sync, repli orchestrateur, « Réessayer ») au lieu
      d'instancier une feuille SANS arguments [crash a6d72e9d :
      requireArguments → IllegalStateException ; la feuille tolère
      désormais un paquet absent] ; « Initialiser un dépôt » surfacé
      [résultat de git init vérifié : git absent (pkg install git),
      chemin FUSE, stderr — erreur affichée dans les DEUX états,
      bouton désactivé pendant l'opération] ; bouton Git sur l'accueil
      [« Get from VCS » mobile : dialogue URL + nom pré-rempli,
      ClonerDepotUseCase à rollback honnête (SAF + port MoteurGit +
      registre, résidu jamais silencieux), nouveau port
      ResolveurCheminFuse lié à ResoudreRepertoireProjet (app/di),
      FakeMoteurGit dans core:testing] ; scripts de build ouverts
      VRAIMENT depuis l'onglet Scripts [ResoudreFichierRelatifUseCase
      traduit le chemin relatif en URI SAF, OuvrirFichier via
      l'EditorViewModel d'activité] ; 9 + 7 + 4 nouveaux tests.
- v0.80.5 : **section Git du tiroir vivante** (retour utilisateur :
      « cloné un dépôt, la section git affiche encore initialiser ») —
      le statut Git n'était qu'une photographie de l'ouverture de
      l'éditeur ; trois mécanismes superposés comme la fenêtre Git
      d'Android Studio [sélection de l'onglet → rafraîchissement
      immédiat (onHiddenChanged du GitFragment, soigne AUSSI la course
      FUSE d'un clone tout juste terminé) ; sonde discrète toutes les
      2 s — existence de .git + horodatages de .git/HEAD et
      .git/index, DEUX stats sans AUCUN processus git, rechargement au
      changement de signature, onStart/onStop de l'éditeur ; bouton
      manuel conservé] ; GitViewModel reçoit le PORT
      ResolveurCheminFuse (plus la classe concrète, ADR 0101) et un
      DispatcherProvider (sonde hors fil principal) ; MoteurGitCli
      résout le binaire git À CHAQUE exécution (résolveur injecté,
      plus de chemin gelé au singleton : `pkg install git` dans le
      terminal devient utilisable sans redémarrer l'app) ; 8 tests
      GitViewModelTest (dont le scénario exact du retour : dépôt créé
      après l'ouverture, zone « initialiser » qui part) + 3 tests
      MoteurGitCliBinaireDynamiqueTest.
- v0.82.0 : **mission « Historique local » H0+H1 — le filet de
      sécurité indépendant de Git** : recherche IntelliJ mesurée dans
      le code [5 jours PAR ACTIVITÉ (trous ≥ 12 h = 1 jour), purge une
      fois par session ~1 s après démarrage, AUCUN quota, contenu AVANT
      changement, regroupement par frontières d'action nommées, revert
      annulable (WriteCommandAction), contenus portés par le VFS (drop
      au rebuild), binaires non stockés par défaut] ; H0 [ADR 0104
      (blobs SHA-256 + index JSON atomique dans le stockage PRIVÉ —
      Room écarté : journal append-then-compact, pas de migration),
      ADR 0105 (5 jours calendaires — divergence assumée vs jours
      d'activité, quota 256 Mo, 2 Mo/fichier, 5000 entrées, SECRETS
      jamais historisés), ADR 0106 (capture par DÉCORATEUR du port
      FileSystem, SourceProjetHistorique = projet ouvert, détection
      externe au plus juste, restauration annulable par construction),
      docs/HISTORIQUE_LOCAL.md + maquette] ; H1 [port HistoriqueLocal +
      modèles + PolitiqueHistorique.exclut (core:domain, JVM pur),
      MoteurHistoriqueLocal (dédup par empreinte, index tmp+rename,
      clé de projet DYNAMIQUE — index rechargé à la clé, purge âge/
      quota/nombre + blobs orphelins, index corrompu → vide),
      HistoriqueFileSystem (contenu AVANT lu au délégué, pierres
      tombales, renommage avec ancien nom, anti-bruit identique,
      silencieux par contrat), liaison décorée dans StorageModule
      (core:storage — l'arbre privé reste nu), EditorViewModel pose la
      racine + purge à l'ouverture] ; tests [MoteurHistoriqueLocalTest
      14 + HistoriqueFileSystemTest 9 — intégration réelle sur
      dossiers temporaires, horloge factice] ; chaîne verte sur
      core:domain, core:storage, core:testing, feature:editor, app —
      assembleDebug 20,5 Mo.
- v0.81.0 : **mission « Exécuter » R0+R1 — le bouton Run d'Android
      Studio, sans adb** (compile → installe → lance) : recherche des
      références mesurée (AndroidIDE : récepteur EXPORTÉ forgeable,
      aucune relance, aucune gestion de permission ; CodeAssist :
      permission guidée avec reprise 5 min, récepteur non exporté à
      action par session, 10×200 ms de relances, lancement relayé au
      process UI ; aucun des deux n'utilise setRequireUserAction) ;
      R0 [ADR 0102 (PackageInstaller : garde canRequestPackageInstalls
      + écran système + reprise automatique, session MODE_FULL_INSTALL +
      fsync, PendingIntent MUTABLE, récepteur RECEIVER_NOT_EXPORTE à
      action unique par session, STATUS_PENDING_USER_ACTION → intent
      système, échecs typés avec actions correctrices — signature →
      désinstaller en nommant la perte, version antérieure, espace) +
      ADR 0103 (pont de logs R2 : Binder bidirectionnel, authenticité
      par UID vs getPackageUid, linkToDeath bilatéral, anneau borné +
      pertes comptées, ApplicationExitInfo, un pont par process,
      injection par script d'init + API PUBLIQUE AGP — configuration
      <variante>RuntimeClasspath, jamais de classes internes) +
      docs/EXECUTER.md + maquette executer-logcat.html] ; R1 [port
      ApkInstaller + ResultatInstallationApk typé (core:domain) +
      FakeApkInstaller (core:testing) + ApkInstallerAndroid (app, process
      principal — un service d'arrière-plan ne peut pas démarrer une
      activité, Android 10+) + ExecuterApplicationUseCase (APK au chemin
      DÉTERMINISTE comme verify-templates.sh, applicationId lu
      d'output-metadata.json par kotlinx.serialization — AUCUN changement
      de protocole tooling) + EditorViewModel.ExecuterApplication
      (attend le VERDICT BuildFinished via l'état process-wide — jamais
      d'installation sur un build évincé, détecte app/build.gradle(.kts)
      → bouton Run contextuel : Android = runner, JVM = gradle run) +
      GradleService.publierLigneExecution (console BUILD, libellés
      localisables) + EffetEditor.NotifierExecution (snackbar + action
      « Désinstaller… » → boîte système) + manifeste :
      REQUEST_INSTALL_PACKAGES (restreinte Play, distribution hors Play
      documentée) + queries MAIN/LAUNCHER ; setRequireUserAction
      (USER_ACTION_NOT_REQUIRED) API 31+ — mises à jour silencieuses SI
      le système l'accepte ; EditorViewModel passe au PORT
      ResolveurCheminFuse (comme GitViewModel v0.80.5 — le dossier
      devient résolvable en tests JVM via couture)] ; tests
      [ExecuterApplicationUseCaseTest 7 (cycle, APK absent, JSON
      hostile, échecs typés, détection module) +
      ExecuterApplicationEditorViewModelTest 3 (couture FUSE vers dossier
      RÉEL : build→install→lance, échec de build = zéro installation,
      détection)] ; chaîne verte sur core:domain, core:testing,
      feature:editor (2 m 23 s), app — assembleDebug 20,5 Mo.
- v0.80.7 : **section Git figée — cause racine TROUVÉE et prouvée**
      (retour utilisateur récurrent : « la section git est toujours figée
      à "ce projet n'est pas un dépôt git / initialisé un dépôt" »).
      Les correctifs v0.80.4/v0.80.5 avaient traité les symptômes
      (rafraîchissement, sonde .git) mais le vrai défaut vivait dans
      MoteurGitCli.executer : DOUBLE COLLECTE de stdoutLines() —
      SupervisionProcessus.attendre draine les flux (contrat du port :
      flux froids consommables UNE SEULE FOIS, le lecteur referme le
      tuyau à l'EOF), puis executer re-collectait le flux déjà refermé
      → stdout VIDE sur appareil réel → estDepot = FAUX pour tout
      dépôt existant, statut/branche/journal vides aussi ; le CLONAGE
      réussissait pourtant (il ne lit que le code de sortie) — d'où le
      tableau trompeur. Les faux de test rejouent leurs flux (asFlow
      d'une liste) : tous les tests étaient verts, le bug n'était
      visible qu'avec de VRAIS processus [correctif :
      SupervisionProcessus.Sortie.sortieStandard — capture intégrale
      pendant l'unique drainage, executer lit la capture, plus
      AUCUNE seconde collecte] ; preuve rouge/vert sur 4 nouveaux
      tests MoteurGitCliFluxUniqueTest (vrais sous-processus JVM :
      « git » scripté + vrai git de la machine, git init réel) —
      3 échecs avec l'ancien code, 4 succès avec le correctif ;
      au passage TOUTES les lectures stdout deviennent fiables
      (journal, branches, branche courante, diff, stash) ; chaîne
      verte sur core:bootstrap (spotless, detekt, 4+3 tests, lint,
      kover, checkModuleDependencies, assembleDebug 20,4 Mo).
- v0.80.6 : **apparence appliquée à TOUS les écrans** (retour
      utilisateur : « à part EditorActivity et CrashActivity, tous les
      autres écrans n'utilisent pas le thème ou palettes de couleurs
      choisies ; incohérences entre palettes et dynamiccolors ») —
      cause racine : installSplashScreen() (uniquement MainActivity)
      appelle Activity.setTheme(postSplashScreenTheme) en interne,
      thème NEUF qui EFFACE l'overlay posé avant création par
      AppliquerApparence.onActivityPreCreated — les activités sans
      splash (éditeur, terminal, diagnostic) gardaient leur overlay
      [correctif : AppliquerApparence.rappliquer(activity), reposé
      par l'hôte juste après installSplashScreen(), AVANT
      super.onCreate()/setContentView() ; no-op si le point
      d'application n'est pas installé (application de test Hilt)] ;
      distinction dynamique/palette honnête [couleurs dynamiques
      appliquées seulement si l'appareil les SUPPORTE
      (DynamicColors.isDynamicColorAvailable : Android 13+, ou 12 chez
      les fabricants supportés), sinon repli sur la palette statique —
      plus jamais « ni l'un ni l'autre » ; écran Apparence : rangées
      de palette désactivées seulement si dynamiques réellement
      applicables, la palette redevient utilisable sans Material You] ;
      preuve anti-régression [test d'intégration MainActivity + splash
      réel + palette BLEU : échoue sans le correctif (vérifié),
      passe avec] ; 2 tests AppliquerApparenceTest (core:ui) + 1
      ApparenceIntegrationTest (app), chaîne verte sur les modules
      touchés (core:ui, app, feature:settings), 69 tests app/0 échec.
- Prochaine : phase 5 du roadmap — LSP (kotlin-language-server côté
      Kotlin, jdtls côté Java, classpath préparé `.codeide/local/
      lsp-classpath.json` ; cf. docs/ROADMAP.md). La refonte du parcours
      d'installation (phase R, E1-E6) est TERMINÉE (v0.55.0 → v0.60.0) ;
      en attente côté dépôt `codeide-tools` : la publication du manifeste
      v2 (prompt 2, R5) exigée par la phase 4.

Détail de chaque étape : `docs/ROADMAP.md` et section 11 du prompt maître.

### Leçons d'ingénierie (à relire avant toute étape)

- **Un layout ne se valide qu'en l'inflatable** : AAPT2 compile un `<menu>`
  inline sans rechigner — c'est `LayoutInflater` qui plante à l'exécution
  (`android.view.menu`, rapport 8b5b73f1, présent des étapes 14 à 18).
  Le test de régression `ActivityEditorLayoutTest` gonfle le vrai layout
  sous Robolectric : tout nouveau layout d'activité mérite son équivalent
  (rejoint en v0.31.1 par `ActivityTerminalLayoutTest` — rapport
  7842f130).
- **Kotlin initialise les propriétés et blocs `init` dans l'ordre de
  DÉCLARATION** (rapport 7842f130, v0.29.0) : une propriété déclarée
  après le bloc `init` vaut encore `null` pendant celui-ci — si le bloc
  l'utilise (directement ou via une méthode appelée), c'est un NPE
  déterministe à la construction, invisible au compile time. Une vue
  gonflable ne doit jamais consommer une propriété d'instance déclarée
  plus bas : réordonner, ou porter la donnée constante dans le
  `companion`.
- **Android 10 interdit l'exécution des binaires app-data pour
  targetSdk >= 29 (W^X)** (rapport 7842f130, v0.29.0) : SELinux refuse
  `execute` sur `app_data_file` au domaine `untrusted_app_29+` — le
  `ProcessBuilder.start()` lève `IOException` (EACCES) SANS autre indice.
  Toute app qui exécute des binaires extraits (terminal intégré, outil
  téléchargé) doit cibler 28 (précédent Termux) — ADR 0045. Symptôme
  codeide v0.29.0 : « erreur inattendue » au second stage du bootstrap
  alors que l'extraction avait réussi.
- **`TerminalSession` (Termux) doit naître sur le thread principal**
  (rapport 511e1c7f, v0.31.1) : son constructeur crée un `Handler` sans
  Looper explicite (`MainThreadHandler`) — hors du thread principal,
  `Can't create handler inside thread … not called Looper.prepare()`
  IMMÉDIATEMENT. Termux crée ses sessions sur l'UI, toute intégration du
  terminal-emulator doit en faire autant (`withContext(dispatchers.main)`,
  ADR 0046). Corollaire : `Dispatchers.Main` d'Android n'existe à
  l'exécution que si `kotlinx-coroutines-android` est dans le graphe
  (chargement ServiceLoader) — un module qui consomme
  `DispatcherProvider.main` en production doit déclarer l'artefact
  lui-même.
- **Une opération longue sans sortie visible est un future rapport de
  panne muet** (rapports 7842f130/511e1c7f) : l'installation du bootstrap
  drainait stdout en le JETANT — le message « la configuration des
  paquets a échoué » couvrait trois étapes sans dire laquelle. Toute
  étape pilotant un sous-processus au nom de l'utilisateur expose sa
  sortie au fil de l'eau (journal d'écran) et ses détails typés à
  l'échec (sous pli) ; stdout se draine TOUJOURS (tuyau bloquant) mais
  se jette JAMAIS.
- **`ExpiringTargetSdkVersion` et `ExpiredTargetSdkVersion` sont DEUX
  issues lint distinctes** (v0.31.1→v0.31.3) : la première est le
  CONSEIL (sévérité avertissement, montée en erreur par
  `warningsAsErrors`), la seconde l'ERREUR directe (« Google Play
  requires… ») — désactiver la seule première laisse la CI rouge.
  Toute exception lint se vérifie en exécutant LA tâche qui échoue
  (`:app:lintDebug`), pas en supposant l'ID couvert.
- **Un répertoire « évident » d'une archive n'y est pas forcément**
  (retour v0.31.2, corrigé v0.31.3) : l'archive publiée n'embarque pas
  `tmp/`, le bootstrap officiel oui — `TMPDIR` pointait donc dans le
  vide et le premier `apt update` mourait en `mkstemp` ENOENT (errno 2
  = « n'existe pas », PAS errno 13 = « permission refusée » : lire le
  code d'errno avant de conclure une cause permission). Les
  répertoires attendus par l'environnement d'un préfixe extrait se
  garantissent côté applicatif, en profondeur (extraction + chaque
  lancement).
- **`@ActivityScoped` lie l'implémentation à l'activité qui l'INJECTE**
  (rapport f2699ac5, v0.31.4) : une classe de navigation pensée « pour
  MainActivity » devient fautive dès qu'une seconde activité plein
  écran l'injecte — son layout n'a pas le conteneur attendu et chaque
  appel lève. Toute dépendance scopée à l'activité doit se poser la
  question « que deviens-je injecté ailleurs ? » : getter nullable,
  repli documenté (`finish()`, routage vers l'hôte) — jamais `error()`.
- **Un flux d'état UI reconstruit écrase tout champ non alimenté**
  (retour v0.31.4) : traduire l'état domaine en état de rendu SANS le
  journal revenait à vider l'écran à chaque étape — deux flux
  asynchrones (état, journal) se COMBINENT dans un seul flux de rendu,
  ils ne s'écrasent pas l'un l'autre. Corollaire : un rendu par phase
  doit régler explicitement CHAQUE bouton de l'écran (v0.31.2 laissait
  « Fermer » invisible et « Réessayer » figé après lancement — les
  transitions se vérifient phase par phase, pas seulement la première).
- **Le contrat `TerminalViewClient.onScale` n'est pas celui qu'on
  croit** (retour v0.31.5, vérifié sur le bytecode v0.118.3) : la vue
  ACCUMULE un facteur, le passe au client, et ne l'applique JAMAIS
  elle-même — retourner le facteur intact rend le pincement inerte
  sans aucun autre symptôme. Le client applique la taille puis
  retourne `1.0f` (consommé). Diagnostiquer une bibliothèque binaire
  sans source publié : désassembler (`javap -c`) l'artefact du cache
  Gradle — dix minutes de bytecode valent mieux qu'une heure de
  suppositions.
- **Un conteneur d'onglets reconstruit mange les taps** (retour
  v0.31.5) : `removeAllTabs` + `addTab` à chaque émission d'un état
  qui change toutes les 250 ms détruit les vues sous le doigt de
  l'utilisateur — la sélection programmatique garde son
  anti-réentrance, mais le GESTE, lui, n'atterrit jamais. Et une
  collection reconstruite doit réévaluer CHAQUE écouteur capturant un
  identifiant positionnel (fermeture/renommage décalent les indices) :
  diff ou rien.
- **Une vérification asynchrone n'est pas une garantie** (retour
  v0.31.5) : un état « Valide » de vérification datée (délai 400 ms)
  peut être périmé au moment de l'action — une action qui écrit doit
  re-vérifier sa précondition dans l'instant (pré-vol), et son échec
  doit offrir la SORTIE que le message réclame (un bouton « Réessayer »
  qui relance la même requête condamnée est un piège, pas une action).
  Corollaire : une erreur typée doit remonter SA raison réelle à
  chaque étage (l'insertion en base refusée n'est pas un `Io`
  générique), et porter des détails lisibles — l'écran d'échec les
  affiche, le prochain rapport d'appareil devient diagnosticable.
- **Le point initial d'un nom de fichier n'est PAS une extension**
  (rapport 4a4526aa, v0.31.6) : `.gitattributes`, `.gitignore`,
  `.editorconfig` sont des fichiers CACHÉS sans extension — le
  fournisseur SAF les complète par l'extension canonique du type
  demandé (`.gitattributes.txt` pour `text/plain`), exactement comme
  les noms sans point. Toute décision MIME fondée sur `contains('.')`
  fabrique des « renommages hostiles » de pure invention : la règle
  est `lastIndexOf('.') <= 0` (partagée : `sansExtensionReelle`), et le
  type privé sans complétion (`text/x-codeide`) protège le nom exact.
  Corollaire : quand un écran d'échec affiche des détails techniques,
  les LIRE — « .gitattributes » dans un message « un dossier porte déjà
  ce nom » désignait le FICHIER en cause, pas le dossier.
- **Un diff de conteneur doit identifier ce qu'il réutilise**
  (rapport 4a4526aa, v0.31.6) : resynchroniser « position par
  position » suppose que la position N porte un élément de la même
  ESPÈCE — quand la liste grandit, la position visée peut être occupée
  par un onglet d'une AUTRE espèce (le « + », dont la vue est un
  `ImageView`) : binder la vue trouvée par habitude lève un NPE
  `Missing required view with ID`. La réutilisation se décide par
  IDENTITÉ (`===` avec l'onglet « + »), pas par présence ; le cas
  minimal (conteneur à UN seul élément, celui qu'on ne borde jamais)
  fait partie de la régression.
- Environnement recyclé (JDK/SDK supprimés) : relancer `scripts/setup-env.sh`,
  puis **toujours** `source scripts/env.sh` avant `./gradlew`, builds en
  avant-plan avec délai explicite (les arrière-plans sont tués entre appels
  d'outils).
- **Jamais `*/` dans un KDoc, même entre backticks** (`platforms/android-*/…`
  ferme le commentaire prématurément — recroisé à l'étape T1 après l'avoir
  rencontré à la 16). Écrire la constante autrement (ex. « un `android.jar`
  sous `platforms` »).
- **`Reader.readLines()` ferme le flux sous-jacent** (`use`/`useLines` dans
  l'implémentation) : sur un `ZipInputStream`, l'entrée suivante devient
  illisible (« Stream closed »). Lire ligne à ligne avec `BufferedReader`
  sans fermeture, comme l'installateur Termux (rencontré à l'étape T2).
- **Tout appel suspendu (même `withContext(NonCancellable)`) depuis une
  coroutine déjà annulée ne revient pas** (kotlinx-coroutines 1.11,
  constat empirique à l'étape T2) : dans le gestionnaire d'annulation,
  le nettoyage est **synchrone**, sinon l'état terminal n'est jamais
  publié et l'UI reste bloquée sur « en cours ».
- **ktlint (via spotless) signale `max-line-length` sur la sortie
  formatée, pas sur la source** : une ligne de 117 caractères peut être
  signalée à une position décalée ; garder les littéraux longs (chemins,
  URLs) hors des lignes de code — les extraire en constantes. Diagnostic
  éprouvé à l'étape T2 : la position signalée ne correspond à rien sur
  le disque, c'est la projection formatée qui compte.
- **Le fork `com.gradleup.shadow` CONSERVE le package historique**
  (leçon G2) : le plugin s'applique par l'id `com.gradleup.shadow` mais
  les classes vivent toujours sous `com.github.jengelman.gradle.plugins.shadow.*`
  — importer depuis `com.gradleup.*` échoue avec « Unresolved reference ».
- **`main()` dans un `object` avec `@JvmStatic` → Main-Class = le nom de
  l'object**, pas `XxxKt` (leçon G2) : un fat jar avec Main-Class erroné
  démarre et meurt sur `ClassNotFoundException` — tester le JAR produit
  (`java -jar`) AVANT de livrer, pas seulement la tâche shadowJar.
- **Les ressources d'une dépendance de test vivent dans un jar** (leçon
  G2) : `Path.of(url.toURI())` sur une ressource `jar:` lève
  `FileSystemNotFoundException` — monter le zipfs explicitement
  (`FileSystems.newFileSystem`) quand le scheme est `jar` ; depuis le
  module lui-même, c'est un simple dossier.
- **BottomNavigationView distribue l'écouteur SYNCHRONEMENT à
  l'affectation d'une destination nouvellement sélectionnée** (plantage
  3d8ede67, v0.25.0) : affecter la destination initiale AVANT
  d'enregistrer l'écouteur, sinon le rendu s'exécute en plein
  `onCreate`, avant que le reste soit branché (menu toolbar, lateinit).
- **La sortie d'un process séparé est son journal** (règle 14, G2) :
  l'orchestrateur JVM ne peut pas appeler AppLogger — son stderr EST le
  canal (tag `gradle-server`), exemption detekt ciblée plutôt qu'un
  contournement trompeur.
- **Écrire le client révèle les défauts du serveur** (leçon G3) : la
  corrélation §3.2 exige que la réponse ÉCHOYE l'identifiant de la requête
  — les handlers de G2 généraient un identifiant neuf, la promesse du
  client n'était jamais résolue. Les deux bouts d'un protocole se testent
  l'un contre l'autre, jamais chacun dans son coin.
- **`Channel`, pas `SharedFlow`, pour la sortie de build** (leçon G3) : un
  canal tamponne les valeurs émises sans abonné ET reste lisible après sa
  fermeture (drain complet) — un `SharedFlow` à rejeu nul perd l'historique
  pré-abonnement, précisément le bug `postValue` documenté par le prompt.
  États = `StateFlow` (conflation légitime) ; flux = canaux (jamais).
- **Robolectric n'a AUCUNE shadow de `LocalSocket`/`LocalServerSocket`**
  (vérifié 4.17, leçon G3) : isoler la logique derrière une couture
  (`SessionTooling`) testée en fake, la colle socket en couche mince
  filtrée de kover — même précédent que la colle Termux/JNI (ADR 0035).
- **Le namespace abstrait d'Android est inaccessible depuis un client
  JDK** (leçon G3) : `LocalServerSocket(String)` bind en ABSTRACT, invisible
  pour `UnixDomainSocketAddress` — l'astuce publique est
  `LocalSocket.bind(FILESYSTEM)` puis `LocalServerSocket(FileDescriptor)`
  (API 8), sans toucher aux interfaces cachées.
- **Un fragment qui obtient un `@HiltViewModel` par `by viewModels()`
  DOIT porter `@AndroidEntryPoint`** (rapport 30e81ee0, v0.24.0 sur
  appareil) : sans elle, la factory par défaut tente la réflexion sur un
  constructeur sans argument — `NoSuchMethodException <init> []`,
  invisible au compile time ET dans les tests JVM (ils construisent le
  ViewModel directement). Le test de régression vérifie l'annotation par
  réflexion ; tout nouveau fragment Hilt mérite son équivalent.
- **detekt n'a PAS d'analyse incrémentale par fichier** (mesuré T6,
  1.23.8) : une tâche réexécutée relit tout le source set du module.
  Mais Gradle saute les modules inchangés (up-to-date, 1,6 s) et le
  build cache restitue tout après un `clean` (6,5 s, from cache) — le
  `clean` systématique historique n'apportait rien : vérifications
  ciblées par module en cours d'étape, chaîne complète sans `clean` en
  fin (ADR 0037), from-scratch garanti par la CI GitHub au push.
- **`OutputStream.write(Int)` n'écrit QUE l'octet de poids faible**
  (rencontré à G1 dans le framing) : écrire un Int 4 octets
  gros-boutiste exige quatre `write` masqués — un `write(taille)` seul
  corrompt silencieusement le protocole dès que des octets suivent.
  Les tests du round-trip l'ont attrapé : écrire les tests AVANT le
  transport paie.
- **Kotlin imbrique les commentaires de bloc** : `/*` au milieu d'un
  KDoc (ex. le joker `golden/*.json`) ouvre un commentaire **imbriqué**
  jamais refermé — « Unclosed comment » à des lignes invraisemblables.
  Cousine de la leçon `*/` prématuré : jamais de séquence `/*` ni `*/`
  dans un commentaire, même entre backticks.
- **Le démon Gradle peut être tué par l'environnement** (mémoire) sur
  `lintDebug` ou les tests parallèles : relancer en deux parties et
  `--max-workers=2` pour `testDebugUnitTest koverVerify` (éprouvé T2).
  Si le démon meurt encore : `GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m
  -XX:MaxMetaspaceSize=512m ..."` + `--max-workers=1` (éprouvé T3, le
  service web cohabitant consomme aussi de la mémoire).
- **Apostrophes des chaînes Android via harnais Python** : dans un
  heredoc Python, `\'` devient une apostrophe nue dans le XML (aapt le
  refuse) — écrire `\\'` pour obtenir `\'` ; toujours vérifier avec
  `python3 -c "repr(...)"` (rencontré T3). Même discipline pour
  l'insertion de fonctions Kotlin par harnais : vérifier la position
  réelle des accolades de classe avant d'insérer (AppNavigatorImpl).
- **android.jar éclipse les API java.* récentes à la COMPILATION des tests
  unitaires Android** (leçon G4) : le classpath de compilation des tests
  porte android.jar, et pour les classes java.* couvertes par les builtins
  Kotlin, c'est leur version JDK 8 qui gagne — `Process.onExit()` (JDK 9)
  et `ServerSocketChannel.open(ProtocolFamily)` (JDK 15) « ne résolvent
  pas » alors qu'elles existent au runtime (JDK 21) et dans android.jar.
  Diagnostiqué par bissection du classpath avec kotlinc en direct.
  Contournements éprouvés : réflexion ciblée (précédent `pid` de
  `ProcessusGere`) et sondage `isAlive` (miroir du port production) ; les
  API java.* récentes vivent dans les modules purs (tooling:server) ou
  derrière ces coutures.
- **La mémoire de la machine de build est comptée** (leçons G4 cumulées) :
  un test qui lance un VRAI sous-processus doit lui passer `-Xmx` borné
  (sinon 1/4 de la RAM par défaut → le démon Gradle meurt), et les tests
  lourds se séparent des légers par `--tests` en cours d'étape avant la
  chaîne complète.
