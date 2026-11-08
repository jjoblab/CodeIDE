# ADR 0158 — Manifestes homogènes : groupes de fichiers exploités et dérivation de `appName`

- **Statut** : accepté (chantier générateur/Nouveau projet — lot L0 ;
  à implémenter en L1 pour la fonction dérivée, en L3/L4 pour les
  manifestes, en L5 pour l'écran Fichiers)
- **S'appuie sur** : ADR 0005, ADR 0075 (phase 2), ADR 0077 (phase 4),
  ADR 0154 (affichage déclaratif).
- **Contexte** : l'audit L0 confirme (A6) que le champ `group` des fichiers
  n'est renseigné que par `kotlin-jvm` et `java` (les 5 autres tombent sur
  le défaut `"core"`), qu'il est validé par le parseur puis recopié dans
  `PlannedFile.group` sans **aucun lecteur** ; et (A8) que `appName`
  (`android-app` « MonApp », `android-library` « MaBibliotheque ») est le
  seul paramètre d'identité à défaut fixe sans `defaultFrom`, alors que
  `packageName`/`applicationId` sont dérivés — l'utilisateur saisit deux
  noms qui ne se suivent pas.

## Décision

### 1. `group` obligatoire explicitement et **exploité** (pas supprimé)

- Les 7 manifestes renseignent `group` (`readme`, `gitignore`,
  `editorconfig`, …) sur **toutes** les entrées concernées ; le défaut
  `"core"` reste réservé aux fichiers d'usage général (sources, build,
  wrapper). `valider-manifestes` (ADR 0157) refuse un fichier catégorisable
  resté sur `core`.
- `TemplateFileGroup` est étendu au fil des lots (ex. `CI`, `DOCKER`,
  `ICONES`) pour porter les extras propres au type (plan A-3) — extension
  additive, rétro-compatible.
- L'écran **Fichiers** (B-6) lit les groupes réellement déclarés par le
  manifeste du modèle courant : il n'affiche que les interrupteurs qui ont
  un effet (un modèle sans `.editorconfig` n'offre pas l'option), et
  présente les extras du type. `TemplateOptions` reste le port des options
  communes ; les extras propres au modèle arrivent en champs optionnels du
  manifeste (L3/L4) et sont relayés par la requête de génération.
- Le récapitulatif et l'aperçu groupent les fichiers par `group`
  (lecteurs effectifs du champ).

### 2. Nouvelle fonction dérivée `appNameFromNom` (A-6)

- Enregistrement de la 7ᵉ fonction `defaultFrom` : **`appNameFromNom`**
  (nom du projet → nom d'application ; ex. « Mon super carnet » →
  « MonSuperCarnet ») — même mécanique que les six existantes
  (`TemplateDefaultFunctions.NOMS` + branche `appliquer()`, testée,
  parseur inchangé). La chaîne de dérivation devient
  **nom → `appName` → `packageName` → `applicationId`** : un seul nom
  saisi, tout suit (resynchronisable champ par champ comme aujourd'hui,
  figé à la première édition manuelle).
- `android-app` et `android-library` passent `appName` sur
  `defaultFrom: "appNameFromNom"` ; défaut littéral supprimé (le défaut de
  type TEXT est la chaîne vide le temps d'une saisie, comme les autres
  dérivés). `templateVersion` incrémenté (L4).
- Le wizard affiche la chaîne dérivée avec l'icône de resynchronisation
  existante (ADR 0021) — aucune nouvelle affordance.

### 3. Ce qui est écarté

- **Composition/`extends` de manifestes** (fusionner `kotlin-jvm` et `java`
  en un manifeste paramétré) : écarté en L0 — le gain est faible (9
  paramètres identiques mais des `files/` et des i18n distincts), le coût
  moteur (héritage, ordre de fusion) et le risque (contrat ADR 0005
  plus simple à tenir) lourds. La mutualisation **des fichiers** (ADR 0156)
  et des libellés communs suffit ; la question est réouverte si un 8ᵉ modèle
  duplique à nouveau la base JVM.
- Suppression du champ `group` du schéma : écartée (il sert désormais).

## Conséquences

- Étape Fichiers fidèle au modèle : plus d'options sans effet, plus de
  `.gitattributes` « toujours » (il devient un extra choisissable — plan
  A-3) ; récapitulatif structuré par groupes réels.
- Identité cohérente : plus de `MonApp`/`MaBibliotheque` figés ; les
  gabarits `{{appName|resourceName}}` continuent de produire des noms de
  ressources sûrs (ADR 0075).
- Tests : `TemplateDefaultFunctionsTest` ancre la 7ᵉ fonction ;
  `ModelesEmbarquesTest` vérifie la dérivation et sa resynchronisation ; le
  lint refuse les fichiers `core` mal catégorisés.
