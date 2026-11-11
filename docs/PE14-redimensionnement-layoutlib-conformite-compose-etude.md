# Étude — Geste fidèle, audit LayoutLib, conformité, palettes et preview Compose (PE14)

> **Objet** : répondre à la commande du propriétaire avant de reprendre les
> chantiers validés (section « système » dans l'arbre, réglage « mode
> gestuel » au menu Calques) —
> 1. « Analyser la fonctionnalité de redimensionnement : elle beugue, les
>    calculs ne sont pas corrects ; lorsque j'appuie sur l'un des handlers
>    pour redimensionner je n'ai pas le résultat voulu » ;
> 2. « Pousser l'analyse sur le LayoutLib d'Android Studio pour voir ce qui
>    manque à mon LayoutLib, ce qui est mal fait, mérite des améliorations » ;
> 3. « Si les vues parents et enfants respectent les paramètres ou attributs ;
>    les vues qui ne peuvent pas avoir d'enfants ; celles qui ne peuvent
>    avoir qu'un enfant et autres » ;
> 4. « Enrichir les palettes et être plus réaliste » ;
> 5. « Analyser comment Android Studio gère la preview Compose et si, avec
>    mon LayoutLib, il utilise aussi son layoutlib pour la preview compose —
>    une preview en temps réel comme Android Studio ».
>
> **Méthode** : les dépôts de référence ont été clonés fraîchement pour cette
> étude (le layoutlib a quitté `frameworks/base` : il vit désormais dans son
> propre dépôt) :
> - `platform/frameworks/layoutlib` (dépôt autonome, HEAD `7b05b277`) dans
>   `reference/aosp-layoutlib` — bridge complet : `Bridge`,
>   `BridgeRenderSession`, `impl/RenderSessionImpl`, `impl/binding`
>   (AdapterBinding), `bars/`, classes forkées `android.*` ;
> - `platform/tools/adt/idea` (sparse : `designer/`, `compose-designer/`,
>   `rendering/`) dans `reference/aosp-adt-idea` — le designer XML ET le
>   pipeline Compose d'Android Studio ;
> - `androidx/androidx` (sparse : `compose/ui/ui-tooling*`) dans
>   `reference/aosp-androidx`.
> Le code des deux dépôts maison a été lu en parallèle (`CodeIDE` v0.169.0,
> `LayoutLib` v3.5.0). Tous les faits cités ont été vérifiés dans les
> fichiers sources, avec chemins et lignes. Annexe A : liste des sources.
>
> **Arbitrages (2026-10-23)** : le propriétaire a tranché « **C'est oui pour
D1-D8** » — les HUIT décisions du § 7 sont acceptées DANS LE SENS RECOMMANDÉ
par l'étude, et les chantiers **S1 (PE13-suite) + S2 (redimension fidèle)
sont LIVRÉS dans CodeIDE v0.170.0** (ADR 0138 pour le geste — D1/D3 au
complet, D2 snap renvoyé en P2 comme recommandé ; ADR 0139 pour la section
« système » et le mode gestuel). Le plan **S3–S9 reste séquencé** ci-dessous
(S3 bugs ponctuels LayoutLib, S4 conformité enfants, S5 palettes, S6 listes,
S7 attributs fidèles, S8 cookie/RTL/hygiène, S9 Compose C0) — chaque
lancement attendra le mot du propriétaire.

**Verdict en une ligne** : le redimensionnement a un défaut de calcul
> **réel et chiffrable** (le saut de préhension — §1.3), le LayoutLib maison
> est **structurellement sain mais concentré sur un périmètre restreint**
> (l'audit livre 3 bugs ponctuels, 15 manques priorisés et 6 stubs
> trompeurs — §2), la conformité parent/enfant est aujourd'hui **nulle côté
> éditeur** (un dépôt dans un AdapterView fait échouer TOUT le rendu — §3),
> la palette est riche mais **aveugle sur 3 catégories entières** de la
> référence (§4), et la preview Compose d'Android Studio **passe bien par le
> layoutlib** — la réponse à la question posée est oui, avec des
> conséquences précises et plutôt bonnes pour CodeIDE (§5).

---

## 0. Ce qui est déjà validé et reste devant nous

Le présent travail ne remplace pas les deux chantiers confirmés par le
propriétaire à la suite de PE13 (« Oui [pour] la section système, le mode
gestuel ») :

- **PE13-suite A — section « système » dans l'arbre** : le moteur expose
  déjà les `ViewType` du décor depuis `ViewInfoCollector.collectDecor`
  (LayoutLib 3.5.0) ; il reste à les consommer côté arbre de l'aperçu ;
- **PE13-suite B — réglage « mode gestuel »** au menu Calques :
  `ModeNavigation`/`NavigationHandle` existent côté moteur ; il reste
  l'option de menu et le passe-plat `ConfigChrome`.

L'étude ci-dessous fixe le socle de connaissances pour ces deux chantiers
(§2 : ce que le moteur sait déjà ; §3 : conformité) et pour les chantiers
suivants que le propriétaire arbitrera (§6-§7).

---

## 1. Redimensionnement — pourquoi le geste ne donne pas le résultat voulu

### 1.1 Le pipeline actuel (vérifié)

Le geste vit dans trois fichiers :

| Morceau | Fichier | Rôle |
|---|---|---|
| `RedimensionApercu` (pur) | `preview/xml/.../RedimensionApercu.kt` | ancre du coin saisi (`ancrePoigneeSous` :38-58), dimensions cibles (`dimensions` :65-82), rectangle du fantôme (`rectangle` :89-96), dérive du contact (`aDerive` :103-113) |
| `VuePreviewXml.traiterRedimension` :782-833 | `preview/xml/.../VuePreviewXml.kt` | machine d'états DOWN/MOVE/UP, conversion écran→contenu (`coordonneesContenu` :2185-2196), consommation de l'événement |
| `PreviewViewModel.redimensionnerSelection` :958-964 + `EditionAttributsXml.ecrireDimensions` :205-239 | `feature/preview` + `preview/xml` | UNE édition groupée `layout_width` + `layout_height` (atomique sur la balise ouvrante), re-rendu immédiat |

La saisie est **géométriquement correcte** : la cible tactile vaut
`tailleEcran(18 dp)` = `18 dp écran × densité hôte ÷ échelle`
(`VuePreviewXml.kt:1984`) — le rayon reste 18 dp **visibles** à tout zoom ;
le carré dessiné fait 9 dp (§ 3.5 maquette). Les conversions sont exactes :
le rendu vit aux dimensions du **profil** (`RenduLayoutLib.densiteProfil`,
`HardwareConfig` du profil), la vue reçoit `moteur.profil.densiteDpi`
(`ControleurPreviewXml.kt:161-165`) et `pxVersDp(px, dpi) = px × 160 ÷ dpi`
(`CalquesApercu.kt:43-46`). Le fantôme suit le doigt et affiche la cible dp
en direct (`dessinerRedimensionnement` :1810-1838). Le relâchement écrit
UNE fois, contact sans dérive = rien (miroir le tap). Ce socle n'est **pas**
en cause : le bug est dans le **modèle de la cible**, pas dans les unités.

### 1.2 Le calcul en trois formules (et où il casse)

Au `ACTION_DOWN`, la poignée sous le doigt ancre le coin **opposé** :
`ancre = (droite, bas)` si l'on saisit `(gauche, haut)`. Au `ACTION_UP`
(`cibleDuRelachement` :840-874) :

```
cibleLargeurDp = pxVersDp(|pointX − ancreX|)
cibleHauteurDp = pxVersDp(|pointY − ancreY|)      // plancher 8 dp chacun
```

Le point est le **doigt**, pas le coin. Les tests actuels couvrent le cas
idéal d'une souris : le `DOWN` tombe **exactement sur le coin**
(`VuePreviewXmlTest.kt:527`, coordonnées 90×60 = le coin lui-même ;
`RedimensionApercuTest.kt:60` : point = coin opposé, distance = taille de
la vue). Aucun test ne couvre le cas réel du tactile : le doigt saisit
**quelque part dans le rayon de 18 dp autour du coin**.

### 1.3 Le saut de préhension — démonstration chiffrée

Soit une vue de 80 × 40 dp, poignée bas-droite saisie. Le doigt pose un
contact tactile imprécis de ±9-18 dp (c'est précisément pourquoi la cible
de saisie fait 18 dp, dérogation ADR 0133 : « un doigt à 9 dp ne tient
pas »). Supposons le contact à **12 dp au sud-est du coin** (dans la
tolérance) :

- avant le geste : la vue mesure 80 × 40 dp ;
- premier `MOVE` à 1 px : la cible vaut |ancre − doigt| = 80 + 8,5 dp ×
  40 + 8,5 dp (composantes du décalage) — **le fantôme saute à ~88 × 48 dp
  sans que l'utilisateur ait bougé** ;
- l'utilisateur tire de 20 dp vers le bas-droite, il voit et veut
  ~100 × 60 dp ; le relâchement écrit **~108 × 68 dp** : +12 dp par axe
  d'erreur, i.e. 15 % de trop sur la largeur, 30 % sur la hauteur ;
- tirer vers l'intérieur pour rétrécir donne l'effet inverse : la cible
  part 12 dp SOUS le geste, parfois sous le plancher 8 dp (silencieusement
  ramenée à 8).

L'erreur est **bornée par le rayon de saisie lui-même** : jusqu'à ~18 dp
par axe (√2 × 18 en diagonale), c'est-à-dire jusqu'à 36 dp de cote. Sur une
petite vue (un bouton 88 × 48 dp, une puce), le saut représente **un tiers
de la dimension**. C'est exactement « j'appuie sur un handler, je n'ai pas
le résultat voulu » : le résultat est systématiquement **décalé du point de
préhension**, dans une direction imprévisible au doigt. Le pill dp en
direct (§1.1) affiche la cible qui a sauté, mais l'utilisateur lit le
chiffre APRÈS le mouvement, pas la position de son doigt au DOWN.

### 1.4 Ce que fait le designer de la référence (sources à l'appui)

La lecture directe d'`adt/idea/designer` (clone `reference/aosp-adt-idea`)
donne trois enseignements :

1. **Le modèle cible est le même** — `ResizeWithSnapBaseTarget
   .getNewDpWidth` (`designer/.../uibuilder/scene/target/ResizeWithSnapBaseTarget.java:228-258`)
   vaut `Math.abs(myStartX2 − x)` : distance bord-ancre ↔ curseur, comme
   CodeIDE. **MAIS** la référence pilote une **souris** : la cible visuelle
   fait ~6 px, le curseur pose SUR elle, le décalage initial vaut 0-3 px —
   le saut de préhension y est invisible. CodeIDE a hérité le modèle
   souris sur une **entrée tactile** dont la cible fait 36 dp : le modèle
   doit être adapté, pas recopié.
2. **Le drag de la référence compense, le resize non — et c'est assumé
   pour la souris** : `CommonDragTarget` (le déplacement) conserve, lui, le
   décalage d'accroche (`designer/.../common/scene/target/CommonDragTarget.kt:233-239`,
   `offsets[index] = x − drawX`). Le patron existe dans la référence ; il
   manque seulement à son resize.
3. **Le resize de la référence rattrape le geste par d'autres mécanismes**
   que CodeIDE n'a pas (§1.5-C/E) : snap ±10 dp vers wrap/match avec
   lignes-guides, conversion par layout parent, seuil de démarrage
   (`DRAG_THRESHOLD = 10 px`, `Scene.java:98`).

Enseignement pour PE14 : conserver l'ancre-coin-opposé (c'est la sémantique
de la maquette v6 « saisir une poignée ANCRE le coin opposé »), mais faire
suivre le **coin** au doigt, pas le point de contact.

### 1.5 Les cinq causes secondaires (vérifiées)

- **A. `layout_weight` neutralise la taille écrite.** L'inflater applique
  le poids (`LayoutLib/module-inflater/.../BridgeInflater.java:1239-1241`,
  `:1280-1282`) ; un enfant `0dp` + `layout_weight="1"` dans un
  LinearLayout horizontal **ignore** le `layout_width="150dp"` que le geste
  vient d'écrire — le rendu ne change pas d'un pixel. L'utilisateur
  tire la poignée, rien ne bouge : « les calculs ne sont pas corrects ».
  La référence écrit la taille fixe sans retirer le poids non plus — mais
  elle affiche l'attribut dans le volet ; CodeIDE peut faire mieux (C3).
- **B. Pas de retour aux tailles sémantiques.** La référence snappe à
  ±10 dp vers `wrap_content` (taille wrap **mesurée par le moteur de
  rendu**, `Scene.measureWrapSize` → `RenderTask.measureChild`,
  `designer/.../common/scene/Scene.java:1178-1223`) et vers `match_parent`
  (frontière du parent), avec lignes-guides visibles. CodeIDE écrit
  toujours du fixe : un retour accidentel à wrap/match est impossible,
  chaque geste fige les deux axes (même celui que l'utilisateur ne visait
  pas — poignée d'angle = deux axes d'un coup, par conception v6).
- **C. Pas de compensation par layout parent.** La référence a un target
  par layout : `RelativeResizeTarget` **réécrit les 4 marges** pour tenir
  le coin opposé (`handlers/relative/targets/RelativeResizeTarget.kt:48-114`),
  `AbsoluteResizeTarget` réécrit `layout_x/y`,
  `ConstraintResizeTarget` pose `tools:layout_editor_absoluteX/Y`
  (`handlers/constraint/targets/ConstraintResizeTarget.java:78-86`).
  CodeIDE écrit w/h pour tous les parents : dans un RelativeLayout avec
  marges, dans un FrameLayout avec gravité centrée, dans un
  ConstraintLayout à contraintes deux-côtés, le coin opposé **ne reste pas
  fixe** — la vue glisse en se redimensionnant.
- **D. Re-rendu asynchrone sans transition.** Au relâchement le fantôme
  disparaît immédiatement (`traiterRedimension` UP :818-821) ; l'écriture
  part en re-rendu immédiat (`soumettreImmediat`, moteur single-flight,
  rendu sur `Dispatchers.default` — `MoteurPreviewXml.kt:164`, `:381`).
  Si le rendu est lent (gros layout, process `:preview`), la fenêtre
  « rien ne semble s'être passé » s'ajoute à l'erreur de préhension.
- **E. Pas de clamp au parent.** Le doigt peut tirer la cible bien
  au-delà du parent (la référence clampe dans `RelativeResizeTarget` ;
  ailleurs elle le permet aussi — à départager en D2/D3, pas bloquant).

### 1.6 Correctifs proposés (C1-C5)

| # | Correctif | Contenu | Effet vécu |
|---|---|---|---|
| **C1** | **Compensation de préhension** (le cœur) | au `DOWN`, mémoriser `decalage = coinSaisi − pointDeContact` (px contenu) ; la cible devient `dimensions(ancre, point + decalage)` ; le fantôme part **de la taille actuelle de la vue** au lieu de sauter. Pur : un paramètre de plus dans `RedimensionApercu.dimensions` + un test « saisie à 12 dp du coin → le fantôme reste 80×40 au premier move ». | le saut de §1.3 disparaît ; le coin visuel suit le doigt (le geste devient analogue au `CommonDragTarget` de la référence). Compatible maquette v6 : « la cible suit le doigt » — le COIN suit le doigt. |
| **C2** | Snap sémantique ±10 dp | si la cible finit à ≤10 dp de la taille wrap **mesurée par le moteur** (la donnée existe déjà côté collecte : bounds wrap non disponibles → à exposer) → écrire `wrap_content` ; à ≤10 dp de la frontière parent → `match_parent`. Lignes-guides pointillées + pill « wrap_content » en direct, miroir § 3.5. | le geste redevient réversible : on peut revenir à wrap/match sans éditer le XML ; rattrape les petites imprécisions restantes. |
| **C3** | Par-layout au relâchement | LinearLayout : retirer `layout_weight` de l'axe écrit au passage en fixe (une édition groupée) ; RelativeLayout : réécrire la marge du bord tenu ; ConstraintLayout : poser `tools:layout_editor_absoluteX/Y`. La vue reste **sur place**, la taille change. | fin de « la vue glisse » (C) et de « rien ne bouge » (A). |
| **C4** | Transition douce post-écriture | garder le fantôme affiché (rect cible plein) jusqu'au rendu suivant, fondu 150 ms ; le panneau n'a jamais d'instant vide. | supprime la fenêtre D. |
| **C5** | Clamp optionnel | borner la cible au parent **si le parent est plus grand que la vue actuelle** (le rétrécissement reste libre). Décision D3. | évite les tailles absurdes dans les conteneurs contraints. |

Tests : étendre `RedimensionApercuTest` (saisie décalée, snap, clamp) et
`VuePreviewXmlTest` (DOWN décalé → fantôme stable ; C1 rétrocompatible si
`decalage = 0`). La partie pure reste sans Android ; les targets par
layout (C3) vivent côté édition (`EditionAttributsXml`), testées sur texte.

---

## 2. Audit du LayoutLib maison contre le moteur de référence

### 2.1 Cadrage

Le moteur de référence vit désormais dans `platform/frameworks/layoutlib`
(dépôt autonome ; il a quitté `frameworks/base/libs/`). Différence
architecturale **assumée et fondatrice** : la référence rend hors appareil
en simulant le framework sur JVM (delegates JNI/Skia, faux Context) ;
CodeIDE rend **sur l'appareil** avec de vraies vues, un vrai hwui, de
vraies fontes — d'où des avantages structurels (§2.6). L'audit porte sur
les écarts **au-delà** de cette différence. Deux nuances de périmètre
importantes pour lire ce qui suit :

- le **chrome 3.5.0 est câblé côté CodeIDE** (`RenduLayoutLib` construit
  `ConfigChrome` et enveloppe le décor) ; il ne l'est pas dans l'app de
  démonstration `layout-editor-app` du dépôt LayoutLib ;
- les **vues custom du projet utilisateur sont chargées côté CodeIDE** via
  la `Factory2` `DexViewFactory` (garde « L'HÔTE D'ABORD », v0.166.4) ;
  le `BridgeInflater` du LayoutLib accepte une factory mais l'app de démo
  n'en branche aucune. Les deux manques ci-dessous sont donc « absents du
  LayoutLib seul », pas de la chaîne complète.

### 2.2 Table par sous-système (synthèse ; détail annexe B)

| Sous-système | Référence AOSP | Projet | Statut |
|---|---|---|---|
| Session de rendu | `impl/RenderSessionImpl` (1230 l.) : modes EXPAND/SHRINK, frames d'animation, scroll, dispatch tactile | `module-layout/.../session/RenderSessionImpl` (99 l., mesure/layout) ; `LayoutEngineImpl` (305 l.) | **PARTIEL** |
| Inflation | `android/view/BridgeInflater` = vrai `LayoutInflater` (préfixes `android.widget.`, `viewInflaterClass` du thème) | `module-inflater/.../BridgeInflater` (2106 l.) parser maison + `ViewTagRegistry` (~150 tags en dur) | **PARTIEL** |
| Attributs par vue | `BridgeTypedArray` : chaque constructeur de vue lit ses styleables, `defStyleAttr`, `defStyleRes` du **thème prévisualisé** | setters post-construction (`AttributeApplier` 1643 l. + familles) ; vues instanciées `(Context)` — jamais d'`AttributeSet` natif | **PARTIEL — le plus gros écart de fidélité** |
| Vues custom | `LayoutlibCallback.loadView/findClass` (classloader projet), Glance | côté CodeIDE : `DexViewFactory` (chargée) ; côté LayoutLib : rien | **OK côté CodeIDE** |
| MockView | `bridge/MockView` (texte gris dans cadre) | `bridge/MockView` (cadre pointillé + hachures + nom) + **repli thème Material3** avant MockView (v3.4.9) | **ÉQUIVALENT +** |
| Listes de preview | `impl/binding/` (`AdapterBinding`, `FakeAdapter`, `FakeExpandableAdapter`) + `tools:listitem/itemCount/listheader/listfooter` | `bridge/binding/` existe mais **non branché** ; `XmlPreprocessor.java:50,98` **strippe** `tools:listitem`/`itemCount` | **ABSENT — visible** |
| ViewInfo | `ViewInfo` porte `viewObject` + cookie XML (`getViewKey`, `MergeCookie`) | `ViewInfoCollector.ViewInfo` : bornes, profondeur, id — **ni objet ni cookie** (sélection par indices) | **PARTIEL** |
| Chrome/bars | `impl/Layout` (725 l.) : cutout, edge-to-edge, barres translucides, `windowIsFloating`, navbar latérale paysage, icônes/hauteurs par version | `bridge/bars` (PE13) : PADDE/SURIMPRESSION, `InsetsDecor`, `ConfigChrome`, glyphes maquette, `LisibiliteChrome` | **PARTIEL** (choix maquette assumés, G1) |
| RTL | `Bridge.isLocaleRtl`, qualificateur layout-direction, `ldrtl` | **aucun** qualificateur direction ; `XmlPreprocessor.java:56-70` réécrit `marginStart→marginLeft` | **ABSENT** |
| Thème appliqué aux vues | le **thème prévisualisé** traverse tout le pipeline (`BridgeContext.resolveThemeAttribute`, `Resources_Theme_Delegate`) | le thème des vues reste celui de **CodeIDE** ; `ThemeResolver` ne sert que `?attr/` explicites + chrome | **PARTIEL — écart de fidélité n°2** |
| Drawables | delegates AVD/9-patch/adaptive + `DrawableInflater_Delegate` | `DrawableResolverImpl` : vrais `GradientDrawable`/`VectorDrawable` via `Drawable.createFromXml` ; AVD/ripple ≈ | **PARTIEL (bonne stratégie)** |
| 9-patch | `NinePatchInputStream` + delegates | chaîne `ninepatch/` présente mais **code mort** (0 appelant) | **ABSENT en pratique** |
| Animations / interactif | `Choreographer_Delegate` (file + `doFrame`), dispatch tactile/clavier, `SessionInteractiveData` | passthrough 24 l. ; `ChoreographerCallbacks`/`HandlerMessageQueue` jamais branchés | **ABSENT** |
| A11y (validateur) | `validator/` (`LayoutValidator`, flag `FLAG_ENABLE_LAYOUT_VALIDATOR`) | rien | **ABSENT** |

### 2.3 Trois bugs ponctuels à corriger vite (impact direct, coût faible)

1. **`layout_column` lu depuis `layout_rowSpan`** — copier-coller :
   `LayoutLib/module-inflater/.../BridgeInflater.java:1599`. Tout
   `GridLayout` avec `layout_column` est mal placé. (La référence lit
   chaque attribut via le styleable réel.)
2. **`layout_gravity` RelativeLayout → toujours `CENTER_IN_PARENT`** —
   `BridgeInflater.java:1261-1264`, la valeur est ignorée.
3. **Ids dynamiques régénérés à chaque appel** —
   `BridgeInflater.java:1798-1817` : un id inconnu reçoit un NOUVEAU
   `View.generateViewId()` à CHAQUE appel (et le compteur de `:1449-1451`
   n'est pas une map name→id). Conséquence : deux vues référençant le même
   `@id/bas` obtiennent des ids DIFFÉRENTS selon l'ordre de résolution —
   `layout_below`, `layout_constraintTop_toBottomOf`, ancres…
   silencieusement cassés, non déterministes. La référence a une
   `DynamicIdMap` stable (`Bridge.java:126` + `util/DynamicIdMap.java`).
   **C'est peut-être aussi une cause du ressenti « résultats bizarres »
   sur les contraintes** : à relier au §1.5-C.

### 2.4 Les manques majeurs, par ordre d'impact aperçu

1. **Attributs : passer du setter post-construction au `AttributeSet`
   réel** (le plus gros gain de fidélité). Aujourd'hui chaque widget ne
   reçoit que ce qui est codé à la main dans `AttributeApplier` /
   `TextFamilyAttributes` / `ProgressFamilyAttributes` / `AdapterFamilyAttributes` /
   `LayoutFamilyAttributes` / `MiscWidgetAttributes` ;
   `textAppearance` (0 occurrence projet), `defStyleAttr` (0 occurrence),
   `typeface`, `numeric`, styles référencés… sont ignorés en silence. La
   référence donne à CHAQUE constructeur de vue un `BridgeTypedArray`
   résolu contre le thème prévisualisé. Pistes maison : (a) envelopper le
   `AttributeSet` du parser maison dans un `BridgeXmlPullAttributes`
   (miroir `android/util/BridgeXmlPullAttributes.java`) qui résout
   `?attr/`/`@ref` à la lecture ; (b) instancier les vues avec le
   constructeur `(Context, AttributeSet)` dès que l'attribut est résolu —
   le chemin `(Context)` seul reste pour les cas dégénérés ; (c) résoudre
   `defStyleAttr` par famille (une table : `buttonStyle`,
   `editTextStyle`, `textAppearanceSmall`…).
2. **Le thème prévisualisé doit devenir le thème des vues.** Instancier
   sous un `ContextThemeWrapper` portant le thème RESOLU du projet
   (`ThemeResolver`), pas le thème de CodeIDE. Sans cela, un projet
   `Theme.MaterialComponents` ne change pas le look de ses boutons —
   l'aperçu trahit le rendu réel. (Le repli Material3 v3.4.9 est un
   pansement ciblé qui disparaîtrait de fait.)
3. **Brancher la preview de listes** (`tools:listitem`, `itemCount`,
   `listheader/listfooter`, `android:entries` étendu). Le squelette existe
   (`bridge/binding/FakeAdapter` 42 l., `AdapterHelper`, `AdapterItem` —
   code mort) ; la référence montre la machinerie complète
   (`impl/binding/AdapterBinding` + `setupViewInContext`,
   `android/view/BridgeInflater.java:463-563`, y compris RecyclerView via
   `RecyclerViewUtil`). Aujourd'hui une `RecyclerView`/`ListView` de
   l'aperçu est **vide** — c'est le manque le plus visible avec les
   attributs.
4. **`ViewInfo` : porter le cookie XML** (et l'objet `View`). La sélection
   CodeIDE fonctionne par bornes/indices ; la référence attache à chaque
   `ViewInfo` un `viewObject` ET une clé (`getViewKey` : cookies menus,
   `MergeCookie` pour `<include>`/`<merge>`). Porter un `viewCookie`
   (nœud XML source) fiabilise sélection/surlignage/tooltip, en
   particulier pour `<include>` (deux vues identiques de nœuds
   différents).
5. **RTL** : cesser de détruire `marginStart`/`paddingStart`
   (`XmlPreprocessor`), exposer un qualificateur direction (miroir
   `LayoutDirectionQualifier`), laisser le vrai framework faire
   (`layoutDirection` sur la Configuration du contexte de rendu) — sur
   appareil, RTL est GRATUIT une fois le qualificateur là (la référence
   doit l'émuler, pas nous).
6. **Animations/interactif de session** : `Choreographer_Delegate` de la
   référence (file d'animations + `doFrame`, 153 l.) montre le patron pour
   un pas-à-pas « bouton lecture » sur AVD/LayoutTransition ; sur appareil
   le vrai Choreographer existe — il s'agit de figer/avancer le temps
   proprement. Chantier indépendant, à ne pas mélanger avec le reste.
7. **Dialogs `windowIsFloating`** : la référence rend les fenêtres flottantes
   (`Layout.Builder`) ; le chrome maison ne connaît pas le mode — tout
   thème de dialog est rendu plein écran. À inscrire à l'étape chrome
   suivante.
8. **Accessibilité** : `validator/LayoutValidator` de la référence est
   l'ossature du « UI Check » d'Android Studio ; un équivalent léger
   (contraste, taille cible, `contentDescription` manquant) est jouable
   en pur — données déjà collectées dans `ViewInfoCollector`.

### 2.5 Stubs trompeurs : à brancher ou à supprimer

Le dépôt contient des classes homonymes AOSP **vides d'utilité** (coquilles
26-97 lignes, aucune référence) : `bridge/view/LayoutlibRenderer`
(26 l. — sur appareil le vrai hwui dessine, la coquille n'apporte rien),
`Choreographer_Delegate`/`ChoreographerCallbacks`/`HandlerMessageQueue`,
`bridge/impl/DelegateManager` (mock), le paquet `bridge/binding/`
(à brancher, cf. §2.4-3), `module-layout/session/*` (RenderSession 99 l.,
RenderAction 58 l., HardwareConfig, CanvasDelegate, LayoutlibCallbackImpl
— non consommés par le pipeline CodeIDE), `MiniLayoutLib.setProjectResources`
no-op (`MiniLayoutLib.java:81-86`), `ViewStubController` placeholder, la
chaîne `ninepatch/` (code mort). Recommandation : **une passe d'hygiène
dédidée** — chaque coquille est soit branchée (binding → §2.4-3 ;
ninepatch → `BitmapParser`), soit supprimée (philosophie « zéro code mort »
du projet). Le piège `ViewTagRegistry(true)` → classes
`jo.layoutlib.design.*` **inexistantes** (tout tag court partirait en
MockView via `MiniLayoutLib`) part avec elle.

### 2.6 Ce que le projet fait mieux (à préserver précieusement)

1. **Fidélité native structurelle** : vraies vues, vrai text shaping
   (HarfBuzz+Skia), vraies ombres/elevation, vrais ripples — la référence
   doit émuler tout ça (delegates graphiques, fontes du SDK, ombres
   logicielles) et n'y arrive pas toujours (l'écart de rendu texte entre
   preview AS et appareil est un problème CONNU de la référence).
2. **MockView supérieur + repli Material3** : cadre lisible + nom, et
   moins de MockView qu'Android Studio quand le thème hôte n'est pas
   Material.
3. **Vraies vues Material/AndroidX embarquées** (`ViewTagRegistry` :
   MaterialButton, Chip, Slider… sur le classpath, rendus par
   construction — la référence exige la dépendance du projet).
4. **ConstraintSolver maison** (Cassowary) sans embarquer la lib du
   projet — l'approximation est assumée (ADR 0116), le périmètre
   (chaînes/guidelines/barrières) est déjà large.
5. **i18n** : `PluralRules` CLDR + `XliffSpan` — même philosophie que la
   référence, implémentation propre et testée.
6. **Chrome 3.5.0** : dérivation thème, `LisibiliteChrome` pur,
   `SURIMPRESSION`, collecte duale — miroir propre de `Layout`/
   `visitAllChildren`, avec l'avantage des vraies fontes pour les
   glyphes.
7. **Discipline de tests** (~60 fichiers) au-dessus de la référence sur
   ce périmètre.

---

## 3. Conformité parent/enfant — l'état actuel et le modèle de la référence

### 3.1 L'état actuel (preuves dans le code)

Côté **éditeur**, AUCUNE règle de type d'enfant n'existe aujourd'hui :
la cible de dépôt est le conteneur le plus profond (`ZonesDepot.cibleSous`,
`ZonesDepot.kt:133-191`), la seule garde est « le parent est
sélectionnable » (`PreviewViewModel.appliquerDepot` :351-388), et les cinq
problèmes de famille (`ProblemesFamilles.kt:78-98`) portent tous sur des
ATTRIBUTS/RÉFÉRENCES (orphelin, référence introuvable, ancre invalide,
comportement manquant, dépendance circulaire) — jamais sur la STRUCTURE
enfants. Trois conséquences vérifiées :

| Cas | Comportement actuel | Chez la référence |
|---|---|---|
| **Déposer dans un AdapterView** (ListView/GridView/Spinner) | `AdapterView.addView` lève `UnsupportedOperationException` → catch global → **TOUT le rendu échoue** (bandeau erreur, plus d'aperçu) | dépôt **refusé à l'interaction** : `AdapterViewHandler.getPlaceholders → emptyList` (« AdapterView should use Java code to add content »), message « does not accept … as a child » (`DragDropInteraction.java:348-404`) |
| **Plusieurs enfants dans un ScrollView/HorizontalScrollView/NestedScrollView** | acceptés ; rendus empilés (comportement FrameLayout), aucun avertissement — la question est ouverte depuis `PREVIEW_PARENTS.md` §17.3 | `ScrollViewHandler.acceptsChild → layout.getChildCount() == 0` (`handlers/ScrollViewHandler.java:124-126`) : le 2ᵉ dépôt est refusé à l'interaction |
| **Enfants dans une vue FEUILLE** (TextView, ImageView, Button, CheckBox, ProgressBar…) | le texte source peut les contenir ; au rendu les enfants sont **skippés silencieusement** (`BridgeInflater.java:621-623`) ; aucun problème signalé | pas de placeholders d'enfants (handlers feuilles), le composant est inséré sans balise enfant par l'éditeur ; la validité vient du schéma |

Cas particuliers vérifiés : `DrawerLayout` (2 zones par gravité), 
`TextInputLayout` (1 champ), `ViewPager2` (pages), `TabLayout`,
`CoordinatorLayout` (comportements/ancres) — aucun contrôle de structure
n'existe pour eux non plus ; le contenu `RecyclerView` est vide (§2.4-3).

### 3.2 Le modèle de la référence (sources)

La référence a DEUX niveaux orthogonaux, bien séparés :

1. **Rendu (layoutlib)** : les VRAIES classes exécutent leurs propres
   contraintes — `AdapterView.addView` jette, TextView n'est pas un
   ViewGroup, le warning « ScrollView can host only one direct child »
   sort dans le log de rendu (`ILayoutLog`). Le moteur ne FAIT PAS la
   politique, il SUBIT le framework et le journalise.
2. **Éditeur (designer)** : la politique est déclarée par famille de vues
   dans des `ViewHandler`/`ViewGroupHandler`
   (`designer/.../uibuilder/api/ViewHandler.java`, `ViewGroupHandler.java`) :
   `acceptsChild(parent, child)` / `acceptsParent(child, newParent)`
   consultés PENDANT le drag (`DragDropInteraction.java:486-504`),
   `onCreate` (peut annuler la création), `onChildInserted`
   (post-insertion : wrappers, attributs), `getPlaceholders` (zones
   dessinées), `FillPolicy` (défauts de dimensions par parent). Le service
   `ViewHandlerManager` résout le handler par convention de nom puis par
   extension. C'est ainsi que AS sait : ScrollView un seul enfant,
   AdapterView aucun enfant par glisser-déposer, TabHost/Menu/TableLayout
   des structures dédiées, ConstraintLayout seul à accepter les helpers
   Guideline/Barrier/Flow.

### 3.3 Proposition — `PolitiqueEnfants` maison (miroir adapté)

Le projet a déjà `FamilleParent` (8 familles) et `ProblemesFamilles` —
l'architecture d'accueil existe. Proposition :

**A. Refus à l'interaction (miroir `acceptsChild`)** — une fonction pure
`PolitiqueEnfants.accepte(parent: FamilleParent, enfant: EntreeDepot): Decision`
(branchement dans `ZonesDepot.cibleSous` : le conteneur qui REFUSE sort de
la cible ; son rectangle se dessine barré + pill « AdapterView : contenu
par code » miroir référence). Règles initiales :

- FEUILLE (TextView, ImageView, Button, ImageButton, CheckBox, Switch,
  RadioButton, ToggleButton, ProgressBar, SeekBar, RatingBar,
  Chronometer, TextClock, ViewStub, Space, VideoView, SurfaceView,
  TextureView, AnalogClock, WebView…) : aucun enfant ;
- ENFANT_UNIQUE (ScrollView, HorizontalScrollView, NestedScrollView,
  ViewPager, ViewPager2, TextInputLayout, ViewAnimator/ViewFlipper/
  ViewSwitcher) : un seul ;
- ADAPTER (ListView, GridView, Spinner, ExpandableListView,
  RecyclerView*) : aucun enfant par palette — contenu par
  `tools:listitem`/`entries` (cf. §2.4-3) ;
- TABS/STRUCTURÉS (TabHost, TableLayout, NavigationView) : structures
  dédiées à une vague suivante ;
- DrawerLayout : 2 zones (gravité start/end vs contenu) — marquage
  visuel des zones, pas encore un refus.

**B. Problèmes de famille STRUCTURELS** (miroir `ProblemesFamilles`, sur
le TEXTE source — visible même hors drag) : `ENFANT_INTERDIT` (feuille
avec balises enfant), `ENFANT_UNIQUE_DÉPASSÉ` (scroll/vp/til avec ≥2),
`ADAPTER_AVEC_ENFANTS` (déposé en XML — crash de rendu garanti §3.1).
La carte de correctif propose l'action (déplacer dans le parent
grand-parent / supprimer).

**C. Côté LayoutLib** : ne pas répliquer les `UnsupportedOperationException`
en échec de document — attraper l'exception AU NŒUD et rendre un
`MockView` « AdapterView : contenu par code » + un `ILayoutLog`-like.
Aujourd'hui un seul mauvais nœud tue l'aperçu entier
(`RenduLayoutLib.kt:272-283`) : la référence, elle, isole les vues en
échec (`Bridge.createMockView`) et rend LE RESTE.

Coût : `PolitiqueEnfants` pur + table par balise (~40 lignes de données),
3 règles dans `ZonesDepot`, 3 genres dans `ProblemesFamilles`, 1 garde
côté LayoutLib. Tests purs sur les deux dépôts.

---

## 4. Palettes — enrichissement et réalisme

### 4.1 Inventaire actuel (vérifié)

`CataloguePalette.kt` (675 l., v0.166.3) : **41 tuiles** en 6 catégories —
Texte (4 : TextView, EditText, TextInputLayout+champ, AutoCompleteTextView),
Boutons (9 : Button, ImageButton, CheckBox, Switch, RadioButton,
ToggleButton, MaterialButton, Chip, FAB), Conteneurs (13 : LinearLayout V/H,
MaterialCardView, FrameLayout, RelativeLayout, CoordinatorLayout,
AppBarLayout, ScrollView, HorizontalScrollView, NestedScrollView,
ConstraintLayout, GridLayout, TabLayout, ViewPager2), Médias (2 :
ImageView, VideoView), Listes (4 : RecyclerView, Spinner, ListView,
GridView), Autres (10 : MaterialToolbar, ProgressBar, SeekBar, View
séparateur, Space, WebView, RatingBar, SearchView). Insertion façonnée par
`PlanInsertionFamille` (marge 16 dp, cellule 3×3 pour FRAME/COORDINATOR,
adhérence ≤28 dp pour RELATIVE, ancre FAB), jamais de ré-encapsulation
automatique.

### 4.2 Gaps contre la palette de la référence (priorisés)

La référence (palette « Palette » du designer) couvre ~10 catégories.
Trois manques ENTIERS côté CodeIDE — avec un paradoxe : **la plupart des
classes sont déjà enregistrées dans `ViewTagRegistry.java:191-264` et
embarquées** (Material 1.14.0), donc chaque tuile coûte : une
`EntreePalette` + une icône + le façonnage famille — pas de travail de
rendu.

**P0 — structurel (répare des parcours cassés)**

1. **Aides ConstraintLayout** : Guideline, Barrier, Group, Flow, Layer —
   LA catégorie absente qui empêche tout CL réaliste (le dépôt CL n'écrit
   que des contraintes `parent`). Registre déjà prêt
   (`ViewTagRegistry.java:227-233`).
2. **Rendre les listes visibles** : `rv` rend toujours vide (layoutManager
   neutralisé `CustomAttributeApplier.java:260-263`, `tools:itemCount`
   strippé `XmlPreprocessor.java:48-54`) ; `lv/gv/spn` vides sans
   `entries`. Chantier lié §2.4-3 (FakeAdapter) + tuile posant
   `android:entries="@array/…"` par défaut.
3. **RadioGroup** : les RadioButton existent sans leur conteneur (à
   enregistrer proprement, la classe n'est pas dans le registre).
4. **Interdire les dépôts casseurs** (§3.3-A) — une palette riche sans
   politique enfante AGGRAVE le §3.1 : plus de tuiles = plus de dépôts
   dans AdapterView.

**P1 — catégories à fort rendement (classes déjà embarquées)**

5. **Material/Design** : MaterialSwitch, Slider,
   LinearProgressIndicator, CircularProgressIndicator, MaterialDivider,
   ShapeableImageView, ExtendedFloatingActionButton, BottomAppBar,
   ChipGroup, MaterialAutoCompleteTextView, MaterialButtonToggleGroup,
   MaterialRatingBar, SearchBar, NavigationView, BottomNavigationView,
   NavigationRailView, CollapsingToolbarLayout, Snackbar-host (vue),
   Tooltip-host. (Vérifier par tuile : celles dont la classe n'est PAS
   une `View` — Snackbar, Tooltip, dialogs Material — restent hors
   palette : `ViewTagRegistry.java:210-222, 273-307` les référence à
   tort, à purger.)
6. **Date & heure** : DatePicker, TimePicker, CalendarView, Chronometer,
   TextClock, NumberPicker — enregistrés ET leurs attributs déjà appliqués
   (`MiscWidgetAttributes.java:100-214`).
7. **Conteneurs complémentaires** : DrawerLayout (enregistré :248),
   SwipeRefreshLayout (:247), ViewFlipper/ViewSwitcher (:135-136),
   ViewStub (:147), TabHost/TabWidget (:156-157),
   ExpandableListView (:301), TableLayout (+ TableRow).
8. **Avancées** : SurfaceView/TextureView (:116-117), GestureOverlayView
   (:161), ZoomControls (:158), QuickContactBadge (:144),
   CheckedTextView/MultiAutoCompleteTextView (:104, 162),
   `<include>`/`<merge>` (la référence les propose — notre inflater les
   gère déjà), `<view class=…>`.

**P2 — réalisme des défauts (le « être plus réaliste »)**

9. Extraits posant `android:text="TextView"` **littéral** → le panneau
   Problèmes signale aussitôt `TEXTE_CODE_DUR` (le défaut fabrique son
   propre problème !). Basculer les défauts sur `tools:text` (déjà
   converti au rendu par `XmlPreprocessor.java:44-46` ET déjà exposé par
   l'inspecteur) — `android:text` reste posable à la main.
10. FAB : `app:srcCompat="@drawable/ic_search"` référence un drawable
    absent d'un projet neuf → `@android:drawable/…` ou `tools:src`.
11. TextInputLayout : extrait OutlinedBox réaliste
    (`style="@style/Widget.Material3.TextInputLayout.OutlinedBox"`),
    accompagné du travail de rendu sur les `app:box*` (lus non appliqués,
    `AttributeApplier.java:583-587`).
12. Champs variés par `inputType` (password/email/number/multiline) —
    miroir la catégorie « Text Fields » de la référence.
13. **Hygiène `ViewTagRegistry`** (affecte le réalisme de TOUTE nouvelle
    tuile) : purger les non-Views (Snackbar, Tooltip,
    BottomSheetDialog(Fragment), MaterialTimePicker/DatePicker,
    SimpleAdapter, AlphabetIndexer, Toast…), dédoublonner
    (RecyclerView `:134`/`:235`, GridLayout `:125`/`:245`,
    ProgressBar), retirer l'alias `ViewPager3` et le tag à espace
    « Text EllipsizingTextView » (`:308`), corriger les LayoutManager
    mappés comme View (`:236-238` — GridLayoutManager N'EST PAS une vue).

### 4.3 Recommandation

Une vague unique « Palettes & conformité » (P14-c) qui livrerait P0+P1
par blocs testables (P0 d'abord), le P2-9/10 en même temps (coût nul,
retour vécu immédiat), et la purge du registre comme garde-fou de la
vague. Chaque tuile s'accompagne d'un test d'insertion + d'un rendu
réel (la classe est sur le classpath — la tuile doit rendre SANS
MockView, assertion Robolectric).
---

## 5. Preview Compose — ce que fait VRAIMENT Android Studio, et ce que ça change pour nous

### 5.1 La réponse à la question posée : oui, layoutlib

La preview Compose d'Android Studio **ne rend pas Compose elle-même** :
elle fabrique un petit **XML synthétique** contenant un `ComposeViewAdapter`
(une `View` fournie par `androidx.compose.ui:ui-tooling`, PAS un artefact
exotique) que le **layoutlib** gonfle ; l'adaptateur initialise le runtime
Compose et exécute la `@Composable` désignée par `tools:composableName`.
Preuves lues dans le clone :

- `compose-designer/docs/architecture.md:15-18` : « **Bridge**: The system
  does NOT render Compose directly. It generates a synthetic XML layout
  (via `ComposePreviewElementModelAdapter.toXml()`) that contains a
  customized `ComposeViewAdapter`. […] **Runtime**: When Layoutlib
  inflates this XML, the adapter initializes the Compose runtime and
  executes the `@Composable` function identified by the
  `tools:composableName` attribute. »
- `compose-designer/src/.../ComposePreviewRepresentation.kt:306-311` :
  « For every preview element a small XML is generated that allows
  Layoutlib to render a `@Composable` function. »
- `rendering/src/.../parsers/TagSnapshot.java:95-99` : le parser remplace
  `ComposeView` par `ComposeViewAdapter` quand `tools:composableName` est
  présent.
- `compose-designer/BUILD:255-277` : la dépendance de preview est
  `androidx.compose.ui:ui-tooling` (+ compose-compiler, activity-compose).
  **Aucune** trace de skiko ni d'un `ui-layoutlib`/`ComposeScene` : le
  rendu passe par le pipeline View/Canvas du layoutlib (skiaparser interne
  du prébuilt).
- Le point de vue extérieur le confirme : « Layoutlib has been the
  rendering backend for the Android Studio Layout Editor since the early
  days of XML layouts, and **Compose Preview reuses the same
  infrastructure** » (analyse publique, annexe A).

Pipeline complet observé : découverte `@Preview` via l'Analysis API
(multi-preview, `PreviewParameter`, wrappers — `PreviewElementFinderUtils`)
→ `Configuration` device/thème/locale appliquée au modèle → `NlModel` +
`LayoutlibSceneManager` **par élément** → `RenderService/RenderTask`
(thread de rendu unique, `RenderSession` standard) → image → extraction
de la hiérarchie Compose **par réflexion** sur l'adaptateur gonflé
(`getViewInfos()` → `ComposeViewInfoParser` → `ComposeViewInfo` :
bounds px + fichier/ligne — le JSON de l'overlay/sélection) →
`ComposeSceneComponentProvider` pour la sélection/navigation.
Erreurs typées (`ComposeRenderErrors` : `COMPOSE_PREVIEW_NOT_FOUND`,
`TIMEOUT_TO_LOAD_PREVIEW`…). Mode interactif : événements injectés à la
session (`RenderTask.triggerTouchEvent/triggerKeyEvent`,
`RenderTask.java:1060-1105`) avec classloader privé et horloge réelle.

### 5.2 Le « temps réel », mécanismes observés

1. **Fast Preview** : compilation Kotlin **en mémoire** (seules les
   fonctions modifiées), hot-swap dans le `ModuleClassLoader` — objectif
   < 1 s entre frappe et preview ; erreurs collectées par
   `RenderTaskPatcher.collectHotReloadErrors`.
2. **Re-render sans re-inflation** : `refreshExistingPreviewElements`
   (QUICK, `reinflate = false`) — la session Layoutlib est conservée,
   seule l'image repart.
3. **Recomposition vs re-render** : dans une session, l'interactif
   (clics, animations) vit DANS le runtime — l'image est re-demandée au
   moteur ; le « live » de l'édition passe par la re-compilation.
4. **Qualité adaptée au zoom** (`DefaultRenderQualityPolicy`), horloges
   (`SteppingSessionClock` 500 ms statique / `RealTimeSessionClock`
   interactif), refresh regroupés par topic avec invalidation.

### 5.3 Confrontation au cadrage existant (PREVIEW.md §4, PC1–PC4)

Le cadrage maison a déjà tranché la voie : **interpréteur** sur l'arbre
résolu du LSP Kotlin, runtime Compose EMBARQUÉ dans le pack preview,
processus `:preview` isolé, live-edit par **diff d'identité** des
fonctions, état `remember` préservé (PC1–PC4), séquencé après la
stabilisation LSP Kotlin, étapes C0–C3. L'audit AOSP **ne contredit pas
ce choix — il le conforte sur 4 points et le précise sur 3** :

Confirmations :
- **l'hôte du rendu doit être une `View` Android** (`ComposeViewAdapter`
  chez AS). Chez nous : un `ComposeView` natif (runtime embarqué) — plus
  simple, pas besoin de l'adapter de la référence (il n'existe que pour
  tromper layoutlib-JVM) ;
- **l'overlay a besoin d'un protocole d'extraction dédié**
  (`getViewInfos` → fichier/ligne + bounds) — prévoir l'équivalent
  tôt (c'est le sésame sélection/surlignage/navigation, exactement comme
  `ViewInfo` côté XML) ;
- **le live-edit = diff d'identité + état préservé** : AS fait le diff
  de CLASSES recompilées, nous ferons le diff de FONCTIONS résolues —
  même sémantique, latence meilleure attendue (pas de compilateur
  D8 par frappe — PC1 valide) ;
- **l'interactif = injection d'événements + horloge maîtrisée** : miroir
  du forwarding `pointer` déjà prévu (§4.1), garde-fous PC4 = classloader
  privé + bornes de recomposition (AS : sandbox + kill de session).

Précisions apportées par l'audit :
1. **Rôle du LayoutLib maison** : la question « est-ce que la preview
   Compose utilise aussi le layoutlib ? » reçoit deux réponses selon le
   côté. Chez AS : oui, PAR NÉCESSITÉ (pas de framework Android sur le
   desktop). Chez CodeIDE : **le rôle d'« hôte du framework » est déjà
   tenu par le vrai Android** — le LayoutLib maison n'est pas requis
   pour RENDRE Compose. Il reste requis pour TOUT LE RESTE, et c'est la
   recommandation de cette étude : passer la composition **DANS la
   session existante** (mêmes `Configuration`/profil, même
   `ConfigChrome`/décor pour `showSystemUi`, mêmes résolveurs de
   ressources/thème du projet, même `ViewInfoCollector`) — un
   `ComposeView` dans la session remplace le `ComposeViewAdapter` de la
   référence, XML synthétique ou inflateur direct. **Une seule
   machinerie, deux dialectes** (XML, Compose), comme la référence.
2. **Découverte `@Preview`** : AS découvre par l'Analysis API (graphe
   multi-preview). Côté maison : l'équivalent Analysis API est le
   **modèle de symboles du LSP Kotlin** (K3+) — déjà identifié
   prérequis PREVIEW.md §4.2 ; l'audit précise le grain attendu :
   FQN de la composable, paramètres `@Preview` (device/widthDp/heightDp/
   showBackground/showSystemUi/locale…), graphe multi-preview.
3. **Garde-fou spécifique** : AS classe des échecs types
   (`COMPOSITION_LOCAL_NOT_FOUND`, `VIEW_MODEL_INSTANTIATION`…) —
   nos futures erreurs d'interprétation gagneront un catalogue
   typé dès C0 (pas de bandeau générique).

### 5.4 Phases recommandées (rattrapage du C0-C3 existant)

| Phase | Contenu | Écho référence |
|---|---|---|
| C0 (spike) | interpréter une `@Composable` triviale sur l'arbre résolu, la composer dans un `ComposeView` **dans la session LayoutLib existante** (décor `ConfigChrome` off par défaut — `showSystemUi` opt-in miroir `@Preview`), mesurer latence + mémoire | « XML synthétique dans la session » (`toPreviewXml` + `RenderTask`) |
| C1 | statique persistant, live-edit par diff d'identité, garde-fous PC4 ; catalogue d'erreurs typées | Fast Preview + `ComposeRenderErrors` |
| C2 | ressources/thème du projet dans la composition (réutilise les résolveurs) ; extraction bounds/fichier:ligne pour l'overlay | `ComposeViewInfoParser` |
| C3 | interactif : forwarding pointer + horloge, stateful, 30 fps cible PREVIEW.md §6 | `triggerTouchEvent` + `RealTimeSessionClock` |

Aucune décision à re-prendre : PC1–PC4 tiennent ; le seul arbitrage
nouveau est **D8** (où vit la composition : dans la session LayoutLib —
recommandé — vs panneau séparé).

---

## 6. Plan de chantiers proposé au propriétaire

Les deux chantiers déjà validés passent en tête (confirmés par le
propriétaire) ; l'analyse ci-dessus découpe la suite en blocs
indépendants, ordonnés par impact vécu :

| # | Chantier | Contenu | Deps |
|---|---|---|---|
| S1 | **PE13-suite** (validé) | section « système » dans l'arbre (consommer les `ViewType`) + réglage « mode gestuel » au menu Calques (`ModeNavigation`→`ConfigChrome`) | — |
| S2 | **Redimension fidèle** | C1 compensation de préhension + C4 transition (+ C2/C3 selon arbitrages D2/D3) — §1.6 | — |
| S3 | **Bugs ponctuels LayoutLib** | ids dynamiques stables (DynamicIdMap maison), `layout_column`/`rowSpan`, gravity relative — §2.3 ; c'est un PR court, tests directs | — |
| S4 | **Conformité parent/enfant** | `PolitiqueEnfants` + refus à l'interaction + 3 problèmes structurels + isolation d'échec au nœud côté LayoutLib — §3.3 | — |
| S5 | **Palettes & registre** | P0 (aides CL, RadioGroup, listes visibles) puis P1 (Material/Date&Time/conteneurs) + défauts `tools:` + purge `ViewTagRegistry` — §4 | S4 (politique AVANT la vague de tuiles), S6 (listes visibles) |
| S6 | **Preview de listes** | brancher `bridge/binding` (FakeAdapter, `tools:listitem`, itemCount, entries étendu) — §2.4-3 | — |
| S7 | **Attributs fidèles (1/2)** | thème prévisualisé appliqué aux vues (`ContextThemeWrapper` thème résolu) + `textAppearance`/`defStyleAttr` par famille — §2.4-1/2 | — |
| S8 | **ViewInfo cookie + RTL + hygiène stubs** | viewCookie (sélection `<include>`), qualificateur direction + fin de la destruction `marginStart`, suppression/branchement des coquilles — §2.4-4/5, §2.5 | S3 |
| S9 | **Compose C0** | spike §5.4 (après stabilisation LSP Kotlin, hors séquencement court) | LSP Kotlin K3-K4 |

S2 et S3 sont courts et indépendants — ils peuvent précéder S1 si le
propriétaire préfère réparer le geste avant d'ajouter. S5 exige S4
(politique d'abord, tuiles ensuite). S6 et S4 sont complémentaires
(AdapterView : refuser les enfants ET montrer un contenu factice).

## 7. Décisions à trancher par le propriétaire (D1-D8)

| # | Question | Recommandation de l'étude |
|---|---|---|
| D1 | **Compensation de préhension** : le coin suit-il le doigt (C1), ou conserve-t-on « cible = distance(ancre, doigt) » à la lettre de la maquette v6 ? | **OUI à C1** : la maquette v6 décrit la maquette WEB souris ; le tactile impose le rattrapage (§1.3, §1.4) ; le geste reste « ancre coin opposé + plancher 8 dp », donc fidèle à l'ESPRIT v6 |
| D2 | **Snap sémantique** ±10 dp vers wrap/match avec lignes-guides (C2) ? | Oui — mais P2 : d'abord C1+C4 ; le snap exige d'exposer la taille wrap mesurée par le moteur |
| D3 | **Par-layout au relâchement** (C3) : retirer `layout_weight` au passage en fixe ? réécrire les marges RelativeLayout ? poser `tools:layout_editor_absoluteX/Y` en CL ? | Oui pour le POIDS (cas A, le plus confondant) ; oui pour les marges relatives ; les `tools:layout_editor_*` CL seulement si le fichier utilise déjà le dialecte `tools` |
| D4 | **PolitiqueEnfants** : refus à l'interaction + problèmes de famille, ou problèmes seulement ? | Les DEUX (refus = pas de mauvaise expérience au drag ; problème = le texte source reste surveillé) |
| D5 | **Vague palettes** : P0 seul / P0+P1 / tout (P0+P1+P2) ? | P0+P1 avec défauts réalistes (P2-9/10) ; P2-11 (TextInputLayout OutlinedBox) lié à S7 |
| D6 | **Listes visibles** : items factices côté moteur (FakeAdapter branché, `tools:listitem`/`itemCount` honorés) ? | Oui — miroir `AdapterBinding` de la référence ; sans cela RecyclerView/ListView restent des tuiles mortes |
| D7 | **Stubs** : brancher (animations, ninepatch) ou supprimer les coquilles ? | Supprimer sauf `bridge/binding` (branché par S6) et ninepatch (à brancher dans `BitmapParser`) — zéro code mort |
| D8 | **Compose** : la composition vit-elle DANS la session LayoutLib (décor `ConfigChrome` partagé, `showSystemUi` opt-in) ou dans un panneau séparé ? | DANS la session (§5.3-1) : une machinerie, deux dialectes — miroir exact de la référence ; PC1–PC4 sinon inchangés |

---

## Annexe A. Sources consultées (clonées pour cette étude)

- `reference/aosp-layoutlib` — `platform/frameworks/layoutlib` (HEAD
  `7b05b277`) : `bridge/src/com/android/layoutlib/bridge/` (Bridge.java,
  BridgeRenderSession.java, MockView.java, impl/RenderSessionImpl.java,
  impl/RenderAction.java, impl/binding/*, impl/Layout.java,
  android/view/BridgeInflater.java, android/content/res/…,
  bars/Config.java, bars/StatusBar.java, …), `common/src/…`.
- `reference/aosp-adt-idea` — `platform/tools/adt/idea` (sparse
  `designer/`, `compose-designer/`, `rendering/`) :
  uibuilder/api/ViewHandler.java, ViewGroupHandler.java,
  handlers/ScrollViewHandler.java, handlers/AdapterViewHandler.java,
  handlers/ViewHandlerManager.java,
  uibuilder/scene/target/ResizeBaseTarget.java,
  ResizeWithSnapBaseTarget.java, CommonDragTarget.kt,
  common/scene/Scene.java, handlers/relative|constraint/absolute targets,
  surface/interaction/DragDropInteraction.java, model/NlComponentHelper.kt,
  model/FillPolicy.java ;
  compose/preview/ComposePreviewRepresentation.kt,
  AnnotationFilePreviewElementFinder.kt, PreviewElementFinderUtils.kt,
  ComposePreviewElementModelAdapter.kt, docs/architecture.md ;
  com/android/tools/rendering/RenderService.java, RenderTask.java,
  compose/RenderTaskPatcher.kt, compose/ComposeViewInfoParser.kt,
  compose/ComposeRenderErrors.kt, parsers/TagSnapshot.java.
- `reference/aosp-androidx` — `androidx/androidx` (sparse
  `compose/ui/ui-tooling*`) : localisation de `ComposeViewAdapter`
  (artefact `androidx.compose.ui:ui-tooling` ; absence confirmée de tout
  module `ui-layoutlib` dans le dépôt public).
- Dépôts maison : `CodeIDE` v0.169.0 (799581c0), `LayoutLib` v3.5.0
  (ca66144) — fichiers cités en ligne dans le corps.
- Analyse publique corroborante (rendu texte/preview vs appareil) :
  hotswan.dev, « Compose Preview Renders Differently Than Your Device » ;
  documentation développeur `@Preview` (developer.android.com) —
  contexte seulement.

## Annexe B. Audit détaillé — table par sous-système (condensé)

| Sous-système | Fichier(s) projet | Fichier(s) référence | Écart essentiel |
|---|---|---|---|
| Session | module-layout session/RenderSessionImpl (99 l.), RenderAction (58 l.) | impl/RenderSessionImpl (1230 l.), RenderAction (519 l.) | modes EXPAND/SHRINK non honorés (le mode est forcé EXACTLY dans le chemin de l'app démo, `module-inflater RenderService.java:494-505`) ; frames/animation, handleScrolling, dispatch d'entrées absents ; CodeIDE pilote la session au profil (RenduLayoutLib) — l'écart concerne le socle LayoutLib seul |
| Inflation | BridgeInflater (2106 l.), ViewTagRegistry (~150 tags), ViewFactory (246 l.) | android/view/BridgeInflater (711 l.), PhoneLayoutInflater | pas d'`onCreateView` par préfixes ni `viewInflaterClass` ; bugs 2.3 |
| Attributs | AttributeApplier (1643 l.) + 6 familles ; module-attributes/registry (minces : Framework 17 attrs) | BridgeTypedArray, Bridge.parseStyleable (enumValueMap) | tout par setters ; defStyle/textAppearance perdus (§2.4-1) |
| Ressources | ResourceResolverImpl (962 l.), 9 qualificatifs | AssetManager_Delegate, FolderConfiguration (18) | pas de `@font` (0 occ.), assets/raw, direction, uiMode |
| Thèmes | ThemeResolverImpl (395 l.), NativeThemeResolver | Resources_Theme_Delegate, resolveThemeAttribute | thème des vues = hôte (§2.4-2) |
| Drawables | DrawableResolverImpl (1096 l.) | DrawableInflater_Delegate & co | `createFromXml` sur resources hôte, échec → null silencieux (:540-545) ; AVD/ripple approximatifs |
| 9-patch | ninepatch/ (code mort) | NinePatchInputStream + delegates | non branché |
| Listes | bridge/binding/ (mort), XmlPreprocessor strippe tools:listitem | impl/binding + setupViewInContext | §2.4-3 |
| ViewInfo | ViewInfoCollector (410 l.) | ViewInfo + getViewKey + MergeCookie | pas de viewObject/cookie (§2.4-4) |
| Chrome | bridge/bars (PE13) | impl/Layout (725 l.), bars/ | cutout/edge-to-edge/translucides/floating absents (§2.4-7) ; assumé G1/G2 |
| Animations | stubs passthrough | Choreographer_Delegate (153 l.) | pas de temps de session maîtrisé |
| Système | bridge/view|android/* (coquilles), BridgeContext (107 l.) | os/app/util delegates complets | coquilles trompeuses (§2.5) |
| A11y | — | validator/LayoutValidator | absent (§2.4-8) |
| Texte | TextFamilyAttributes + framework natif | ICU/Typeface delegates + BidiRenderer jar | trous : drawableStart/End (RTL), @font (§2.4) |
| Meilleur côté projet | MockView hachuré + repli M3 ; DexViewFactory (CodeIDE) ; ConstraintSolver Cassowary ; PluralRules/XliffSpan ; vraies vues Material embarquées ; tests | — | §2.6 |
