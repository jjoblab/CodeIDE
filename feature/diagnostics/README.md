# feature/diagnostics — Écran Diagnostic (étape 12)

Visionneuse des journaux applicatifs et des rapports de plantage,
accessible depuis **Paramètres › Avancé › Diagnostic**.

- **Onglet Journaux** : fenêtre des 500 dernières entrées (plus anciennes
  révélées au défilement — pagination en mémoire, ADR 0025), filtres par
  niveau (chips combinables), recherche avec délai (250 ms, insensible à la
  casse et aux accents), suivi en direct, détail d'une entrée avec
  exception repliable, taille occupée sur disque ; actions Partager
  (archive via la feuille de partage), Enregistrer (SAF, écriture directe)
  et Effacer (confirmation) ; réglage du niveau de journalisation
  Normal / Détaillé — persisté puis appliqué à chaud au moteur.
- **Onglet Plantages** : historique vivant des rapports conservés (date,
  type, exception, résumé, pastille « Non consulté ») ; l'appui ouvre
  l'écran dédié en consultation (`AppNavigator.openCrashReport`) ;
  suppression unitaire et globale (confirmations), export en archive.
- **Section Informations** : version, appareil non identifiant,
  identifiant de session de journalisation ; le **menu debug** (build
  debug uniquement — no-op de même signature en release) vit ici :
  provoquer un plantage, exception non fatale, salve de journaux, essais
  SAF S1-S5.

Aucune donnée personnelle affichée ni exportée (règle 15).

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (+ `androidx.viewpager2` pour les
onglets — le seul ajout propre au module au-delà de la convention
feature).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `DiagnosticFragment` — destination du graphe de navigation
  (`R.id.diagnostics`, action `action_settings_to_diagnostics`).
- `JournalViewModel` / `PlantagesViewModel` — états, actions et effets UDF
  par onglet.

## Tests

`JournalViewModelTest`, `PlantagesViewModelTest` (filtres, recherche à
délai, fenêtre/pagination, fusion sans doublon, effacement, partage,
enregistrement, verbosité, restauration `SavedStateHandle` ; listes,
suppressions, export).

```bash
./gradlew :feature:diagnostics:check
```
