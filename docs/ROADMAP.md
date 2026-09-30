# CodeIDE — Roadmap v0.42.0+

> Dernière mise à jour : 2026-09-30
> Version courante : 0.41.0

## État actuel (v0.41.0)

### Fonctionnel ✅
- **Tooling Gradle** : sync d'ouverture, étapes dynamiques progressives, sync suivante immédiate (empreinte SHA-256 + revalidation silencieuse), chip d'action unique, stats classpath par module, progress circulaire (AnneauTournant 16dp), lignes stdout/stderr visibles
- **Exécution** : bouton Run (détecte `fun main()`), support stdin/readln (BuildInput + champ saisie), println → console
- **Templates** : kotlin-jvm, java, android-app, spring-boot, kotlin-multiplatform
- **Éditeur** : code-editor 3.40.0, coloration syntaxique, auto-sauvegarde, onglets, explorateur
- **Terminal** : Termux, sessions shell, pty, pont SAF/FUSE
- **Diagnostics** : parseur javac/kotlinc, onglet Problèmes, inline dans l'éditeur

### Problèmes connus ❌
- **Console lente** — un build de 725ms prend 2 minutes à s'afficher (cause : RecyclerView + DiffUtil + StateFlow par ligne = O(n²))
- **Templates trop simples** — un seul fichier Main + Greeter, pas de strings.xml/colors.xml/themes.xml pour Android
- **Clés i18n manquantes** — gitattributes.entete dans KMP/spring-boot/android-app
- **Package name** — `packageFromNameAndAuthor` produit un package non standard (devrait être `com.example.<name>`)
- **Wizard** — ne montre pas correctement les nouveaux paramètres (minSdk, appName, projectType)

---

## Phase 1 — Performance console (CRITIQUE)

> Objectif : un build de 725ms s'affiche en < 1s, pas 2 min.

### 1.1 Architecture hybride (comme Android Studio)

Séparer la console en deux zones :
- **Zone structurée** (RecyclerView) : étapes de sync + tâches de build + synthèse — mise à jour en place via DiffUtil (peu de rangées, O(1) par mise à jour)
- **Zone texte** (TextView monospace scrollable) : lignes stdout/stderr brutes de Gradle — append direct, **pas de DiffUtil, pas de StateFlow par ligne**

```
┌─────────────────────────────────┐
│ > Task :app:compileDebugKotlin 6,1s │  ← RecyclerView (structuré)
│ > Task :app:mergeDebugResources 2,3s│
│   e: file:///.../Main.kt:12:3 error │  ← TextView (texte brut)
│   Downloading kotlin-stdlib...      │  ← TextView
│ ✓ Build réussi en 12,4s             │  ← RecyclerView (structuré)
│ 37 actionable tasks: 2 executed     │  ← TextView (texte brut)
└─────────────────────────────────┘
```

**Implémentation** :
- `PanneauConsoleFragment` : un `RecyclerView` (rangées structurées) + un `TextView` (texte brut) empilés verticalement
- `GradleService` : les `LigneConsole.Sortie` vont dans un buffer texte (`StringBuilder` borné à 2000 lignes), pas dans `etat.lignes`
- `LigneConsole.Tache` et `LigneConsole.Etape` restent dans `etat.lignes` (peu de rangées, DiffUtil OK)
- Le `TextView` est mis à jour par `append()` direct — O(1) par ligne

### 1.2 Optimisations supplémentaires

- **Borner le TextView** à 2000 lignes (tête tronquée, comme `NB_LIGNES_MAX`)
- **Auto-scroll** : le TextView scroll automatiquement vers le bas pendant un build en cours
- **Pas de filtrage** : tout s'affiche, comme Android Studio

---

## Phase 2 — Fix templates

### 2.1 Corriger les clés i18n manquantes

- Ajouter `gitattributes.entete` dans `i18n/fr.json` et `en.json` de :
  - `kotlin-multiplatform`
  - `spring-boot`
  - `android-app`

### 2.2 Corriger le package name

- Android : `com.example.<appName>` par défaut (pas `packageFromNameAndAuthor`)
- Spring Boot : `com.example.<artifactId>` par défaut
- KMP : `com.example.<artifactId>` par défaut
- Kotlin JVM : garder `packageFromNameAndAuthor` (c'est un projet JVM, pas de convention Android)

### 2.3 Enrichir le template Android app

Fichiers à ajouter :
- `app/src/main/res/values/strings.xml` — `<resources><string name="app_name">{{appName}}</string></resources>`
- `app/src/main/res/values/colors.xml` — couleurs de base
- `app/src/main/res/values/themes.xml` — `Theme.Material3.DayNight` avec `parent`
- `app/proguard-rules.pro` — règles ProGuard de base
- `app/src/test/java/.../ExampleUnitTest.kt` — test unitaire simple
- `.gitignore` — complet (local.properties, .gradle, build, *.apk, *.aab)

### 2.4 Enrichir le template Spring Boot

Fichiers à ajouter :
- `src/main/resources/application.yml` — configuration YAML (port, profile)
- `src/main/kotlin/.../GreeterRepository.kt` — repository pattern
- `src/test/kotlin/.../ApplicationTests.kt` — test de contexte Spring

### 2.5 Enrichir le template KMP

Fichiers à ajouter :
- `src/jvmMain/kotlin/.../Greeter.jvm.kt` — implémentation `actual` du Greeter
- Tests `commonTest` plus complets

---

## Phase 3 — Templates avancés

### 3.1 Types de projet Android

Ajouter un paramètre `projectType` au template Android :
- `empty-activity` — Activity + layout (actuel)
- `no-activity` — juste le projet Android sans Activity
- `basic-activity` — Activity + Fragment + navigation drawer

### 3.2 Support Java pour Android

Créer un template `android-app-java` ou ajouter un paramètre `language` (kotlin/java) au template Android existant.

### 3.3 Templates supplémentaires

- `compose-app` — Jetpack Compose (quand CodeIDE supportera Compose — ADR 0002 interdit Compose pour l'instant)
- `gradle-plugin` — développement de plugin Gradle
- `library` — bibliothèque Android (.aar)

---

## Phase 4 — Wizard enrichi

### 4.1 Adapter aux nouveaux paramètres

- Le wizard lit déjà les paramètres depuis `template.json` dynamiquement
- Vérifier que `visibleWhen`, `defaultFrom`, `validator` fonctionnent pour les nouveaux paramètres
- Ajouter des sections spécifiques à Android (minSdk, targetSdk, applicationId)

### 4.2 Aperçu de structure

- Afficher l'arbre des fichiers qui seront générés avant la création
- Permettre de modifier le nom de fichiers/dossiers

### 4.3 Choix de dépendances

- Checkboxes pour ajouter des dépendances communes :
  - Android : Retrofit, Room, Coroutines, Navigation, Hilt
  - Spring Boot : JPA, Security, Actuator, Validation
  - KMP : Serialization, Coroutines, DateTime

---

## Phase 5 — LSP (long terme)

### 5.1 LSP Kotlin

- Utiliser le classpath préparé (`.codeide/local/lsp-classpath.json`)
- Intégrer un LSP Kotlin (ex. kotlin-language-server) via le tooling serveur
- Autocomplétion, hover, go-to-definition, diagnostics en temps réel

### 5.2 LSP Java

- jdtls (Eclipse JDT Language Server) ou java-language-server
- Même architecture que le LSP Kotlin

---

## Phase 6 — Fonctionnalités IDE

### 6.1 Recherche globale
- Recherche de texte dans tous les fichiers du projet
- Remplacement multi-fichiers

### 6.2 Git intégré
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

## Priorisation

| Phase | Priorité | Effort | Impact utilisateur |
|---|---|---|---|
| 1 — Console perf | **CRITIQUE** | Moyen | Très haut — l'app devient utilisable |
| 2 — Fix templates | Haute | Faible | Haut — les templates marchent |
| 3 — Templates avancés | Moyenne | Moyen | Moyen — plus de choix |
| 4 — Wizard | Moyenne | Moyen | Moyen — meilleure UX |
| 5 — LSP | Basse | Très haut | Très haut — transforme en IDE |
| 6 — Fonctionnalités IDE | Basse | Très haut | Très haut — fonctionnalités pro |
