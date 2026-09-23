# feature/editor — Espace de travail de l'éditeur (étapes 13-14)

`EditorActivity` — activité séparée de `MainActivity`, trois zones
(ADR 0026 et 0027) :

- **tiroir de navigation gauche** : en-tête (nom du projet, chemin
  lisible, bouton **Actualiser** — revérifie l'accès puis recharge,
  « Fermer le projet ») — **permanent verrouillé ouvert sur grand
  écran** (sw600dp+, façon IDE de bureau) ;
  - **explorateur de fichiers paresseux** (étape 14, ADR 0027) : un
    dossier n'énumère ses enfants (`FileSystem.list`) qu'à son premier
    dépliement, le résultat est mis en cache dans le `EditorViewModel` ;
    tri dossiers puis fichiers puis alphabétique ; icônes par extension
    (`IconesFichiers` de `core:ui`) ; bandeau d'accès
    (`ProjectAccessState` — permission perdue / introuvable / erreur)
    avec action « Résoudre à l'accueil » ; un dossier défaillant est
    signalé par sa ligne, l'appui réessaie ;
  - **barre de navigation basse** : Explorateur active, Recherche et
    Git visibles mais désactivées (« Bientôt disponible ») ;
- **zone centrale** : barre d'outils au nom du projet, onglets de
  fichiers vides (étape 15), états « Aucun fichier ouvert » et
  « Projet introuvable » ;
- **panneau inférieur replié** : en-tête à poignée (replié ↔ mi-hauteur)
  et trois onglets vides Console · Problèmes · Journal (étape 16).

Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte. Le
`EditorViewModel` charge le projet reçu par l'intention (identifiant
par `SavedStateHandle`) et le suit au registre : renommage,
relocalisation (réinitialise l'arborescence) ou suppression depuis
l'accueil se répercutent sans rechargement.

L'ouverture des fichiers en onglets et l'édition `cel-ui` (étape 15)
et le contenu du panneau (étape 16) s'ajouteront à ce cadre.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` — **et**
`com.github.jjoblab.code-editor:cel-ui` (JitPack) : la seule dépendance
externe autorisée dans une fonctionnalité, exception documentée (ADR
0026, prompt compagnon section 3) — bibliothèque de composants d'UI au
même titre que Material Components, pas une source de données. Les
fichiers ne sont lus **que** via l'interface `FileSystem` du domaine,
jamais d'accès direct au SAF ni à `java.io.File`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `EditorActivity` — lancée par `AppNavigator.openEditor(projectId)`
  (extra `ClesEditor.EXTRA_PROJECT_ID`).
- `ActionEditor` — intentions du tiroir : `Rafraichir` (revérification
  d'accès + rechargement), `BasculerNoeud(uri)` (dépliement replié /
  réessai).

## Tests

`EditorViewModelTest` : chargement et suivi du registre, identifiant
inconnu, projet supprimé ; explorateur — tri, énumération paresseuse
et cache (compteur d'appels de `FakeFileSystem`), permission perdue
(bandeau), actualisation, dossier disparu (nœud en erreur réessayable),
relocalisation (réinitialisation).

```bash
./gradlew :feature:editor:check
```
