# feature/diagnostics — Fonctionnalité — visionneuse de journaux et plantages

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 12 (diagnostic).

Écran à deux onglets (journaux, plantages) accessible depuis Paramètres › Avancé › Diagnostic : liste performante des 500 dernières entrées avec filtres et recherche, mode suivi en direct, actions partager/enregistrer/effacer, réglage du niveau de journalisation ; historique des rapports de plantage avec ouverture en mode VIEW via AppNavigator. Aucune donnée personnelle affichée ni exportée.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- DiagnosticsFragment + onglets Journaux / Plantages (étape 12)

## Vérifications du module

```bash
./gradlew :feature:diagnostics:check
```
