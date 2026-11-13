# core/ui — Interface — thème Material 3 et composants

> Statut étape 1 : socle complet livré (thème, tokens, `BaseFragment`, composants d'état, helpers insets, `AppNavigator`).

Socle visuel partagé : thème Material 3 clair/sombre (tokens de couleurs, typographie, formes, espacements), couleurs dynamiques optionnelles (Android 12+), classes de base (`BaseFragment<VB>`), composants réutilisables (`EmptyStateView`, `LoadingView`, `ErrorStateView`), helpers edge-to-edge/insets et l'interface `AppNavigator` que `app` implémente.

## Dépendances autorisées

`core:model` autorisé par la section 5.2, mais **aucune dépendance de module utilisée à ce stade** (aucune API de model nécessaire ; règle « aucune dépendance morte »). Bibliothèques : coroutines (api), core-ktx, fragment, lifecycle-runtime, material, splashscreen, viewbinding.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **Thème `Theme.CodeIDE`** (clair/sombre) et **`Theme.CodeIDE.Splash`** (API SplashScreen, `postSplashScreenTheme`) ; couleurs/typographie/formes/espacements en **tokens** (`values/colors.xml`, `typography.xml`, `shapes.xml`, `dimens.xml`, `styles.xml`).
- **`BaseFragment<VB>`** — ViewBinding créé en `onCreateView` (final), libéré en `onDestroyView` ; accès via `binding`.
- **`collectWithLifecycle`** — collecte lifecycle-aware (`repeatOnLifecycle(STARTED)` par défaut).
- **`EmptyStateView` / `LoadingView` / `ErrorStateView`** — composants d'état configurables par XML (`stateIcon`, `stateTitle`, `stateMessage`, `errorRetryText`) ou par code ; bouton Réessayer avec écouteur.
- **`applySystemBarsInsets` / `applyImeBottomInset`** — helpers edge-to-edge (un seul écouteur d'insets par vue).
- **`applyDynamicColorsIfAvailable`** — couleurs dynamiques Material You (Android 12+), optionnelles (ADR 0008).
- **`AppNavigator`** — navigation découplée (`openSettings`, `goBack` à l'étape 1 ; s'enrichit à chaque étape), implémentée dans `app`.
- **`IconesFichiers`** (étape 14) — icône d'un fichier de l'explorateur selon son extension (badges vectoriels maison : Kotlin, Java, Gradle, XML, Markdown, JSON, dossier, fichier générique en repli) ; `pourNom(nom)`, `pourDossier()`.

## Vérifications du module

```bash
./gradlew :core:ui:check
```
