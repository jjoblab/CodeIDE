# feature/home — Fonctionnalité — liste des projets

> Statut étape 7 (v0.8.0) : **liste des projets complète** — voir
> `docs/TESTS_MANUELS.md` (A1-A10) et ADR 0015-0016.

Écran d'accueil : liste des projets (ListAdapter + DiffUtil), tri, recherche avec debounce, états chargement/vide/erreur, statut d'accès (Introuvable / Permission perdue), actions par projet, FAB Nouveau projet et Ouvrir un dossier. Adaptatif une/deux colonnes.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (via la convention `codeide.android.feature`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- **`HomeFragment`** — destination initiale du graphe de navigation :
  rend l'état de `HomeViewModel` (UDF), n'émet que des actions.
- **`HomeViewModel`** — état observable `EtatAccueil` (projets filtrés
  et triés, états d'accès, requête, tri, rafraîchissement, bandeau),
  événements ponctuels `EffetAccueil` (snackbars), actions
  `ActionAccueil` ; recherche/tri dans le `SavedStateHandle`.
- **`ProjetsAccueilAdapter`** (+ `EcouteurProjets`) — `ListAdapter` +
  `DiffUtil` ; la ligne combine projet et état d'accès.

États couverts par les tests : chargement, vide, sans résultat, erreur
(réessai), contenu trié/filtré, accès rompu avec résolution.

## Vérifications du module

```bash
./gradlew :feature:home:check
```
