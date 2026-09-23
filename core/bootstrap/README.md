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

## À venir (étapes T2+)

`NativeProcessLauncher` (sous-processus non interactifs),
`BootstrapInstaller` (téléchargement, extraction, second stage,
`sources.list`), `Aapt2Deployer`. Voir `docs/adr/0032` et la ROADMAP.

## Dépendances

`core:domain` (ports) — et rien d'autre en production. La règle est
vérifiée par `checkModuleDependencies`.
