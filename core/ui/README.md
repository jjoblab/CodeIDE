# core/ui — Interface — thème Material 3 et composants

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 1 (fondations transverses).

Socle visuel partagé : thème Material 3 clair/sombre (tokens de couleurs, typographie, formes, espacements), couleurs dynamiques optionnelles (Android 12+), classes de base (`BaseFragment<VB>`), composants réutilisables (`EmptyStateView`, `LoadingView`, `ErrorStateView`), helpers edge-to-edge/insets et l'interface `AppNavigator` que `app` implémente.

## Dépendances autorisées

`core:model` uniquement (jamais `core:domain` ni `core:data`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- Thème Material 3 + styles de composants (étape 1)
- BaseFragment<VB> — ViewBinding nettoyé dans onDestroyView (étape 1)
- Extensions de collecte lifecycle-aware (étape 1)
- AppNavigator — navigation découplée entre fonctionnalités (étape 1)
- EmptyStateView, LoadingView, ErrorStateView (étape 1)

## Vérifications du module

```bash
./gradlew :core:ui:check
```
