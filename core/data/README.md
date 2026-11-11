# core/data — Couche données — implémentations des repositories

> Statut étape 4 : **livré** (v0.5.0).

Implémentations des repositories du domaine (section 11, étape 4) et
assemblage des sources réelles :
[ProjectRepositoryImpl](src/main/kotlin/jo/codeide/core/data/ProjectRepositoryImpl.kt)
(registre des projets sur Room — l'identifiant UUID et l'horodatage sont
produits à l'ajout, le `documentUri` unique est défendu par la base,
`SQLiteConstraintException` traduit en `AlreadyExists`) et
[SettingsRepositoryImpl](src/main/kotlin/jo/codeide/core/data/SettingsRepositoryImpl.kt)
(paramètres sur DataStore, délégation pure à `core:datastore`).

Journalisation des opérations via `AppLogger` — **identifiants
uniquement** (règle 15) : jamais un nom de projet, un nom d'auteur ni un
libellé de dossier. Les tests verrouillent cette convention.

Politique assumée : `removeProject` retire du registre **sans toucher au
dossier sur disque** (supprimer des fichiers est une action explicite) ;
`renameProject` change le libellé **en base uniquement** (ADR 0012) ;
`setWorkspace` persiste le réglat sans gérer les permissions (le flux
onboarding/Paramètres des étapes 5-6 prend la permission via `FileSystem`
et décide des libérations — les projets peuvent encore dépendre de
l'ancien accès).

## Dépendances autorisées

`core:domain`, `core:model`, `core:database`, `core:datastore`,
`core:storage`, `core:logging` (fakes de `core:testing` en
`testImplementation`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- les liaisons Hilt `ProjectRepository` et `SettingsRepository` vers
  leurs implémentations — rien d'autre ne sort du module (les features
  et `app` n'injectent que les interfaces du domaine).

## Vérifications du module

```bash
./gradlew :core:data:check
```

Tests Robolectric avec les sources réelles : Room en mémoire pour le
registre (ordre de l'accueil, unicité, trim, comptages, idempotence) et
DataStore sur fichier temporaire pour les paramètres, y compris la
convention de journalisation.
