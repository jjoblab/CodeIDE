# ADR 0146 — L'écran d'indexation branché sur les flux RÉELS des trois moteurs (D1–D5 livrées côté frère) et les boutons ENFIN distincts + correctif plantage `rendreActions` (rapport 2ff0dee0)

- **Statut** : **accepté** (livré avec CodeIDE v0.180.0).
- **Date** : 2026-10-24.
- **Décideurs** : jjoblab (« Tu peux maintenant travailler sur CodeIDE
  et faire les mises à jour nécessaire. Profite aussi pour corriger :
  CodeIDE — Rapport de plantage 2ff0dee0 — ClassCastException
  LinearLayout → MaterialButton, FragmentDetailIndexation.
  rendreActions »).
- **Dépend de** : ADR 0145 (écran multi-moteurs, fenêtres de
  nourrissage synthétisées), `docs/DEMANDES_CODEIDE_LSP.md` (D1–D5 —
  les CINQ demandes du chantier indexation, LIVRÉES dans
  codeide-lsp **1.8.0**, ADR 0211–0214 du dépôt frère), ADR 0117
  (écran d'indexation), ADR 0144/0145 (rapports de plantage :
  convention « cause racine + correctif + test de non-régression »).

## Contexte

Deux événements simultanés:

1. **Le dépôt frère a livré les cinq demandes** (codeide-lsp 1.8.0,
   ADR 0211–0214, publié sur GitHub Packages — `maven-metadata`
   `codeide-lsp/host` : 1.8.0 visible) : `SessionLspKotlin.etatIndexation`
   et `SessionLspXml.etatIndexation` (`StateFlow<EtatIndexation>`, le
   MÊME type public que Java — D3), `annulerPassage()` et
   `reesayerEchecs()` sur les TROIS façades (`Boolean`, `false` =
   offre retirée — D4/D5), supplier de connexion sans E/S (D1) et
   verrou lecture-écriture (D2). Toutes ADDITIVES. Côté CodeIDE,
   l'écran v0.178.0 attendait exactement ce branchement : l'agrégat
   acceptait DÉJÀ un moteur « riche » (`MoteurIndexationPresent(moteur,
   phase, etat)`) et le présentateur dégradait honnêtement les
   moteurs sans état.
2. **Plantage rapport 2ff0dee0** (v0.179.0, motorola moto g06 /
   Android 15, EditorActivity, 64 s de session) :
   `java.lang.ClassCastException: android.widget.LinearLayout cannot
   be cast to com.google.android.material.button.MaterialButton`
   dans `FragmentDetailIndexation.rendreActions` (l.338), via
   `rendre` ← `EditorActivity.rendreEtatIndexationLsp` ← la collecte
   de `etatsMoteursIndexation` (émission de
   `fenetrerNourrissage`). CAUSE RACINE : contrat du
   `LayoutInflater` — `inflate(gabarit, conteneur, true)` avec
   `attachToRoot=true` **retourne le CONTENEUR**, pas la vue gonflée ;
   le cast `as MaterialButton` explosait donc DÈS QU'UNE ACTION
   vivait — et une action vit dès le PRET (`REINDEXER`) : toucher la
   pastille une fois l'index prêt, ou recevoir n'importe quelle
   émission du flux avec le panneau ouvert, fermait l'application.
   Plantage DÉTERMINISTE passé inaperçu en revue : les tests du
   présentateur sont PURS (aucun gonflage) et les parcours manuels
   de la v0.179.0 visaient le rapport 032311ee (reprises).

## Décision

### Montée codeide-lsp 1.7.0 → 1.8.0 (bump-frère)

Catalogue `gradle/libs.versions.toml` : `codeide-lsp = "1.8.0"`
(publication Packages du tag v1.8.0 vérifiée). La surface hôte
consommée n'est que RICHE (trois méthodes additives sur les façades
déjà branchées) — aucun autre module n'est touché.

### D3 — L'agrégat lit les flux RÉELS, les fenêtres deviennent filet

`EditorViewModel.etatsMoteursIndexation` : les rangées Kotlin/XML
consomment `session?.etatIndexation` (`flatMapLatest` par session
vivante, `INITIAL` honnête si absente — miroir EXACT du moteur Java),
et portent l'état RICHE au présentateur. Les fenêtres de nourrissage
v0.178.0 (`fenetreIndexationKotlin/XmlInterne`) NE disparaissent PAS :
elles fusionnent par **pire phase** (`EtatsMoteursIndexation.pirePhase`)
et ne peuvent que SURÉLEVER la rangée, jamais masquer l'état réel :
- **Témoin d'échec hôte** : une exception de nourrissage CÔTÉ CodeIDE
  (I/O FUSE du modèle, conversion) n'est jamais vue par le flux
  moteur — seul l'ERREUR de la fenêtre la rend visible (règle v0.178.0
  « le silence devient un état visible ») ;
- **Repli pack ancien** : CONSTRUCTION→PRET tant que le frère
  n'observe pas.
Détail crucial : les fenêtres restent SOURCES de la `combine` (une
lecture `.value` ne re-déclencherait JAMAIS l'agrégat quand SEULE la
fenêtre change — l'ERREUR hôte serait invisible).

### D3 — Le présentateur devient réellement MULTI-MOTEURS

Plus AUCUN Java codé en dur : TOUTES les sections globales lisent les
états RICHES de TOUS les moteurs — compteurs de la barre et de
l'en-tête SOMMÉS sur les moteurs en construction à total connu
(`CompteursConstruction`), reprises SOMMÉES, liste de travail
FUSIONNÉE, entrées écartées / erreurs bloquantes de N'IMPORTE QUEL
moteur, histogramme des coûts toutes langues confondues, bilan du
passage le PLUS RÉCENT (horodatage), compteurs riches dans CHAQUE
rangée moteur (le verbe synthétique reste aux packs anciens).

### D4/D5 — Les boutons deviennent DISTINCTS et branchés

- `PresentationIndexation.actionsDe` : CONSTRUCTION → **[ANNULER]**
  (la maquette revit : le point d'annulation coopératif existe
  désormais côté frère) ; PARTIEL avec écarts → [REESSAYER_ECHECS,
  REINDEXER] dès que N'IMPORTE QUEL moteur observable a des
  avertissements.
- `EditorViewModel.actionerIndexation(action)` : dispatch unique —
  le fragment reste MUET (règle §5.11). `annulerPassageLsp()` /
  `reesayerEchecsLsp()` sollicitent les TROIS sessions vivantes
  (chacune répond `false` sans pass en vol — les autres moteurs n'en
  sont pas affectés) ; `reindexerLsp()` reste l'action LOURDE Java
  (seul moteur à exposer un `reindexer()` public — documenté).

### Correctif plantage 2ff0dee0 — `rendreActions`

`inflate(gabarit, conteneur, false) as MaterialButton` puis
`conteneur.addView(bouton)` : la vue retournée EST le bouton, ses
LayoutParams restent ceux du gabarit (`match_parent`). Le saut
défensif de `ANNULER` disparaît (l'action est de nouveau émise).
Test de non-régression : les assertions d'actions du présentateur
(`ANNULER` en construction, `REESSAYER_ECHECS` au partiel) verrouillent
le contrat AMONT du bouton ; le gonflage lui-même n'est pas
reproductible en test pur (harnais UI hors périmètre du module —
même verdict que le rapport 032311ee : le correctif est la règle
posée au code + le commentaire anti-régression).

## Options écartées

- **Supprimer purement les fenêtres de nourrissage** (brancher le
  flux réel seul) : l'exception de nourrissage côté hôte deviendrait
  un SILENCE (le flux moteur n'en sait rien) — régression du contrat
  v0.178.0 « le silence devient un état visible ».
- **Écran d'indexation par langue** : la pastille est UN point
  d'entrée, le panneau RASSEMBLE (maquette §2/§3/§5 — déjà tranché
  ADR 0145).
- **Retour `false` d'annulation = retirer le bouton immédiatement** :
  l'offre maquette reste pendant TOUTE la construction ; un pack
  ancien répond `false` sans conséquence (l'état suivant fait vivre
  l'écran) — Android Studio garde son bouton actif pendant toute
  l'indexation.

## Conséquences

- **Positives** : l'écran de la maquette est enfin VRAI pour les
  trois langues (compteurs, listes, écarts, bilan Kotlin/XML) ; les
  boutons font ce qu'ils promettent ; le plantage du panneau est
  corrigé à la racine ; l'ouverture `.kt`/`.kts` ne paie plus le
  nourrissage (D1 côté frère, l'hôte n'a rien à changer).
- **Négatives** : la barre SOMMÉE agrège des unités hétérogènes
  (segments kidx + jars Java + racines res/) — c'est un « travail
  restant » global, pas une mesure homogène (chaque rangée moteur
  porte SON compteur exact).
- **Neutres** : `EtatsMoteursIndexation.pirePhase` (fusion) et les
  signatures internes du présentateur changent — interne au module,
  aucun consommateur externe ; `etatJava` (accesseur) conservé pour
  les tests.

## Références

- Demandes/réponses : `docs/DEMANDES_CODEIDE_LSP.md` (journal D1–D5
  ✅ livrés, codeide-lsp 1.8.0, ADR 0211–0214).
- Code : `EditorViewModel` (`etatsMoteursIndexation` sur flux réels,
  `actionerIndexation`, `annulerPassageLsp`, `reesayerEchecsLsp`),
  `MoteurIndexation` (`pirePhaseDe`), `PresentationIndexation`
  (sections multi-moteurs, `actionsDe`), `FragmentDetailIndexation`
  (`rendreActions` corrigé).
- Tests : `PresentationIndexationTest` (23 tests : +D5 inversée,
  +Kotlin riche, +compteurs sommés, +partiel Kotlin, +bilan le plus
  récent, +bloquantes fusionnées).
