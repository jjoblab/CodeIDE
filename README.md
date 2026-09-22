# CodeIDE

**Un environnement de développement pour Android, sur Android.**

CodeIDE est un IDE embarquant un éditeur, un terminal et un système de création de
projets directement sur téléphone ou tablette. La **Phase 1** (en cours) pose les
fondations : interface et navigation, configuration de l'application, gestion des
projets, diagnostic (journalisation et plantages) et création de projet via un
assistant avec des modèles **Kotlin** et **Java**.

> État actuel : **étape 2 terminée** (journalisation maison : pipeline asynchrone borné, JSONL
> avec rotation, expurgation à l'écriture, export zip) — v0.3.0.
> Voir `docs/ROADMAP.md` pour le détail des étapes et `CHANGELOG.md` pour l'historique.

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
| `app/` | Module d'application : assemblage final, MainActivity, navigation |
| `build-logic/` | Convention plugins Gradle (configurations partagées des modules) |
| `core/` | Socle transverse : modèle, domaine, données, interface, diagnostics |
| `feature/` | Fonctionnalités : onboarding, accueil, wizard, paramètres… |
| `config/detekt/` | Règles de qualité statique |
| `docs/` | Architecture, conventions, environnement, feuille de route, ADR |
| `scripts/` | Environnement, version, packaging, vérification d'archive |
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
| compileSdk / targetSdk | 37.2 (android-37.2) / 37 |
| minSdk | 26 (Android 8.0) |

Interface : **vues XML + ViewBinding, Activities + Fragments, Material 3** — pas de
Jetpack Compose (ADR 0002). Langues : français (défaut) + anglais.

## Livrables de fin d'étape

Chaque étape validée produit dans `dist/` une archive complète, l'APK debug et les
empreintes SHA-256 (`scripts/package.sh`), vérifiées par `scripts/verify-archive.sh`.

## Licence

Projet personnel — tous droits réservés pour l'instant. Le choix de licence sera
effectué par le propriétaire du projet.
