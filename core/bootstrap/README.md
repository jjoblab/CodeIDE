# core:bootstrap

Localisation des outils du bootstrap natif type Termux et construction de
l'environnement de sous-processus — prompt compagnon « Terminal intégré
et bootstrap natif » (Terminal-1), sections 1.4, 2.2 et 3.

## Périmètre livré (étape T1, v0.20.0)

- `ToolchainLocator` (port `core:domain`) : disposition `filesDir/usr` +
  `filesDir/home`, scan multi-emplacements du JDK (`lib/jvm`, `opt`),
  des distributions Gradle (marqueur `lib/gradle-launcher-*.jar` ou
  apparenté, remontée d'un **vrai** symlink `bin/gradle`), du SDK Android
  (plateformes `android.jar`), du `aapt2` déployé, du cache du wrapper
  Gradle (`~/.gradle/wrapper/dists`) et du shell par défaut.
- `ProcessEnvironmentProvider` (port `core:domain`) : retrait de
  `CLASSPATH`/`LD_PRELOAD` hérités, fixation de `HOME`, `TMPDIR`,
  `PREFIX`, `LANG`, `LD_LIBRARY_PATH`, `GRADLE_USER_HOME` (bug
  `getpwuid`), composition du `PATH`, export conditionnel de
  `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- Toute la logique vit dans des fonctions pures (`LocalisationOutils`,
  `EnvironnementProcessus`) testées en JVM — les adaptateurs Hilt
  (`ToolchainBootstrap`, `EnvironnementProcessusFournisseur`) n'apportent
  que la racine `filesDir` et la délégation.

## Étape T2 (v0.21.0) — lanceur et installateur

- `NativeProcessLauncher` / `ManagedProcess` (ports `core:domain`) :
  sous-processus **non interactifs** (scripts d'installation, futur
  serveur Gradle — jamais les sessions shell interactives), environnement
  exactement issu de `ProcessEnvironmentProvider`, flux de lignes,
  attente annulable, terminaison explicite, `pid` par réflexion (repli
  `-1`).
- `BootstrapInstaller` (port `core:domain`, ADR 0033) : pipeline
  coroutine à `StateFlow` partagé — espace disque (≥ 1 Gio), architecture
  (`aarch64` seul publié), téléchargement `HttpURLConnection` avec
  progression et **empreinte SHA-256 vérifiée**, extraction vers
  `usr-staging` (permissions `0700`, garde anti-traversée), liens du
  manifeste `SYMLINKS.txt`, bascule atomique, **second stage** via le
  lanceur, `sources.list` avec `[trusted=yes]` (correction de l'URL
  antérieure), `apt update` puis paquets **un à un** (état par outil,
  échec global seulement si aucun n'est installé). Annulation propre :
  nettoyage **synchrone** du staging (un appel suspendu depuis une
  coroutine annulée ne revient pas — voir les leçons d'AGENTS.md).
- `Aapt2Deployeur` : binaire cross-compilé déployé depuis les assets
  vers `$PREFIX/bin` — l'absence d'asset à ce jour est une erreur typée
  (`AssetAbsent`), signalée côté `codeide-packages` (paquet `aapt` en
  amont, non publié).
- Fakes fournis par `core:testing` : `FakeToolchainLocator`,
  `FakeProcessEnvironmentProvider`, `FakeNativeProcessLauncher`
  (+ `ProcessusScripte`), `FakeBootstrapInstaller`.

## À venir (étapes T3+)

Branchement dans `app` (écran d'installation, onboarding, permission
`INTERNET` avec ADR dédié), sessions shell interactives
(`core:terminal-runtime`, T4).

## Dépendances

`core:domain` (ports) — et rien d'autre en production. La règle est
vérifiée par `checkModuleDependencies`.
