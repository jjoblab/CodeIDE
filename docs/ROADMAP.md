# CodeIDE — Roadmap v0.46.0+

> Dernière mise à jour : 2026-10-01
> Version courante : 0.45.0

## État actuel (v0.45.0)

### Fonctionnel ✅
- **Tooling Gradle** : sync d'ouverture, étapes dynamiques progressives, sync suivante immédiate (empreinte SHA-256 + revalidation silencieuse), chip d'action unique, stats classpath par module, progress circulaire (AnneauTournant 16dp)
- **Console hybride** (v0.42.0, ADR 0074) : zone structurée (RecyclerView : étapes, tâches, synthèse) + zone texte (lignes brutes par append O(1), loties par trame, tampon borné 2 000) — un build de 725 ms s'affiche en < 1 s
- **Exécution** : bouton Run (détecte `fun main()`), support stdin/readln (BuildInput + champ saisie), println → console
- **Templates** (v0.45.0, ADR 0076/0077) : kotlin-jvm, java, android-app (variantes `projectType` empty/no/basic-activity + `language` kotlin/java + sections `minSdk`/`targetSdk`/`applicationId` + 5 dépendances au choix), android-library (.aar), spring-boot (4 dépendances au choix), kotlin-multiplatform (3 dépendances au choix), gradle-plugin — tous vérifiés par `scripts/verify-templates.sh` (**37 combinaisons**, build réel, tests, exécution, APK, AAR, publication + consommation E2E du plugin, renommage de l'aperçu) ; package `com.example.<app|artefact>` par convention, chemins `{{packageName|packagePath}}`, Android riche (strings/colors/themes/proguard/tests, tiroir Material 3, KSP 2.3.12 + Room + Hilt avec le Kotlin intégré AGP), Spring Boot 4.1.1, KMP (actual câblé), plugin Gradle `kotlin-dsl` (compilateur embarqué, testkit ProjectBuilder)
- **Éditeur** : code-editor 3.41.0, coloration syntaxique, auto-sauvegarde, onglets, explorateur
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

## Phase 5 — LSP

### 5.1 LSP Java ✅ (v0.91.0 → v0.108.0)

- Livré **in-process** (compilateur ECJ embarqué, module vendored ADR 0108) — détail dans `docs/LSP_JAVA.md`.
- Classpath modèle v8 (ADR 0113), index disque segments par jar (ADR 0111), session intégrée au cycle de vie (I1–I7, RegistreFermeture), routage `jo.codeide.projet_cible` (v0.106.0).
- Correctifs d'exploitation v0.108.0 : intrinsèques du compilateur (StringConcatFactory), downgrade honnête databinding.

### 5.2 LSP Kotlin — K1 à K5 livrés (v0.110.0, v0.112.0, v0.113.0, v0.115.0, v0.116.0)

- Décision : **parseur vendoré** (grammaire officielle du compilateur, Apache 2.0) + **modèle de symboles propre** conservateur, in-process — fwcd/kotlin-language-server **écarté**, Analysis API K2 réduite à un éventuel « mode profond » à la demande (étude CodeAssist).
- **K1 livré v0.110.0** : module `:lsp:kotlin`, vendoring **v2.3.0** octet identique (ADR 0115, VENDOR.md, script de re-sync), façade `Kt*`, suite de parité (corpus dépôt 818 fichiers / 0 erreur, digeste baseline), SPI ServiceLoader + descripteur de pack (découpage réel différé), jar 257 Ko, kover 91,7 %.
- **K2 livré v0.112.0** : session LSP minimale — contrat de session derrière le SPI (D6 : diagnostics neutres, aucun LSP4J dans le moteur), diagnostics syntaxiques conservateurs `kt.syntax`/`kt.unusedImport`/`kt.conflictingImport`, adaptateur LSP4J + façade `SessionLspKotlin` dans `:lsp:host` (définitions cel-lsp `kt`/`kts`), éditeur bilingue (LspProject unique, routage par extension, cycle de vie registre I2/I7) — 25 tests.
- **K3 livré v0.113.0** : symboles + classpath + les trois requêtes — complétion (membre/noms/imports, auto-import), survol (chaîne de résolution), définition (projet, inter-fichiers par scan des racines sources) ; vue de classpath NEUTRE partagée avec l'index Java (D4), lecteur de class-files + décodeur @Metadata (kotlin-metadata-jvm 2.2.10), index disque des appelables (clés top:/ext:), scan paresseux des racines sources — 27 tests, définition = projet seul (D3 documenté).
- **K4 livré v0.115.0** : inférence bornée + sémantique conservatrice — SMART CASTS positionnels (branche THEN, `when` sans sujet, opérande droit de `&&`, garde `!is` à sortie précoce — l'ombrage par une locale postérieure est tranché), paramètres de LAMBDA typés par l'attente du consommateur projet (fonction, SAM d'interface à méthode unique, initialiseur typé, `it` implicite), SEPT diagnostics sémantiques (`kt.unresolved`, `kt.typeMismatch`, `kt.argumentCount`, `kt.unsafeNullable`, `kt.missingReturn`, `kt.unreachable`, `kt.valReassign` — un test de sous-rapport par code) et QUICK-FIXES d'import (`textDocument/codeAction` : un CodeAction par FQN candidate de la vue, `kt.unresolved` ciblé). Correctifs de fond au passage : `classesProjet` K3 toujours vide (double garde `!= CLASSE`/`!= INTERFACE`), octets des classes d'AAR dans l'adaptateur hôte. 36 tests moteur+hôte, kover moteur 84,1 %.
- **K5 livré v0.116.0** : classes synthétiques R/ViewBinding/DataBinding **avant tout build** (D5 — coupées par les classes générées réelles, sonde v0.108.0 généralisée), multi-langue Java↔Kotlin (types de premier niveau des `.java` vus du moteur Kotlin — complétion, résolution, définition, arité jamais contrôlée), récepteurs `with` + attentes de lambda du CLASSPATH (SAM Java, `setOnClickListener { v -> }`), typage statique des chaînes `R.layout.…` ; correctif éditeur ANR e7dd5a7 (nourrissage LSP sur `DispatcherProvider.io` injecté, jamais le thread principal). 17 tests moteur + 1 éditeur, baseline parité 873 fichiers.
- Étape suivante éventuelle : protocole v10 (compliance par projet — champ `languageVersion` du module) ; détail du cadre dans `docs/LSP_KOTLIN.md`.
- Distribution : embarqué en T1, découpage en « pack langue » téléchargeable **différé à post-stabilisation** (décision propriétaire, architecture préparée dès K1).

### 5.3 LSP XML — XE1 et XE2 livrés (v0.117.0, v0.118.0)

- Moteur **maison in-process** : parseur tolérant + DOM neutre + complétion par contributeurs (catalogue dérivé des données de plateforme, index ressources projet) ; Lemminx/XSD écartés — détail dans `docs/LSP_XML.md`.
- **XE1 livré v0.117.0** : module `:lsp:xml` (zéro dépendance, jar 80 Ko) — parseur TOLÉRANT écrit main (pile de noms ouverts, reprise sur buffer à moitié tapé, aucun marqueur à chaud sur le guillemet en frappe), DOM neutre aux plages, recouvrements `xml.tag*` + règles de structure pures PAR GENRE (layout/values, conservatoires X4), session LSP minimale derrière le SPI ServiceLoader (descripteur de pack), façade `SessionLspXml` + adaptateur `XmlLanguageServer` dans `:lsp:host`, éditeur trilingue (garde PAR LANGUE, registre ADR 0114) ; corpus LayoutLib en test (0 recouvrement), 67 tests module + 13 hôte/éditeur, kover 91,1 %.
- **XE2 livré v0.118.0** : scanner de position du curseur (lexical, respect des quotes) + catalogue plateforme (`widgets.txt` + `attrs.xml` **parsés par notre parseur**, cache partagé une fois par plateforme) + complétion BALISES (vues + spéciales, fréquentes SDK d'abord) et ATTRIBUTS (styleables de la chaîne de classes + `layout_*` du PARENT + `tools:` + `xmlns:` en frappe), serveur LSP4J (déclencheurs `<`/`=`/`:`) ; genre layout seul (X4), 108 tests module, vraie plateforme SDK en test optionnel auto-skippé.
- Étapes suivantes : XE3 index ressources + résolution `@ref` + hover/définition ; XE4 manifest + quick-fixes. Fixtures partagées avec le catalogue de validation LayoutLib.

### 5.4 Previews — PE1 et PE2 livrés (v0.111.0, v0.114.0)

- **XML — PE1 livré v0.111.0** : module `:preview:xml` (moteur **LayoutLib en intégration source composite depuis v0.112.0** — tag v2.2.0, JitPack abandonné pour LayoutLib sur décision propriétaire — rendu bitmap off-screen sur fil dédié, résolveurs du projet par FUSE), panneau `VuePreviewXml` + menu « Aperçu XML » dans l'éditeur, 22 tests, kit catalogue 50 layouts (TESTS_MANUELS) — détail dans `docs/PREVIEW.md`.
- **XML — PE2 livré v0.114.0** : isolation processus `:preview` (AIDL `IRenduApercu` + PNG compressé, `ServicePreview` bound-only, DeathRecipient, politique UNE reprise puis repli local honnête avec régime observable, idle-shutdown 60 s + fermeture d'espace I7), pipeline extrait `RenduLayoutLib` partagé service/repli — 12 tests, socle réutilisable par la preview Compose.
- **XML — suite** : PE3 sélection/inspection + profiles device + jour/nuit.
- **Compose** : **interpréteur sur l'arbre résolu** (approche CodeAssist), runtime Compose comme bibliothèque de preview (ADR 0002 intact), **après stabilisation du LSP Kotlin** — étapes C0–C3.
- Détail dans `docs/PREVIEW.md`.

---

## Phase 6 — Fonctionnalités IDE

### 6.1 Recherche globale
- Recherche de texte dans tous les fichiers du projet
- Remplacement multi-fichiers

### 6.2 Git intégré
- **G0 (v0.70.0)** : investigation moteur, ADR 0092 (git CLI via NativeProcessLauncher), port `MoteurGit` dans `core:domain`, spec `docs/GIT.md`, maquette `docs/preview/git.html`
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
- **E2** ✅ livré v0.56.0 (ADR 0087) : cadre commun (orchestrateur
  verify-first, `CommandRunner` capture intégrale, `DownloadManager`
  cache SHA-256 + reprise `Range`, `install-state.json` atomique,
  service de premier plan avec Annuler) + phases 1 et 2 complètes
  (56 tests nouveaux, invariant de l'unique téléchargement testé).
- **E3** ✅ livré v0.57.0 (ADR 0088) : phase 3 Java complète — `openjdk-17`
  du catalogue, dépôt APT interrogé et journalisé (`apt-cache policy`),
  résolution unique `JAVA_HOME`, `java`/`javac` vérifiés par exécution,
  majeure analysée, **sonde TLS** compilée et exécutée par le JDK
  (truststore cassé détecté ici, classé `Jvm` vs `Reseau`) ; 7 tests,
  dont la régression du mode muet R6 (ADR 0084).
- **E4** ✅ livré v0.58.0 (ADR 0089) : phase 4 Outils Android complète —
  plan résolu du manifeste v2 (une fois par exécution, contrôle d'espace),
  composants installés/vérifiés par exécution avec péremption par
  quadruplet (réparation du seul fautif), `cmdline-tools` non critique
  (`Degraded`), licences après acceptation, câblage Gradle (override
  `aapt2` bloc géré idempotent, `buildToolsVersion` explicite dans les
  templates, matrice documentée), relance du daemon par empreinte,
  vérification approfondie (projet + vrai `assembleDebug`) ; 22 tests
  nouveaux, parcours complet exécutable de bout en bout.
- **E5** ✅ livré v0.59.0 (ADR 0090) : maquettes statiques
  (`docs/preview/installation-environnement.html`, six états) puis
  nouvelle interface — écran d'installation à stepper de 4 cartes
  (progression « étape N sur 4 », journal en direct repliable, vitesse et
  temps restant **mesurés** du téléchargement courant, consentement
  licence § 12.5 avant la phase 4, actions contextuelles masquées jamais
  grisées, récapitulatif final + « Créer mon premier projet », tablette
  sw600dp deux panneaux) et écran Environnement des Paramètres (tailles
  réelles auditées par composant, rangée JDK « paquet APT », Vérifier
  légère/approfondie, Réparer, Désinstaller avec confirmation via
  `uninstallComponent`, diagnostic copiable partagé en codes neutres) ;
  section maître « Outils (bientôt) » remplacée par la section réelle ;
  26 tests nouveaux (divergence assumée : pas d'estimation globale de
  temps restant — ADR 0090 § 3).
- **E6** ✅ livré v0.60.0 (ADR 0091) : **migration des installations
  existantes** — adoption par l'exécution au premier `run()` (composants
  sans quadruplet vérifiés par le `verify` du manifeste → quadruplets du
  plan reconstruits, **zéro retéléchargement** ; préfixe déjà basculé →
  archive dispensée ; le fautif éventuel réparé seul), trois scénarios
  testés (appareil ancien complet, appareil neuf, à moitié installé) ;
  **suppression de l'ancien code** (`InstallateurBootstrap`,
  `TelechargeurBootstrap`, `EcrivainSdkAndroidCli`,
  `EcrivainCodeideEnvCli`, `EcrivainGradleCli`, `EcrivainProfilShell`,
  `Aapt2Deployeur`, `VersionneurScriptsTerminal`, ports
  `BootstrapInstaller`/`BootstrapAssetsSource`/`ConfigurationEnvTerminal`,
  modèles `EtatInstallationBootstrap`/`EtapeInstallation`/`OutilResume`,
  ancien écran `InstallFragment` + mini-terminal, ~2 900 lignes) ;
  rebranchements (bandeau accueil, observateur d'outils, relance du
  daemon Gradle par empreinte E4 seule) ; `refreshTerminalScripts` retiré
  (environnement injecté par session, scripts anciens laissés en place) ;
  ADR 0082/0083 marquées **remplacées** ; **fin de la phase R**.

## Priorisation

| Phase | Priorité | Effort | Impact utilisateur |
|---|---|---|---|
| 1 — Console perf | **CRITIQUE** ✅ livré v0.42.0 (ADR 0074) | Moyen | Très haut — l'app devient utilisable |
| 2 — Fix templates | **Haute** ✅ livré v0.43.0 (ADR 0075) | Faible | Haut — les templates marchent |
| 3 — Templates avancés | **Haute** ✅ livré v0.44.0 (ADR 0076) | Moyen | Moyen — plus de choix |
| 4 — Wizard | **Moyenne (prochaine)** ✅ livré v0.45.0 (ADR 0077) | Moyen | Moyen — meilleure UX |
| 5 — LSP | Basse | Très haut | Très haut — transforme en IDE |
| 6 — Fonctionnalités IDE | Basse | Très haut | Très haut — fonctionnalités pro |
