# Environnement de build

Ce document décrit les prérequis, les versions retenues et les commandes de
vérification de l'environnement CodeIDE (section 4 du prompt maître).
Toutes les versions ont été **vérifiées sur les dépôts officiels** le
2026-09-22, jamais déduites de mémoire.

## Prérequis

| Outil | Version retenue | Remarques |
|---|---|---|
| JDK | Temurin **21.0.12.1** (LTS) | AGP 9.4 exige ≥ 17 ; nous figeons la 21. `JAVA_HOME` requis. |
| Command-line tools Android | build **13114758** | `sdkmanager --licenses` acceptées. |
| platform-tools | 37.0.1 | `adb`, etc. |
| Plateforme SDK | **platforms;android-37.2** | Dernière API stable installable au 2026-09-22. |
| Build-tools | **36.0.0** | Version minimale par défaut documentée pour AGP 9.4. |
| Gradle | **9.7.1** | **Uniquement via le wrapper** (`./gradlew`). |
| git, zip, unzip, sha256sum, curl | versions système | |
| Maven + JDK multiples | non installé | Requis **uniquement à partir de l'étape 9** (validation des modèles générés). |

Émulateur : **non retenu** dans cet environnement (pas de KVM disponible). Les
tests instrumentés SAF seront documentés dans `docs/TESTS_MANUELS.md` et
exécutés sur appareil réel — signalé explicitement dans les rapports d'étape.

## Installation et vérification

```bash
scripts/setup-env.sh    # idempotent : installe ce qui manque, valide le reste
source scripts/env.sh   # exporte JAVA_HOME, ANDROID_HOME, PATH
```

Sorties de vérification (extraits, 2026-09-22) :

```
$ java -version
openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS)

$ sdkmanager --list_installed
  build-tools;36.0.0      | 36.0.0 | Android SDK Build-Tools 36
  platform-tools          | 37.0.1 | Android SDK Platform-Tools
  platforms;android-37.2  | 1      | Android SDK Platform 37.2

$ ./gradlew --version
Gradle 9.7.1 (via wrapper, JVM 21.0.12.1)
```

## Versions de la chaîne de build

| Composant | Version | Contrainte de compatibilité |
|---|---|---|
| AGP | 9.4.1 | Gradle ≥ 9.6.0 ; API ≤ 37 ; JDK ≥ 17 |
| Kotlin | **2.2.10** | Version **embarquée** par AGP 9.4.1 pour le Kotlin intégré (ADR 0007) |
| KSP | 2.3.12 | Versionnement indépendant depuis KSP 2.3 ; compatible AGP 9 |
| Hilt | 2.60.1 | Via KSP (kapt incompatible AGP 9) |
| Room | 2.8.5 | Via KSP |
| kotlinx-coroutines | 1.11.0 | stdlib 2.2.20 — lisible par Kotlin 2.2.10 |
| kotlinx-serialization | **1.9.0** | 1.10+ est compilé avec Kotlin 2.3 : illisible par le compilateur 2.2 — rester en 1.9.0 tant que Kotlin 2.2.10 est imposé |
| Material | 1.14.0 | — |
| AndroidX | appcompat 1.8.0, core 1.19.0, fragment 1.9.0, navigation 2.10.1, lifecycle 2.11.0, datastore 1.2.1, splashscreen 1.2.0… | Catalogue `gradle/libs.versions.toml` |
| androidx.databinding:viewbinding | 9.4.1 | Suit la version de l'AGP (voir « particularités ») |
| detekt / Spotless / ktlint | 1.23.8 / 8.10.2 / 1.8.0 | — |
| Kover | 0.9.9 | Seuils ≥ 80 % sur `core:model` et `core:domain` |
| Robolectric | 4.17 | Voir « particuliarités » ci-dessous |
| JUnit | 4.13.2 | Choix du prompt maître pour les tests CodeIDE |
| LeakCanary | 2.14 | `debugImplementation` uniquement |

## Particularités découvertes et contournements

### AGP 9 : Kotlin intégré (ADR 0007)

AGP 9.4.1 active le support Kotlin intégré **par défaut** : ne pas appliquer
`org.jetbrains.kotlin.android` (erreur « extension kotlin already registered »).
La configuration passe par `kotlin { compilerOptions { } }`. kapt est
incompatible — tout traitement d'annotations passe par KSP.

### Robolectric sur JDK récent (JPMS)

Robolectric doit refléter les internes `java.io.FileDescriptor` via
`jdk.internal.access.SharedSecrets`, non exportés par le système de modules.
Sans correctif : `Failed to interact with raw FileDescriptor internals; perhaps
JRE has changed?` (issue robolectric/robolectric#11434). Les conventions
appliquent à toutes les tâches de test :

```
--add-exports java.base/jdk.internal.access=ALL-UNNAMED
```

`testOptions.unitTests.isIncludeAndroidResources = true` est également requis
pour que le manifeste fusionné soit visible des tests.

### Modules vides + KSP + Gradle 9

KSP crée des répertoires de sortie vides (`build/generated/ksp/…`) que Gradle 9
considère comme « sources de test présentes » : un module **sans test** fait
alors échouer `testDebugUnitTest` (failOnNoDiscoveredTests). Conséquence : les
conventions KSP (`codeide.android.feature`, `codeide.android.hilt`,
`codeide.android.room`) ne sont appliquées qu'aux modules qui contiennent du
code — et donc des tests. Depuis l'étape 1, `feature:home` et `feature:settings`
appliquent la convention feature (chaque module a au moins un test).

### Mémoire et CPU : build séquentiel et Kotlin in-process (étape 1)

Avec 18 modules actifs (KSP, Hilt, Robolectric), l'exécution parallèle a
provoqué l'**OOM killer du noyau** : plusieurs démons Kotlin autonomes
s'empilaient (≈ 700 Mo chacun) aux côtés du démon Gradle et des JVM de test.
`gradle.properties` est donc réglé depuis l'étape 1 sur :

- `org.gradle.parallel=false` et `org.gradle.workers.max=1` (build séquentiel) ;
- `kotlin.compiler.execution.strategy=in-process` — la compilation Kotlin se
  fait **dans** le démon Gradle : plus aucun démon Kotlin séparé ;
- `org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m` ;
- `maxHeapSize = "640m"` sur toutes les tâches `Test` (configuré dans les
  conventions de `build-logic`).

Sur une machine plus dotée, ces réglages peuvent être relâchés.

### ViewBinding sous AGP 9 : coordonnées et paquet (étape 1)

L'interface `ViewBinding` vit dans la bibliothèque
`androidx.databinding:viewbinding` (même version que l'AGP, 9.4.1), paquet
`androidx.viewbinding`. AGP l'ajoute **automatiquement** aux modules qui
activent `buildFeatures.viewBinding` ; `core:ui` la déclare explicitement car
`BaseFragment<VB>` n'active pas la génération. Les classes générées vivent
dans le sous-paquet `<namespace>.databinding` du module (importer
`jo.codeide.feature.home.databinding.FragmentHomeBinding`).

### Tests unitaires en bibliothèque : classe R du source set de test

Dans un module **bibliothèque**, les tests référencent la classe `R` du
source set de test (`jo.codeide.core.ui.test.R`) : elle contient les symboles
fusionnés du module **et** des dépendances (incluant la nôtre et les
bibliothèques). Dans un module **application**, les identifiants des
dépendances restent dans leur propre R (`import … as RAccueil`) car le R du
test n'existe pas. Par ailleurs, un `ContextThemeWrapper` sur
`Theme.CodeIDE` est nécessaire pour gonfler des composants Material dans
Robolectric.

### Hilt 2.60 : composants Android et Activity non qualifiée

Depuis Hilt 2.60, `ActivityComponent` vit dans `dagger.hilt.android.components`
(l'ancien paquet `dagger.hilt.components` ne contient plus que
`SingletonComponent`) et le builder du composant d'activité expose l'activité
en instance liée **non qualifiée** : injecter `Activity` sans `@ActivityContext`
(voir `app/…/AppNavigatorImpl.kt`).

## Réseau

Domaines requis : `dl.google.com`, `maven.google.com`, `repo.maven.apache.org`,
`plugins.gradle.org`, `services.gradle.org` (distribution Gradle),
`api.adoptium.net` (JDK Temurin), `github.com` (redirections Adoptium).
Si l'un d'eux est bloqué, **ne pas contourner** : lister précisément les
domaines à autoriser et demander à l'utilisateur.


## Bibliothèque d'édition — JitPack (étape 13)

La bibliothèque `code-editor` (cel-ui) est consommée via **JitPack**
(`https://jitpack.io`, ajouté au gestionnaire de résolution global du
`settings.gradle.kts`) : le build reste autonome, sans jeton. Coordonnées
réelles : `com.github.jjoblab.code-editor:cel-ui:3.37.0` (dernier tag
stable vérifié le 2026-09-23 ; cel-core et cel-lsp-api transitifs).

Alternative locale (non utilisée par défaut, prompt compagnon
section 2.2) : GitHub Packages avec `gpr.user`/`gpr.key` dans
`~/.gradle/gradle.properties` — jamais versionnés.
