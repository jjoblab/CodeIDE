# core/datastore — Préférences — source des paramètres applicatifs

> Statut étape 4 : **livré** (v0.5.0).

Source de données des paramètres (section 11, étape 4) :
[SettingsDataStore](src/main/kotlin/jo/codeide/core/datastore/SettingsDataStore.kt)
projette Preferences DataStore vers `AppSettings` du modèle. Rôle étroit :
**persister et relire**, aucune règle métier — les cas d'usage du domaine
décident, ce module traduit.

Robustesse :
- fichier corrompu **remplacé** par des préférences vides
  (`ReplaceFileCorruptionHandler`, section 6 de la stack) — l'utilisateur
  retrouve les défauts, pas un crash ;
- lecture tolérante : une valeur inconnue sur disque (montée de version,
  édition manuelle) retombe sur le défaut **champ par champ**, sans
  invalider les autres réglages ;
- le dossier de travail vit en trio de clés (`grant_uri`,
  `document_uri`, `display_path`) — un trio incomplet vaut « non
  configuré », jamais un état incohérent ;
- erreurs d'E/S : lecture → publication des défauts (le flot ne coupe
  jamais l'UI), écriture → `AppResult` typé (l'appelant sait que son
  réglage n'est pas pris) ;
- transformations **atomiques** : `update` fait lire-transformer-réécrire
  sous le verrou DataStore, sans mise à jour perdue.

Les défauts dépendent du type de build via `FLAG_DEBUGGABLE` : la
verbosité de journalisation démarre en `DETAILED` en debug, `NORMAL` en
release (section 5.7) — c'est elle qu'`app` applique au moteur via
`LogLevelApplier` (`core:logging`) au démarrage du processus principal.

## Dépendances autorisées

`core:model` (API), `core:domain` (réservé si besoin ; fakes de
`core:testing` en `testImplementation`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `SettingsDataStore` — `observe(): Flow<AppSettings>`,
  `current(): AppResult<AppSettings>`,
  `update((AppSettings) -> AppSettings): AppResult<Unit>`,
  `setWorkspace(StorageLocation?)` ;
- les liaisons Hilt (DataStore + source) fournies par le module.

## Vérifications du module

```bash
./gradlew :core:datastore:check
```

Tests Robolectric : allers-retours de chaque réglage, trio du dossier de
travail (définition, effacement, dégradation), valeurs inconnues,
fichier corrompu, transformations concurrentes sérialisées.
