# CodeIDE — Roadmap v0.46.0+

> Dernière mise à jour : 2026-10-01
> Version courante : 0.45.0

## État actuel (v0.45.0)

### Fonctionnel ✅
- **Tooling Gradle** : sync d'ouverture, étapes dynamiques progressives, sync suivante immédiate (empreinte SHA-256 + revalidation silencieuse), chip d'action unique, stats classpath par module, progress circulaire (AnneauTournant 16dp)
- **Console hybride** (v0.42.0, ADR 0074) : zone structurée (RecyclerView : étapes, tâches, synthèse) + zone texte (lignes brutes par append O(1), loties par trame, tampon borné 2 000) — un build de 725 ms s'affiche en < 1 s
- **Exécution** : bouton Run (détecte `fun main()`), support stdin/readln (BuildInput + champ saisie), println → console
- **Templates** (v0.45.0, ADR 0076/0077) : kotlin-jvm, java, android-app (variantes `projectType` empty/no/basic-activity + `language` kotlin/java + sections `minSdk`/`targetSdk`/`applicationId` + 5 dépendances au choix), android-library (.aar), spring-boot (4 dépendances au choix), kotlin-multiplatform (3 dépendances au choix), gradle-plugin — tous vérifiés par `scripts/verify-templates.sh` (**37 combinaisons**, build réel, tests, exécution, APK, AAR, publication + consommation E2E du plugin, renommage de l'aperçu) ; package `com.example.<app|artefact>` par convention, chemins `{{packageName|packagePath}}`, Android riche (strings/colors/themes/proguard/tests, tiroir Material 3, KSP 2.3.12 + Room + Hilt avec le Kotlin intégré AGP), Spring Boot 4.1.1, KMP (actual câblé), plugin Gradle `kotlin-dsl` (compilateur embarqué, testkit ProjectBuilder)
- **Éditeur** : code-editor 3.40.0, coloration syntaxique, auto-sauvegarde, onglets, explorateur
- **Terminal** : Termux, sessions shell, pty, pont SAF/FUSE
- **Diagnostics** : parseur javac/kotlinc, onglet Problèmes, inline dans l'éditeur
- **Wizard enrichi** (v0.45.0, ADR 0077) : rendu 100 % dynamique depuis les manifestes (visibleWhen, defaultFrom, validator), sections Android (`minSdk`/`targetSdk`/`applicationId`), interrupteurs de dépendances, **aperçu de l'arborescence renommable** avant création (dry-run = écriture, renommages par identité originale, survie à la mort du processus)

### Problèmes connus ❌
- (aucun ouvert — le renommage de l'aperçu livré en phase 4 clôt le dernier point connu)

---

## Phase 1 — Performance console (CRITIQUE) ✅ v0.42.0

> Objectif : un build de 725 ms s'affiche en < 1s, pas 2 min.
> **Livré (ADR 0074)** : architecture hybride + toutes les optimisations ci-dessous.

### 1.1 Architecture hybride (comme Android Studio) ✅

Séparer la console en deux zones :
- **Zone structurée** (RecyclerView) : étapes de sync + tâches de build + synthèse — mise à jour en place via DiffUtil (peu de rangées, O(1) par mise à jour) ✅
- **Zone texte** (TextView monospace scrollable) : lignes stdout/stderr brutes de Gradle — append direct, **pas de DiffUtil, pas de StateFlow par ligne** ✅

**Implémentation** :
- `PanneauConsoleFragment` : un `RecyclerView` (rangées structurées) + un `TextView` (texte brut) empilés verticalement ✅
- `GradleService` : les lignes brutes vont dans un flux dédié `lignesBrutes` (`SharedFlow` borné à 2 000 événements — le rejeu EST le tampon), pas dans `etat.lignes` ✅
- `LigneConsole.Tache` et `LigneConsole.Etape` restent dans `etat.lignes` (peu de rangées, DiffUtil OK) ✅
- Le `TextView` est mis à jour par `append()` direct — O(1) par ligne, LOTI par trame (un seul append et une seule passe de layout par trame) ✅

### 1.2 Optimisations supplémentaires ✅

- **Borner le TextView** à 2000 lignes (tête tronquée, comme `NB_LIGNES_MAX`) ✅ (le rejeu du SharedFlow est borné par construction)
- **Auto-scroll** : le TextView scroll automatiquement vers le bas pendant un build en cours (tant que l'utilisateur y est resté — jamais rabattu) ✅
- **Pas de filtrage** : tout s'affiche, comme Android Studio ✅

---

## Phase 2 — Fix templates ✅ v0.43.0

> Objectif : les trois modèles de la v0.41.1 (android-app, spring-boot,
> KMP) étaient cassés à la GÉNÉRATION (clé i18n manquante, chemins codés
> en dur, actual KMP non câblé, versions incompatibles Gradle 9).
> **Livré (ADR 0075)** : tout ce qui suit, plus la couverture complète de
> `scripts/verify-templates.sh` (24 combinaisons, build réel).

### 2.1 Corriger les clés i18n manquantes ✅

- Ajouter `gitattributes.entete` dans `i18n/fr.json` et `en.json` de :
  - `kotlin-multiplatform`
  - `spring-boot`
  - `android-app`
- (livré au-delà : README/.gitignore/catalogue des trois modèles
  entièrement i18n fr/en, comme kotlin-jvm et java)

### 2.2 Corriger le package name ✅

- Android : `com.example.<appName>` par défaut — fonction
  `packageFromAppName` (pas `packageFromNameAndAuthor`)
- Spring Boot : `com.example.<artifactId>` par défaut — fonction
  `packageFromArtifactId`
- KMP : `com.example.<artifactId>` par défaut — fonction
  `packageFromArtifactId`
- Kotlin JVM : `packageFromNameAndAuthor` conservé (projet JVM, pas de
  convention Android)
- (livré au-delà : chemins `{{packageName|packagePath}}`, options
  communes câblées, filtre `resourceName` pour les noms de ressources)

### 2.3 Enrichir le template Android app ✅

Fichiers livrés :
- `app/src/main/res/values/strings.xml` — `<resources><string name="app_name">{{appName}}</string></resources>`
- `app/src/main/res/values/colors.xml` — couleurs de base
- `app/src/main/res/values/themes.xml` — `Theme.Material3.DayNight` avec `parent` (+ `colorPrimary`/`colorSecondary`)
- `app/proguard-rules.pro` — règles ProGuard de base (+ `buildTypes.release` minifié)
- `app/src/test/.../ExampleUnitTest.kt` — test unitaire simple
- `.gitignore` — complet (local.properties, .gradle, build, *.apk, *.aab) + `app/.gitignore`
- (livré au-delà : AGP 9.4.1 Kotlin intégré, `compileSdk 37.2`, JUnit 4,
  manifeste `@string/app_name` + `@style/Theme.<Nom>`, allowBackup/supportsRtl)

### 2.4 Enrichir le template Spring Boot ✅

Fichiers livrés :
- `src/main/resources/application.yml` — configuration YAML (port, profil) — remplace `application.properties`
- `src/main/kotlin/.../GreeterRepository.kt` — repository pattern
- `src/test/kotlin/.../ApplicationTests.kt` — test de contexte Spring
- (livré au-delà : Spring Boot 4.1.1, Kotlin 2.2.21, plugin
  `kotlin-spring`, BOM `platform()`, `POST/GET /salutations`)

### 2.5 Enrichir le template KMP ✅

- `src/jvmMain/kotlin/.../Greeter.jvm.kt` — implémentation `actual` du Greeter (câblée au manifeste — le fichier existait mais n'y figurait pas)
- Tests `commonTest` plus complets : ordre de `greetAll`, plateforme nommée
- (livré au-delà : Kotlin 2.2.21, tâche `run` JavaExec exécutable et vérifiée)

---

## Phase 3 — Templates avancés ✅ v0.44.0

> Objectif : le modèle Android grandit (types de projet, langage Java) et
> deux modèles s'ajoutent (bibliothèque .aar, plugin Gradle).
> **Livré (ADR 0076)** : tout ce qui suit, `projectType` rendu en cartes
> radio (3 valeurs), `language` en tuiles segmentées, et
> `scripts/verify-templates.sh` étendu à 32 combinaisons (builds réels,
> APK, AAR, publication + consommation E2E du plugin).

### 3.1 Types de projet Android ✅

- Paramètre `projectType` (CHOICE) sur `android-app` :
  - `empty-activity` — Activity + layout (inchangé, défaut)
  - `no-activity` — projet Android sans Activity (manifeste `{{#if}}`,
    aucune `MainActivity`, aucun layout — `Greeter` seul)
  - `basic-activity` — Activity + Fragment + tiroir de navigation Material 3
    (`MainActivity` avec `ActionBarDrawerToggle`, `FragmentAccueil` +
    arguments, `activity_main_tiroir.xml`, `menu/tiroir.xml`, thème
    `NoActionBar`, dépendance `drawerlayout`)
- (livré au-delà : thème conditionnel, chaînes du tiroir i18n fr/en,
  contrôles structurels par variante dans verify-templates.sh)

### 3.2 Support Java pour Android ✅

- Paramètre `language` (CHOICE kotlin/java) sur le modèle Android existant
  (pas de modèle séparé) : `MainActivity`, `FragmentAccueil`, `Greeter`,
  `ExampleUnitTest` et `GreeterTest` déclinés en `.java` — le bloc
  `kotlin { compilerOptions }` disparaît du build en Java
- (livre au-delà : ViewBinding par champs publics en Java, imports
  `static` pour les assertions JUnit)

### 3.3 Templates supplémentaires ✅

- `android-library` — bibliothèque Android (.aar) : module `:library`,
  `com.android.library`, `consumer-rules.pro`, API publique `Greeter`,
  tests, README d'intégration (`assembleRelease` → `library/build/outputs/aar/`)
- `gradle-plugin` — plugin Gradle en Kotlin : `kotlin-dsl` (compilateur
  **embarqué** dans Gradle — un KGP externe 2.2.21 se heurte au
  `kotlin-reflect` 2.4.0 du `gradleApi()`), `java-gradle-plugin`,
  `maven-publish` avec artifactId valide, extension `greeting` + tâche
  `greet` (`@DisableCachingByDefault` pour `validatePlugins`), tests
  ProjectBuilder (testkit), `pluginId`/`packageName` dérivés de
  `artifactId` (`packageFromArtifactId` réutilisée), classe principale
  `{{projectName|resourceName}}Plugin`
- `compose-app` — **différé** (ADR 0002 interdit Compose jusqu'à
  réévaluation explicite)
- (livré au-delà : 32e combinaison E2E — publication maven locale du
  plugin puis application + exécution `greet` depuis un projet
  consommateur jetable)

---

## Phase 4 — Wizard enrichi ✅ v0.45.0

> Objectif : le wizard suit la croissance des modèles — sections Android,
> aperçu **renommable** et dépendances cochables.
> **Livré (ADR 0077)** : tout ce qui suit, éprouvé par 37 combinaisons de
> `scripts/verify-templates.sh` (build réel) et 1445 tests unitaires.

### 4.1 Adapter aux nouveaux paramètres ✅

- Le wizard lit les paramètres depuis `template.json` dynamiquement
- `visibleWhen`, `defaultFrom`, `validator` vérifiés pour les nouveaux paramètres (`ModelesPhase4Test`)
- Ajouter des sections spécifiques à Android (minSdk, targetSdk, applicationId)
- (livré au-delà : `applicationIdFromPackageName` — sixième fonction `defaultFrom`, chaîne `appName → packageName → applicationId` ; minSdk 24–34, plancher Navigation 2.10.2 ; targetSdk 34–37, défaut 37)

### 4.2 Aperçu de structure ✅

- Afficher l'arbre des fichiers qui seront générés avant la création (livré dès l'étape 11, ADR 0017)
- Permettre de modifier le nom de fichiers/dossiers (crayon par ligne, renommages par identité originale, création emportée, harnais couvert par `kt-app-renoms`)

### 4.3 Choix de dépendances ✅

- Checkboxes pour ajouter des dépendances communes :
  - Android : Retrofit, Room, Coroutines, Navigation, Hilt (Room/Hilt via **KSP 2.3.12**, Kotlin intégré AGP — miroirs Java en trois fichiers)
  - Spring Boot : JPA, Security, Actuator, Validation (H2 en mémoire, BOM sans version)
  - KMP : Serialization, Coroutines, DateTime (fichiers d'usage par dépendance)

---

## Phase 5 — LSP (long terme)

### 5.1 LSP Kotlin

- Utiliser le classpath préparé (`.codeide/local/lsp-classpath.json`)
- Intégrer un LSP Kotlin (ex. kotlin-language-server) via le tooling serveur
- Autocomplétion, hover, go-to-definition, diagnostics en temps réel

### 5.2 LSP Java

- jdtls (Eclipse JDT Language Server) ou java-language-server
- Même architecture que le LSP Kotlin

---

## Phase 6 — Fonctionnalités IDE

### 6.1 Recherche globale
- Recherche de texte dans tous les fichiers du projet
- Remplacement multi-fichiers

### 6.2 Git intégré
- Statut des fichiers (modifié/ajouté/supprimé)
- Diff visuel
- Commit/Push depuis l'app

### 6.3 Refactoring
- Renommer (symbole → toutes les références)
- Extraire (méthode, variable)
- Importer automatiquement

### 6.4 Build variants (Android)
- Sélection debug/release
- Product flavors
- Build types

---

## Phase R — Refonte du parcours d'installation (E1–E6)

Prompt « Refonte complète du parcours d'installation de l'environnement »
(dépôt `jjoblab/CodeIDE`), exécuté en parallèle de la refonte de
`jjoblab/codeide-tools` (manifeste v2) — contrat commun § 12 des deux
prompts. Motivation : ~1 400 lignes de shell générées non testables,
double téléchargement cmdline-tools, « SDK non fonctionnel » muet
(cause racine établie : ADR 0084), orchestration par frappe dans un pty.

- **E1** ✅ livré v0.55.0 (ADR 0084/0085/0086) : investigation (sorties
  réelles capturées), ADR d'architecture (4 phases vérifiées, ports,
  état persisté), modèle de domaine + résolveur de plan pur (20 tests),
  catalogue de versions (build-tools 35.0.2 aarch64, platform
  android-37.2, JDK 17).
- **E2** — cadre commun : orchestrateur, `CommandRunner`,
  `DownloadManager`, état persisté, service de premier plan + phases 1
  (Bootstrap) et 2 (PackageTools).
- **E3** — phase 3 Java (openjdk-17, `java`/`javac` démarrent, test TLS).
- **E4** — phase 4 Outils Android (manifeste v2, composants, licences
  après acceptation, câblage Gradle/aapt2, relance du daemon).
- **E5** — maquettes validées puis nouvelle interface (écran
  d'installation stepper + écran Environnement des Paramètres).
- **E6** — migration des installations existantes, suppression de
  l'ancien code (`EcrivainSdkAndroidCli`, `EcrivainCodeideEnvCli`,
  `ConfigurationEnvTermux`, `Aapt2Deployeur`…), ADR 0082/0083 marqués
  remplacés, documentation.

## Priorisation

| Phase | Priorité | Effort | Impact utilisateur |
|---|---|---|---|
| 1 — Console perf | **CRITIQUE** ✅ livré v0.42.0 (ADR 0074) | Moyen | Très haut — l'app devient utilisable |
| 2 — Fix templates | **Haute** ✅ livré v0.43.0 (ADR 0075) | Faible | Haut — les templates marchent |
| 3 — Templates avancés | **Haute** ✅ livré v0.44.0 (ADR 0076) | Moyen | Moyen — plus de choix |
| 4 — Wizard | **Moyenne (prochaine)** ✅ livré v0.45.0 (ADR 0077) | Moyen | Moyen — meilleure UX |
| 5 — LSP | Basse | Très haut | Très haut — transforme en IDE |
| 6 — Fonctionnalités IDE | Basse | Très haut | Très haut — fonctionnalités pro |
