# Étude — Le chrome système possédé par le moteur de rendu (phase suivante)

> **Objet** : valider (ou corriger) l'hypothèse du propriétaire — « déplacer
> le chrome au niveau de la session de rendu, c'est l'approche de l'IDE de
> bureau de référence ; le moteur possède le décor, il peut donc dispatcher
> les insets, gérer le mode edge-to-edge (barres en surimpression) et
> exposer les vues système séparément ; une seule source de vérité pour
> les dimensions ; l'application hôte ne fait plus que passer la
> configuration (thème, titre, nuit, barre de navigation on/off) » — et
> évaluer le plan d'exécution en 5 étapes sûres proposé.
>
> **Méthode** : lecture directe de la source réelle du moteur de rendu du
> SDK (paquet `com.android.layoutlib.bridge` de l'AOSP, révision
> android-32 — la plus récente consultable : les révisions ultérieures du
> miroir utilisé n'embarquent plus ce paquet), mise en regard du code
> courant des deux dépôts (`CodeIDE` v0.167.0, `LayoutLib` v3.4.9). Tous
> les faits AOSP cités ci-dessous ont été vérifiés dans les fichiers
> sources, listés en annexe B.
>
> **Verdict en une ligne** : l'hypothèse est **correcte sur toute la
> ligne** ; le plan en 5 étapes est viable et dans le bon ordre, avec
> trois nuances de conception à trancher (drapeaux de barres, drapeau
> nuit, insets explicites) et un garde-fou à poser sur les hauteurs.

---

## 0. Décisions tranchées — délégation du propriétaire (2026-10-23)

Le propriétaire a délégué les trois nuances (« je te laisse décider
pour les 3 nuances du chantier chrome »). Elles sont tranchées dans le
sens recommandé par l'étude — chaque décision reprend la
recommandation correspondante (§3.2, §3.3) avec sa raison d'être ;
l'ADR 0137 du chantier les portera comme décisions D1–D3.

| # | Nuance | Décision | Raison (résumé) |
|---|---|---|---|
| 1 | **Drapeaux de barres** (`showStatusBar`/`showNavigationBar`) | **SURCHARGES utilisateur** — le défaut est DÉRIVÉ du thème + du matériel (`windowFullscreen`, boutons logiciels), comme la référence ; un drapeau ne sert qu'à forcer/masquer explicitement | Miroir du `Layout.Builder` de la référence : la présence des barres est une donnée du THÈME et du MATÉRIEL simulés, pas une case à cocher IDE ; zéro constante dupliquée côté hôte |
| 2 | **Drapeau nuit** (`darkMode` de `bars/Config`) | **SUPPRIMÉ** — la nuit est le RÉGIME DES RÉSOLVEURS (`ThemeResolver`, `setNightMode` déjà branché), pas un drapeau du simulateur de barres | La référence n'a PAS de drapeau nuit : les barres relisent le thème résolu ; un doublon `darkMode` créerait DEUX sources de vérité contradictoires |
| 3 | **Insets** | **EXPLICITES** — le moteur CALCULE les hauteurs (ressource framework `status_bar_height`/`navigation_bar_height` avec repli constante, garde-fou G1 : 25 dp, pas 24) et les DISPATCHE au contenu ; PAS la machinerie `ViewRootImpl_Accessor` de la référence | Rendu DÉTACHÉ : il n'y a pas de fenêtre réelle, la machinerie ViewRoot vivrait sur des invariants absents ; des insets calculés et injectés donnent la même sémantique `fitsSystemWindows` sans la fragilité |

Conséquence sur le plan : l'étape 2 retire `darkMode` de `Config`
(migration des appelants vers le régime nuit des résolveurs), l'étape 3
pose le calcul + la dispatch explicite des insets (et le mode
SURIMPRESSION par-dessus), l'étape 5 conserve les glyphes maquette +
`LisibiliteChrome` (mieux que le `windowLightStatusBar` absent de la
référence v32 — nuance §3.5 maintenue : pas de vraie action bar par
réflexion).

---

## 1. Comment fait réellement le moteur de référence (layoutlib AOSP)

### 1.1 La session de rendu crée et possède le décor

Dans `com.android.layoutlib.bridge.impl.RenderSessionImpl`, la session ne
demande **jamais** à l'IDE de dessiner le chrome. À l'inflation :

```java
mViewRoot = new Layout(mLayoutBuilder);          // le décor
mContentRoot = ((Layout) mViewRoot).getContentRoot();
...
view = mInflater.inflate(mBlockParser, mContentRoot);  // le layout utilisateur DANS le décor
```

- `mViewRoot` est un **`Layout extends FrameLayout`** (`bridge/impl/Layout.java`)
  : c'est l'équivalent du `DecorView` — il appartient au moteur.
- `mContentRoot` est un `FrameLayout` posé à `android:id/content` : le
  layout de l'utilisateur y est inflaté.
- `Layout.Builder(SessionParams, BridgeContext)` décide **côté moteur** de
  tout ce qui entoure le contenu (voir §1.3).
- Le dessin final est `renderAndBuildResult(mViewRoot, mCanvas)` : le
  bitmap contient décor + contenu, en UNE passe, UNE seule vérité.

L'IDE n'intervient que par **`SessionParams`** : profil matériel
(`HardwareConfig` : écran, densité, `hasSoftwareButtons()`), thème, locale,
mode de rendu, label et icône de l'application (`getAppLabel()`,
`getAppIcon()`), drapeaux (`isForceNoDecor()`, `RenderParamsFlags`), et le
`LayoutlibCallback` (classes du projet, menus, `ActionBarCallback`). Il n'y
a **aucune** écriture de chrome côté IDE — pas un pixel.

### 1.2 Les barres vivent dans le moteur (`bridge/bars`)

Le paquet `com.android.layoutlib.bridge.bars` contient la famille :

| Classe | Rôle vérifié |
|---|---|
| `CustomBar extends LinearLayout` | Base : inflate son layout XML **embarqué dans le moteur** (`status_bar.xml`, `navigation_bar.xml`, variante 600dp), charge les icônes via `IconLoader`/`SysUiResources` (sélection par densité, direction RTL, version de plateforme), lit les couleurs du thème via `getBarColor(attrCouleur, attrTranslucide)` |
| `StatusBar` | Fond = `statusBarColor` (seulement si `windowDrawsSystemBarBackgrounds`), sinon noir par version de plateforme ; icônes wifi/batterie + horloge à l'heure **figée** `Config.getTime(version)` |
| `NavigationBar` | Icônes retour/accueil/récents (`ic_sysbar_*.png`), variante `quick_step` (gestes) ; verticale en paysage téléphone (< 600dp), horizontale en tablette ; fond = `navigationBarColor` sous les mêmes conditions |
| `TitleBar` / `FrameworkActionBar` / `AppCompatActionBar` / `BridgeActionBar` / `OverflowMenuAdapter` | Voir §1.4 |
| `Config` | **Pas un porteur de drapeaux** : un simulateur de version de plateforme (`showOnScreenNavBar(version)` = nav bar à partir d'ICS ; heure figée par version — « 11:00 » pour Android 11+ ; répertoires d'icônes `/bars/v9` à `/bars/v28`) |

Fait important pour l'étape 5 : la status bar du moteur de référence ne
**dessine pas ses icônes en code** — elle inflate un XML du moteur et
charge des images. Le contenu glyphé de CodeIDE
(`9:41 / 5G ▮▮▮ 100%`, v0.166.5) est un choix produit assumé, pas un
écart technique.

### 1.3 Une seule source de vérité pour les dimensions

`Layout.Builder` ne porte **aucune constante** de hauteur. Chaque taille
vient des **ressources du framework**, résolues contre le thème courant :

```java
mStatusBarSize   = getFrameworkAttrDimension("status_bar_height", 25); // défaut layoutlib : 25dp
mNavBarSize      = getFrameworkAttrDimension("navigation_bar_height" /* ou _width */, 48);
mFrameworkActionBarSize = getFrameworkAttrDimension("actionBarSize", 25);
mTitleBarSize    = getDimension(appcompat "windowTitleSize", 25);
```

Les **présences** elles-mêmes sont dérivées, pas passées par l'IDE :

- status bar ⇐ `!windowFullscreen && !windowIsFloating` (attributs **du thème**) ;
- action bar framework ⇐ tableau `android:windowNoTitle` × `android:windowActionBar`
  (ActionBar / TitleBar / Rien) ; action bar AppCompat ⇐ attributs **appcompat**
  (`windowNoTitle`, `windowActionBar`) et `context.isAppCompatTheme()` ;
- nav bar ⇐ `Config.showOnScreenNavBar(versionPlateforme)` **ET**
  `HardwareConfig.hasSoftwareButtons()` (le **profil matériel**) — plus le
  sens (bas/côté) selon l'orientation et la largeur minimale (600dp).

Autrement dit : l'IDE ne dit même pas « montre la nav bar » ; il décrit
l'appareil et le thème, le moteur conclut. Le seul échappatoire offert à
l'IDE est négatif : `SessionParams.isForceNoDecor()` retire tout le décor.

### 1.4 Le titre, la flèche retour et les menus : de la configuration pure

`BridgeActionBar.setupActionBar()` montre le contrat exact — tout vient de
la session, rien de l'initiative du moteur :

```java
mCallback = params.getLayoutlibCallback().getActionBarCallback();
setTitle(params.getAppLabel());                       // titre = label de l'application
setSubtitle(mCallback.getSubTitle());                 // sous-titre (IDE)
setIcon(params.getAppIcon());                         // icône (IDE)
setHomeAsUp(mCallback.getHomeButtonStyle()            // FLÈCHE RETOUR = config de l'IDE
        == HomeButtonStyle.SHOW_HOME_AS_UP);
```

- `AppCompatActionBar` va chercher le **vrai** layout `abc_screen_toolbar`
  dans les ressources résolues du projet et instancie le vrai
  `WindowDecorActionBar` **du projet** par réflexion via `LayoutlibCallback` ;
  les menus viennent de `getCallBack().getMenuIds()` (les vrais `menu.xml`
  du projet). C'est la fidélité maximale : l'aperçu montre l'action bar
  réelle de l'application.
- En complément, `RenderSessionImpl.setActiveToolbar()` détecte un
  `CoordinatorLayout`+`AppBarLayout`+`CollapsingToolbarLayout`+`Toolbar`
  **dans le layout utilisateur** et y pose le titre de l'application — le
  moteur s'adapte aussi aux documents qui portent leur propre barre.
- Les menus de l'action bar sont **marqués** dans l'arbre système
  (`ViewType.ACTION_BAR_MENU`, `ACTION_BAR_OVERFLOW`,
  `ACTION_BAR_OVERFLOW_MENU` — cf. §1.5), ce qui permet à l'IDE de les
  traiter comme des vues système interactives.

### 1.5 Les insets : le decor est une racine de fenêtre

`Layout` **agit comme ViewRoot** (« in layoutlib, Layout will act as view
root for most purposes », commentaire du code) :

```java
@Override public void requestFitSystemWindows() {
    ViewRootImpl root = AttachInfo_Accessor.getRootView(this);
    if (root != null) {
        ViewRootImpl_Accessor.dispatchApplyInsets(root, this);  // VRAIS WindowInsets
    }
}
```

Le contenu est placé **par les règles de layout** du décor : le
`mContentRoot` est lié `BELOW` la status bar / l'action bar et `ABOVE`
la nav bar (règles `RelativeLayout` entre vues système identifiées
`android_layoutlib_statusBar`, `_navBar`, `_frameworkBar`,
`_appCompatActionBar`). Le mode « barres en surimpression » de l'époque
s'obtient par la **translucence** (`windowTranslucentStatus` /
`windowTranslucentNavigation`) : la barre devient semi-transparente
(40 % de noir), la règle de placement est retirée, et le contenu passe
**sous** la barre. Les vues du contenu qui consomment
`fitsSystemWindows` reçoivent les insets par la machinerie réelle
`ViewRootImpl`.

Note de version : android-32 est antérieur au régime « edge-to-edge
impératif » d'Android 15 (`setDecorFitsSystemWindows(false)` structurant).
Le principe reste le même — **le moteur, propriétaire des barres, calcule
et distribue les insets** — mais la mécanique exacte des révisions
récentes devra être revérifiée contre une source plus fraîche au moment
de l'implémentation (cf. §6, risque R4).

### 1.6 Deux arbres exposés : contenu / système

La partie la plus intéressante pour notre étape 4. À chaque rendu :

```java
mSystemViewInfoList = visitAllChildren(mViewRoot, 0, 0, extendedMode, false);
```

et dans `visitAllChildren`, **au passage du content root**, le collecteur
produit DEUX listes d'un coup :

```java
if (viewGroup == mContentRoot) {
    // pour chaque enfant : [0] = infos SANS offset (arbre système), [1] = infos AVEC offset
    mViewInfoList = childrenWithOffset;   // ← arbres de CONTENU, bounds TRANSLATÉES dans l'espace du bitmap
    return childrenWithoutOffset;         // ← enfants pour l'arbre SYSTÈME
}
```

- `RenderSession.getViewInfos()` → **contenu seul**, bounds **absolues dans
  le bitmap** (l'offset du décor est déjà appliqué, avec `scrollX/Y` et
  `translationX/Y` défaqués) ;
- `RenderSession.getSystemViewInfos()` → **décor complet**, où chaque
  nœud système est un `SystemViewInfo extends ViewInfo` portant un
  `ViewType` (marquage des menus, bouton débordement, popup).

L'IDE n'a donc **aucun recalage à faire** : la séparation contenu/système
et la translation bitmap sont des responsabilités du moteur. C'est très
exactement ce que CodeIDE fait aujourd'hui en aval avec
`decalageContenuDansArbreRendu` — sauf que là-bas c'est un hack IDE.

---

## 2. État des lieux jjoblab (avant refactor)

### 2.1 Côté CodeIDE (`:preview:xml`)

- **`ChromeSysteme`** (517 l.) construit le chrome en vraies vues autour
  du contenu : status bar 24 dp avec glyphes `9:41 / 5G ▮▮▮ 100%`
  (`GlyphesBarreStatut`, dessin en code), action bar 56 dp avec titre 20
  lisible par luminance (`LisibiliteChrome`), barre de navigation 48 dp
  optionnelle (`GlyphesBarreNavigation`), `windowBackground` appliqué.
  Les couleurs viennent du `ThemeResolver` (`statusBarColor` →
  `colorPrimaryDark` → défauts maquette) et les présences de
  `windowNoTitle`/`windowActionBar`/`windowFullscreen` (correctif C1,
  ADR 0135). Les **hauteurs 24/56/48 sont des constantes IDE**.
- **`RenduLayoutLib.rendreLayout`** : inflate →
  `envelopperChromeSiNecessaire` (chrome IDE) → `layoutEngine.render` →
  `dessiner` → `decalageContenuDansArbreRendu(racine)` (remontée de la
  chaîne des parents en défaquant le scroll) → `collecterVues` qui
  translate les bounds `ViewInfoCollector` dans l'espace bitmap.
- La **config circule déjà** proprement dans `RequeteRendu`
  (`themeName`, `labelActivite` — résolu par `titreActionBar` avec
  replis `@string/` — `nuit`, `barreNavigation`, `paysage`,
  `echellePolice`, `langue`, `profil`, `classpath`, `dossierRes`).

### 2.2 Côté LayoutLib

- **`bridge/bars/` en stubs** : `Config` (drapeaux
  `showStatusBar`/`showNavigationBar`/`showTitleBar`/`darkMode` +
  `apiLevel` + `getTotalBarsHeightDp()`), `StatusBar` (24 dp +
  `getBackgroundColor(nightMode)`), `NavigationBar` (48 dp), `TitleBar`,
  `FrameworkActionBar` (21 l.), `NavigationHandle` (pilule gestuelle
  48×4 dp). Ces hauteurs **dupliquent** celles de `ChromeSysteme`.
- **`resources/api/` squelettique** : `RenderParams` porte déjà un
  drapeau `decor` (isDecor/setDecor) mais ni label, ni thème ;
  `SessionParams`, `ActionBarCallback`, `ViewType` sont des coquilles
  nom/valeur sans sémantique.
- **`ViewInfoCollector`** (réel) : arbre **unique**, bounds absolues
  (racine-relative), paddings/marges/visibilité/profondeur, helpers de
  hit-test (`findAtPoint`). Pas de séparation contenu/système ; le
  `SystemViewInfo` de `impl/` étend `Layout.ViewInfo` avec focus/visibilité
  — pas la sémantique AOSP (marquage `ViewType` des vues système).
- **Points réutilisables précieux** : `module-themes` (`ThemeResolver` —
  le chrome peut lire le thème **côté moteur**), `module-resources`
  (`SystemResourceRepository`/`FrameworkResourceRepository` — la lecture
  des dimens framework `status_bar_height`/`navigation_bar_height` est
  **déjà possible côté moteur**, c'est la source de vérité manquante),
  `setNightMode(nuit)` déjà branché sur les résolveurs,
  `module-layout/LayoutEngineImpl` (mesure/layout/dessin),
  `bridge/view/ViewRootImpl_Delegate`+`AttachInfo_Accessor` (utilitaires
  minces, à étoffer si l'on veut la machinerie insets), `InsetUtil`
  (attention : parsing padding/margin XML, **rien à voir** avec les
  insets de fenêtre).

---

## 3. Validation du plan en 5 étapes

| Étape proposée | Verdict | Ajustements recommandés |
|---|---|---|
| 1. Déplacer `ChromeSysteme` tel quel dans LayoutLib (stubs `bars/*`) | ✅ Exact | Transporter les glyphes maquette à l'identique ; garder les constantes dp dans un premier temps ; le recalage IDE continue de fonctionner tel quel (la remontée parent fonctionne aussi bien sur un décor moteur) |
| 2. Brancher `RenderParams`/`Config` (decor, showStatusBar, showNavigationBar, darkMode) | ✅ avec 3 nuances | Le drapeau `decor` existe déjà (stub). Réduire `showStatusBar`/`showNavigationBar` à des **surcharges utilisateur** (défaut = thème+matériel, comme la référence), et **supprimer `darkMode`** (redondant avec le régime nuit des résolveurs) |
| 3. Insets : hauteurs calculées, dispatchées au contenu, mode surimpression | ✅ avec simplification assumée | Ne pas répliquer la machinerie `ViewRootImpl` : le rendu jjoblab est détaché de fenêtre. Insets **explicites** calculés par le moteur propriétaire des barres, padding par défaut, surimpression = contenu plein cadre + insets exposés |
| 4. Séparer vues système/contenu dans `ViewInfoCollector`, retirer `decalageContenuDansArbreRendu` | ✅ Exact (c'est LE modèle de la référence) | Reproduire le collecteur duel (contenu translaté bitmap / système `SystemViewInfo`+`ViewType`) ; supprimer le recalage **et** sa doc ; donner leur vraie sémantique aux stubs `ViewType`/`SystemViewInfo` |
| 5. Apparence : icônes, `windowLightStatusBar`, mode gestuel, flèche retour + menu | ✅ avec choix assumés | Garder les glyphes maquette (identité produit) ; `LisibiliteChrome` (luminance) **remplace avantageusement** `windowLightStatusBar` (absent du moteur de référence v32) ; pilule gestuelle = `NavigationHandle` + option Calques ; flèche retour + menu = config `ActionBarCallback` (pas la vraie action bar par réflexion) |

### 3.1 Étape 1 — le déménagement à l'identique

Le plan dit « aucun changement visuel, donc aucun risque de régression » :
c'est correct et c'est aussi ce que permet l'architecture actuelle, car
`ChromeSysteme` ne dépend que d'objets déjà moteurs (`Context`,
`ThemeResolver`, couleurs thème). Concrètement :

- créer dans `LayoutLib` un **décor** (équivalent `Layout` AOSP :
  `FrameLayout`/`LinearLayout` possédant un content root) et une factory
  construite depuis la config de session — les stubs `bars/*` deviennent
  de vraies vues (`CustomBar` jjoblab = gonfleur du chrome maquette) ;
- `envelopperChromeSiNecessaire` migre côté moteur (le chrome IDE
  devient un simple passe-plat : plus aucune vue chrome construite dans
  `:preview:xml`) ;
- les glyphes `GlyphesBarreStatut`/`GlyphesBarreNavigation` et
  `LisibiliteChrome` partent **tels quels** (logique pure + dessin, déjà
  testés — `ChromeSystemeTest` migre avec eux) ;
- garder 24/56/48 dp en dur à ce stade (l'étape 2 introduira la lecture
  des dimens framework, cf. garde-fou G1).

Piège à éviter : ne PAS collecter les vues du chrome dans
`ViewInfoCollector.collect(racine)` à ce stade — CodeIDE continuant de
collecter sur la racine utilisateur, rien ne change. Le décor moteur
encadre sans être vu.

### 3.2 Étape 2 — la config

Correspondances vérifiées contre la référence :

| Config souhaitée | Chez la référence | Recommandation jjoblab |
|---|---|---|
| `decor` | `SessionParams.isForceNoDecor()` (négatif) | Garder `RenderParams.decor` (déjà là), positif, défaut = vrai quand un thème d'activité est résolu |
| `showStatusBar` | **N'existe pas** : dérivé du thème (`windowFullscreen`, `windowIsFloating`) | Dérivation par défaut dans le moteur + surcharge booléenne nullable pour le menu Calques |
| `showNavigationBar` | **N'existe pas** : dérivé du profil (`hasSoftwareButtons()`) + plateforme | Idem : dérivation du profil (`ProfilPeripherique` porte déjà la matérialité des boutons ? sinon l'ajouter) + surcharge (l'interrupteur PE11j « Barre de navigation » reste une **surcharge utilisateur légitime**, la référence n'offre simplement pas ce levier) |
| `darkMode` | **N'existe pas** : le nuit est un **régime de résolution de ressources** | Le supprimer de `Config` ; le moteur lit le même régime nuit que les résolveurs (`setNightMode` déjà branché) |
| titre de l'action bar | `SessionParams.getAppLabel()` (+ `setActiveToolbar` pour les Toolbar du projet) | Faire descendre `labelActivite` (résolu côté IDE aujourd'hui) dans `RenderParams.appLabel` ; le moteur pose le titre |
| flèche retour / menus | `ActionBarCallback` (`HomeButtonStyle`, `getMenuIds()`, `isOverflowPopupNeeded()`) | Remplacer la coquille `ActionBarCallback` par cette sémantique réelle ; CodeIDE la remplit depuis le manifest/les réglages |

« Supprimer les constantes en double » : confirmé — les hauteurs
appartiennent au moteur, lues depuis les ressources framework
(`module-resources` sait le faire), constantes maquette en repli.

### 3.3 Étape 3 — les insets

La référence dispatche de **vrais** `WindowInsets` par
`ViewRootImpl_Accessor.dispatchApplyInsets` parce qu'elle contrôle une
fausse racine de fenêtre attachée. Le rendu jjoblab est **détaché** (mesure
par `LayoutEngineImpl`, dessin vers bitmap) : recommandation — des insets
**explicites et déterministes** :

```kotlin
class InsetsDecor(val haut: Int, val bas: Int, val gauche: Int, val droite: Int)
// + mode: PADDE_PAR_DEFAUT | SURIMPRESSION
```

- par défaut, le content root est posé sous/au-dessus des barres
  (l'équivalent des règles `BELOW`/`ABOVE` de la référence) ;
- en surimpression, le contenu est mesuré **plein cadre** et les insets
  sont **exposés** (l'IDE peut dessiner ses repères, une future
  surcouche « cutout » les consommera, et un document qui padding lui-même
  par `fitsSystemWindows` reste simulable en aval) ;
- la transparence des barres suit les attributs thème
  (`windowTranslucentStatus`/`windowTranslucentNavigation`), miroir de
  `CustomBar.getBarColor` — et le nouveau régime Android 15+
  (`setDecorFitsSystemWindows(false)` structurant) se branchera sur ce
  même exposant « mode ».

C'est une simplification **assumée** de la référence (qui passe par la
machinerie réelle), pas une perte de fidélité : le résultat visible
(padding ou surimpression) est identique et reproductible en test.

### 3.4 Étape 4 — la séparation des arbres

Reproduire le collecteur duel vérifié au §1.6 :

- `ViewInfoCollector` gagne un mode décoré : infos **contenu** translatées
  dans l'espace bitmap (l'offset du décor appliqué à la collecte, scroll
  et translation défaqués — l'invariant déjà documenté dans
  `RenduLayoutLib` v0.134.2 « draw, hit-test et surlignage partagent le
  même espace de coordonnées » est **préservé**, mais il devient une
  propriété **du moteur**) ;
- infos **système** : `SystemViewInfo` prend sa vraie sémantique
  (sous-classe d'info + `ViewType` — `BARRE_STATUT`, `BARRE_NAVIGATION`,
  `ACTION_BAR`, `MENU_ACTION_BAR`, `DEBORDEMENT`…), pour que l'IDE puisse
  exclure le chrome de l'arbre des composants et, plus tard, l'y
  réintroduire en section « système » ;
- **suppression complète** de `decalageContenuDansArbreRendu` et de sa
  documentation — le recalage n'a plus de raison d'être ; le contrat
  `InfoVueRendu` (bounds absolues bitmap) ne bouge pas d'un pixel.

Point d'attention : `ResultatRendu.nombreVues` compte aujourd'hui le
chrome (`compterVues(vueRendue)`). Décision recommandée : compter **le
contenu seul** (la pastille « rendue • n vues » doit parler à
l'utilisateur de SES vues) — petit changement de contrat à écrire noir
sur blanc dans l'ADR et le CHANGELOG.

### 3.5 Étape 5 — l'apparence

- **Icônes status bar** : la référence charge des icônes embarquées du
  moteur ; jjoblab garde ses glyphes maquette (déjà livrés et testés en
  v0.166.5, identité produit). Les DEUX sont légitimes ; l'important est
  que le moteur les possède.
- **`windowLightStatusBar`** : absent du moteur de référence consulté
  (icônes à couleurs fixes par version de plateforme).
  `LisibiliteChrome` (couleur du contenu par luminance du fond) le
  **remplace avantageusement** — garder, et l'honorer en plus
  explicitement si l'attribut est posé dans le thème.
- **Mode gestuel** : `NavigationHandle` (48×4 dp) existe en stub —
  le brancher comme variante de la barre de navigation (option Calques
  ou config), en plus des trois boutons actuels.
- **Flèche retour + menu dans l'action bar** : la référence instancie la
  **vraie** action bar du projet (réflexion sur
  `WindowDecorActionBar` + vrais `menu.xml`). Pour jjoblab :
  - hors périmètre recommandé (fragilité réflexive, écart maquette) ;
  - à la place : glyphes flèche retour + débordement dans l'action bar
    factice, **pilotés par la config** (`ActionBarCallback.homeAsUp`,
    `isOverflowPopupNeeded()`) — et, à terme, l'inflate des menus du
    projet (`LayoutlibCallback.getMenuIds()`) reste possible comme
    chantier séparé.
- **Hauteurs** : basculer sur les dimens framework (cf. G1).

---

## 4. Écarts assumés avec la référence (choix produits)

| Sujet | La référence | Choix jjoblab recommandé | Statut |
|---|---|---|---|
| Action bar | Vraie action bar AppCompat du projet (réflexion) | Action bar factice maquette + config (titre, flèche, menu) | Assumé |
| Icônes système | Images embarquées du moteur, heure par version de plateforme | Glyphes maquette `9:41 / 5G ▮▮▮ 100%` figés | Assumé (v0.166.5) |
| Lisibilité des barres | Couleurs fixes par version | Luminance (`LisibiliteChrome`) + `windowLightStatusBar` explicite si posé | Amélioration |
| Insets | Vraie machinerie `ViewRootImpl`/`WindowInsets` | Insets explicites calculés par le moteur (rendu détaché) | Simplification assumée |
| Drapeaux de barres | Aucun (thème + matériel) | Dérivation identique + surcharges utilisateur (menu Calques) | Extension assumée |
| Simulateur de plateforme | `Config` = heures/icônes par version | Non pertinent (pas de simulation multi-versions dans CodeIDE) | Hors périmètre |

---

## 5. Risques et tests

**Garde-fous à écrire dans l'ADR :**

- **G1 — hauteurs** : le défaut de la référence pour la status bar est
  **25 dp**, pas 24 ; la valeur réelle d'un appareil vient de
  `android:dimen/status_bar_height` (~24 dp sur la plupart des
  téléphones). Règle : hauteur = ressource framework résolue par le
  moteur, **repli** = constante maquette actuelle ; comparer au pixel près
  contre la maquette `apercu-xml.html` et arbitrer les deltas avec le
  propriétaire (risque visuel minime, à ne pas laisser silencieux).
- **G2 — zéro changement visuel à l'étape 1** : captures avant/après
  obligatoires (status bar, action bar, nav bar, jour/nuit, thème
  sans barre), même bitmap au pixel près.
- **G3 — contrat AIDL inchangé** pour `InfoVueRendu` (bounds absolues
  bitmap) ; seul `nombreVues` change de sémantique (contenu seul) —
  l'écrire dans CHANGELOG.

**Risques :**

- **R1** — deux dépôts, deux versions : le chantier impose un **LayoutLib
  3.5.0** (API : décor, config, insets, collecte duale) consommé en
  composite (`-Pfreres.sources`) puis par JitPack ; prévoir la montée
  versionnée comme pour 3.4.9.
- **R2** — tests à déplacer/réécrire : `ChromeSystemeTest` (7 tests)
  migre vers LayoutLib (les vues s'y testent déjà en JVM — cf.
  `BridgeInflaterTest`, `MockViewTest`) ; `RenduLayoutLibV3Test`,
  `MesureRenduParentsTest` (hauteur racine fil 779 dp — le décor ne
  change PAS la racine utilisateur, vérifier les hauteurs totales),
  `GeometrieFeuilleApercuTest` (le bitmap gagne/perd la nav bar selon la
  surcharge — vérifier le retrait du bord courant), la doc du recalage à
  purger de `PREVIEW.md`/ADR 0135 adjacents.
- **R3** — régression d'interaction : le hit-test/surlignage repose sur
  l'invariant bounds bitmap ; les tests de sélection (P6, X4) doivent
  passer **sans modification** après l'étape 4 — sinon l'étape est
  incomplète.
- **R4** —Android 15+ edge-to-edge impératif : vérifier au moment de
  l'étape 3 la mécanique post-32 (`setDecorFitsSystemWindows`) contre
  une source plus récente et l'adosser au mode SURIMPRESSION.

**Coût confirmé** : oui, c'est un refactor des deux dépôts avec des tests
de rendu à refaire — l'évaluation du propriétaire est juste. Le périmètre
est néanmoins circonscrit : le transport du bitmap (v0.166.4), le zoom,
la géométrie de la feuille (v0.166.5) ne sont pas touchés.

---

## 6. Recommandations

1. **Valider le plan en 5 étapes tel quel**, avec les nuances des §3.2
   (drapeaux = surcharges, suppression `darkMode`), §3.3 (insets
   explicites) et §3.5 (pas de vraie action bar par réflexion).
2. **Ouvrir l'ADR 0137** (« Le moteur de rendu possède le chrome
   système ») au lancement du chantier : options (A) chrome IDE — statu
   quo, (B) chrome moteur avec collecte duale — recommandé, (C) copie
   intégrale du comportement de la référence (vraie action bar,
   insets ViewRoot) — refusé (fragilité, écart maquette). Statut
   « Accepté » à la fin de l'étape 4.
3. **API côte moteur** (esquisse, à figer dans l'ADR) :
   `RenderParams { decor, appLabel, themeName, surchargesBarres?, }` +
   `ActionBarCallback { homeAsUp, sousTitre, menus, popupDebordement }` +
   résultat enrichi `{ bitmap, infosContenu (bitmap), infosSysteme
   (SystemViewInfo), insetsDecor, modePlacement }`.
4. **Ordre inchangé** : 1 (déménagement, G2) → 2 (config + suppression
   constantes dupliquées, G1) → 3 (insets) → 4 (collecte duale + retrait
   du recalage, R3) → 5 (apparence). Chaque étape se pousse verte
   indépendamment.
5. Après l'étape 4, `nombreVues` compte le contenu seul (pastille
   « rendue • n vues »), et l'arbre de composants peut afficher — plus
   tard — une section « système » depuis `getSystemViewInfos` (moteur de
   référence : les nœuds marqués `ViewType` servent exactement à ça).

---

## Annexe A — Correspondances de fichiers/classes

| AOSP layoutlib (android-32, lu) | jjoblab aujourd'hui | jjoblab cible (plan) |
|---|---|---|
| `impl/RenderSessionImpl` (session, rendu, collecte duale) | `RenduLayoutLib` (CodeIDE) + `RenderService`/`LayoutEngineImpl` (LayoutLib) | session moteur possédant le décor |
| `impl/Layout` + `Builder` (décor FrameLayout, décisions thème/matériel) | — (le décor est `ChromeSysteme`, côté IDE) | décor + factory côté LayoutLib |
| `bars/CustomBar` (base inflateur XML/icônes) | stubs `bars/*` (21–68 l.) | vraies vues maquette transportées |
| `bars/Config` (simulateur de plateforme) | `bars/Config` (drapeaux) | drapeaux réduits à des surcharges ; nuit = résolveurs |
| `bars/StatusBar`/`NavigationBar`/`TitleBar`/`NavigationBar` quickstep/`NavigationHandle` | `GlyphesBarreStatut`/`GlyphesBarreNavigation` (CodeIDE) + stubs | glyphes maquette moteurs + pilule gestuelle |
| `bars/BridgeActionBar`/`AppCompatActionBar`/`FrameworkActionBar` + `ActionBarCallback` | `FrameworkActionBar` stub + `ActionBarCallback` coquille | action bar factice + callback réel (titre/flèche/menus) |
| `impl/SystemViewInfo` + `ViewType` (marquage) | `impl/SystemViewInfo` (focus/visibilité — sémantique différente) | vraie sémantique `ViewType` |
| `RenderSession.getViewInfos()`/`getSystemViewInfos()` | `ViewInfoCollector.collect()` + `decalageContenuDansArbreRendu` (IDE) | collecte duale moteur, recalage supprimé |
| `SessionParams` (`isForceNoDecor`, `getAppLabel`…) + `LayoutlibCallback` | `RenderParams` (decor) + `RequeteRendu` (CodeIDE) | config enrichie moteur |

## Annexe B — Sources consultées (toutes lues intégralement ou par sections ciblées)

Moteur de référence (AOSP `platform/tools/layoutlib`, révision android-32,
via miroir public de sources android.jar) : `bridge/impl/RenderSessionImpl.java`
(1 196 l.), `bridge/impl/Layout.java` (613 l.), `bridge/impl/RenderAction.java`,
`bridge/impl/SystemViewInfo.java`, `bridge/impl/SysUiOverlay.java`,
`bridge/impl/DisplayCutoutView.java`, `bridge/bars/Config.java` (173 l.),
`bridge/bars/StatusBar.java` (144 l.), `bridge/bars/NavigationBar.java`
(144 l.), `bridge/bars/CustomBar.java` (268 l.), `bridge/bars/TitleBar.java`,
`bridge/bars/BridgeActionBar.java` (162 l.), `bridge/bars/AppCompatActionBar.java`
(310 l.), `bridge/bars/FrameworkActionBar.java`, `bridge/bars/OverflowMenuAdapter.java`,
`bridge/bars/FrameworkActionBarWrapper.java`.

Dépôts jjoblab : `CodeIDE` v0.167.0 — `ChromeSysteme.kt` (517 l.),
`RenduLayoutLib.kt` (1 170 l., sections rendreLayout/collecterVues/
decalageContenuDansArbreRendu/envelopperChromeSiNecessaire/titreActionBar/
reconfigurerSiNecessaire), `InfoVueRendu.kt`, `AligneurElementsXml.kt` ;
`LayoutLib` v3.4.9 — `bars/*` (stubs), `ViewInfoCollector.java` (250 l.),
`bridge/impl/SystemViewInfo.java`, `bridge/util/InsetUtil.java`,
`bridge/view/WindowManagerImpl.java`, `bridge/view/ViewRootImpl_Delegate.java`,
`resources/api/{RenderParams,SessionParams,ActionBarCallback,ViewType}.java`,
`RenderService.java`, `module-layout/LayoutEngineImpl.java`.
