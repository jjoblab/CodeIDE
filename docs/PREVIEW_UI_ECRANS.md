# Aperçu XML — spécification visuelle des écrans (partie II)

> **Statut** : normatif (ADR 0131 à 0130). Complète `PREVIEW_UI.md` (partie I : jetons, thème, typographie,
> écran Attributs) pour **l'écran principal, la palette, le placement, les menus, et les onglets Arbre,
> Problèmes et Source**.
> **Source de vérité visuelle** : `docs/mockups/apercu-xml.html` (maquette **v6** — resynchronisée par le
> redimensionnement de PE11l ; l'étiquette disait v5). Si ce document et la maquette
> divergent, **la maquette gagne**.
> **Public** : l'agent d'implémentation. Rien n'est à deviner : chaque valeur est une ressource `apercu_*`
> (dossier `docs/mockups/ref/ressources/`) ou une ligne d'un tableau ci-dessous.

---

## 0. Ce qui est dans la maquette et ne se porte PAS

La page de la maquette contient des éléments qui servent la **revue**, pas le produit. Ne les reproduisez pas.

| Élément de la maquette | Pourquoi on l'ignore |
|---|---|
| Cadre « téléphone » (rayon 28, ombre 0 18 50, trait 1) | C'est l'écran réel de l'appareil. |
| Colonne de droite : légende des calques, « À essayer », tableaux, constats | Texte de revue. |
| Étiquettes `Refonte` / `Nouveau` / `Existant` (`.tag.rework`, `.tag.new`, `.tag.old` dans les **menus** et la légende) | Annotations de revue. Dans la palette, `Material` et `dépendance absente` sont en revanche **de vrais tags** (dépendance requise). |
| Menu ⋮ « Simuler un état du panneau » | Simulateur de la maquette. Dans l'app, ⋮ ouvre le menu « Choisir le fichier… » (ancien menu d'options). |
| Valeurs d'exemple : « 142 ms », « 9 vues », « Pixel 4 », `activity_login.xml` | Données de démonstration ; l'app affiche les vraies. |
| Bouton `phone-cap` « PreviewActivity · écran dédié » | Légende de la maquette. |

## 1. Règles propres aux écrans (en plus de celles de la partie I)

1. **La bordure CSS est extérieure au padding ; le trait d'un drawable Android est intérieur.** Les dimensions
   `*_pad_*` de `dimens_ecrans.xml` ajoutent déjà 1 dp quand la maquette a une bordure (« trait inclus »).
2. **Les ombres sont des élévations** (`android:elevation`), pas des drawables : 4 dp pour le HUD, 8 dp pour
   la bulle et les popovers, 10 dp pour le ghost. Seule l'ombre légère des boutons sélectionnés de `.mode` /
   `.pos` est dessinée (1 dp, `apercu_mode_palette_bouton_fond`).
3. **Les popovers sont des vues de l'activité**, jamais des fenêtres (le dépôt par glisser doit traverser). Leur
   ordre d'empilement suit la maquette : feuille 5 < bulle 6 < toast 7 < popover 8.
4. **Les parties dessinées par le moteur** (`:preview:xml`, zéro Material) reçoivent leurs couleurs par un
   objet pur de jetons (§ 11), jamais par des constantes.
5. **Aucun `Toast` système** : le toast est la vue `toast_apercu` (§ 3.6).
6. **Les écarts voulus par le propriétaire restent** (PREVIEW.md § 3.4.1 et § 3.4.2) : menus translucides et
   pastilles colorées, appbar 52 dp avec titre 14 sp, volet déployé à 48 %, mode Plan sans libellés, onglets en
   RecyclerView. Chacun est signalé « ÉCART VOULU » où il intervient.

## 2. Constat : pourquoi ces écrans ne ressemblent pas à la maquette

Relevé au commit `6a9325b` (v0.135.0). « Chantier » renvoie à `PE11-attributs-brief.md`.

| # | Élément | Maquette | Code actuel | Chantier |
|---|---|---|---|---|
| E1 | **Pastille de mode** Design / Plan / Les deux | pilule pleine « surface 2 », padding 3, boutons 26 dp sans trait, coché = primaire plein | `MaterialButtonToggleGroup` de 3 `OutlinedButton` (un trait par bouton, coins carrés au milieu, texte 11 sp) | PE11i |
| E2 | **Puces** de réglages | pilule 30 dp, trait 1 dp, texte 12 / 500 ; appuyée = conteneur primaire **sans trait** | `Chip.Assist` / `Chip.Filter` M3 par défaut : coins **8 dp**, 32 dp, coche visible sur les filtres, espacement 4 dp au lieu de 6 | PE11i |
| E3 | **Barre du haut** | 3 traits : bouton rond 36, titre mono + sous-titre, trait bas 1 dp | `MaterialToolbar` (zone de navigation M3), pas de trait bas | PE11i |
| E4 | **Trait bas des puces** | 1 dp « trait » | aucun | PE11i |
| E5 | **Damier** | cases de **8 dp**, base « surface 2 » + cases `apercuDamierCase`, **clair et nuit** | cases de **16 dp**, couleurs fixes `#E8E8EC` / `#F6F6F9` (clair même en nuit) | PE11i |
| E6 | **Sélection** | trait 2 dp accent `#1E6EC8`, fond accent doux, poignées **carrées** 9 dp (fond surface, trait 2 accent, rayon 2), étiquette rectangle rayon 4, mono 11 | couleur `colorPrimary` (indigo), alpha 0x30, poignées **rondes** r = 4, étiquette **pilule**, 12 dp | PE11i |
| E7 | **Parent / frères / bornes** | parent 1,5 dp pointillé accent à 75 % ; frères **bord** 1 dp accent à 35 % ; bornes 1 dp `apercuBornes` | parent `textColorSecondary` ; frères **remplis** (aplat) à 9 % de `colorPrimary` | PE11i |
| E8 | **Marges / padding** | marges `apercuMargeFond` + liseré pointillé 1 dp `apercuMarge` ; padding `apercuRemplissageFond` | mêmes teintes **claires** (`#D9822B`, `#2E8B4B`) mais **constantes** : les variantes nuit (`#F0A35A`, `#6FC47A`) et l'alpha 38 % ne sont pas appliqués | PE11i |
| E9 | **Épingles** | 18 dp, liseré blanc 2 dp, ambre `#B8860B` / rouge `#BA1A1A` (jetons `apercuEpingle*`) | constantes `COULEUR_PIN_*` dans le moteur | PE11i |
| E10 | **Pastille d'état** | **pilule** (rayon 999) surface + trait, padding 6 / 12, **mono 11**, point 7 dp | coque `fond_barre_edition` (**rayon 14**), `LabelMedium` sans, point 8 dp | PE11i |
| E11 | **HUD de zoom** | 36 × 34 par bouton, rayon 10, **mono 13**, coque padding 4 | `MaterialButton` TextButton (min 40 dp, insets Material) : colonne trop haute et trop large | PE11i |
| E12 | **Barre d'édition** | boutons **34** dp, icône 18, séparateur marges 4 / 3, 4 dp de padding | boutons **40** dp (`IconButton`), séparateur 6 / 2 / 4 | PE11i |
| E13 | **Bandeau d'erreur** | **vue** : fond « erreur conteneur », texte « erreur » 12 sp, trait bas, boutons « Ligne n » et « Détails » | **dessiné par le moteur** : rouge plein `#B3261E`, texte blanc 13, 36 dp, **sans boutons** | PE11i |
| E14 | **Toast** | pastille inversée haut-centre, fondu 200 ms | `Toast` système (placement et style du système) | PE11i |
| E15 | **Bulle** | carré violet **50 dp** + halo 4 dp **à l'extérieur** | trait de 4 dp **à l'intérieur** de 50 dp : le violet ne fait que **42 dp** (l'icône, elle, est conforme ; trait 1,8 au lieu de 2) | PE11j |
| E16 | **Palette** : coque et espacements | opaque, padding 12, espacement 10, haut 8, bas 12, **hauteur max 600** | `LinearLayout` sans espacement uniforme (marge haute 8, pas de marge basse), **hauteur non bornée** : la grille peut déborder de l'écran | PE11j |
| E17 | **Mode d'insertion** | deux boutons, le sélectionné en **surface + ombre légère** | `fond_mode_palette` existe mais **aucun layout ne l'utilise** : le mode sélectionné n'a **aucun fond visible** | PE11j |
| E18 | **Recherche de la palette** | style commun Recherche (fond « fond », trait, rayon 10, 36 dp) | `TextInputEditText` fond `colorSurfaceContainerHighest`, sans trait ni rayon | PE11j |
| E19 | **Puces de catégories** | `Chip` pilule 28 dp | `MaterialButton` outlined avec insets | PE11j |
| E20 | **Tuiles** | 3 colonnes, **espacement 8 dp**, fond « fond » + trait 1, rayon 12, nom 12 / 600, classe mono 10,5, tag en bas | **aucun espacement** (pas d'`ItemDecoration`), fond `colorSurfaceContainerHighest` | PE11j |
| E21 | **Ghost** | pilule violette, rayon 12, icône 18, texte 12 / 600 + « → cible » | pilule violette conforme, mais **icône de la bulle** au lieu de celle du widget, pas de « → cible » | PE11j |
| E22 | **Placement** : mini-arbre | **mêmes rangées que l'arbre** (monogramme surface 2, classe, **id**), référence en **accent doux** | monogramme **violet**, id **masqué** (`visibility=gone`), fond de sélection **non câblé** dans le layout | PE11j |
| E23 | **Placement** : positions | trois boutons dans un conteneur « surface 2 », sélectionné en surface + ombre | `TextButton` Material, `fond_position_placement` non référencé | PE11j |
| E24 | **Placement** : résumé et bouton | résumé mono 12 avec valeurs en violet ; bouton 37 dp rayon 12 | résumé sans couleur ; `MaterialButton` à teinte violette, rayon M3 | PE11j |
| E25 | **Menus** : options | `.opt` 13 sp, détail mono 11, sélection = accent doux, **interrupteur 34 × 20** | radio (appareil), `MaterialSwitch` 52 × 32 (calques), point de coche (cible) | PE11j |
| E26 | **Menu calques** | 6 calques **+ groupe « Chrome système » (barre de navigation)** | 6 calques seulement | PE11j |
| E27 | **Arbre** | rangée 34 dp, monogramme **20** surface 2 rayon 5, classe + id mono 12,5, badge sur fond surface 2 (« ! n » sur fond ambre) | monogramme **24** `colorPrimaryContainer` rayon 6, badge **sans fond**, gris | PE11k |
| E28 | **Problèmes** | point 8 dp aligné sur la 1ʳᵉ ligne, titre 13 / 600, détail 12 sur 2 lignes, « Voir » mono 11 accent | point 10 dp au centre, détail décalé de 18 dp, **pas de « Voir »** | PE11k |
| E29 | **Source** | ligne 20 dp, numéro 24 dp à droite, coloration balise / attribut / valeur, 2 niveaux de surlignage + surlignage « inséré » | numéro 36 dp, texte d'une seule couleur (aucun span de coloration), un seul surlignage `colorSecondaryContainer` | PE11k |
| E30 | **En-tête Source** | plage 12 gris + bouton « Ouvrir dans l'éditeur ↗ » (26 dp, trait 1, rayon 8, accent) | `TextButton` Material | PE11k |

**Bilan** : 30 écarts, tous visibles à l'œil nu sur capture. Aucun n'est un écart voulu du propriétaire.

## 3. Écran principal (`PreviewActivity`)

Captures : `ref/ecran-principal-ferme-{clair,nuit}.png`, `ecran-principal-attributs-*.png`,
`ecran-bandeau-erreur-*.png`, `ecran-statut-frappe-*.png`, `ecran-toast-*.png`.
Layout de référence : `ref/ressources/layout/activity_preview.xml`.

### 3.1 Anatomie (de haut en bas, valeurs en dp)

```
entete_preview (fond apercuSurface ; le padding haut reçoit l'inset de la barre d'état)
├─ appbar_preview ........................ 52  (padding 10 | espacement 8)
│   ├─ bouton_retour .................... 36×36 rond, icône 20
│   ├─ bloc_titre (poids 1)
│   │   ├─ titre_apercu ................. mono 14 / 600 (ÉCART VOULU PE10 : 13 dans la maquette)
│   │   └─ sous_titre_apercu ............ sans 11, gris doux, ellipsé
│   ├─ groupe_mode_apercu ............... pilule « surface 2 », padding 3, boutons 26 dp
│   └─ bouton_plus ...................... 36×36 rond, icône 20 (3 points)
├─ trait 1 dp (apercuTrait)
├─ barre_reglages_apercu ................. padding 8 / 10, 6 puces de 30 dp, espacement 6, défile
└─ trait 1 dp (apercuTrait)
conteneur_rendu (poids 1)
├─ vue_preview ........................... damier + planche(s) + calques (moteur)
└─ HUD : banniere_rendu, barre_edition, toast_apercu, pastille_etat, hud_zoom, bulle_palette,
         puis conteneur_palette et conteneur_placement AU-DESSUS de la bulle
conteneur_feuille (BottomSheet) ........... voir partie I (§ 8)
```
Hauteur totale en-tête = 52 + 1 + (8 + 30 + 8) + 1 = **100 dp** (+ inset de la barre d'état).

### 3.2 Barre du haut

| Pièce | Cotes | Texte / couleur | Notes |
|---|---|---|---|
| `bouton_retour`, `bouton_plus` | 36 × 36, rond, icône 20 (padding 8) | icône `apercuTexte` | ondulation `apercuSurface2` ; `bouton_plus` ouvre le menu « Choisir le fichier… » |
| Titre | une ligne, ellipsé | `TextAppearance.…Apercu.Titre` : mono 14 bold, `apercuTexte` | nom du fichier |
| Sous-titre | une ligne, ellipsé | `…SousTitre` : sans 11, `apercuTexteDoux` | « res/layout · rendu en Pixel 4 » (`preview_sous_titre_format`) |
| Pastille de mode | hauteur 32 = 3 + 26 + 3 ; boutons 26 dp, padding horizontal 10 | `…Mode` : sans-serif-medium 12 ; coché = `apercuSurPrimaire` sur `apercuPrimaire`, sinon `apercuTexteDoux` sur transparent | `ChipGroup` à sélection unique **obligatoire** ; ids `mode_design`, `mode_plan`, `mode_les_deux` ; l'écouteur devient `setOnCheckedStateChangeListener` |

L'inset de la barre d'état s'applique à `entete_preview` (fond `apercuSurface`) : la zone système est de la couleur de l'en-tête.
`setSupportActionBar` disparaît ; le menu « Choisir le fichier… » s'ouvre par `PopupMenu` ancré à `bouton_plus` (`R.menu.menu_preview`).

### 3.3 Puces de réglages (`Widget.CodeIDE.Apercu.Puce`)

| État | Fond | Trait | Texte |
|---|---|---|---|
| repos | `apercuSurface` | 1 dp `apercuTrait` | `apercuTexte` |
| appuyée (`isChecked`) ou menu ouvert (`isActivated`) | `apercuPrimaireConteneur` | transparent | `apercuSurPrimaireConteneur` |

Cotes : hauteur **30**, texte à **12 dp** du bord, 12 sp / 500, pilule, espacement **6**, ligne défilante sans barre.
Ordre immuable : **appareil ▾ · orientation · jour/nuit · langue · police · calques ▾**. Appareil et Calques sont `checkable=false` ;
leur état « menu ouvert » est `isActivated` posé par le code à l'ouverture et retiré à la fermeture.
Pas d'icône de coche (`checkedIconVisible=false`).

### 3.4 Canvas (dessiné par `VuePreviewXml`)

| Pièce | Valeur |
|---|---|
| Fond | `apercuSurface2` |
| Damier | cases de **8 dp** en alternance, couleur `apercuDamierCase` (`#21808096`, identique clair et nuit), origine (0, 0) |
| Écart entre planches | 40 dp (déjà conforme) |
| Légende de planche (`.cap`) | « DESIGN · PIXEL 4 » : mono 11 / 500, capitales, interlettrage 0,04 em, `apercuTexteDoux`, **22 dp** au-dessus de la planche, alignée à gauche |
| Écran rendu | trait extérieur 1 dp `apercuEcranTrait` + ombre 0 / 6 / 24 `apercuEcranOmbre` |
| Plan (`.screen.bp`) | contours pointillés 1 dp accent, **sans libellés** (ÉCART VOULU PE10) |

### 3.5 Calques (moteur) — valeurs à appliquer

Toutes les largeurs de trait et tailles de texte ci-dessous sont **en dp écran constants** (divisées par le zoom au dessin).

| Calque | Trait / forme | Couleur |
|---|---|---|
| Sélection `.sel` | cadre plein **2 dp** + aplat | trait `apercuAccent`, aplat `apercuAccentDoux` |
| Poignées `.sel .h` | **carré 9 dp** aux 4 coins, centré sur le coin, rayon 2, trait 2 dp | fond `apercuSurface`, trait `apercuAccent` |
| Parent `.par` | **1,5 dp pointillé** | `apercuAccent` à **75 %** |
| Frères `.sib` | **bord** plein 1 dp (pas d'aplat) | `apercuAccent` à **35 %** |
| Bornes `.bnd` | bord plein 1 dp | `apercuBornes` |
| Marges `.mg` | aplat + liseré pointillé 1 dp | aplat `apercuMargeFond`, liseré `apercuMarge` |
| Padding `.pd` | aplat | `apercuRemplissageFond` |
| Étiquette `.lab` | rectangle **rayon 4**, padding **3 / 6**, texte **mono 11 / 500** | fond `apercuAccent`, texte `apercuSurCalque` |
| Étiquette cotes `.lab.dim` | idem | fond `apercuTexte`, texte `apercuFond` |
| Étiquette insertion `.lab.nw` | idem | fond `apercuNouveau`, texte `apercuSurNouveau` |
| Épingle `.pin` | disque **18 dp**, liseré **2 dp** `apercuSurCalque`, glyphe « ! » ou le compte, sans-serif 11 / 700 | fond `apercuEpingleAvert` ou `apercuEpingleErreur` (erreur si une erreur existe) |
| Zone de dépôt `.dz` | contour **1,5 dp pointillé**, 75 % | `apercuNouveau` |
| Zone visée `.dz.on` | contour **plein**, 100 %, + aplat | contour `apercuNouveau`, aplat `apercuNouveauDoux` |
| Barre d'insertion `.slot` | **4 dp** d'épaisseur, rayon 3, + halo 3 dp | `apercuNouveau`, halo `apercuNouveauDoux` |
| Grille 8 dp `.screen.grid` | traits 1 dp tous les 8 dp | `apercuAccent` à 18 % |

### 3.6 HUD (vues posées sur le canvas)

| Pièce | Position | Cotes | Style |
|---|---|---|---|
| `barre_edition` | haut-centre, **10 dp** du haut ; **64 dp** sous un bandeau visible | 4 boutons de **34 × 34**, icône 18, rayon 10, espacement 2, séparateur 1 dp (marges 4 / 3), padding 4 | coque `Hud` : surface, trait 1 dp, rayon 14, élévation 4 ; désactivé = opacité .32 |
| `pastille_etat` | bas-gauche, **10 dp** | padding 6 / 12 ; pilule ; point **7 dp**, marge 6 | `Etat` ; texte mono 11 gris doux, partie forte (« Rendu », « Dernier rendu valide ») en `apercuTexte` |
| `hud_zoom` | bas-droite, **10 dp** | colonne ; boutons **36 × 34**, rayon 10, padding 4 ; ordre + / 100 % / ⤢ / − | `Hud` ; texte mono 13 |
| `bulle_palette` | gauche ou droite à **10 dp** du bord de l'écran, hauteur normalisée persistée | vue de **58 dp** (violet 50 + halo 4) ; icône 24 | `apercu_bulle_fond` ; ouverte : `apercu_bulle_fond_ouverte` + icône `apercuFond` ; élévation 8 |
| `toast_apercu` | haut-centre, **14 dp** | padding 8 / 12, rayon 10, largeur max ≈ 88 % (340 dp) | `Toast` : fond `apercuTexte`, texte `apercuFond` 12 sp ; fondu **200 ms**, reste **2,4 s** |
| `banniere_rendu` | haut, pleine largeur | padding 9 / 12 (+1 de trait bas), espacement 10 | `Bandeau` : fond `apercuErreurConteneur`, trait bas `apercuErreur`, texte 12 sp `apercuErreur` ; **une ligne** ; boutons « Ligne n » (aller à la source) et « Détails » ↔ « Réduire » (déplie le message) |

Comportements à conserver à l'identique :
- La pastille d'état et le HUD de zoom **remontent** avec la feuille (`translationY`) pour rester **10 dp** au-dessus d'elle ; la bulle suit.
- Pendant un glisser : bulle et barre d'édition à **20 %** d'opacité, non tactiles ; la palette est masquée mais **le glisser continue**.
- Les trois états de la pastille d'état : rendu OK (point `apercuSucces`), frappe en cours (point `apercuAvert`), erreur (point `apercuErreur`).
- Le bandeau est visible en **erreur de rendu** (dernier rendu conservé) et en **processus arrêté** (bouton « Réessayer »). Le moteur ne dessine plus de bandeau.

## 4. Palette

Captures : `ref/ecran-palette-glisser-*.png`, `ecran-palette-popup-*.png`, `ecran-palette-recherche-*.png`, `ecran-glisser-*.png`.
Layouts de référence : `popover_palette.xml`, `item_puce_categorie.xml`, `item_tuile_palette.xml`, `item_sous_titre_palette.xml`, `item_vide_palette.xml`, `item_ghost_palette.xml`.

### 4.1 Géométrie du popover

- Marge à gauche et à droite : **10 dp** ; haut : **8 dp** sous la rangée de puces ; bas : **12 dp**.
- Hauteur = `min(600 dp, hauteur disponible)` **imposée par le code** (`LayoutParams.height`) : le `LinearLayout` enveloppe son contenu, sans borne la grille déborde.
- Coque `Popover` : surface **opaque**, trait 1 dp, rayon 16, padding 12, élévation 8, espacement vertical 10.
- Ouverte : la bulle prend `apercu_bulle_fond_ouverte`.

### 4.2 Contenu (de haut en bas)

| Pièce | Cotes | Style |
|---|---|---|
| En-tête | 36 dp : titre « Palette » (sans-serif-medium 15), puce bulle (28 dp), fermer (36 × 36) | puce `PuceCategorie` : coché = bulle **fixe** ; libellé « Bulle fixe » / « Bulle flottante » |
| Mode d'insertion | conteneur « surface 2 », rayon 12, padding 4 ; deux boutons égaux, espacement 4, rayon 9, padding 9 / 8 | titre 12 / 500, détail 11 (opacité .85) ; **sélectionné = surface + ombre 1 dp**, texte `apercuTexte` ; sinon `apercuTexteDoux` |
| Recherche | 36 dp, style `Recherche` | fond `apercuFond`, trait 1 dp, rayon 10, 13 sp |
| Catégories | puces **28 dp**, texte à 11 dp du bord, espacement 6, défile | `PuceCategorie` ; « Tous » + 6 catégories ; sélectionnée = `apercuPrimaireConteneur` |
| Grille | **3 colonnes**, espacement **8** (`ItemDecoration`), padding 2, défile seule | sous-titres « Récents » et « Tous les widgets » pleine largeur (titre de groupe mono 10) |
| Tuile | min 112 dp ; padding 10 ; icône 24 ; espacement 6 | `Tuile` : fond `apercuFond`, trait 1 dp, rayon 12 ; icône `apercuNouveau` trait 1,8 ; nom 12 / 600 (2 lignes max) ; classe mono 10,5 `apercuTexteDoux` ; tag en bas |
| Tag | padding 4 / 7, rayon 5, mono 11 | `Tag` (« Material » : surface 2 + trait) ; `Tag.Avert` (« dépendance absente » : `apercuAvertTag`, texte `apercuAvert`) |
| Aucun résultat | centré, padding 18 / 10 | `Vide` : « Aucun widget ne correspond à « … ». » |

Tuile appuyée : fond `apercuNouveauDoux`, trait `apercuNouveau`. En mode **Placer par popup**, un tap ouvre le placement ; en mode **Glisser-déposer**, un tap simple affiche le toast d'aide et un appui long + déplacement lance le glisser.
Les 16 icônes `ic_tuile_*` sont déjà conformes (mêmes tracés que `ICO` de la maquette, trait 1,8) : **seule leur teinte change**, de `@color/preview_palette_icone` à `?attr/apercuNouveau`.

### 4.3 Ghost et zones de dépôt

- Ghost `.ghost` : pilule `apercu_ghost_fond` (violet, rayon 12), padding 8 / 12, icône **du widget** 18 dp (`apercuSurNouveau`), nom sans-serif-medium 12, puis « → Conteneur » en mono 11 quand une cible est survolée. Élévation 10. Le point d'accroche est **au-dessus** du doigt (−50 % en x, −130 % en y).
- Zones de dépôt : voir § 3.5 (`.dz`, `.dz.on`, `.slot`, `.lab.nw`).

## 5. Placement par popup

Captures : `ref/ecran-placement-*.png`. Layouts : `popover_placement.xml`, `item_rangee_placement.xml`.

- Collé en **bas** du rendu, marges **10 / 10 / 10**, hauteur max **340 dp**, coque `Popover` (opaque, padding 12, espacement 10).
- En-tête 36 dp : « Où placer TextView ? » (sans-serif-medium 15, une ligne) + fermer.
- Mini-arbre `.mini` : cadre trait 1 dp rayon 10, padding 4, **150 dp max**, défile ; **mêmes rangées que l'arbre** (§ 7) mais hauteur 32 ; la référence est en **accent doux** ; défile pour centrer la référence.
- Positions `.pos` : conteneur « surface 2 » rayon 12 padding 4 ; **Avant / Dedans / Après**, boutons égaux (padding vertical 10, 12,5 / 500, rayon 9, espacement 4) ; sélectionné = surface + ombre ; indisponible = opacité .35 (« Dedans » sur une vue sans enfants ; « Avant / Après » sur la racine).
- Résumé `.res` : « Dans **ConstraintLayout · @+id/root**, position **8** sur 9 » — mono 12, interligne 1,4, les valeurs en `apercuNouveau`.
- Bouton « Placer ici » : `Principal` — 37 dp, rayon 12, fond `apercuNouveau`, texte `apercuSurNouveau` 13 / 600.
- Pendant le placement, la **cible est dessinée en direct** sur le rendu (§ 3.5) et le rendu se **recadre** pour la montrer au-dessus du popup.

## 6. Menus (appareil, calques, cible, plus)

Captures : `ref/ecran-menu-appareil-*.png`, `ecran-menu-calques-*.png`, `ecran-menu-plus-*.png`.

- Géométrie `.pop` : 10 dp de chaque bord, 8 dp sous la rangée de puces, padding 8, hauteur max 430 dp, coque `Menu` (surface **translucide**, trait 1 dp, rayon 16 — ÉCART VOULU PE9c, colorée par les pastilles).
- Titre de groupe : mono 10 / 600 capitales, interlettrage .08 em ; marges 10 (côtés), 8 (haut), 6 (bas).
- Option `.opt` : rayon 10, padding 10, espacement 10 ; **cochée = `apercuAccentDoux`** (`isSelected`) ; appuyée = `apercuSurface2`.
  - **Appareil** : nom 13 sp + détail mono 11 « 393×851 dp · 440 dpi » ; 5 options ; **pas de radio, pas de tags Existant/Nouveau**.
  - **Calques** : pastille colorée 12 dp (ÉCART VOULU PE9c), libellé, **interrupteur 34 × 20** (piste `apercuTrait` éteinte, `apercuAccent` allumée ; pouce blanc 16 dp, course 14 dp) ; 6 calques puis le groupe **« Chrome système »** avec « Barre de navigation ».
  - **Cible** : libellé 13 sp ; la cible courante est sélectionnée (pas de point de coche).
- L'interrupteur est un `SwitchCompat` stylé (`Widget.CodeIDE.Apercu.Interrupteur`) — **pas** de `MaterialSwitch` (52 × 32).

## 7. Onglet Arbre

Captures : `ref/ecran-arbre-*.png`. Layouts : `fragment_volet_arbre.xml`, `item_arbre.xml`.

| Pièce | Valeur |
|---|---|
| Volet | padding 8 / 10 / 14 (partie I) |
| Rangée | **34 dp** (20 + 7 + 7), padding horizontal 8, rayon 8, espacement 8 ; indentation `8 + profondeur × 16` dp |
| États | sélectionnée = `apercuAccentDoux` ; appuyée = `apercuSurface2` |
| Monogramme | **20 × 20**, `apercuSurface2`, rayon 5, initiale mono 10 / 600 `apercuPrimaire` |
| Classe | mono 12,5, `apercuTexte` |
| Id | « @+id/btn_connexion » mono 12,5 `apercuTexteDoux`, ellipsé |
| Badge | à droite ; padding 2 / 5 ; mono 10 ; neutre = `apercuSurface2` + `apercuTexteDoux` (« 345×48 ») ; **avertissement = `apercuAvert` + texte `apercuFond` (« ! 2 »)** |

## 8. Onglet Problèmes

Captures : `ref/ecran-problemes-*.png`. Layouts : `fragment_volet_problemes.xml`, `item_probleme.xml`.

| Pièce | Valeur |
|---|---|
| Rangée | padding 9 / 8, rayon 8, espacement 10 ; appuyée = `apercuSurface2` |
| Point | **8 dp**, marge haute 6 ; `apercuAvert`, **erreur = `apercuErreur`** |
| Titre | sans-serif-medium 13 |
| Détail | 12 `apercuTexteDoux`, deux lignes : libellé de la vue (« Button @+id/btn_connexion »), puis message |
| « Voir » | à droite ; mono 11 `apercuAccent` ; appuyer sur la rangée sélectionne la vue |
| Vide | « Aucun problème détecté à cette taille et à cette police. » — `Vide` (centré, 13 sp, padding 18 / 10) |

Le badge de l'onglet (partie I) suit le nombre de problèmes et se **masque à zéro**.

## 9. Onglet Source

Captures : `ref/ecran-source-*.png`. Layouts : `fragment_volet_source.xml`, `item_ligne_source.xml`.

| Pièce | Valeur |
|---|---|
| En-tête `.openrow` | padding 2 / 4 / 8 / 4, espacement 10 ; plage « Lignes 12 à 19 » / « Document entier » (12 `apercuTexteDoux`) ; bouton « Ouvrir dans l'éditeur ↗ » `BoutonLien` (26 dp, trait 1, rayon 8, texte `apercuAccent`) |
| Ligne | **20 dp** (mono 12, interligne 1,65), padding horizontal 8, rayon 4, espacement 10 |
| Numéro | colonne **24 dp**, à droite, `apercuTexteDoux` |
| Coloration | balise `apercuPrimaire`, attribut `apercuRemplissage`, valeur `apercuMarge` (spans) |
| Surlignages | racine sélectionnée = `isSelected` → `apercuAccentDoux` ; plage de la vue = `isActivated` → `apercuSourceVue` ; ligne qui vient d'être insérée = `apercuSourceInsere`, retiré après ~1,2 s |
| Défilement | à l'ouverture, la plage de la vue est amenée à 70 dp du haut |

## 10. Nuit

Toutes les couleurs passent par les attributs de la partie I : **aucune règle spécifique à la nuit** hormis
trois points à contrôler sur capture : (a) le damier (`apercuDamierCase` reste le même, sur `apercuSurface2` sombre) ;
(b) l'icône de la bulle (`apercuSurNouveau` = `#1B0B3D`) ; (c) les épingles et les bornes, identiques au clair.

## 11. Contrat du moteur (`:preview:xml`) — jetons injectés

Le module reste **zéro-Material** et ne lit aucun attribut de thème. Il reçoit un objet pur :

```kotlin
public data class JetonsCalques(
    val surface2: Int,          // fond du canvas
    val damierCase: Int,        // apercuDamierCase
    val accent: Int, val accentDoux: Int,
    val surface: Int,           // poignées, liserés
    val texte: Int, val fond: Int,       // étiquette cotes
    val texteDoux: Int,         // légende de planche
    val marge: Int, val margeFond: Int,
    val remplissage: Int, val remplissageFond: Int,
    val bornes: Int,
    val nouveau: Int, val nouveauDoux: Int, val surNouveau: Int,
    val epingleAvert: Int, val epingleErreur: Int,
    val surCalque: Int,
    val ecranTrait: Int, val ecranOmbre: Int,
)
```
`VuePreviewXml.appliquerJetons(JetonsCalques)` est appelée par `PreviewActivity` à la création et à chaque changement de thème (`onConfigurationChanged`). Elle **remplace** les constantes `COULEUR_DAMIER_A/B`, `COULEUR_BANDEAU`, `COULEUR_PLAN`, `COULEUR_MARGE`, `COULEUR_PADDING`, `COULEUR_PIN_*`, `couleurSurlignage`, `couleurParent`, `couleurSurface`. Le moteur **supprime** `dessinerBandeauErreur` (le bandeau est une vue) ; le message d'erreur lui parvient toujours, il le relaie.
Test : `JetonsCalquesTest` (JVM) vérifie que `JetonsCalques` construit depuis `jetons-maquette.json` contient les mêmes entiers que les ressources (parité, comme `JetonsMaquetteTest`).

## 12. Recette de fidélité (par chantier)

1. **Gonflage et cotes** (Robolectric, `Theme.CodeIDE.Apercu`) : pour chaque layout de référence, gonfler, mesurer et comparer à `ref/meas_ecrans_clair.json` — **±1 dp** sur largeur et hauteur des pièces **à dimension fixe** (appbar 52, puce 30, bouton de zoom 36 × 34, bouton d'édition 34 × 34, bulle 58, tuile ≥ 112, rangée d'arbre 34, ligne de source 20…). Les largeurs de texte ne sont pas comparées (la police de la maquette n'est pas celle de l'appareil).
2. **Parité des couleurs** : `JetonsMaquetteTest` (39 jetons) et `JetonsCalquesTest`.
3. **Paires de captures** : pour chaque capture `ref/ecran-*.png`, produire la capture de l'app au même état, en clair et en nuit, et les joindre au CHANGELOG du chantier (maquette | app).
4. **Grille de contrôle** à cocher : pastille de mode pleine ; puces pilule 30 dp ; trait sous l'appbar et sous les puces ; damier 8 dp en nuit ; sélection carrée 9 dp ; bandeau avec 2 boutons ; toast haut-centre ; bulle 50 dp violet entouré de son halo ; palette bornée à 600 dp ; mode sélectionné visible ; 3 colonnes espacées de 8 ; mini-arbre avec ids ; interrupteurs 34 × 20 ; rangée d'arbre 34 dp ; « Voir » sur chaque problème ; source colorée.

## 13. Points ouverts à trancher par le propriétaire

| # | Question | Proposition |
|---|---|---|
| O1 | Titre de l'appbar : 14 sp (voulu PE10) ou 13 (maquette) ? | Garder 14 sp (déjà décidé). |
| O2 | Menus translucides et colorés (voulu PE9c) | Garder pour les **menus** ; palette et placement **opaques** comme la maquette. |
| O3 | Cibles tactiles < 48 dp dans le chrome dense (puces 30, segments 26, HUD 34-36, tuiles) | Étendre la dérogation de l'ADR 0133 (plancher 24 dp) à ces zones ; l'appbar et la barre de navigation gardent 48 dp. |
| O4 | Défilement horizontal de la Source | Tronquer d'abord ; défilement horizontal en PE11l si le besoin se confirme. |
| O5 | Interrupteur 34 × 20 | Retenu (fidélité) ; coût : un `SwitchCompat` stylé à la place de `MaterialSwitch`. |

## Annexe A — Cotes mesurées dans la maquette (clair)

Valeurs **calculées par le navigateur**, 1 px = 1 dp. `n` = nombre d'éléments correspondants ; la 1ʳᵉ occurrence est mesurée. Les largeurs de texte dépendent de la police : ne comparer que les hauteurs et les cotes fixes.


### Écran principal

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `phone` | 1 | 420 × 812 | 15 | 400 | 28px |
| `appbar` | 1 | 418 × 66.7 | 15 | 400 | 0px |
| `ib` | 2 | 36 × 36 | 13.3333 | 400 | 50% |
| `ib_svg` | 2 | 20 × 20 | 13.3333 | 400 | 0px |
| `ttl` | 1 | 119.5 × 49.7 | 15 | 400 | 0px |
| `ttl_b` | 1 | 119.5 × 15.6 | 13 | 600 | 0px |
| `ttl_span` | 1 | 119.5 × 34.1 | 11 | 400 | 0px |
| `seg` | 1 | 182.5 × 32 | 15 | 400 | 999px |
| `seg_btn` | 3 | 59.9 × 26 | 12 | 500 | 999px |
| `seg_on` | 1 | 59.9 × 26 | 12 | 500 | 999px |
| `chips` | 1 | 418 × 47 | 15 | 400 | 0px |
| `chip` | 6 | 77.5 × 30 | 12 | 500 | 999px |
| `chip_dev` | 1 | 77.5 × 30 | 12 | 500 | 999px |
| `caret` | 2 | 9.2 × 9 | 9 | 500 | 0px |
| `canvas` | 1 | 418 × 696.3 | 15 | 400 | 0px |
| `world` | 1 | 271.6 × 550 | 15 | 400 | 0px |
| `board` | 1 | 246.5 × 533.7 | 15 | 400 | 0px |
| `cap` | 1 | 70.9 × 6.9 | 11 | 500 | 0px |
| `screen` | 1 | 246.5 × 533.7 | 15 | 400 | 0px |
| `sbar` | 1 | 246.5 × 15.1 | 11 | 500 | 0px |
| `abar` | 1 | 246.5 × 35.1 | 20 | 500 | 0px |
| `zoom` | 1 | 46 × 164 | 15 | 400 | 14px |
| `zoom_btn` | 4 | 36 × 34 | 13 | 500 | 10px |
| `zoom_pct` | 1 | 36 × 34 | 13 | 500 | 10px |
| `status` | 1 | 279.6 × 25 | 11 | 500 | 999px |
| `status_dot` | 1 | 7 × 7 | 11 | 500 | 50% |
| `status_b` | 1 | 33.1 × 11 | 11 | 500 | 0px |
| `edit` | 1 | 161 × 44 | 15 | 400 | 14px |
| `edit_btn` | 4 | 34 × 34 | 13.3333 | 400 | 10px |
| `edit_svg` | 4 | 18 × 18 | 13.3333 | 400 | 0px |
| `edit_sep` | 1 | 1 × 0 | 15 | 400 | 0px |
| `bubble` | 1 | 50 × 50 | 13.3333 | 400 | 17px |
| `bubble_svg` | 1 | 24 × 24 | 13.3333 | 400 | 0px |
| `toast` | 1 | 24 × 16 | 12 | 400 | 10px |
| `sheet` | 1 | 418 × 72 | 15 | 400 | 18px |
| `grab` | 1 | 418 × 38.4 | 13.3333 | 400 | 0px |
| `grab_i` | 1 | 36 × 4 | 13.3333 | 400 | 2px |
| `crumb` | 1 | 390 × 14.4 | 12 | 500 | 0px |
| `tabs` | 1 | 418 × 37 | 15 | 400 | 0px |
| `tab` | 4 | 54.9 × 36 | 13 | 500 | 0px |
| `tab_on` | 1 | 54.9 × 36 | 13 | 500 | 0px |
| `tab_n` | 1 | 16 × 14 | 10 | 500 | 9px |
| `sel` | 1 | 216.4 × 30.1 | 15 | 400 | 0px |
| `sel_h` | 4 | 9 × 9 | 15 | 400 | 2px |
| `par` | 1 | 246.5 × 453.4 | 15 | 400 | 0px |
| `sib` | 7 | 55.2 × 55.2 | 15 | 400 | 0px |
| `mg` | 3 | 246.5 × 12.5 | 15 | 400 | 0px |
| `pd` | 2 | 15.1 × 30.1 | 15 | 400 | 0px |
| `lab` | 7 | 25.2 × 17 | 17.54 | 500 | 6.37818px |
| `lab_dim` | 6 | 25.2 × 17 | 17.54 | 500 | 6.37818px |
| `pin` | 2 | 18 × 18 | 17.54 | 700 | 50% |

### Arbre

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pane` | 1 | 418 × 223.6 | 15 | 400 | 0px |
| `row` | 9 | 398 × 34 | 12.5 | 400 | 8px |
| `row_on` | 1 | 398 × 34 | 12.5 | 400 | 8px |
| `row_k` | 9 | 20 × 20 | 10 | 600 | 5px |
| `row_id` | 9 | 67.7 × 15 | 12.5 | 400 | 0px |
| `row_badge` | 9 | 52.2 × 16 | 10 | 400 | 4px |
| `row_badge_w` | 2 | 28.1 × 16 | 10 | 400 | 4px |
| `row_text` | 9 | 120.4 × 15 | 12.5 | 400 | 0px |

### Problèmes

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pane` | 1 | 418 × 223.6 | 15 | 400 | 0px |
| `prob` | 3 | 398 × 62.9 | 13.3333 | 400 | 8px |
| `sev` | 3 | 8 × 8 | 13.3333 | 400 | 50% |
| `prob_b` | 3 | 280.8 × 16.9 | 13 | 600 | 0px |
| `prob_span` | 6 | 280.8 × 28 | 12 | 400 | 0px |
| `go` | 3 | 26.5 × 11 | 11 | 500 | 0px |

### Source

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pane` | 1 | 418 × 223.6 | 15 | 400 | 0px |
| `openrow` | 1 | 398 × 36 | 15 | 400 | 0px |
| `openrow_span` | 1 | 82.6 × 18.6 | 12 | 400 | 0px |
| `linkbtn` | 1 | 150.6 × 26 | 12 | 500 | 8px |
| `src` | 10 | 252 × 41.6 | 12 | 400 | 0px |
| `src_line` | 100 | 398 × 19.8 | 12 | 400 | 4px |
| `src_hl2` | 12 | 398 × 19.8 | 12 | 400 | 4px |
| `ln` | 100 | 24 × 19.8 | 12 | 400 | 0px |
| `tg` | 10 | 354 × 14 | 12 | 400 | 0px |
| `at` | 91 | 50.6 × 14 | 12 | 400 | 0px |
| `vl` | 91 | 36.1 × 14 | 12 | 400 | 0px |

### Palette

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pop` | 1 | 398 × 600 | 15 | 400 | 16px |
| `head` | 1 | 372 × 36 | 15 | 400 | 0px |
| `head_b` | 1 | 218.8 × 18 | 15 | 600 | 0px |
| `head_chip` | 1 | 101.2 × 28 | 12 | 500 | 999px |
| `head_ib` | 1 | 36 × 36 | 13.3333 | 400 | 50% |
| `mode` | 1 | 372 × 56.8 | 15 | 400 | 12px |
| `mode_btn` | 2 | 180 × 48.8 | 12 | 500 | 9px |
| `mode_on` | 1 | 180 × 48.8 | 12 | 500 | 9px |
| `mode_small` | 2 | 164 × 13.8 | 11 | 400 | 0px |
| `search` | 1 | 372 × 35.6 | 13 | 400 | 10px |
| `cats` | 1 | 372 × 30 | 15 | 400 | 0px |
| `cat` | 7 | 49.8 × 28 | 12 | 500 | 999px |
| `cat_on` | 1 | 49.8 × 28 | 12 | 500 | 999px |
| `tiles` | 1 | 372 × 375.7 | 15 | 400 | 0px |
| `tile` | 16 | 117.3 × 112 | 13.3333 | 400 | 12px |
| `tile_svg` | 16 | 24 × 24 | 13.3333 | 400 | 0px |
| `tile_b` | 16 | 53.9 × 14.4 | 12 | 600 | 0px |
| `tile_small` | 16 | 50.6 × 12.6 | 10.5 | 400 | 0px |
| `tile_tag` | 3 | 71.6 × 21 | 11 | 500 | 5px |
| `tag_old` | 2 | 71.6 × 21 | 11 | 500 | 5px |
| `tag_wr` | 1 | 139.2 × 19 | 11 | 500 | 5px |

### Placement

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pop` | 1 | 398 × 340 | 15 | 400 | 16px |
| `head` | 1 | 372 × 36 | 15 | 400 | 0px |
| `head_b` | 1 | 328 × 18 | 15 | 600 | 0px |
| `head_ib` | 1 | 36 × 36 | 13.3333 | 400 | 50% |
| `mini` | 1 | 372 × 150 | 15 | 400 | 10px |
| `mini_row` | 9 | 362 × 32 | 12.5 | 400 | 8px |
| `mini_row_on` | 1 | 362 × 32 | 12.5 | 400 | 8px |
| `pos` | 1 | 372 × 40.5 | 15 | 400 | 12px |
| `pos_btn` | 3 | 118.7 × 32.5 | 12.5 | 500 | 9px |
| `pos_on` | 1 | 118.7 × 32.5 | 12.5 | 500 | 9px |
| `pos_off` | 1 | 118.7 × 32.5 | 12.5 | 500 | 9px |
| `res` | 1 | 372 × 16.8 | 12 | 400 | 0px |
| `res_b` | 2 | 202.3 × 14 | 12 | 500 | 0px |
| `prim` | 1 | 372 × 37 | 13 | 600 | 12px |
| `dz` | 2 | 246.5 × 453.4 | 15 | 400 | 0px |
| `dz_on` | 1 | 246.5 × 453.4 | 15 | 400 | 0px |
| `slot` | 1 | 236.4 × 4 | 15 | 400 | 4.78364px |
| `lab_nw` | 2 | 230.4 × 17 | 17.54 | 500 | 6.37818px |

### Menu appareil

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pop` | 1 | 398 × 297.9 | 15 | 400 | 16px |
| `h4` | 1 | 360 × 10 | 10 | 600 | 0px |
| `opt` | 5 | 380 × 51.2 | 13 | 400 | 10px |
| `opt_on` | 1 | 380 × 51.2 | 13 | 400 | 10px |
| `opt_small` | 5 | 278.4 × 14.3 | 11 | 400 | 0px |
| `opt_grow` | 5 | 278.4 × 31.2 | 13 | 400 | 0px |
| `tag` | 5 | 71.6 × 21 | 11 | 500 | 5px |

### Menu calques

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pop` | 1 | 398 × 346 | 15 | 400 | 16px |
| `h4` | 2 | 360 × 10 | 10 | 600 | 0px |
| `opt` | 7 | 380 × 40 | 13 | 400 | 10px |
| `opt_on` | 4 | 380 × 40 | 13 | 400 | 10px |
| `opt_grow` | 7 | 316 × 16.9 | 13 | 400 | 0px |
| `sw` | 7 | 34 × 20 | 13 | 400 | 999px |

### Menu plus

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `pop` | 1 | 398 × 232.5 | 15 | 400 | 16px |
| `h4` | 1 | 360 × 10 | 10 | 600 | 0px |
| `opt` | 4 | 380 × 51.2 | 13 | 400 | 10px |
| `opt_on` | 1 | 380 × 36.9 | 13 | 400 | 10px |
| `opt_small` | 3 | 360 × 14.3 | 11 | 400 | 0px |
| `opt_grow` | 4 | 360 × 31.2 | 13 | 400 | 0px |

### Glisser

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `ghost` | 1 | 233.6 × 34 | 12 | 600 | 12px |
| `ghost_svg` | 1 | 18 × 18 | 12 | 600 | 0px |
| `ghost_small` | 1 | 119.2 × 11 | 11 | 500 | 0px |
| `dz` | 2 | 246.5 × 453.4 | 15 | 400 | 0px |
| `dz_on` | 1 | 246.5 × 453.4 | 15 | 400 | 0px |
| `slot` | 1 | 236.4 × 4 | 15 | 400 | 4.78364px |
| `phone_dragging` | 1 | 420 × 812 | 15 | 400 | 28px |
| `bubble` | 1 | 50 × 50 | 13.3333 | 400 | 17px |
| `edit` | 1 | 161 × 44 | 15 | 400 | 14px |

### Bandeau

| Élément | n | L × H | Police | Poids | Rayon |
|---|---|---|---|---|---|
| `banner` | 1 | 418 × 42 | 12 | 400 | 0px |
| `msg` | 1 | 253.2 × 16.8 | 12 | 400 | 0px |
| `btn` | 2 | 64.9 × 23 | 11 | 500 | 8px |
| `status` | 1 | 234.4 × 25 | 11 | 500 | 999px |
