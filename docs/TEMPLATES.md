# TEMPLATES.md — Format des modèles de projet

Ce document est le **contrat des concepteurs de modèles** de CodeIDE
(étapes 8-9 — v0.9.0/v0.10.0, ADR 0005, 0018 et 0019 ; outillage étendu au
lot L2 — v0.210.0, ADR 0157). Un modèle est un
répertoire d'assets interprété par le moteur (`core:domain`) : **aucune
classe Kotlin ne code un modèle en dur**, aucun `when(templateId)`
 n'existe dans le code.
Ajouter un modèle = ajouter des assets ; un plugin peut en apporter via
`ProjectTemplateProvider` (multibinding Hilt) sans recompiler le moteur.

## Arborescence d'un modèle

```
assets/templates/<id>/
  template.json      manifeste déclaratif (obligatoire, UTF-8)
  i18n/en.json       dictionnaire anglais (obligatoire — repli)
  i18n/fr.json      dictionnaire français (requis dès qu'un paramètre existe — lint L2)
  files/…            sources : *.tpl (texte) ou binaires copiés tels quels
```

- Un répertoire sans `template.json` est ignoré (outillage) ; un manifeste
  **présent mais invalide** fait échouer le chargement explicitement —
  jamais de catalogue amputé en silence.
- `assets/licenses/` (hors modèle) porte les textes officiels SPDX :
  `mit.txt`, `bsd-3-clause.txt` (avec `{{year}}`/`{{author}}`),
  `apache-2.0.txt`, `gpl-3.0.txt`.

## Manifeste `template.json`

```json
{
  "schemaVersion": 1,
  "id": "kotlin-jvm",
  "templateVersion": "1.0.0",
  "nameKey": "template.name",
  "descriptionKey": "template.description",
  "category": "jvm",
  "iconKey": "template.icon",
  "tags": ["Kotlin · JVM"],
  "parameters": [ … ],
  "computed": [ { "name": "estApplication", "expression": "…" } ],
  "constraints": [ { "when": "…", "severity": "ERREUR", "messageKey": "…" } ],
  "autoDefinit": [ { "when": "…", "definit": { "avecKsp": true }, "messageKey": "…" } ],
  "wizard": { "etapes": [ … ], "presets": [ … ] },
  "files": [ … ]
}
```

Champs contrôlés au chargement (le rendu ne peut plus échouer que sur des
valeurs) :

- `schemaVersion` : seule `1` est comprise.
- `id` : `^[a-z][a-z0-9-]*$`, **identique au nom du répertoire**.
- `templateVersion` : SemVer 2.0.0 (le modèle évolue indépendamment de
  l'application).
- `nameKey`/`descriptionKey`/`iconKey` : clés i18n (`^[a-zA-Z0-9_.-]+$`),
  résolues dans `i18n/<langue>.json` avec repli anglais.
- Bornes : 32 paramètres, 32 variables calculées, 32 contraintes,
  32 `autoDefinit`, 12 étapes de modèle, 12 préréglages, 256 fichiers,
  12 tags.

### Paramètres

| Champ | Rôle |
|---|---|
| `id` | identifiant Kotlin valide, unique, ne **masque pas** une variable du moteur |
| `type` | `TEXT`, `BOOLEAN` ou `CHOICE` |
| `labelKey`, `helpKey` | clés i18n affichées par le wizard |
| `choices`, `default` | pour `CHOICE` (défaut parmi les valeurs) ; `BOOLEAN` accepte `true`/`false` |
| `defaultFrom` | fonction dérivée **enregistrée** : `slug`, `parentPackage`, `packageFromNameAndAuthor`, `packageFromAppName` (`com.example.<appName>`, ADR 0075), `packageFromArtifactId` (`com.example.<artifactId>`), `applicationIdFromPackageName` (suit le paramètre `packageName`, repli `com.example.app` — chaîne `appName → packageName → applicationId`, ADR 0077), `appNameFromNom` (nom du projet → nom d'application, « Mon super carnet » → « MonSuperCarnet » — la chaîne de dérivation d'identité devient **nom → appName → packageName → applicationId**, ADR 0158 §2) — jamais de code. Une dérivée peut lire la valeur effective des paramètres déclarés AVANT elle (la source précède sa dérivée) : `appName`/`artifactId` avant `packageName`, `packageName` avant `groupId`/`applicationId` ; le modèle `gradle-plugin` réutilise `packageFromArtifactId` pour son `pluginId` (ADR 0076) |
| `validator` | `project-name`, `package-name`, `identifier`, `semver` ou `regex:<motif>` (correspondance complète) |
| `visibleWhen` | expression du mini-langage ; absent = toujours visible |
| `enabledWhen` | expression du mini-langage ; absent = toujours actif. **Faux = désactivé, pas masqué** : le champ reste à l'écran, grisé, avec sa raison localisée annoncée à TalkBack ; côté génération il se comporte comme un paramètre masqué (ADR 0153 §2) |
| `disabledReasonKey` | clé i18n (fr **et** en) de la raison de désactivation, lue quand `enabledWhen` est faux |
| `rendu` | indice de composant (ADR 0154 §2) : `TUILES_SEGMENTEES`, `CARTES_RADIO`, `LISTE_DEROULANTE`, `INTERRUPTEUR`, `CHAMP_TEXTE` ; absent = repli par traits du wizard |
| `groupe` | identifiant de groupe d'affichage (`^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`, ADR 0154 §3) ; titre via la clé `groupe.<groupe>.titre` |
| `avance` | `true` : le paramètre est rendu dans la zone « Avancé » repliable |
| `section` | `CONFIGURATION` ou `INFORMATION` (étapes du wizard) |
| `persist` | la valeur est-elle écrite dans `.codeide/project.json` ? |

Règles d'évaluation (section 12.2) : la valeur **dérivée** (`defaultFrom`)
suit ses sources tant que l'utilisateur ne modifie pas le champ à la main ;
un paramètre **masqué** (`visibleWhen` faux) **ou désactivé** (`enabledWhen`
faux) vaut sa valeur par défaut, n'est ni validé ni persisté (la saisie
éventuelle reste conservée dans l'état du wizard pour une réactivation —
ADR 0153 §2).

#### Libellés de choix déclaratifs (ADR 0154 §1)

`choices` reste une liste de valeurs brutes ; les textes par choix vivent
au dictionnaire i18n (fr **et** en obligatoires pour `libelle`) :

| Clé | Rôle |
|---|---|
| `param.<id>.choix.<valeur>.libelle` | libellé affiché (obligatoire — repli valeur brute, jamais observé sur le catalogue) |
| `param.<id>.choix.<valeur>.sousTitre` | sous-titre court (optionnel — ex. `targetSdk` « API 36 · Android 15 ») |
| `param.<id>.choix.<valeur>.description` | explication longue (optionnel) |
| `param.<id>.choix.<valeur>.icone` | nom logique d'icône (`IconesModele` de `core:ui`) (optionnel) |
| `groupe.<groupe>.titre` | titre du groupe d'affichage (optionnel — repli identifiant) |

### Bloc `wizard` — étapes de modèle et préréglages (ADR 0155 §1, schéma lu dès L3)

Bloc **optionnel** : sans lui, le parcours de modèle est une seule étape
« Configuration » regroupant tous les paramètres `CONFIGURATION`
(comportement historique, consommé par le calcul de parcours — L5a).

```json
"wizard": {
  "etapes": [
    { "id": "langage-build", "titreKey": "etape.langage-build.titre",
      "when": "buildSystem == \"gradle\"",
      "parametres": ["langage", "buildSystem"] }
  ],
  "presets": [
    { "id": "api-rest-jpa", "nomKey": "preset.api-rest.nom",
      "descriptionKey": "preset.api-rest.description",
      "valeurs": { "includeJpa": true, "includeValidation": true } }
  ]
}
```

- Étape : `id` `^[a-z][a-z0-9-]*$` unique, jamais un identifiant du socle
  (MODELE, IDENTITE, FICHIERS, RECAPITULATIF — réservés par l'application) ;
  `titreKey` obligatoire (fr **et** en), `iconeKey` optionnel, `when`
  optionnel (mini-langage — une étape dont `when` est faux **n'existe
  pas**) ; `parametres` non vide, chaque identifiant cité est un paramètre
  `CONFIGURATION` existant cité **au plus une fois** ; un paramètre non
  rangé rejoindra l'étape par défaut « Configuration » au calcul du
  parcours (L5a) — jamais d'écran vide.
- Préréglage : `id` unique, `nomKey` (fr **et** en), `descriptionKey`
  optionnel, `valeurs` visant des paramètres existants (`true`/`false`
  pour un `BOOLEAN`, valeur dans les `choices` pour un `CHOICE`, texte
  validé pour un `TEXT`) — même sémantique que `autoDefinit`, rien n'est
  figé.

### Fichiers

| Champ | Rôle |
|---|---|
| `path` | chemin relatif **templatisable** (`{{packageName|packagePath}}/Main.kt`) — contrôlé après substitution (garde de sécurité) |
| `source` | chemin dans le répertoire du modèle (`files/main.kt.tpl`) — jamais de `..`, jamais absolu ; **préfixe `commun:<x>`** (ADR 0156) : le fichier est lu dans `templates/_commun/<x>` (wrapper Gradle, `gradlew*`, `.gitattributes`, `.editorconfig` — une seule copie pour les 7 modèles, ≈ 428 Ko d'APK économisés ; le lint refuse une copie locale d'un fichier servi par la source partagée et toute source `commun:` absente) |
| `binary` | `true` : copie octet pour octet (icônes, polices…) |
| `when` | expression conditionnant l'inclusion |
| `group` | `core`, `readme`, `gitignore`, `editorconfig`, `license` — piloté par les options communes |

Un **même chemin de sortie statique** ne peut apparaître qu'une fois ; un
chemin **templatisé** peut être visé par plusieurs entrées à condition que
leurs `when` soient mutuellement exclusifs (variantes `langage`,
`avecJpa`…).

## Mini-langage d'expressions (ADR 0018, amendé L1)

Pour `visibleWhen`, `enabledWhen`, `when`, `computed`, `constraints`,
`autoDefinit` et `{{#if}}` :

- identifiants (paramètres, variables calculées, variables automatiques) ;
- littéraux : `"chaîne"` (échappements `\"`, `\\`, `\n`, `\t`), `true`,
  `false`, et **entiers** (`36`, 32 bits au maximum) ;
- opérateurs : `==`, `!=`, `&&`, `||`, `!`, parenthèses — précédence usuelle ;
- **comparaisons ordonnées** `>`, `>=`, `<`, `<=` (ADR 0018 amendé, lot L1) :
  deux **nombres** exigés, jamais de comparaison lexicographique de chaînes,
  jamais de coercition implicite — `"9" > "36"` est une erreur, pas un
  résultat ;
- **aucune évaluation de code**, aucune fonction appelable ;
- bornes : 512 caractères, 128 jetons, 16 de profondeur.

Typage des valeurs du contexte (uniforme, ADR 0018 amendé) : un paramètre
`BOOLEAN` est booléen ; une valeur **entier canonique** (sans zéro de tête —
« 026 » reste une chaîne, la substitution `{{param}}` ne mute jamais la
saisie) est un **nombre** ; tout le reste reste une chaîne. Une égalité avec
une valeur numérique s'écrit donc `jdkVersion == 21` (littéral nu), pas
`jdkVersion == "21"`.

Variables **automatiques** (ne peuvent pas être masquées par un paramètre) :
`projectName`, `description`, `author`, `year`, `slug`, `packagePath`,
`contentLanguage`, **`plateformeMajeure`** (majeure du `compileSdk` du
catalogue d'outils — 36 aujourd'hui, ADR 0153 §3) et les options communes
`includeReadme`, `includeGitignore`, `includeEditorconfig`, `license`.
Les contraintes et `autoDefinit` voient les paramètres et les variables
calculées — pas les options communes (le formulaire ne les connaît pas
encore à ce stade du wizard).

## Règles de projet et dépendances automatiques (ADR 0153)

Deux blocs optionnels du manifeste, évalués par le moteur sur la
combinaison complète (l'UI n'évalue **aucune** règle) :

```json
"constraints": [
  { "when": "targetSdk > plateformeMajeure", "severity": "ERREUR", "messageKey": "regle.target.sup.compileSdk" },
  { "when": "avecRoom && !avecHilt", "severity": "INFO", "messageKey": "regle.room.sans.hilt" }
],
"autoDefinit": [
  { "when": "avecRoom && !avecKsp", "definit": { "avecKsp": true }, "messageKey": "regle.ksp.auto" }
]
```

- `severity` ∈ `ERREUR` (bloquante : `isValid = false`, « Suivant » refusé,
  **planification refusée** — `CreateProjectUseCase` revalide toujours via
  le plan partagé, une UI contournée n'écrit rien), `AVERTISSEMENT`, `INFO`.
- `messageKey` : clé i18n du manifeste (fr **et** en) ; les messages
  déclenchés arrivent à l'UI déjà résolus (`MessageRegle`).
- `autoDefinit` : évaluée après les dérivations, une seule passe ; chaque
  cible de `definit` est un paramètre `BOOLEAN` déclaré plus tôt, jamais
  écrasé si l'utilisateur l'a figé à la main ; le `messageKey` optionnel
  annonce l'ajout automatique (« sera ajouté automatiquement »).
- Ordre d'évaluation : 1. valeurs, 2. dérivées, 3. calculées,
  4. `autoDefinit`, 5. `visibleWhen`, 6. `enabledWhen`, 7. validation,
  8. repli des masqués/désactivés, 9. `constraints`.

## Substitution (`.tpl`)

| Balise | Effet |
|---|---|
| `{{variable}}` | substitution brute |
| `{{variable\|filtre}}` | substitution filtrée |
| `{{#if expr}}…{{#else}}…{{/if}}` | conditionnel (imbrication ≤ 16) |
| `{{t:clé}}` | traduction i18n du modèle (repli anglais) |
| `\{{` | accolade ouvrante littérale |

Filtres : `kotlinString`, `javaString`, `xml`, `json`, `tomlString`, `md`
(échappements — la saisie de l'utilisateur ne casse **jamais** le code
généré) ; `slug`, `lower`, `upper`, `packagePath`, `resourceName`
(transformations — `resourceName` produit un nom de ressource Android
sûr : `« mon éclat & 2048 »` → `MonEclat2048`, ADR 0075).

**Échec explicite** (fichier + ligne, jamais de `{{…}}` résiduel) :
variable inconnue, filtre inconnu, clé i18n manquante, balise mal formée.
Les fins de ligne sont normalisées en LF (CRLF pour `.bat`).

## Options communes du moteur

Rendues à l'étape « Fichiers » du wizard pour tous les modèles :
`includeReadme`, `includeGitignore`, `includeEditorconfig`,
`includeGitattributes` (plan A-3 #11 — `.gitattributes` choisissable,
défaut `true`) ; les quatre pilotent des variables du même nom dans le
mini-langage (`when` des fichiers) ; `license` (`none`, `mit`,
`apache-2.0`, `gpl-3.0`, `bsd-3-clause` — le fichier officiel SPDX est
copié, MIT/BSD substituent année et auteur), `contentLanguage`
(`fr`|`en`, défaut = langue de l'application).

## Métadonnées `.codeide/project.json`

Chaque projet généré contient :

```json
{
  "schemaVersion": 1,
  "templateId": "kotlin-jvm",
  "templateVersion": "1.0.0",
  "generator": "CodeIDE 0.9.0",
  "parameters": { "…seulement les paramètres persist=true, visibles et actifs…" }
}
```

**Aucune donnée personnelle** (ni auteur, ni chemin local). Sert à
reconnaître le type de projet à l'ouverture (étape 18).

## Garde de sécurité des chemins (après substitution)

Rejetés : chemins vides, absolus (`/`, `C:`), antislashs, segments `.`/`..`,
segments vides (`a//b`), caractères de contrôle, espaces de bord, point
final, noms réservés Windows (`CON`, `COM1`…), chemins > 240 caractères,
segments > 120, doublons (insensible à la casse). Jamais d'écrasement d'un
fichier existant.

### Renommage depuis l'aperçu (phase 4, ADR 0077)

L'utilisateur peut renommer un nœud de l'arborescence prévue **avant**
création : la demande entre dans la requête (clé = chemin original du
nœud — fichier exact ou préfixe de dossier —, valeur = **nouveau nom du
segment**, jamais un chemin complet), le moteur substitue chaque segment
concerné puis rejoue **toutes** les gardes ci-dessus (un renommage qui
créerait un doublon ou un chemin interdit échoue explicitement — jamais
de `{{…}}` résiduel, jamais de silence). Les clés obsolètes (arborescence
changée entre-temps) sont ignorées, comme les paramètres périmés ; le
dossier `.codeide/` est intouchable. La licence suit les renommages comme
n'importe quel fichier. Un concepteur de modèle n'a **rien à déclarer** :
le renommage s'applique à l'arborescence rendue, en aval des `when` et
des chemins templatisés.

## Extension — `ProjectTemplateProvider`

Les fournisseurs s'enregistrent en **multibinding Hilt** (`@IntoSet`).
L'embarqué (`EmbeddedTemplatesProvider`) lit `assets/templates/` via le
port `TemplateAssetsSource` (implémenté dans `app` sur l'AssetManager).
Un manifeste corrompu d'un lot échoue explicitement ; deux modèles de même
identifiant entre fournisseurs aussi.

## Tester un modèle

- `./gradlew :core:domain:test` : le fixture `templates/fixture` (répertoire
  `src/test/resources`) éprouve tout le moteur — conditions, i18n, filtres,
  entrées hostiles, sécurité des chemins, déterminisme.
- `tools/generateur` (lot L2, ADR 0157) :
  - `valider-manifestes` — **lint** du catalogue : clés i18n référencées en
    `fr` **et** `en` (jamais d'asymétrie sur les clés optionnelles),
    expressions évaluables, sources existantes, choix `CHOICE` tous
    atteignables ; **zéro écart attendu à tout instant** — c'est la porte
    d'entrée des lots de modèles ;
  - `matrice` — dérivation des combinaisons (voir § suivant) ;
  - `InvariantsEffetModelesTest` — structure sans compilation (dry-run) de
    toutes les combinaisons des matrices réelles + **invariants d'effet** ;
  - `determinisme` — deux générations comparées octet pour octet.
- Les modèles embarqués sont aussi éprouvés par `ModelesEmbarquesTest`
  (module `app`) : combinaisons structurelles, options communes,
  déterminisme, entrées hostiles, écriture complète sur `FakeFileSystem`.
- `scripts/verify-templates.sh` : chaque combinaison de la matrice
  dérivée **puis réellement compilée, testée, exécutée et publiée** avec les
  vrais outils.

## La matrice dérivée des manifestes (lot L2, ADR 0157)

Les combinaisons de couverture ne sont plus écrites à la main :
`generateur matrice` les **dérive des manifestes** — ajouter une option à un
modèle signale immédiatement le trou de couverture.

- **Paramètres énumérables** : `BOOLEAN` (false/true) et `CHOICE` (leurs
  choix) ; les `TEXT` ne multiplient pas les cas (dérivations exercées sur
  le nom de projet simulé `Projet Verification`).
- **Masquages respectés** : un paramètre masqué (`visibleWhen` faux) ou
  désactivé (`enabledWhen` faux) ne multiplie pas les cas — deux candidats
  ne différant que par lui sont équivalents (même signature d'évaluation).
- **Règles `ERREUR`** : les candidats qui les déclenchent (ou invalident un
  paramètre visible) sont **exclus et signalés** (JSONL).
- **Stratégies** : `exhaustif` (espace ≤ seuil 64), `paires` (toutes les
  paires de valeurs atteignables — glouton, propriété testée), `bornes`
  (chaque valeur au moins une fois, défauts d'abord).
- **Cas nommés de régression** (`scripts/verify-templates-cas-nomes.json`,
  écrits à la main) : noms et descriptions hostiles, renommages de l'aperçu,
  couverture des langues et des licences, dépendances toutes cochées — ils
  s'ajoutent à la matrice dérivée avant le filtre `CODEIDE_COMBINAISONS`.
- Le protocole de sortie de l'outil est versionné (`PROTOCOLE=2`) : la
  première ligne de toute exécution ; les rapports nouveaux (lint, matrice,
  déterminisme) sortent en JSONL.

### Invariants d'effet (C-3, table tenue par `InvariantsEffetModelesTest`)

Pour chaque combinaison générée en dry-run, l'effet de chaque option est
contrôlé — **aucune option sans effet observable** :

| Modèle | Invariant |
|---|---|
| `android-app` | `.kt` présent ssi `language=kotlin` (`.java` en miroir) ; `MainActivity` ssi `projectType != no-activity` ; classe `*Application` ssi `includeHilt` ; `BddLocale` ssi `includeRoom` ; `gradlew` ssi `includeWrapper` ; `src/test` ssi `includeTests` ; Retrofit/Navigation câblées dans `app/build.gradle.kts` ssi cochées |
| `spring-boot` | `build.gradle.kts` ssi `buildSystem=gradle`, `pom.xml` ssi `maven` (jamais les deux) ; `src/main/kotlin` ssi `langage=kotlin` (`.java` en miroir) ; JDK demandé inscrit dans la toolchain (`jvmToolchain`) ou le pom (`java.version`) ; profils `application-dev.yml`/`application-prod.yml` toujours générés ; entité `EntiteSalutation` + starter `data-jpa` + `datasource` H2 ssi `includeJpa` ; `SecuriteConfiguration` ssi `includeSecurity` ; exposition `management:` ssi `includeActuator` ; DTO `DemandeSalutation` ssi `includeValidation` ; `gradlew` ssi gradle **et** `includeWrapper` (masqué en Maven) ; `src/test` ssi `includeTests` |
| `kotlin-multiplatform` | plugin serialization / coroutines / datetime dans `build.gradle.kts` ssi cochés |
| `kotlin-jvm`, `java` | `build.gradle.kts` ssi `gradle-kts`, `pom.xml` ssi `maven` (jamais les deux) ; `gradlew` ssi gradle **et** `includeWrapper` ; `src/test` ssi `includeTests` |
| `android-library` | `gradlew` ssi `includeWrapper` ; `src/test` ssi `includeTests` ; `minSdk = <valeur>` écrit tel quel |
| `gradle-plugin` | `gradlew` ssi `includeWrapper` ; `src/test` ssi `includeTests` |
| tous | `.gitattributes` présent ssi l'option commune `includeGitattributes` (défaut vrai — cas nommé `kt-app-sans-attributs` pour le faux) |

Invariants **attendus du lot L4** (déclarés ici, activés dans
`InvariantsEffetModelesTest` quand les modèles les satisfont — le test
actuel les documente sans les exiger) :

| Modèle | Invariant attendu | Lot |
|---|---|---|
| `android-app` | permission `INTERNET` présente ssi `includeRetrofit` (câblage réel : service d'exemple, ADR du plan §A1.1) | L4 |
| `android-app` | `NavHostFragment` présent ssi `includeNavigation` | L4 |

## Si l'outillage manque

Les outils requis par les modèles (JDK 17/21, Maven, SDK Android, Gradle)
sont annoncés en tête d'exécution de `verify-templates.sh` avec la conduite
à tenir ; les combinaisons qui les exigent échouent explicitement et font
échouer le script — **jamais de contournement, jamais de vérification
désactivée** (ADR 0157 §2). Les familles vérifiables restent prouvables par
tranches (`CODEIDE_COMBINAISONS`) ; une impossibilité (ex. réseau bloqué
vers Maven Central) se **signale dans le rapport**, elle ne se contourne
pas.

## Les modèles embarqués (étapes 9, phases 2-4 du roadmap)

Sept modèles vivent sous `assets/templates/`, tous vérifiés par build réel
via `scripts/verify-templates.sh` — la matrice est **dérivée des
manifestes** (`generateur matrice`, ADR 0157) complétée des cas nommés de
régression :

| Modèle | Ce qu'il génère | Détails |
|---|---|---|
| `kotlin-jvm` | projet JVM Kotlin (app/biblio, Gradle/Maven/none, JDK 17/21) | étape 9 — Greeter + Main + GreeterTest, `-Werror` |
| `java` | projet JVM Java (mêmes axes) | étape 9 — `-Xlint:all -Werror` |
| `android-app` | application Android (AGP 9.4.1, Material 3) | phases 2-4 — `projectType` (`empty-activity`/`no-activity`/`basic-activity` tiroir Material), `language` (`kotlin`/`java`), `minSdk` 24-34, `targetSdk` 34-37, `applicationId` dérivé, package `com.example.<appName>`, ViewBinding, 5 dépendances au choix (Coroutines, Retrofit, Navigation, Room, Hilt) |
| `spring-boot` | service REST Boot 4.1.1 — Kotlin **ou** Java, Gradle **ou** Maven, JDK 17/21 (L3, v2.0.0) | L3 — port `DepotSalutations` (dépôt mémoire ssi !JPA, sinon Spring Data + entité + H2), profils `dev`/`prod`, SecurityFilterChain explicite (health ouvert ssi Actuator), DTO validé ssi Validation, tests de contexte et d'effets générés, 3 étapes wizard + préréglage « API REST avec persistance » |
| `kotlin-multiplatform` | bibliothèque KMP (Kotlin 2.2.21) | phases 2+4 — `expect`/`actual` câblé, `jvmTest`, tâche `run`, 3 dépendances au choix (serialization, coroutines, datetime) |
| `android-library` | bibliothèque Android (.aar) | phases 3+4 — module `:library`, `consumer-rules.pro`, package `com.example.<nom>`, `minSdk` 24-34 |
| `gradle-plugin` | plugin Gradle en Kotlin | phase 3 — `kotlin-dsl` (compilateur **embarqué** dans la distribution : Gradle 9.7.1 livre Kotlin 2.4.0, incompatible avec un KGP externe 2.2.21 sur `gradleApi()`), `pluginId`/`packageName` dérivés de l'`artifactId`, testkit ProjectBuilder (ADR 0076) |

### Dépendances au choix (phase 4, ADR 0077)

Chaque dépendance est un interrupteur `BOOLEAN` (défaut faux) : le
catalogue `libs.versions.toml` du projet généré n'embarque **que** les
versions des dépendances cochées, le build ne déclare que les plugins
requis (KSP n'apparaît que si Room ou Hilt est coché). Versions
vérifiées par build réel :

| Dépendance | Version | Modèle |
|---|---|---|
| `kotlinx-coroutines` (android/core) | 1.11.0 | `android-app`, `kotlin-multiplatform` |
| Retrofit + converter-gson | 3.0.0 | `android-app` |
| AndroidX Navigation | 2.10.2 | `android-app` (plancher minSdk 24) |
| Room (runtime/ktx/compiler) | 2.8.5 | `android-app` — via KSP |
| Hilt (android/compiler) | 2.59.2 | `android-app` — via KSP |
| **KSP** | **2.3.12** | `android-app` — accepte le Kotlin intégré d'AGP 9.4.1 (les 2.2.x le refusent ; un KGP externe est inapplicable, `BaseExtension` n'existe plus) |
| kotlinx-serialization-json + plugin | 1.11.0 + Kotlin 2.2.21 | `kotlin-multiplatform` |
| kotlinx-datetime | 0.8.0 | `kotlin-multiplatform` (`Instant` ponté vers kotlin.time, `@OptIn` dans l'exemple) |
| starters Spring Boot + H2 | gérés par la BOM 4.1.1 | `spring-boot` (alias de catalogue **sans version**) |

`kotlin-jvm` et `java` partagent la même logique de paramètres (section 11
du prompt maître) : `projectType` (application/bibliothèque), `buildSystem`
(`gradle-kts`/`maven`/`none`), `jdkVersion` (17 ou 21 — **seules les LTS
entièrement validées** sont proposées, voir ADR 0019 : Kotlin 2.2.21 ne
supporte pas encore la cible JVM 25), `includeTests` (JUnit 5),
`includeWrapper` (Gradle Wrapper avec `distributionSha256Sum`), puis
`packageName`/`groupId`/`artifactId`/`version` (section Informations).
L'exemple généré — `Greeter` (logique testable) + `Main` (point d'entrée
fin) + `GreeterTest` — compile et passe ses tests **sans aucune
modification, avec zéro avertissement** (Kotlin `-Werror` via
`allWarningsAsErrors`, Java `-Xlint:all -Werror`, Maven équivalent,
JUnit 5).

## Versions figées des projets générés

Chaque version a été vérifiée sur son dépôt officiel (2026-09-22 pour le
socle JVM, 2026-09-30 pour la chaîne Android et Gradle 9 — ADR 0075/0076),
puis **validée par build réel** via `verify-templates.sh` :

| Élément | Version | Source |
|---|---|---|
| Distribution Gradle (wrapper) | 9.7.1 (+ SHA-256) | services.gradle.org |
| Kotlin (plugin, stdlib, `kotlin-test`) | 2.2.21 | repo.maven.apache.org |
| Kotlin du plugin `kotlin-dsl` | **embarqué** dans Gradle 9.7.1 (2.4.0) | distribution Gradle |
| JUnit Jupiter (BOM) | 5.14.4 | repo.maven.apache.org |
| junit-platform-launcher | 1.14.4 | repo.maven.apache.org |
| AGP (application/bibliothèque Android) | 9.4.1 | dl.google.com/dl/android/maven2 |
| androidx core-ktx | 1.19.0 | dl.google.com/dl/android/maven2 |
| androidx appcompat | 1.8.0 | dl.google.com/dl/android/maven2 |
| material | 1.14.0 | dl.google.com/dl/android/maven2 |
| androidx drawerlayout | 1.2.0 | dl.google.com/dl/android/maven2 |
| JUnit 4 (tests Android) | 4.13.2 | repo.maven.apache.org |
| Spring Boot | 4.1.1 | repo.maven.apache.org |
| foojay-resolver-convention | 1.0.0 | plugins.gradle.org |
| Maven (vérification) | 3.9.16 | dlcdn.apache.org |
| maven-surefire-plugin | 3.6.0 | repo.maven.apache.org |
| maven-jar-plugin | 3.5.1 | repo.maven.apache.org |
| maven-source-plugin | 3.4.0 | repo.maven.apache.org |
| maven-javadoc-plugin | 3.12.0 | repo.maven.apache.org |
| maven-compiler-plugin | 3.16.0 | repo.maven.apache.org |
| exec-maven-plugin | 3.6.4 | repo.maven.apache.org |

Jamais de `+`, `latest` ni `RELEASE` : les versions vivent dans les
`.tpl` (`libs.versions.toml.tpl`, `pom.xml.tpl`, `wrapper.properties.tpl`)
et dans ce tableau. `docs/TEMPLATES.md` est la référence de traçabilité.

## Mettre à jour les versions d'un modèle, puis revalider

1. Vérifier la nouvelle version sur le dépôt officiel (tableau ci-dessus) ;
   pour le wrapper, récupérer aussi le SHA-256
   (`curl -sL https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`).
2. Mettre à jour les `.tpl` concernés **et** le tableau ci-dessus (les deux
   vivent ensemble — un modèle est figé, une montée de version est un
   changement délibéré du modèle, qui incrémente son `templateVersion`).
3. `./gradlew :app:testDebugUnitTest --tests 'jo.codeide.templates.*'` :
   les tests de génération doivent rester verts.
4. `scripts/verify-templates.sh` **entièrement vert** : c'est lui qui prouve
   que la nouvelle chaîne compile, teste et exécute les projets générés.
   Si le réseau bloque le téléchargement des dépendances de build, le
   **signaler** — jamais contourner (section 11).
5. Pour proposer un nouveau `jdkVersion` (ex. 25) : l'ajouter au `choices`
   du manifeste des deux modèles, ajouter une combinaison de couverture au
   script, et ne le proposer **que si** le script reste vert (ADR 0019).

## Ajouter un modèle embarqué

1. Créer `app/src/main/assets/templates/<id>/` : `template.json`,
   `i18n/en.json` (requis) + `i18n/fr.json`, `files/…` (`.tpl` textuels ou
   binaires). Le contrat complet : ce document.
2. Un **même chemin de sortie statique** ne peut apparaître qu'une fois
   dans le manifeste (contrôle au chargement). Un chemin **templatisé**
   (`…/java/{{packageName|packagePath}}/MainActivity.kt`) peut être visé
   par plusieurs entrées **à condition que leurs `when` soient mutuellement
   exclusifs** (variantes `projectType`/`language`, ADR 0076) ; les
   variantes d'un fichier au chemin statique (`build.gradle.kts`,
   `.gitignore`) restent des blocs `{{#if}}` **à l'intérieur** du `.tpl`.
3. Les valeurs i18n insérées dans des littéraux de code (messages d'exemple)
   ne doivent contenir ni `"` ni `\` ni `$` : elles sont substituées brutes.
4. Ajouter les tests de génération (combinaisons structurelles + golden)
   dans `ModelesEmbarquesTest`, et des combinaisons de couverture à
   `scripts/verify-templates.sh` si le modèle apporte un nouveau système de
   build.
5. Mettre à jour `Module.md`/`README.md` du module `app` si le périmètre
   change ; incrémenter le `templateVersion` à chaque évolution des assets.
