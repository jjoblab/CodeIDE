# feature/home

Écran d'accueil : liste des projets (ListAdapter + DiffUtil), tri, recherche
avec debounce, états chargement/vide/erreur, statut d'accès, actions par projet,
FAB Nouveau projet et Ouvrir un dossier. Adaptatif une/deux colonnes.

Étape 11 (v0.12.0) : mise en évidence du projet créé par le wizard
(ADR 0024 — défilement + contour primaire, identifiant consommé une fois
via `AppNavigator.consommerProjetCree`).

Étape 7 (v0.8.0) : liste complète — `HomeViewModel` (UDF, SavedStateHandle),
`ProjetsAccueilAdapter` (ListAdapter + DiffUtil), recherche à délai, tris,
états d'accès avec résolution, actions par projet, import de dossier
existant (ADR 0015), suppression du disque (ADR 0016), sw600dp 2 colonnes.

Contenu fonctionnel détaillé : voir `README.md` du module.
