# ADR 0154 — Contrat d'affichage déclaratif des libellés, composants et groupes

- **Statut** : accepté (chantier générateur/Nouveau projet — lot L0) —
  **côté domaine implémenté en L1** (résolution des choix, `rendu`,
  groupes, « Avancé » dans `TemplateParameterEvaluation`) ; suppression des
  registres UI et rendu des `ChoixEvalue` en L5.
- **Amende** : ADR 0021 (le registre de présentation d'alors devient des
  métadonnées déclaratives ; le repli par traits reste).
- **Contexte** : l'audit L0 confirme que `RenduParametres.kt` porte cinq
  registres codés en dur (`libelleChoix`, `sousTitreChoix`, `iconeChoix`,
  `explicationChoix`, `aideSousSelection`) et que `libelleValeur`
  (EtapeConfigurationFragment) ne connaît que 7 valeurs JVM : une valeur
  hors registre s'affiche **brute** (`empty-activity`, `26`, `37`, `fr`),
  deux modèles réutilisant une même valeur partagent le même libellé, et un
  modèle futur ne peut pas apporter ses propres textes. Contrat cible
  (B-1) : tout vient du dictionnaire i18n du modèle.

## Décision

### 1. Convention de clés i18n par choix (pas de restructuration de `choices`)

`choices` reste une liste de valeurs brutes. Libellés, sous-titres,
descriptions et icônes par valeur vivent dans le dictionnaire du modèle :

```
param.<id>.label                       (existant)
param.<id>.help                        (existant)
param.<id>.choix.<valeur>.libelle      (nouveau — obligatoire fr/en)
param.<id>.choix.<valeur>.sousTitre    (optionnel)
param.<id>.choix.<valeur>.description  (optionnel — explication longue)
param.<id>.choix.<valeur>.icone        (optionnel — nom logique, cf. §4)
param.<id>.raison.inactif              (optionnel — cf. ADR 0153)
groupe.<groupe>.titre                  (nouveau — cf. §3)
```

- Le **moteur** résout ces clés (comme il résout déjà `label`/`help`) et
  expose par paramètre une liste de `ChoixEvalue(valeur, libelle, sousTitre,
  description, icone)` dans `TemplateParameterEvaluation` : l'UI ne lit
  jamais de dictionnaire (elle n'a pas accès aux manifestes — ADR 0021).
- **Repli** : valeur brute affichée uniquement si la clé manque — cas
  théorique pour un fournisseur tiers ; pour les modèles embarqués, un test
  (`ModelesEmbarquesTest` étendu) **échoue** si une clé manque en `fr` ou en
  `en`, et `valider-manifestes` (ADR 0157) l'interdit. Le repli ne doit
  donc jamais être observé sur le catalogue.
- Langue du contenu : `contentLanguage` reçoit le même traitement via des
  clés `commun.langue.fr`/`commun.langue.en` livrées par le moteur
  (variables automatiques), pour un récapitulatif « Français » / « Anglais »
  (B-4).

### 2. Indice de composant optionnel : `rendu`

Chaque paramètre peut préciser son composant :

```json
{ "id": "projectType", "type": "CHOICE", "rendu": "TUILES_SEGMENTEES", ... }
```

`rendu` ∈ `TUILES_SEGMENTEES`, `CARTES_RADIO`, `LISTE_DEROULANTE`,
`INTERRUPTEUR`, `CHAMP_TEXTE`. Absent → repli par traits existant
(CHOICE → liste, BOOLEAN → interrupteur, TEXT → champ). **Suppression** de
`COMPOSANTS_NOMMES` (map par id de paramètre) et du registre associé : le
manifeste dit quoi rendre, la vue exécute.

### 3. Groupes et zone « Avancé » (B-2)

- Champ optionnel `groupe` par paramètre (identifiant libre borné, par
  convention `plateforme`, `langageSdk`, `bibliotheques`, `qualite`…) ;
  titre via `groupe.<groupe>.titre` (fr/en). L'ordre de lecture reste l'ordre
  du manifeste, regroupé par première occurrence.
- Champ optionnel `avance: true` : le paramètre est rendu dans une zone
  **« Avancé » repliable** de l'étape (targetSdk, applicationId, JDK…),
  repliée par défaut, annoncée et accessible.
- Les bibliothèques (`BOOLEAN` groupés sous un groupe `bibliotheques`) sont
  rendues en **cartes sélectionnables** avec description courte, compteur
  « N sélectionnées » et mentions « sera ajouté automatiquement » (ADR
  0153).

### 4. Icônes logiques et `iconKey`

- `icone` / `iconKey` portent un **nom logique** (`globe`, `baseDeDonnees`,
  `navigation`, `android`, `printemps`…). `core:ui` gagne un registre
  `IconesModele` : drawables **vectoriels maison** (aucun logo officiel,
  règle de l'étape 9), rendus dans les cartes de modèle à la place de la
  pastille texte 40 dp/16 sp (débordante — constat B2.5), avec repli
  monogramme i18n si un nom manque.
- Les icônes créées s'ajoutent à `core:ui` (KDoc + aperçu
  `docs/mockups/ui/`) — jamais en local (règle 15).

### 5. Ce qui est supprimé côté UI (L5)

`libelleChoix`, `sousTitreChoix`, `iconeChoix`, `explicationChoix`,
`aideSousSelection`, `COMPOSANTS_NOMMES` (`RenduParametres.kt`) et
`libelleValeur` (`EtapeConfigurationFragment`). `RenduParametres` ne mappe
plus des **valeurs** : il rend des `ChoixEvalue` et `EtatParametre`. La
valeur lisible reste affichée partout où l'identifiant brut l'était
(minSdk/targetSdk → « API 26 · Android 8.0 » via `sousTitre`).

## Conséquences

- Un modèle fournit ses propres textes et son propre rendu sans toucher au
  code ; deux modèles réutilisant une valeur ne partagent plus rien.
- Dette de traduction : les dictionnaires s'enrichissent (clés de choix) —
  parité fr/en stricte testée.
- `checkUiTokens` : aucune nouvelle dette ; le cliquet descend en L5/L6 sur
  les 7 entrées `feature/newproject` de `config/ui-baseline.json`.
- Impact test : `WizardViewModelTest` (37) reste vert (contrat inchangé) ;
  nouveaux tests de résolution des choix côté moteur.
