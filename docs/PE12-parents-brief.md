# PE12 — Familles de parents : RelativeLayout, CoordinatorLayout, FrameLayout, AppBarLayout (brief pour l'agent d'implémentation)

> À coller tel quel dans la session de l'agent. Lis d'abord `AGENTS.md`, **`docs/STRATEGIE_VERIFICATION.md`** (elle prime sur AGENTS.md pour la vérification), `docs/PREVIEW.md` § 3.4, puis :
> **`docs/PREVIEW_PARENTS.md`** (spécification normative, partie III), **`docs/adr/0135-apercu-familles-de-parents.md`**, et la maquette **`docs/mockups/apercu-xml.html`** (section « Parents et enfants », quatre documents : Connexion, Profil = `RelativeLayout`, Carte = `FrameLayout`, Fil = `CoordinatorLayout` + `AppBarLayout`).
> La maquette est la référence du **comportement et du design** ; son JavaScript est le miroir de la spécification (§ 16 : correspondance maquette → code). **LayoutLib reste la vérité du rendu** : on n'émule pas `RelativeLayout` ni `CoordinatorLayout`.
> **Numérotation** : `PE12` est libre ; PE11a-d et PE11i-l sont livrés (v0.153.0) ; PE11e-h (couleur, ressource, curseur, actions rapides…) sont **indépendants** de cette série sauf pour `ReferenceId` (voir PE12c).
> **Exigence non négociable** : les nouveaux écrans et contrôles sont **indiscernables de la maquette**, clair et nuit, avec les ressources de référence fournies (`docs/mockups/ref/ressources/`) ; aucun littéral de couleur / dp / sp dans les layouts (grille `PREVIEW_UI_ECRANS.md` § 12.4).

## 1. Objectif

Aujourd'hui l'aperçu ne sait éditer que des enfants de `ConstraintLayout` et de `LinearLayout`. Un enfant de `RelativeLayout`, `FrameLayout` ou `CoordinatorLayout` n'a aucune section de position, le dépôt le traite comme une colonne, la suppression laisse des références mortes et une boucle de règles fait échouer le rendu sans explication.

Ce que l'utilisateur obtient :

1. L'inspecteur d'un **enfant** propose la section de position de **sa famille de parent** ; celui d'un **parent** propose les attributs de conteneur de sa famille.
2. Le **dépôt** (glisser) et le **popup « Où placer ? »** choisissent les bons attributs : gravité dans une grille 3 × 3 (`FrameLayout`, `CoordinatorLayout`), règles vers un frère ou le parent avec marge (`RelativeLayout`), ancre pour un `FloatingActionButton`, comportement de défilement sous une `AppBarLayout`.
3. Le calque **Relations** dessine règles et ancres.
4. **Cinq nouveaux problèmes** avec correctif ; la **boucle de règles** est expliquée et réparable.
5. **Supprimer** une vue retire aussi les références qui la visaient, en une seule édition.
6. La palette offre `FrameLayout`, `RelativeLayout`, `CoordinatorLayout`, `AppBarLayout`, `MaterialToolbar`, `FloatingActionButton`.

## 2. État du code à connaître (clone `origin/main`, v0.153.0)

- `preview/xml/.../CatalogueAttributs.kt` : `GenreParent` (5 valeurs), `genreParent(classeParente, parentEstRacine)` par nom simple, `sections(classe, genreParent)`.
- `preview/xml/.../ZonesDepot.kt` (`cibleSous`, `emplacement`, `indexEntreFreres`), `PlacementPopup.kt` (`Position` = Avant/Dedans/Après), `InsertionXml.kt` (`inserer`, `contraindre`, `renumeroter`), `EditionAttributsXml.kt` (`lire`, `definir`, `definirReference`, `supprimer`, `dupliquer`, `ecrireDimensions`), `RenommageIdentifiant.kt`, `ProblemesPreview.kt`, `AligneurElementsXml.kt` (spans d'éléments, **sans attributs**), `InfoVueRendu.kt` (Parcelable AIDL, 22 champs), `CataloguePalette.kt`, `VuePreviewXml.kt`.
- `feature/preview/.../VoletAttributsFragment.kt`, `PopupPlacement`, `PreviewViewModel.kt`.
- Limite **P3** (ADR 0116) : `applyCustomAttributes` est un stub : un attribut `app:*` peut être écrit sans changer le rendu. À mesurer, pas à promettre.

## 3. Découpage proposé (un chantier = un commit, un push par SÉRIE — STRATEGIE_VERIFICATION § 4)

Chaque chantier : chaîne **locale** de la stratégie (`spotlessApply`, `spotlessCheck`, `detekt` racine, `checkModuleDependencies` racine, compilation + tests des modules touchés), **jamais** `assembleDebug` / `lintDebug` / `koverVerify` en local. **Chaîne racine obligatoire** pour PE12a (signature publique `InfoVueRendu`, port consommé par `:feature:preview`) et PE12b. Commits conventionnels en français ; version et `CHANGELOG.md` à l'intégration (règle de l'intégrateur). **Propriété des modules** à déclarer en tête de PR : `:preview:xml`, `:feature:preview` — rien d'autre.

### PE12a — Famille de parent (`:preview:xml`)

Lis `PREVIEW_PARENTS.md` § 2 et ADR 0135 (D1).

- Créer `FamilleParent` (enum) avec `deClasses(chaine: List<String>)` et `deBalise(nom)` : table de § 2.2 (`AppBarLayout` avant `LinearLayout` ; `MaterialCardView`, `NestedScrollView`, `ScrollView`, `CollapsingToolbarLayout` → `FRAME`).
- `RenduLayoutLib` calcule la chaîne `Class.getSuperclass()` de la vue réelle **par nom de classe** (jamais d'`instanceof` : pas de dépendance Material) ; repli par balise si la vue est un `MockView`.
- `InfoVueRendu.famille: String = ""` **ajouté en dernier** du constructeur, de `writeToParcel`, de `createFromParcel`, de `equals` / `hashCode` ; adapter le test de parité AIDL.
- Alias interne : `GenreParent` continue d'exister, alimenté par `FamilleParent`.
- Tests : `FamilleParentTest` (chaînes complètes jusqu'à `Object`, sous-classes, classe inconnue → `AUTRE`, balise qualifiée et simple), `InfoVueRenduTest` (aller-retour Parcel, `equals`).

### PE12b — Lecteur d'attributs et relations (`:preview:xml`)

Lis § 3, § 4, § 9 (table des attributs orphelins) et § 5.3.

- `ArbreAttributsXml` (pur, `XmlPullParser` comme `AligneurElementsXml`) : pour chaque élément, balise, `id`, attributs de layout (`android:layout_*`, `app:layout_*`), span, index du parent. **Tolérant** aux XML incomplets (comme l'aligneur).
- `RelationsParents` : `relations(parent)`, `cycle(parent)` (premier cycle, chemin d'identifiants), `fermeraitBoucle(parent, vue, attribut, cible)`, `attributsOrphelins(famille, attributs)`.
- Supprimer `GenreParent` et migrer `CatalogueAttributs.sections(classe, famille)` (comportement **inchangé** pour CONSTRAINT / LINEAR ; AUTRE reste vide jusqu'à PE12c).
- Tests : un test par règle de côté, par alignement, par ancre ; cycle direct, cycle à trois, pas de cycle ; `fermeraitBoucle` ; attribut hors table jamais signalé.

### PE12c — Inspecteur par famille (`:preview:xml`, `:feature:preview`)

Lis § 5 ; ressources : `controle_reference_id.xml`, `dimens_parents.xml`, `styles_parents.xml`, `strings_parents.xml` (+ `values-en`).

- `CatalogueAttributs.sections(classe, famille)` : sections « Conteneur » par famille (§ 5.1) et « Dans le parent » / « Positions relatives » (§ 5.2). Bascules d'ancrage `RelativeLayout` (7), drapeaux `scrollFlags`, liste `layout_behavior`.
- `ControleAttribut.ReferenceId(nom, etiquette, portee)` (`TOUT`, `FRERES`, `SOUS_ARBRE`). **Vérifier d'abord s'il existe déjà** (PE11e-h en cours) : le réutiliser et ajouter la portée, sinon le créer. Option « aucun » en tête, option qui ferme une boucle **désactivée et suffixée « (boucle) »**, valeur introuvable ajoutée en dernier « (introuvable) ».
- Nouvelles classes avec section propre : `MaterialToolbar`, `FloatingActionButton` (§ 5.4).
- `VoletAttributsFragment` : un layout par contrôle **copié** de la référence ; pas de logique métier dans le fragment (règle 1) ; une fonction par contrôle (règle 8).
- Tests : sections par famille ; `ReferenceId` par portée et grisage ; aller-retour `definir` puis `lire` ; pas de doublon `fitsSystemWindows` / `clipToPadding` sur la racine.

### PE12d — Dépôt et popup « Où placer ? » (`:preview:xml`, `:feature:preview`)

Lis § 6, § 7 et ADR 0135 (D3, D4, D6, D9) ; **la décision D6/D9 doit être confirmée par le propriétaire avant ce chantier**.

- `PlanInsertionFamille` : `cellule`, `cote`, `versParent`, `auPointeur` (§ 4.2, algorithmes § 6). Constantes nommées : adhérence 28 dp, marge 16 dp, arrondi 8 dp, plafond 64 dp, tolérance d'alignement 16 dp.
- `InsertionXml.ajouterAttributs(extrait, attributs)` ; `contraindre` conservé pour CONSTRAINT. Une interaction = **une** `EditionXml`.
- `ZonesDepot.cibleSous` : cible `FloatingActionButton` → plus proche `CoordinatorLayout` + ancre ; 9 cellules ; adhérence.
- `PlacementPopup` : positions par famille, filtre feuille / racine, repli sur la première valide ; `popover_placement_parents.xml` **remplace** `popover_placement.xml`, plus `item_position_bouton.xml`, `item_cellule_gravite.xml`.
- `VuePreviewXml` : repères cellule (`--new-soft`, trait `--new`) et fantôme pointillé ; **aucune constante de couleur** (jetons `JetonsCalques` : ajouter les entrées **seulement si** les jetons existants ne couvrent pas ; la maquette n'en demande aucune).
- **Tests en or** : les 12 scénarios de `docs/mockups/ref/parents/insertions_or.json` (S1-S12) comparent **l'ensemble** des lignes ajoutées (pas l'ordre) ; bornes de marge ; adhérence à ±28 dp.
- Cotes Robolectric du popup contre `meas_popups_parents.json` (cinq contextes, ±1 dp).

### PE12e — Calque « Relations » (`:preview:xml`)

Lis § 8. `VuePreviewXml` dessine, pour chaque parent `RELATIVE` ou `COORDINATOR` du document, règles pleines / pointillées / vers le bord du parent / ancre → centre. Lignes 1,5 dp, extrémités 3,5 dp, couleur `accent` du `JetonsCalques` ; étiquette seulement pour la vue sélectionnée. Le menu « Calques » renomme « Contraintes » en « Relations » (clé de chaîne dans `strings_ecrans`). Tests : segments produits pour les trois fixtures (fonction pure de géométrie extraite, testée sans Canvas).

### PE12f — Problèmes et correctifs (`:preview:xml`, `:feature:preview`)

Lis § 9 et § 12. Cinq genres dans `ProblemesPreview.GenreProbleme`, calculés **sur le texte** via `ArbreAttributsXml`, avec leurs correctifs (une édition chacun). `DEPENDANCE_CIRCULAIRE` : l'exception de LayoutLib est classée comme erreur de rendu (dernier rendu valide conservé), le bandeau nomme le cycle et « Ligne N » ouvre la première vue du cycle. Tests : cinq genres + correctifs, sévérités, texte d'erreur du cycle.

### PE12g — Suppression sûre (`:preview:xml`, `:feature:preview`)

Lis § 10. `SuppressionSure.composer(texte, span)` : une seule édition (élément + références mortes + `anchorGravity`). `PreviewViewModel` l'appelle à la place de `EditionAttributsXml.supprimer` ; message « N références retirées ». Test de non-régression : `RenommageIdentifiant` suit `layout_below`, `layout_anchor`, `labelFor`.

### PE12h — Palette, fixtures, recette (`:preview:xml`, `:feature:preview`)

Lis § 11, § 15.

- Six entrées dans `CataloguePalette` + tuiles (icônes `ic_tuile_*` de la référence, déjà tracées dans la maquette `ICO`). 
- Copier les fixtures `docs/mockups/ref/parents/exemples/` en ressources de test (`activity_profil.xml`, `activity_carte.xml`, `activity_fil.xml`, valeurs fr/en).
- Captures § 15.2 (maquette | application, clair et nuit) ; **mesure appareil** § 15.3 avec `PROTOCOLE_MESURE_APPAREIL.md` ; écarts consignés en annexe de l'ADR 0135 (la maquette est corrigée, pas le moteur).
- `docs/PREVIEW.md` § 3.4 : lignes PE12a-h ; points ouverts de § 17 tranchés ou reportés ; confirmation du propriétaire (D6, D9).

## 4. Ressources à copier (dans `feature/preview/src/main/res/`)

| Fichier (`docs/mockups/ref/ressources/`) | Rôle |
|---|---|
| `values/dimens_parents.xml`, `values/styles_parents.xml`, `values/strings_parents.xml`, `values-en/strings_parents.xml` | cotes, styles `Widget.CodeIDE.Apercu.*`, chaînes |
| `layout/popover_placement_parents.xml` | **remplace** `popover_placement.xml` (garder les identifiants du liage ; sinon adapter `PopupPlacement` et ses tests) |
| `layout/item_position_bouton.xml`, `layout/item_cellule_gravite.xml` | bouton de position, cellule de la grille 3 × 3 |
| `layout/controle_reference_id.xml` | contrôle de référence d'id |

Aucun doublon avec l'existant ; fusionner les fichiers `values`. Les dépôts de cotes : `docs/mockups/ref/parents/meas_popups_parents.json`. Captures : `docs/mockups/ref/parents/*.png`.

## 5. Règles du dépôt à respecter (AGENTS.md)

- KDoc, commentaires, messages d'erreur, commits et documentation **en français**.
- **Règle 1** : aucune logique métier dans Activity / Fragment / ViewModel ; **règle 3** : aucune chaîne ni couleur en dur ; **règle 6** : erreurs attendues en types scellés ; **règle 8** : pas d'exemption detekt pour passer ; **règle 16** : une exemption detekt ciblée seulement si documentée.
- **Règle 13** : n'écris dans le dépôt **aucun nom d'un autre éditeur ou IDE Android** (code, KDoc, docs, CHANGELOG, commits). La spécification fournie respecte déjà cette règle ; relis-la avant de la copier.
- Fidélité visuelle (ADR 0131-0134) : couleurs `?attr/apercu*`, dimensions `@dimen/apercu_*`, styles `Widget.CodeIDE.Apercu.*`, aucun `GradientDrawable` en code.
- Ne relance **pas** de rendu à chaque frappe : un rendu par édition écrite.
- `:preview:xml` reste **pur** : zéro Material, zéro AndroidX, zéro `Context`.

## 6. Critères d'acceptation (à vérifier sur appareil)

1. Ouvrir `activity_profil.xml` : sélectionner `bio` → section « Positions relatives » avec `below` = `separateur` ; le calque Relations montre la ligne pleine ; l'option qui fermerait une boucle est grisée « (boucle) ».
2. Glisser un `Button` sous `bio` : écrit `layout_below="@id/bio"`, `layout_alignStart="@id/bio"` et une marge de 16 dp (scénario S1) ; **un seul annuler** le retire.
3. Ouvrir `activity_carte.xml` : le repère 3 × 3 apparaît ; déposer en bas à droite écrit `layout_gravity="end|bottom"` et deux marges de 16 dp ; l'ordre dans le fichier = le plan (« Devant » ajoute en dernier).
4. Ouvrir `activity_fil.xml` : glisser un `FloatingActionButton` sur `liste` écrit `app:layout_anchor="@id/liste"` et `app:layout_anchorGravity` ; glisser un `RecyclerView` dans le `CoordinatorLayout` écrit `app:layout_behavior="@string/appbar_scrolling_view_behavior"` sans marge.
5. Éditer à la main `layout_below` de `nom` vers `role` (qui dépend de `nom`) : bandeau « Dépendance circulaire… », dernier rendu conservé, correctif « Casser la boucle » rétablit le rendu.
6. Supprimer `avatar` : `layout_toEndOf="@id/avatar"` et `layout_alignTop="@id/avatar"` de `nom` disparaissent dans la même édition ; message « 2 références retirées ».
7. Renommer `nom` : les règles de `role` et de la suite suivent.
8. Un `layout_weight` dans un `FrameLayout` est signalé « Attribut ignoré par le parent » ; le correctif le retire.
9. Cotes du popup à ±1 dp de `meas_popups_parents.json`, clair et nuit.

## 7. Pièges connus

- **Ne pas déduire la famille d'`orientation`** : un `AppBarLayout` est vertical par construction, un `MaterialCardView` n'a pas d'orientation.
- **`app:` et P3** : mesurer sur appareil avant de promettre qu'un attribut `app:` change le rendu.
- **Ancres** : la maquette suppose point d'ancrage début/haut par défaut et enfant centré ; **mesurer** (§ 15.3) avant de figer des tests de rendu.
- **`RelativeLayout` et marges** : `below` ajoute marge de l'ancre **et** de la vue ; `alignTop` n'ajoute que celle de la vue (§ 3.2). Ne pas « corriger » la maquette, la mesurer.
- **`xmlns:app`** : `InsertionXml.ajouterAttributs` doit l'ajouter à la racine quand un attribut `app:` est injecté (comme `composerEdition`).
- **Une édition = un annuler** : pas de deuxième `remplacer` pour la marge ou l'alignement croisé.
