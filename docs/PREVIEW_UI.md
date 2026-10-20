# Aperçu XML — spécification visuelle et fichiers de ressources

> **Statut** : normatif (ADR 0131, 0128, 0129, 0130). **Partie II** : `docs/PREVIEW_UI_ECRANS.md` (écran principal, palette, placement, menus, Arbre, Problèmes, Source). Remplace tout choix de style local du module
> `:feature:preview` qui s'en écarte.
> **Source de vérité visuelle** : `docs/mockups/apercu-xml.html` (maquette **v6** — celle du dépôt ; étiquette
> resynchronisée en 0.156.1 : elle disait v3). Quand ce document et la
> maquette divergent, **la maquette gagne** — et on corrige ce document.
> **Public** : l'agent d'implémentation. Tout ce qu'il faut pour reproduire l'écran **à l'identique**
> est ici ou dans les fichiers cités. Aucune valeur de design n'est à deviner.

---

## 1. La règle d'or

**L'écran Attributs (et le chrome de la feuille qui l'entoure) doit être indiscernable de la maquette**,
capture contre capture, en clair et en nuit. « Ressemble » ne suffit pas : les couleurs sont **exactes**
(même hexadécimal), les dimensions sont **exactes** (±1 dp d'arrondi de police), les polices sont
celles de la table du § 5.

Trois conséquences pratiques :

1. **On ne choisit jamais une valeur de style.** Chaque couleur, taille, marge, rayon et police vient
   d'une ligne de ce document, donc d'une ressource `apercu_*`.
2. **On ne construit jamais une vue en code** (`GradientDrawable`, `TextView(...)`, `setPadding(...)`).
   Chaque pièce visible est un layout XML (§ 9) habillé par un style (§ 6).
3. **Un test garde la parité** des jetons (§ 12) : si quelqu'un modifie une couleur d'un côté sans
   l'autre, la CI casse.

## 2. Ce qui est fourni

| Fichier | Rôle |
|---|---|
| `docs/PREVIEW_UI_ECRANS.md` | **Partie II** : spécification cotée de l'écran principal, de la palette, du placement, des menus et des onglets Arbre / Problèmes / Source (ADR 0134). |
| `docs/mockups/apercu-xml.html` | La maquette interactive (la référence). Mode clair/sombre = réglage du système. |
| `docs/mockups/ref/jetons-maquette.json` | Les 39 jetons de couleur, clair + nuit, extraits de la maquette (27 de l'écran Attributs + 12 des autres écrans). Sert au test de parité. |
| `docs/mockups/ref/meas_ecrans_clair.json`, `meas_ecrans_nuit.json`, `ecran-*.png` | Mêmes mesures et captures pour les écrans de la partie II. |
| `docs/mockups/ref/meas_clair.json`, `meas_nuit.json` | Styles **calculés** mesurés dans le navigateur (taille, padding, police, couleur, rayon, trait) pour chaque élément de l'écran. |
| `docs/mockups/ref/*.png` | 100+ captures de référence à l'échelle ×2 : la feuille complète, chaque section par vue (Button, TextView, EditText, ImageView, CheckBox, racine), barre d'outils, correctifs ; clair et nuit. |
| `docs/mockups/ref/cotes-chrome-feuille-clair.png`, `cotes-section-controles-clair.png` | Captures **annotées** (largeur × hauteur de chaque pièce). |
| `docs/mockups/ref/ressources/` | **Fichiers Android prêts à copier** : `values/` (attrs, colors, dimens, styles, themes, strings), `values-night/`, `color/`, `drawable/`, `layout/`. XML bien formé et références croisées vérifiées. |

Les ressources de référence ne sont **pas** dans un `res/` du dépôt : elles se **copient** dans
`feature/preview/src/main/res/` puis s'adaptent aux noms existants (§ 10), pour ne pas écraser du travail
en cours sans relecture.

## 3. Constat : pourquoi l'écran actuel ne ressemble pas à la maquette

Relevé au commit `6a9325b` (v0.135.0), `feature/preview/src/main/res/`. Chaque ligne est une cause visible.

| # | Élément | Maquette | Code actuel | Effet visible |
|---|---|---|---|---|
| 1 | Fond des **champs** | fond « fond » (`#F9F9FF` / `#111318`) **+ trait 1 dp** « trait », rayon 8 | `fond_champ` = `colorSecondaryContainer` (`#DDE1F9`), **sans trait** | Champs bleutés et pleins au lieu de blancs cassés encadrés |
| 2 | Conteneur des **segments** | surface 2 (`#EEEEF7`), rayon 9, segments **plats**, le coché est rempli primaire | `MaterialButton` **Outlined** par segment (un trait par bouton) | Rangée de boutons contournés au lieu d'une bascule |
| 3 | **Carte de section** | surface `#FFFFFF`, trait `#D6D5E0`, rayon 10, en-tête **30 dp** | `colorSurface` (`#F9F9FF`), `colorOutlineVariant` (`#C6C5D0`), en-tête **`minHeight 48dp`** | Cartes plus hautes (+18 dp chacune) et teintes décalées |
| 4 | **Étiquettes** de champ | **mono 11 sp / 500**, gris doux | `textAppearanceLabelSmall` (sans, 11 sp) | La « signature » technique de la maquette disparaît |
| 5 | **Titre** de la section | sans 12 sp / 600 | `textAppearanceLabelMedium` | Graisse et taille différentes |
| 6 | **Lecture seule** (`.kv`) | 2 colonnes `1fr | 1.1fr`, mono 12 sp | colonne fixe `160dp`, `LabelSmall` / `BodySmall` | Colonnes mal réparties, police fausse |
| 7 | **Écarts** | volet 8/10/14/10, cartes à 10 dp l'une de l'autre, contrôles à 9 dp | marges `12dp`/`8dp`, `16dp` latéraux dans chaque contrôle | Rythme vertical et alignement différents |
| 8 | **Valeurs en dur** | — | ~70 littéraux `dp` dans `controle_*.xml`, 30 layouts sur 44 en `textAppearanceLabel*` | Viole la règle 3 ; impossible à corriger globalement |
| 9 | **Thème** | jetons propres (`surface` blanc, `trait`, `texte doux`…) | thème de l'app (`colorSurface`, `colorOutlineVariant`, `colorSecondaryContainer`…) | Toute la feuille est décalée de quelques teintes |
| 10 | **Jetons existants** | `--warn #9A6700`, badge nuit `#3A2A66` plein | `preview_probleme_avertissement #B8860B` ; badge nuit **translucide** ; **pas de variante nuit** pour `preview_probleme_*` | Ambre et rouge faux, surtout en nuit |
| 11 | **Barre collante** Courants/Tous, correctifs, pastilles `app`/`tools`, ↺ | présents | absents | Fonctionnalités PE11 |
| 12 | **Volet** | un seul défilement | `ScrollView` + `LinearLayout` rempli en code | Coût en Tous (70+ contrôles) |

> Le retour propriétaire de PE9 demandait « mêmes thèmes/palettes que l'app » : c'est **conservé** — le
> nouveau thème `Theme.CodeIDE.Apercu` **hérite** de `Theme.CodeIDE` (mêmes couleurs de marque, mêmes
> hexadécimaux pour primaire, fond, erreur). Seuls les **neutres** de la maquette (surface blanche,
> trait, gris doux, accent bleu, violet « nouveau ») s'ajoutent, ce que l'app n'avait pas. Voir ADR 0131.

## 4. Principes de conversion maquette → Android

| Maquette (CSS) | Android | Remarque |
|---|---|---|
| `1px` de mise en page | `1dp` | La maquette est dessinée à 420 px de large ≈ 411 dp (Pixel). |
| `font-size: Npx` | `Nsp` | La typographie suit la police système de l'utilisateur. |
| `border: 1px` + `padding: p` (bordure **à l'extérieur** du padding) | padding de la vue = **p + 1 dp** | Le trait d'un `<shape>` est dessiné **à l'intérieur** de la vue. Les dimens `apercu_champ_pad_*` l'incluent déjà. |
| `border-bottom: 1px` d'un bandeau | `layer-list` (§ ressources `apercu_trait_bas_surface`) | Pas de `stroke` (il ferait le tour complet). |
| `line-height` ≠ 1 | `lineHeight` (**sans** `android:`) dans le style | Rétroporté par AppCompat/Material. Seulement où la maquette a un interligne explicite. |
| `font-weight: 500/600` | `sans-serif-medium` (sans) ; mono 600 → `textStyle=bold` ; mono 500 → normal | Aucun `textFontWeight` (API 28, alerte lint sous minSdk 26). |
| `letter-spacing: .08em` | `android:letterSpacing="0.08"` | Même unité (em). |
| `text-transform: uppercase` | `android:textAllCaps="true"` | |
| `box-shadow` de la feuille | `android:elevation="8dp"` | |
| `gap` flex/grid | `showDividers="middle"` + `drawable` d'espace, ou `layout_margin*` | Pas de marges doublées. |
| `flex-wrap: wrap` | `ChipGroup` (Material 1.14) | Aucune dépendance ajoutée. |
| `<select>` | `AppCompatAutoCompleteTextView` `inputType=none` + chevron | |
| `<input type=range>` accent violet | `Slider` Material, `thumbColor`/`trackColorActive` = violet | |
| `<datalist>` | `AppCompatAutoCompleteTextView` + adaptateur | |

### Les pièges qui font échouer la ressemblance (à vérifier un par un)

1. **`includeFontPadding`** : actif par défaut sur Android, absent du navigateur → chaque ligne est ~3 dp
   trop haute. Tout `TextView` de l'aperçu hérite de `Widget.CodeIDE.Apercu.Texte` (`includeFontPadding=false`).
2. **Minimums implicites** : `EditText` ≈ 48 dp, `MaterialButton` ≈ 40 dp, `Chip` ≈ 32 dp (+ zone tactile 48).
   Les styles `apercu` posent `minHeight` / `chipMinHeight` / `ensureMinTouchTargetSize=false` explicitement.
3. **Fond d'`EditText`** : retirer le soulignement natif (le style pose `android:background`).
4. **`MaterialButton` + `android:background`** : ne jamais ; utiliser `backgroundTint` / `strokeColor`.
5. **Insets Material** (`insetTop/Bottom` 6 dp) : mis à 0 dans `BoutonLien`.
6. **Couleurs dynamiques** : non appliquées à l'aperçu (§ 5, ADR 0131).
7. **`layout_width/height` dans un style** : ignorés — toujours dans le layout.
8. **Commentaires XML** : jamais de `--` à l'intérieur (document mal formé).

## 5. Couleurs — les 39 jetons

Définis dans `values/colors.xml` et `values-night/colors.xml`, exposés par **attributs de thème**
(`values/attrs.xml`) et câblés par `Theme.CodeIDE.Apercu` (`values/themes.xml`). Les layouts, drawables
et styles écrivent **`?attr/apercuXxx`**, jamais `@color/apercu_xxx` ni un hexadécimal.

| Attribut de thème | Ressource | Clair | Nuit | Maquette | Sert à |
|---|---|---|---|---|---|
| `apercuFond` | `apercu_fond` | `#F9F9FF` | `#111318` | `--bg` | fond des champs, texte du badge d'onglet |
| `apercuSurface` | `apercu_surface` | `#FFFFFF` | `#1A1E25` | `--surface` | feuille, cartes de section, barre collante |
| `apercuSurface2` | `apercu_surface2` | `#EEEEF7` | `#20252E` | `--surface2` | conteneur des segments, valeur en lecture seule, palette |
| `apercuTexte` | `apercu_texte` | `#1A1B21` | `#E2E1E9` | `--fg` | texte principal |
| `apercuTexteDoux` | `apercu_texte_doux` | `#5B5C66` | `#A3A3AE` | `--muted` | étiquettes, onglets inactifs, flèche, notes |
| `apercuTrait` | `apercu_trait` | `#D6D5E0` | `#2C323D` | `--line` | **tous** les traits 1 dp, poignée |
| `apercuPrimaire` | `apercu_primaire` | `#43559E` | `#BAC4FF` | `--primary` | segment coché |
| `apercuSurPrimaire` | `apercu_sur_primaire` | `#FFFFFF` | `#0C2264` | `--on-primary` | texte du segment coché |
| `apercuPrimaireConteneur` | `apercu_primaire_conteneur` | `#DCE1FF` | `#293D8F` | `--primary-c` | chip « pressé » |
| `apercuSurPrimaireConteneur` | `apercu_sur_primaire_conteneur` | `#001159` | `#DCE1FF` | `--on-primary-c` | texte du chip « pressé » |
| `apercuAccent` | `apercu_accent` | `#1E6EC8` | `#7AB8F5` | `--accent` | **indicateur d'onglet**, liens, classe du fil d'Ariane |
| `apercuAccentDoux` | `apercu_accent_doux` | `#241E6EC8` | `#2E7AB8F5` | `--accent-soft` | ondulation des liens |
| `apercuSucces` | `apercu_succes` | `#2E7D32` | `#81C995` | `--ok` | succès |
| `apercuAvert` | `apercu_avert` | `#9A6700` | `#FDD663` | `--warn` | pastille d'onglet, pastille `app`, avertissements |
| `apercuErreur` | `apercu_erreur` | `#BA1A1A` | `#FFB4AB` | `--err` | trait et message d'erreur |
| `apercuErreurConteneur` | `apercu_erreur_conteneur` | `#FFDAD6` | `#93000A` | `--err-c` | fond d'erreur |
| `apercuMarge` | `apercu_marge` | `#D9822B` | `#F0A35A` | `--margin` | calque marges |
| `apercuRemplissage` | `apercu_remplissage` | `#2E8B4B` | `#6FC47A` | `--padding` | calque padding |
| `apercuNouveau` | `apercu_nouveau` | `#7C3AED` | `#C4A8FF` | `--new` | **violet « nouveau/modifiable »** : badge, drapeau coché, curseur, bouton |
| `apercuNouveauFond` | `apercu_nouveau_fond` | `#EDE5FF` | `#3A2A66` | `--new-c` | fond du badge, drapeau coché |
| `apercuSurNouveau` | `apercu_sur_nouveau` | `#FFFFFF` | `#1B0B3D` | `--on-new` | texte du bouton violet |
| `apercuNouveauDoux` | `apercu_nouveau_doux` | `#247C3AED` | `#33C4A8FF` | `--new-soft` | halo (bulle, curseur) |
| `apercuCorrectifFond` | `apercu_correctif_fond` | `#F6F1E8` | `#2E2F2B` | mélange ambre 9 % / surface | carte de correctif |
| `apercuCorrectifTrait` | `apercu_correctif_trait` | `#BBA47B` | `#8A7C4E` | mélange ambre 45 % / trait | trait de la carte de correctif |
| `apercuAvertDoux` | `apercu_avert_doux` | `#339A6700` | `#33FDD663` | ambre 20 % | pastille `app` |
| `apercuDamierCase` | `apercu_damier_case` | `#21808096` | `#21808096` | `--checker` | cases du damier du canvas (8 dp) |
| `apercuEpingleAvert` | `apercu_epingle_avert` | `#B8860B` | `#B8860B` | `--pin-warn` | épingle d'avertissement (calque) |
| `apercuEpingleErreur` | `apercu_epingle_erreur` | `#BA1A1A` | `#BA1A1A` | `--pin-err` | épingle d'erreur (calque) |
| `apercuBornes` | `apercu_bornes` | `#801E6EC8` | `#801E6EC8` | `--bounds` | calque bornes |
| `apercuAvertTag` | `apercu_avert_tag` | `#389A6700` | `#38FDD663` | `--tag-wr` | tag « dépendance absente » (palette) |
| `apercuEcranTrait` | `apercu_ecran_trait` | `#2E000000` | `#2E000000` | `--screen-ring` | liseré extérieur de l'écran rendu |
| `apercuEcranOmbre` | `apercu_ecran_ombre` | `#38000000` | `#38000000` | `--screen-shadow` | ombre de l'écran rendu |
| `apercuSurCalque` | `apercu_sur_calque` | `#FFFFFF` | `#FFFFFF` | `--on-overlay` | texte des étiquettes et liseré des épingles |
| `apercuSourceInsere` | `apercu_source_insere` | `#422E7D32` | `#4281C995` | `--ins` | ligne de Source qui vient d'être insérée |
| `apercuSourceVue` | `apercu_source_vue` | `#4C1E6EC8` | `#4C7AB8F5` | `--hl2` | plage de la vue dans la Source |
| `apercuMargeFond` | `apercu_marge_fond` | `#61D9822B` | `#61F0A35A` | `--margin-fill` | aplat du calque marges |
| `apercuRemplissageFond` | `apercu_remplissage_fond` | `#612E8B4B` | `#616FC47A` | `--padding-fill` | aplat du calque padding |
| — | `apercu_damier_a` / `_b` | `#BBBBBB` / `#FFFFFF` | idem | damier | fond de la pastille de couleur |

Les 12 jetons des lignes ajoutées (de `apercuDamierCase` à `apercuRemplissageFond`) viennent des autres écrans (partie II). Les trois jetons « mélange » ne sont pas dans le CSS (calculés par `color-mix`) : leurs valeurs sont
**mesurées au rendu** et figées. Voir `jetons-maquette.json` (`"calcule": true`).

### Rôles Material 3 recâblés par `Theme.CodeIDE.Apercu`

Pour que chips, barre d'outils, boutons et menus Material soient eux aussi conformes **sans code** :

| Rôle M3 | → jeton | Pourquoi |
|---|---|---|
| `android:colorBackground`, `windowBackground` | `apercu_fond` | |
| `colorSurface` | `apercu_surface` | blanc de la maquette (≠ `#F9F9FF` de l'app) |
| `colorOnSurface` | `apercu_texte` | |
| `colorOnSurfaceVariant` | `apercu_texte_doux` | |
| `colorOutline`, `colorOutlineVariant` | `apercu_trait` | bordure des chips (`.chip{border:1px var(--line)}`) |
| `colorSecondaryContainer`, `colorSurfaceVariant` | `apercu_surface2` | |
| `colorPrimary` … `colorOnPrimaryContainer` | jetons primaires | mêmes hexadécimaux que l'app (parité testée) |
| `colorError`, `colorErrorContainer` | jetons d'erreur | |

À vérifier à l'œil après application : les composants qui lisent `colorSurfaceContainer*` (menus,
`MaterialToolbar`) ; ajouter ces rôles au thème si une teinte dérive.

### Migration des jetons existants `preview_*`

| Existant | Action |
|---|---|
| `preview_probleme_erreur` / `_avertissement` / `_neutre` | **Supprimer** → `?attr/apercuErreur` / `apercuAvert` / `apercuTexteDoux` (ajoute la variante nuit manquante ; corrige l'ambre `#B8860B` → `#9A6700`). |
| `preview_section_badge_texte` / `_fond` | **Supprimer** → `apercuNouveau` / `apercuNouveauFond` (corrige le fond nuit : `#3A2A66` plein, pas `#33C4A8FF`). |
| `preview_bulle_fond` / `_halo` / `_icone`, `preview_palette_icone` | **Alias** : `@color/apercu_nouveau`, `apercu_nouveau_doux`, `apercu_sur_nouveau`, `apercu_nouveau`. |
| `preview_menu_fond` | **Conserver** tel quel (translucidité demandée par le propriétaire, écart documenté PREVIEW.md § 3.4.1). |

## 6. Typographie

Police : **sans = police système** (Roboto), **mono = `monospace`**. **Aucune police embarquée**
(ADR 0133) : la maquette charge des polices Web seulement pour sa page de présentation ; l'écart de
glyphes est assumé, **les tailles, poids et espacements sont exacts**.

| Style (`TextAppearance.CodeIDE.Apercu.…`) | Police | Taille | Poids | Autre | Couleur | Utilisé par |
|---|---|---|---|---|---|---|
| `FilAriane` | mono | 12 sp | 500 | — | texte doux (classe : accent) | fil d'Ariane sous la poignée |
| `Onglet` | sans | 13 sp | 500 | — | texte doux → texte si sélectionné | onglets |
| `BadgeOnglet` | mono | 10 sp | 500 | — | `apercuFond` sur fond ambre | pastille de compte |
| `TitreGroupe` | mono | 10 sp | 600 (gras) | MAJUSCULES, interlettrage 0.08 | texte doux | « MODIFIER · BUTTON », titres de groupes lecture seule |
| `SectionTitre` | sans | 12 sp | 600 | — | texte | titre de carte, flèche ▾ ▴ |
| `Badge` | mono | 10 sp | 500 | — | violet | « n définis » |
| `Etiquette` | mono | 11 sp | 500 | — | texte doux | étiquette de champ, étiquette de dimension |
| `Saisie` | sans | 13 sp | 400 | hint = texte doux | texte | champs, recherche, liste |
| `SaisieMono` | mono | 12 sp | 400 | — | texte | champs numériques compacts |
| `LectureSeule` | mono | 12 sp | 400 | interligne 17 sp | texte | valeur `@string/…` |
| `Segment` | sans | 12 sp | 500 | — | texte doux → sur-primaire si coché | segments |
| `Drapeau` | mono | 11 sp | 500 | — | texte doux → violet si coché | drapeaux |
| `Note` | sans | 11 sp | 400 | interligne 15 sp | texte doux | notes, messages |
| `CorrectifTitre` / `CorrectifCorps` | sans | 12 sp | 600 / 400 | interligne 16 sp | texte / texte doux | correctifs |
| `BoutonLien` | sans | 12 sp | 500 | — | accent | « Palette », « Ajouter », actions |
| `BoutonNouveau` | sans | 12 sp | 600 | — | sur-violet | bouton du correctif |
| `Pastille` | mono | 9 sp | 500 | — | texte doux (`app` ambre, `tools` violet) | `app` / `tools` |
| `KvCle` / `KvValeur` | mono | 12 sp | 400 | interligne 18 sp | texte doux / texte | lecture seule |

## 7. Dimensions

Toutes dans `values/dimens.xml`, une ligne par valeur, **avec le sélecteur CSS source en commentaire**.
Aucun layout n'écrit un littéral `dp` (sauf `0dp` de poids et `1dp` de trait déjà tokenisé). Les cotes
qui structurent l'écran :

| Pièce | Cote | Jeton(s) |
|---|---|---|
| Feuille | coins hauts **18**, trait haut 1, ombre → élévation 8 | `apercu_feuille_*` |
| Poignée | **36 × 4**, rayon 2 ; padding 8 / 14 / 6, écart 6 ; hauteur totale **38.4** | `apercu_poignee_*` |
| Onglets | hauteur **37** (36 + trait 1) ; padding de l'onglet 10 ; indicateur **2** ; écart 2 | `apercu_onglet*` |
| Volet | padding **8 / 10 / 14 / 10** | `apercu_pane_pad_*` |
| Barre collante | **418 × 52.6** ; padding 8/10 ; recherche **269.8 × 35.6** (rayon 10) ; bascule **120.2 × 30** | `apercu_barre_*`, `apercu_recherche_*` |
| Cartes | rayon **10** ; écart entre cartes **10** | `apercu_section_rayon`, `apercu_gap_sections` |
| En-tête de carte | **396 × 30** ; padding 9 / 11 ; écart 8 | `apercu_section_entete_*` |
| Corps de carte | padding **2 / 11 / 11 / 11** ; écart entre contrôles **9** | `apercu_section_corps_*`, `apercu_gap_controles` |
| Badge « n définis » | **60 × 16** pour « 3 définis » ; padding 3 / 6 ; rayon 5 | `apercu_badge_defini_*` |
| Champ | **374 × 33.6** ; rayon 8 ; padding 10/8 + trait | `apercu_champ_*` |
| Étiquette → champ | écart **4** | `apercu_gap_etiquette` |
| Segments | conteneur **374 × 30**, rayon 9, padding 2 ; segment **26** de haut, rayon 7, padding 9 | `apercu_segment*` |
| Drapeaux | **25** de haut, rayon 7, padding 8, écart 5 | `apercu_drapeau*` |
| Couleur | pastille **34 × 34** (rayon 8) ; écart 6 | `apercu_pastille_couleur*` |
| Palette | pastilles **28**, écart 6, padding 8, rayon 8 | `apercu_palette_*` |
| Curseur | champ **72** de large ; écart 10 | `apercu_curseur_*` |
| Dimension | étiquette **56** ; champ **64 × 28** ; écart 8 | `apercu_dim_*`, `apercu_champ_num_*` |
| Côtés | 4 champs, écart **6**, chacun ≈ 89 × 33.6 | `apercu_cotes_gap` |
| Correctif | **398 × 68.6** ; padding 9/11 ; rayon 10 ; écart 10 | `apercu_correctif_*` |
| Pastille `app`/`tools` | mono 9, padding 2/5, rayon 4 | `apercu_ns_*` |
| Lecture seule `.kv` | colonnes **1fr / 1.1fr**, écart 10 ; ligne 2 | `apercu_kv_*` |

> **Cibles tactiles** : la maquette est dense (segments 26 dp, en-tête 30 dp). Le minimum retenu est
> **24 × 24 dp** (WCAG 2.2, critère 2.5.8) — pas les 48 dp de `touch_target_min`, qui reste la règle
> ailleurs dans l'app. Écart borné au **volet Attributs**, voir ADR 0133. Le bouton ↺ (25 × 20 dans la
> maquette) reçoit `minWidth/minHeight 24dp`.

## 8. Anatomie de l'écran Attributs

Structure, de haut en bas, telle que rendue par la maquette (vue `Button`, mode « Courants ») :

```
feuille (fond apercu_feuille_fond, élévation 8)
├── poignée  36×4 + fil d'Ariane « ConstraintLayout › Button @+id/btn_connexion »
├── onglets  Arbre · Attributs(sélectionné, indicateur accent 2 dp) · Problèmes ③ · Source
└── volet Attributs  (RecyclerView, padding 8/10/14/10, défile)
    ├── 0  titre « MODIFIER · BUTTON »                        entete_editeur
    ├── 1  barre collante [ Rechercher un attribut | Courants·Tous ]   barre_outils_attributs   (épinglée en haut)
    ├── 2  correctifs rapides (0..n cartes ambre)             item_correctif  (+ titre « CORRECTIONS »)
    ├── 3… cartes de section (2 premières ouvertes)           carte_section   (écart 10)
    │       ├── en-tête : titre · badge « n définis » · ▾/▴
    │       └── corps : contrôles (écart 9) — voir § 9
    ├── N  bouton « Afficher les 66 autres attributs de Button »   bouton_autres_attributs (Courants seulement)
    └── N+1 trait + lecture seule en groupes (Identité, Disposition, Texte, Mesuré)   item_attribut_groupe / item_attribut
```

**Ordre des sections** pour une vue : celui de `CatalogueAttributs` (propres à la classe → parent →
génériques), qui reproduit la maquette. **Par défaut** : les 2 premières ouvertes, sauf *Identité* fermée.
Une section ouverte à la main le reste (état par titre).

Fil d'Ariane : classe du parent en texte doux, `›` en texte doux, classe courante en **accent**, puis
`@+id/…` en texte doux — un seul `TextView` avec `ForegroundColorSpan`.

Onglet « Problèmes » : `BadgeDrawable` (couleur = `apercuAvert`, texte = `apercuFond`, mono 10) **ou** pastille
`apercu_badge_onglet_fond` ; la maquette est une pastille ambre arrondie de 16 × 14.

## 9. Un contrôle = un layout = un style

Tous les fichiers sont dans `docs/mockups/ref/ressources/layout/` avec, en en-tête, **le sélecteur CSS
source et les cotes**. Correspondance contrôle (kind) → layout :

| Contrôle (`ControleAttribut`) | Layout | Pièces |
|---|---|---|
| Identifiant, Texte, Nombre | `controle_texte` | `entete_champ` + `saisie_texte` **ou** `item_lecture_seule` + note + message |
| Choix, TriEtat | `controle_segments` | `entete_champ` + `ChipGroup` de `item_segment` |
| **Liste** | `controle_liste` | `entete_champ` + liste déroulante |
| **Drapeaux** | `controle_drapeaux` | `entete_champ` + `ChipGroup` de `item_drapeau` |
| **Couleur** | `controle_couleur` | pastille 3 couches + hex + « Palette » ; palette = `item_pastille_palette` |
| **Ressource** | `controle_ressource` | champ à suggestions + message |
| **Curseur** | `controle_curseur` | `Slider` violet + champ 72 dp + note |
| **Ratio** | `controle_ratio` | 2 champs « : » + drapeaux prédéfinis + verrou W/H + note |
| **TypeSaisie** | `controle_liste` + `controle_drapeaux` | liste de base + modificateurs |
| **Coins**, Cotes | `controle_cotes` | 4 champs |
| **ReferenceId** | `controle_liste` | liste `@id/…` |
| **ActionRapide** | `controle_action` | `ChipGroup` de `item_action` |
| Dimensions | `controle_dimensions` + `item_dimension` | une rangée par axe |
| Contraintes (lecture) | `controle_contraintes_lecture` | `.ro` multi-lignes ; l'édition garde `controle_contrainte` |
| **Attribut libre** | `controle_attribut_libre` + `item_attribut_libre` | liste + rangée d'ajout |
| (tous) | `entete_champ` | étiquette · pastille `app`/`tools` · ↺ |

### États à reproduire (tous visibles dans les captures)

| État | Rendu |
|---|---|
| **Erreur de validation** | `apercu_champ_fond_erreur` (trait `apercuErreur`) + `message_controle` en `apercuErreur`, `accessibilityLiveRegion=polite` |
| **Avertissement** (ex. ressource absente, `app:`) | message en `apercuAvert`, jamais bloquant |
| **Désactivé** (curseur sans effet, modificateur de saisie) | `alpha 0.4` + note d'explication |
| **Coché** (segment / drapeau) | voir styles `Segment` / `Drapeau` (sélecteurs de couleur) |
| **Défini** | le bouton ↺ apparaît ; le badge de section compte les définis |
| **Section vide** (« Autres attributs ») | masquée tant qu'il n'y a rien |
| **Nuit** | aucune différence de layout : tout passe par les attributs de thème |

## 10. Organisation et conventions des fichiers XML

Règles à appliquer à **tout** fichier ajouté ou refondu de `:feature:preview` :

1. **Nommage** (français, snake_case, préfixes du module) : `controle_*` (un contrôle), `item_*` (une
   ligne d'adaptateur ou une pièce répétée), `carte_*`, `barre_*`, `entete_*`, `volet_*`, `popover_*`,
   `fond_*`/`apercu_*_fond` (drawables de fond), `ic_*` (icônes). Styles : `Widget.CodeIDE.Apercu.*` et
   `TextAppearance.CodeIDE.Apercu.*`. Jetons : `apercu_*`.
2. **Un contrôle = un layout.** Pas de vue construite en code ; le liage ne fait que gonfler, remplir,
   brancher (`ViewBinding`).
3. **Aucun littéral** de couleur, `dp`, `sp` ou texte dans un layout : `?attr/apercu*`, `@dimen/apercu_*`,
   `@style/…`, `@string/…`. Exceptions : `0dp` (poids), `match_parent`/`wrap_content`, `1dp` via
   `apercu_trait_epaisseur`, `24dp` des zones tactiles minimales.
4. **Styles, pas attributs répétés** : tout jeu d'attributs répété 2 fois devient un style.
5. **Thème** : `PreviewActivity` porte `android:theme="@style/Theme.CodeIDE.Apercu"` ; tout fragment/popup
   gonflé hors de l'activité utilise un `ContextThemeWrapper` de ce thème.
6. **Clair/nuit** : uniquement par les jetons (`values-night/colors.xml`). Jamais de layout `-night`.
7. **En-tête de fichier** : commentaire en français — rôle, sélecteur CSS miroir, cotes, états.
   Pas de `--` à l'intérieur d'un commentaire XML.
8. **Chaînes** : `values/strings.xml` **et** `values-en/strings.xml` (le français est la langue source).
   Libellés, `hint`, `contentDescription`, **et** glyphes (↺ ▾ ▴ :) — tout est une chaîne.
9. **Accessibilité** : `contentDescription` sur tout contrôle sans texte ; `importantForAccessibility=no`
   sur les glyphes décoratifs ; `accessibilityLiveRegion` sur les messages ; `labelFor` entre
   étiquette et champ si le liage le permet.
10. **RTL** : `Start`/`End`, jamais `Left`/`Right`.
11. **Pas de `tools:` décoratif** qui masque un écart (pas de `tools:ignore` pour faire taire lint — règle 8).

## 11. Où vit quoi (module `:feature:preview`)

```
res/
├── values/{attrs,colors,dimens,styles,themes,strings}.xml
├── values-night/colors.xml
├── values-en/strings.xml
├── color/        apercu_onglet_texte, apercu_segment_{fond,texte}, apercu_drapeau_{fond,texte,trait}
├── drawable/     apercu_*_fond, apercu_poignee, apercu_trait_bas*, apercu_espace_*, apercu_pastille_palette, …
└── layout/       (§ 9) + volet_attributs
```

Les `fond_*.xml` existants dont le rôle est couvert par un `apercu_*_fond` sont **supprimés** (pas de
doublon) ; ceux qui restent (popovers, menus, bulle) migrent leurs couleurs vers `?attr/apercu*` au fil
de PE11b (§ 14).

## 12. Recette de fidélité (obligatoire avant de fermer un chantier)

### 12.1 Test de parité des jetons (JVM, pur, en CI)

`JetonsMaquetteTest` (module `:feature:preview`, `src/test`) :

1. lit `docs/mockups/ref/jetons-maquette.json` ;
2. lit `values/colors.xml` et `values-night/colors.xml` (chemins relatifs au module, `File`) ;
3. **échoue** si une couleur diffère (comparaison ARGB exacte), si un jeton du JSON manque dans
   `colors.xml`, ou si un `apercu*` de `attrs.xml` n'est pas câblé dans `themes.xml` ;
4. vérifie aussi que `colorPrimary`, `colorOnPrimary`, `colorPrimaryContainer`, `colorError`
   de `core:ui` (clair **et** nuit) sont égaux aux jetons primaires/erreur de la maquette.

Pas d'Android, pas de Robolectric : `kotlin.test` + `javax.xml`. Un échec nomme le jeton fautif.

### 12.2 Test de gonflage et de cotes (Robolectric)

Pour chaque layout de § 9 : gonfler sous `Theme.CodeIDE.Apercu`, mesurer à largeur 398 dp (volet 418 − 20)
densité 1, et comparer à `meas_clair.json` : **hauteur ±1 dp**, `paddingStart/Top/End/Bottom`, taille de police,
couleur de fond/texte résolue (clair **et** nuit via `Configuration.uiMode`). Tableau minimal : carte (en-tête
30), champ (33.6), segment (26), drapeau (25), pastille couleur (34), correctif (≥ 68), barre collante (52.6),
recherche (35.6). Aucune valeur n'est codée dans le test : elles viennent du JSON.

**Tolérance interligne (±3 dp)** : les cotes `sec2sum` (30) et `fldro` (32.8) mesurent une boîte
dont la maquette force `line-height: 1` (ou 1.4) SOUS l'ampleur naturelle de la police — la boîte
de police Android ne descend pas sous son ascension+descente (14 px pour 12 sp), même avec
`android:lineHeight` posé : l'écart résiduel est un artefact de police (Roboto/mono contre IBM
Plex), pas de layout. Ces deux cotes tolèrent ±3 dp (codé dans `CotesMaquetteTest`) ; les autres
restent à ±1 dp. Le test gonfle avec l'inflateur Material de production (les `MaterialTextView`
lisent l'interligne de leur apparence) et tourne en mode graphique NATIVE (les métriques LEGACY
de Robolectric sont fausses).

### 12.3 Revue visuelle (appareil ou émulateur, `agent-browser` pour la maquette)

1. Ouvrir la **maquette** dans `agent-browser`, vue `Button`, onglet Attributs, feuille agrandie ; capturer
   (clair puis nuit) → comparer aux `ref/feuille-attributs-bouton-courants-*.png`.
2. Ouvrir l'**application** sur la même vue avec les mêmes sections ouvertes ; capturer au même zoom.
3. Superposer ou alterner. **Aucun écart visible** accepté sur : couleurs, traits, rayons, polices
   (mono/sans), hauteurs, espacements, ordre des éléments. Écarts tolérés : glyphes de police (Roboto
   contre la police de la maquette), antialiasing, ±1 dp.
4. Répéter pour `TextView`, `EditText`, `ImageView`, `CheckBox`, racine : captures `ref/<classe>-<section>-*.png`.
5. Joindre les paires de captures (maquette | app) au compte rendu de chantier.

### 12.4 Grille de contrôle (à cocher dans le CHANGELOG du chantier)

- [ ] Aucune `GradientDrawable`, `TextView(...)`, `setPadding(...)`, `setBackground(...)` construits pour l'écran
- [ ] `grep -nE 'android:(textColor|background)="#' res/layout` → vide ; idem pour `[0-9]+dp` hors tokens
- [ ] Tous les `TextView` héritent de `Widget.CodeIDE.Apercu.Texte` (`includeFontPadding=false`)
- [ ] `JetonsMaquetteTest` vert ; Robolectric des cotes vert
- [ ] Captures clair + nuit jointes, paires comparées
- [ ] En-têtes de layout à jour (sélecteur CSS + cotes)
- [ ] `lintDebug` sans nouvel avertissement ; aucun `@Suppress`/`tools:ignore` ajouté

## 13. Autres zones du chrome

Barre d'application, puces, canvas et calques, HUD, bandeau, toast, bulle, palette, placement, menus,
onglets Arbre / Problèmes / Source : **tout est spécifié, coté et accompagné de ses ressources dans la partie II**,
`docs/PREVIEW_UI_ECRANS.md` (ADR 0134). Les jetons, styles et règles de conversion de ce document s'y appliquent
sans changement.

## 14. Plan de reprise (PE11 — voir le brief `PE11-attributs-brief.md`)

| Chantier | Contenu |
|---|---|
| **PE11a** | Jetons, attributs, thème `Theme.CodeIDE.Apercu`, migration `preview_*`, `JetonsMaquetteTest`. Aucun changement d'écran visible hors teintes. |
| **PE11b** | **Refonte visuelle** de l'existant à l'identique : styles, drawables, layouts (carte, champ, segments, étiquettes, lecture seule), `RecyclerView`, barre collante, en-tête/onglets/poignée ; suppression des littéraux ; Robolectric des cotes ; captures. |
| **PE11i** | **Écran principal** : barre du haut, pastille de mode, puces, canvas (damier, calques), HUD (barre d'édition, état, zoom), bandeau et toast en vues, jetons injectés dans le moteur (`JetonsCalques`). |
| **PE11j** | **Bulle, palette, ghost, placement par popup, menus** appareil / calques / cible / plus. |
| **PE11k** | **Onglets Arbre, Problèmes, Source** (rangées, badges, spans, surlignages). |
| **PE11l** | **Recette finale** : cotes Robolectric de tous les écrans, paires de captures, grille de contrôle, mise à jour de la maquette dans le dépôt. |
| PE11c → PE11h | Catalogue et nouveaux contrôles (ancien PE10a–f du brief), **chacun livré avec son layout de § 9 et sa capture**. |

Un chantier PE11c+ **ne se ferme pas** tant que son contrôle n'est pas indiscernable de sa capture
`ref/*.png` (§ 12.3).
