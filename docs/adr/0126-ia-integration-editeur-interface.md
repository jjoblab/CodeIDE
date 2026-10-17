# ADR 0126 — IA : intégration à l'éditeur et à l'interface

- **Statut** : proposé
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0122 (modules), 0125 (agent). **S'appuie sur** : ADR 0052/0053 (tiroir à
  fragments + fabrique), ADR 0059 (tokens de thème, section « bientôt »), ADR 0118
  (panneau inférieur/clavier/insets de `EditorActivity`).
- **Contexte** (vérifié dans le dépôt et dans `jjoblab/code-editor`) :
  - Le tiroir de l'éditeur est un `FragmentContainerView` avec un **rail de destinations**
    (Fichiers, Recherche, Projet, Git, Terminal) construit dans `construireRail()` ;
    chaque destination est un fragment ajouté une fois puis montré/caché par tag.
  - La bibliothèque d'édition expose : `EditorView.addSelectionAction(SelectionAction)`
    (action d'hôte dans la pill de sélection), `painterHost.register(EditorDecorationPainter)`
    (décorations), `setCompletionProvider`, `InlayHint`, et `EditorSession.replaceRange` comme
    **point de mutation unique** avec `beginBatch/endBatch` et coalescence d'annulation.
  - **`ViewZone`/`ViewZoneProvider` existent dans `cel-lsp-api` mais ne sont consommés par
    aucun code de rendu de `cel-ui`** (aucune référence dans `EditorView`) : insérer une vue
    Android entre deux lignes **n'est pas disponible aujourd'hui**.
  - `DiffUnifie` (`core:domain`) et `ligne_diff.xml` (`feature:editor`) existent déjà
    pour l'affichage de diffs.
  - `SectionParametres.IA` et le cas `BientotFragment` existent déjà (écran « bientôt »).

## Décision

### Points d'entrée dans l'interface
1. **Tiroir → 6ᵉ destination « IA »** (rail, icône `ic_smart_toy` déjà présente dans
   `core:ui`, libellé « IA »). Fragment hôte `IaTiroirFragment` fourni par la fabrique
   `FabriqueFragmentIaTiroir`, avec trois sous-panneaux — **Chat**, **Agent**,
   **Historique** — et un quatrième, **Recherche**, réservé (état « bientôt » générique
   existant) : la recherche sémantique du projet n'est pas dans ce chantier.
2. **Éditeur → action de sélection « Demander à l'IA »** (`SelectionAction`) ouvrant une
   **barre de consigne en ligne** (champ + actions rapides : Expliquer, Corriger,
   Documenter, Tests, Simplifier) ancrée au bas de la zone éditeur, au-dessus de la barre de
   symboles.
3. **Éditeur → carte de proposition (diff)** : le résultat d'une édition IA s'affiche dans
   une **carte de diff** (`DiffUnifie` + `ligne_diff.xml`) avec **Accepter / Rejeter / Réessayer**.
   « Accepter » applique le changement en **un seul lot** (`beginBatch/endBatch`) : un
   seul Annuler défait tout.
4. **Panneau inférieur → Problèmes / Console / Logcat** : action de ligne
   « Corriger avec l'IA » (diagnostic) et « Expliquer cette erreur » (console/logcat) qui
   ouvrent le Chat pré-rempli avec le contexte borné de la ligne.
5. **Paramètres → IA** : le cas `IA` de `BientotFragment` est remplacé par un écran réel
   (fournisseurs, clé, modèle, confidentialité, limites, historique).

### Câblage dans l'éditeur (limité)
`EditorActivity` ne gagne qu'un **câblage mince** (≲ 80 lignes cumulées) : ajout de la
destination au rail + de son tag, enregistrement de la `SelectionAction`, hôte de la carte de
diff, implémentation de `PortEditeurActif`. Toute logique vit dans `:feature:ai` / `:core:ai`.
Les insets IME/barre de navigation du panneau et de la carte **réutilisent**
`GestionnaireInsetsEditeur` (ne pas réintroduire les régressions de l'ADR 0118).

### Évolutions différées (hors lots IA-1 à IA-7)
- **Diff en ligne dans le flux de texte** (lignes retirées/ajoutées intercalées) : nécessite
  de **brancher `ViewZone` dans le rendu de `cel-ui`** (chantier dans `jjoblab/code-editor`,
  version publiée, puis montée de version dans le catalogue). Lot optionnel **IA-5b**.
- **Complétion « texte fantôme »** (Tab pour accepter) : lot optionnel **IA-8**, ADR propre
  (latence, coût, déclenchement).

## Options considérées

### Option A — Carte de diff ancrée (existant) + diff en ligne plus tard (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Faible-moyenne (briques existantes) |
| Coût | Aucune modification de la bibliothèque d'édition au lancement |
| Scalabilité | Le diff en ligne reste ajoutable (IA-5b) sans refonte |
| Familiarité | Élevée (`DiffUnifie`, `ligne_diff.xml`, popups ancrées) |

### Option B — Diff en ligne via `ViewZone` dès le lancement
Meilleure expérience, mais dépend d'un chantier de rendu non commencé dans la
bibliothèque (publication, montée de version) : **bloquerait** tout le chantier IA.
**Reportée** en IA-5b.

### Option C — Appliquer directement dans l'éditeur, annuler au besoin
Aucune revue, risque de perte de contexte à l'écran, et conflit avec les diagnostics LSP
en cours. **Écartée** (cohérent avec ADR 0125).

## Analyse des compromis
A livre la valeur (édition assistée sûre) sans attendre la bibliothèque ; la carte de diff
est moins « magique » qu'un diff en ligne mais suffit sur mobile où la hauteur est comptée.

## Conséquences
- **Plus facile** : lot IA-5 livrable de façon autonome ; aucune dépendance externe.
- **Plus difficile** : un 6ᵉ élément de rail réduit la largeur de chaque destination
  (vérifier 360 dp et tiroir à 45 % ; le rail est en `layout_weight` égal, `HAUTEUR_RAIL_DP = 66`).
- **À revoir** : IA-5b (diff en ligne), IA-8 (texte fantôme), onglet « Recherche » du tiroir IA.

## Actions
1. [ ] `FabriqueFragmentIaTiroir`, `ControleurIaTiroir`, `PontIaEditeur` (`core:ui`) (lot IA-0).
2. [ ] Destination IA dans `construireRail()` / `selectionnerDestination()` + tag `TAG_IA` (lot IA-3).
3. [ ] `SelectionAction` « Demander à l'IA » + barre de consigne + carte de diff (lot IA-5).
4. [ ] Actions de ligne Problèmes/Console/Logcat (lot IA-5).
5. [ ] Remplacer le cas `IA` de `BientotFragment` par l'écran réel (lot IA-2).
6. [ ] Ouvrir un ticket/ADR dans `jjoblab/code-editor` pour `ViewZone` si IA-5b est retenu.
