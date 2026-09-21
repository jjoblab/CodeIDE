# AGENTS.md — Repère pour les sessions futures

Ce fichier résume les règles de travail du projet CodeIDE pour qu'un agent
(ou un développeur) reprenne **sans perte de contexte**. Il est tenu à jour à
chaque étape. Le document de référence complet est le prompt maître
(« CodeIDE — Prompt maître pour agent IA », version 1.0) ; en cas de
contradiction, le prompt maître prime.

## Rôle

Ingénieur Android/Kotlin senior. Reconstruire CodeIDE **de zéro** : base saine,
modulaire, testée, maintenable. Une étape à la fois, livraison validée par
l'utilisateur à chaque fin d'étape (« GO étape N+1 »).

## Paramètres du projet (ne pas changer sans accord)

- `applicationId` = **`jo.codeide`** (imposé).
- Kotlin 100 % (aucun Java écrit à la main) ; identifiants en anglais.
- **KDoc, commentaires, commits, documentation, messages d'erreur : français.**
- minSdk 26 ; compileSdk/targetSdk = dernière API stable (37.2/37 à ce jour).
- UI : vues XML + ViewBinding, Activities + Fragments, Material 3.
  **Pas de Jetpack Compose** (ADR 0002).
- Langues de l'interface : français (`values/`, défaut) + anglais (`values-en/`).
- Stockage des projets : SAF (URI, jamais `File`) — ADR 0003.
- SemVer : `0.N.0` par étape validée, `0.N.M` par correction.
- Aucune permission `INTERNET` ni `MANAGE_EXTERNAL_STORAGE` en Phase 1.

## Règles impératives (abrégées — section 3 du prompt maître)

1. Aucune logique métier dans Activity/Fragment/ViewModel : elle vit dans des
   use cases du domaine.
2. Règles de dépendance entre modules vérifiées par `./gradlew
   checkModuleDependencies` (le build échoue en cas de violation).
3. Aucune ressource en dur : tout passe par les ressources et les tokens.
4. Interdits : `!!`, `GlobalScope`, `runBlocking` (hors tests), `Thread.sleep`,
   `catch` qui avale l'erreur, `@Suppress` sans justification commentée.
5. I/O hors du thread principal ; dispatchers **injectés**
   (`DispatcherProvider`).
6. Erreurs attendues modélisées (`AppResult`/`AppError`), jamais des
   exceptions jusqu'à l'UI ; `CancellationException` toujours relancée.
7. Aucun secret ni `local.properties` dans le dépôt ni dans l'archive.
8. Aucune vérification désactivée pour faire passer le build (exceptions
   ciblées, minimales et **commentées** uniquement).
9. Dépendances justifiées ; versions uniquement via `gradle/libs.versions.toml`.
   **Ne jamais deviner un numéro de version** : le vérifier sur Google Maven,
   Maven Central ou le Plugin Portal.
10. Commits conventionnels en français (`feat(newproject): ajoute la validation du nom`).
11. Journalisation via `AppLogger` uniquement. `android.util.Log`, `println`,
    `printStackTrace` interdits hors `core:logging` et `core:crash`
    (règle detekt active). **Aucune donnée personnelle dans les journaux.**
12. Le gestionnaire de plantages ne doit jamais lui-même planter ni bloquer.

## Architecture (sections 5 et 6 du prompt)

Modules : `app`, `core:{model, domain, data, database, datastore, storage,
logging, crash, ui, testing}`, `feature:{onboarding, home, newproject,
settings, diagnostics, editor}`. `core:model` et `core:domain` sont des
modules **Kotlin JVM purs** avec `explicitApi()`.

Tableau des dépendances autorisées : `docs/ARCHITECTURE.md`. Patron de
présentation : MVVM + flux unidirectionnel (UiState/Action/Effect via
StateFlow/Channel). Navigation inter-features via `AppNavigator` (interface
dans `core:ui`, implémentée dans `app`).

## Chaîne de build (vérifiée — voir ADR 0007 et docs/ENVIRONNEMENT.md)

- JDK Temurin 21, Gradle **9.7.1 via wrapper uniquement**, AGP **9.4.1**.
- **AGP 9 = Kotlin intégré** : ne PAS appliquer `org.jetbrains.kotlin.android` ;
  configurer via `kotlin { compilerOptions { } }`. kapt interdit → KSP 2.3.12.
- Kotlin **2.2.10** (embarqué par AGP 9.4.1 — ne pas monter à 2.4.x sans
  revalidation complète, les métadonnées compilées 2.4 sont illisibles par 2.2).
- kotlinx-serialization **1.9.0** (1.10+ exige Kotlin 2.3 — incompatible).
- Robolectric 4.17 exige `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`
  (déjà configuré dans les conventions) et `isIncludeAndroidResources = true`.
- Tests : JUnit 4 (choix du prompt), noms de test en français avec accents graves.

## Commandes

```bash
source scripts/env.sh                      # JAVA_HOME, ANDROID_HOME, PATH
./gradlew clean spotlessCheck detekt checkModuleDependencies lintDebug \
  testDebugUnitTest koverVerify assembleDebug   # vérification complète (doit être verte)
./gradlew spotlessApply                    # formatage avant commit
scripts/bump-version.sh minor              # incrémente la version
scripts/package.sh 0                       # dist/ : archive + APK + SHA256SUMS
scripts/verify-archive.sh dist/CodeIDE-v0.1.0-etape00.zip   # archive autonome ?
```

## Définition de « terminé » (par étape)

Fonctionnalités de l'étape sans débordement ; vérification complète verte ;
tests de la logique ajoutée (≥ 80 % sur `core:model`/`core:domain`) ; KDoc et
docs à jour ; `CHANGELOG.md`, `ROADMAP.md`, `AGENTS.md` à jour ; aucun TODO non
tracé ; version incrémentée, tag Git, archive créée **et vérifiée** ; rapport
remis (format section 14) puis attente du « GO ».

## État d'avancement

- [x] Étape 0 — Environnement, squelette, outillage → v0.1.0
- [x] Étape 1 — Fondations transverses → v0.2.0 (`core:model` AppResult/AppError/identifiants/
      StorageLocation, `core:domain` DispatcherProvider, `core:testing` MainDispatcherRule/
      TestDispatcherProvider, `core:ui` thème M3 complet + BaseFragment + composants d'état +
      insets + AppNavigator, `app` Hilt/SplashScreen/NavHost avec navigation Home ↔ Settings,
      features placeholder, ADR 0008)
- [ ] Étape 2 — Journalisation → v0.3.0
- [ ] Étape 3 — Gestion des plantages → v0.4.0
- [ ] Étape 4 — Couche données → v0.5.0
- [ ] Étape 5 — Onboarding → v0.6.0
- [ ] Étape 6 — Paramètres → v0.7.0
- [ ] Étape 7 — Accueil → v0.8.0
- [ ] Étape 8 — Moteur de templates → v0.9.0
- [ ] Étape 9 — Modèles Kotlin/Java → v0.10.0
- [ ] Étape 10 — Wizard (partie 1) → v0.11.0
- [ ] Étape 11 — Wizard (partie 2) → v0.12.0
- [ ] Étape 12 — Diagnostic → v0.13.0
- [ ] Étape 13 — Ouverture, finitions, audit → v0.14.0

Détail de chaque étape : `docs/ROADMAP.md` et section 11 du prompt maître.
