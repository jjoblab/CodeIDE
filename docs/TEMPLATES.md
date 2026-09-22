# TEMPLATES.md — Format des modèles de projet

Ce document est le **contrat des concepteurs de modèles** de CodeIDE
(étapes 8-9 — v0.9.0/v0.10.0, ADR 0005, 0018 et 0019). Un modèle est un
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
  i18n/fr.json      dictionnaire français (optionnel)
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
- Bornes : 32 paramètres, 32 variables calculées, 256 fichiers, 12 tags.

### Paramètres

| Champ | Rôle |
|---|---|
| `id` | identifiant Kotlin valide, unique, ne **masque pas** une variable du moteur |
| `type` | `TEXT`, `BOOLEAN` ou `CHOICE` |
| `labelKey`, `helpKey` | clés i18n affichées par le wizard |
| `choices`, `default` | pour `CHOICE` (défaut parmi les valeurs) ; `BOOLEAN` accepte `true`/`false` |
| `defaultFrom` | fonction dérivée **enregistrée** : `slug`, `parentPackage`, `packageFromNameAndAuthor` — jamais de code |
| `validator` | `project-name`, `package-name`, `identifier`, `semver` ou `regex:<motif>` (correspondance complète) |
| `visibleWhen` | expression du mini-langage ; absent = toujours visible |
| `section` | `CONFIGURATION` ou `INFORMATION` (étapes du wizard) |
| `persist` | la valeur est-elle écrite dans `.codeide/project.json` ? |

Règles d'évaluation (section 12.2) : la valeur **dérivée** (`defaultFrom`)
suit ses sources tant que l'utilisateur ne modifie pas le champ à la main ;
un paramètre **masqué** (`visibleWhen` faux) vaut sa valeur par défaut,
n'est ni validé ni persisté.

### Fichiers

| Champ | Rôle |
|---|---|
| `path` | chemin relatif **templatisable** (`{{packageName|packagePath}}/Main.kt`) — contrôlé après substitution (garde de sécurité) |
| `source` | chemin dans le répertoire du modèle (`files/main.kt.tpl`) — jamais de `..`, jamais absolu |
| `binary` | `true` : copie octet pour octet (icônes, polices…) |
| `when` | expression conditionnant l'inclusion |
| `group` | `core`, `readme`, `gitignore`, `editorconfig`, `license` — piloté par les options communes |

## Mini-langage d'expressions (ADR 0018)

Pour `visibleWhen`, `when`, `computed` et `{{#if}}` :

- identifiants (paramètres, variables calculées, variables automatiques) ;
- littéraux : `"chaîne"` (échappements `\"`, `\\`, `\n`, `\t`), `true`, `false` ;
- opérateurs : `==`, `!=`, `&&`, `||`, `!`, parenthèses — précédence usuelle ;
- **aucune évaluation de code**, aucune fonction appelable ;
- bornes : 512 caractères, 128 jetons, 16 de profondeur.

Variables **automatiques** (ne peuvent pas être masquées par un paramètre) :
`projectName`, `description`, `author`, `year`, `slug`, `packagePath`,
`contentLanguage` et les options communes `includeReadme`,
`includeGitignore`, `includeEditorconfig`, `license`.

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
généré) ; `slug`, `lower`, `upper`, `packagePath` (transformations).

**Échec explicite** (fichier + ligne, jamais de `{{…}}` résiduel) :
variable inconnue, filtre inconnu, clé i18n manquante, balise mal formée.
Les fins de ligne sont normalisées en LF (CRLF pour `.bat`).

## Options communes du moteur

Rendues à l'étape « Fichiers » du wizard pour tous les modèles :
`includeReadme`, `includeGitignore`, `includeEditorconfig`,
`license` (`none`, `mit`, `apache-2.0`, `gpl-3.0`, `bsd-3-clause` —
le fichier officiel SPDX est copié, MIT/BSD substituent année et auteur),
`contentLanguage` (`fr`|`en`, défaut = langue de l'application).

## Métadonnées `.codeide/project.json`

Chaque projet généré contient :

```json
{
  "schemaVersion": 1,
  "templateId": "kotlin-jvm",
  "templateVersion": "1.0.0",
  "generator": "CodeIDE 0.9.0",
  "parameters": { "…seulement les paramètres persist=true et visibles…" }
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
- Les modèles embarqués (`kotlin-jvm`, `java`) sont éprouvés
  **exhaustivement** par `ModelesEmbarquesTest` (module `app`) : toutes les
  combinaisons structurelles (langage × type × build × JDK × tests ×
  wrapper × langue), options communes, déterminisme, entrées hostiles,
  écriture complète sur `FakeFileSystem`.
- L'étape 9 ajoute `scripts/verify-templates.sh` : chaque combinaison
  d'options d'un modèle embarqué est générée **puis réellement compilée,
  testée, exécutée et publiée** avec les vrais outils.

## Les modèles embarqués (étape 9)

Deux modèles, `kotlin-jvm` et `java`, partagent la même logique de
paramètres (section 11 du prompt maître) : `projectType`
(application/bibliothèque), `buildSystem` (`gradle-kts`/`maven`/`none`),
`jdkVersion` (17 ou 21 — **seules les LTS entièrement validées** sont
proposées, voir ADR 0019 : Kotlin 2.2.21 ne supporte pas encore la cible
JVM 25), `includeTests` (JUnit 5), `includeWrapper` (Gradle Wrapper avec
`distributionSha256Sum`), puis `packageName`/`groupId`/`artifactId`/`version`
(section Informations). L'exemple généré — `Greeter` (logique testable) +
`Main` (point d'entrée fin) + `GreeterTest` — compile et passe ses tests
**sans aucune modification, avec zéro avertissement** (Kotlin `-Werror`
via `allWarningsAsErrors`, Java `-Xlint:all -Werror`, Maven équivalent,
JUnit 5).

## Versions figées des projets générés

Chaque version a été vérifiée sur son dépôt officiel le 2026-09-22, puis
**validée par build réel** via `verify-templates.sh` :

| Élément | Version | Source |
|---|---|---|
| Distribution Gradle (wrapper) | 9.7.1 (+ SHA-256) | services.gradle.org |
| Kotlin (plugin, stdlib, `kotlin-test`) | 2.2.21 | repo.maven.apache.org |
| JUnit Jupiter (BOM) | 5.14.4 | repo.maven.apache.org |
| junit-platform-launcher | 1.14.4 | repo.maven.apache.org |
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
2. Un **même chemin de sortie** ne peut apparaître qu'une fois dans le
   manifeste (contrôle au chargement) : les variantes d'un même fichier
   (`build.gradle.kts` app/bibliotheque, `.gitignore` par build) passent par
   des blocs `{{#if}}` **à l'intérieur** du `.tpl`.
3. Les valeurs i18n insérées dans des littéraux de code (messages d'exemple)
   ne doivent contenir ni `"` ni `\` ni `$` : elles sont substituées brutes.
4. Ajouter les tests de génération (combinaisons structurelles + golden)
   dans `ModelesEmbarquesTest`, et des combinaisons de couverture à
   `scripts/verify-templates.sh` si le modèle apporte un nouveau système de
   build.
5. Mettre à jour `Module.md`/`README.md` du module `app` si le périmètre
   change ; incrémenter le `templateVersion` à chaque évolution des assets.
