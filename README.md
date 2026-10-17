# CodeIDE

**Un environnement de développement pour Android, sur Android.**

CodeIDE est un IDE embarquant un éditeur, un terminal et un système de création de
projets directement sur téléphone ou tablette.

> État actuel : **v0.135.0** — IDE fonctionnel : tooling Gradle réel (sync,
> console hybride, exécution), wizard de création enrichi, LSP Java/Kotlin/XML
> avec indexation et previews (moteurs du dépôt frère [`codeide-lsp`](https://github.com/jjoblab/codeide-lsp)),
> terminal (Termux, tiroir), refonte complète du parcours d'installation
> (E1–E6, ADR 0082–0091), historique local, Git (clonage, statut),
> paramètres Material 3. Les trois dépendances majeures (LayoutLib, tooling,
> lsp) vivent dans des **dépôts frères** publiés sur GitHub Packages —
> 18 paquets publics, consommés **en binaire par défaut** (voir
> « Dépôts frères » ci-dessous). **Mission IA démarrée** : garde-fous
> livrés (lot IA-0 — réseau explicite, sauvegarde, `LogRedactor`), 4
> modules IA échafaudés (ADR 0121–0126 acceptées, `docs/IA_PLAN_EXECUTION.md`).
> Voir `docs/ROADMAP.md` pour le détail des phases et `CHANGELOG.md` pour l'historique.

## Démarrage rapide

```bash
# 1. Environnement (JDK 21, SDK Android, wrapper Gradle) — idempotent.
scripts/setup-env.sh

# 2. Variables d'environnement de la session.
source scripts/env.sh

# 3. Vérification complète (section 8 du cahier des charges).
./gradlew clean spotlessCheck detekt checkModuleDependencies lintDebug \
  testDebugUnitTest koverVerify assembleDebug

# 4. Installer l'APK debug.
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Organisation du dépôt

| Dossier | Rôle |
|---|---|
| `app/` | Module d'application : assemblage final, MainActivity, navigation, **assets des modèles embarqués** (`kotlin-jvm`, `java`) |
| `build-logic/` | Convention plugins Gradle (configurations partagées des modules) |
| `core/` | Socle transverse : modèle, domaine, données, interface, diagnostics |
| `feature/` | Fonctionnalités : onboarding, accueil, wizard, paramètres… |
| `tools/` | Outils hors application : harnais de génération sur disque (`:tools:generateur`, ADR 0019) |
| `config/detekt/` | Règles de qualité statique |
| `docs/` | Architecture, conventions, environnement, feuille de route, ADR |
| `scripts/` | Environnement, version, packaging, vérification d'archive, **validation réelle des modèles** (`verify-templates.sh`) |
| `gradle/libs.versions.toml` | Catalogue de versions — source unique des dépendances |

L'architecture détaillée (modules, règles de dépendance, patrons) est décrite dans
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Les conventions de code sont dans
[`docs/CONVENTIONS.md`](docs/CONVENTIONS.md).

## Chaîne de build (versions vérifiées)

| Composant | Version |
|---|---|
| JDK | Temurin 21 LTS |
| Gradle | 9.7.1 (wrapper uniquement) |
| Android Gradle Plugin | 9.4.1 (Kotlin intégré, sans `kotlin-android`) |
| Kotlin | 2.2.10 (version embarquée par AGP — voir ADR 0007) |
| KSP / Hilt / Room | 2.3.12 / 2.60.1 / 2.8.5 |
| compileSdk / targetSdk | 37.2 (android-37.2) / **28 (délibéré — exécution des binaires du bootstrap, ADR 0045)** |
| minSdk | 26 (Android 8.0) |

Interface : **vues XML + ViewBinding, Activities + Fragments, Material 3** — pas de
Jetpack Compose (ADR 0002). Langues : français (défaut) + anglais.

## Livrables de fin d'étape

Chaque étape validée produit dans `dist/` une archive complète, l'APK debug et les
empreintes SHA-256 (`scripts/package.sh`), vérifiées par `scripts/verify-archive.sh`.

## Dépôts frères : binaire en premier, composite en opt-in

Trois dépendances vivent dans des dépôts séparés : [`jjoblab/LayoutLib`](https://github.com/jjoblab/LayoutLib)
(moteur de rendu des previews XML), [`jjoblab/codeide-tooling`](https://github.com/jjoblab/codeide-tooling)
(protocol/api/server/testing du tooling Gradle) et [`jjoblab/codeide-lsp`](https://github.com/jjoblab/codeide-lsp)
(moteurs Java/Kotlin/XML + hôte LSP). Le mode par défaut est **BINAIRE**
(arbitrage du 2026-10-18) — trois vitesses :

- **Binaire (défaut)** : les coordonnées `com.github.jjoblab.*` résolvent
  de `~/.m2` (d'abord) puis de **GitHub Packages** (LayoutLib 3.4.7,
  tooling 1.0.0, lsp 1.0.2). GitHub Packages exige une authentification même
  pour un paquet public : fournir un PAT `read:packages` via
  `CODEIDE_PACKAGES_PAT` ou la propriété Gradle `github.packages.pat`.
  Sans jeton, des artefacts déjà publiés en local suffisent ; sans jeton
  ET sans artefacts locaux, la configuration échoue avec un message à
  trois options.
- **Itération (~30 s, sans CI)** : un frère modifié est republié en
  local — `publishToMavenLocal` dans le dépôt frère — et consommé
  immédiatement par CodeIDE (registre `mavenLocal` filtré sur les trois
  groupes).
- **Stabilisation (une commande)** : `scripts/bump-frere.sh
  <tooling|lsp|layoutlib> [patch|minor|major]` — version suivante → tag
  → la CI du frère publie → vérification du registre → ligne
  `gradle/libs.versions.toml` mise à jour → push.
- **Composite (opt-in)** : `-Pfreres.sources` (ou
  `CODEIDE_FRERES_SOURCES=1`) avec les checkouts présents à côté du
  clone — réservé aux sessions de chirurgie croisée multi-dépôts. La CI
  nocturne (`garde-fou-composite`) prouve en continu que ce chemin
  source reste assemblable.

## Licence

Projet personnel — tous droits réservés pour l'instant. Le choix de licence sera
effectué par le propriétaire du projet.
