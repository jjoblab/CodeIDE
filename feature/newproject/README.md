# feature/newproject — Fonctionnalité — wizard de création de projet

> Statut étape 7 (v0.8.0) : destination **placeholder** — l'accueil y mène
> par « Nouveau projet ». Le wizard complet arrive aux étapes 10-11.

Wizard de création en cinq étapes numérotées (Modèle, Configuration, Informations et emplacement, Fichiers, Récapitulatif) puis écran de création avec progression et rollback. Machine à états `WizardViewModel` partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur de templates, champs dérivés qui suivent leurs sources tant que non modifiés à la main.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- **`NewProjectFragment`** — placeholder de l'étape 7 (retour vers
  l'accueil via `AppNavigator.goBack`)
- Écrans du wizard + machine à états (étapes 10-11, à venir)
- Écrans du wizard + machine à états (étapes 10-11)
- Écran de création avec progression et rollback (étape 11)

## Vérifications du module

```bash
./gradlew :feature:newproject:check
```
