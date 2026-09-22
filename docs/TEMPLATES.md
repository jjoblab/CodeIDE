# TEMPLATES.md — Format des modèles de projet

Ce document est le **contrat des concepteurs de modèles** de CodeIDE
(étape 8 — v0.9.0, ADR 0005 et 0018). Un modèle est un répertoire d'assets
interprété par le moteur (`core:domain`) : **aucune classe Kotlin ne code
un modèle en dur**, aucun `when(templateId)` n'existe dans le code.
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
reconnaître le type de projet à l'ouverture (étape 13).

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
- L'étape 9 ajoute `verify-templates.sh` : chaque combinaison d'options
  d'un modèle embarqué est générée **puis réellement compilée et testée**.
