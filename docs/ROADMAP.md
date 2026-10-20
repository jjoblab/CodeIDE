# CodeIDE — Roadmap

> Dernière mise à jour : 2026-10-19
> Version courante : **0.145.2** (source de vérité : `version.properties`)
> Historique complet des 235 livraisons : `CHANGELOG.md`. Cette feuille
> décrit l'état réel courant et ce qui reste — les phases achevées sont
> gardées comme record (voir aussi `docs/archive/`).

## État actuel (v0.145.2 — stratégie de vérification + CI 3 jobs + lot design écrans)

### Fonctionnel ✅
- **Tooling Gradle** : sync d'ouverture, étapes dynamiques progressives, sync suivante immédiate (empreinte SHA-256 + revalidation silencieuse), chip d'action unique, stats classpath par module — moteur déplacé dans le dépôt frère [`codeide-tooling`](https://github.com/jjoblab/codeide-tooling) (v1.0.0)
- **LSP Java/Kotlin/XML + previews** : indexation disque, greffes, inline diagnostics, écran d'indexation — moteurs déplacés dans le dépôt frère [`codeide-lsp`](https://github.com/jjoblab/codeide-lsp) (v1.0.2, Phase 5 livrée v0.91.0 → v0.120.0)
- **Dépôts frères en binaire** (v0.134.41 → v0.134.44) : séparation des dépôts, publication de 18 paquets publics sur GitHub Packages, consommation **binaire par défaut** (mavenLocal puis Packages, `scripts/bump-frere.sh`), composite en opt-in `-Pfreres.sources`, CI sans clone + garde-fou composite nocturne
- **Console hybride** (v0.42.0, ADR 0074) : zone structurée + zone texte — un build de 725 ms s'affiche en < 1 s
- **Exécution** : bouton Run (détecte `fun main()`), support stdin/readln, installer/lancer un APK sans adb (ADR 0102)
- **Templates & wizard** (v0.43.0 → v0.45.0, ADR 0075–0077) : 7 familles de modèles vérifiés par 37 combinaisons réelles, rendu 100 % dynamique, aperçu renommable
- **Installation** (Phase R, E1–E6, v0.55.0 → v0.65.0) : JDK 17/21 + outils Android installés sur l'appareil, orchestration quatre phases, migration des installations
- **Terminal** (ADR 0035/0036/0053) : Termux, sessions, tiroir, pont SAF/FUSE, cartes d'aperçu
- **Historique local** (ADR 0104–0106) : blobs, index atomique, étiquettes, capture de ports
- **Git** : socle livré — moteur CLI (ADR 0092, G0 v0.70.0), clonage depuis l'accueil (ADR 0101, v0.80.x), initialisation, détection de dépôt
- **Recherche/paramètres/explorateur** : Material You + paramètres deux niveaux (ADR 0059/0064), explorateur v2 (ADR 0052), recherche/remplacement dans l'éditeur (B10)
- **Mission IA — domaine et fournisseurs** (IA-1, v0.136.0, ADR 0123) : `:core:ai` (port `AiProvider`, `AiEvent` normalisés, `TokenBudget`, `SendMessageUseCase`), `:ai:providers` (transport `HttpURLConnection`, parseur SSE maison, adaptateurs Anthropic Messages et « compatible OpenAI », reprise bornée 429/5xx avant premier jeton, annulation coopérative < 1 s, HTTP en clair refusé hors boucle locale), `AppError.Ai` modélisée avec traductions UI ; aucun nom de modèle codé en dur, aucune clé dans les journaux.
- **Mission IA — garde-fous** (IA-0, v0.135.0, ADR 0121–0126 acceptées) : `network_security_config.xml` (https obligatoire, boucle locale seule exception), sauvegarde gouvernée (`data_extraction_rules.xml`/`backup_rules.xml`), `LogRedactor` masque les clés (`<secret>`), 4 modules IA échafaudés, contrats d'accroche (`PontIaEditeur`, `PortEditeurActif`)
- **Mission IA — coffre et réglages** (IA-2, v0.137.0, ADR 0122/0124) : `CoffreClesKeystore` (AES-256-GCM Keystore, blobs `noBackupFilesDir`, perte de clé → « Clé à ressaisir » sans plantage), `AiSettingsStore` (DataStore dédié hors `AppSettings`), routeur `FournisseurIaActif` (**sans consentement, aucune requête réseau IA**, testé), écrans Paramètres → IA (fournisseurs, modèle, confidentialité, limites) et consentement de premier usage ; la clé n'apparaît jamais dans un état, un `Bundle` ou un journal.
- **Mission IA — contexte et confidentialité** (IA-4, v0.139.0, ADR 0124 §4) : `AssemblerContexte` pur (`:core:ai`) — puces de contexte retirable qui reflètent EXACTEMENT ce qui part, exclusions par défaut + `.codeide/ia-ignore`, balayage de secrets bloquant (« Envoyer sans / Annuler » — l'étiquette du motif, jamais la valeur), sélecteur `@` de fichiers, budget de jetons visible, encadrement neutre « contenu non fiable », chemins relatifs garantis (absolu et `../` refusés, testé).
- **Mission IA — intégration éditeur** (IA-5, v0.140.0, ADR 0126) : action de sélection « IA » (pill), barre de consigne en ligne (actions rapides Expliquer/Corriger/Documenter/Tests/Simplifier), carte de diff (DiffUnifie + ligne_diff.xml) Accepter/Rejeter/Réessayer avec garde de révision et application en un seul lot, bloc de code « Appliquer » dans le chat, « Corriger avec l'IA » (Problèmes) et « Expliquer » (Console/Logcat) ouvrant le chat pré-rempli via le pont `PontIaEditeur` (canal à signal unique, aucun envoi sans geste utilisateur).
- **Mission IA — chat du tiroir** (IA-3, v0.138.0, ADR 0122) : 6ᵉ destination du rail de l'éditeur (`IaTiroirFragment`, onglets Chat / Agent / Historique / Recherche), chat en **streaming** (DiffUtil, bascule Envoyer ↔ Arrêter — l'annulation ferme la requête —, Régénérer, erreurs inline), rendu **Markdown minimal maison** (titres, listes, blocs de code provisoires au fil du flux, gras/italique, code en ligne, liens — aucune WebView, ADR 0002), actions de bloc **Copier / Insérer au curseur** via le port `PortEditeurActif` publié par l'éditeur (remplacement atomique, un seul pas d'annulation).

### Problèmes connus ❌
- (aucun ouvert — les quatre bloquants IA identifiés par `docs/IA_REVUE_DEPOT.md`
  sont traités au lot IA-0 : ADR 0121 acceptée, sauvegarde gouvernée par
  `data_extraction_rules.xml`/`backup_rules.xml`, `LogRedactor` masque les
  clés, coffre de clés prévu au lot IA-2)
- Recherche globale multi-fichiers : spec prête (`docs/RECHERCHE.md`, ADR 0094), onglet réservé dans le tiroir — livraison après la mission IA

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

- Décision : **parseur vendoré** (grammaire officielle du compilateur, Apache 2.0) + **modèle de symboles propre** conservateur, in-process — fwcd/kotlin-language-server **écarté**, Analysis API K2 réduite à un éventuel « mode profond » à la demande (étudu projet de référence).
- **K1 livré v0.110.0** : module `:lsp:kotlin`, vendoring **v2.3.0** octet identique (ADR 0115, VENDOR.md, script de re-sync), façade `Kt*`, suite de parité (corpus dépôt 818 fichiers / 0 erreur, digeste baseline), SPI ServiceLoader + descripteur de pack (découpage réel différé), jar 257 Ko, kover 91,7 %.
- **K2 livré v0.112.0** : session LSP minimale — contrat de session derrière le SPI (D6 : diagnostics neutres, aucun LSP4J dans le moteur), diagnostics syntaxiques conservateurs `kt.syntax`/`kt.unusedImport`/`kt.conflictingImport`, adaptateur LSP4J + façade `SessionLspKotlin` dans `:lsp:host` (définitions cel-lsp `kt`/`kts`), éditeur bilingue (LspProject unique, routage par extension, cycle de vie registre I2/I7) — 25 tests.
- **K3 livré v0.113.0** : symboles + classpath + les trois requêtes — complétion (membre/noms/imports, auto-import), survol (chaîne de résolution), définition (projet, inter-fichiers par scan des racines sources) ; vue de classpath NEUTRE partagée avec l'index Java (D4), lecteur de class-files + décodeur @Metadata (kotlin-metadata-jvm 2.2.10), index disque des appelables (clés top:/ext:), scan paresseux des racines sources — 27 tests, définition = projet seul (D3 documenté).
- **K4 livré v0.115.0** : inférence bornée + sémantique conservatrice — SMART CASTS positionnels (branche THEN, `when` sans sujet, opérande droit de `&&`, garde `!is` à sortie précoce — l'ombrage par une locale postérieure est tranché), paramètres de LAMBDA typés par l'attente du consommateur projet (fonction, SAM d'interface à méthode unique, initialiseur typé, `it` implicite), SEPT diagnostics sémantiques (`kt.unresolved`, `kt.typeMismatch`, `kt.argumentCount`, `kt.unsafeNullable`, `kt.missingReturn`, `kt.unreachable`, `kt.valReassign` — un test de sous-rapport par code) et QUICK-FIXES d'import (`textDocument/codeAction` : un CodeAction par FQN candidate de la vue, `kt.unresolved` ciblé). Correctifs de fond au passage : `classesProjet` K3 toujours vide (double garde `!= CLASSE`/`!= INTERFACE`), octets des classes d'AAR dans l'adaptateur hôte. 36 tests moteur+hôte, kover moteur 84,1 %.
- **K5 livré v0.116.0** : classes synthétiques R/ViewBinding/DataBinding **avant tout build** (D5 — coupées par les classes générées réelles, sonde v0.108.0 généralisée), multi-langue Java↔Kotlin (types de premier niveau des `.java` vus du moteur Kotlin — complétion, résolution, définition, arité jamais contrôlée), récepteurs `with` + attentes de lambda du CLASSPATH (SAM Java, `setOnClickListener { v -> }`), typage statique des chaînes `R.layout.…` ; correctif éditeur ANR e7dd5a7 (nourrissage LSP sur `DispatcherProvider.io` injecté, jamais le thread principal). 17 tests moteur + 1 éditeur, baseline parité 873 fichiers.
- Étape suivante éventuelle : protocole v10 (compliance par projet — champ `languageVersion` du module) ; détail du cadre dans `docs/LSP_KOTLIN.md`.
- Distribution : embarqué en T1, découpage en « pack langue » téléchargeable **différé à post-stabilisation** (décision propriétaire, architecture préparée dès K1).

### 5.3 LSP XML — XE1 à XE4 livrés (v0.117.0 → v0.120.0)

- Moteur **maison in-process** : parseur tolérant + DOM neutre + complétion par contributeurs (catalogue dérivé des données de plateforme, index ressources projet) ; Lemminx/XSD écartés — détail dans `docs/LSP_XML.md`.
- **XE1 livré v0.117.0** : module `:lsp:xml` (zéro dépendance, jar 80 Ko) — parseur TOLÉRANT écrit main (pile de noms ouverts, reprise sur buffer à moitié tapé, aucun marqueur à chaud sur le guillemet en frappe), DOM neutre aux plages, recouvrements `xml.tag*` + règles de structure pures PAR GENRE (layout/values, conservatoires X4), session LSP minimale derrière le SPI ServiceLoader (descripteur de pack), façade `SessionLspXml` + adaptateur `XmlLanguageServer` dans `:lsp:host`, éditeur trilingue (garde PAR LANGUE, registre ADR 0114) ; corpus LayoutLib en test (0 recouvrement), 67 tests module + 13 hôte/éditeur, kover 91,1 %.
- **XE2 livré v0.118.0** : scanner de position du curseur (lexical, respect des quotes) + catalogue plateforme (`widgets.txt` + `attrs.xml` **parsés par notre parseur**, cache partagé une fois par plateforme) + complétion BALISES (vues + spéciales, fréquentes SDK d'abord) et ATTRIBUTS (styleables de la chaîne de classes + `layout_*` du PARENT + `tools:` + `xmlns:` en frappe), serveur LSP4J (déclencheurs `<`/`=`/`:`) ; genre layout seul (X4), 108 tests module, vraie plateforme SDK en test optionnel auto-skippé.
- **XE3 livré v0.119.0** : index des ressources du PROJET (scan `res/` des racines sources, incrémental par mtime, purgé des disparus) + résolution de références `@type/nom`/`@+id`/`@android:`/`?attr` (projet + framework sur `public-final.xml`, garde dumb mode X4) + complétion de VALEURS (références projet/framework + énumérations du catalogue) + SURVOL (référence, classe de balise, format d'attribut) + DÉFINITION (saut au `res/values` déclarant) + règle `xml.referenceNonResolue` ; 130 tests module, survol/définition déclarés au serveur.
- **XE4 livré v0.120.0** : règles de MANIFEST (`xml.manifestSansPackage`, `xml.composantSansNom` — composants d'`<application>` sans `android:name`) + règle `xml.tagInconnu` (couture conservatrice X4 : catalogue plateforme ET vue de noms du classpath d'accord, sinon silence — jamais à chaud) + complétion du genre VALUES (entrées nommées, attributs `name`/`parent`/`translatable`/`format`, `parent=` → styles du projet + thèmes framework `@android:style/`) + QUICK-FIXES `textDocument/codeAction` (insertion `xmlns:android` après le nom de racine ; « did you mean » par préfixe commun puis Levenshtein borné, ouvrante ET fermante renommées — éditions neutres du moteur traduites par l'hôte) ; 152 tests module, codeActionProvider déclaré au serveur.
- **XE5 livré v0.121.0** (suite demandée par le propriétaire) : **complétion drawables/menus** — genres DRAWABLE/MENU déduits de l'URI, contributeurs parents-conscients (racines drawables + enfants selon le parent, hiérarchie menu → item/group → sous-menu), attributs par balise (états/calques/frames pour `item`, préfixe `app:` AppCompat pour les menus), énumérations et FANIONS `showAsAction`, valeurs FUSIONNÉES avec le contributeur XE3 (références `@drawable/…` + catalogue) ; règles xmlns/ids étendues aux nouveaux genres (quick-fix inchangé) ; 188 tests module, aucun changement de contrat (pack 0.121.0).
- **Mission LSP XML complète (XE1–XE4, suite XE5 livrée).** Suites potentielles : mipmap/adaptive-icon par les mêmes moteurs (§4.3), fixtures partagées avec le catalogue de validation LayoutLib.

### 5.4 Previews — PE1 et PE2 livrés (v0.111.0, v0.114.0)

- **XML — PE1 livré v0.111.0** : module `:preview:xml` (moteur **LayoutLib en intégration source composite depuis v0.112.0** — tag v2.2.0, JitPack abandonné pour LayoutLib sur décision propriétaire — rendu bitmap off-screen sur fil dédié, résolveurs du projet par FUSE), panneau `VuePreviewXml` + menu « Aperçu XML » dans l'éditeur, 22 tests, kit catalogue 50 layouts (TESTS_MANUELS) — détail dans `docs/PREVIEW.md`.
- **XML — PE2 livré v0.114.0** : isolation processus `:preview` (AIDL `IRenduApercu` + PNG compressé, `ServicePreview` bound-only, DeathRecipient, politique UNE reprise puis repli local honnête avec régime observable, idle-shutdown 60 s + fermeture d'espace I7), pipeline extrait `RenduLayoutLib` partagé service/repli — 12 tests, socle réutilisable par la preview Compose.
- **XML — suite** : PE3 sélection/inspection + profiles device + jour/nuit.
- **XML — PE11a-d livrés v0.146.0 → v0.149.0** : jetons exacts de la maquette (39 `apercu_*`, clair + nuit), `Theme.CodeIDE.Apercu` + `JetonsMaquetteTest` de parité (PE11a) ; refonte de l'écran Attributs à l'identique (RecyclerView, 13 layouts de référence, `CotesMaquetteTest` — PE11b) ; le contrôle LISTE déroulante et la règle des enums longs (PE11c, v0.148.0) ; le contrôle DRAPEAUX, la règle des masques `|` et le composite TYPE DE SAISIE (PE11d, v0.149.0) — spécification normative `docs/PREVIEW_UI.md` (+ partie II, ADR 0131-0134) ; **PE11i l'écran PRINCIPAL à l'identique** (v0.150.0 — ADR 0134 : barre 52 dp sans ActionBar, pastille de mode ChipGroup, bandeau et toast en VUES, jetons `JetonsCalques` injectés au moteur, bulle 58 dp, légende de planche, cotes § 12.1 vertes) ; **PE11j BULLE/PALETTE/GHOST/PLACEMENT/MENUS à l'identique** (v0.151.0 — 15 layouts de référence, palette bornée `min(600 dp, disponible)` + grille 3 colonnes à écart 8, placement aux rangées de l'arbre et résumé fort, menus sans radio à `SwitchCompat` 34 × 20, NOUVELLE option « Chrome système / Barre de navigation » rendue par le chrome, correctif bulle inactive après la palette) ; **PE11k ONGLETS ARBRE/PROBLÈMES/SOURCE à l'identique** (v0.152.0 — 6 layouts de référence : arbre 34 dp à sélection `isSelected` + badge ambre/neutre, problèmes à message vide dans le layout + « Voir », source colorée balise/attribut/valeur + trois surlignages dont l'inséré temporaire 2,6 s par le flux `plageInseree` ; 4 CORRECTIFS propriétaire : clair/nuit gardé par test, bandeau crash miroir exact + `ServicePreview` à l'épreuve d'exception, z-order des popovers par l'élévation des CONTENEURS, HUD zoom/pastille au-dessus du PEEK de la feuille) ; **PE11l RECETTE FINALE + 5 correctifs propriétaire** (v0.153.0 — ① HUD/pastille posés au lancement feuille repliée, ② bandeau permanent : pipeline `:preview` PARESSEUX + budgets de transport 45/15/10 s, ③ palette remplie dès la construction, ④ rendu PLAN à l'origine en planche seule, ⑤ REDIMENSIONNEMENT par poignée : fantôme + UNE édition groupée des deux axes, maquette v6 resynchronisée ; littéraux VIDE, cotes de TOUS les layouts de partie II `CotesPlacementMenusMaquetteTest`, grille § 12.4 cochée, O1-O5 au CHANGELOG). **PE11e le contrôle COULEUR livré v0.154.0** (pastille 3 couches damier+teinte+cadre 34 dp, champ hex VALIDÉ, `ValidationAttribut` 1re tranche miroir `vCol`, `CouleursProjetXml` miroir `colorCss`, palette `@color`, kind `Couleur` — usager `textColor`). **PE11f livré v0.155.0** (contrôle RESSOURCE — suggestions `@type/nom`, complétion du nom nu, `vRes` miroir, usagers `app:icon`/`android:src` ; + la BARRE branchée : RECHERCHE par étiquette/nom/titre à sections ouvertes, BASCULE Courants/Tous, drapeau `courant` sur les 11 kinds PE10a, bouton large `.linkbtn.wide` ; re-cut : les CORRECTIONS RAPIDES partent en PE11g-corrections — mêmes lots plus fins que PE11c/d). **PE11g-corrections livré v0.156.0** (3 NOUVEAUX GENRES transportés par `InfoVueRendu` +3 champs — TRONQUÉ détecté CÔTÉ MOTEUR `ProblemesRendu`, SANS_CONTENT_DESCRIPTION lu sur la vue, TEXTE_CODE_DUR au span (`estLitteral` partagé avec l'extraction) ; cartes `item_correctif` 16e layout de référence ENTRE la barre et les sections ; les 4 actions — `elargirCibleTactile` UNE édition groupée (fixe <48→48, wrap/0dp→minimums, 0dp jamais réécrit), extraction par le flux existant, `maxLines=2`, focus `contentDescription` SANS édition ; CLASSE_ABSENTE sans carte (action simulée, hors périmètre) ; `LibellesProblemes` partagé volet Problèmes/cartes ; PE10e CLÔTURÉ par construction). Suite : **PE11g-contrôles** (curseur + ratio), **PE11h** (actions rapides + attribut libre + contraintes lecture) — la suite FERME PE10 par construction.
- **Compose** : **interpréteur sur l'arbre résolu** (approche du projet de référence), runtime Compose comme bibliothèque de preview (ADR 0002 intact), **après stabilisation du LSP Kotlin** — étapes C0–C3.
- Détail dans `docs/PREVIEW.md`.

---

## Phase 6 — Fonctionnalités IDE

### 6.1 Recherche globale ⬜ non livrée
- Recherche de texte dans tous les fichiers du projet
- Remplacement multi-fichiers
- Statut : spec prête (`docs/RECHERCHE.md`, ADR 0094), onglet **réservé** dans le tiroir (l'assistant IA l'utilisera pour la recherche sémantique) — livraison planifiée après la mission IA

### 6.2 Git intégré 🔶 socle livré
- **G0 (v0.70.0)** ✅ : investigation moteur, ADR 0092 (git CLI via NativeProcessLauncher), port `MoteurGit` dans `core:domain`, spec `docs/GIT.md`, maquette `docs/preview/git.html`
- **Livré** ✅ : clonage depuis l'accueil avec rollback (ADR 0101, v0.80.x), initialisation d'un dépôt, détection du statut (pas un dépôt / dépôt propre / modifications)
- ⬜ Statut des fichiers par ressource (modifié/ajouté/supprimé) en UI complète
- ⬜ Diff visuel
- ⬜ Commit/Push depuis l'app

### 6.3 Refactoring ⬜
- Renommer (symbole → toutes les références)
- Extraire (méthode, variable)
- Importer automatiquement
- Statut : non commencé ; s'appuiera sur les index LSP (codeide-lsp)

### 6.4 Build variants (Android) ⬜
- Sélection debug/release
- Product flavors
- Build types
- Statut : non commencé

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

## Phase IA — Assistant « apporte ta clé » (BYOK) ⬜ PLAN

> Mission du 2026-10-17 : ADR **0121–0126** (statut « proposé », à accepter
> par le propriétaire avant le lot IA-0). Documents opérationnels :
> [`IA_PLAN_EXECUTION.md`](IA_PLAN_EXECUTION.md) (lots, règles, critères),
> [`IA_REVUE_DEPOT.md`](IA_REVUE_DEPOT.md) (audit préalable),
> [`IA_SPEC_ECRANS.md`](IA_SPEC_ECRANS.md) et maquette
> `docs/mockups/ia/ia-ecrans.html`.

| Lot | Version | Titre | Statut |
|---|---|---|---|
| IA-0 | 0.135.0 | Préalables et garde-fous (réseau explicite, sauvegarde, `LogRedactor`, 4 modules vides) | ✅ v0.135.0 |
| IA-1 | 0.136.0 | Domaine IA et fournisseurs (Anthropic + compatible OpenAI, streaming testé) | ✅ v0.136.0 |
|      |        | `:core:ai` (AiMessage/AiEvent/AiProvider/TokenBudget) + `:ai:providers` (HttpURLConnection + SSE maison + 2 adaptateurs, reprise bornée, annulation < 1 s, fichiers dorés, Kover ≥ 80 %) | |
| IA-2 | 0.137.0 | Coffre de clés (Keystore), réglages, consentement | ✅ v0.137.0 |
| IA-3 | 0.138.0 | Chat dans le tiroir (streaming, Markdown) | ✅ v0.138.0 |
| IA-4 | 0.139.0 | Contexte et confidentialité (`@fichiers`, exclusions, détection de secrets) | ✅ v0.139.0 |
| IA-5 | 0.140.0 | Intégration éditeur (carte de diff, actions Problèmes/Console) | ✅ v0.140.0 |
| IA-6 | 0.141.0 | Historique des conversations (Room v2, migration testée) | ⬜ |
| IA-7 | 0.142.0 | Agent, outils, approbations (`ChangeSet`, restauration) | ⬜ |
| IA-9 | 0.143.0 | Durcissement et release (tests d'architecture, accessibilité, perf) | ⬜ |

Optionnels : IA-5b (diff en ligne `ViewZone` — chantier `jjoblab/code-editor`),
IA-8 (complétion « texte fantôme », ADR propre).

## Mission UI/LSP — cohérence visuelle et réglages ⬜ PROPOSÉ

> ADR 0127 (cohérence UI + garde-fou à cliquet) et 0128 (réglages LSP pilotés par
> capacités) **proposées** — les lots démarreront après acceptation par le propriétaire.
> Documents : [`UI_AUDIT_COHERENCE.md`](UI_AUDIT_COHERENCE.md) (mesures C1–C10),
> [`consommes/LSP_REVUE_REGLAGES.md`](consommes/LSP_REVUE_REGLAGES.md) (constats L1–L7),
> [`consommes/LSP_SPEC_ECRANS.md`](consommes/LSP_SPEC_ECRANS.md) (écrans L1–L5),
> [`mockups/lsp/lsp-reglages.html`](mockups/lsp/lsp-reglages.html).
> **Règle d'ordonnancement : UI-1 et UI-2 d'abord** — aucun écran IA (IA-2+) ni LSP
> ne se construit avant.

### Lots UI — cohérence (ADR 0127)
| Lot | Version | Contenu | État |
|---|---|---|---|
| UI-1 | v0.158.0 | Jetons, styles et composants partagés dans `core:ui` (`RangeeParametre`, `VueEtat`, `IndicateurEtat`, `Retour`) | ✅ v0.158.0 (échelle d'espacement complète 2xs→xl, `Micro`/`SectionLabel`, `Widget.CodeIDE.Card`/`Button.Outlined`/`Chip.*`/`EnteteSection`, rôles de pastilles jour+nuit, +17 tests Robolectric, planche `docs/mockups/ui/composants-partages.html`) |
| UI-2 | v0.159.0 | Garde-fou `checkUiTokens` à cliquet (baseline versionnée, jamais à la hausse) + CI + tests du moteur | ✅ v0.159.0 (règles R1-R7, moteur pur +20 tests JUnit, baseline initialisée 96 fichiers/606 occurrences, branché chaîne locale étape 5 + CI statique & composite, AGENTS.md règle 15 ; sens ROUGE prouvé par attaque) |
| UI-3 | v0.160.0 | Settings : rangées → `RangeeParametre`, cartes → `Widget.CodeIDE.Card` | ✅ v0.160.0 (0 rangée recopiée — `ligne_parametre.xml` supprimé, 17 cartes `Widget.CodeIDE.Card` + `EnteteSection`, 9 boutons `Outlined` ; `RangeeParametre` + `rangeeDestructrice`/`rangeeIndicateur`/`setIcone` ; cliquet 606→561, `feature:settings` HORS baseline ; recette visuelle sur appareil dans `TESTS_MANUELS.md` § UI-3) |
| UI-4 | — | `install`, `core/crash`, `terminal` : alignement complet | ⬜ |
| UI-5 | — | `editor` : spacing/typo/boutons/cartes, par sous-écrans (≤ 10 fichiers/commit) | ⬜ |
| UI-6 | — | `preview` : idem | ⬜ |
| UI-7 | — | Toast → Snackbar partout + règle de bannissement | ⬜ |

### Lots LSP — réglages (ADR 0128, deux dépôts)
| Lot | Dépôt | Contenu | État |
|---|---|---|---|
| LSP-0 | les deux | Purge règle 13 ; publication ; source unique des docs `LSP_*` ; numérotation d'ADR | ✅ v0.156.3 + frère `2aaa65a` (docs LSP → stubs de renvoi vers `codeide-lsp` ; 88 désignations tiers purgées ; ADR 0121 du frère renumérotée 0200) |
| LSP-1 | `codeide-lsp` | `ConfigurationLsp`, `appliquer`, cache et capacités exposés, tests, **v1.2.0** (v1.1.0 = stabilisation S3 du 2026-10-19) | ✅ v1.2.0 `6c23677` (+14 tests : 119 host, 247 index, parité capacités ×3 langages, baseline 216 fichiers) |
| LSP-2 | CodeIDE | Version vérifiée, `LspSettings` (hors `AppSettings`), câblage, filtres côté client | ✅ v0.157.0 (dépôt dédié `lsp_settings`, câblage DYNAMIQUE sans redémarrer ; filtres d'AFFICHAGE gravité/capacités différés au chantier code-editor — jamais un contrôle sans effet, ADR 0059) |
| LSP-3 | v0.161.0 | Écrans L1–L5 « Intelligence du code », composants ADR 0127 uniquement (après UI-1/UI-2) | ✅ v0.161.0 (5 écrans feature:settings, port `PortLsp`/`RegistrePortLsp` + `TableCapacitesLsp` core:domain, `PortLspEspace` feature:editor, `PresentationLsp` pur +13 tests JVM, rangée LANGAGES au maître, FR/EN + pluriels ; interrupteurs/gravité en puce « Bientôt » — décision LSP-2, recette visuelle dans `TESTS_MANUELS.md` § LSP-3) |
| LSP-4 | CodeIDE | « Copier le diagnostic LSP », tests d'architecture, accessibilité, EN/FR | ✅ v0.162.0 (lien L3 « Détails de l'indexation » LIVRÉ : demande-ÉTAT sur `PortLsp` + remontée `revenirALEditeur` REORDER_TO_FRONT, panneau consommé à la reprise STARTED ; test d'architecture `ArchitectureInterrupteursLspTest` « aucun interrupteur sans capacité annoncée » (dérivation ordre fixe, table vide = zéro rangée) + demande verrouillée `PortLspEspaceTest`/`LangagesViewModelTest` ; audit § 5 : a11y portée par les composants — rôles complets « Java : Prêt », liveRegion, 48 dp, ×1,3 sans troncature, recette § LSP-4 ; FR/EN 105+2 clés paritaires ; « Copier le diagnostic » déjà livré par LSP-3 (L5) ; les deux docs de planification LSP consommés → `docs/consommes/`) |

## Priorisation

| Phase | Priorité | Effort | Impact utilisateur |
|---|---|---|---|
| 1 — Console perf | **CRITIQUE** ✅ livré v0.42.0 (ADR 0074) | Moyen | Très haut — l'app devient utilisable |
| 2 — Fix templates | **Haute** ✅ livré v0.43.0 (ADR 0075) | Faible | Haut — les templates marchent |
| 3 — Templates avancés | **Haute** ✅ livré v0.44.0 (ADR 0076) | Moyen | Moyen — plus de choix |
| 4 — Wizard | **Moyenne (prochaine)** ✅ livré v0.45.0 (ADR 0077) | Moyen | Moyen — meilleure UX |
| 5 — LSP | Basse ✅ livré v0.91.0 → v0.120.0, moteurs déplacés dans `codeide-lsp` | Très haut | Très haut — transforme en IDE |
| R — Installation E1–E6 | Haute ✅ livré v0.55.0 → v0.65.0 | Très haut | Très haut — le build marche sur l'appareil |
| **IA — Assistant BYOK** | **EN COURS** — IA-0 v0.135.0, IA-1 v0.136.0 (domaine + fournisseurs), IA-2 v0.137.0 (coffre + réglages), IA-3 v0.138.0 (chat du tiroir), IA-4 v0.139.0 (contexte et confidentialité), IA-5 v0.140.0 (intégration éditeur), IA-6 v0.141.0 (historique des conversations), IA-7a v0.142.0 (agent : cœur + outils + confinement), IA-8 v0.143.0 (ADR 0129 texte fantôme), IA-7b v0.144.0 (écran Agent, approbations, application ChangeSet + annulation, service premier plan), IA-8b v0.145.0 (complétion fantôme opt-in, plafond visible, inlay éditeur) | Très haut | Très haut — assistance à l'édition, agent contrôlé |
| **UI/LSP — cohérence + réglages** | **PROPOSÉ** — ADR 0127/0128 en attente d'acceptation ; UI-1/UI-2 prérequis des écrans IA et LSP | Haut | Haut — unité visuelle, réglages LSP réels |
| 6 — Fonctionnalités IDE | Après IA (6.2 socle ✅) | Très haut | Très haut — fonctionnalités pro |
