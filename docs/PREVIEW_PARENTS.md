# Aperçu XML — familles de parents (partie III de la spécification)

> Partie III de la spécification de l'aperçu. La partie I (`PREVIEW_UI.md`) couvre l'écran Attributs, la partie II
> (`PREVIEW_UI_ECRANS.md`) les autres écrans. Celle-ci décrit **comment l'aperçu traite un parent selon sa famille**
> (`RelativeLayout`, `CoordinatorLayout`, `FrameLayout`, `AppBarLayout`…) et ses enfants : inspecteur, dépôt, popup de
> placement, calque de relations, problèmes, suppression.
>
> **Source de vérité visuelle et comportementale : la maquette v6** (`docs/mockups/apercu-xml.html`, section « Parents
> et enfants », quatre documents d'exemple). **Décision** : ADR 0135. **Brief d'exécution** : `PE12-parents-brief.md`.
> Statut : **proposé**. Rien dans ce document ne modifie le dépôt tant que PE12 n'est pas lancé.

## 0. Résumé

| Sujet | Aujourd'hui (v0.153.0) | Cible |
|---|---|---|
| Genres de parent | `GenreParent` : `AUCUN`, `CONSTRAINT_RACINE`, `CONSTRAINT`, `LINEAR`, `AUTRE` | `FamilleParent` : `CONSTRAINT`, `LINEAR`, `FRAME`, `RELATIVE`, `COORDINATOR`, `APPBAR`, `AUTRE`, `AUCUN` |
| Sections de l'enfant | Contraintes ou « Dans le parent » (`LINEAR`) ; rien pour `AUTRE` | Une section « Dans le parent » par famille (§ 5) |
| Dépôt | Barre d'insertion en colonne ou en ligne, pour tous les parents | Barre (CONSTRAINT, LINEAR, APPBAR), 9 cellules (FRAME, COORDINATOR), règles vers un frère ou le parent (RELATIVE) |
| Popup « Où placer ? » | Avant / Dedans / Après | Jeu de positions et sous-contrôles selon la famille (§ 7) |
| Calque Contraintes | Racine ConstraintLayout seulement | Calque **Relations** : contraintes, règles, ancres (§ 8) |
| Problèmes | `CLASSE_ABSENTE`, `CIBLE_TACTILE` | + attribut ignoré par le parent, référence introuvable, ancre invalide, dépendance circulaire, comportement manquant (§ 9) |
| Suppression | `EditionAttributsXml.supprimer` retire l'élément | Retire aussi les références mortes (§ 10) |
| Palette | 16 widgets | + `FrameLayout`, `RelativeLayout`, `CoordinatorLayout`, `AppBarLayout`, `MaterialToolbar`, `FloatingActionButton` (§ 11) |

## 1. Audit de l'existant (clone `origin/main`, v0.153.0)

Les constats ci-dessous viennent de la lecture du dépôt, pas de la maquette.

| # | Constat | Fichier | Effet visible |
|---|---|---|---|
| P1 | `GenreParent` n'a que cinq valeurs ; tout parent autre que `ConstraintLayout`/`LinearLayout` est `AUTRE` | `CatalogueAttributs.kt` | Un enfant de `RelativeLayout` n'a ni « Positions relatives » ni « Dans le parent » ; il n'y a aucun moyen de poser `layout_below` depuis l'inspecteur |
| P2 | `genreParent(classeParente, parentEstRacine)` compare des noms simples de classe | `CatalogueAttributs.kt` | `MaterialCardView`, `NestedScrollView`, `AppBarLayout` (sous-classes) tombent dans `AUTRE` |
| P3 | `ZonesDepot.cibleSous` calcule l'index d'après l'orientation : horizontal sinon vertical | `ZonesDepot.kt` | Un `FrameLayout` ou un `RelativeLayout` est traité comme une colonne : on ne peut pas choisir la gravité ni une règle |
| P4 | `ZonesDepot.emplacement` ne sait dessiner qu'une barre | `ZonesDepot.kt` | Aucun repère de cellule ni de règle |
| P5 | `PlacementPopup.Position` = `AVANT`, `DEDANS`, `APRES` | `PlacementPopup.kt` | « Après » dans un `FrameLayout` n'a pas de sens visuel (c'est « Devant ») ; dans un `RelativeLayout` il faut un côté |
| P6 | `InsertionXml.contraindre` ne façonne que pour `ConstraintLayout` | `InsertionXml.kt` | Le dépôt dans un autre parent n'ajoute aucun attribut de position |
| P7 | `InfoVueRendu` ne porte ni famille ni attributs de layout | `InfoVueRendu.kt` | Le moteur ne peut pas distinguer un `FrameLayout` d'un `LinearLayout` sans orientation |
| P8 | `EditionAttributsXml.supprimer` retire l'élément sans regarder les références | `EditionAttributsXml.kt` | `layout_below="@id/x"` survit à la suppression de `x` : la règle devient muette, l'aperçu change sans explication |
| P9 | `ProblemesPreview` ne connaît que deux règles | `ProblemesPreview.kt` | Rien ne signale un `layout_weight` dans un `FrameLayout`, une ancre invalide ou une boucle de règles |
| P10 | `RenommageIdentifiant.renommer` remplace déjà toutes les occurrences `@id/` | `RenommageIdentifiant.kt` | **Rien à faire** : la maquette montre le même résultat ; seul un test de non-régression est ajouté |
| P11 | La palette n'a ni `FrameLayout`, `RelativeLayout`, `CoordinatorLayout`, `AppBarLayout`, `MaterialToolbar`, ni `FloatingActionButton` | `CataloguePalette.kt` | Les parents concernés ne peuvent pas être créés depuis l'aperçu |

## 2. Modèle : la famille de parent

### 2.1 Définition

Une **famille** est l'ensemble des règles avec lesquelles un `ViewGroup` interprète les `LayoutParams` de ses enfants.

```kotlin
public enum class FamilleParent {
    AUCUN,        // la vue est la racine
    CONSTRAINT,   // ConstraintLayout (racine ou non) et MotionLayout
    LINEAR,       // LinearLayout et sous-classes hors AppBarLayout
    FRAME,        // FrameLayout, MaterialCardView, NestedScrollView, ScrollView, CollapsingToolbarLayout…
    RELATIVE,     // RelativeLayout
    COORDINATOR,  // CoordinatorLayout
    APPBAR,       // AppBarLayout (sous-classe de LinearLayout : testée AVANT LINEAR)
    AUTRE,        // tout le reste : GridLayout, TableLayout, DrawerLayout, ViewPager…
}
```

### 2.2 Calcul

1. **À l'exécution du rendu** (`RenduLayoutLib`) : on remonte la chaîne `Class.getSuperclass()` de la vue réelle jusqu'à la première
   classe connue, dans cet ordre : `AppBarLayout` → `CoordinatorLayout` → `ConstraintLayout`/`MotionLayout` → `RelativeLayout` →
   `LinearLayout` → `FrameLayout`. Le test se fait **par nom de classe** (`Class.name`), jamais par `instanceof` : `:preview:xml` reste
   pur (zéro Material, zéro AndroidX en dépendance de compilation).
2. **Repli** : si la vue est un `MockView` (classe introuvable), ou si l'information manque, `FamilleParent.deBalise(nomSimple)`
   applique la même table sur le nom de balise.
3. Le résultat voyage dans `InfoVueRendu.famille` (nom de l'enum, `String`, **ajouté à la fin** du Parcel et de `writeToParcel`, puis aux
   `equals`/`hashCode` ; test de parité AIDL).

### 2.3 Mapping avec l'existant

| `GenreParent` | `FamilleParent` |
|---|---|
| `AUCUN` | `AUCUN` |
| `CONSTRAINT_RACINE`, `CONSTRAINT` | `CONSTRAINT` (la différence ne sert qu'à `CatalogueAttributs.genreParent(..., parentEstRacine)` ; la section Contraintes est la même) |
| `LINEAR` | `LINEAR` |
| `AUTRE` | `FRAME`, `RELATIVE`, `COORDINATOR`, `APPBAR` ou `AUTRE` |

`GenreParent` reste le temps de PE12a (alias interne), puis disparaît : `CatalogueAttributs.sections(classe, famille)`.

## 3. Sémantique Android à respecter, famille par famille

La maquette reproduit ces règles ; le moteur réel (LayoutLib) les applique lui-même. **La spec les fixe parce que le dépôt calcule
des attributs à écrire, des cellules à dessiner et des problèmes à signaler** : ils doivent être d'accord avec le framework.

### 3.1 FrameLayout (et `MaterialCardView`)

- Chaque enfant est posé selon `android:layout_gravity` ; **défaut `start|top`**. Les enfants s'empilent : **l'ordre du document est
  l'ordre de peinture** (le dernier est au premier plan). Les marges s'additionnent au placement
  (`FrameLayout.onLayout`, marges pleines : au centre, `+ marginStart − marginEnd`).
- `layout_weight`, `layout_below`, `layout_constraint*` sont **ignorés**.
- `MaterialCardView` : même famille ; `android:orientation` n'existe pas sur lui (la maquette ne l'écrit plus).

### 3.2 RelativeLayout

- Un enfant est positionné par des **règles** : vers un frère (`layout_below`, `above`, `toStartOf`, `toEndOf`, `alignStart`, `alignEnd`,
  `alignTop`, `alignBottom`, `alignBaseline`, et les variantes `Left`/`Right`) ou vers le parent (`alignParentStart/End/Top/Bottom`,
  `centerHorizontal`, `centerVertical`, `centerInParent`). Sans aucune règle : coin haut-début.
- Les règles forment un **graphe orienté** ; `RelativeLayout` le trie (`DependencyGraph.getSortedViews`) et **lève**
  `IllegalStateException("Circular dependencies cannot exist in RelativeLayout")` à la première boucle. LayoutLib remonte l'erreur :
  l'aperçu garde le dernier rendu valide (§ 12).
- Marges : `below`/`above`/`toEndOf`/`toStartOf` ajoutent la marge de **l'ancre ET** celle de la vue (`ancre.marge + vue.marge`) ;
  `alignStart`/`alignTop`… n'ajoutent que la marge de la vue.
- Avec `match_parent` et une seule borne posée, la vue remplit **jusqu'au bord du parent** (pas au-delà).
- `android:gravity` (conteneur) et `android:ignoreGravity` existent mais **ne sont pas simulés** par la maquette (§ 14).

### 3.3 CoordinatorLayout

- **Sans ancre** : `layout_gravity` (défaut `start|top`) dans le rectangle intérieur **réduit des marges** (`layoutChild` : au centre,
  la moitié de `marge début − marge fin`, contrairement à `FrameLayout`).
- **Avec `app:layout_anchor`** : `getDesiredAnchoredChildRect` — (1) point d'ancrage sur la vue ancre, donné par
  `layout_anchorGravity` (**début/haut par défaut**) ; (2) décalage de l'enfant selon son `layout_gravity` (**centre par défaut** quand
  il n'y en a aucun) ; (3) `constrainChildRect` recadre dans le parent **en respectant les marges** (les marges ne décalent pas, elles bornent).
- L'ancre doit être une vue du `CoordinatorLayout`, ni la vue elle-même ni l'un de ses descendants (sinon exception à l'exécution).
- `app:layout_behavior="@string/appbar_scrolling_view_behavior"` sur un enfant défilant : posé sous l'`AppBarLayout`, hauteur restante.
  `layout_insetEdge`, `layout_dodgeInsetEdges`, `layout_keyline` : écrits, **non simulés**.

### 3.4 AppBarLayout

- Sous-classe de `LinearLayout` **vertical** : `layout_weight` et `layout_gravity` s'y appliquent, `orientation` est fixée.
- `app:layout_scrollFlags` (`scroll`, `exitUntilCollapsed`, `enterAlways`, `enterAlwaysCollapsed`, `snap`, `snapMargins`) **sur chaque
  enfant** : ignoré hors d'une `AppBarLayout`. `liftOnScroll`, `liftOnScrollTargetViewId`, `expanded` sur la barre elle-même.

### 3.5 ConstraintLayout et LinearLayout

Inchangés par cette vague (PE8b, PE9). La seule modification : ils passent par la même entrée `FamilleParent`.

## 4. Architecture et API

### 4.1 Modules et fichiers

| Fichier | Module | Rôle | Pur ? |
|---|---|---|---|
| `FamilleParent.kt` (nouveau) | `:preview:xml` | enum, `deClasses(chaine)`, `deBalise(nom)` | oui |
| `InfoVueRendu.kt` | `:preview:xml` | + champ `famille: String` (après `orientation`, dernier du Parcel) | non (Parcelable) |
| `RenduLayoutLib.kt` | `:preview:xml` | calcule la famille depuis la vue réelle | non |
| `ArbreAttributsXml.kt` (nouveau) | `:preview:xml` | lit le texte : pour chaque élément, balise, id, attributs `layout_*`/`app:layout_*`, span, parent | oui (`XmlPullParser` déjà utilisé par `AligneurElementsXml`) |
| `RelationsParents.kt` (nouveau) | `:preview:xml` | relations (arêtes) d'un parent, cycles, `viseCycle(…)`, attributs orphelins | oui |
| `PlanInsertionFamille.kt` (nouveau) | `:preview:xml` | cible + point/cellule + marge → liste d'attributs, marges, rectangle fantôme, libellé | oui |
| `InsertionXml.kt` | `:preview:xml` | `ajouterAttributs(extrait, attributs)` : généralise `contraindre` | oui |
| `ZonesDepot.kt` | `:preview:xml` | `cibleSous(vues, x, y, contenu)` retourne aussi un `PlanInsertionFamille` pour FRAME/RELATIVE/COORDINATOR | oui |
| `PlacementPopup.kt` | `:preview:xml` | positions selon la famille (§ 7) | oui |
| `SuppressionSure.kt` (nouveau) | `:preview:xml` | édition unique : retire l'élément **et** les références mortes | oui |
| `ProblemesPreview.kt` | `:preview:xml` | + 5 genres (§ 9) et leurs correctifs | oui |
| `CatalogueAttributs.kt` | `:preview:xml` | `sections(classe, famille)` ; sections de conteneur par famille | oui |
| `CataloguePalette.kt` | `:preview:xml` | + 6 entrées | oui |
| `VuePreviewXml.kt` | `:preview:xml` | calque Relations, repères cellule / fantôme | non (Canvas) |
| `PreviewViewModel.kt`, `VoletAttributsFragment.kt`, `PopupPlacement` | `:feature:preview` | branchement | non |

### 4.2 Signatures proposées (indicatives, à ajuster à la dette du dépôt)

```kotlin
public object FamilleParent … {
    public fun deClasses(chaine: List<String>): FamilleParent          // noms qualifiés, de la classe à Object
    public fun deBalise(nomBalise: String): FamilleParent              // « RelativeLayout », « androidx.coordinatorlayout.widget.CoordinatorLayout »
}

public object RelationsParents {
    public enum class TypeRelation { REGLE_FRERE, REGLE_PARENT, ANCRE, CONTRAINTE }
    public data class Relation(val source: String, val cible: String?, val attribut: String, val type: TypeRelation)
    public fun relations(parent: ArbreAttributsXml.Noeud): List<Relation>
    public fun cycle(parent: ArbreAttributsXml.Noeud): List<String>?              // chemin @id, premier cycle, ou null
    public fun fermeraitBoucle(parent: ArbreAttributsXml.Noeud, vue: String, attribut: String, cible: String): Boolean
    public fun attributsOrphelins(famille: FamilleParent, attributs: Map<String, String>): List<String>
}

public object PlanInsertionFamille {
    public data class Plan(
        val attributs: List<Pair<String, String>>,   // à ajouter dans l'extrait, noms qualifiés
        val marges: Marges,                          // dp, multiples de 8
        val fantome: RectangleDepot?,                // bitmap, pour le calque
        val libelle: String,
    )
    public fun cellule(famille: FamilleParent, parent: InfoVueRendu, ligne: Int, colonne: Int, ancre: InfoVueRendu?, densiteDpi: Int): Plan
    public fun cote(parent: InfoVueRendu, reference: InfoVueRendu, cote: Cote, margeDp: Int, croix: PointF?, densiteDpi: Int): Plan
    public fun versParent(parent: InfoVueRendu, ligne: Int, colonne: Int, margeDp: Int, point: PointF?, densiteDpi: Int): Plan
    public fun auPointeur(parent: InfoVueRendu, freres: List<InfoVueRendu>, point: PointF, taille: TailleDp, densiteDpi: Int): Plan
}
```

### 4.3 Écriture : une seule édition

Comme pour PE7d/PE8, **une interaction = une `EditionXml`** passée à `SourceApercu.remplacer` (un seul annuler). `InsertionXml.inserer`
reste l'unique chemin d'insertion ; le plan ne fait que **choisir les attributs** de l'extrait avant l'appel
(`InsertionXml.ajouterAttributs(extrait, plan.attributs + plan.marges)`). Les règles de renumérotation (`renumeroter`) et de
`xmlns:app` manquant sont celles de `EditionAttributsXml.composerEdition`.

## 5. Inspecteur : sections par famille

`CatalogueAttributs.sections(classe, famille)` ajoute **deux** groupes, après les sections propres à la classe et avant les sections
communes.

### 5.1 Sections du conteneur (la vue sélectionnée EST un parent)

| Famille de la vue | Section | Contrôles (courant = ★) |
|---|---|---|
| LINEAR | `Conteneur` (existant) | `orientation` ★, `gravity` ★, `baselineAligned`, `weightSum`, `showDividers`, `divider`, `dividerPadding`, `measureWithLargestChild`, `clipChildren`, `clipToPadding`, `animateLayoutChanges` |
| FRAME | `Conteneur` | `foregroundGravity`, `measureAllChildren`, `clipChildren`, `clipToPadding` |
| RELATIVE | `Conteneur` | `gravity` ★ (drapeaux), `ignoreGravity` (référence, portée sous-arbre), `clipChildren`, `clipToPadding` |
| COORDINATOR | `Conteneur` | `fitsSystemWindows` ★, `statusBarBackground` (`@drawable`/`@color`), `clipChildren` |
| APPBAR | `Barre d'application` | `liftOnScroll` ★, `liftOnScrollTargetViewId` (référence, sous-arbre), `fitsSystemWindows`, `expanded` |
| `MaterialCardView` | + `Carte` (existant) | inchangé |

Sur la **racine**, `fitsSystemWindows` et `clipToPadding` ne sont pas répétés (déjà dans « Racine »).

### 5.2 Section « Dans le parent » (la vue est l'enfant)

| Famille du **parent** | Section | Contrôles |
|---|---|---|
| CONSTRAINT | `Contraintes` (existant) | `Contraintes` ★, biais, pourcentage, ratio, min/max |
| LINEAR | `Dans le parent` (existant) | `layout_weight` ★, `layout_gravity` ★, action « Remplir l'espace restant » ★ |
| FRAME | `Dans le parent` | `layout_gravity` ★ |
| RELATIVE | `Dans le parent` | **Ancrage au parent** ★ (7 bascules : début, fin, haut, bas, centre H, centre V, centre), `alignWithParentIfMissing` |
| RELATIVE | `Positions relatives` | **Règles vers un frère** : `below` ★, `toEndOf` ★, `above`, `toStartOf`, `alignTop`, `alignBottom`, `alignStart`, `alignEnd`, `alignBaseline` (références d'id, portée « frères ») |
| COORDINATOR | `Dans le parent` | `layout_gravity` ★, **`layout_anchor` ★** (référence, portée sous-arbre du parent), `anchorGravity` (désactivé sans ancre), `layout_behavior` ★ (liste), `insetEdge`, `dodgeInsetEdges`, `keyline` |
| APPBAR | `Dans le parent` | `layout_scrollFlags` ★ (drapeaux), `layout_scrollInterpolator`, `layout_gravity` |
| AUTRE / AUCUN | — | — |

Les attributs de type **bascule** (ancrage) écrivent `="true"` et retirent l'attribut au second appui ; le bouton ↺ du champ réinitialise
tous les attributs du groupe.

### 5.3 Contrôle « Référence d'id » (`ControleAttribut.ReferenceId`)

C'est le contrôle `idref` de la maquette (prévu par le brief PE11 pour `labelFor` ; **vérifier s'il existe déjà** dans `ControleAttribut` avant PE12c, sinon PE12c le crée). Il porte une **portée** :

| Portée | Candidats | Sert à |
|---|---|---|
| `TOUT` (prévue par PE11 pour `labelFor`) | tous les `@id/` de l'arbre sauf la vue | `labelFor` |
| `FRERES` | les frères directs sauf la vue | règles de `RelativeLayout` |
| `SOUS_ARBRE` | le sous-arbre du parent, hors la vue et ses descendants (pour `layout_anchor` : les nœuds du `CoordinatorLayout`) ; pour `ignoreGravity` et `liftOnScrollTargetViewId` : le sous-arbre de la vue | ancres |

Règles : première option « aucun » (retire l'attribut) ; **une option qui fermerait une boucle est désactivée et suffixée
« (boucle) »** (`RelationsParents.fermeraitBoucle`) ; une valeur absente de l'arbre s'ajoute en dernière option « (introuvable) » ; note
sous le champ pour `FRERES` (« Vues sœurs seulement. Une règle qui fermerait une boucle est grisée. »). Layout de référence :
`controle_reference_id.xml`.

### 5.4 Nouvelles classes ayant une section propre

| Classe | Section | Contrôles |
|---|---|---|
| `MaterialToolbar` | `Barre d'outils` | `app:title` ★ (lit `@string`), `subtitle`, `navigationIcon` ★, `titleCentered`, `titleTextColor`, `menu` |
| `FloatingActionButton` | `Bouton d'action` | `srcCompat` ★, `backgroundTint` ★, `fabSize` ★ (`auto`, `normal`, `mini`), `tint`, `elevation`, `borderWidth`, `rippleColor`, `useCompatPadding` |

Les attributs `app:` portent la puce `app` (limite P3 du rendu LayoutLib, déjà en place).

## 6. Dépôt (glisser-déposer) : algorithme par famille

Les coordonnées sont en dp du rendu (la maquette) ; l'implémentation convertit avec la densité du profil, comme `ZonesDepot`.

### 6.1 Détermination du parent visé

1. Conteneur le plus profond contenant le point (existant, `conteneurLePlusProfond`).
2. **Cas particulier `FloatingActionButton`** : si un `CoordinatorLayout` contient le conteneur visé (ou l'est), la cible devient le plus profond
   `CoordinatorLayout` ancêtre ; la vue frère du `CoordinatorLayout` sous le point devient l'**ancre** candidate.
3. La famille du parent choisit l'algorithme ci-dessous.

### 6.2 CONSTRAINT, LINEAR, APPBAR

Inchangé (barre d'insertion, `ZonesDepot.indexEntreFreres`). `APPBAR` se comporte comme une colonne ; pour un `LINEAR` horizontal, comme une ligne.

### 6.3 FRAME et COORDINATOR : neuf cellules

Le rectangle intérieur du parent (moins le padding) est divisé en **3 × 3**. La cellule sous le point donne la gravité :

| | colonne début | colonne centre | colonne fin |
|---|---|---|---|
| **ligne haut** | `start\|top` (défaut : attribut omis) | `center_horizontal\|top` | `end\|top` |
| **ligne centre** | `start\|center_vertical` | `center` | `end\|center_vertical` |
| **ligne bas** | `start\|bottom` | `center_horizontal\|bottom` | `end\|bottom` |

- `android:layout_gravity` = la gravité (omis pour `start|top`).
- **Marges de 16 dp** sur chaque bord que la gravité touche : début → `marginStart`, fin → `marginEnd`, haut → `marginTop`, bas → `marginBottom`.
- L'enfant est **ajouté en dernier** (premier plan) pour un dépôt ; le popup permet « Derrière » (avant la référence) et « Devant » (après).
- `COORDINATOR` + widget défilant (`RecyclerView`) + une `AppBarLayout` frère : ajouter `app:layout_behavior="@string/appbar_scrolling_view_behavior"` **et ne pas
  écrire de marge** ; le libellé ajoute « · défilement lié à l'AppBar ».
- `COORDINATOR` + `FloatingActionButton` sur une vue frère : **ancre**. Les 9 cellules s'appliquent **à la vue ancre** :
  `app:layout_anchor="@id/x"`, `app:layout_anchorGravity` = la gravité de la cellule (écrite même si elle vaut `start|top`), marges **16 dp** sur les quatre côtés.
  Le repère montre la vue ancre, la cellule, et le **fantôme** : point d'ancrage − moitié de la taille, recadré à 16 dp des bords du parent
  (le même calcul que `constrainChildRect`).

### 6.4 RELATIVE : adhérence puis ancrage

Le pointeur est le **centre** de la vue déposée. La taille de la vue vient du widget (`defSize` : largeur `match_parent` → largeur du parent moins padding ; fixe → valeur ; sinon 96 dp ; hauteur ≥ `minHeight`).

1. **Adhérence** : le frère dont le rectangle est le plus proche du point, à **≤ 28 dp** (`ADH`). S'il existe :
   - **côté** = celui où le point dépasse le plus (`dessous` = y − bas, `dessus` = haut − y, `droite` = x − droite, `gauche` = gauche − x) ; si le point est dans le rectangle, le bord le plus proche ;
   - **règle principale** : `below`, `above`, `toEndOf` ou `toStartOf` vers ce frère ;
   - **marge** (côté de la règle) = distance entre le bord du frère et le bord de la vue, **arrondie au multiple de 8**, plafonnée à 64 ;
   - **alignement croisé** (règle supplémentaire) : pour `below`/`above`, `alignStart` si le bord début est à ≤ 16 dp du bord début du frère ; `alignEnd` si c'est le bord fin ; sinon `alignParentStart` + `marginStart` = distance au parent arrondie à 8. Pour `toEndOf`/`toStartOf` : `alignTop`, `alignBottom`, sinon `alignParentTop` + `marginTop`.
2. **Sinon, ancrage au parent** : cellule 3 × 3 ; dans ±16 dp du centre d'un axe, on **centre** sur cet axe (`centerHorizontal`, `centerVertical`, les deux = `centerInParent`) ; sinon `alignParentStart/End` (colonnes 0 et 2) et `alignParentTop/Bottom` (lignes 0 et 2), avec une marge = distance au bord arrondie à 8 (16 par défaut pour le popup).

La règle `alignBaseline`, les variantes `Left`/`Right` et `layout_alignWithParentIfMissing` ne sont **jamais écrites** par un dépôt (elles restent éditables).

### 6.5 Table de référence des insertions

`docs/mockups/ref/parents/insertions_or.json` donne, pour douze scénarios, les lignes ajoutées par la maquette. À reprendre en tests unitaires de
`PlanInsertionFamille` : on compare **l'ensemble des attributs** (pas l'ordre). Extraits :

| Scénario | Attributs ajoutés (hors `id`, tailles, texte) |
|---|---|
| S1 `Button` sous `bio`, marge 16 | `layout_marginTop=16dp`, `layout_below=@id/bio`, `layout_alignStart=@id/bio` |
| S2 `TextView` à droite de `nom`, marge 8 | `layout_marginStart=8dp`, `layout_toEndOf=@id/nom`, `layout_alignTop=@id/nom` |
| S4 `TextView` dans le parent, bas-fin, marge 16 | `layout_marginBottom=16dp`, `layout_marginEnd=16dp`, `layout_alignParentEnd=true`, `layout_alignParentBottom=true` |
| S5 `TextView` au centre du parent | `layout_centerInParent=true` |
| S6 `FrameLayout`, haut-début | marges 16 dp haut et début, **pas** de `layout_gravity` |
| S7 `FrameLayout`, bas-fin | `layout_gravity=end\|bottom`, marges 16 dp bas et fin |
| S9 `FloatingActionButton` sur `liste` | `app:layout_anchor=@id/liste`, `app:layout_anchorGravity=center_horizontal\|top`, marges 16 dp |
| S11 `RecyclerView` dans le `CoordinatorLayout` | `app:layout_behavior=@string/appbar_scrolling_view_behavior`, **aucune** marge |
| S12 `Button` dans l'`AppBarLayout`, après `toolbar` | aucun attribut de position |

## 7. Popup « Où placer ? »

La famille se lit sur le **parent résultant** (recalculée à chaque changement de référence ou de position).

| Parent résultant | `groupe_position` | Sous-contrôles |
|---|---|---|
| CONSTRAINT, LINEAR, APPBAR, AUTRE | Avant · Dedans · Après | — |
| FRAME, COORDINATOR | Derrière · Dedans · Devant | **Position dans le conteneur** : grille 3 × 3 |
| RELATIVE (référence = frère) | Dessous · Dessus · Droite · Gauche (+ Dedans si la référence est un conteneur) | **Marge** : 0 · 8 · 16 · 24 dp |
| RELATIVE (« Dedans ») | Dedans | **Ancrage dans le parent** : grille 3 × 3 + **Marge** |

- Jeu d'options **filtré** : une feuille n'offre pas « Dedans » ; la racine n'offre que « Dedans » ; si la position courante devient invalide, la
  première position valide est choisie (« Dedans » si la référence est un conteneur).
- Le résumé suit : `Sous @id/bio · marge 16 dp, dans RelativeLayout · @+id/root` ou `Dans FrameLayout · @+id/root, haut, début (défaut), plan 3 sur 5`.
- Pendant le choix, **le repère est dessiné dans le rendu** (cellule, fantôme, flèche de règle) et la vue est amenée à l'écran (`ensureVisible`).
- Hauteur : 340 dp max pour les parents à barre, `min(580 dp, hauteur disponible − 134 dp)` quand la grille ou la rangée de marges est visible ; le mini-arbre passe à 100 dp.
- Ressources de référence : `popover_placement_parents.xml` (remplace `popover_placement.xml`), `item_position_bouton.xml`, `item_cellule_gravite.xml`, `dimens_parents.xml`, `styles_parents.xml`, `strings_parents.xml` (+ `values-en`).
- Mesures : `docs/mockups/ref/parents/meas_popups_parents.json` (cotes de chaque groupe et bouton, cinq contextes).

## 8. Calque « Relations »

Le calque **Contraintes** devient **Relations** (menu Calques). Il se dessine pour la famille de **chaque parent** du document :

| Famille | Dessin | Étiquette (vue sélectionnée seulement) |
|---|---|---|
| CONSTRAINT | ancres et distances (existant) | valeur en dp |
| RELATIVE | ligne **pleine** pour `below`, `above`, `toEndOf`, `toStartOf` (entre le milieu de la vue et le bord du frère) ; ligne **pointillée** pour `align*` (le long du bord partagé) ; ligne vers le bord du parent pour `alignParent*` ; traits pointillés au centre pour `center*` | nom de la règle (`below`, `alignTop`, `parent`) |
| COORDINATOR | ligne pointillée du point d'ancrage (selon `anchorGravity`) au centre de la vue ancrée | `anchor · bottom\|end` |

Extrémités : disques de 3,5 dp, trait de 1,5 dp, couleur accent (jeton `--accent`, déjà dans `JetonsCalques`). **Aucun nouveau jeton.**
Le calque est activé par défaut pour les documents `RELATIVE` et `COORDINATOR` de la maquette (réglage utilisateur ensuite).

Repères de dépôt : `.cell` (fond violet doux, trait violet 2 dp, rayon 4 dp), `.gbox` (fantôme : trait violet pointillé 1,5 dp, fond violet doux) — jetons `--new` et `--new-soft` existants.

## 9. Problèmes et correctifs rapides

| Genre (`GenreProbleme`) | Sévérité | Condition | Correctif |
|---|---|---|---|
| `ATTRIBUT_ORPHELIN` | avertissement | attribut de layout hors famille (table ci-dessous) | « Retirer cet attribut » / « Retirer ces attributs » |
| `REFERENCE_INTROUVABLE` | avertissement | règle `RelativeLayout` vers un `@id/` absent des frères | « Retirer la règle » |
| `ANCRE_INVALIDE` | **erreur** | `layout_anchor` vers une vue inconnue, la vue elle-même, un de ses descendants, ou hors du `CoordinatorLayout` | « Retirer l'ancre » (retire aussi `anchorGravity`) |
| `COMPORTEMENT_MANQUANT` | avertissement | enfant défilant (`RecyclerView`, `ScrollView`) d'un `CoordinatorLayout` qui contient une `AppBarLayout`, sans `layout_behavior` | « Ajouter le comportement » |
| `DEPENDANCE_CIRCULAIRE` | **erreur** | boucle de règles dans un `RelativeLayout` | « Casser la boucle » (retire la règle de la première vue du cycle) |

Attributs orphelins — attribut → familles de parent **autorisées** :

| Attribut | Familles |
|---|---|
| `app:layout_constraint*` | CONSTRAINT |
| `layout_below`, `above`, `toStartOf`, `toEndOf`, `toLeftOf`, `toRightOf`, `align*`, `centerInParent`, `centerHorizontal`, `centerVertical` | RELATIVE |
| `layout_weight` | LINEAR, APPBAR |
| `layout_gravity` | LINEAR, APPBAR, FRAME, COORDINATOR |
| `app:layout_anchor`, `anchorGravity`, `layout_behavior`, `layout_insetEdge`, `layout_dodgeInsetEdges`, `layout_keyline` | COORDINATOR |
| `app:layout_scrollFlags`, `layout_scrollInterpolator` | APPBAR |

Un attribut **absent de la table** n'est jamais signalé. Le message est affiché dans l'onglet Problèmes et dans la section « Corrections rapides » de la vue.
Les problèmes de famille se calculent **sur le texte** (`ArbreAttributsXml`), pas sur `InfoVueRendu` : ils restent donc disponibles quand le rendu est en erreur.

## 10. Suppression et renommage sans référence morte

- **Suppression** (`SuppressionSure.composer(texte, span)`) : une seule `EditionXml` qui retire (1) l'élément et ses descendants (comme `EditionAttributsXml.supprimer`)
  et (2) tout attribut dont la valeur est `@id/x` ou `@+id/x` avec `x` dans le sous-arbre supprimé. Si l'élément qui portait `app:layout_anchor` le perd,
  `app:layout_anchorGravity` est retiré avec lui. La plage de l'édition englobe tous les changements (même principe que `composerEdition`).
  Message : `Button · @+id/x supprimé · 2 références retirées`.
- **Renommage** : `RenommageIdentifiant.renommer` fait déjà le travail (P10) ; un test garantit que `layout_below`, `layout_anchor`, `labelFor`
  suivent. Message : `Renommé en @+id/x · 3 références mises à jour`.
- **Duplication** : les références **ne sont pas** réécrites (le clone pointe les mêmes cibles : c'est à l'utilisateur de les changer).

## 11. Palette

Six entrées dans `CataloguePalette` (catégorie, extrait XML minimal, dépendance) :

| Id | Nom | Catégorie | Extrait | Dépendance |
|---|---|---|---|---|
| `fl` | FrameLayout | Conteneurs | `<FrameLayout … match_parent / wrap_content, minHeight 96dp />` | — |
| `rl` | RelativeLayout | Conteneurs | idem | — |
| `cl` | CoordinatorLayout | Conteneurs | `match_parent / 160dp` | `androidx.coordinatorlayout` (transitif de Material) |
| `abl` | AppBarLayout | Conteneurs | `match_parent / wrap_content, minHeight 56dp` | Material |
| `tb` | MaterialToolbar | Autres | `match_parent / 56dp`, `app:title` | Material |
| `fab` | FloatingActionButton | Boutons | `56dp × 56dp`, `app:srcCompat`, `contentDescription` | Material |

Les icônes de tuile (24 × 24, trait 1,8) sont dans la maquette : `ICO.fl`, `rl`, `cl`, `abl`, `tb`, `fab`.

## 12. Rendu en erreur : dépendance circulaire

Quand le document contient une boucle de règles :

1. LayoutLib lève l'exception ; `RenduLayoutLib` la classe comme **erreur de rendu** (comme une classe introuvable) et conserve le dernier rendu valide.
2. La pastille d'état passe à « Dernier rendu valide conservé ».
3. Le bandeau affiche : `Erreur de rendu · Dépendance circulaire dans <conteneur> : @id/a → @id/b → @id/a. RelativeLayout refuse une boucle de règles.` Le bouton « Ligne N » ouvre la **première vue du cycle** dans la source.
4. Le problème `DEPENDANCE_CIRCULAIRE` propose « Casser la boucle ».
5. Les **sections de l'inspecteur** grisent déjà toute option qui fermerait une boucle (§ 5.3) : la boucle n'est atteignable que par édition manuelle du texte.

## 13. Accessibilité et i18n

- Chaque bouton de position et de cellule est un **radio** (`role`, `checked`) ; les cellules portent un `contentDescription` (« Haut, début »…).
- Aucune chaîne en dur : `strings_parents.xml` (fr) et `values-en/strings_parents.xml`. Les noms d'attributs et de classes ne se traduisent pas.
- Cibles tactiles : **mêmes dérogations que l'ADR 0134 (D8)** — boutons de position 32,5 dp, cellules 34 dp, espacement 4 dp.
- Le repère de dépôt ne repose pas sur la couleur seule : libellé textuel sous le repère (« + Button · Sous @id/bio · marge 16 dp »).

## 14. Limites assumées (à répéter dans l'interface quand c'est utile)

| Limite | Où | Comportement |
|---|---|---|
| `RelativeLayout.gravity` / `ignoreGravity` | maquette | écrits, effet **non simulé** ; LayoutLib, lui, les applique |
| `alignBaseline` | maquette | approchée (centre vertical) ; LayoutLib exact |
| Comportements autres que `appbar_scrolling_view_behavior` | maquette | écrits, ne déplacent rien |
| `layout_insetEdge`, `dodgeInsetEdges`, `keyline` | maquette | écrits, non simulés |
| `CollapsingToolbarLayout` | spec | famille FRAME ; `layout_collapseMode` n'a pas de contrôle dans cette vague |
| `app:` et LayoutLib | rendu réel (P3) | `applyCustomAttributes` est un stub : un attribut `app:` peut s'écrire sans changer le rendu |
| Ancres | spec | la sémantique (point d'ancrage, gravité, recadrage) vient du code AndroidX ; **à confirmer par une mesure LayoutLib** avant de figer les tests de rendu (§ 15) |

## 15. Recette

### 15.1 Tests unitaires (JVM, `:preview:xml`)

| Test | Vérifie |
|---|---|
| `FamilleParentTest` | chaînes de classes → famille (dont `AppBarLayout` avant `LinearLayout`, `MaterialCardView` → FRAME) ; repli par balise ; classe inconnue → AUTRE |
| `InfoVueRenduTest` (existant) | parité Parcel avec le champ `famille` en dernier ; `equals`/`hashCode` |
| `RelationsParentsTest` | arêtes de chaque règle ; cycle direct, cycle à trois ; `fermeraitBoucle` ; orphelins par famille |
| `PlanInsertionFamilleTest` | les 12 scénarios de `insertions_or.json` (ensemble d'attributs) ; bornes (marge ≤ 64, arrondi à 8) |
| `ZonesDepotTest` | choix de la cellule (9 coins et centres) ; cible `FloatingActionButton` ; adhérence à ±28 dp |
| `PlacementPopupTest` | jeu de positions par famille ; filtre feuille / racine ; position invalide → première valide |
| `SuppressionSureTest` | références `@id/` et `@+id/` retirées dans le sous-arbre ; `anchorGravity` retiré avec l'ancre ; une seule édition |
| `ProblemesPreviewTest` | cinq genres, sévérité, correctifs ; un attribut hors table n'est jamais signalé |
| `CatalogueAttributsTest` | sections par famille (§ 5) ; pas de doublon `fitsSystemWindows` / `clipToPadding` sur la racine |

### 15.2 Robolectric / Android (`:feature:preview`)

- Cotes de `popover_placement_parents.xml` contre `meas_popups_parents.json` (cinq contextes, tolérance 1 dp).
- `VoletAttributsFragment` : sections affichées pour un enfant de chaque famille ; options grisées « (boucle) ».
- Paire de captures maquette | application, clair et nuit, pour les trois états de popup et le calque Relations sur `activity_profil.xml` et `activity_fil.xml`.

### 15.3 Mesure sur appareil (à lancer **avant** de figer les rendus)

Protocole `PROTOCOLE_MESURE_APPAREIL.md` : rendre `activity_profil.xml`, `activity_carte.xml`, `activity_fil.xml` (`docs/mockups/ref/parents/exemples/`) dans
LayoutLib, relever les bornes des vues, comparer à `meas_*` de la maquette (cible : ≤ 1 dp). Écarts attendus : `alignBaseline`, ancres (centrage), hauteur de la
barre d'état. **Tout écart est consigné dans l'ADR 0135 (annexe) ; la maquette est corrigée, pas le moteur.**

## 16. Correspondance maquette → code

| Maquette (`apercu-xml.html`) | Code cible |
|---|---|
| `famOf`, `FAM_LABEL`, `FAM_ATTRS` | `FamilleParent`, `RelationsParents.attributsOrphelins` |
| `layoutPos` (frame / coord / rel) | LayoutLib (aucun code) ; sert de référence aux mesures |
| `relEdges`, `relCycle`, `wouldCycle`, `pathTo` | `RelationsParents.relations`, `cycle`, `fermeraitBoucle` |
| `framePlan`, `relPlanSide`, `relPlanParent`, `relPlanPointer`, `defSize`, `cellAt`, `cellRect`, `relGhost` | `PlanInsertionFamille` |
| `targetAt`, `drawDrop` | `ZonesDepot.cibleSous`, `VuePreviewXml` (repères) |
| `posList`, `normPos`, `placeTarget`, `drawPlace` | `PlacementPopup`, `PopupPlacement` |
| `containerSecs`, `parentSecs`, `refCands` | `CatalogueAttributs.sections`, `ControleAttribut.ReferenceId` |
| `computeProblems` (+ `quickFixes`) | `ProblemesPreview.evaluer`, correctifs |
| `drawRelations` | `VuePreviewXml` (calque Relations) |
| `delSel`, renommage d'id | `SuppressionSure`, `RenommageIdentifiant` |
| `DOCS`, `loadDoc` | fixtures `docs/mockups/ref/parents/exemples/` (tests et captures) |

## 17. Questions ouvertes

1. **Ancres** : confirmer par mesure LayoutLib le point d'ancrage par défaut (début/haut) et le centrage de l'enfant (§ 3.3).
2. **`MotionLayout`** : famille CONSTRAINT par héritage ; ses `ConstraintSet` ne sont pas couverts.
3. **`NestedScrollView` / `ScrollView`** : FRAME, mais un seul enfant direct est admis — faut-il avertir ? (proposition : problème « plusieurs enfants dans un ScrollView », hors vague).
4. **`GridLayout`, `TableLayout`, `DrawerLayout`** : restent AUTRE ; la vague suivante pourrait leur donner leurs familles.
5. **Marge par défaut** du dépôt (16 dp) : alignée sur la grille de 8 dp de la maquette ; à confirmer par le propriétaire.
