# ADR 0134 — Aperçu : écran principal, palette, placement, menus, onglets Arbre / Problèmes / Source à l'identique de la maquette

- **Statut** : **accepté** (2026-10-19, GO PE11i du propriétaire) — les décisions D1 à D9 découlent de la demande
  « design identique pour tous les écrans » ; **D8 (cibles tactiles) confirmée par le propriétaire** avec le GO PE11i
  (le brief PE11 prescrit ces cotes : puces 30, segments 26, HUD 34-36, tuiles et rangées 34 dp ; l'appbar garde 48 dp
  de zone tactile au bouton plus).
- **Date** : 2026-10-18
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0131 (jetons, thème `Theme.CodeIDE.Apercu`), ADR 0132 (layouts, styles, `RecyclerView`),
  ADR 0133 (typographie, unités, cibles tactiles). **Spécification** : `docs/PREVIEW_UI_ECRANS.md` (partie II).
- **Numérotation** : si un autre ADR a pris 0130 (ou 0127-0129), renuméroter la série et les renvois.

## Contexte

PE11a-b ont aligné l'écran Attributs sur la maquette. Le reste de `PreviewActivity` (barre du haut, puces, canvas,
calques, HUD, bandeau, toast, bulle, palette, placement, menus, onglets Arbre / Problèmes / Source) a été livré par
PE9/PE10 avec les rôles du thème de l'app et des littéraux. Trente écarts sont relevés (`PREVIEW_UI_ECRANS.md` § 2,
E1-E30) : damier de 16 dp aux couleurs fixes, barre d'application Material au lieu de la barre de la maquette,
segments de mode en boutons contournés, interrupteurs Material de 52 × 32, popover de palette non borné, rangées
d'arbre / de problèmes / de source sans les cotes de la maquette, bandeau d'erreur dessiné par le moteur, constantes
de couleur dans `VuePreviewXml`.

Contraintes : `:preview:xml` est pur (zéro Material, zéro `Context`) ; `:feature:preview` est en XML + ViewBinding
(ADR 0002, pas de Compose) ; l'app s'appuie sur la police système (ADR 0133).

## Décision

- **D1 — Barre du haut en `LinearLayout` de 52 dp**, pas de `ActionBar`/`Toolbar` : `setSupportActionBar` disparaît ;
  le menu « Choisir le fichier… » s'ouvre par un `PopupMenu` ancré à `bouton_plus` ; l'inset de la barre d'état est
  appliqué à `entete_preview` (fond `apercuSurface`).
- **D2 — Pastille de mode en `ChipGroup`** (sélection unique, obligatoire) de trois `Chip` ; écouteur
  `setOnCheckedStateChangeListener`. Même technique pour les puces de réglages et de catégories
  (`ensureMinTouchTargetSize=false`, `chipStartPadding` à 0 + `textStartPadding`, état « menu ouvert » = `isActivated`).
- **D3 — Surfaces** : popovers de **palette et de placement opaques** (comme la maquette) ; **menus (appareil, calques,
  cible) translucides et colorés** — écart voulu du propriétaire conservé (PREVIEW.md § 3.4.1/3.4.2, `preview_menu_fond`
  → `apercu_menu_fond` `#F2FFFFFF` / `#F21A1E25`).
- **D4 — Bulle** : vue de **58 dp** = disque violet de 50 dp + halo extérieur de 4 dp (layer-list), icône 24 dp ;
  ouverte : `apercu_bulle_fond_ouverte`, icône `apercuFond`.
- **D5 — Interrupteurs** : `SwitchCompat` 34 × 20 stylé (`Widget.CodeIDE.Apercu.Interrupteur`, piste et pouce en
  drawables) à la place de `MaterialSwitch` (52 × 32) ; coût accepté pour la fidélité.
- **D6 — Bandeau d'erreur et toast deviennent des vues** (`banniere_rendu`, `toast_apercu`) ; le moteur ne dessine plus
  de bandeau (`dessinerBandeauErreur` supprimé) et relaie le message ; `Toast.makeText` est remplacé par la vue
  (fondu 200 ms, 2,4 s).
- **D7 — Le moteur reçoit des jetons purs** : `JetonsCalques` (entiers ARGB, data class dans `:preview:xml`) injecté par
  `VuePreviewXml.appliquerJetons(...)` à la création et à chaque changement de thème ; il remplace les constantes
  de couleur. Test de parité `JetonsCalquesTest` contre `jetons-maquette.json`. Les largeurs de trait, tailles de
  poignées, d'étiquettes et d'épingles et le damier (8 dp) sont alignés sur la maquette (`PREVIEW_UI_ECRANS.md` § 3.4-3.5).
- **D8 — Cibles tactiles** : la dérogation de l'ADR 0133 (plancher **24 × 24 dp**) est **étendue au chrome dense de
  l'aperçu** : puces 30 dp, segments de mode 26 dp, boutons de HUD 34-36 dp, tuiles et rangées d'arbre 34 dp.
  Reste à **48 dp** : retour, plus (36 dp visibles + zone tactile 48 dp par `TouchDelegate`), bulle (58 dp), fermer.
  Compensations : espacements ≥ 6 dp, `contentDescription` partout, zones étendues pour les boutons sans texte.
- **D9 — Palette** : hauteur = `min(600 dp, hauteur disponible)` imposée par le code ; grille 3 colonnes avec
  `ItemDecoration` d'espacement 8 dp ; mini-arbre du placement = **mêmes rangées que l'arbre** (hauteur 32) ;
  glyphe « ▾ » des puces Appareil / Calques via ressource texte (un `RelativeSizeSpan` est toléré).
- **Hors périmètre / non portés** : cadre de téléphone, colonne de légende, étiquettes Refonte / Nouveau / Existant,
  menu « Simuler un état » de la maquette, valeurs d'exemple (142 ms, 9 vues).
- **Écarts voulus conservés** : titre de l'appbar 14 sp mono gras ; Plan sans libellés ; feuille à 48 % ; onglets
  `RecyclerView` ; ghost violet ; pas de chip « Compacter » ; menus translucides (D3).

## Conséquences

- **Positives** : écran indiscernable de la maquette, clair et nuit ; une seule source de couleurs (39 jetons, parité
  testée) pour les vues **et** le moteur ; le thème dynamique n'altère plus l'aperçu.
- **Coûts** : refonte de la barre du haut (abandon de l'`ActionBar`), nouveaux layouts de popovers et d'items, suppression
  de code d'habillage dans `PreviewActivity` / `PalettePopover` / `PopupPlacement` / `VuePreviewXml` ; `VoletSourceFragment`
  passe à des lignes d'adaptateur (spans, `isSelected` / `isActivated`).
- **Risques** : lignes de Source plus longues que l'écran (tronquées pour l'instant, défilement horizontal si besoin,
  point O4) ; accessibilité des cibles denses (D8) ; couleurs dynamiques désactivées dans l'aperçu (déjà décidé ADR 0131).
- **Vérification** : Robolectric de cotes par layout contre `meas_ecrans_*.json`, parité `JetonsMaquetteTest` +
  `JetonsCalquesTest`, paires de captures maquette | app, clair et nuit (`PREVIEW_UI_ECRANS.md` § 12).

## Alternatives écartées

- Garder `Toolbar` + `ActionBar` et la surcharger par des styles : n'atteint pas les 52 dp, le sous-titre à 11 sp ni la
  pastille de mode ; surcharge fragile.
- `MaterialSwitch` / `MaterialButtonToggleGroup` : cotes différentes (52 × 32, boutons contournés) → écart visible.
- Laisser le moteur dessiner bandeau et toast : texte non sélectionnable, pas de boutons ni d'accessibilité.
- Lire les attributs de thème depuis le moteur : casse sa pureté (zéro Android UI) et son test JVM.
