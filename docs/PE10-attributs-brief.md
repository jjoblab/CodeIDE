# PE10 — Écran Attributs : deuxième vague (brief pour l'agent d'implémentation)

> À coller tel quel dans la session de l'agent. Lis d'abord `AGENTS.md`, `docs/PREVIEW.md` §3.4 et la maquette `docs/mockups/apercu-xml.html` (version v3 jointe à ce brief : ouvre-la dans un navigateur, onglet **Attributs**).
> La maquette est la référence du comportement. Son JavaScript `sectionsFor` est le miroir exact du catalogue décrit en annexe.

## 1. Objectif

L'onglet Attributs de `PreviewActivity` doit proposer **plus de sections selon la vue sélectionnée**, pour éditer ses attributs sans retourner au code. PE7e et PE9 ont livré une première vague (14 sections, 8 contrôles dans `CatalogueAttributs`). Cette étape la prolonge sans la casser.

Ce qui change pour l'utilisateur :

1. Chaque classe de vue gagne des sections (typographie, lignes, icônes, saisie, aides du champ, variante de bouton, carte, liste, apparence, transformation, interaction, accessibilité, `tools:`).
2. Un mode **Courants** (6 à 8 sections, les attributs fréquents) et un mode **Tous**.
3. Une **recherche** d'attribut qui ignore ce mode.
4. Un bouton **↺** par champ pour rétablir la valeur par défaut (l'attribut est retiré).
5. Une **validation** avant écriture, avec message sous le champ.
6. Une section **Autres attributs** : ceux du XML que le catalogue ne connaît pas, plus l'ajout d'un attribut libre.
7. Des **corrections rapides** en haut de l'onglet, tirées des problèmes de la vue.
8. Dix nouveaux contrôles (§4).

## 2. État du code à connaître (lu au commit 0ad3cab, v0.134.38)

- `preview/xml/.../CatalogueAttributs.kt` : `ControleAttribut` (sealed : `Identifiant`, `Texte`, `Nombre`, `Choix`, `TriEtat`, `Cotes`, `Dimensions`, `Contraintes`), `SectionAttributs`, `TitreSection` (14 entrées), `Etiquette`, `sections(classe, genreParent)`, `genreParent(...)`. Convention : la première valeur d'un `Choix` est la valeur par défaut, l'attribut est alors retiré.
- `preview/xml/.../EditionAttributsXml.kt` : `lire`, `definir`, `definirReference`, `valeurCote`, `ecrireCote`, `ecrireDimension`, `lireContraintes`, `ecrireContrainte`, `dupliquer`, `supprimer`. `definir` renvoie une édition de texte et ajoute `xmlns:app` ou `xmlns:tools` si besoin.
- `feature/preview/.../VoletAttributsFragment.kt` (979 lignes) : `carteSection`, `controleSection(...)` (un `when` sur `ControleAttribut`), un `controle*` et un layout `controle_*.xml` par contrôle.
- `lsp/xml/.../catalog/CataloguePlateforme.kt` : `AttrCatalogue(nom, formats, enumerations, fanions)`, `BaliseCatalogue(nom, fqcn, superieurs)`, `StyleableCatalogue(nom, attributs)`, lus dans `attrs.xml` et `public.txt` des plateformes installées.
- Limite connue **P3** (ADR 0116) : `applyCustomAttributes` est un stub dans LayoutLib v3. Un attribut `app:*` peut être écrit sans changer le rendu.

## 3. Découpage proposé (un chantier = un push, voir AGENTS.md §7)

Chaque chantier suit la séquence de l'AGENTS.md : chaîne locale à la racine, parité en dernier, version, `CHANGELOG.md`, commit conventionnel en français, push, CI surveillée, chantier suivant immédiatement.

### PE10a — Contrôles et formats, en fonctions pures (`:preview:xml`)

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

### PE10b — Catalogue curé, deuxième vague (`:preview:xml`)

- Étendre `sections(classe, genreParent)` avec l'**annexe** (§7). `GenreParent` gagne `FRAME` (enfant d'un FrameLayout ou MaterialCardView : `layout_gravity` seulement). Les familles texte existantes restent regroupées par `familleTexte`, `familleSaisie`, etc.
- Ajouter la section **Racine** (pour le ConstraintLayout racine : aucune édition aujourd'hui).
- `TitreSection` gagne : `TYPOGRAPHIE`, `LIGNES`, `ICONES_TEXTE`, `AIDES_CHAMP`, `ICONES`, `CONTOUR`, `CARTE`, `APPARENCE`, `TRANSFORMATION`, `INTERACTION`, `ACCESSIBILITE`, `APERCU`, `AUTRES`, `RACINE`. `AFFICHAGE` perd `contentDescription`, qui passe en `ACCESSIBILITE`.
- Le choix **Courants** est le champ `courant` de chaque contrôle (annexe, dernière colonne). Un test vérifie qu'en mode Courants aucune classe n'affiche plus de 8 sections.
- Test d'un attribut qui existe déjà dans le document (donc lu) pour chaque nouveau contrôle, plus l'aller-retour `definir` puis `lire`.

### PE10c — « Tous » dérivé du catalogue plateforme

- Un port `PortAttributsPlateforme` dans `:core:domain` : `attributsDe(classe: String): List<DescriptionAttribut>?` (hiérarchie via `BaliseCatalogue.superieurs`, puis `StyleableCatalogue.attributs`). `null` si les plateformes ne sont pas installées.
- Un adaptateur dans `:feature:preview` ou `:lsp:host` selon ce que `ModuleRulesPlugin` autorise. **Lance `./gradlew checkModuleDependencies` tôt** : c'est lui qui bloque, pas le build (directive v0.120.1). Si une liste d'autorisation doit changer, c'est une décision à noter dans un ADR.
- En mode Tous, les attributs de la plateforme absents du catalogue curé apparaissent dans « Autres attributs » avec le contrôle déduit par `controlePourFormat`. Sans plateforme installée : repli silencieux sur le seul catalogue curé, journalisé par `AppLogger`.
- Les attributs `app:*` (Material, ConstraintLayout) ne sont pas dans `attrs.xml` de la plateforme : le catalogue curé les couvre, la dérivation non. À documenter.

### PE10d — Interface (`:feature:preview`)

- Barre **collante** en haut de `VoletAttributsFragment` : champ de recherche et bascule Courants / Tous. Les sections se reconstruisent sans reconstruire la barre (la frappe ne perd pas le focus).
- La recherche cherche dans l'étiquette, le nom d'attribut et le titre de section ; elle ouvre les sections qui correspondent. Un nom qui ressemble à `android:xxx`, `app:xxx` ou `tools:xxx` sans résultat propose « Ajouter comme attribut libre ».
- Un layout par nouveau contrôle : `controle_liste.xml`, `controle_drapeaux.xml`, `controle_couleur.xml`, `controle_ressource.xml`, `controle_curseur.xml`, `controle_ratio.xml`, `controle_saisie.xml`, `controle_coins.xml`, `controle_action.xml`, `controle_attribut_libre.xml`. Material 3, XML + ViewBinding, **pas de Compose** (ADR 0002).
- Drapeaux : `Chip` à bascule dans un `ChipGroup`. Couleur : pastille (damier derrière la transparence), champ, bouton « Palette » qui montre les `@color` du projet. Curseur : `Slider` et champ liés, **l'édition n'est écrite qu'au relâchement**. Liste : `MaterialAutoCompleteTextView` en menu déroulant.
- ↺ : visible seulement quand l'attribut est défini ; retire l'attribut, une édition.
- Puce `app` (couleur d'avertissement) avec la description P3 ; puce `tools` (violet) « aperçu seulement ».
- Un champ invalide n'écrit rien et affiche l'erreur dessous (`setError` du `TextInputLayout`) ; un avertissement écrit quand même.
- Compteur « N définis » dans l'en-tête de section.
- La feuille passe à environ 420 dp quand l'onglet Attributs est actif.
- Aucune logique métier dans le fragment (règle 1) : il appelle les fonctions pures de `:preview:xml` et passe l'édition à `SourceApercu.remplacer`, comme la palette. Une édition de contrôle = une entrée d'historique.
- Toutes les chaînes en `values/` et `values-en/`. Contrainte detekt connue : `controleSection` et `carteSection` grossissent, extraire une fonction par contrôle plutôt que d'ajouter une exemption (règle 8).

### PE10e — Corrections rapides

En haut de l'onglet, une carte par problème de la vue sélectionnée qui a une correction :

| Problème (`ProblemesPreview`) | Bouton | Édition |
|---|---|---|
| Cible tactile trop petite | « Passer à 48 dp » | dimension fixe < 48 → 48 ; `wrap_content` → `minWidth` / `minHeight` = `48dp` |
| Texte codé en dur | « Extraire en @string » | `ExtractionRessource` existant, valeurs FR et EN |
| Texte tronqué | « Autoriser 2 lignes » | `android:maxLines="2"` |
| Sans `contentDescription` | « Renseigner » | ouvre Accessibilité et met le focus sur le champ, **sans édition** |
| Classe introuvable | « Ajouter la dépendance » | édition Gradle + synchronisation, **hors périmètre si elle n'existe pas encore** : n'afficher le bouton que si l'action existe |

### PE10f — Classes supplémentaires (après mesure)

ConstraintLayout (racine déjà dans PE10b), FrameLayout, ScrollView, NestedScrollView, Chip, FloatingActionButton, MaterialToolbar, Guideline. D'abord par la dérivation de PE10c ; une section curée seulement pour celles qui le méritent. **Mesurer avant de promettre** : pour les attributs `app:*`, regarder lesquels changent vraiment le rendu (P3) et mettre le résultat dans `docs/PREVIEW.md`.

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
- **Règle 13** : n'écris dans le dépôt **aucun nom d'un autre éditeur ou IDE Android**, dans le code, les KDoc, les docs, le `CHANGELOG.md` ou les commits. La maquette jointe est une référence de comportement, pas un texte à copier tel quel dans le dépôt sans relecture.
- Version incrémentée par `scripts/bump-version.sh patch`, `CHANGELOG.md` à jour, tag annoté, `docs/PREVIEW.md` §3.4 mis à jour (lignes PE10a à PE10f).
- Chaîne locale **à la racine** : `./gradlew spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify --stacktrace`. La baseline de parité (`:lsp:kotlin:test -Dparite.majBase=true`) se régénère **en dernier**.
- Ne relance pas de rendu à chaque frappe de curseur ou de champ de recherche : un rendu par édition écrite.

## 6. Critères d'acceptation (à vérifier sur appareil)

1. Sélectionner un `TextView`, `Button`, `EditText`, `TextInputLayout`, `CheckBox`, `Switch`, `ImageView`, `LinearLayout`, `MaterialCardView`, `ProgressBar`, `SeekBar`, `RecyclerView` : l'onglet affiche les sections de l'annexe, en Courants puis en Tous.
2. La recherche de `bias` sur un enfant du ConstraintLayout n'affiche que la section Contraintes ; `zzz` affiche « Aucun attribut ne correspond ».
3. Une couleur invalide ne modifie pas le document et affiche l'erreur ; `#ff0000` écrit `android:textColor="#ff0000"`.
4. Basculer un drapeau écrit `a|b` dans l'ordre du catalogue ; tout désactiver retire l'attribut.
5. ↺ retire l'attribut ; annuler le rétablit ; une édition = une entrée d'historique.
6. `tools:text` écrit `xmlns:tools` à la racine si absent et change l'aperçu.
7. Les corrections rapides du tableau PE10e produisent chacune **une** édition et font disparaître le problème.
8. Un attribut `app:*` modifié porte la puce P3. Vérifier sur chaque attribut Material ajouté si le rendu change, et le noter.
9. Aucun débordement horizontal à 360 dp, thème clair et sombre, police ×1,3 et ×2.
10. `controlePourFormat` : un test par format ; `ValidationAttribut` : un test par règle.

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
