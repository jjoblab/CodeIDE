# feature/home — Fonctionnalité — liste des projets

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 7 (accueil).

Écran d'accueil : liste des projets (ListAdapter + DiffUtil), tri, recherche avec debounce, états chargement/vide/erreur, statut d'accès (Introuvable / Permission perdue), actions par projet, FAB Nouveau projet et Ouvrir un dossier. Adaptatif une/deux colonnes.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- HomeFragment + HomeViewModel (étape 7)
- Liste, recherche, tri, actions par projet (étape 7)

## Vérifications du module

```bash
./gradlew :feature:home:check
```
