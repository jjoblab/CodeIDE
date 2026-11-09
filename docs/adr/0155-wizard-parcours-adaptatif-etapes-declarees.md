# ADR 0155 — Parcours de création adaptatif : étapes déclarées par le modèle, liste effective calculée par le domaine

- **Statut** : accepté (chantier générateur/Nouveau projet — lot L0) ;
  **schéma du manifeste implémenté au lot L3 (v0.211.0)** — bloc `wizard`
  (étapes + préréglages) analysé et validé par le parseur, porté dans
  `ProjectTemplate.etapesWizard`/`presets`, lint des clés i18n, `lister`
  étendu ; les cinq modèles hors Android déclarent leurs étapes. Le
  **consommateur** (`CalculeParcoursUseCase`, état du wizard, écrans)
  reste au lot L5 (L5a-L5d). Amendement L3 : la clé de condition d'une
  étape s'écrit **`when`** (alignée sur la convention du manifeste —
  `files`/`constraints`/`autoDefinit`), l'exemple du §1 disait `quand` ;
  les identifiants d'étapes et de préréglages suivent
  `^[a-z][a-z0-9-]*$` (tirets admis).
- **Amende** : ADR 0020 (le cadre hôte/enfants/état partagé est conservé ;
  `ETAPES_WIZARD` cesse d'être une constante) — ADR 0021/0023 inchangés
  dans leurs invariants.
- **Contexte** : `ETAPES_WIZARD` (`EtatWizard.kt`) est une liste statique de
  5 étapes identique pour tous les modèles ; `TemplateSection` n'a que deux
  valeurs. `android-app` (14 paramètres) et `gradle-plugin` (6) traversent
  le même parcours, l'étape « Informations » mélange champs communs et
  paramètres du modèle, et aucune étape n'est propre à un type. La règle 1
  impose que la structure du parcours soit **calculée par le domaine** à
  partir du manifeste.

## Décision

### 1. Bloc `wizard` du manifeste (optionnel, rétro-compatible)

```json
"wizard": {
  "etapes": [
    { "id": "plateforme", "titreKey": "etape.plateforme.titre",
      "iconeKey": "etape.plateforme.icone", "quand": null,
      "parametres": ["projectType", "language"] },
    { "id": "sdk", "titreKey": "etape.sdk.titre",
      "parametres": ["minSdk", "targetSdk"] },
    { "id": "bibliotheques", "titreKey": "etape.biblio.titre",
      "quand": "avecRoom || avecHilt || avecRetrofit || avecNavigation || avecCoroutines",
      "parametres": ["includeCoroutines", "includeRetrofit", "includeNavigation", "includeRoom", "includeHilt"] }
  ],
  "presets": [
    { "id": "mvvm-complet", "nomKey": "preset.mvvm.nom",
      "descriptionKey": "preset.mvvm.description",
      "valeurs": { "language": "kotlin", "includeHilt": true, "includeRoom": true, "includeNavigation": true } }
  ]
}
```

- Chaque paramètre `CONFIGURATION` du manifeste apparaît dans **exactement
  une** étape (le parseur le vérifie ; un paramètre non rangé va dans une
  étape par défaut unique « Configuration »). Les `INFORMATION` restent sur
  le socle (identité). Bornes : 12 étapes de modèle ; ids uniques ;
  `quand` = mini-langage existant (une étape dont `quand` est faux
  **n'existe pas** — jamais d'écran vide ni de « Suivant » inutile).
- `presets` (B-8, retenu — §9-Q4) : préréglages déclarés qui posent un jeu de
  valeurs cohérent en un geste, présentés sur la première étape de modèle ;
  ils s'appuient sur la même sémantique que `autoDefinit` (ADR 0153) et ne
  figent rien (`modifiesManuellement` inchangé).
- Manifeste sans bloc `wizard` : une seule étape de modèle « Configuration »
  contenant tous les paramètres `CONFIGURATION` (comportement actuel
  conservé) — les 7 manifestes évoluent au fil des lots L3/L4.

### 2. Socle imposé par l'application (non déclarable)

`MODELE` → **[étapes du modèle, ordre déclaré]** → `IDENTITE` (nom,
description, emplacement — l'ancienne « Informations » scindée) →
`FICHIERS` → `RECAPITULATIF` ; écran de création inchangé (hors
numérotation). L'application garantit ce socle pour tout fournisseur ;
l'étape `FICHIERS` devient **adaptée au modèle** (groupes réellement
déclarés — ADR 0158 — plus extras du type : CI, Dockerfile — ADR 0157/plan
A-3).

### 3. Calcul par le domaine (règle 1)

Nouveau cas d'usage `CalculeParcoursUseCase` (core:domain) :
manifeste + `TemplateFormEvaluation` courant → `ParcoursCreation` :
- liste ordonnée des étapes **effectives** (socle + étapes de modèle dont
  `quand` est vrai), chacune portant id, titre résolu, icône, paramètres
  avec leur `EtatParametre` (ADR 0153) ;
- recalculée à **chaque** changement de formulaire (déjà observé par le
  ViewModel) ; l'indicateur « Étape N sur M » lit M dans cette liste.

### 4. Champs d'une étape qui disparaît

Un changement d'option (ex. décocher la dernière bibliothèque → étape
« Bibliothèques » disparaît) :
- les **valeurs saisies sont conservées** dans `EtatWizard` /
  `SavedStateHandle` (clés par paramètre — déjà le cas) et **exclues** de la
  requête de génération (le wizard ne transmet que les paramètres des étapes
  effectives ; les absents retombent sur leur défaut à la revalidation
  domaine) et du récapitulatif ;
- si l'étape **réapparaît**, les valeurs sont reprises puis revalidées ;
- si l'étape **courante** disparaît, l'utilisateur est ramené à la dernière
  étape effective valide (jamais sauté en avant) ; retour arrière sans perte
  de saisie ; reprise après mort du processus via `SavedStateHandle`
  inchangée ; gardes de validité par étape conservées.

### 5. Fragments : registre au lieu de `when` exhaustif

`NewProjectFragment` maintient un registre id → fabrique de fragment : socle
(les fragments actuels) + un **fragment générique `EtapeParametresFragment`**
pour toute étape de modèle (il rend `RenduParametres` enrichi — ADR 0154 —
avec titres de groupe, zone « Avancé », cartes de bibliothèques, raisons de
désactivation). `EtapeId` devient un type fermé : ids du socle + id
personnalisé des étapes de modèle.

### 6. Aperçu en direct (B-5) et ergonomie

- Depuis toute étape de modèle (et l'identité) : **aperçu du plan**
  (`PlanProjectCreationUseCase`) en feuille basse (téléphone) ou panneau
  latéral (`layout-sw600dp`), antirebond ~300 ms, annulable, hors fil
  principal, état d'erreur typé ; **différentiel** ajouté/retiré calculé
  entre deux plans consécutifs.
- Tablette : parcours à gauche, aperçu à droite (deux colonnes) ;
  téléphone : une colonne, aperçu en feuille basse. Un « mode rapide »
  (préréglage → récapitulatif) rejoint la L5d (§9-Q9).

## Conséquences

- Parcours attendus (détail et schémas par famille dans
  `docs/NEWPROJECT_GENERATEUR_PLAN.md` et maquettes `docs/mockups/ui/`) :
  3 étapes pour `gradle-plugin`, 6-7 pour `android-app` riche.
- `ETAPES_WIZARD` supprimée : l'état dérive la liste ; « l'étape N sur M »
  reste exact quand M change.
- Impact sur les 37 tests `WizardViewModelTest` : le comportement socle est
  préservé (les modèles JVM donnent le même ordre qu'aujourd'hui) ; les
  tests qui nomment `INFORMATIONS`/`CONFIGURATION` sont renommés
  mécaniquement vers le socle (`IDENTITE`, étape de modèle) — aucun test ne
  reste rouge, les nouveaux couvrent apparition/disparition d'étapes,
  conservation des valeurs, restauration après mort du processus.
- `TemplateSection` : conservé pour la compatibilité de lecture ; son rôle
  écran est transféré au bloc `wizard` (une étape de modèle remplace
  `CONFIGURATION`, `INFORMATION` reste le marqueur socle). Suppression
  décidée en L5 si plus aucun lecteur.
