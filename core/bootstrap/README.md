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

## Étape T2 (v0.21.0) — lanceur (et ancien installateur, retiré en E6)

- `NativeProcessLauncher` / `ManagedProcess` (ports `core:domain`) :
  sous-processus **non interactifs** (scripts d'installation, futur
  serveur Gradle — jamais les sessions shell interactives), environnement
  exactement issu de `ProcessEnvironmentProvider`, flux de lignes,
  attente annulable, terminaison explicite, `pid` par réflexion (repli
  `-1`).
- ~~`BootstrapInstaller`~~ (port retiré en E6, ADR 0091) : l'ancien
  pipeline coroutine a vécu de v0.21.0 à v0.54.0 — remplacé par
  `OrchestrateurInstallation` (ADR 0085/0087). L'historique complet vit
  dans les ADR 0033/0046/0048 et le CHANGELOG.
- ~~`Aapt2Deployeur`~~ (retiré en E6) : l'`aapt2` vient du plan du
  manifeste v2 depuis E4 (§ 12.4) — jamais d'un asset.
- Fakes fournis par `core:testing` : `FakeToolchainLocator`,
  `FakeProcessEnvironmentProvider`, `FakeNativeProcessLauncher`
  (+ `ProcessusScripte`), `FakeEnvironmentSetupOrchestrator`.

## À venir

Branché depuis v0.22.0 (écran d'installation) puis E5 (nouvelle
interface) ; sessions shell interactives dans `core:terminal-runtime`.
La refonte du parcours d'installation est terminée (E6, v0.60.0) — suite
du roadmap dans `docs/ROADMAP.md`.

## Dépendances

`core:domain` (ports) — et rien d'autre en production. La règle est
vérifiée par `checkModuleDependencies`.

## Cadre commun d'installation (E2-E4, ADR 0087/0088/0089)

Le sous-package `installation/` porte le nouveau parcours :
`OrchestrateurInstallation` (reprise « verify-first », annulation,
réparation ciblée, licence SDK exigée avant la phase 4, phases
`Degraded` pour les composants non critiques), le runner de commandes à
capture intégrale, le gestionnaire de téléchargements à cache SHA-256 et
reprise `Range`, la persistance `install-state.json` (schéma 2 :
quadruplets + `installPath`), le client du manifeste v2, les **quatre
phases** — `BOOTSTRAP`, `PACKAGE_TOOLS`, `JAVA` (E3 : JDK vérifié par
exécution, sonde TLS) et `ANDROID_SDK` (E4 : plan résolu du manifeste,
péremption par quadruplet, licences, câblage Gradle idempotent,
vérification `sdkmanager`) — et le service de premier plan (notification
avec action Annuler). Les tests éprouvent la machine d'états contre des
phases doublées, le gestionnaire contre un serveur HTTP local à compteur
de requêtes (l'invariant « un composant = une version résolue = un
téléchargement » est vérifié, cache compris), la phase Java contre le
mode muet R6 de l'ADR 0084 et la phase SDK contre le monde simulé
complet (réparation ciblée, dégradé non critique, licences, override
Gradle).

## Migration et unicité du parcours (E6, ADR 0091)

L'ancien parcours a été **supprimé** (`InstallateurBootstrap`,
`TelechargeurBootstrap`, `Ecrivain*Cli`, `Aapt2Deployeur`,
`VersionneurScriptsTerminal` — l'orchestrateur du parcours est l'unique
source de vérité ; les ports `BootstrapInstaller` et
`BootstrapAssetsSource` ont disparu du domaine). Les briques partagées
restent : `ExtracteurBootstrap` (algorithme d'extraction, `extraire`
est une `suspend fun`), `ConfigurateurApt`, `EspaceDisque`,
`CapaciteArchitecture`, `EchecBootstrap`/`AppError.Bootstrap` traduits
à la frontière par `ErreursInstallation`, `LocalisationOutils` (son
héritage `aapt2` de `$PREFIX/bin` documente les installations anciennes)
et `ToolchainBootstrap`.

**Adoption des installations existantes** (ADR 0085 § 6 → 0091 § 1) : au
premier `run()` sur un appareil ayant vécu l'ancien parcours, un
composant présent sous son `installPath` **sans quadruplet persisté**
est vérifié **par exécution** (le `verify` du manifeste) — vérifié →
adopté, quadruplet du plan reconstruit dans `install-state.json`, zéro
retéléchargement ; en échec → réparation de ce composant seul ; un
préfixe déjà basculé dispense de l'archive du bootstrap. La licence du
SDK n'est jamais migrée (§ 12.5 : consentement explicite). Trois
scénarios testés : appareil ancien complet (0 téléchargement), appareil
neuf (parcours propre), à moitié installé (adoption partielle +
réparation du fautif seul).
