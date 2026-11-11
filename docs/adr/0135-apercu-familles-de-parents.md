# ADR 0135 — Aperçu : familles de parents (RelativeLayout, CoordinatorLayout, FrameLayout, AppBarLayout) et leurs enfants

- **Statut** : accepté — D1 à D10 découlent de la demande « gérer les vues comme `RelativeLayout` et `CoordinatorLayout`, parents
  et enfants, de façon complète et professionnelle ». **D6 (valeur de marge par défaut) et D9 (cibles tactiles) confirmées par le
  propriétaire le 2026-10-21, avant le lancement de PE12d** (aucune réserve, valeurs de la proposition).
- **Date** : 2026-10-20
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0131 (jetons, thème `Theme.CodeIDE.Apercu`), ADR 0132 (écran Attributs), ADR 0133 (typographie, unités,
  cibles tactiles), ADR 0134 (écrans principal, palette, placement, Arbre, Problèmes, Source).
  **Spécification** : `docs/PREVIEW_PARENTS.md` (partie III). **Brief d'exécution** : `PE12-parents-brief.md`.
- **Numérotation** : les ADR de la série de l'aperçu portent 0131-0134 dans le dépôt (0127-0130 dans le lot d'origine) ;
  celui-ci prend **0135**. Si ce numéro est pris, renuméroter ce fichier et ses renvois (spécification, brief).

## Contexte

L'aperçu traite aujourd'hui trois sortes de parents : `ConstraintLayout`, `LinearLayout`, « autre ». `CatalogueAttributs.GenreParent`
n'a que cinq valeurs et compare des **noms simples** de classe. Les conséquences, relevées au clone v0.153.0
(`PREVIEW_PARENTS.md` § 1, P1-P11) :

- un enfant de `RelativeLayout`, `FrameLayout` ou `CoordinatorLayout` n'a **aucune section de position** dans l'inspecteur ;
- le dépôt (`ZonesDepot`) traite tout parent non contraint comme une **colonne**, même quand l'ordre n'a pas de sens
  (`FrameLayout` : ordre = plan ; `RelativeLayout` : ordre sans effet) ;
- `InsertionXml` ne sait poser des attributs de position que pour `ConstraintLayout` ;
- supprimer une vue laisse des **références mortes** (`layout_below="@id/x"`) ; rien ne les signale ;
- une **boucle de règles** `RelativeLayout` fait lever une exception à LayoutLib ; l'aperçu ne l'explique pas ;
- `MaterialCardView`, `AppBarLayout`, `NestedScrollView` (sous-classes) tombent dans « autre » ;
- la palette n'offre aucun de ces conteneurs.

Les règles Android sont **différentes d'une famille à l'autre** et parfois contre-intuitives (marges de `FrameLayout` pleines au centre,
marges de `CoordinatorLayout` retranchées du rectangle ; ancre par défaut début/haut ; `match_parent` borné par une seule règle).
La maquette v6 les reproduit et a été vérifiée sur quatre documents (profil, carte, fil, connexion) par 65 contrôles automatiques.
Contraintes inchangées : `:preview:xml` est pur (zéro Material, zéro `Context`) ; `:feature:preview` est en XML + ViewBinding (ADR 0002) ;
une interaction = une édition = un annuler ; LayoutLib reste la vérité du rendu.

## Décision

- **D1 — Une famille de parent explicite.** `FamilleParent` (`AUCUN`, `CONSTRAINT`, `LINEAR`, `FRAME`, `RELATIVE`, `COORDINATOR`,
  `APPBAR`, `AUTRE`) remplace `GenreParent`. Elle est **calculée par le rendu** depuis la chaîne de superclasses de la vue réelle
  (test par nom de classe, `AppBarLayout` avant `LinearLayout`), **repliée par nom de balise** sans rendu, et portée par
  `InfoVueRendu.famille` (champ ajouté **en dernier** du Parcel, test de parité AIDL). `GenreParent` disparaît à PE12b.
- **D2 — Les règles Android sont écrites une fois, dans la spécification.** `PREVIEW_PARENTS.md` § 3 les énonce avec leur source
  (AOSP : `FrameLayout.onLayout`, `CoordinatorLayout.layoutChild` / `getDesiredAnchoredChildRect` / `constrainChildRect`,
  `RelativeLayout` + `DependencyGraph`, `AppBarLayout.LayoutParams`). L'application **n'émule pas** ces règles : LayoutLib les applique ;
  le code ne s'en sert que pour **choisir les attributs à écrire** et **dessiner les repères**.
- **D3 — Le dépôt choisit des attributs, pas un index.** `PlanInsertionFamille` (pur) transforme (parent, point ou cellule, taille)
  en un `Plan` : attributs, marges multiples de 8 dp, rectangle fantôme, libellé. `InsertionXml` généralise l'injection
  (`ajouterAttributs`) ; `contraindre` reste pour `CONSTRAINT`. `FRAME`/`COORDINATOR` : 9 cellules de gravité, marges de 16 dp sur les bords touchés ;
  `RELATIVE` : adhérence ≤ 28 dp à un frère (règle principale + alignement croisé) sinon ancrage au parent ; `COORDINATOR` +
  `FloatingActionButton` : ancre ; défilant + `AppBarLayout` : comportement, sans marge. Table de référence : 12 insertions en or.
- **D4 — Le popup « Où placer ? » suit la famille du parent résultant** : Avant/Dedans/Après (barres), Derrière/Dedans/Devant +
  grille 3 × 3 (`FRAME`, `COORDINATOR`), Dessous/Dessus/Droite/Gauche + marge ou Dedans + grille + marge (`RELATIVE`). Les positions
  invalides sont filtrées, la première valide est choisie.
- **D5 — Un calque « Relations »** remplace « Contraintes » : lignes pleines pour les règles de côté, pointillées pour les alignements,
  vers le bord du parent, ancre → centre de la vue. **Aucun nouveau jeton** (`--accent`, `--new`, `--new-soft` existent).
- **D6 — Marge de dépôt : 16 dp** (grille de 8 dp). Alternatives mesurées dans le popup (0, 8, 16, 24). *Confirmée par le propriétaire (2026-10-21).*
- **D7 — Les références ne meurent plus.** Suppression : **une** édition retire l'élément et toute valeur `@id/x` / `@+id/x` visant son sous-arbre
  (`SuppressionSure`) ; `anchorGravity` part avec son ancre. Renommage : `RenommageIdentifiant` suffit déjà (test de non-régression).
  Duplication : les références ne sont pas réécrites.
- **D8 — Cinq problèmes de famille** (`ATTRIBUT_ORPHELIN`, `REFERENCE_INTROUVABLE`, `ANCRE_INVALIDE`, `COMPORTEMENT_MANQUANT`,
  `DEPENDANCE_CIRCULAIRE`), calculés **sur le texte** (`ArbreAttributsXml`) pour rester disponibles quand le rendu échoue ;
  chacun avec son correctif. Les erreurs de rendu par boucle sont classées comme les erreurs de rendu existantes (dernier rendu valide conservé).
- **D9 — Cibles tactiles** : mêmes dérogations que l'ADR 0134 (D8) pour les nouveaux contrôles : boutons de position 32,5 dp, cellules
  34 dp, espacement 4 dp ; `contentDescription` partout. *Confirmée par le propriétaire (2026-10-21).*
- **D10 — Palette et inspecteur.** Six entrées (`FrameLayout`, `RelativeLayout`, `CoordinatorLayout`, `AppBarLayout`, `MaterialToolbar`,
  `FloatingActionButton`) ; sections « Dans le parent » et « Conteneur » par famille ; `ControleAttribut.ReferenceId` avec portée
  (`TOUT`, `FRERES`, `SOUS_ARBRE`) et options **grisées si elles ferment une boucle**.
- **Hors périmètre** : `GridLayout`, `TableLayout`, `DrawerLayout`, `MotionLayout` (`ConstraintSet`), `CollapsingToolbarLayout.layout_collapseMode`,
  simulation de `RelativeLayout.gravity`/`ignoreGravity`, de `alignBaseline` exact et des comportements Coordinator autres que le défilement lié.

## Conséquences

- **Positives** : tout parent courant se comprend et s'édite sans quitter l'aperçu ; plus de référence morte à la suppression ;
  les boucles de règles sont expliquées et réparables ; le dépôt produit du XML idiomatique pour chaque famille ; les règles sont
  écrites et testées une fois (fixtures + insertions en or).
- **Coûts** : un champ de plus dans le Parcel AIDL (`InfoVueRendu`) ; un lecteur d'attributs de layout (`ArbreAttributsXml`) ; cinq genres de
  problèmes ; `controleSection` / `carteSection` grossissent (extraire une fonction par contrôle, règle 8) ; ~13 fichiers de ressources
  neufs, `popover_placement.xml` remplacé.
- **Risques** : (1) **sémantique des ancres** déduite du code AndroidX, à confirmer par mesure LayoutLib ; (2) un attribut `app:` peut ne pas
  changer le rendu (limite P3) ; (3) `alignBaseline` approché dans la maquette ; (4) une règle d'adhérence trop agressive (28 dp) peut
  surprendre : réglable dans `PlanInsertionFamille` sans changer l'API ; (5) dépendance à l'état de PE11e-h (contrôles de l'inspecteur).
- **Vérification** : tests JVM (famille, relations, plans, popup, suppression, problèmes, catalogue), Robolectric de cotes contre
  `meas_popups_parents.json`, paires de captures maquette | application, mesure appareil (`PROTOCOLE_MESURE_APPAREIL.md`) des trois fixtures.

## Alternatives écartées

- **Étendre `GenreParent` par valeurs** : garde la comparaison par nom simple, donc les sous-classes (`MaterialCardView`, `AppBarLayout`) restent mal classées.
- **Instancier les familles par `instanceof` dans le moteur** : introduit Material / AndroidX dans `:preview:xml` et casse sa pureté.
- **Écrire un moteur de mise en page pour l'aperçu** (émuler `RelativeLayout`, `CoordinatorLayout`) : doublon de LayoutLib, divergences garanties.
  La maquette le fait pour la démonstration ; l'application, non.
- **Réécrire les références à la duplication** : change le sens du document sans que l'utilisateur l'ait demandé.
- **Forcer une marge de 0 dp au dépôt** : place la vue contre le bord ; moins lisible et à rebours des pratiques Material (16 dp).
- **Bloquer l'insertion qui ferme une boucle** : l'éditeur de texte peut de toute façon l'écrire ; on **grise l'option** dans l'inspecteur
  et on **explique** l'erreur dans l'aperçu.

## Annexe — PV de la mesure PE12h (§ 15.3, consigné 2026-10-22)

Cadre : les trois fixtures (`activity_profil.xml`, `activity_carte.xml`, `activity_fil.xml`) rendues par le pipeline
`RenduLayoutLib` sous Robolectric NATIVE (la même LayoutLib que l'appareil, métriques de police réelles), profil Pixel 4
(393 × 851 dp, 440 dpi), français, police ×1, chrome système complet (24 + 56 + 48 dp ; le fil est rendu avec l'action bar,
voir C1). Relevé maquette : `docs/mockups/ref/parents/meas_rendu_parents.json` (planche Design, `S.b` après `loadDoc`).
La comparaison est RELATIVE à la racine du document, tolérance ±1 dp (spec § 15.3) ; les bornes qui dépendent d'une métrique
de TEXTE ne sont pas comparées (§ 12.1 de la partie II : la police de la maquette n'est pas celle de l'appareil). La séance
sur VRAI appareil (`PROTOCOLE_MESURE_APPAREIL.md`) reste à la charge du propriétaire ; ce PV en garantit l'attendu.

### Ce qui est mesuré et VERT (écarts ≤ 1 dp)

- **Profil (`RelativeLayout`)** : racine 393 × 723 ; `avatar` coin (16, 16) en 80 × 80 (alignParentStart/Top + marges) ;
  `btn_suivre` droite 377 et hauteur 40 (alignParentEnd) ; `separateur` hauteur 1 ; `btn_message` bas de racine − 24 − 48,
  hauteur 48 (alignParentBottom + centerHorizontal).
- **Carte (`FrameLayout`) — la famille INTÉGRALEMENT mesurée** : `fond` plein parent ; `badge` coin haut-fin (droite 377,
  y 16) par `layout_gravity top|end` ; `btn_lecture` (164.4 ; 329.5) en 64 × 64 par `layout_gravity center` ;
  `legende` pleine largeur au bas par `layout_gravity bottom`.
- **Fil (`CoordinatorLayout` + `AppBarLayout`)** : `appbar` 393 × 104 en haut (toolbar 56 + filtres 48, empilement
  `LinearLayout` vertical natif) ; `toolbar` et `filtres` aux bornes exactes ; `liste` x 0, pleine largeur ; `fab_nouveau`
  56 × 56.

### Constats de défauts du moteur (limitent la table de comparaison)

Chaque constat porte la cause exacte relevée dans le code ; le correctif appartient au **frère LayoutLib** (dépôt
`jjoblab/LayoutLib`, tag épinglé v2.2.0) ou au chrome du pipeline — après bump, la table de `MesureRenduParentsTest`
s'étend aux vues exclues et ce PV est complété.

- **F1 — références d'id inertes dans les règles** (`BridgeInflater.resolveIdReference` : quand l'id n'est pas déclaré
  dans les `Resources` du contexte, la boucle `idToView` est VIDE et la fonction rend `View.generateViewId()` — un id
  aléatoire qui ne correspond à AUCUNE vue). `layout_below`/`toEndOf`/`alignTop`/`alignStart`… et `app:layout_anchor`
  sont donc ignorés : la vue tombe au coin haut-début. Chiffré : profil `nom` (16 ; 4) mesuré contre (112 ; 20) attendu ;
  `separateur` y 16 mesuré contre 112 attendu ; fil `fab_nouveau` (0 ; 0) contre (321 ; 76). Touchés : `nom`, `role`,
  `separateur` (y), `bio`, `stats`, `n_projets`, `btn_suivre` (y), `fab_nouveau` (x, y).
- **F2 — comportement non résolu** (`createCoordinatorLayoutParams` fait `Class.forName` de la VALEUR LITTÉRALE de
  `app:layout_behavior` : `@string/appbar_scrolling_view_behavior` n'est jamais résolu en nom de classe). La `liste` du
  fil couvre la barre au lieu de s'y arrêter : (0 ; 0 ; 393 ; 723) mesuré contre (0 ; 104 ; 393 ; 675) attendu.
- **F3 — attributs agrégés non lus** (`produceLayoutParams` ne lit pas `layout_marginHorizontal`/`layout_marginVertical`,
  ni `paddingHorizontal`/`paddingVertical` — API 26) : profil `separateur`/`bio`/`stats` perdent leurs marges
  horizontales (x 0 contre 16, largeur 393 contre 361) ; carte `titre`/`sous_titre` x 0 contre 16 (padding de la
  légende) ; fil `f_tout` x 0 contre 16.
- **C1 — l'action bar du chrome ne se retirait pas** (`ChromeSysteme.attrBool` interrogeait
  `ThemeResolver.getStyleAttribute(null, nom)` qui rend TOUJOURS null quand le style n'est pas nommé — et null de toute
  façon au premier argument) : le fil était rendu AVEC l'action bar, sa racine gardait 723 dp contre 779 à la planche
  `noBar` ; la HAUTEUR de la racine du fil n'était pas comparée. **CORRIGÉ en v0.166.5** : la lecture passe par
  `resolveAttr("?android:attr/…")` (les attributs fenêtre vivent dans le namespace android) ; le diagnostic a de plus
  révélé que le résolveur vivait en REPLI NATIF sur toute la fixture (un commentaire XML de `parents/res` citait
  textuellement un jeton CSS à double tiret — illégal en commentaire XML — : le scan du dossier échouait en silence,
  `catch` → repli sans thèmes) ; la hauteur de la racine du fil (779 dp) est DÉSORMAIS comparée.
- **F4 — métriques de texte gonflées (~×2,7)** : les `TextView`/`Button` `wrap_content` mesurent bien plus que le texte
  (profil `nom` 377 dp de large contre ~143 attendu, `btn_message` clampé au parent) — cause non isolée (suspect :
  double conversion sp de l'AttributeApplier), sans effet sur les bornes comparées (elles sont fixes) ; les captures
  de recette § 15.2 le MONTRENT, c'est leur rôle.

### Captures § 15.2

`CapturesRecetteParentsTest` produit les paires application (popup des trois familles `relatif_cote`/`cadre_devant`/
`coordinateur_devant`, calque Relations sur profil et fil, rendus — clair ET nuit) : PNG écrits dans
`build/captures-pe12h/` du module (hors dépôt, miroir du protocole — jamais de binaire au dépôt), paires maquette
archivées à côté pour la séance de recette du propriétaire. Les défauts ci-dessus restent VISIBLES sur les captures.

### Décision

La mesure a été menée AVANT de figer les rendus (§ 15.3) et remplit son rôle : la CARTE (FrameLayout, gravités
naties) est intégralement vérifiée à ±1 dp ; les règles `RelativeLayout`, l'ancre et le comportement `CoordinatorLayout`
attendent le correctif du frère. **La maquette n'est PAS en cause** (elle simule la sémantique Android de référence) :
la règle « la maquette est corrigée, pas le moteur » ne s'applique à AUCUN de ces constats — le moteur est contredit
par le framework (un vrai `RelativeLayout` applique `below`). Suivra : dépôt des correctifs au frère (bump LayoutLib
au-delà de v2.2.0), re-mesure, extension de la table, PUIS séance appareil du propriétaire.
