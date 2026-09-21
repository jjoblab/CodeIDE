# feature/onboarding — Fonctionnalité — assistant de premier lancement

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 5 (onboarding).

Assistant de premier lancement en cinq étapes (bienvenue, dossier de travail SAF avec test d'écriture, apparence avec aperçu immédiat, profil, terminé), pager non swipable, transitions MaterialSharedAxis. L'état survit à la rotation et à la mort du processus.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (jamais les autres fonctionnalités).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- OnboardingFragment — hôte du pager (étape 5)
- Écrans : Bienvenue, Dossier, Apparence, Profil, Terminé (étape 5)

## Vérifications du module

```bash
./gradlew :feature:onboarding:check
```
