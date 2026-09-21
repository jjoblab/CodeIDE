# core/storage — Stockage — accès aux fichiers via SAF

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 4 (couche données).

Implémente `FileSystem` du domaine au-dessus du Storage Access Framework : URI (jamais de `File`), permissions persistantes, requêtes groupées `DocumentsContract` (jamais de boucle sur `DocumentFile`), gestion des dossiers refusés par Android 11+. Voir la section 5.6 du prompt et l'ADR 0003.

## Dépendances autorisées

`core:model`, `core:domain`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- SafFileSystem — implémentation complète de FileSystem (étape 4)
- Gestion des permissions persistantes (étape 4)

## Vérifications du module

```bash
./gradlew :core:storage:check
```
