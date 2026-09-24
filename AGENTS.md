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
- Prochaine : étape 31 (= Système de plugins — cf. docs/ROADMAP.md ;
      les prompts compagnons LSP et formatage suivront).

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
