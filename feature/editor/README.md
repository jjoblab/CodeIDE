# feature/editor — Espace de travail de l'éditeur (étape 13)

`EditorActivity` — activité séparée de `MainActivity`, trois zones sans
logique (ADR 0026) :

- **tiroir de navigation gauche** : en-tête (nom du projet, chemin
  lisible, « Fermer le projet ») — **permanent verrouillé ouvert sur
  grand écran** (sw600dp+, façon IDE de bureau) ;
- **zone centrale** : barre d'outils au nom du projet, onglets de
  fichiers vides (étape 15), états « Aucun fichier ouvert » et
  « Projet introuvable » ;
- **panneau inférieur replié** : en-tête à poignée (replié ↔ mi-hauteur)
  et trois onglets vides Console · Problèmes · Journal (étape 16).

Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte. Le
`EditorViewModel` charge le projet reçu par l'intention (identifiant
par `SavedStateHandle`) et le suit au registre : renommage ou
suppression depuis l'accueil se répercutent sans rechargement.

L'explorateur (étape 14), les onglets et l'édition `cel-ui` (étape 15)
et le contenu du panneau (étape 16) s'ajouteront à ce cadre.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` — **et**
`com.github.jjoblab.code-editor:cel-ui` (JitPack) : la seule dépendance
externe autorisée dans une fonctionnalité, exception documentée (ADR
0026, prompt compagnon section 3) — bibliothèque de composants d'UI au
même titre que Material Components, pas une source de données.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `EditorActivity` — lancée par `AppNavigator.openEditor(projectId)`
  (extra `ClesEditor.EXTRA_PROJECT_ID`).

## Tests

`EditorViewModelTest` : chargement et suivi du registre, identifiant
inconnu, projet supprimé.

```bash
./gradlew :feature:editor:check
```
