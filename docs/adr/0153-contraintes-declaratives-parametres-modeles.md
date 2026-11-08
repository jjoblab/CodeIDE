# ADR 0153 — Contraintes déclaratives entre paramètres des modèles

- **Statut** : accepté (chantier « Refonte du générateur de projets et des
  écrans Nouveau projet » — lot L0 ; à implémenter en L1)
- **S'appuie sur** : ADR 0005 (modèles déclaratifs), ADR 0017 (plan figé),
  ADR 0018 (mini-langage borné), ADR 0089 (catalogue d'outils, compileSdk
  verrouillé), ADR 0031 (`.codeide/project.json`).
- **Contexte** : le seul mécanisme de relation entre paramètres est
  `visibleWhen` (masquage). Les constats de l'audit L0 montrent les limites :
  `includeCoroutines` reste proposé en Java, `androidx-room-ktx` est ajouté
  même en Java, `includeNavigation` reste proposé avec `no-activity`,
  `targetSdk` (34–37, défaut 37) peut dépasser le `compileSdk` figé à 36, et
  aucune combinaison pauvre ne prévient l'utilisateur. Règle 1 du dépôt :
  ces règles de compatibilité vivent dans `core:domain`, jamais dans un
  Fragment ni dans `RenduParametres`.

## Décision

### 1. `schemaVersion` reste `1` — champs nouveaux OPTIONNELS

Le schéma du manifeste évolue **par champs optionnels à valeur par défaut**
(nulle vide / booléen faux / liste vide), dans le même `schemaVersion: 1`.
Le parseur (DTO `ignoreUnknownKeys=false`) est étendu pour les accepter ;
**tous les manifestes des 7 modèles existants restent valides sans
modification** et la reconnaissance des projets déjà créés (ADR 0031) n'est
pas touchée. Un passage en `schemaVersion: 2` avec parseur double est écarté :
double voie de maintenance pour aucun gain (aucun champ retiré, aucun
champ existant changeant de sémantique).

### 2. État « désactivé avec raison » : `enabledWhen` + `disabledReasonKey`

Un paramètre (ou un choix, cf. ADR 0154) peut déclarer :

```json
{ "id": "includeNavigation", "type": "BOOLEAN", "section": "CONFIGURATION",
  "labelKey": "param.includeNavigation.label", "helpKey": "param.includeNavigation.help",
  "default": false,
  "enabledWhen": "projectType != \"no-activity\"",
  "disabledReasonKey": "param.includeNavigation.raison.inactif" }
```

- **Désactivé ≠ masqué** : le champ reste à l'écran (affordance découverte),
  grisé, avec sa raison localisée annoncée à TalkBack ; `visibleWhen` garde
  son rôle de masquage pur.
- Évaluation : `enabledWhen` absent = toujours actif. Un paramètre
  **désactivé** se comporte côté génération exactement comme un paramètre
  masqué (passe 5 du moteur) : il retombe sur sa valeur par défaut, n'est ni
  validé ni persisté. La saisie éventuelle de l'utilisateur est **conservée**
  dans l'état du wizard (réutilisée si le champ se réactive) mais exclue de
  la requête de génération tant que le champ est désactivé.
- `disabledReasonKey` est une clé i18n du modèle (fr **et** en, vérifiée par
  `valider-manifestes` — ADR 0157 — et par test sur les modèles embarqués).

### 3. Règles de projet : `constraints` au niveau du manifeste

```json
"constraints": [
  { "when": "targetSdk > plateformeMajeure", "severity": "ERREUR",
    "messageKey": "regle.target.sup.compileSdk" },
  { "when": "avecRoom && !avecHilt", "severity": "INFO",
    "messageKey": "regle.room.sans.hilt" },
  { "when": "avecRetrofit && !avecCoroutines && langage == \"kotlin\"", "severity": "INFO",
    "messageKey": "regle.retrofit.sans.coroutines" }
]
```

- Évaluées par le **mini-langage existant** (ADR 0018 : mêmes bornes
  512/128/16, aucune évaluation de code) — aucune grammaire nouvelle.
- `severity` ∈ `ERREUR` (bloquante : `isValid = false`, « Suivant » refusé,
  création refusée) / `AVERTISSEMENT` / `INFO` (messages globaux affichés au
  bon endroit de l'UI, récapitulatif compris). Messages i18n fr/en.
- Nouvelle **variable automatique** `plateformeMajeure` : majeur du
  `compileSdk` du catalogue d'outils (ADR 0089 — 36 aujourd'hui), injectée
  par `TemplateProjectPlanner` exactement comme `year` l'est par l'horloge.
  Source de vérité unique : le catalogue ; `AlignementCatalogueTemplatesTest`
  étendu pour verrouiller la valeur exposée au moteur.
- La règle `targetSdk > plateformeMajeure` est **ERREUR** ; en parallèle les
  manifestes Android restreignent leurs `choices` aux valeurs réellement
  couvertes (L4 : `targetSdk` 34–36, défaut 36, `templateVersion` incrémenté,
  clé `readme.sdk` corrigée) — la règle est le filet, pas l'ajustement
  principal (réponse §9-Q6).

### 4. Dépendances implicites : `autoDefinit`

```json
"autoDefinit": [
  { "when": "avecRoom && !avecKsp", "definit": { "avecKsp": true },
    "messageKey": "regle.ksp.auto" }
]
```

- Évaluées après les passes de dérivation, **avant** les règles de projet ;
  un paramètre `definit` que l'utilisateur a **figé à la main** n'est jamais
  écrasé (le champ `modifiesManuellement` existant prime) et un message
  `INFO` l'annonce si le `messageKey` est fourni. Le wizard affiche « sera
  ajouté automatiquement » sur les bibliothèques concernées (compteur du
  B-2).
- Borné : `definit` ne peut viser qu'un paramètre BOOLEAN déclaré plus tôt
  (parseur), aucune chaîne de propagation indirecte (une seule passe).

### 5. Sortie unique : `EvaluateTemplateFormUseCase` enrichi

`TemplateFormEvaluation` (core:model) gagne :
- par paramètre : un `EtatParametre` (`VISIBLE` / `DESACTIVE(raison)`) à côté
  du `visible` existant ;
- des **messages globaux** (`MessageRegle(severite, texte résolu)`) ;
- `isValid` tient compte des règles `ERREUR`.

`CreateProjectUseCase` **revalide toujours** la combinaison complète avant
planification (règle existante, désormais avec les règles de projet) : une
combinaison contenant une règle `ERREUR` échoue avec une erreur typée, même
si l'UI a été contournée. Le plan figé (ADR 0017) et la garde de chemins ne
changent pas ; la génération reste déterministe.

## Conséquences

- Rétro-compatible : manifestes existants valides sans retouche ; aucun
  changement de `.codeide/project.json`.
- L'UI n'évalue AUCUNE règle : elle rend `EtatParametre` et les messages
  globaux (règle 1). `RenduParametres` perd toute logique de compatibilité.
- Coût moteur : une passe d'évaluation supplémentaire + DTO étendu ; tests
  core:domain (parseur, évaluation, bornes, autoDefinit) et app (chaque
  manifeste embarqué : clés présentes, règles citables).
- Le tableau de cohérence complet par modèle (L0) devient **déclarable** :
  chaque ligne du tableau correspond à un `enabledWhen`, une `constraint`
  ou un `autoDefinit` vérifiable par `valider-manifestes`.
