# Phase A — Audit et conception de l'écran d'indexation LSP

> **Document de phase A** (prompt 2 du 2026-10-13 — `docs/PREVIEW.md`
> hors périmètre ; PE4 est traité séparément en ADR 0116). Ce document
> est livré en v0.124.0 (audit + ADR brouillon 0117 + plan
> d'implémentation + maquette HTML + questions ouvertes). **Aucun code
> de production n'est livré en phase A** : la phase B (implémentation)
> ne démarre qu'après le GO du propriétaire.
>
> **Règle absolue** : aucune mention de la référence privée dans
> aucun livrable. Les principes observés dans la bibliothèque externe
> servent de lecture, jamais d'attribution. Le tableau comparatif du
> §2 reste interne à ce document.

## 1. À lire avant tout

- `AGENTS.md` en entier : règles impératives, chaîne de vérification,
  identité git (`jjoblab <olson12jb@gmail.com>`), KDoc/commentaires/
  commits en français, aucune ressource en dur, interdits (`!!`,
  `GlobalScope`, `runBlocking`), erreurs modélisées, dispatchers
  injectés, aucune donnée personnelle dans les journaux.
- `docs/LSP_JAVA.md` (section 16.2, indexation), `docs/LSP_KOTLIN.md`,
  ADR `0108` à `0113` (mission LSP Java, index disque par segments,
  reprise « à chaud », état publié en flux), ADR `0111` en particulier.
- Contraintes : Vues XML + ViewBinding (pas de Compose, ADR 0002),
  Material 3, FR + EN (`values/` et `values-en/`), minSdk 26,
  targetSdk 28, Material 1.14.0. Le chantier Material You/theming est
  en cours : **aucune couleur en dur**, uniquement des tokens et
  attributs de thème.

## 2. Ce que fait la bibliothèque de référence (lecture privée)

Le tableau ci-dessous est pour **compréhension interne** : il ne doit
apparaître dans aucun livrable diffusé (règle absolue).

| Principe observé | Détail | Ce que CodeIDE en tire |
| --- | --- | --- |
| **Un modèle unique d'état** | La bibliothèque de référence publie un modèle d'état riche : phase de construction, fraction, message, entrées en cours (avec état par élément), `processed`/`total`, `breakdown` (par indexeur : millisecondes cumulées, nombre d'entrées), `stats` (durée phase bibliothèques, durée phase sources, artefacts reconstruits/réutilisés, fichiers parcourus/ré-analysés). | Étendre `EtatIndex` (lsp:index) avec les champs manquants (durées, breakdown, items) ; exposer intégralement via `lsp:host`. |
| **Deux drapeaux distincts pour les consommateurs** | `ready` (index complet, interrogeable) et `settled` (un passage est allé au bout, même partiellement ou en échec). Garde « mode dégradé » : avant `ready`, une recherche vide veut dire « pas encore » et non « absent ». | CodeIDE a déjà `PhaseIndex.PRET` (= ready) et `PhaseIndex.PARTIEL`/`ERREUR` (= settled). Mais **aucun drapeau explicite** « un passage a eu lieu ». À ajouter. |
| **Une pastille discrète dans la barre du haut** | Indicateur animé + pourcentage pendant la construction, simple coche discrète au repos, masquée sur écran étroit. | Remplacer `barre_etat_lsp` par une pastille dans la toolbar. |
| **Un dialogue de détail au toucher** | En-tête (icône d'état, titre, sous-titre = phase), barre de progression, compteur, liste défilante des éléments avec icône par état, histogramme par indexeur, résumé de cache, bouton « Réindexer ». | Concevoir un bottom sheet de détail (déjà la métaphore de l'app). |
| **Le détail répond à deux questions** | « Qu'est-ce qui est en train d'être indexé ? » et « quel index coûte le plus de temps ? ». | Mêmes questions directrices pour le panneau de détail. |

**Limites de la référence, à dépasser** :
- Un échec ou un index partiel n'a pas de présentation dédiée dans le
  dialogue (aucune liste d'avertissements ni de réessai ciblé).
  CodeIDE a déjà `avertissements: List<String>` dans `EtatIndex` — à
  exposer.
- Pas d'estimation de temps restant. À évaluer (cf. §6.3).
- Le libellé de phase sert aussi de clé de logique (couplage
  présentation/logique). À éviter côté CodeIDE.
- L'information est réservée à un seul indexeur logique (pas de vue
  par langage). CodeIDE est déjà multi-langues (Java + Kotlin) — à
  exploiter.

## 3. Cartographie complète de la chaîne d'état

```
[ServiceIndexDisque]
       │
       │  publie EtatIndex (lsp:index, modèle RICHE)
       │  en StateFlow sur VueIndex.etat
       ▼
[VueIndex]  ── etat: StateFlow<EtatIndex>  ──┐
       │                                      │
       │  (consommé par AdaptateurVueClasspath,
       │   AdaptateurIndexNoms en repli par sondage)
       │                                      │
       ▼                                      ▼
[SessionLsp.relancerCollecteEtat]
       │
       │  collecte vue.etat, TRADUIT en EtatIndexation (lsp:host)
       │  PERT reprises, ne publie pas de breakdown ni de stats
       ▼
[SessionLsp.etatIndexation: StateFlow<EtatIndexation>]
       │
       │  (consommé par feature:editor uniquement aujourd'hui)
       ▼
[EditorActivity.observerEtatIndexationLsp]
       │  flatMapLatest { session.etatIndexation ?: null }
       ▼
[EditorActivity.rendreEtatIndexationLsp]
       │
       │  affiche barre_etat_lsp : VuePointEtat 20 dp + texte
       │  "Indexation Java : 12/191" — UNE SEULE LIGNE
       │  avertissements/erreurs JOURNALISÉS mais PAS AFFICHÉS
       ▼
[activity_editor.xml L72 : barre_etat_lsp]
       │  LinearLayout horizontal dans zone_centrale (entre onglets
       │  et conteneur_editeur) — consomme de la hauteur (risque
       │  v0.100.2 « la barre avalait l'éditeur » documenté)
       ▼
[ÉCRAN]
```

### 3.1 Cadence de publication et risque de saturation UI

- **Source** : `ServiceIndexDisque` publie un `EtatIndex` par **jar
  traité** (ADR 0111 §7) — sur un classpath Android moyen (191 jars),
  cela fait ~191 publications en quelques secondes (la phase
  bibliothèques est rapide car la reprise « à chaud » évite la
  reconstruction des segments valides).
- **Collecte** : `SessionLsp.relancerCollecteEtat` est une `collect`
  sur `scope` — chaque publication se traduit en une écriture de
  `etatIndexationMutable.value` qui déclenche l'UI.
- **UI** : `EditorActivity.observerEtatIndexationLsp` utilise
  `repeatOnLifecycle(STARTED)` + `flatMapLatest` (bonne pratique),
  mais la collecte fait une écriture de vue (`isVisible`, `text`,
  `contentDescription`) par publication — soit **~191 mises à jour de
  vue en quelques secondes**.
- **Verdict** : pas de saturation observée à ce jour (les vues sont
  légères), MAIS l'écran cible (pastille + panneau de détail avec
  liste défilante et histogramme) sera BEAUCOUP plus lourd à repeindre.
  L'échantillonnage devient nécessaire dès la phase B.

### 3.2 Inventaire des consommateurs de « l'index est-il prêt ? »

| Consommateur | Où | Stratégie actuelle | Risque « vide = absent » |
| --- | --- | --- | --- |
| `AdaptateurVueClasspath.kt:97` | `lsp/host` | Garde explicite : si `phase != PRET && phase != PARTIEL` → repli (sondage direct sur les jars). | Aucun : la garde est explicite, le repli est documenté. |
| `AdaptateurIndexNoms.kt:55` | `lsp/host` | `estPret()` = `pret || phase == PRET`. Le champ `pret` est lazy (chargé séparément). | Modéré : si `pret` est false et `phase != PRET`, mais que la construction est settled, le consommateur fait un repli — potentiellement à tort. |
| Complétion LSP Java | `lsp:host` (via les adaptateurs ci-dessus) | Repli par sondage tant que `phase != PRET`. | Aucun (repli documenté). |
| Diagnostics LSP Java | idem | Idem. | Aucun. |
| Recherche de symboles | idem | Idem. | Aucun. |
| Import automatique | idem | Idem. | Aucun. |
| `EditorActivity.rendreEtatIndexationLsp` | `feature/editor` | Affiche une ligne d'état, ignore `avertissements`/`erreurs`. | N/A (UI pure). |
| Kotlin (`SessionLspKotlin`) | `lsp/host` | **N'expose pas d'état d'indexation** (le moteur Kotlin a son propre index `IndexAppelablesKotlin`, non publié en flux). | Élevé : aucun retour UI possible pour l'indexation Kotlin. |

### 3.3 Données disponibles vs manquantes

| Élément cible de l'écran (§5 du prompt 2) | Source | Disponible ? | Coût si manquant |
| --- | --- | --- | --- |
| Phase (construction / prêt / partiel / erreur) | `PhaseIndex` (lsp:index) → `PhaseIndexation` (lsp:host) | ✅ | — |
| Progression `faits`/`total` | `EtatIndex.faits`/`total` | ✅ | — |
| Entrée courante (jar en cours) | `EtatIndex.entreeCourante` | ✅ | — |
| Reprises sans reconstruction | `EtatIndex.reprises` | ✅ côté index, ❌ côté host (PERT) | Faible (1 champ à transporter) |
| Avertissements (entrées écartées) | `EtatIndex.avertissements: List<String>` | ✅ côté index, ✅ côté host | Affichage à ajouter |
| Erreurs bloquantes | `EtatIndex.erreurs: List<String>` | ✅ côté index, ✅ côté host | Affichage à ajouter |
| Liste de travail (entrées en attente / actives / terminées) | — | ❌ | Modéré : à ajouter à `ServiceIndexDisque` (suivi par entrée). |
| Drapeau `settled` (un passage allé au bout) | — | ❌ | Faible : à déduire de la transition `CONSTRUCTION → PRET/PARTIEL/ERREUR`. |
| Drapeau `ready` (interrogeable) | `PhaseIndex.PRET` | ✅ (sémantique existante) | — |
| Répartition par indexeur (breakdown) | — | ❌ | Modéré : à instrumenter dans chaque indexeur (`lsp/index`, `lsp/kotlin`). |
| Durée par phase (bibliothèques / sources) | — | ❌ | Faible : à mesurer dans `ServiceIndexDisque`. |
| Artefacts reconstruits vs réutilisés | `reprises` (partiel) | ⚠️ Partiel : `reprises` donne le compte global, pas la distinction reconstruit/réutilisé par phase. | Faible : à compléter. |
| Fichiers parcourus / ré-analysés | — | ❌ | Modéré : à instrumenter dans la phase sources. |
| Bilan du dernier passage | — | ❌ | Faible : à persister (DataStore ?) |
| Historique court des passages | — | ❌ | Modéré : à persister. |
| Indexeur le plus coûteux | — | ❌ (découle du breakdown) | — |
| État d'indexation Kotlin | `IndexAppelablesKotlin` | ❌ (pas publié en flux) | Modéré : à exposer. |
| État d'indexation XML | — | ❌ (pas applicable à ce stade) | — |

### 3.4 Contraintes d'architecture

- **Règles de dépendance** (`docs/ARCHITECTURE.md`,
  `checkModuleDependencies`) : `lsp:index` est un module JVM pur
  (`explicitApi()`), `lsp:host` dépend de `lsp:index` (API), `lsp:kotlin`
  dépend de `lsp:host` (impl). Un modèle d'état commun Java/Kotlin doit
  vivre dans `lsp:index` (ou `lsp:host` si transcription publique
  nécessaire).
- **`explicitApi()` sur `lsp:*` purs** : toute nouvelle surface publique
  doit être déclarée `public val`/`public class` explicitement.
- **Pas de logique dans les vues** (règle 1) : un présentateur pur
  (modèle d'état → modèle d'affichage) testé unitairement, dans le
  patron de `PresentationTooling.kt`. Les vues ne font que lier.
- **Libellés transportés par énumérations**, jamais par chaînes
  comparables (durcis la i18n et la logique).
- **Performance UI** : échantillonnage des mises à jour de progression
  (pas une mise à jour par jar) ; liste recyclée avec diff.
- **Accessibilité** : annonces TalkBack aux changements de phase
  (pas à chaque pourcentage), cibles tactiles ≥ 48 dp, libellés
  localisés, ne jamais coder l'état par la seule couleur.

### 3.5 Impact sur les surfaces existantes

| Surface | Devenir proposé | Justification |
| --- | --- | --- |
| `barre_etat_lsp` (`activity_editor.xml` L72) | **Supprimée** : la hauteur qu'elle consomme dans `zone_centrale` est un risque (incident v0.100.2 « la barre avalait l'éditeur » déjà documenté). Remplacée par la pastille de toolbar. | Pas de saut de mise en page pendant l'indexation. |
| Action de menu `action_reindexer` | **Conservée** mais **visuellement liée** à l'état (icône + couleur en phase avec la pastille). | Cohérence visuelle. |
| Ligne tooling du panneau inférieur (`PanneauToolingController`) | **Conservée** : couvre un périmètre DIFFÉRENT (synchronisation Gradle, build, tâches). Pas d'unification forcée. | Pas de confusion utilisateur. |
| Index Kotlin (`IndexAppelablesKotlin`) | **Exposé** via un nouveau `SessionLspKotlin.etatIndexation` (à créer sur le modèle de `SessionLsp.etatIndexation`). | Vue par langage. |

## 4. ADR brouillon (0117)

> **Statut** : brouillon (phase A, v0.124.0 — non finalisé tant que
> l'utilisateur n'a pas donné le GO pour la phase B).

### 4.1 Contexte

L'écran d'indexation actuel de CodeIDE est une ligne unique
`barre_etat_lsp` (`activity_editor.xml` L72) : point d'état 20 dp +
texte « Indexation Java : 12/191 ». Cette ligne (a) consomme de la
hauteur dans la colonne centrale (incident v0.100.2 déjà documenté),
(b) n'affiche ni les avertissements ni les erreurs du modèle
`EtatIndex` (pourtant disponibles), (c) ne couvre que Java (l'index
Kotlin n'est pas publié en flux), (d) ne donne aucun détail
« qu'est-ce qui est en train d'être indexé ? » ni « quel index coûte
le plus de temps ? ».

### 4.2 Constats vérifiés

1. **`EtatIndex` (lsp:index) est RICHE** (7 champs, cf. §3.3) —
   `PhaseIndex.CONSTRUCTION`/`PRET`/`PARTIEL`/`ERREUR`,
   `total`/`faits`/`entreeCourante`/`reprises`/`avertissements`/
   `erreurs`. Plus riche que la référence externe sur l'honnêteté
   (partiel et erreur distincts, avertissements listés).
2. **`EtatIndexation` (lsp:host) est une traduction APPAUVRIE** :
   6 champs (perte de `reprises`). Aucune donnée de durée, de
   breakdown, ni de bilan de dernier passage. Aucune liste de travail.
3. **`EditorActivity.rendreEtatIndexationLsp` est minimaliste** : une
   ligne de texte + un point d'état. Aucun détail au-delà de la phase
   et de la progression.
4. **`barre_etat_lsp` consomme de la hauteur** dans `zone_centrale`
   (risque v0.100.2 « la barre avalait l'éditeur » déjà documenté en
   commentaire du layout).
5. **L'index Kotlin n'est pas publié en flux** (`SessionLspKotlin`
   n'expose pas d'`etatIndexation`). Le moteur a son propre index
   `IndexAppelablesKotlin` sans surface observable.
6. **`AdaptateurIndexNoms.estPret()`** mélange deux notions : `pret`
   (lazy chargé) et `phase == PRET` (index autoritatif). Pas de
   drapeau `settled` distinct.
7. **Aucune persistance du bilan** : impossible d'afficher « il y a
   2 min, 4,2 s, 188 reprises, 3 reconstruits » au prochain démarrage.

### 4.3 Options évaluées

| Option | Description | Avantages | Inconvénients |
| --- | --- | --- | --- |
| **A — Ne rien changer** | Garder la ligne `barre_etat_lsp` telle quelle. | Aucun travail, aucun risque. | Ne répond pas au besoin (le modèle est riche mais invisible). Constate l'écart vis-à-vis de la référence externe sans agir. |
| **B — Étendre la ligne** | Ajouter les avertissements et erreurs dans la ligne existante (par exemple un compteur « 3 ⚠ »). | Faible coût, pas de saut de mise en page majeur. | La ligne reste limitée à une dimension ; impossible d'ajouter breakdown, liste de travail, bilan. Toujours pas de pastille de toolbar. |
| **C — Pastille de toolbar + bottom sheet de détail** (recommandée) | Pastille discrète dans la toolbar (indicateur animé + % pendant la construction, coche au repos, masquée sur écran étroit). Toucher la pastille → ouvre un bottom sheet de détail (la métaphore est déjà celle de l'app). Le sheet affiche : en-tête, barre de progression, compteur, liste de travail, vue par moteur, bilan de dernier passage, bouton Réindexer. | Aucun saut de mise en page (la pastille est dans la toolbar qui existe déjà). Détail riche. Cohérence avec la métaphore existante (panneau inférieur). Répond à toutes les exigences §5 du prompt. | Coût d'implémentation : nouveau présentateur, nouveau modèle d'affichage, nouvelles vues, branchement, tests. Décision à valider en phase B. |
| **D — Écran dédié plein écran** | Ouvrir un écran séparé pour l'indexation. | Place disponible. | Perturbe le flux utilisateur (l'utilisateur veut savoir PENDANT qu'il édite, pas en quittant l'éditeur). Rejeté. |

### 4.4 Décision recommandée

**Option C** : pastille de toolbar + bottom sheet de détail.

1. **Pastille de toolbar** :
   - Construction : icône animée (anneau accent en rotation) + %,
     masquée sur écran étroit (largeur < 600 dp).
   - Prêt : coche discrète (`codeide_succes` ou
     `?attr/colorOnSurfaceVariant`), masquée sur écran étroit
     (l'information « tout va bien » n'a pas besoin d'être visible).
   - Partiel : triangle d'avertissement (`codeide_journal_warn` ou
     token équivalent).
   - Erreur : icône d'erreur (`codeide_error`).
2. **Bottom sheet de détail** (réutilise la métaphore du panneau
   inférieur existant — pas un nouveau paradigme) :
   - En-tête : icône d'état + titre (« Indexation en cours » / « Index
     à jour » / « Index partiel » / « Index en erreur ») + sous-titre
     (phase courante ou bilan).
   - Construction : barre déterminée ou indéterminée, compteur
     « 118 sur 191 entrées », estimation du temps restant
     (uniquement si fiable, cf. §6.3).
   - Liste de travail défilante (hauteur bornée) : une ligne par
     entrée, icône d'état (en attente / actif / terminé / écarté).
   - Vue par moteur : section Java + section Kotlin, chacune avec son
     état (`codeide_langage_java` #E76F00, `codeide_langage_kotlin`
     #7F52FF — tokens existants).
   - Bilan de dernier passage (au repos) : durée, artefacts reconstruits
     vs réutilisés, fichiers ré-analysés, indexeur le plus coûteux
     (histogramme horizontal simple).
   - Boutons : « Réindexer » (toujours), « Réessayer les N entrées
     échouées » (si PARTIEL).
3. **Modèle d'état étendu** :
   - Ajouter à `EtatIndex` (lsp:index) : `listeTravail: List<EntreeTravail>`,
     `breakdownIndexeurs: List<BreakdownIndexeur>`, `dureePhases:
     DureesPhases?`, `bilanDernierPassage: BilanDernierPassage?`,
     `settled: Boolean`.
   - Étendre `EtatIndexation` (lsp:host) pour transporter ces champs
     (ne plus perdre de données à la frontière).
   - Créer `SessionLspKotlin.etatIndexation` (parallèle à Java).
4. **Présentateur pur** (patron `PresentationTooling.kt`) :
   - `PresentationIndexation` transforme `EtatIndexation` (Java) +
     `EtatIndexationKotlin` (Kotlin) en `ModeleAffichageIndexation`
     (immutable, testé unitairement, sans aucune référence Android).
5. **Vues XML + ViewBinding** (pas de Compose, ADR 0002) :
   - Pastille : `VuePointIndexation` (nouvelle vue custom, sur le
     modèle de `VuePointEtat` existant).
   - Sheet : nouveau fragment dans `feature:editor`, layout dédié,
     `RecyclerView` pour la liste de travail.
6. **Pas de logique dans les vues** : les libellés de phase et les
   statuts sont transportés par des **énumérations** (jamais par des
   chaînes comparables).

### 4.5 Conséquences

- **Positives** : le modèle riche `EtatIndex` devient visible.
  L'utilisateur comprend ce qui se passe (et ce qui est dégradé).
  Aucun saut de mise en page (la `barre_etat_lsp` disparaît au profit
  d'une pastille dans la toolbar qui existe déjà).
- **Négatives** : travail d'implémentation substantiel (nouveau
  présentateur, nouvelle vue custom, nouveau fragment, nouveau modèle
  d'état étendu, instrumentation des indexeurs pour le breakdown et
  les durées, persistance du bilan). Découpé en lots livrables
  séparément (cf. §5).
- **Neutres** : la phase B devra décider si l'estimation de temps
  restant est fiable (cf. §6.3).

### 4.6 Hors périmètre

- L'agencement bottom sheet / clavier / barre de navigation
  d'`EditorActivity` (prompt 1 : ne pas modifier ces parties).
- La logique interne de construction des index (l'audit se contente
  d'exposer ce qui est déjà produit).
- La bibliothèque `code-editor` (prompt compagnon, ne pas modifier ici).

## 5. Plan d'implémentation (phase B, après GO)

Lots livrables séparément. Un commit conventionnel par lot (`feat(lsp-host):
...`, `feat(editor): ...`) sous l'identité `jjoblab`.

### Lot B1 — Étendre le modèle d'état (lsp:index)
- Fichiers touchés : `lsp/index/.../EtatIndex.kt` (extension),
  nouveaux fichiers pour `EntreeTravail`, `BreakdownIndexeur`,
  `DureesPhases`, `BilanDernierPassage`.
- Critère de fin : `EtatIndex` étendu avec tous les champs du §4.4-3,
  tests unitaires des invariants (champs nuls hors phase, etc.).
- Module JVM pur, `explicitApi()`.
- SemVer : patch (champ additif, pas de rupture).

### Lot B2 — Instrumenter les indexeurs (lsp:index, lsp:kotlin)
- Fichiers touchés : `ServiceIndexDisque.kt` (mesure des durées,
  remplissage du breakdown, suivi par entrée), `IndexAppelablesKotlin.kt`
  (publication d'un `EtatIndex` analogue).
- Critère de fin : `VueIndex.etat` publie les nouveaux champs ;
  `SessionLspKotlin.etatIndexation` existe.
- SemVer : patch.

### Lot B3 — Étendre la transcription (lsp:host)
- Fichiers touchés : `SessionLsp.kt` (transport des nouveaux champs
  dans `EtatIndexation`), `SessionLspKotlin.kt` (création de
  `etatIndexation`), `AdaptateurIndexNoms.kt` (drapeau `settled`).
- Critère de fin : `EtatIndexation` est isomorphe à `EtatIndex` ;
  `SessionLspKotlin.etatIndexation` existe.
- SemVer : patch.

### Lot B4 — Présentateur pur (feature:editor)
- Fichiers touchés : nouveau `PresentationIndexation.kt` (patron
  `PresentationTooling.kt`), `ModeleAffichageIndexation.kt`.
- Critère de fin : présentateur testé unitairement (tous les états,
  y compris partiel avec avertissements, erreur, passage vide, total
  inconnu, estimation non fiable).
- SemVer : patch.

### Lot B5 — Pastille de toolbar (feature:editor)
- Fichiers touchés : nouveau `VuePointIndexation.kt` (vue custom),
  `activity_editor.xml` (ajout de la pastille dans la toolbar,
  suppression de `barre_etat_lsp`).
- Critère de fin : la pastille s'affiche dans les 4 états (construction
  animée, prêt discrète, partiel avertissement, erreur).
- SemVer : minor (changement visible).

### Lot B6 — Bottom sheet de détail (feature:editor)
- Fichiers touchés : nouveau fragment `FragmentDetailIndexation`,
  layout `fragment_detail_indexation.xml`, `RecyclerView` + adapter
  pour la liste de travail, histogramme horizontal simple.
- Critère de fin : le sheet affiche tous les éléments du §4.4-2 dans
  les 4 états.
- SemVer : minor.

### Lot B7 — Branchement et tests UI (feature:editor)
- Fichiers touchés : `EditorActivity` (branchement de la pastille et
  du sheet), tests Robolectric (liaison d'état, rotation).
- Critère de fin : la CI passe (chaîne complète), `docs/TESTS_MANUELS.md`
  mis à jour, ADR 0117 finalisé.
- SemVer : patch.

### Lot B8 — Chaînes FR + EN
- Fichiers touchés : `feature/editor/src/main/res/values/strings.xml`
  et `values-en/strings.xml`.
- Critère de fin : tous les nouveaux libellés sont traduits.
- SemVer : patch (peut être fusionné avec B5/B6).

### Chaîne de vérification (par lot)

`source scripts/env.sh`, puis la chaîne COMPLÈTE de la directive
v0.79.0 sur les modules touchés : `spotlessApply`, puis
`spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest
koverVerify`, puis `:app:assembleDebug`. Si un module `lsp:*` pur est
modifié, respecte `explicitApi()`.

## 6. Maquette HTML

Voir `docs/mockups/indexation/indexation.html` — ancrée sur les vraies
ressources de l'application (`core/ui/src/main/res/values/colors.xml`
et `values-night/colors.xml`). La maquette montre :

- Les 4 états de la pastille (construction avec %, prêt, partiel,
  erreur) — clair et sombre côte à côte.
- Les 4 états du panneau de détail (construction, partiel avec
  avertissements, erreur, au repos avec bilan) — clair et sombre.
- Portrait (390×844 — Pixel 4) et paysage (844×390).
- Écran large (1280×800 — tablette) : la pastille reste visible, le
  panneau s'élargit.
- La liste de travail (entrées en attente / actives / terminées /
  écartées) avec icônes par état.
- La vue par moteur (section Java + section Kotlin).
- Le bilan de dernier passage (durée, artefacts reconstruits/réutilisés,
  fichiers ré-analysés, indexeur le plus coûteux en histogramme).
- Les boutons Réindexer / Réessayer les échecs.

La maquette de départ fournie par l'utilisateur (`Main.dc.html`,
`Partiel.dc.html`, `Pret.dc.html`) a été respectée et étendue :
- **Conserver** : la palette (`codeide_primary` #43559E, `codeide_background`
  #F9F9FF, `codeide_langage_java` #E76F00, `codeide_langage_kotlin`
  #7F52FF, `codeide_succes` #2E7D32, `codeide_journal_warn`), la
  structure (toolbar + scrim + bottom sheet arrondi), le système
  d'icônes SVG.
- **Étendre** : ajout de l'état « erreur » (manquant dans la maquette
  de départ), ajout du mode sombre (clair/sombre côte à côte), ajout
  du paysage et de l'écran large.
- **Justifier les écarts** : la maquette de départ ne montrait pas
  l'état erreur — ajouté car l'état `PhaseIndex.ERREUR` existe dans le
  modèle CodeIDE et DOIT être représenté. Le mode sombre est nécessaire
  car CodeIDE supporte déjà `values-night/` (palette complète). Le
  paysage et l'écran large sont nécessaires car la preview CodeIDE
  elle-même supporte déjà ces configurations (PE3 profils device).

## 7. Questions ouvertes pour l'utilisateur

> Une seule question par sujet, avec la recommandation de l'audit.

### Q1 — Persistance du bilan de dernier passage

Le bilan (durée, artefacts reconstruits/réutilisés, fichiers
ré-analysés) doit-il être persisté entre les sessions pour s'afficher
au prochain démarrage, ou bien recalculé à chaque sync ?

- **Recommandation** : persister dans DataStore (déjà utilisé par
  CodeIDE, ADR existante) avec une clé par projet. Le bilan d'un
  passage est court (quelques champs), ne justifie pas une
  persistance lourde.

### Q2 — Estimation du temps restant

L'estimation du temps restant (cf. §5 du prompt 2) est-elle attendue,
et à quel seuil de fiabilité ?

- **Recommandation** : afficher une estimation UNIQUEMENT si au moins
  20 % des entrées sont traitées ET que le débit moyen est stable
  (écart-type < 50 %). Sinon : rien, jamais de faux chiffre.
  Prendre en compte la reprise « à chaud » (entrées quasi
  instantanées) en excluant les reprises du calcul de débit.

### Q3 — Annulation d'un passage en cours

Le bouton « Annuler le passage » (visible dans la maquette de départ
fournie) doit-il être implémenté en phase B ?

- **Recommandation** : non, en phase B. L'annulation d'un passage
  interrompt des écritures de segments (risque de segments partiels —
  l'ADR 0111 garantit la atomicité par segment, mais l'annulation
  utilisateur n'a jamais été testée). À évaluer en phase C si le
  besoin se confirme.

### Q4 — Indexation en priorité du projet courant

Faut-il offrir « indexer en priorité le projet courant » (cf. §5.7 du
prompt 2) ?

- **Recommandation** : non. CodeIDE ne supporte qu'une session de
  projet à la fois (ADR 0114 I1) — il n'y a pas de concurrence entre
  projets. L'option n'a de sens que si plusieurs sessions sont
  ouvertes simultanément, ce qui est exclu.

### Q5 — Cohérence visuelle avec la ligne tooling du panneau inférieur

La ligne tooling du panneau inférieur (`PanneauToolingController`)
utilise déjà une pastille de canal colorée, un titre, un sous-titre, un
chrono, une progression. Faut-il unifier le langage visuel ?

- **Recommandation** : oui, pour la pastille de toolbar (mêmes
  formes, mêmes couleurs sémantiques). Non, pour le panneau de détail
  (le bottom sheet d'indexation a un périmètre différent du panneau
  inférieur tooling : pas de boutons d'action rapides, pas de
  journal, focus sur le « quoi » et le « combien de temps »).

### Q6 — Cycle de vie du panneau

Le panneau doit-il se fermer automatiquement quand la session LSP se
ferme (ADR 0114) ?

- **Recommandation** : oui, avec une transition douce (animation de
  fermeture, pas de disparition brutale). La collecte est liée à la
  session (`flatMapLatest` déjà en place), donc technique : il
  suffit d'observer `session == null` et de fermer le sheet.

## 8. Format du rapport de phase B (à venir)

- Ce qui est livré par lot (fichiers, tests, lignes).
- Tests ajoutés (présentateur, modèle, vues Robolectric,
  non-régression de la place de l'éditeur).
- Ce qui n'a pas pu être vérifié sans appareil.
- Ce qui est recommandé ensuite.

## 9. Hors périmètre

- L'agencement bottom sheet / clavier / barre de navigation
  d'`EditorActivity` (prompt 1).
- La logique interne de construction des index.
- La bibliothèque `code-editor`.
- `barre_etat_lsp` est retirée de `activity_editor.xml` (uniquement ce
  retrait, signalé pour ne pas marcher sur le travail du prompt 1).
