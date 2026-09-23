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
- minSdk 26 ; compileSdk/targetSdk = dernière API stable (37.2/37 à ce jour).
- UI : vues XML + ViewBinding, Activities + Fragments, Material 3.
  **Pas de Jetpack Compose** (ADR 0002).
- Langues de l'interface : français (`values/`, défaut) + anglais (`values-en/`).
- Stockage des projets : SAF (URI, jamais `File`) — ADR 0003.
- SemVer : `0.N.0` par étape validée, `0.N.M` par correction.
- Aucune permission `INTERNET` ni `MANAGE_EXTERNAL_STORAGE` en Phase 1.

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
- Étape 13+ : `feature:editor` dépend de `com.github.jjoblab:cel-ui` via
  **JitPack** (dépôt `maven { url = uri("https://jitpack.io") }` à ajouter —
  seule dépendance externe autorisée dans une feature, exception documentée ;
  accès réseau à `jitpack.io` vérifié le 2026-09-23).

## Commandes

```bash
source scripts/env.sh                      # JAVA_HOME, ANDROID_HOME, PATH
./gradlew clean spotlessCheck detekt checkModuleDependencies lintDebug \
  testDebugUnitTest koverVerify assembleDebug   # vérification complète (doit être verte)
./gradlew spotlessApply                    # formatage avant commit
scripts/bump-version.sh minor              # incrémente la version
scripts/package.sh 0                       # dist/ : archive + APK + SHA256SUMS
scripts/verify-archive.sh dist/CodeIDE-v0.1.0-etape00.zip   # archive autonome ?
```

## Définition de « terminé » (par étape)

Fonctionnalités de l'étape sans débordement ; vérification complète verte ;
tests de la logique ajoutée (≥ 80 % sur `core:model`/`core:domain`) ; KDoc et
docs à jour ; `CHANGELOG.md`, `ROADMAP.md`, `AGENTS.md` à jour ; aucun TODO non
tracé ; version incrémentée, tag Git, archive créée **et vérifiée** ;
commits et tag **poussés sur `origin`** (`https://github.com/jjoblab/CodeIDE.git`,
`git push origin main --follow-tags`) — ou échec de publication signalé
explicitement dans le rapport (prompt compagnon, section 1.2) ; rapport
remis (format section 14) puis attente du « GO ».

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
- [ ] Étape 13 — Fondations de l'espace de travail (`EditorActivity` trois zones, `cel-ui` via JitPack) → v0.14.0
- [ ] Étape 14 — Explorateur de fichiers (tiroir, arborescence paresseuse) → v0.15.0
- [ ] Étape 15 — Intégration de l'éditeur et onglets de fichiers → v0.16.0
- [ ] Étape 16 — Panneau inférieur (journal applicatif, stubs Sortie/Problèmes) → v0.17.0
- [ ] Étape 17 — Actions du tiroir et finitions de l'espace de travail → v0.18.0
- [ ] Étape 18 — Audit final de Phase 1 → v0.19.0

Détail de chaque étape : `docs/ROADMAP.md` et section 11 du prompt maître.
