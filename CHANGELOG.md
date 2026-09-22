# Journal des modifications

Ce journal suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/),
en français. Le versionnage suit [SemVer](https://semver.org/lang/fr/) :
`0.N.0` par étape validée, `0.N.M` pour une correction après retour utilisateur.

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
