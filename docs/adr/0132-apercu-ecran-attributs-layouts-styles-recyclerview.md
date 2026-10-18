# ADR 0132 — Écran Attributs : refonte en layouts réels, styles et RecyclerView

- **Statut** : accepté (propriétaire, 2026-10-18)
- **Date** : 2026-10-18
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0131 (jetons et thème), ADR 0002 (vues XML + ViewBinding), ADR 0133 (typographie,
  unités, cibles tactiles). **Étend** : `PREVIEW.md` § 3.4.1 (« RecyclerView au lieu du LinearLayout »,
  PE9a) au volet Attributs. **Remplace en partie** : PE9b (layouts de contrôles), sur la forme
  seulement — la mécanique (ViewModel, ports, `EditionAttributsXml`, `CatalogueAttributs`) est
  **conservée telle quelle**.
- **Contexte** (v0.135.0) :
  - Le volet Attributs est un `ScrollView` + `LinearLayout` dont les cartes sont gonflées une à une ;
    8 contrôles existent (`controle_texte`, `_segments`, `_dimensions`, `_cotes`, `_identifiant`,
    `_extraction`, `_contrainte(s)`), chacun avec ses marges `16dp` et ses `textAppearanceLabel*`.
  - Les segments sont des `MaterialButton` **Outlined** (un trait par segment) alors que la maquette a
    une bascule plate dans un conteneur surface 2.
  - La deuxième vague (PE11c+) ajoute dix contrôles et un mode « Tous » à **70+ contrôles par vue** :
    un `LinearLayout` rempli d'avance ne tient pas (cf. famille du crash 0.134.30).
  - L'en-tête de carte a `minHeight 48dp` (maquette : **30 dp**) et chaque carte coûte ~18 dp de plus.

## Décision

1. **Un contrôle = un layout = un style.** La liste des layouts, leur correspondance avec les
   `ControleAttribut` et leurs cotes sont dans `docs/PREVIEW_UI.md` § 9 ; les fichiers de référence sont
   dans `docs/mockups/ref/ressources/layout/`. Aucune vue n'est construite en code (`GradientDrawable`,
   `TextView(...)`, `setPadding(...)`) pour cet écran.
2. **Styles partagés** `Widget.CodeIDE.Apercu.*` et `TextAppearance.CodeIDE.Apercu.*` ; tout
   `TextView` hérite de `Widget.CodeIDE.Apercu.Texte` (`includeFontPadding=false`). Aucun littéral
   `dp`/`sp`/couleur dans un layout (règle 3).
3. **Segments et drapeaux = `ChipGroup` + `Chip` (Material 1.14, déjà au catalogue)** : le renvoi à la
   ligne de la maquette (`flex-wrap`), la sélection unique et l'accessibilité viennent du composant ;
   les `MaterialButton` Outlined de `item_segment.xml` disparaissent. Aucune dépendance ajoutée.
4. **Le volet est un `RecyclerView`** dont les items sont : titre, barre d'outils, correctifs, **cartes
   de section**, bouton « autres attributs », groupes de lecture seule. Le **corps d'une carte n'est
   gonflé qu'à l'ouverture** et détruit à la fermeture ; l'état ouvert/fermé est mémorisé par titre
   (comportement actuel conservé). `DiffUtil` sous le capot.
5. **Barre d'outils collante** (recherche + Courants/Tous) : item d'index 1, **épinglée en haut** par une
   `ItemDecoration` dès qu'elle atteindrait le bord supérieur — identique à la maquette (`position:
   sticky`), où le titre « MODIFIER · … » défile au-dessus.
6. **Ordre de travail** : d'abord **PE11b** — refonte de l'**existant** à l'identique (8 contrôles
   actuels, cartes, titre, barre, onglets, poignée) ; puis PE11c+ ajoutent les nouveaux contrôles, chacun
   **avec son layout et sa capture de référence**. Le catalogue pur (`:preview:xml`) ne bouge pas pour
   la refonte.
7. **Recette de fidélité obligatoire** (`PREVIEW_UI.md` § 12) : test de parité des jetons, Robolectric
   des cotes (gonflage sous `Theme.CodeIDE.Apercu`, mesure contre `meas_*.json`), paires de captures
   maquette | application jointes à chaque chantier. **Un chantier ne se ferme pas avec un écart
   visible.**

## Options considérées

### Option A — Refonte en layouts/styles + RecyclerView (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne-élevée (liage par type d'item, décoration collante) |
| Coût | ~1 chantier de refonte (PE11b) avant la deuxième vague |
| Performance | Bonne : les corps de cartes ne sont gonflés qu'ouverts |
| Fidélité | Exacte et vérifiable |
| Familiarité | Élevée : même patron que arbre/problèmes/source (PE9a) |

### Option B — Corriger les valeurs sur le `ScrollView` existant
Moins de code, mais le mode « Tous » gonfle des centaines de vues et le crash de PE9 revient à la
portée. **Écartée.**

### Option C — Vues personnalisées dessinées au `Canvas`
Fidélité maximale mais perte d'accessibilité, de saisie et de thème. **Écartée.**

### Option D — Jetpack Compose
Interdit (ADR 0002).

### Barre collante : variante « titre dans la barre »
Placer le titre dans la barre fixe en haut du volet éviterait la décoration mais **change l'ordre visuel**
au repos (le titre passerait sous la barre) — écart avec la maquette. **Écartée** ; à reconsidérer
seulement si l'épinglage s'avère instable, avec une ligne dans `PREVIEW.md` § 3.4.

## Analyse des compromis
La refonte coûte un chantier de plus avant les nouveautés, mais supprime la cause commune des écarts
(valeurs locales) et rend le mode « Tous » viable. Le risque de régression fonctionnelle est borné : la
mécanique d'édition, ses 100+ tests et le catalogue ne sont pas touchés.

## Conséquences
- **Plus facile** : ajouter un contrôle (un layout + un style + un liage) ; corriger une teinte ou une
  marge partout ; vérifier la fidélité mécaniquement.
- **Plus difficile** : l'épinglage de la barre ; le liage par type d'item (ViewBinding par layout).
- **À revoir** : accessibilité des segments (voir ADR 0133) ; coût du `DiffUnifie` en mode « Tous » après
  mesure sur appareil.

## Actions
1. [ ] PE11b : styles, drawables, layouts de § 9 pour les 8 contrôles existants ; `volet_attributs.xml`
   en `RecyclerView` ; `barre_outils_attributs` épinglée ; suppression des `GradientDrawable`/vues
   construites en code et des littéraux ; Robolectric des cotes.
2. [ ] Supprimer `fond_champ`, `fond_carte_section`, `fond_badge_definis`, `item_segment` (ancien) et les
   `textAppearanceLabel*` du volet une fois remplacés.
3. [ ] `PREVIEW.md` § 3.4 : ligne PE11b et renvoi à cet ADR.
4. [ ] Joindre les captures maquette | application (clair + nuit) pour `Button`, `TextView`, `EditText`.
