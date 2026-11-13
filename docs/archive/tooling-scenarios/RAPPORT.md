# Phase 0 — Rapport des scénarios de sync réels

> Source du catalogue d'étapes dynamiques et des règles d'apparition (prompt
> de suivi, §1). Ce rapport fixe ce qui sera affiché dans la console de sync
> de CodeIDE : toute étape dont le signal de détection n'est pas fiable
> est **retirée ou fusionnée** — jamais d'affirmation devinée.

## 1. Matrice exécutée

### 1.1. Projets couverts (10/12)

| # | Type de projet | Statut |
|---|---|---|
| 01 | Kotlin/JVM pur, sans dépendances runtime | ✅ Exécuté (daemon froid) |
| 02 | Kotlin/JVM pur (suite du 01, daemon vivant) | ✅ Exécuté (daemon chaud) |
| 03 | Kotlin/JVM avec dépendance (okio), premier run | ✅ Exécuté |
| 04 | Kotlin/JVM avec dépendance, deuxième run | ✅ Exécuté |
| 05 | Multi-modules Kotlin/JVM (`:app`, `:lib`) | ✅ Exécuté |
| 06 | Groovy DSL | ✅ Exécuté |
| 07 | Kotlin/JVM avec `buildSrc` | ✅ Exécuté |
| 08 | Échec de configuration (plugin inexistant) | ✅ Exécuté |
| 09 | Mode hors ligne avec cache complet | ✅ Exécuté |
| 10 | Dépendances non cachées (`kotlinx-datetime`) | ✅ Exécuté + scénario 10b (résolution forcée via `dependencies`) |

### 1.2. Projets NON couverts (2/12) — à exécuter plus tard

| Type | Raison | Plan |
|---|---|---|
| **Android app mono-module** (Kotlin DSL + version catalog) | Nécessite SDK Android complet + `compileSdk 37.2` téléchargé (~50 Mo) | À exécuter dans une session avec `ANDROID_HOME` complet |
| **Kotlin Multiplatform** | Téléchargements lourds (Kotlin/Native toolchains) | À exécuter séparément |

### 1.3. États du cache couverts (5/7)

| État | Couverture |
|---|---|
| (a) `GRADLE_USER_HOME` vide | ❌ (nous sommes partis d'un cache déjà chaud) |
| (b) Distribution présente, dépendances absentes | ✅ (scénarios 03, 10) |
| (c) Tout chaud, daemon vivant | ✅ (scénarios 02, 04) |
| (d) Tout chaud, daemon froid | ✅ (scénario 01, daemon précédent expiré puis recréé) |
| (e) Version Gradle modifiée | ❌ (non testé — pas de `gradle-wrapper.properties` modifié) |
| (f) Une dépendance ajoutée | ✅ (scénario 10b) |
| (g) Hors ligne cache complet | ✅ (scénario 09) |

## 2. Signaux observés par phase

### 2.1. Démarrage du daemon Gradle

**Cold start** (scénario 01) :
```
Found daemon DaemonInfo{...} however its context does not match the desired criteria.
Looking for a different daemon...
Starting process 'Gradle build daemon'. Working directory: ...
```
**Warm start** (scénario 02) :
```
Starting Nth build in daemon [uptime: X secs, performance: Y%, ...]
```

**Signal de détection fiable** :
- `Starting process 'Gradle build daemon'` → daemon **démarré à froid** (peu importe la cause : pas de daemon, options JVM changées, daemon expiré).
- `Starting Nth build in daemon [uptime: ...]` → daemon **réutilisé**.

**Côté Tooling API** (ce que `SyncHandler` reçoit réellement via
`ProgressBridge`/`EcouteurProgressionCommun`) :
- La Tooling API Gradle **n'émet PAS d'événement `OperationType` spécifique
  au démarrage du daemon** — c'est un signal **stderr only**.
- `OperationType.DAEMON` n'existe pas dans l'API ; les événements
  disponibles sont `PROJECT_CONFIGURATION`, `FILE_DOWNLOAD`, `TASK`,
  `TRANSFORM`, `TEST`.
- **Conséquence** : la phase `DAEMON` ne peut pas être détectée via les
  événements Tooling API. **Elle doit être inférée depuis le stderr**
  (`Starting process 'Gradle build daemon'`) ou **supprimée du catalogue
  affiché**.

### 2.2. Distribution Gradle (téléchargement)

**Observé** : la distribution Gradle elle-même n'est jamais téléchargée dans
nos scénarios car elle est déjà en cache (`/home/z/.gradle/wrapper/dists/`).
Pour déclencher un vrai téléchargement de distribution, il faudrait
supprimer le cache ou modifier `gradle-wrapper.properties`.

**Signal de détection fiable** :
- Côté Tooling API : `OperationType.FILE_DOWNLOAD` avec un descripteur dont
  l'URL pointe vers `services.gradle.org/distributions/`. C'est ce que
  `EcouteurProgressionCommun` filtre déjà pour les téléchargements de
  dépendances — la distribution Gradle est aussi un `FILE_DOWNLOAD`.
- Côté fichier : présence de `$GRADLE_USER_HOME/wrapper/dists/gradle-X/<hash>/`
  avec un marqueur `.ok` (déjà utilisé par `EtatsDistribution.estInstallee`).

**Prédiction d'avance** (avant l'appel à la Tooling API) :
- Lister `wrapper/dists/gradle-X/` et vérifier le marqueur `.ok` → on sait
  si le téléchargement aura lieu.
- Si `gradle-wrapper.properties` change (empreinte du fichier) →
  re-téléchargement probable.

### 2.3. Téléchargements de plugins et dépendances

**Observé** (scénarios 01, 03, 10b) : Gradle émet `Downloading https://...`
pour **chaque** ressource téléchargée, **y compris les métadonnées des
plugins** (`.pom`, `.module`). Même un projet Kotlin/JVM pur télécharge
des plugins Gradle au premier run.

**Côté Tooling API** (ce que `EcouteurProgressionCommun` voit) :
- `OperationType.FILE_DOWNLOAD` avec un `DownloadDescriptor` portant l'URL
  complète (les `.pom` et `.module` y passent aussi).
- Le descripteur ne porte pas de type « plugin » vs « dépendance » — il
  faut le distinguer côté client (URL commence par `plugins.gradle.org` vs
  `repo1.maven.org`).

**Conséquence** : la phase « Dépendances » affichée par la console ne
peut pas distinguer les plugins des dépendances runtime sans une
heuristique d'URL. **Recommandation** : fusionner sous l'étiquette
« Dépendances et modèle IDE » (déjà le cas dans l'aperçu v3 pour les syncs
suivantes).

### 2.4. Configuration des projets (multi-modules)

**Observé** (scénario 05) : Gradle émet `> Configure project :<name>` pour
chaque module configuré. Pas de limite de temps observable — chaque
configuration est quasi-instantanée (~50-200 ms).

**Côté Tooling API** : `OperationType.PROJECT_CONFIGURATION` émet un
événement par projet, avec un descripteur portant le nom du projet. C'est
ce que `EcouteurProgressionCommun` reçoit déjà et compte.

**Signal de détection fiable** :
- Nombre de modules = nombre d'événements `PROJECT_CONFIGURATION`.
- Un projet mono-module → 1 événement.
- Un projet `:app + :lib + :feature:editor` → 3 événements.
- L'aperçu affiche « Configuration du projet · N modules ».

### 2.5. buildSrc et includeBuild

**Observé** (scénario 07) : Gradle émet `> Configure project :buildSrc`
puis `> Task :buildSrc:compileKotlin` (et d'autres tâches) **avant** la
configuration du projet racine. C'est une phase séparée, non détectable via
`OperationType.PROJECT_CONFIGURATION` (qui ne couvre que les projets
inclus, pas `buildSrc`/`includeBuild`).

**Signal de détection fiable** :
- Présence d'un dossier `buildSrc/` à la racine OU d'un `includeBuild(...)`
  dans `settings.gradle(.kts)` → la phase `BUILD_LOGIC` sera nécessaire.
- Pas de signal Tooling API spécifique ; on l'infère depuis la présence
  du dossier.

### 2.6. Échec de configuration

**Observé** (scénario 08) : Gradle émet `> Configure project :` puis
directement `Script compilation error:` + `BUILD FAILED` — pas de
`> Task` du tout. L'échec intervient pendant la configuration.

**Côté Tooling API** : l'appel à `GradleConnector` jette une
`GradleConnectionException` — `SyncHandler` publie `SyncResult` avec
`succeeded = false` et le message d'erreur.

**Signal de détection fiable** :
- Sync échouée sans aucune phase annoncée → échec de configuration.
- Sync échouée après au moins une phase annoncée → échec de résolution
  des modèles.

### 2.7. Toolchain JDK à provisionner

**Observé** : aucun de nos scénarios ne déclenche le provisioning d'une
toolchain (le `jvmToolchain(21)` du fixture Kotlin/JVM est satisfait par
le JDK déjà installé).

**Signal de détection théorique** :
- Côté Gradle stderr : `Downloading JDK ... from ...` (lorsque Gradle
  télécharge une toolchain depuis un repo `foojay-resolver-convention`).
- Côté Tooling API : aucun événement spécifique. Les téléchargements de
  toolchains passent par `FILE_DOWNLOAD` avec une URL pointant vers un
  download JDK (Foojay, Adoptium, etc.).

**Recommandation** : ne pas lister l'étape `TOOLCHAIN_JDK` tant qu'elle
n'a pas été observée sur un scénario réel — risque d'affirmation devinée.
**Phase à réserver** pour une fois un scénario de provisioning réel
exécuté.

### 2.8. Configuration cache

**Observé** : aucun scénario n'a activé `--configuration-cache`. La
configuration cache modifie l'ordre et le contenu des événements de
progression (la première sync est plus lente, les suivantes plus
rapides). À tester séparément.

## 3. Catalogue d'étapes dynamique proposé

### 3.1. Étapes par défaut (sync à froid, 1er lancement)

| # | Étape | Signal de détection | Condition d'apparition |
|---|---|---|---|
| 1 | **Vérification des outils** | Aucun événement Tooling API — phase synthétique locale (CodeIDE vérifie JDK + distribution présente avant l'appel Gradle) | Toujours (1er lancement) ; supprimée sur sync suivante si empreinte identique |
| 2 | **Distribution Gradle** | Marqueur `.ok` absent dans `wrapper/dists/` OU `gradle-wrapper.properties` modifié (empreinte SHA-256 différente) | Si distribution nécessaire |
| 3 | **Démarrage du daemon** | Stderr `Starting process 'Gradle build daemon'` (parsé par `StreamingOutputStream.observateur`) | Si daemon froid (jamais si stderr dit `Starting Nth build in daemon`) |
| 4 | **Configuration du projet** | `OperationType.PROJECT_CONFIGURATION` (compteur n/N) | Toujours |
| 5 | **Modèle des tâches** | Phase interne CodeIDE (résolution `GradleProject` via Tooling API) | Toujours |
| 6 | **Modèle IDE et dépendances** | `OperationType.FILE_DOWNLOAD` (téléchargements) + résolution `IdeaProject` | Toujours — fusionne les téléchargements de plugins et dépendances |
| 7 | **Classpaths et sources** | Phase interne CodeIDE (résolution `ClasspathResult` pour LSP) | Toujours |

### 3.2. Étapes supprimées du catalogue actuel (v0.40.0)

- **Phase SAUTÉE / « En cache »** : supprimée entièrement. Une étape qui
  n'a pas lieu n'est PAS listée. Pas de point gris, pas de libellé
  atténué, pas de `sautee` dans le protocole, pas de `R.string.editor_
  console_etape_en_cache`.
- **Étape DAEMON quand le daemon est chaud** : absente. Le stderr ne
  mentionne pas de démarrage → l'étape n'apparaît pas.
- **Étape DISTRIBUTION quand le cache est chaud** : absente. Prédiction
  par empreinte du wrapper + présence du marqueur `.ok` → pas de phase.

### 3.3. Étapes conditionnelles (apparition dynamique)

| Étape | Condition d'apparition | Source du signal |
|---|---|---|
| **buildSrc / build-logic** | Dossier `buildSrc/` OU `includeBuild(...)` dans `settings.gradle(.kts)` | Inspection pré-sync des fichiers Gradle |
| **Configuration cache** | `--configuration-cache` actif | Réglages tooling (Settings) |
| **Toolchain JDK** | À RÉSERVER — non observée, ne pas afficher tant qu'elle n'est pas validée |

### 3.4. Trois scènes de l'aperçu v3

| Scène | Étapes affichées | Justification |
|---|---|---|
| **Sync 1er lancement** | OUTILS · DISTRIBUTION · DAEMON · CONFIGURATION · MODELE_TACHES · DEPENDANCES_MODELE · CLASSPATHS (7) | Tout est à faire, daemon froid, distribution absente |
| **Sync suivante** | CONFIGURATION · MODELE_TACHES · DEPENDANCES_MODELE · CLASSPATHS (4) | Daemon chaud, distribution en cache, outils déjà validés → on saute les 3 étapes de préparation |
| **Gradle modifié** | DISTRIBUTION · DAEMON · CONFIGURATION · MODELE_TACHES · DEPENDANCES_MODELE · CLASSPATHS (6) | La version Gradle a changé → re-téléchargement de la distribution + daemon incompatible → redémarrage à froid |

## 4. Hypothèses invalidées par les scénarios

### 4.1. « La phase DISTRIBUTION n'a lieu qu'au premier lancement »

**Partiellement faux**. La distribution a lieu **chaque fois que la
version ou l'empreinte du wrapper change** — pas seulement au premier
lancement. Le mécanisme de l'empreinte SHA-256 de `gradle-wrapper.properties`
est donc nécessaire (pas seulement « existe-t-il un dossier dans
`wrapper/dists` »).

### 4.2. « Un projet sans dépendances runtime n'a pas de phase téléchargements »

**Faux**. Même un projet Kotlin/JVM pur télécharge les plugins Gradle
(scénario 01 : 25+ fichiers `.pom` et `.module` depuis `plugins.gradle.org`).
La phase « Dépendances » doit donc toujours être listée — mais elle peut
être fusionnée avec « Modèle IDE » sous une seule étiquette
(`DEPENDANCES_MODELE`), comme le fait déjà l'aperçu v3.

### 4.3. « La phase DAEMON peut être détectée via les événements Tooling API »

**Faux**. La Tooling API n'émet aucun `OperationType` pour le démarrage
du daemon. Le seul signal est sur stderr : `Starting process 'Gradle
build daemon'` (cold) vs `Starting Nth build in daemon` (warm). Le
`StreamingOutputStream` doit donc observer le stderr (pas seulement
extraire les diagnostics) pour détecter cette phase.

### 4.4. « La phase CONFIGURATION est instantanée »

**Vrai pour des projets simples**, mais peut dépasser 1 s sur des
projets complexes (buildSrc + Kotlin DSL + version catalog). L'aperçu
affiche la durée seulement pour une étape terminée ≥ 0,1 s — donc une
configuration < 100 ms n'affiche pas de durée. C'est honnête.

### 4.5. « Le `gradle tasks` déclenche la résolution des dépendances »

**Faux**. `gradle tasks` ne résout PAS les dépendances runtime (les
tâches ne sont pas exécutées, seulement listées). Pour observer les
téléchargements, il faut `gradle dependencies --configuration runtimeClasspath`
(scénario 10b) ou un vrai `gradle assemble`. Le `SyncHandler` de CodeIDE
utilise la Tooling API qui résout les modèles — c'est l'équivalent
d'un `dependencies` mais sans exécution.

## 5. Règles d'apparition des étapes

### 5.1. Prédiction pré-sync (catalogue initial)

Avant l'appel à la Tooling API, CodeIDE **construit le catalogue** en
inspectant le projet :

1. **OUTILS** : toujours au premier lancement ; supprimée si empreinte
   SHA-256 des fichiers Gradle identique au cache local.
2. **DISTRIBUTION** : présente si le dossier `wrapper/dists/gradle-X/`
   manque ou si le `gradle-wrapper.properties` diffère du dernier cache
   (empreinte SHA-256).
3. **DAEMON** : présente si on prévoit un daemon froid. Heuristique :
   si la dernière sync a réussi il y a < 3 h (durée d'inactivité par
   défaut du daemon), le daemon est probablement chaud → pas d'étape.
   Si non, étape présente. **Signal réel** (stderr) confirme ou infirme.
4. **BUILD_LOGIC** : présente si `buildSrc/` existe OU `includeBuild()`
   dans `settings.gradle(.kts)`.
5. **CONFIGURATION** : toujours.
6. **MODELE_TACHES** : toujours.
7. **DEPENDANCES_MODELE** : toujours (les téléchargements de plugins
   suffisent à la justifier).
8. **CLASSPATHS** : toujours.

### 5.2. Découverte en cours de route

Si une étape prévue n'a pas lieu (ex. DISTRIBUTION prévue mais stderr
dit « Starting Nth build in daemon » au lieu de « Starting process »)
→ l'étape est **retirée** silencieusement, sans marqueur « sautée ».

Si une étape non prévue apparaît (ex. toolchain JDK à télécharger,
signal stderr nouveau) → l'étape s'**insère** à sa place logique.

### 5.3. Compteur « étape k/N »

- **N est fixé au départ** si la prédiction est complète (TOUS les
  scénarios de notre matrice donnent un catalogue prédictible).
- Si une étape est **découverte** en cours de route, N augmente — c'est
  honnête, le compteur ne recule jamais.
- Si une étape prévue **n'a pas lieu**, on la retire — N diminue. **C'est
  acceptable** car c'est une correction honnête : l'utilisateur voit le
  plan se simplifier en direct, ce qui est mieux qu'une étape
  fantôme « En cache ».

## 6. Recommandations pour les étapes suivantes (C, D, E)

### 6.1. Étape C (étapes dynamiques)

- **Supprimer** dans le protocole v6 : `SyncProgress.sautee`. Tout
  `SyncProgress` est soit « ouverture » (`terminee = false`) soit
  « conclusion » (`terminee = true`), jamais « sautée ».
- **Supprimer** côté UI : `R.string.editor_console_etape_en_cache`,
  `point_etape_sautee.xml`, le `StatutEtapeSync.SAUTEE`, le
  `EtapeSyncAffichee.sautee`, le `ConteurPhasesSync.sauter()`.
- **Ajouter** : inspection pré-sync (QuickFix `AnalyseProjetGradle`) qui
  produit le catalogue prédit et le passe au `SyncHandler` — le serveur
  n'émet QUE les phases prédites (ou découvertes en cours de route).
- **Empreinte SHA-256** : calculée sur `build.gradle*`, `settings.gradle*`,
  `gradle.properties`, `gradle/libs.versions.toml`, `gradle-wrapper
  .properties` (et builds inclus), stockée dans `.codeide/local/
  sync-state.json`.

### 6.2. Étape D (chip d'action unique)

- Le chip affiche **l'action courante** : Sync / Build / Tâches /
  Classpaths / Test / Nettoyage… (libellé + couleur `colorCanal*`
  correspondante).
- Le chip n'est **pas cliquable** — il indique juste l'état. Les
  bascules Sync ↔ Build sont automatiques (sync démarre → chip Sync,
  build démarre → chip Build).
- Plus de `FiltreCanalConsole`, plus de `BasculerFiltreConsole`.
- **Conflit avec `38d7933`** : le `filtreConsole` que je viens d'ajouter
  doit être retiré. L'action `BasculerFiltreConsole` doit être
  supprimée. Les chips Sync/Build dans `fragment_panneau_console.xml`
  doivent être remplacés par un seul chip d'affichage.

### 6.3. Étape E (stats de classpath)

- Les `ClasspathModule` existent déjà dans le protocole. Le `Classpath
  Result` est déjà publié par `ClasspathHandler`. Il faut :
  - Compter les jars/AAR/sources par module côté serveur (extension de
    `ClasspathHandler` ou nouveau message `ClasspathModuleStats`).
  - Côté UI, ajouter des sous-lignes à l'étape CLASSPATHS, une par
    module, avec un appui pour le détail.

## 7. Points non traités dans ce rapport

- **Android app mono-module** : à exécuter dans une session avec
  Android SDK complet.
- **Kotlin Multiplatform** : à exécuter.
- **Version Gradle modifiée** (état de cache e) : à exécuter en
  supprimant `wrapper/dists/gradle-9.7.1-bin/` puis en relançant un
  scénario.
- **Configuration cache activé** : à exécuter avec
  `--configuration-cache`.
- **Toolchain JDK à provisionner** : à exécuter avec un
  `foojay-resolver-convention` plugin et `jvmToolchain(17)` alors que
  seul JDK 21 est installé.
- **Reproduction visuelle du bug progress circulaire (§5)** : à faire
  sur appareil/émulateur. Tests Robolectric possibles en attendant.
