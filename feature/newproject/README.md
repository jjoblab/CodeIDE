# feature/newproject — Fonctionnalité — wizard de création de projet

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étapes 10-11 (wizard).

Wizard de création en cinq étapes numérotées (Modèle, Configuration, Informations et emplacement, Fichiers, Récapitulatif) puis écran de création avec progression et rollback. Machine à états `WizardViewModel` partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur de templates, champs dérivés qui suivent leurs sources tant que non modifiés à la main.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- NewProjectFragment — hôte du wizard (étape 10)
- Écrans du wizard + machine à états (étapes 10-11)
- Écran de création avec progression et rollback (étape 11)

## Vérifications du module

```bash
./gradlew :feature:newproject:check
```
