# core/storage — Stockage — accès aux documents via SAF

> Statut étape 4 : **livré** (v0.5.0).

Implémentation du port `FileSystem` du domaine au-dessus du Storage
Access Framework (section 5.6 du prompt maître, ADR 0003) :
[SafFileSystem](src/main/kotlin/jo/codeide/core/storage/SafFileSystem.kt)
travaille en **URI de documents** (`content://…/tree/<arbre>/document/<id>`,
jamais de chemin `File`) sur `DocumentsContract` + `ContentResolver`.

Principes de la section 5.6, appliqués littéralement :
- **requêtes groupées** : le listing charge tous les enfants d'un dossier
  en une seule requête sur l'URI des enfants — jamais de boucle sur
  `DocumentFile` (une requête par appel) ;
- **jamais d'écrasement** : l'existence d'un homonyme (comparaison
  insensible à la casse) est vérifiée **avant** `createDocument`, et le
  nom **retourné** est contrôlé — si le fournisseur renomme malgré tout
  (course), le document créé est nettoyé et `AlreadyExists` remonte ;
- **jamais un crash** : chaque exception système est traduite en
  `AppError.Storage` typée (`SecurityException` → `PermissionLost`,
  `FileNotFoundException` → `NotFound`, indices « disque plein » →
  `NoSpace`, etc.) ;
- **permissions persistantes** isolées derrière le port interne
  `PersistableUriPermissions` (lecture + écriture en une prise,
  consultation de l'état pour `ProjectAccessState`) — testable sans
  simulateur système.

Les dossiers **refusés** par Android 11+ (racine, `Download`,
`Android/data`, `Android/obb`) sont détectés par
`ForbiddenFolders` (`core:domain`, pur), consommé par l'onboarding à
l'étape 5 pour expliquer clairement les refus.

Les tests Robolectric exercent le **vrai chemin du framework** : un
fournisseur de documents factice (`ShadowContentResolver` +
protocole d'appel vérifié sur le bytecode d'`android-all`) sert
`query`/`createDocument`/`deleteDocument`/flux d'octets. Les essais sur
le SAF système réel sont décrits dans `docs/TESTS_MANUELS.md`
(procédures S1 à S5).

## Dépendances autorisées

`core:model`, `core:domain` (fakes de `core:testing` en
`testImplementation`).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- la liaison Hilt `FileSystem` → `SafFileSystem` (le module ne expose
  rien d'autre : tout passe par l'interface du domaine).

## Vérifications du module

```bash
./gradlew :core:storage:check
```
