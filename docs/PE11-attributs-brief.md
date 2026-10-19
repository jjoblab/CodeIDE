# PE11 — Écran Attributs : fidélité à la maquette, puis deuxième vague (brief pour l'agent d'implémentation)

> À coller tel quel dans la session de l'agent. Lis d'abord `AGENTS.md`, `docs/PREVIEW.md` §3.4, **`docs/PREVIEW_UI.md`** (spécification visuelle, normative), les **ADR 0127, 0128, 0129, 0130**, **`docs/PREVIEW_UI_ECRANS.md`** (partie II : tous les autres écrans) et la maquette `docs/mockups/apercu-xml.html` (version v5 : ouvre-la dans un navigateur ; onglet **Attributs**, mais aussi l'écran principal, la palette, les menus, **Arbre**, **Problèmes**, **Source**).
> La maquette est la référence du **comportement et du design**. Son JavaScript `sectionsFor` est le miroir exact du catalogue décrit en annexe. **Numérotation** : `PE10` est déjà pris par le dépôt (v0.134.39, mode d'affichage / chips / undo) ; cette série s'appelle **PE11**.
> **Deux exigences non négociables** : (1) **tous** les écrans de l'aperçu (Attributs, principal, palette, placement, menus, Arbre, Problèmes, Source) sont **indiscernables de la maquette**, clair et nuit (`PREVIEW_UI.md` § 1 et § 12, `PREVIEW_UI_ECRANS.md` § 12) ; (2) les refontes de l'existant (PE11a-b, PE11i-l) passent **avant** les nouveautés (PE11c-h).
> **Ordre recommandé** : PE11a → PE11b → **PE11i → PE11j → PE11k → PE11l** → PE11c → … → PE11h.

## 1. Objectif

L'onglet Attributs de `PreviewActivity` doit proposer **plus de sections selon la vue sélectionnée**, pour éditer ses attributs sans retourner au code. PE7e et PE9 ont livré une première vague (14 sections, 8 contrôles dans `CatalogueAttributs`). Cette étape la prolonge sans la casser.

Ce qui change pour l'utilisateur :

0. **L'écran prend exactement l'apparence de la maquette** (couleurs, traits, rayons, polices mono/sans, hauteurs, espacements). Aujourd'hui il s'en écarte (`PREVIEW_UI.md` § 3 : champs bleutés sans trait, segments en boutons contournés, cartes de 48 dp au lieu de 30, étiquettes dans la mauvaise police, neutres du thème au lieu de ceux de la maquette). C'est le **premier** chantier (PE11a-b).
1. Chaque classe de vue gagne des sections (typographie, lignes, icônes, saisie, aides du champ, variante de bouton, carte, liste, apparence, transformation, interaction, accessibilité, `tools:`).
2. Un mode **Courants** (6 à 8 sections, les attributs fréquents) et un mode **Tous**.
3. Une **recherche** d'attribut qui ignore ce mode.
4. Un bouton **↺** par champ pour rétablir la valeur par défaut (l'attribut est retiré).
5. Une **validation** avant écriture, avec message sous le champ.
6. Une section **Autres attributs** : ceux du XML que le catalogue ne connaît pas, plus l'ajout d'un attribut libre.
7. Des **corrections rapides** en haut de l'onglet, tirées des problèmes de la vue.
8. Dix nouveaux contrôles (§4).

## 2. État du code à connaître (lu au commit 0ad3cab, v0.134.38 ; dépôt à jour : 6a9325b, v0.135.0 — lot IA-0, ADR 0121-0126)

- `preview/xml/.../CatalogueAttributs.kt` : `ControleAttribut` (sealed : `Identifiant`, `Texte`, `Nombre`, `Choix`, `TriEtat`, `Cotes`, `Dimensions`, `Contraintes`), `SectionAttributs`, `TitreSection` (14 entrées), `Etiquette`, `sections(classe, genreParent)`, `genreParent(...)`. Convention : la première valeur d'un `Choix` est la valeur par défaut, l'attribut est alors retiré.
- `preview/xml/.../EditionAttributsXml.kt` : `lire`, `definir`, `definirReference`, `valeurCote`, `ecrireCote`, `ecrireDimension`, `lireContraintes`, `ecrireContrainte`, `dupliquer`, `supprimer`. `definir` renvoie une édition de texte et ajoute `xmlns:app` ou `xmlns:tools` si besoin.
- `feature/preview/.../VoletAttributsFragment.kt` (979 lignes) : `carteSection`, `controleSection(...)` (un `when` sur `ControleAttribut`), un `controle*` et un layout `controle_*.xml` par contrôle.
- `lsp/xml/.../catalog/CataloguePlateforme.kt` : `AttrCatalogue(nom, formats, enumerations, fanions)`, `BaliseCatalogue(nom, fqcn, superieurs)`, `StyleableCatalogue(nom, attributs)`, lus dans `attrs.xml` et `public.txt` des plateformes installées.
- **Écart visuel** : les ressources de `feature/preview/src/main/res/` s'appuient sur les rôles du thème de l'app (`colorSurface`, `colorOutlineVariant`, `colorSecondaryContainer`) et sur ~70 littéraux `dp` ; relevé complet dans `docs/PREVIEW_UI.md` § 3. Les jetons `preview_probleme_*` et `preview_section_badge_*` contiennent des erreurs de teinte et n'ont pas de variante nuit.
- Limite connue **P3** (ADR 0116) : `applyCustomAttributes` est un stub dans LayoutLib v3. Un attribut `app:*` peut être écrit sans changer le rendu.

## 3. Découpage proposé (un chantier = un push, voir AGENTS.md §7)

Chaque chantier suit la séquence de l'AGENTS.md : chaîne locale à la racine, parité en dernier, version, `CHANGELOG.md`, commit conventionnel en français, push, CI surveillée, chantier suivant immédiatement.

### PE11a — Jetons, thème, test de parité (`:feature:preview`, aucune refonte d'écran)

Lis `docs/PREVIEW_UI.md` § 4-5 et § 12.1, ADR 0127.

- Si absents du dépôt, commiter d'abord (`docs:`) : `docs/PREVIEW_UI.md`, `docs/adr/0127-0129-*.md`, `docs/mockups/ref/**` (captures, `jetons-maquette.json`, mesures, ressources de référence). Si un autre ADR a pris 0127-0129, renuméroter la série et les renvois.
- Copier `values/{attrs,colors,dimens,styles,themes}.xml` et `values-night/colors.xml` de `docs/mockups/ref/ressources/` dans le module (fusionner avec l'existant, **aucun doublon**), plus `values/strings.xml`/`values-en/strings.xml` (chaînes de référence).
- Poser `android:theme="@style/Theme.CodeIDE.Apercu"` sur `PreviewActivity` ; tout popup/fragment gonflé hors de l'activité utilise un `ContextThemeWrapper` de ce thème.
- Migrer les jetons `preview_*` selon la table de `PREVIEW_UI.md` § 5 ; supprimer les doublons ; ne pas toucher `preview_menu_fond`.
- `JetonsMaquetteTest` (JVM pur, `kotlin.test`) : spécification exacte en `PREVIEW_UI.md` § 12.1 ; il **échoue** si un jeton diffère de `jetons-maquette.json`.
- Livrable visible : les teintes de la feuille (surface blanche, traits, gris doux, ambre, badge nuit) deviennent celles de la maquette ; aucune structure ne change. Joindre une paire de captures avant/après, clair et nuit.

### PE11b — Refonte visuelle de l'écran Attributs existant, à l'identique (`:feature:preview`)

Lis `docs/PREVIEW_UI.md` § 3, 6-11, ADR 0128 et 0129. **Aucune nouvelle fonctionnalité** : on refait l'habillage de ce qui existe (8 contrôles, cartes, titre, lecture seule, onglets, poignée, fil d'Ariane) pour qu'il soit indiscernable de la maquette.

- Copier puis adapter les layouts, styles et drawables de `docs/mockups/ref/ressources/` : `carte_section`, `entete_editeur`, `entete_champ`, `controle_texte` + `saisie_texte` + `item_lecture_seule`, `controle_segments` + `item_segment` (en `Chip`), `controle_dimensions` + `item_dimension`, `controle_cotes`, `item_attribut_groupe` + `item_attribut` (lecture seule `.kv`). Garder les **identifiants** du liage quand c'est possible ; sinon adapter `VoletAttributsFragment` et ses tests.
- Remplacer `ScrollView` + `LinearLayout` par `volet_attributs` (`RecyclerView`) : items titre, cartes, groupes de lecture seule ; corps de carte gonflé à l'ouverture seulement ; état ouvert/fermé mémorisé par titre (comportement actuel). Cartes **30 dp** d'en-tête (plus de `minHeight 48dp`), écart de 10 dp entre cartes, volet 8/10/14/10.
- Barre collante (`barre_outils_attributs`) : **habillage et épinglage** livrés ici (`ItemDecoration`), champ de recherche et bascule **masqués** tant que PE11f ne branche pas leur logique ; vérifier l'épinglage avec un `RecyclerView` de test.
- Habiller poignée, fil d'Ariane et onglets de la feuille (`fragment_feuille_apercu.xml`) avec les styles `FilAriane`/`Onglet`/`BadgeOnglet`, `TabLayout` indicateur accent 2 dp.
- **Supprimer** : `fond_champ`, `fond_carte_section`, `fond_badge_definis`, l'ancien `item_segment` (MaterialButton Outlined), les `textAppearanceLabel*` du volet, tous les littéraux `dp`/`sp`/couleur des layouts du volet, toute vue ou `GradientDrawable` construit en code pour ce volet.
- Tests : Robolectric de **cotes** par layout contre `meas_clair.json`/`meas_nuit.json` (`PREVIEW_UI.md` § 12.2) ; les tests existants du volet (liage, édition, validation) **restent verts sans modification de logique**.
- **Recette de fidélité** (§ 12.3-12.4) : paires de captures maquette | application, clair et nuit, pour `Button` (Courants), `TextView`, `EditText` ; **aucun écart visible**. La grille de § 12.4 est recopiée dans le `CHANGELOG.md` du chantier, cases cochées.

### PE11c — Contrôles et formats, en fonctions pures (`:preview:xml`)

- Ajouter à `ControleAttribut` : `Liste(nom, etiquette, valeurs)`, `Drapeaux(nom, etiquette, valeurs)`, `Couleur(nom, etiquette)`, `Ressource(nom, etiquette, types)`, `Curseur(nom, etiquette, min, max, pas, unite, defaut)`, `Ratio(nom, etiquette)`, `TypeSaisie(nom, etiquette)`, `Coins(base, quatreNoms, etiquette)`, `ReferenceId(nom, etiquette)`, `ActionRapide(etiquette, action)`.
- Ajouter `courant: Boolean` (valeur par défaut `false`) à chaque contrôle, et `tailleMax`/`bornes` quand c'est utile.
- Nouvelle classe de données `DescriptionAttribut(nom, formats, enumerations, fanions)`, **sans dépendre de `:lsp:xml`**, et une fonction pure `controlePourFormat(description): ControleAttribut` :

| Format (`attrs.xml`) | Contrôle |
|---|---|
| `enum`, 4 valeurs ou moins | `Choix` |
| `enum`, plus de 4 valeurs | `Liste` |
| `flags` | `Drapeaux` |
| `boolean` | `TriEtat` |
| `color` | `Couleur` |
| `dimension` | `Nombre` en `dp` (`sp` si l'attribut est une taille de texte) |
| `reference` | `Ressource` |
| `float`, `integer` | `Nombre` |
| `string` | `Texte` |
| plusieurs formats | le premier de la liste ci-dessus qui s'applique, `Texte` sinon |

- `ValidationAttribut` pur, testé cas par cas : couleur (`#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`, `@color/`, `@android:color/`, `?attr/`), référence (`@type/nom`, `?attr/`, type accepté, existence dans l'index → avertissement et non erreur), bornes numériques, nom de méthode Java pour `onClick`, `snake_case` et unicité pour `@+id`, ratio (`[W|H,]a:b`, deux nombres positifs).
- Le résultat d'une validation est un type scellé (`Valide`, `Avertissement(message)`, `Erreur(message)`), jamais une exception (règle 6). Les messages sont des ressources, pas des chaînes (règle 3).
- Tests : un test par ligne du tableau ci-dessus et par validateur, cas limites inclus. Couverture Kover du module maintenue.

### PE11d — Catalogue curé, deuxième vague (`:preview:xml`)

- Étendre `sections(classe, genreParent)` avec l'**annexe** (§7). `GenreParent` gagne `FRAME` (enfant d'un FrameLayout ou MaterialCardView : `layout_gravity` seulement). Les familles texte existantes restent regroupées par `familleTexte`, `familleSaisie`, etc.
- Ajouter la section **Racine** (pour le ConstraintLayout racine : aucune édition aujourd'hui).
- `TitreSection` gagne : `TYPOGRAPHIE`, `LIGNES`, `ICONES_TEXTE`, `AIDES_CHAMP`, `ICONES`, `CONTOUR`, `CARTE`, `APPARENCE`, `TRANSFORMATION`, `INTERACTION`, `ACCESSIBILITE`, `APERCU`, `AUTRES`, `RACINE`. `AFFICHAGE` perd `contentDescription`, qui passe en `ACCESSIBILITE`.
- Le choix **Courants** est le champ `courant` de chaque contrôle (annexe, dernière colonne). Un test vérifie qu'en mode Courants aucune classe n'affiche plus de 8 sections.
- Test d'un attribut qui existe déjà dans le document (donc lu) pour chaque nouveau contrôle, plus l'aller-retour `definir` puis `lire`.

### PE11e — « Tous » dérivé du catalogue plateforme

- Un port `PortAttributsPlateforme` dans `:core:domain` : `attributsDe(classe: String): List<DescriptionAttribut>?` (hiérarchie via `BaliseCatalogue.superieurs`, puis `StyleableCatalogue.attributs`). `null` si les plateformes ne sont pas installées.
- Un adaptateur dans `:feature:preview` ou `:lsp:host` selon ce que `ModuleRulesPlugin` autorise. **Lance `./gradlew checkModuleDependencies` tôt** : c'est lui qui bloque, pas le build (directive v0.120.1). Si une liste d'autorisation doit changer, c'est une décision à noter dans un ADR.
- En mode Tous, les attributs de la plateforme absents du catalogue curé apparaissent dans « Autres attributs » avec le contrôle déduit par `controlePourFormat`. Sans plateforme installée : repli silencieux sur le seul catalogue curé, journalisé par `AppLogger`.
- Les attributs `app:*` (Material, ConstraintLayout) ne sont pas dans `attrs.xml` de la plateforme : le catalogue curé les couvre, la dérivation non. À documenter.

### PE11f — Interface (`:feature:preview`)

- Barre **collante** (déjà habillée et épinglée en PE11b) : brancher la logique dans `VoletAttributsFragment` : champ de recherche et bascule Courants / Tous. Les sections se reconstruisent sans reconstruire la barre (la frappe ne perd pas le focus).
- La recherche cherche dans l'étiquette, le nom d'attribut et le titre de section ; elle ouvre les sections qui correspondent. Un nom qui ressemble à `android:xxx`, `app:xxx` ou `tools:xxx` sans résultat propose « Ajouter comme attribut libre ».
- Un layout par nouveau contrôle, **copié puis adapté depuis `docs/mockups/ref/ressources/layout/`** (`controle_liste`, `controle_drapeaux`, `controle_couleur`, `controle_ressource`, `controle_curseur`, `controle_ratio`, `controle_cotes` pour `Coins`, `controle_action`, `controle_attribut_libre`, `item_correctif`, `bouton_autres_attributs`, `barre_outils_attributs` branchée) ; **pas de réinvention** : styles `Widget.CodeIDE.Apercu.*` uniquement, aucun littéral. Material 3, XML + ViewBinding, **pas de Compose** (ADR 0002). Chaque contrôle est comparé à sa capture `docs/mockups/ref/*.png` (clair et nuit) avant de fermer le chantier.
- Drapeaux : `Chip` à bascule dans un `ChipGroup`. Couleur : pastille (damier derrière la transparence), champ, bouton « Palette » qui montre les `@color` du projet. Curseur : `Slider` et champ liés, **l'édition n'est écrite qu'au relâchement**. Liste : `MaterialAutoCompleteTextView` en menu déroulant.
- ↺ : visible seulement quand l'attribut est défini ; retire l'attribut, une édition.
- Puce `app` (couleur d'avertissement) avec la description P3 ; puce `tools` (violet) « aperçu seulement ».
- Un champ invalide n'écrit rien et affiche l'erreur dessous (`setError` du `TextInputLayout`) ; un avertissement écrit quand même.
- Compteur « N définis » dans l'en-tête de section.
- La feuille passe à environ 420 dp quand l'onglet Attributs est actif.
- Aucune logique métier dans le fragment (règle 1) : il appelle les fonctions pures de `:preview:xml` et passe l'édition à `SourceApercu.remplacer`, comme la palette. Une édition de contrôle = une entrée d'historique.
- Toutes les chaînes en `values/` et `values-en/`. Contrainte detekt connue : `controleSection` et `carteSection` grossissent, extraire une fonction par contrôle plutôt que d'ajouter une exemption (règle 8).

### PE11g — Corrections rapides

En haut de l'onglet, une carte par problème de la vue sélectionnée qui a une correction :

| Problème (`ProblemesPreview`) | Bouton | Édition |
|---|---|---|
| Cible tactile trop petite | « Passer à 48 dp » | dimension fixe < 48 → 48 ; `wrap_content` → `minWidth` / `minHeight` = `48dp` |
| Texte codé en dur | « Extraire en @string » | `ExtractionRessource` existant, valeurs FR et EN |
| Texte tronqué | « Autoriser 2 lignes » | `android:maxLines="2"` |
| Sans `contentDescription` | « Renseigner » | ouvre Accessibilité et met le focus sur le champ, **sans édition** |
| Classe introuvable | « Ajouter la dépendance » | édition Gradle + synchronisation, **hors périmètre si elle n'existe pas encore** : n'afficher le bouton que si l'action existe |

### PE11h — Classes supplémentaires (après mesure)

ConstraintLayout (racine déjà dans PE11d), FrameLayout, ScrollView, NestedScrollView, Chip, FloatingActionButton, MaterialToolbar, Guideline. D'abord par la dérivation de PE11e ; une section curée seulement pour celles qui le méritent. **Mesurer avant de promettre** : pour les attributs `app:*`, regarder lesquels changent vraiment le rendu (P3) et mettre le résultat dans `docs/PREVIEW.md`.

### PE11i — Refonte de l'écran principal, à l'identique (`:feature:preview`, `:preview:xml`)

Lis `docs/PREVIEW_UI_ECRANS.md` § 1-3, § 11-12 et l'ADR 0130 (D1, D2, D6, D7, D8). Prérequis : PE11a.

- Copier de `docs/mockups/ref/ressources/` : `values/{dimens_ecrans,styles_ecrans,colors_ecrans,strings_ecrans}.xml`, `values-night/colors_ecrans.xml`, `values-en/strings_ecrans.xml`, les sélecteurs `color/apercu_{mode,puce,hud}_*`, les drawables et vecteurs `apercu_*` (appbar, HUD, état, bandeau, toast, bulle) et le layout **`activity_preview.xml`** (remplace l'existant ; garder les identifiants du liage ; sinon adapter `PreviewActivity` et ses tests). Aucun doublon.
- **Barre du haut** : `LinearLayout` 52 dp (D1) ; **supprimer** `setSupportActionBar`, `toolbarPreview`, `AppBarLayout`/`MaterialToolbar` ; `PopupMenu` ancré à `bouton_plus` ; inset de barre d'état sur `entete_preview`.
- **Mode** : `ChipGroup` de trois `Chip` (D2) ; remplacer `addOnButtonCheckedListener` par `setOnCheckedStateChangeListener`. **Puces** : `Widget.CodeIDE.Apercu.Puce` ; Appareil et Calques en `isActivated` tant que leur menu est ouvert.
- **Bandeau et toast en vues** (D6) : `banniere_rendu` (une ligne, « Ligne n », « Détails » ↔ « Réduire », « Réessayer » en processus arrêté), `toast_apercu` (fondu 200 ms, 2,4 s) à la place de `Toast.makeText`. **Supprimer** `dessinerBandeauErreur` du moteur.
- **HUD** : `TextView` stylés (`BoutonHud`, `ZoomBouton`) à la place des `MaterialButton` ; barre d'édition à 64 dp sous un bandeau visible ; pastille d'état à trois états ; remontée de la pastille et du zoom avec la feuille (10 dp au-dessus).
- **Moteur** (D7) : `JetonsCalques` + `VuePreviewXml.appliquerJetons` ; damier 8 dp (`apercuDamierCase`) ; sélection 2 dp, poignées 9 dp, parent pointillé 1,5 dp à 75 %, frères en bord à 35 %, bornes, marges, padding, étiquettes (mono 11 / 500, rayon 4), épingles 18 dp, zones de dépôt, barre d'insertion 4 dp, légende de planche 22 dp au-dessus ; **toutes les constantes de couleur disparaissent**. `JetonsCalquesTest` (parité).
- Tests : Robolectric de cotes de `activity_preview.xml` contre `meas_ecrans_clair.json` (appbar 52, puces 30, boutons de zoom 36 × 34, barre d'édition 34 × 34, bulle 58) ; tests JVM du moteur (traits, damier) sans régression.
- Recette : paires de captures `ecran-principal-ferme`, `ecran-principal-attributs`, `ecran-bandeau-erreur`, `ecran-statut-frappe`, `ecran-toast`, `ecran-glisser`, clair et nuit.

### PE11j — Bulle, palette, ghost, placement, menus (`:feature:preview`)

Lis `docs/PREVIEW_UI_ECRANS.md` § 4-6 et l'ADR 0130 (D3, D4, D5, D8, D9). Prérequis : PE11i.

- Copier et remplacer : `popover_palette`, `item_puce_categorie`, `item_tuile_palette`, `item_sous_titre_palette`, `item_vide_palette`, `item_ghost_palette`, `popover_placement`, `item_rangee_placement`, `popover_menu_appareil`/`item_menu_appareil`, `popover_menu_calques`/`item_menu_calque`, `popover_menu_cible`/`item_menu_cible`, `bulle_palette` et leurs drawables.
- **Palette** : hauteur `min(600 dp, disponible)` imposée par le code (D9) ; grille 3 colonnes avec `ItemDecoration` d'espacement 8 dp ; mode sélectionné = surface + ombre (`isSelected`) ; tuiles aux cotes de § 4.2 ; icônes `ic_tuile_*` **reteintées** en `?attr/apercuNouveau` (tracés inchangés) ; ghost violet avec l'icône du widget et « → cible ».
- **Placement** : mini-arbre aux rangées de l'arbre (32 dp) ; Avant / Dedans / Après ; résumé ; « Placer ici » ; cible dessinée en direct.
- **Menus** : popovers translucides colorés (D3, écart voulu), options sans radio ni tags, **`SwitchCompat` 34 × 20** (D5), groupe « Chrome système » avec « Barre de navigation » (id `calque_navigation`, à brancher sur l'option correspondante de la configuration de rendu).
- Tests : cotes Robolectric (tuile ≥ 112, onglets de mode 48,8, option 51,2 / 40, interrupteur 34 × 20) ; test de la hauteur bornée de la palette ; tests existants verts.
- Recette : `ecran-palette-glisser`, `-popup`, `-recherche`, `ecran-placement`, `ecran-menu-appareil`, `-calques`, `-plus`, clair et nuit.

### PE11k — Onglets Arbre, Problèmes, Source (`:feature:preview`)

Lis `docs/PREVIEW_UI_ECRANS.md` § 7-9. Prérequis : PE11b.

- Copier et remplacer : `item_arbre`, `item_probleme`, `item_ligne_source`, `fragment_volet_arbre`, `fragment_volet_problemes`, `fragment_volet_source`, drawables `apercu_rangee_fond`, `apercu_monogramme_fond`, `apercu_badge_rangee_*`, `apercu_probleme_point`, `apercu_source_ligne_fond`.
- **Arbre** : rangée 34 dp, monogramme 20 dp, badge neutre ou avertissement. **Problèmes** : point 8 dp, « Voir », message vide, badge d'onglet masqué à zéro. **Source** : lignes d'adaptateur, spans de coloration, `isSelected` (racine) / `isActivated` (plage de la vue) / surlignage temporaire de l'insertion ; plage amenée à 70 dp du haut ; bouton « Ouvrir dans l'éditeur ↗ ». Lignes longues tronquées (point ouvert O4).
- Tests : cotes (rangée 34, problème ≥ 62, ligne 20, numéro 24) ; tests existants verts.
- Recette : `ecran-arbre`, `ecran-problemes`, `ecran-source`, clair et nuit.

### PE11l — Recette finale

- `JetonsMaquetteTest` (39 jetons) et `JetonsCalquesTest` verts ; Robolectric de cotes pour **tous** les layouts de la partie II ; grille de contrôle `PREVIEW_UI_ECRANS.md` § 12.4 cochée dans le `CHANGELOG.md` ; recherche des littéraux (`#`, `dp`, `sp`) dans les layouts : vide ; mise à jour de `docs/mockups/apercu-xml.html` dans le dépôt ; `docs/PREVIEW.md` §3.4 : lignes PE11i-l, écarts voulus datés ; confirmation du propriétaire des points O1-O5.

## 4. Les dix nouveaux contrôles

| Contrôle | Écriture | Détails |
|---|---|---|
| `Liste` | valeur de l'enum ; « par défaut » retire | affiche le dernier segment pour `layoutManager` (`LinearLayoutManager`) et les styles |
| `Drapeaux` | valeurs jointes par `\|`, dans l'ordre du catalogue | conserve les mots inconnus déjà présents dans le document |
| `Couleur` | texte saisi ou `@color/…` choisi | valide avant écriture ; la pastille montre la couleur résolue (`@color`, `?attr/` via le thème) |
| `Ressource` | `@type/nom` ; un nom nu reçoit le premier type | complétion depuis l'index des ressources ; introuvable = avertissement |
| `Curseur` | `valeur + unité`, retire si égal au défaut | borné ; bornes dans le catalogue |
| `Ratio` | `[W\|H,]a:b` | préréglages 1:1, 4:3, 16:9, 3:4 ; rappel : demande `0dp` sur la dimension calculée |
| `TypeSaisie` | `base\|modificateur…` | modificateurs réservés aux bases `text*` ; `text` seul = attribut retiré |
| `Coins` | un attribut par coin (`boxCornerRadius*`) | `0` ou vide retire |
| `ReferenceId` | `@id/nom` | liste des identifiants de l'arbre, sans la vue elle-même |
| `ActionRapide` | une édition regroupée | ex. « Remplir l'espace restant » = `layout_weight=1` et dimension `0dp` |

Le biais vertical est **désactivé** (avec explication) tant que la vue n'a pas de contrainte en haut **et** en bas. Teste avec `lireContraintes`.

## 5. Règles du dépôt à respecter (AGENTS.md)

- KDoc, commentaires, messages d'erreur, commits et documentation **en français**.
- **Règle 1** : aucune logique métier dans Activity/Fragment/ViewModel ; **règle 3** : aucune chaîne ni couleur en dur (ressources, jetons de `core/ui`) ; **règle 6** : erreurs attendues en types scellés ; **règle 8** : pas d'exemption detekt pour passer le build.
- **Fidélité visuelle** (ADR 0127-0129) : aucune valeur de style choisie à la main ; couleurs `?attr/apercu*`, dimensions `@dimen/apercu_*`, styles `Widget.CodeIDE.Apercu.*` ; tous les `TextView` héritent de `Widget.CodeIDE.Apercu.Texte` ; pas de `GradientDrawable`/vue construite en code ; un test de parité garde les jetons ; chaque chantier visuel joint ses paires de captures.
- **Règle 13** : n'écris dans le dépôt **aucun nom d'un autre éditeur ou IDE Android**, dans le code, les KDoc, les docs, le `CHANGELOG.md` ou les commits. La maquette jointe est une référence de comportement, pas un texte à copier tel quel dans le dépôt sans relecture.
- Version incrémentée par `scripts/bump-version.sh patch`, `CHANGELOG.md` à jour, tag annoté, `docs/PREVIEW.md` §3.4 mis à jour (lignes PE11a à PE11h, écarts voulus datés).
- Chaîne locale **à la racine** : `./gradlew spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify --stacktrace`. La baseline de parité (`:lsp:kotlin:test -Dparite.majBase=true`) se régénère **en dernier**.
- Ne relance pas de rendu à chaque frappe de curseur ou de champ de recherche : un rendu par édition écrite.

## 6. Critères d'acceptation (à vérifier sur appareil)

1. Sélectionner un `TextView`, `Button`, `EditText`, `TextInputLayout`, `CheckBox`, `Switch`, `ImageView`, `LinearLayout`, `MaterialCardView`, `ProgressBar`, `SeekBar`, `RecyclerView` : l'onglet affiche les sections de l'annexe, en Courants puis en Tous.
2. La recherche de `bias` sur un enfant du ConstraintLayout n'affiche que la section Contraintes ; `zzz` affiche « Aucun attribut ne correspond ».
3. Une couleur invalide ne modifie pas le document et affiche l'erreur ; `#ff0000` écrit `android:textColor="#ff0000"`.
4. Basculer un drapeau écrit `a|b` dans l'ordre du catalogue ; tout désactiver retire l'attribut.
5. ↺ retire l'attribut ; annuler le rétablit ; une édition = une entrée d'historique.
6. `tools:text` écrit `xmlns:tools` à la racine si absent et change l'aperçu.
7. Les corrections rapides du tableau PE11g produisent chacune **une** édition et font disparaître le problème.
8. Un attribut `app:*` modifié porte la puce P3. Vérifier sur chaque attribut Material ajouté si le rendu change, et le noter.
9. Aucun débordement horizontal à 360 dp, thème clair et sombre, police ×1,3 et ×2.
10. `controlePourFormat` : un test par format ; `ValidationAttribut` : un test par règle.
11. **Fidélité visuelle** : pour `Button` (Courants et Tous), `TextView`, `EditText`, `ImageView`, `CheckBox` et la racine, la paire de captures maquette | application est indiscernable (couleurs exactes, rayons, traits, polices mono/sans, hauteurs, écarts), clair et nuit (`PREVIEW_UI.md` § 12.3).
12. `JetonsMaquetteTest` et les tests de cotes Robolectric sont verts ; `grep` des littéraux de couleur/`dp` dans les layouts du volet est vide.
13. En mode Tous (70+ contrôles) : défilement fluide, aucune reconstruction du volet entier à la frappe, pas de crash au gonflage.
14. **Autres écrans** : écran principal, palette, placement, menus, Arbre, Problèmes et Source indiscernables de leurs captures `ref/ecran-*.png`, clair et nuit ; palette bornée à 600 dp ; bandeau et toast sont des vues ; le moteur ne contient plus de constante de couleur ; aucun texte tronqué dans la barre du haut à 360 dp et police ×1,3.

## 7. Annexe : catalogue curé, deuxième vague

Généré depuis la maquette (`sectionsFor`). « Courant » = affiché en mode Courants. Les sections communes sont décrites une fois ; les contrôles marqués `—` n'ont pas de nom d'attribut propre (largeur, marges, padding : contrôles existants).

#### Identité — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:id` | Identifiant — identifiant |  |
| `android:tag` | Texte |  |

#### Disposition — Button, CheckBox, EditText, LinearLayout, MaterialCardView, ProgressBar, RecyclerView, SeekBar, Switch, TextInputLayout, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `—` | Dimensions — largeur et hauteur | oui |
| `—` | Cotes — marges | oui |
| `—` | Cotes — padding | oui |
| `android:minHeight` | Nombre — minHeight |  |
| `android:minWidth` | Nombre [≥ 0] dp |  |
| `android:maxWidth` | Nombre [≥ 0] dp |  |
| `android:maxHeight` | Nombre [≥ 0] dp |  |

_Disposition, pour ImageView : sans maxWidth ni maxHeight (déjà dans la section Image)._

#### Apparence — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:background` | Couleur |  |
| `android:backgroundTint` | Couleur |  |
| `android:foreground` | Ressource (@drawable) |  |
| `android:elevation` | Curseur [0 à 24] dp |  |
| `android:clipToOutline` | TriEtat |  |
| `android:theme` | Ressource (@style) |  |

#### Affichage — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:visibility` | Choix (visible, invisible, gone) | oui |
| `android:alpha` | Curseur [0 à 1] | oui |

#### Transformation — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:rotation` | Curseur [-180 à 180] |  |
| `android:scaleX` | Curseur [0.1 à 3] |  |
| `android:scaleY` | Curseur [0.1 à 3] |  |
| `android:translationX` | Curseur [-100 à 100] dp |  |
| `android:translationY` | Curseur [-100 à 100] dp |  |
| `android:translationZ` | Curseur [0 à 24] dp |  |

#### Interaction — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:enabled` | TriEtat | oui |
| `android:clickable` | TriEtat |  |
| `android:focusable` | TriEtat |  |
| `android:focusableInTouchMode` | TriEtat |  |
| `android:longClickable` | TriEtat |  |
| `android:onClick` | Texte | oui |

#### Accessibilité — toutes les vues

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:contentDescription` | Texte | oui |
| `android:importantForAccessibility` | Liste (auto, yes, no, noHideDescendants) |  |
| `android:accessibilityHeading` | TriEtat |  |
| `android:labelFor` | ReferenceId |  |
| `android:tooltipText` | Texte |  |
| `android:screenReaderFocusable` | TriEtat |  |

#### Aperçu uniquement — EditText, LinearLayout, MaterialCardView, ProgressBar, SeekBar, TextInputLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:visibility` | Choix (visible, invisible, gone) |  |
| `tools:ignore` | Texte |  |

_Aperçu uniquement : le premier attribut dépend de la classe (`tools:text` pour la famille texte, `tools:src` pour ImageView, `tools:itemCount` pour RecyclerView, rien pour les autres) ; `tools:visibility` et `tools:ignore` pour toutes._



### Sections propres à une famille de vues

#### Texte — Button, CheckBox, Switch, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:text` | Texte (extrayable) — texte/libellé | oui |
| `android:textSize` | Nombre — taille | oui |
| `android:textStyle` | Drapeaux (bold, italic) | oui |
| `android:textColor` | Couleur | oui |
| `android:gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |
| `android:textAlignment` | Liste (gravity, textStart, center, textEnd, viewStart, viewEnd) |  |
| `android:textAppearance` | Liste (?attr/textAppearanceHeadlineLarge, ?attr/textAppearanceHeadlineMedium, ?attr/textAppearanceTitleLarge, ?attr/textAppearanceTitleMedium, ?attr/textAppearanceBodyLarge, ?attr/textAppearanceBodyMedium, ?attr/textAppearanceLabelLarge) |  |

#### Typographie — Button, CheckBox, EditText, Switch, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:fontFamily` | Liste (sans-serif, sans-serif-medium, sans-serif-light, serif, monospace, cursive) |  |
| `android:textFontWeight` | Liste (100, 200, 300, 400, 500, 600, 700, 800, 900) |  |
| `android:textAllCaps` | TriEtat |  |
| `android:letterSpacing` | Curseur [-0.1 à 0.5] |  |
| `android:lineSpacingExtra` | Nombre [≥ 0] dp |  |
| `android:lineSpacingMultiplier` | Nombre [0.5 à 3] |  |
| `android:includeFontPadding` | TriEtat |  |

#### Lignes et débordement — Button, CheckBox, EditText, Switch, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:maxLines` | Nombre [1 à 50] |  |
| `android:minLines` | Nombre [1 à 50] |  |
| `android:ellipsize` | Liste (none, start, middle, end, marquee) |  |
| `android:autoLink` | Drapeaux (web, email, phone, map, all) |  |
| `android:textIsSelectable` | TriEtat |  |
| `android:autoSizeTextType` | Choix (none, uniform) |  |
| `android:autoSizeMinTextSize` | Nombre [≥ 1] sp |  |
| `android:autoSizeMaxTextSize` | Nombre [≥ 1] sp |  |

#### Icônes du texte — Button, CheckBox, EditText, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:drawableStart` | Ressource (@drawable) |  |
| `android:drawableEnd` | Ressource (@drawable) |  |
| `android:drawableTop` | Ressource (@drawable) |  |
| `android:drawableBottom` | Ressource (@drawable) |  |
| `android:drawablePadding` | Nombre [≥ 0] dp |  |
| `android:drawableTint` | Couleur |  |

#### Dans le parent — CheckBox, EditText, LinearLayout, MaterialCardView, ProgressBar, RecyclerView, SeekBar, Switch, TextInputLayout, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:layout_weight` | Nombre [≥ 0] | oui |
| `android:layout_gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |
| `—` | ActionRapide — «Remplir l’espace restant» | oui |

#### Aperçu uniquement — Button, CheckBox, Switch, TextView

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:text` | Texte | oui |
| `tools:visibility` | Choix (visible, invisible, gone) |  |
| `tools:ignore` | Texte |  |

#### Bouton — Button

| Attribut | Contrôle | Courant |
|---|---|---|
| `style` | Liste (Widget.Material3.Button, Widget.Material3.Button.OutlinedButton, Widget.Material3.Button.TextButton, Widget.Material3.Button.TonalButton, Widget.Material3.Button.ElevatedButton) | oui |
| `app:cornerRadius` | Nombre [≥ 0] dp | oui |
| `app:backgroundTint` | Couleur | oui |
| `app:icon` | Ressource (@drawable) | oui |
| `app:iconGravity` | Liste (start, textStart, end, textEnd, top, textTop) |  |
| `app:iconPadding` | Nombre [≥ 0] dp |  |
| `app:iconTint` | Couleur |  |
| `app:iconSize` | Nombre [≥ 0] dp |  |
| `app:strokeColor` | Couleur |  |
| `app:strokeWidth` | Nombre [≥ 0] dp |  |
| `app:rippleColor` | Couleur |  |
| `android:insetTop` | Nombre dp |  |
| `android:insetBottom` | Nombre dp |  |

#### Contraintes — Button

| Attribut | Contrôle | Courant |
|---|---|---|
| `—` | Contraintes — ancres lues | oui |
| `app:layout_constraintHorizontal_bias` | Curseur [0 à 1] | oui |
| `app:layout_constraintVertical_bias` | Curseur [0 à 1] |  |
| `app:layout_constraintWidth_percent` | Curseur [0.1 à 1] |  |
| `app:layout_constraintDimensionRatio` | Ratio |  |
| `app:layout_constraintWidth_min` | Nombre [≥ 0] dp |  |
| `app:layout_constraintWidth_max` | Nombre [≥ 0] dp |  |

#### État — CheckBox

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:checked` | TriEtat | oui |
| `app:buttonTint` | Couleur | oui |
| `android:button` | Ressource (@drawable) |  |

#### État — Switch

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:checked` | TriEtat | oui |
| `android:thumbTint` | Couleur | oui |
| `android:trackTint` | Couleur | oui |
| `android:showText` | TriEtat |  |
| `android:textOn` | Texte |  |
| `android:textOff` | Texte |  |

#### Saisie — EditText

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:hint` | Texte (extrayable) — hint | oui |
| `android:inputType` | TypeSaisie | oui |
| `android:imeOptions` | Liste (actionNone, actionGo, actionSearch, actionSend, actionNext, actionDone) | oui |
| `android:maxLength` | Nombre [≥ 1] | oui |
| `android:digits` | Texte |  |
| `android:autofillHints` | Liste (emailAddress, username, password, newPassword, name, phone, postalAddress, postalCode, smsOTPCode) |  |
| `android:importantForAutofill` | Liste (auto, yes, no, noExcludeDescendants) |  |
| `android:selectAllOnFocus` | TriEtat |  |
| `android:cursorVisible` | TriEtat |  |
| `android:textColorHint` | Couleur |  |

#### Champ — TextInputLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:hint` | Texte (extrayable) — texte/libellé | oui |
| `app:boxBackgroundMode` | Choix (outline, filled, none) | oui |
| `style` | Liste (Widget.Material3.TextInputLayout.OutlinedBox, Widget.Material3.TextInputLayout.FilledBox, Widget.Material3.TextInputLayout.OutlinedBox.Dense) |  |
| `app:hintEnabled` | TriEtat |  |

#### Aides et compteur — TextInputLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `app:helperText` | Texte | oui |
| `app:errorEnabled` | TriEtat |  |
| `app:counterEnabled` | TriEtat |  |
| `app:counterMaxLength` | Nombre [≥ 1] |  |
| `app:placeholderText` | Texte |  |
| `app:prefixText` | Texte |  |
| `app:suffixText` | Texte |  |

#### Icônes — TextInputLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `app:startIconDrawable` | Ressource (@drawable) | oui |
| `app:endIconMode` | Liste (none, password_toggle, clear_text, custom, dropdown_menu) | oui |
| `app:endIconTint` | Couleur |  |

#### Contour — TextInputLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `app:boxStrokeColor` | Couleur |  |
| `app:boxStrokeWidth` | Nombre [≥ 0] dp |  |
| `app:boxBackgroundColor` | Couleur |  |
| `app:boxCornerRadiusTopStart` | Coins |  |

#### Image — ImageView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:src` | Ressource (@drawable) | oui |
| `app:srcCompat` | Ressource (@drawable) |  |
| `android:scaleType` | Liste (matrix, fitXY, fitStart, fitCenter, fitEnd, center, centerCrop, centerInside) | oui |
| `android:adjustViewBounds` | TriEtat |  |
| `app:tint` | Couleur |  |
| `android:tintMode` | Liste (src_in, src_atop, multiply, screen, add) |  |
| `android:cropToPadding` | TriEtat |  |
| `android:maxWidth` | Nombre [≥ 0] dp |  |
| `android:maxHeight` | Nombre [≥ 0] dp |  |

#### Dans le parent — ImageView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:layout_gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |

#### Disposition — ImageView

| Attribut | Contrôle | Courant |
|---|---|---|
| `—` | Dimensions — largeur et hauteur | oui |
| `—` | Cotes — marges | oui |
| `—` | Cotes — padding | oui |
| `android:minHeight` | Nombre — minHeight |  |
| `android:minWidth` | Nombre [≥ 0] dp |  |

#### Aperçu uniquement — ImageView

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:src` | Ressource (@drawable) | oui |
| `tools:visibility` | Choix (visible, invisible, gone) |  |
| `tools:ignore` | Texte |  |

#### Conteneur — LinearLayout, MaterialCardView

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:orientation` | Choix — orientation | oui |
| `android:gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |
| `android:baselineAligned` | TriEtat |  |
| `android:weightSum` | Nombre [≥ 0] |  |
| `android:showDividers` | Drapeaux (beginning, middle, end) |  |
| `android:divider` | Ressource (@drawable) |  |
| `android:dividerPadding` | Nombre [≥ 0] dp |  |
| `android:measureWithLargestChild` | TriEtat |  |
| `android:clipChildren` | TriEtat |  |
| `android:clipToPadding` | TriEtat |  |
| `android:animateLayoutChanges` | TriEtat |  |

#### Carte — MaterialCardView

| Attribut | Contrôle | Courant |
|---|---|---|
| `app:cardBackgroundColor` | Couleur | oui |
| `app:cardCornerRadius` | Nombre [≥ 0] dp | oui |
| `app:cardElevation` | Nombre [≥ 0] dp | oui |
| `style` | Liste (Widget.Material3.CardView.Elevated, Widget.Material3.CardView.Filled, Widget.Material3.CardView.Outlined) |  |
| `app:strokeColor` | Couleur |  |
| `app:strokeWidth` | Nombre [≥ 0] dp |  |
| `app:rippleColor` | Couleur |  |
| `app:contentPadding` | Nombre [≥ 0] dp |  |
| `app:cardUseCompatPadding` | TriEtat |  |
| `app:cardPreventCornerOverlap` | TriEtat |  |
| `android:checkable` | TriEtat |  |

#### Progression — ProgressBar

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:max` | Nombre [≥ 1] | oui |
| `android:progress` | Curseur [0 à 100] | oui |
| `android:min` | Nombre [≥ 0] |  |
| `android:secondaryProgress` | Nombre [≥ 0] |  |
| `android:indeterminate` | TriEtat | oui |
| `android:progressTint` | Couleur | oui |
| `android:progressBackgroundTint` | Couleur |  |
| `android:indeterminateTint` | Couleur |  |

#### Progression — SeekBar

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:max` | Nombre [≥ 1] | oui |
| `android:progress` | Curseur [0 à 100] | oui |
| `android:min` | Nombre [≥ 0] |  |
| `android:secondaryProgress` | Nombre [≥ 0] |  |
| `android:thumbTint` | Couleur | oui |
| `android:progressTint` | Couleur | oui |
| `android:thumb` | Ressource (@drawable) |  |
| `android:splitTrack` | TriEtat |  |
| `android:tickMark` | Ressource (@drawable) |  |

#### Liste — RecyclerView

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:listitem` | Ressource (@layout) | oui |
| `app:layoutManager` | Liste (LinearLayoutManager, GridLayoutManager, StaggeredGridLayoutManager) | oui |
| `android:orientation` | Choix (vertical, horizontal) |  |
| `app:spanCount` | Nombre [1 à 12] |  |
| `app:reverseLayout` | TriEtat |  |
| `app:stackFromEnd` | TriEtat |  |
| `android:clipToPadding` | TriEtat |  |
| `android:scrollbars` | Drapeaux (vertical, horizontal) |  |
| `android:overScrollMode` | Liste (always, ifContentScrolls, never) |  |
| `android:nestedScrollingEnabled` | TriEtat |  |

#### Aperçu uniquement — RecyclerView

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:itemCount` | Nombre [1 à 12] | oui |
| `tools:visibility` | Choix (visible, invisible, gone) |  |
| `tools:ignore` | Texte |  |

#### Racine — ConstraintLayout (racine)

| Attribut | Contrôle | Courant |
|---|---|---|
| `tools:context` | Texte | oui |
| `android:background` | Couleur | oui |
| `—` | Cotes — padding | oui |
| `android:fitsSystemWindows` | TriEtat |  |
| `android:clipToPadding` | TriEtat |  |
| `android:minHeight` | Nombre dp |  |

### Enfants selon le parent

#### Enfant d’un ConstraintLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `—` | Contraintes | oui |
| `app:layout_constraintHorizontal_bias` | Curseur [0 à 1] | oui |
| `app:layout_constraintVertical_bias` | Curseur [0 à 1] |  |
| `app:layout_constraintWidth_percent` | Curseur [0.1 à 1] |  |
| `app:layout_constraintDimensionRatio` | Ratio |  |
| `app:layout_constraintWidth_min` | Nombre [≥ 0] dp |  |
| `app:layout_constraintWidth_max` | Nombre [≥ 0] dp |  |

#### Enfant d’un LinearLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:layout_weight` | Nombre | oui |
| `android:layout_gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |
| `—` | ActionRapide | oui |

#### Enfant d’un MaterialCardView / FrameLayout

| Attribut | Contrôle | Courant |
|---|---|---|
| `android:layout_gravity` | Drapeaux (start, end, top, bottom, center_horizontal, center_vertical, center, fill) | oui |
