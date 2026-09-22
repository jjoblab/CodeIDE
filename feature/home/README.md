# feature/home — Fonctionnalité — liste des projets

> Statut étape 5 (v0.6.0) : fragment placeholder + bandeau « Configurer le
> dossier de travail » (`HomeViewModel`, piloté par les paramètres
> applicatifs). La liste réelle des projets arrive à l'étape 7.

Écran d'accueil : liste des projets (ListAdapter + DiffUtil), tri, recherche avec debounce, états chargement/vide/erreur, statut d'accès (Introuvable / Permission perdue), actions par projet, FAB Nouveau projet et Ouvrir un dossier. Adaptatif une/deux colonnes.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (via la convention `codeide.android.feature`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`HomeFragment`** — destination initiale du graphe de navigation (écran provisoire) : `BaseFragment<FragmentHomeBinding>`, injection Hilt de `AppNavigator`, insets edge-to-edge.

Prévu à l'étape 7 : `HomeViewModel`, liste, recherche, tri, actions par projet, états vide/chargement/erreur.

## Vérifications du module

```bash
./gradlew :feature:home:check
```
