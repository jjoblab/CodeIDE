# core/database — Base de données — Room v1 (registre des projets)

> Statut étape 4 : **livré** (v0.5.0).

Persistance Room du registre des projets (section 11, étape 4) : table
`projects` avec **index unique sur `document_uri`** (le même dossier ne
peut pas être référencé deux fois — filet de sécurité du wizard,
section 12.4), tri de l'accueil porté par la requête (épingles
d'abord, dernier ouvert d'abord — les `NULL` ferment la marche —, puis
nom croissant insensible à la casse), mutations ciblées par colonne qui
comptent les lignes affectées.

Schéma **exporté** dans `schemas/jo.codeide.core.database.CodeIdeDatabase/1.json`
(convention `codeide.android.room`) : référence pour écrire et tester les
migrations des versions futures — jamais de `fallbackToDestructiveMigration`.
Aucun convertisseur de type en v1 : toutes les colonnes sont des
primitives SQLite, les types riches du modèle (identifiants valués,
emplacement SAF) sont reconstruits par les mappeurs du module — la
frontière base/modèle reste explicite.

La base vit dans le **processus principal** uniquement (section 5.8) ;
ouverte paresseusement à la première requête, jamais dans `:crash`.

## Dépendances autorisées

`core:model` (les entités se mappent vers le modèle ; fakes de
`core:testing` en `testImplementation` si besoin).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `CodeIdeDatabase` — base Room v1 (`projectDao()`) ;
- `ProjectEntity` — ligne du registre (URI SAF en colonnes, pas de chemin
  `File`, ADR 0003) ;
- `ProjectDao` — observation ordonnée pour l'accueil, insertion
  (`SQLiteConstraintException` sur doublon), renommage/épingle/ouverture
  ciblés avec comptage, suppression ;
- `toModel()` / `toEntity()` — mappeurs exhaustifs du registre.

## Vérifications du module

```bash
./gradlew :core:database:check
```

Tests du DAO en Robolectric (base en mémoire, exécuteurs synchrones) :
ordre de l'accueil, unicité, comptages, aller-retour des mappeurs.
