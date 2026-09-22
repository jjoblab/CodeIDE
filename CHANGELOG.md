# Journal des modifications

Ce journal suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/),
en français. Le versionnage suit [SemVer](https://semver.org/lang/fr/) :
`0.N.0` par étape validée, `0.N.M` pour une correction après retour utilisateur.

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
