# Previews (XML + Compose) — cadrage de mission (v0.109.0)

> Document de cadrage. Missions démarrées sur « GO » du propriétaire.
> Sources d'étude : dépôt **jjoblab/LayoutLib** v2.1.0 (commit edd84ff, analyse
> directe, projet interne du même auteur — intégration licite) et un IDE
> Android tiers sous GPL-3.0 (enseignements d'architecture uniquement, zéro
> code réutilisé — l'étude comparative est interne, le dépôt n'en cite ni le
> nom ni les sources : règle « projets tiers » d'AGENTS.md).

## 1. Objectif et contexte

Le propriétaire demande deux previews pour l'éditeur :

- **Preview XML** (layout editor) : un rendu fidèle des layouts à côté du
  code, comme l'éditeur visuel d'Android Studio — s'appuyer sur le dépôt
  **jjoblab/LayoutLib**, « pas encore stable à 100 % ».
- **Preview Compose** : intégrer l'**approche interpréteur** (sur
  l'arbre résolu, rendu interactif hors processus) — après stabilisation du
  LSP Kotlin.

Les deux partagent un problème commun : exécuter du code *du projet édité*
dans le processus de l'IDE. La réponse retenue à ce problème (processus
`:preview` dédié + repli in-process) est la colonne vertébrale retenue ici.

## 2. Analyse du dépôt jjoblab/LayoutLib (v2.1.0)

### 2.1 Ce qu'il est

Bibliothèque **mobile-first de rendu de layouts Android**, « inspirée du
layoutlib d'Android Studio mais allégée pour fonctionner on-device » —
l'auteur vise 95 % des cas d'usage avec ~32 000 lignes (vs ~85 000 + 50 Mo de
natives + java.awt pour l'AOSP). Java 11, minSdk 24, compileSdk 34.

| Module | Rôle | Interface principale |
|--------|------|----------------------|
| `module-inflater` (85 cl.) | XML → arbre de vues réelles (réflexion), `RenderService` | `MiniLayoutLib`, `BridgeInflater`, `RenderService` |
| `module-resources` (96 cl.) | `@color/`, `@string/`, `@dimen/`, `@array/` | `ResourceResolverImpl(cheminRes)` |
| `module-drawables` (15 cl.) | `<shape>`, `<selector>`, `<vector>` | `DrawableResolverImpl` |
| `module-themes` (34 cl.) | themes.xml/styles.xml, `?attr/` | `ThemeResolverImpl` |
| `module-attributes` (41 cl.) | `<declare-styleable>`, formats, `app:*` | `AttributeRegistry` |
| `module-layout` (42 cl.) | measure/layout engine | `LayoutEngineImpl.render(root, w, h)` |

868 tests JVM (`testAllModules`) + tests instrumentés + **catalogue de 50
layouts de comparaison** (`validation/`). Métriques cibles annoncées :
inflation 50 éléments < 200 ms, code ajouté < 500 Ko, similarité ≥ 95 %.

### 2.2 Pipeline observé (RenderService, layout-editor-app)

`RenderService` (module-inflater) : debounce **400 ms** ; **validation XML
préalable** par XmlPullParser (XML malformé = frappe en cours → rendu annulé
**silencieusement**, dernier rendu valide conservé — comportement Android
Studio) ; rendu **synchrone sur le thread principal** (⚠️ voir §3.4) ;
re-render défensif si demande pendant un rendu ; `release()` annule le
debounce ; callback `onRenderSuccess(rootView, timeMs, viewCount, w, h)` /
`onRenderError` ; seuil de journalisation rendu lent 32 ms.
`XmlPreprocessor` : retire `xmlns:tools`, convertit `tools:*` → attributs
réels, `marginStart` → `marginLeft` etc. `ViewTagRegistry` : tags courts →
classes natives/design, FQN si point, `ViewFactory` : réflexion avec cache
de constructeurs `(Context)` > `(Context, AttributeSet)` > `()`.
Intégration Gradle documentée : `include` + `projectDir` (settings.gradle.kts
de l'hôte, dossier `LayoutLib/` à la racine).

### 2.3 Limitations résiduelles (« pas stable à 100 % »)

Les READMEs de modules (limitations v1.0, partiellement rattrapées par la
v2.1.0 « tous modules intégrés ») documentent encore :

- qualifiers `w600dp`/`h720dp`/`sw600dp` et qualifiers de **langue**
  (`values-fr/`) non supportés (module-resources) ;
- `?attr/` limité à colors/dimens/strings, pas de résolution vers des
  drawables (module-themes) ;
- `<layer-list>`, `<ripple>`, `<inset>`, `<bitmap>` **détectés mais pas
  créés** ; `StateListDrawable` à enfants et `VectorDrawable` complet
  (pathData) partiels ; pas d'animated-vector (module-drawables) ;
- `applyCustomAttributes()` partiellement stub ; attributs hérités via
  `parent` sur styleables non résolus (module-attributes) ;
- **pas de solver Cassowary pour ConstraintLayout** — le rendu
  ConstraintLayout est donc approximatif (module-layout) ;
- pas de `LayoutTransition`/`ViewTreeObserver`.

Conséquence pour CodeIDE : ces écarts sont **acceptés au cadrage** (rendu
« 95 % »), ils seront tracés dans un état des lieux par layout du catalogue
de validation et priorisés avec l'auteur (le dépôt est vivant, PR #1 déjà
fusionnée). Le panneau preview doit rester **tolérant aux limitations**
(placeholder neutre plutôt que mauvaise guess — philosophie partagée avec
LSP_XML X4).

### 2.4 Compatibilité CodeIDE

- **Langue** : LayoutLib est en **Java** (Javadoc françaises, auteur jo@Dev).
  CodeIDE est « Kotlin 100 % » pour **son** code — une dépendance de
  bibliothèque Java n'est pas du Java écrit à la main : conforme.
- **Stockage** : `ResourceResolverImpl` prend un **chemin de répertoire** —
  nos projets sont en SAF (ADR 0003). Parade : le **FUSE** du projet (montage
  réel déjà utilisé par le LSP Java) expose `res/` comme un chemin POSIX ;
  aucun changement LayoutLib nécessaire.
- **Vraies vues** : LayoutLib inflate de **vraies classes Android** par
  réflexion — c'est l'approche « vraies vues » (pas la simulation AOSP). Un
  crash d'une vue framework est rare mais non nul → §3.4 isolation.
- **Licence** : projet interne jjoblab (même auteur que CodeIDE) : intégration
  licite, notice `THIRD_PARTY_NOTICES.md` à ajouter.

## 3. Preview XML — intégration

### 3.1 Décisions

| # | Décision | Motif |
|---|----------|-------|
| P1 | **LayoutLib est le moteur de rendu XML** de CodeIDE (pas de réécriture, pas d'AOSP layoutlib). | Dépôt de l'auteur, conçu pour, tests fournis. |
| P2 | **Intégration en SOURCE par build composite** (RÉVISÉE v0.112.0, décision propriétaire 2026-10-13 : LayoutLib est un projet personnel **GitHub-only, aucune publication JitPack**) : `settings.gradle.kts` inclut le checkout frère `../LayoutLib` et SUBSTITUE les coordonnées `com.github.jjoblab.LayoutLib:module-inflater` (les 5 modules frères transitent par les `api(project(...))` du build inclus) ; l'épinglage = REF git check out (tag **v2.2.0**, aligné toolchain CodeIDE — la version du catalogue est un pin informatif, la substitution l'ignore) ; la CI clone le tag ; le build échoue avec instructions si le checkout manque. | Évolution découplée des deux dépôts (bump = ref + catalogue) ; chaque dépôt garde son catalogue et ses conventions ; démon Gradle/caches partagés (mêmes Gradle/AGP/JDK). |
| P3 | **Isolation processus `:preview`** : service + AIDL, `DeathRecipient` → « Preview process crashed, retry », **repli in-process** automatique. Le rendu ne tourne **jamais** sur le thread UI de l'IDE. | RenderService rend aujourd'hui sur le thread principal (§2.2) ; une vue peut crasher ou bloquer ; l'IDE doit survivre. |
| P4 | **Rendu bitmap off-screen** : measure/layout (`LayoutEngineImpl`) à la taille device-profile choisie + `view.draw(canvas)` sur un `Bitmap` ; streaming du bitmap (pas de vue attachée à la hiérarchie de l'IDE). | Découple preview de l'UI CodeIDE ; redimensionnement/vivacité ; même forme que le pipeline Compose §4. |
| P5 | **Ressources via FUSE + résolveurs LayoutLib** : `ResourceResolverImpl`/`ThemeResolverImpl`/`DrawableResolverImpl` montés sur le chemin FUSE du projet, thème du projet appliqué. | §2.4 ; zéro adaptation LayoutLib. |
| P6 | **Sélection/inspection** (clic sur une vue du bitmap → sélection dans l'éditeur) : phase 2 — hit-test sur les bounds du layout (les métriques sont dans le callback de rendu). | La parité AS « clic → sélection » est la fonctionnalité qui fait un vrai layout editor. |

### 3.2 Pipeline CodeIDE

```
Éditeur XML (LSP_XML) ──document──▶ Panneau Preview (feature:editor)
   │ debounce (réutilise 400 ms de RenderService)          ▲
   ▼                                                       │ AIDL : bitmap + métriques + erreurs
SessionProjet :preview ── RenderService adapté ──▶ Process :preview
   (FUSE/res → résolveurs LayoutLib)            BridgeInflater → LayoutEngine
                                                → draw(Canvas) → Bitmap
```

Le service `:preview` est démarré paresseusement à la première ouverture
d'un fichier previewable (layout/drawable/values : classification
`previewKindOf`), arrêté par idle-shutdown (registre de
fermeture I7 : la fermeture de projet arrête aussi le processus preview).

### 3.3 Affichage

Un `PreviewPane` (à côté de Code/Blocks) rend le bitmap avec zoom/pan,
checkerboard de transparence, sélecteur de device-profile (taille × densité,
appliquée au `DimensionConverter` LayoutLib), jour/nuit. Les erreurs de rendu
(semantic) remontent en overlay dans le panneau (pas de toast), les XML
malformés gardent le dernier rendu (§2.2, comportement AS).

### 3.4 Ordre des étapes

| Étape | Contenu | Version cible |
|-------|---------|---------------|
| **PE1** ✅ | **Livré v0.111.0** : module `:preview:xml` (moteur LayoutLib — P2 d'alors en dépendance JitPack v2.1.2, **révisée v0.112.0 en intégration source composite, tag v2.2.0, plus de JitPack**) ; « RenderService adapté » (validation XmlPullParser, inflation, LayoutEngine, **dessin bitmap off-screen sur fil dédié** — P3/P4, single-flight par mutex) ; résolveurs du projet par FUSE (`res/`, thèmes jour/nuit — P5) ; `VuePreviewXml` auto-dessinée (zoom/pan/double-tap, damier, bandeau d'erreur, dernier rendu valide conservé) ; classification `previewKindOf` (LAYOUT rendu, DRAWABLE/VALEURS classifiés pour PE3) ; câblage `ControleurPreviewXml` (onglet actif, écoute des frappes, menu « Aperçu XML ») ; **22 tests** (classification, calculs purs, moteur Robolectric — debounce/malformé/erreur/ressources projet/repli natif, vue) ; kit état des lieux 50 layouts documenté (TESTS_MANUELS §Previews — en attente d'appareil). | v0.111.0 |
| **PE2** ✅ | **Livré v0.114.0 (devancée — GO propriétaire)** : isolation `:preview` (P3/P4) — frontière AIDL (`IRenduApercu`/`ResultatRendu` Parcelable, **bitmap compressé en PNG** — plafond Binder ~1 Mo) ; service `ServicePreview` du processus `:preview` (manifest, **bound-only** : délié il meurt seul — AUCUN minuteur interne nécessaire), pipeline extrait dans `RenduLayoutLib` (partagé service + repli, zéro duplication), rendu verrouillé (inflater non thread-safe) ; **couture `TransportRendu`** (publique) : `TransportRenduLocal` (PE1 tel quel) + `TransportRenduProcess` (liaison paresseuse bornée 8 s, appel borné 10 s, **DeathRecipient** → relance à la demande suivante) ; **politique `TransportIsolation`** (transport par défaut) : UNE reprise silencieuse puis **désactivation session** (journal honnête + `regime` observable PROCESSUS_ISOLE/LOCAL_DEGRADE — le rendu courant toujours livré par le repli) ; **idle-shutdown** : déliaison après 60 s sans rendu + fermeture d'espace (I7), BIND_AUTO_CREATE relance ; socle **réutilisable tel quel par la preview Compose** (§4.2.3). 12 tests (service Robolectric PNG/Parcel, politique par faux injecté, transport par couture de liaison + VRAI stub). | v0.114.0 |
| **PE3** ✅ | **Livré v0.122.0** : **sélection/inspection bidirectionnelle (P6)** — tap confirmé sur le rendu → hit-test sur les bounds ABSOLUS du `ViewInfoCollector` → **span source exact sélectionné dans l'éditeur** (remontée à l'ancêtre sélectionnable, Android Studio), sélection de l'éditeur → surlignage de la vue (overlay, élément le plus interne) ; l'alignement (`AligneurElementsXml`) est le miroir EXACT des sémantiques de `BridgeInflater` (une vue par élément producteur, pré-ordre, `<merge>` transparent, `<include>` résolu par le même résolveur — éléments externes non sélectionnables, désalignement honnête au-delà d'un include non résoluble : jamais de span deviné, X4) ; les vues (bounds + spans) voyagent dans le `ResultatRendu` AIDL (`InfoVueRendu`, arbre aplati). **Profils device** (`ProfilPeripherique`, miroir `DeviceProfile.DEFAULTS` : Pixel 4 / 7 Pro / Galaxy S24 — densité et dimensions du PROFIL, barre de réglages, changement = rendu immédiat). **Jour/nuit** (`setNightMode` des deux résolveurs, bascule par bouton, testé par PIXEL). **Drawables/values previewables** (pipeline dispatch PAR GENRE : `DrawableResolverImpl.parse` → dessin intrinsèque ; values → inventaire bitmap-tableau, nuanciers couleurs, racine non `resources` = erreur lisible). Requête paramétrée `RequeteRendu` (Parcelable, AIDL) ; moteur `definirGenre/definirProfil/definirNuit` = rendu immédiat. 27 tests (aligneur, hit-test pur, moteur — nuit par pixel, profils, genres — parcel vues). | v0.122.0 |
| PE4 | **Audit livré v0.122.2** (ADR 0116) puis **montée LayoutLib v2.2.0 → v3.0.0** (v0.125.1) : les 3 limitations prioritaires P0/P1/P2 sont **résolues côté LayoutLib v3.0.0** par l'auteur du dépôt frère —
  - **P0 (layer-list/ripple/inset/bitmap)** : nouveaux parsers `LayerListParser`, `RippleParser`, `InsetParser`, `BitmapParser` + `DrawableXmlFragments` (re-sérialisation des sous-arbres XML inline) + câblage `DrawableResolverImpl.create*` avec résolution récursive. +83 tests JVM. Les fonds complexes (layer-list) et le feedback tactile (ripple) ne produisent plus de trou noir dans la preview.
  - **P1 (qualifiers de langue `values-fr`/`values-en`)** : `ResourceQualifier` étendu (parsing ISO 639 + région ISO 3166, BCP47 toléré), sémantique de repli Android (locale > région > nuit > orientation > API), `ResourceResolver.setLanguage()`/`setLocale()` avec invalidation du cache. +41 tests. La preview peut désormais basculer les chaînes FR/EN.
  - **P2 (ConstraintLayout Cassowary)** : `ConstraintSpec` (instantané immuable des 12 ancres + baseline + biais + pourcentages + ratios + chaînes + poids + marges + guidelines + barriers + match_constraint 0dp) + `ConstraintSolver` réécrit avec le solver Cassowary complet (ancres REQUIRED, biais STRONG, ratios cross-axe, chaînes détectées dans la topologie, barrières en 2 passes, repli `solveLegacy` si le système échoue) + `BridgeInflater` capture les contraintes au START_TAG (bug latent corrigé) + `RenderService` ÉTAPE 3bis `applyConstraintLayouts`. +42 tests (600 total). Le rendu ConstraintLayout n'est plus approximatif.
  - **Limitations restantes** : P3 (`applyCustomAttributes` stub — attributs `app:*`), P4 (adaptive icons), P5 (animated-vector), P6 (LayoutInflater.Factory2, LayoutTransition, ViewTreeObserver) — toutes négligeables en preview statique. Mission **continue** : tout nouveau tag LayoutLib déclenche un nouvel audit + bump éventuel. | continu |
| **PE6-0** ✅ | **Livré v0.134.21** (maquette `docs/mockups/apercu-xml.html` § « Activité dédiée ») : le panneau quitte l'overlay d'`EditorActivity` et vit dans **`PreviewActivity` (`:feature:preview` — nouveau module)**, ouverte par `AppNavigator.openPreview` (patron openTerminal) ; **port `SourceApercu`** (`:core:domain` — texte, remplacement atomique, sélection, annuler/rétablir, écoute de mutation — AUCUN type `cel-*` ne traverse) + **registre singleton `RegistreSourcesApercu`** (Hilt) où `EditorViewModel` publie chaque session ouverte (enregistrement/libération dans `SessionSuivie`, migration d'URI en place au déplacement de fichier) ; retrait du bloc `colonne_apercu` de `activity_editor.xml` (l'éditeur garde sa pleine largeur). **Pièges traités** : mort du processus (registre vide → l'écran se termine et relance l'éditeur, jamais de panneau vide) ; rotation (moteur dans `PreviewViewModel`) ; changement de fichier (menu sélecteur des documents ouverts previewables) ; sens inverse de la sélection (le tap pose le span dans la session — l'éditeur le retrouve, l'overlay intra-activité disparaît). 12 tests (registre pur, adaptateur session — vraies `EditorSession` cel-core, layout Robolectric). | v0.134.21 |
| **PE6a** ✅ | **Livré v0.134.21** (maquette § « Calques », « sans nouvelle donnée ») : calques de `VuePreviewXml` étendus — **cadre de sélection avec poignées d'angle et étiquette « nom · dimensions en dp »** (conversion px → dp par la densité du PROFIL, fonctions pures `CalquesApercu` testées), **parent en pointillés**, **frères en filigrane** (indices depuis `InfoVueRendu.indiceParent` existant), **pastille d'état** (durée du rendu + nombre de vues — données déjà là, jamais affichées : § « Constat ») ; tous les tracés à épaisseur ÉCRAN constante (patron `LARGEUR_TRAIT_SURLIGNAGE / echelle`). Marges/padding/attributs → PE6c (`InfoVueRendu` enrichie), contraintes → PE6e-f. | v0.134.21 |
| **PE6b** ✅ | **Livré v0.134.24** (maquette § « Chrome Material dans la nouvelle activité ») : **puces défilantes** (`HorizontalScrollView` + `MaterialButton` Outlined, miroir `.chips`) — **Appareil ▾** (menu RADIO ancré du catalogue : description `nom · WxHdp · dpi`, l'entrée courante cochée), **Jour/Nuit** (coche), **Langue**, **Calques ▾** (menu à COCHES : parent et frères, marges et padding, contraintes, problèmes, bornes de toutes les vues, grille 8 dp — états relus à l'ouverture) ; **HUD de zoom** (miroir `.zoom`, colonne bas-droite) : + / **100 %** (échelle réelle affichée, mise à jour par `VuePreviewXml.auChangementEchelle`) / ⤢ ajuster / − — expose `VuePreviewXml.zoomer(facteur)` et `ajuster()` ; **catalogue d'appareils étendu** (ProfilPeripherique : + Tablette 10″ 800×1280@320, + Petit écran 320×568@320 — `DeviceProfile` accepte toute combinaison taille × densité, l'extension n'exige pas de miroir LayoutLib). Orientation et police → PE6e-f (données `RequeteRendu`). | v0.134.24 |
| **PE6c** ✅ | **Livré v0.134.24** (maquette § « Données enrichies pour marges, attributs, problèmes et dépôt ») : **`InfoVueRendu` enrichie** — `classe` (simpleName, `MockView` = dégradation G15), `idRes` (nom d'entrée de la ressource), `marges*`/`padding*` (px du rendu), `cliquable`, `conteneur` (ViewGroup), `orientation` (LinearLayout, constantes `ORIENTATION_*`) — **constructeur Parcel et `writeToParcel` dans le MÊME ordre** (avertissement maquette, aller-retour intégral testé y compris taille du tampon lue jusqu'au bout) ; `RenduLayoutLib` : **marche parallèle de l'arbre `View` réel** (pré-ordre strictement identique au `ViewInfoCollector` — cliquable + orientation ; écart → repli défensif, jamais de faux attribut) ; **`ProblemesPreview`** (fonctions pures, testées comme `SelectionPreview`) : règles **classe absente** (ERREUR) et **cible tactile** (AVERTISSEMENT — cliquable < 47,5 dp, conversion par la densité du PROFIL, se tait sans densité) ; **calques consommateurs** dans `VuePreviewXml` : box-model **marges (orange, contour pointillé) / padding (vert)** de la sélection avec pills en dp, **lignes de contrainte** des enfants de la racine (miroir `drawConstraints` — `CalquesApercu.lignesContraintes`, géométrie pure), **pastilles de problèmes** au coin haut-droit (ambre/rouge, compteur multi-problèmes), **grille 8 dp du profil** ; le contrôleur évalue les problèmes à chaque rendu valide. | v0.134.24 |
| **PE6d** ✅ | **Livré v0.134.25** (maquette § « Feuille inférieure : arbre, attributs, problèmes, source ») : **`FeuilleApercuFragment`** (`:feature:preview`) — BottomSheet Material (poignée + fil d'Ariane, 4 onglets, repliée 76 dp / déployée 300 dp, miroir `.sheet`), ALIMENTÉE PAR LE MÊME ÉTAT que la vue (moteur du `PreviewViewModel` partagé) ; **sélection croisée deux voies** (rangée d'arbre, ligne de source, « Voir » → `PreviewViewModel.selectionner(indice)` → span posé dans la session + StateFlow partagé pilote le surlignage ; le contrôleur remonte l'INDICE — `auSelection`) ; **Arbre** (pré-ordre, indentation, badge « ! n » ou dimensions dp), **Attributs** LECTURE SEULE (groupes Identité/Disposition/Mesuré depuis l'[InfoVueRendu] enrichie), **Problèmes** (badge compteur `nProbs`, pastilles ambre/rouge, libellés FR/EN par genre), **Source** (texte VIVANT, lignes numérotées, surlignage du span, clic ligne → vue propriétaire — miroir `ownerOf`, bouton « Ouvrir dans l'éditeur ↗ ») ; `AligneurElementsXml.numeroLigne`/`debutLigne` (offset ↔ ligne partagés), **`FeuilleApercu`** (`:preview:xml`, pures testées : `entreesArbre`/`groupesAttributs`/`entreesProblemes`/`plageSource`/`vueDeLigne`) ; le HUD de zoom remonte au déploiement (miroir `applySheet`). | v0.134.25 |
| **PE7a** ✅ | **Livré v0.134.25** (maquette § « Catalogue et insertion XML, en fonctions pures ») : **`CataloguePalette`** — les 16 widgets de la maquette (6 catégories, extrait XML, dépendance requise Material/RecyclerView, filtres catégorie + recherche) ; **`InsertionXml.inserer(texte, spanParent, index, extrait)`** → UNE édition `(debut, fin, remplacement)` : la position est dérivée en re-marchant le TEXTE vivant (`AligneurElementsXml.aligner` — span périmé / XML malformé / include non résolu → refus `null`, X4), indentation du style EXISTANT des frères, balise auto-fermante convertie en paire ouvrante/fermante, `xmlns:app` ajouté à la RACINE si l'extrait l'utilise (plage étendue — un seul annuler) ; **`renumeroter`** (premier identifiant libre, miroir `nextId`) et **`contraindre`** (contraintes `parent` + marges en dp du point de dépôt par la densité du PROFIL) ; l'édition est testée PAR APPLICATION au texte (re-marche de l'aligneur). | v0.134.25 |
| **PE7b** ✅ | **Livré v0.134.25** (maquette § « Zones de dépôt et glisser-déposer natif ») : **`ZonesDepot`** (`:preview:xml`, pures) — `cibleSous` (conteneur le plus PROFOND + index par milieux dépassés, miroir `targetAt`), `emplacement` (barre d'insertion aux milieux des écarts, marges absorbées à moitié, miroir `slotRect` vertical ET horizontal), libellés des pills ; **`VuePreviewXml`** implémente `OnDragListener` — les drags portant `ZonesDepot.ContenuDepot` sont acceptés (les autres passent au travers) : le moteur de glisser-déposer d'Android livre les événements SANS relais (la palette PE7c lancera `startDragAndDrop` puis se masquera) ; zones Canvas EN DERNIER (conteneurs pointillés violets, conteneur visé plein, barre + halo, pills — miroir `drawDrop`) ; les calques de sélection se masquent au glisser (miroir `!S.dropView`) ; **câblage au dépôt** (`PreviewViewModel.appliquerDepot` : cible → identifiant libre → façonnage ConstraintLayout → édition unique → `SourceApercu.remplacer` → re-rendu par le chemin normal) ; machine d'états interne testée sans `DragEvent`. | v0.134.25 |
| **PE6e-f** ✅ | **Livré v0.134.26** (maquette § chips « Portrait » et « Police ×1,0 ») : **orientation paysage** — puce toggle (`RequeteRendu.paysage`) pilotant le qualifieur `values-land`/`values-port` de l'activité rendue (`ResourceResolverImpl.setLandscape`, API LayoutLib v3.4.7 confirmée), l'état vit dans le `PreviewViewModel` (survit à la rotation) ; **échelle de police du profil utilisateur** — puce cycle ×1,0/×1,3/×1,5/×1,8 (`RequeteRendu.echellePolice`) pilotant le `scaledDensity` du convertisseur LayoutLib (= densité du PROFIL × facteur — le requête porte le FACTEUR, jamais une densité) ; persistance dans `SettingsDataStore`/`AppSettings` (avec la bulle et le mode de palette PE7c) ; sous-titre toolbar « res/layout · rendu en {profil} » (miroir `ttlProfile`) et pastille d'état avec point coloré (miroir `.status .dot` — PE6a fix design) intégrés à la même passe. | v0.134.26 |
| **PE7c** ✅ | **Livré v0.134.26** (maquette § « Bulle, palette et popup de placement ») : **`BulleFlottante`** (`View` custom + `PlacementBulle` pures : aimantation gauche/droite, position normalisée 0..1, `placeBubble`/`bindBubble` miroirs) — le mode et la position PERSISTENT dans `SettingsDataStore` (miroir « son mode et sa position sont enregistrés ») ; **`PalettePopover`** (vue de l'activité, PAS une dialog — exigence PE7b) : en-tête avec puce « Bulle fixe/flottante », mode Glisser-déposer (`startDragAndDrop` natif, tuile maintenue → long-appui ; la palette se masque, miroir `.phone.dragging`) ou Placer par popup, recherche + puces de catégories + RÉCENTS (4, session), grille 3 colonnes de tuiles (icône violette, nom, classe, tag dépendance Material/« dépendance absente » ambre) ; **`PopupPlacement`** + **`PlacementPopup`** (pures : `positionsAutorisees` miroir `normPos`, `cible` miroir `placeTarget`, `resume`, `positionInitiale`) : arbre des vues de référence, positions Avant/Dedans/Après (désactivées hors conteneur/racine), résumé « Dans X, position n/total », bouton « Placer ici » — le repère EN DIRECT réutilise les zones de dépôt PE7b et l'insertion passe par `InsertionXml.inserer` (une seule écriture). | v0.134.26 |
| **PE7d** ✅ | **Livré v0.134.26** (maquette § « Édition d'attributs, annuler, dupliquer, supprimer ») : **barre d'édition flottante** (miroir `.edit` : annuler, rétablir, dupliquer, supprimer — boutons désactivés hors sélection) ; **`EditionAttributsXml`** (`:preview:xml`, pures sur le TEXTE vivant, refus X4 `null`) : `valeurAttribut`/`ecrireAttribut` (remplacement de la VALEUR seule), `ecrireDimension` (wrap/match/fixe, miroir `seg2`), **`dupliquer`** (clône renuméroté [InsertionXml.renumeroter] inséré à la suite — l'élément doit posséder sa ligne) et **`supprimer`** (la ligne entière part avec son retour ; racine se refuse) ; chaque action = UNE édition → `SourceApercu.remplacer` (une seule entrée d'historique) → rendu immédiat (`soumettreImmediat`) ; la sélection SURVIT à l'édition (l'indice de l'arbre aplati ne bouge pas — miroir re-sélection). Généralisée en PE7e (sections par type de vue) : un seul chemin d'édition. | v0.134.26 |
| **PE7e** ✅ | **Livré v0.134.26** (maquette v2 § « Attributs en sections selon le type de vue » — miroir `sectionsFor`) : **`CatalogueAttributs`** (`:preview:xml`, pures, SANS Android) — familles PAR HÉRITAGE (`View` → `TextView` → Button/CheckBox/…, `ImageView` → ImageButton, ViewGroup → LinearLayout/MaterialCardView ; classe inconnue → sections GÉNÉRIQUES seulement), sections PROPRES par classe (Texte/Saisie/Champ/Bouton/État/Image/Conteneur/Progression/Liste), sections DU PARENT (Contraintes lecture seule si racine ConstraintLayout, layout_weight/layout_gravity si LinearLayout), sections GÉNÉRIQUES (Dimensions, marges/padding 4 côtés, Affichage) — titres et étiquettes = clés sémantiques traduites au chrome (aucune chaîne en dur) ; **`EditionAttributsXml.lire`** (attributs du source avec plages) et **`definir`** (remplacement de valeur, ajout à la position CONVENTIONNELLE id→largeur→hauteur→layout_→reste, RETRAIT propre avec séparateur, `xmlns:app`/`xmlns:tools` posés sur la racine dans la MÊME ÉDITION composée, échappement `&<>` `@`/`?` initiaux, `\n` littéral) et **`ecrireCote`** (côté spécifique modifié, sinon RACCOURCI ÉCLATÉ en Start/End/Top/Bottom conservant les autres côtés — miroir `.mrow`) ; **onglet Attributs** : cartes repliables (miroir `.sec2`, badge « n définis », deux premières ouvertes, état mémorisé par titre), contrôles texte/nombre+unité/choix segmenté/tri-état/côtés/contraintes lecture seule, validation à l'IME et à la perte de focus (jamais à la frappe), `@string` en lecture seule, racine éditable pour Disposition/Affichage, refus → toast unique + rechargement du volet ; **34 tests** (23 `EditionAttributsXmlTest` + 11 `CatalogueAttributsTest`, applications au texte vivant). **Écarts documentés vs maquette** : carte = arrondi/élévation SANS orientation/gravity (non applicables à MaterialCardView) ; racine éditable (Disposition/Affichage) là où la maquette masque l'éditeur (prompt PE7e § 5 prime). | v0.134.26 |
| **PE8a** ✅ | **Livré v0.134.28** (le « renommage : à prévoir » laissé par le prompt PE7e § 3) : **`RenommageIdentifiant`** (`:preview:xml`, pures sur le TEXTE vivant) — la déclaration `android:id="@+id/x"` de la vue sélectionnée ET toutes les références `@id/x` du MÊME fichier (contraintes comprises) deviennent le nouveau nom en UNE édition (un seul annuler) ; refus nets X4 (nom invalide aapt2, identique, déjà pris, span périmé, sans id, plateforme `@android:id/…`), frontières respectées (`@id/titre2` jamais emporté pour `titre`) ; **section Identité** en tête de toute fiche (racine comprise) : valeur courante mono + champ « nouveau nom » validé à l'IME/perte de focus ; hors périmètre SIGNALÉ (le code `R.id.x` et les autres fichiers ne sont pas touchés) ; 9 tests par application. | v0.134.28 |
| **PE8b** ✅ | **Livré v0.134.29** (le « lecture seule, édition à prévoir » du prompt PE7e § 3) : **`EditionAttributsXml.lireContraintes`** (les 4 ancres Début/Fin/Haut/Bas — cible `parent`/`@id/x`, TOUTE variante `to…Of` licite, marge dp) + **`ecrireContrainte`** (cible parent/frère/Aucune et/ou marge en UNE édition — xmlns:app posé au besoin, cible+marge empilés, retrait à 0 ; la cible change, le CÔTÉ visé reste ; refus X4) ; la carte Contraintes devient 4 rangées éditables (menu Aucune/parent/frères à id + champ marge validé IME) ; `GenreParent.CONSTRAINT` étend l'édition aux parents ConstraintLayout NON racines. **Corrigé au passage** : corruption latente PE7e (plage d'attribut = nom seul → insertion après ancre et éclatement de raccourci soudaient nom et valeur) — plages sur l'ATTRIBUT COMPLET + 2 tests de régression re-marchés. 12 tests. | v0.134.29 |
| **PE8c** ✅ | **Livré v0.134.30** (le troisième « à prévoir » du prompt PE7e § 3) : **`ExtractionRessource`** (pures) — `extraire` : le littéral de `text`/`hint`/`contentDescription` devient `@string/nom` (via `EditionAttributsXml.definirReference`, SANS l'échappement des littéraux) ; `entreeStrings` : l'entrée `<string name="nom">…</string>` rejoint `res/values/strings.xml` OUVERT du même projet (registre, chemin dérivé `res/<qualifier>/` → `res/values/`), indentation détectée, échappement `&`/`'`/`"`/`\n`, nom déjà pris refusé ; `nomPropose` pré-remplit par pliage du littéral (convention minuscule) ; l'ENTRÉE part d'abord (jamais de référence orpheline), deux documents = deux entrées d'historique ; hors `strings.xml` ouvert → bouton masqué. 7 tests par application. | v0.134.30 |
| **PE9** ✅ | **EN COURS** (série v0.134.33+, retour propriétaire 17-10 : mêmes thèmes/palettes de l'app, popups translucides colorées, volets = fragments + vrais layouts, BottomSheet plus haut + option « compacter l'aperçu », flottants + items d'adapter en layout réel — PE9a→PE9f). PE9a LIVRÉ v0.134.33 : 4 fragments de volet + conteneur unique, onglet actif en flux, RecyclerView arbre/problèmes/source + item layouts réels, badge problèmes tenu par la feuille, StyleVolet/LibellesVolet, 8 tests Robolectric ; le volet Attributs reste LinearLayout + contrôles en code (→ PE9b). Détail d'origine (retour 0.134.30) : le panneau (feuille inférieure : arbre, attributs, problèmes, source + bulle/palette/popup) construit ses vues PROGRAMMATIQUEMENT (`FeuilleApercuFragment` ~1 700 lignes : rangées, cartes, contrôles créés en Kotlin) — le design s'éloigne de la maquette. Étapes prévues : (1) **`fragment_feuille_apercu.xml`** : les 4 volets en layouts XML réels (include réutilisables, styles/material_components) ; (2) **fiches d'attributs** : `item_attribut_*.xml` par type de contrôle (texte, choix segmenté, côtés, contraintes) — le catalogue reste la source de vérité, les VUES viennent des layouts ; (3) **bulle + palette + popup** : `popup_palette.xml`, `popup_placement.xml`, `bulle.xml` ; (4) passage du chrome de la feuille aux tokens exacts de la maquette (audit agent-browser final). La mécanique d'état (ViewModel, éditions au port, tests pures) est CONSERVÉE telle quelle — seuls les sites de construction de vues migrent. | SÉRIE TERMINÉE : PE9a–PE9f livrées v0.134.33→38, CI vertes |
| **PE10** ✅ | **Livré v0.134.39** (retour propriétaire du 17-10 sur l'app livrée : « 5 étapes d'un seul coup » — pastille segmented, chips Material, undo/redo qui se mettent à jour, pastille d'état visible, appbar plus compacte, couches de la maquette) : **PE10a PASTILLE D'ÉTAT RÉELLE** — elle QUITTE le Canvas de `VuePreviewXml` (règle zéro-Material du moteur retrouvée) et devient une VUE de la couche HUD (`pastille_etat` : point `fond_point_pastille` teinté + plurals `preview_pastille_rendu` « Rendu · X ms · N vues · W×H dp »), glisse AVEC la feuille (`majGlissadeFeuille` — miroir `applySheet`, jamais recouverte par le peek) ; **PE10b CHIPS MATERIAL** — les 7 `MaterialButton` Outlined deviennent 6 `Chip` M3 (Assist pour Appareil ▾/Calques ▾, Filter pour Portrait/Nuit/Langue/Police — état « pressed » = conteneur primaire rempli, miroir `aria-pressed`) ; **PE10c UNDO/REDO RÉACTIFS** — le port `SourceApercu` expose `peutAnnuler()/peutRetablir()` (`EditorSession.undoManager.canUndo/canRedo`), le ViewModel publie `historiqueEdition` (StateFlow relu à chaque mutation — undo/redo notifient les écouteurs), `majBarreEdition` pilote les QUATRE boutons (miroir `updateEditBar`) ; **PE10d/e MODE D'AFFICHAGE** — `MaterialButtonToggleGroup` dans la toolbar COMPACTE (52 dp, titre 14 sp, sous-titre 11 sp — l'appbar était « trop grosse ») : `ModeApercu` DESIGN (bitmap), PLAN (squelette pointillé accent des vues rendues sur le damier, miroir `.screen.bp`), LES_DEUX (deux planches côte à côte, écart 40 px, miroir `buildBoards`) — hit tap/drag RE-MAPPÉ sur la planche design (`CalculsVuePreview.coordonneesPlanche`, miroir `toDesign`) ; **PE10 COUCHES** — l'écran rendu « FLOTTE » sur le damier (ombre 3 halos + liseré 1 px à épaisseur écran constante, miroir le box-shadow de `.screen`) ; **SUPPRESSION de l'option « Compacter »** (PE9d retirée à la demande du propriétaire : puce, clé DataStore `preview_sheet_compact`, `ReglagesApercu.compacterApercu`, branche de compaction — la feuille recouvre le rendu comme la maquette). Tests : pile d'annulation (session), re-mappage planche (pures), modes sans lever + gonflage layout (Robolectric). **HOTFIX v0.134.40** (rapport 9774dd61) : NPE sur `setValue` pendant `<init>` — `historiqueInterne` (PE10c) déclarée APRÈS le bloc `init` qui branche la source au constructeur ; la section PE10c remonte AU-DESSUS de l'`init` (contrat d'ordre documenté) + `PreviewViewModelConstructionTest` reproduit le NPE exact sans le correctif et verrouille les deux voies du constructeur. | v0.134.40 |
| **PE11a-b** ✅ | **Livré v0.146.0 / v0.147.0** (maquette v5, `PREVIEW_UI.md` + ADR 0131-0133) : **PE11a jetons et thème** — 39 jetons `apercu_*` clair+nuit (valeurs exactes du `:root`), 37 attributs + `Theme.CodeIDE.Apercu` posé sur `PreviewActivity`, migration `preview_*` (teintes erronées supprimées, alias), `JetonsMaquetteTest` de parité (§ 12.1) ; **PE11b refonte de l'écran Attributs À L'IDENTIQUE** (ADR 0132) — le volet devient un **RecyclerView** (items scellés : titre, barre d'outils épinglée par `DecorationBarreCollante`, cartes de section dont le CORPS n'est gonflé qu'à l'ouverture — état par titre conservé, mécanique ViewModel/ports intacte), les 13 layouts de § 9 copiés de la référence (`carte_section` en-tête 30 dp, `entete_champ` avec pastille d'espace de noms + ↺, contrôles texte/segments Chip/dimensions/côtés 4 champs, lecture seule, kv), poignée VERTICALE + fil d'Ariane + onglets `Widget.CodeIDE.Apercu.Onglets` (indicateur accent 2 dp) + pastille de compte `.tabs .n` en vue personnalisée, `CotesMaquetteTest` (§ 12.2 : gonflage sous le thème, 398 dp, densité 1, cotes vs `meas_clair.json`) ; anciens `fond_*`/`item_cote`/`item_titre_groupe`/`item_libelle` supprimés, AUCUN littéral dans les layouts du périmètre (grille § 12.4). Écart documenté : la maquette force `line-height:1` (boîte 12 px) alors que la boîte de police Android ne descend pas sous son ampleur naturelle (14 px à 12 sp) — tolérance interligne ±3 dp sur `sec2sum`/`fldro`, codée dans le test. | v0.147.0 |
| **PE11c** ✅ | **Livré v0.148.0** (premier lot de nouveaux contrôles, § 9 « un contrôle = un layout = un style ») : **le contrôle LISTE déroulante** (`controle_liste` — miroir `.fld select` : `entete_champ` + `AppCompatAutoCompleteTextView` sans saisie (`inputType none`, chevron `apercu_fleche_deroulant` 16 dp, `popupBackground=fond_menu_apercu`, hauteur min 36 via `apercu_liste_hauteur_min`), options gonflées par `item_option_liste` (corps du champ, sans fond) ; la première entrée « par défaut » RETIRE l'attribut, une valeur courante hors catalogue est ajoutée à la fin (miroir `opts.push(cur)`). **Règle § 9 appliquée au catalogue** : tout enum de PLUS de 4 valeurs porte désormais le kind `ControleAttribut.Liste` — premier usager `android:scaleType` qui passe de Choix (4 valeurs rétrécies) à Liste avec les **8 valeurs plateforme** (SCALE de la maquette : matrix, fitXY, fitStart, fitCenter, fitEnd, center, centerCrop, centerInside) ; 4 valeurs ou moins restent en segments. Tests : parité des enums longs (`CatalogueAttributsTest`), gonflage + chevron/hauteur (`VoletsPreviewLayoutTest`). | v0.148.0 |
| **PE11d** ✅ | **Livré v0.149.0** (2e lot de nouveaux contrôles, § 9) : **le contrôle DRAPEAUX** (`controle_drapeaux` — miroir `.flg` : `entete_champ` + `ChipGroup` multi `GroupeDrapeaux` de `item_drapeau` Chip Filter mono 11 sp / trait 1 / rayon 7 / 25 dp, coché = violet doux ; layouts COPIÉS de la référence, styles/couleurs déjà posés par PE11a). **Sémantique des masques `|`** (pures sur le kind) : cochés = parties de la valeur, drapeau hors catalogue AJOUTÉ à la fin (miroir `opts.concat`), bascule ordonnée selon le CATALOGUE (miroir `opts.filter(…).join('|')`), tout décoché = retrait. **Le composite TYPE DE SAISIE** (`TypeSaisie` — miroir `input`, § 9 `controle_liste` + `controle_drapeaux`) : base en liste (IN_BASE 12 valeurs, « text » défaut sans entrée « par défaut ») + modificateurs en drapeaux greffés sous le champ (IN_MOD 5, glyphe sans préfixe « text »), DÉSACTIVÉS alpha .4 sur base non textuelle ; « text » seul = retrait. **Règle § 9** : tout masque `|` porte le kind `Drapeaux` — convertis `textStyle` (bold|italic, « normal » = état vide), `gravity`/`layout_gravity` (8 drapeaux GRAV), `inputType` (composite) ; ajoutés de la maquette `showDividers` (Conteneur) et `scrollbars` (Liste). Tests : 5 nouveaux parité masques/composite (`CatalogueAttributsTest`), gonflage + groupe multi (`VoletsPreviewLayoutTest`), cote drapeau 25 dp (`CotesMaquetteTest`). | v0.149.0 |
| **PE11i** ✅ | **Livré v0.150.0** (refonte de l'ÉCRAN PRINCIPAL à l'identique — ADR 0134 **accepté**, D8 confirmé par le GO propriétaire) : **barre du haut 52 dp** en `LinearLayout` (retour 36 dp, bloc titre mono 14 + sous-titre, PASTILLE de mode `ChipGroup` avec `setOnCheckedStateChangeListener`, bouton plus → `PopupMenu` — `setSupportActionBar`/`MaterialToolbar` supprimés, inset de barre d'état sur `entete_preview`), puces `Widget.CodeIDE.Apercu.Puce` (pilule 30, trait 1) avec `isActivated` aux menus Appareil/Calques ; **bandeau et toast en VUES** (D6 : `banniere_rendu` — une ligne, « Ligne n » va à l'onglet Source, « Détails » ↔ « Réduire », « Réessayer » en processus arrêté (`reactiverIsolation`), barre d'édition décalée de 64 dp ; `toast_apercu` fondu 200 ms / 2,4 s — plus AUCUN `Toast.makeText` ; `dessinerBandeauErreur` supprimé du moteur, la ligne d'erreur traverse l'AIDL (`ResultatRendu.ligne`)) ; **jetons injectés au moteur** (D7 : `JetonsCalques` pur de 21 entiers + `VuePreviewXml.appliquerJetons` posé par l'activité clair/nuit — TOUTES les constantes de couleur du moteur disparaissent, `JetonsCalquesTest` de parité) ; **calques § 3.5** : damier 8 dp `apercuDamierCase` sur `apercuSurface2`, poignées CARRÉES 9 dp, parent 1,5 dp pointillé à 75 %, frères en bord 35 %, étiquettes RECTANGLE rayon 4 mono 11/500, épingles liserées `apercuSurCalque`, marges/padding aux jetons, LÉGENDE de planche `.cap` « DESIGN · PIXEL 4 » ; **bulle 58 dp** (D4 : halo dans la vue, état OUVERTE `apercu_bulle_fond_ouverte`). `activity_preview.xml` remplacé par la référence + 6 fichiers values `_ecrans` + 8 sélecteurs + 32 drawables copiés (écarts : `chipStrokeColor None` → `@null` ; 3 littéraux → ressources de même valeur). Tests : `CotesEcransMaquetteTest` (7 cotes § 12.1 vs `meas_ecrans_clair.json`), `JetonsCalquesTest` (3), layout/parcel actualisés — 298 tests verts, aucun littéral. **Écarts documentés** : « Ligne n » seulement si le parseur/LayoutLib rapporte la ligne (erreurs sémantiques sans position : à mesurer en PE11h) ; fond racine `?attr/apercuFond` de la référence NON porté (le thème peint déjà `windowBackground` de la même couleur — le repeindre serait un surdessin, règle Overdraw) ; captures § 12.3 au prochain retour propriétaire. | v0.150.0 |
| **PE11j** ✅ | **Livré v0.151.0** (BULLE, PALETTE, GHOST, PLACEMENT et MENUS à l'identique — ADR 0134 D3/D5/D9) : les **15 layouts de référence copiés** (§ 4-6 de la partie II — popover de palette, items tuile/puce/sous-titre/vide/ghost, popup de placement + rangées du mini-arbre, menus appareil/calques/cible + items, bulle) + 5 drawables ; la COQUE vient des styles (`Popover` OPAQUE, `Menu` TRANSLUCIDE — plus aucun fond ni élévation en code) ; **palette** : hauteur `min(600 dp, disponible)` IMPOSÉE par le code (`bornerHauteur`, fonction pure testée — D9), grille 3 colonnes + `ItemDecoration` d'écart 8 à perte ÉGALE par colonne, icônes `ic_tuile_*` reteintées `?attr/apercuNouveau` depuis le layout, puces catégorie `state_checked` ; **placement** : rangées = l'arbre (monogramme/classe/id), référence `isSelected` en accent DOUX (plus de violet en code), positions à `TextView` + opacité .35, résumé à valeurs FORTES (`ResumeFort`), mini-arbre borné 150 et coque 340 ; **menus** : appareil SANS radio (`isSelected` + détail mono « 393×851 dp · 440 dpi »), cible SANS coche, calques à `SwitchCompat` 34 × 20 (D5 — style corrigé : `track` sans préfixe, `android:thumb` préfixé, `minHeight 0`), pleine largeur − 10 dp, 8 dp sous les puces, hauteur 430 ; **« Chrome système / Barre de navigation »** : NOUVELLE option de rendu — `RequeteRendu.barreNavigation` (parcel, défaut ON), `definirBarreNavigation` relance le rendu, et `ChromeSysteme` DESSINE la barre 48 dp à trois glyphes (miroir `.nbar`). **CORRECTIF propriétaire** : la bulle ne réagissait plus après un passage par la palette (`basculerPalette` testait le drapeau de l'enfant au lieu du CONTENEUR posé `GONE` — le tap re-masquait au lieu de rouvrir). Tests : `CotesPopoversMaquetteTest` (6 cotes § 12.1 + hauteur bornée), `ChromeSystemeTest` (3), test chrome actualisé (48 dp retranchés) — littéraux des 15 layouts : VIDE. **Écarts documentés** : libellé « → cible » du ghost MASQUÉ (l'ombre d'un drag Android se fige — la cible en direct reste au moteur) ; pastille 12 dp littérale → `apercu_pastille_menu_taille` ; captures § 12.3 au prochain retour propriétaire. | v0.151.0 |
| **PE11k** ✅ | **Livré v0.152.0** (ONGLETS ARBRE, PROBLÈMES, SOURCE à l'identique — ADR 0134, § 7-9 de la partie II) : les **6 layouts de référence copiés** (`fragment_volet_arbre`/`_problemes`/`_source` + `item_arbre`/`item_probleme`/`item_ligne_source`) + `apercu_probleme_point` ; **arbre** : rangée 34 dp à trois états (sélection `isSelected` → accent doux du sélecteur — la SÉLECTION vit dans les entrées, rebind DiffUtil), monogramme 20 dp, badge « ! n » ambre/texte fond ↔ dimensions neutre/texte doux, MASQUÉ sans contenu, arbre vide = liste vide (miroir `renderTree`) ; **problèmes** : message « Aucun problème détecté… » dans le LAYOUT (`message_aucun_probleme` — plus de vue en code), point 8 dp ambre/erreur, titre 13/600, détail 2 lignes en UNE colonne, « Voir » mono 11 accent ; **source** : en-tête `.openrow` (« Document entier » à la racine — miroir `renderSrc`), « Ouvrir dans l'éditeur ↗ » enfin en `Widget.CodeIDE.Apercu.BoutonLien` (26 dp — le style PE11b était en attente de branchement), ligne 20 dp/numéro 24 dp, COLORATION balise/attribut/valeur (`ColorationSourceXml` pur — machine à états sur le document entier, rôles → spans du thème), TROIS surlignages (racine `isSelected` → accent doux, plage `isActivated` → `apercuSourceVue`, inséré → fond temporaire `apercuSourceInsere` par le flux `plageInseree`, retiré après 2,6 s — LA MAQUETTE GAGNE sur le « ~1,2 s » de la § 9), plage amenée à 70 dp. **4 CORRECTIFS propriétaire** : ① clair/nuit gardé par `RenduNuitTest` (jour ≠ nuit sur un faux `values-night` — le bus était sain, la cause apparente était le bandeau ②) ; ② bandeau « processus arrêté » = miroir EXACT de la maquette (message complet + 3ᵉ phrase « Une reprise automatique a déjà eu lieu. », « Réessayer » SEUL — plus de « Détails »/« Réduire ») et `ServicePreview` attrape tout `Throwable` (une exception résiduelle TUAIT le processus `:preview` — le bandeau revenait à chaque « Réessayer ») ; ③ z-order : l'élévation des CONTENEURS palette/placement (`apercu_popover_elevation` — celle de la coque enfant ne traverse pas la frontière du parent : la barre undo/redo à 4 dp recouvrait l'écran palette) ; ④ HUD zoom + pastille « Rendu · N vues » : translation par le PEEK (`GlissadeFeuilleApercu` — repliée, l'entête 78 dp du bottom sheet les recouvrait au bord). Chrome : `PopoverCalquesApercu` extrait (règle 8), `StyleVolet` dépouillé de ses aides orphelines d'avant-PE11b. Tests : `CotesVoletsMaquetteTest` (7 — rangée 34, monogramme 20, problème ≥ 62 composé, point 8, ligne 20, numéro 24, bouton 26), `ColorationSourceXmlTest` (8), `RenduNuitTest` (2), `GlissadeFeuilleApercuTest` (5) — littéraux des 6 layouts : VIDE. **Écarts documentés** : `xmlns:app` non porté (RedundantNamespace, même écart qu'PE11j) ; fond « inséré » en drawable `apercu_source_insere_fond` (le commentaire de la référence le nomme « posé en code » — ADR 0127-0129 interdit le `GradientDrawable` en code) ; délai inséré 2,6 s de la maquette vs « ~1,2 s » de la § 9. Captures § 12.3 (`ecran-arbre`, `ecran-problemes`, `ecran-source`, clair et nuit) : au prochain retour propriétaire. | v0.152.0 |
| **PE11l** ✅ | **Livré v0.153.0** (RECETTE FINALE de la partie II + les 5 écarts rapportés par le propriétaire) : **① zoom/pastille au lancement** : la translation du HUD et de la pastille n'était posée que par les callbacks du `BottomSheetBehavior` — la pose INITIALE (`majGlissadeFeuille(0f)` au montage, miroir `applySheet` au chargement) règle la feuille repliée ; **② bandeau permanent** : pipeline `RenduLayoutLib` PARESSEUX dans `ServicePreview` (une exception à la construction tuait le processus `:preview` avant le binder → deux échecs de liaison → `LOCAL_DEGRADE` pour la session, bandeau toujours affiché ; désormais l'échec part en DONNÉE, règle 6) + budget de PREMIER rendu après liaison 45 s (le démarrage à froid de LayoutLib sur appareil était converti en panne de transport au-delà des 10 s chaudes ; liaison 8 → 15 s) ; **③ palette remplie dès la construction** (`remplirGrille()` à l'`init`, miroir `fillGrid` — la grille ne vivait que des callbacks) ; **④ rendu PLAN réparé** : la planche bp était décalée de `decalagePlanche()` MÊME SEULE — dessinée hors du cadre du fit (`[W+écart, 2W+écart]`), écran vide ; `CalculsVuePreview.decalagePlancheAffichage` (0 en Plan seul — miroir `buildBoards` `[true]`), testé ; **⑤ REDIMENSIONNEMENT par poignée** (demandé) : `RedimensionApercu` pur (ancre = coin opposé, dp du profil, plancher 8 — miroir `Math.max(8, …)`), machine `traiterRedimension` (DOWN saisit à 18 dp écran de rayon — dérogation ADR 0133 ; MOVE dessine le fantôme pointillé + pill dp en direct ; UP écrit UNE fois si dérivée ; contact seul = rien ; racine exclue ; densité requise), `EditionAttributsXml.ecrireDimensions` groupe les DEUX axes en UNE édition (balise remaniée ↔ balise originale — une entrée d'historique), maquette **v6** resynchronisée avec l'interaction à l'identique. **Recette § 12** : littéraux des layouts VIDE (4 littéraux → ressources : `apercu_reset_cible` 24, `apercu_palette_puce_bulle_marge_fin` 8, `apercu_categories_pad_bas` 2, `apercu_feuille_volets_defaut` 224 ; sentinelles `0dp` hors périmètre) ; cotes pour TOUS les layouts de la partie II (`CotesPlacementMenusMaquetteTest` 6 : puce 28/cat, rangée 32/mini_row, ghost 34, placement padding 12 + fermer 36 + pos_btn 32,5 composé, menus coque 8 + hauteur max 430, sanité cible/messages) ; grille § 12.4 cochée au CHANGELOG ; `JetonsMaquetteTest` (39) + `JetonsCalquesTest` verts ; O1-O5 tranchés au CHANGELOG (confirmation propriétaire attendue). Tests : `RedimensionApercuTest` (7), `VuePreviewXmlTest` +4, `EditionAttributsXmlTest` +4, palette +1. **Écarts datés** : redimensionnement = comportement NOUVEAU voulu (porté par la maquette v6 d'abord) ; budgets de transport 45/15/10 s ; menu cible et messages de palette SANS clé JSON de mesure (sanité — cote portée par le texte, § 12.1). Captures § 12.3 : 16 paires à produire sur appareil (liste au CHANGELOG). | v0.153.0 |

#### 3.4.1 Écarts PE9 vs maquette (retour propriétaire du 17-10, INTENTIONNELS)

- **Popups menus translucides et colorées** (PE9c) : la maquette avait des
  menus système opaques ; le retour propriétaire demande « des popups un
  peu transparent et coloré » — coque `preview_menu_fond` (surface ~95 %
  d'alpha, clair/nuit) + pastilles d'accent sémantiques (canaux tooling,
  erreur, succès, info).
- **Volet déployé 48 % de l'écran** (PE9d) : la maquette fixait 300 px
  (224 dp de volet) ; « le bottomsheet behavior doit expand plus haut » —
  le ratio suit l'écran ; + puce « Compacter » (hors maquette) qui
  rétrécit le viewport au fil de la glissade, réglage persisté.
- **RecyclerView au lieu du LinearLayout** (PE9a) : la maquette re-marchait
  tout le volet à chaque état ; le recyclage supprime la reconstruction
  (famille du crash 0.134.30) — même apparence, DiffUtil sous le capot.
- **Badge problèmes toujours frais** (PE9a) : tenu par la feuille à chaque
  rendu valide (la maquette ne le mettait à jour qu'en visitant un onglet).
- **Ghost du drag violet** (PE9e) : l'ombre par défaut de la tuile devient
  la pill `.ghost` de la maquette (icône + nom).

#### 3.4.2 Écarts PE10 vs maquette (retour propriétaire du 17-10)

- **Chips 6 au lieu de 6+1** : l'option « Compacter » (PE9d, hors
  maquette) est SUPPRIMÉE à la demande du propriétaire — la feuille
  recouvre le rendu au déploiement (miroir `applySheet` : seuls le HUD
  de zoom et la pastille d'état glissent au-dessus d'elle).
- **Squelette Plan sans libellés** : le `.screen.bp` de la maquette
  colore chaque vue en accent ; le mode Plan de l'app dessine les
  contours pointillés accent SANS re-étiqueter chaque vue (le
  surlignage de sélection y reste lisible) — les étiquettes restent sur
  la planche Design (miroir `.cap` « Design · Pixel 4 » implicite).
- **Appbar 52 dp** : la maquette tient sur ~52 px avec des polices
  13/11 px ; l'app réplique avec 14 sp/11 sp (échelle sp — la
  typographie suit la police système de l'utilisateur).

PE1 ne dépend d'aucune autre mission (LSP XML améliorera la complétion mais
le preview consomme le document brut + FUSE). PE2 (livré) a introduit le
socle `:preview` **réutilisé tel quel par la preview Compose** (§4) — c'est
la raison de l'avoir fait proprement dès le départ.

## 4. Preview Compose — intégration de l'approche interpréteur

### 4.1 L'approche retenue (constatée)

- **Pas de compilation** : un **interpréteur** (tree-walking) marche
  l'**arbre résolu** d'un moteur de symboles maison (celui du LSP Kotlin,
  §2 de LSP_KOTLIN) et **émet de vrais nœuds Compose** contre le
  **runtime Compose embarqué** dans l'app.
- **Isolation totale** : le runtime Compose vivant (Composition, Recomposer,
  frame clock, slot table) est **entièrement dans le processus `:preview`**,
  jamais composé dans l'arbre de l'IDE. Rendu off-screen → **streaming
  bitmap** vers l'IDE ; **forwarding des entrées** (pointer) vers la
  composition distante → preview **interactive** (clics, scroll, drag).
- **Live edit** : session persistante par preview ouverte ; diff d'identité
  des fonctions résolues → seules les `@Composable` changées sont
  ré-interprétées ; l'état (`remember`/`mutableStateOf`) survit aux éditions.
- **Garde-fous** (la partie dure) : borne de tempête de recomposition
  (1000/1 s), budget de boucles de l'interpréteur, `LocalInspectionMode`,
  politique de sandbox des hooks d'interprétation, watchdog de gel — un
  `@Composable` utilisateur qui boucle ne doit **jamais** geler l'IDE.
- Statut : Phase 3 faite (interactif hors processus), toggle expérimental
  off par défaut, repli in-process.

### 4.2 Conditions d'intégration à CodeIDE

1. **Prérequis structurel** : le **modèle de symboles/résolveur du LSP
   Kotlin** (LSP_KOTLIN T1, idéalement K3-K4). L'interpréteur consomme un
   arbre **résolu** — c'est ce qui rend l'approche possible sans compiler.
   D'où le séquencement : preview Compose **après** stabilisation du LSP
   Kotlin (conforme à la demande du propriétaire).
2. **Runtime Compose embarqué comme bibliothèque d'interprétation**, pas
   comme UI : ADR 0002 (pas de Compose pour l'UI CodeIDE) reste intact —
   le runtime est une dépendance du pack preview, exactement comme LayoutLib
   l'est du preview XML. Cohérent avec la décision de distribution
   LSP_KOTLIN §6 : le runtime Compose (~quelques Mo) rejoint le futur
   « pack preview » téléchargeable plutôt que l'APK de base.
3. **Socle `:preview` de PE2 réutilisé** : même AIDL, même streaming bitmap,
   même forwarding d'entrées, mêmes garde-fous de cycle de vie (I7).

### 4.3 Décisions

| # | Décision | Motif |
|---|----------|-------|
| PC1 | **Voie interpréteur**, pas voie « compiler+dexer+DexClassLoader par frappe ». | La voie compile coûte D8 par édition (secondes) ; l'interpréteur donne la latence interactive — le rejet du compilateur par frappe fait partie des enseignements de l'étude comparative (même logique). |
| PC2 | Preview Compose = **module séparé** (`:preview:compose` ou pack) ne référençant l'UI CodeIDE par rien d'autre que le streaming. | Isolation des dépendances (runtime Compose), distribution pack. |
| PC3 | **Interactif dès la conception** (entrées + état), mais livré en deux temps : statique d'abord (C1), interactif ensuite (C3). | Le statique valide le rendu ; l'interactif ajoute le forwarding et les garde-fous. |
| PC4 | Garde-fous **obligatoires avant toute activation** : borne de recomposition, budget de boucles, watchdog de gel avec kill du processus `:preview`, `LocalInspectionMode`, sandbox des APIs (réseau/stockage interdits dans la preview). | Un `@Composable` hostile ou simplement bugué ne doit ni geler ni fuir. |

### 4.4 Étapes (après stabilisation LSP Kotlin)

| Étape | Contenu |
|-------|---------|
| C0 | Spike : interpréter un `@Composable` trivial sur l'arbre résolu K3 dans un processus `:preview` jetable ; mesure de latence rendu. |
| C1 | Rendu statique : session persistante, streaming bitmap, live-edit par diff d'identité (état préservé). |
| C2 | Ressources et thème du projet dans la composition distante (réutilise les résolveurs PE1 + index LSP_XML). |
| C3 | Interactif : forwarding des entrées, animations (frame clock réelle), garde-fous PC4 complets. |

## 5. Séquencement global (synthèse)

```
PE1–PE2 (preview XML, indépendant)     ──▶ PE3
LSP Kotlin K1–K5 (LSP_KOTLIN.md)       ──▶ stabilisation
C0–C3 (preview Compose)                ──▶ après stabilisation Kotlin
Distribution « pack » (LSP_KOTLIN §6)  ──▶ après stabilisation Kotlin
```

LSP_XML (XE1–XE4) est orthogonal : il améliore l'édition, partage l'index
ressources avec PE1, mais aucun ordre strict n'est imposé.

## 6. Cibles non fonctionnelles

- Preview XML : rendu complet < 400 ms (fichier ≤ 300 éléments) après
  debounce ; live-typing : le dernier rendu valide reste affiché pendant les
  frappes (jamais de panneau vide).
- Processus `:preview` : mort détectée < 1 s, redémarrage < 2 s, repli
  in-process automatique silencieux (1 reprise, puis désactivation pour la
  session avec message honnête).
- Preview Compose : rendu statique < 500 ms après édition (C1) ; frame
  interactive 30 fps sur device de référence (C3).
- Aucun des deux previews n'ajoute de dépendance au thread UI de l'IDE.

## 7. Risques et parades

| Risque | Parade |
|--------|--------|
| Limitations LayoutLib (§2.3) visibles par l'utilisateur | État des lieux catalogue + placeholders neutres + priorisation avec l'auteur (PE4). |
| `:preview` tué par l'OS (lowmemorykiller) | DeathRecipient + redémarrage paresseux + idle-shutdown pour ne pas le garder vivant inutilement. |
| Interpréteur Compose : profondeur du modèle de symboles insuffisante (génériques/lambdas) | C0 mesure la réalité AVANT tout engagement ; le fallback est une preview partielle (composables triviaux d'abord), jamais un faux rendu. |
| Taille : runtime Compose + interpréteur dans l'APK | Pack téléchargeable (LSP_KOTLIN §6) — le pack « preview » est le même mécanisme. |
| Gel par recomposition infinie | PC4 : bornes + watchdog + kill du processus ; tests de charge dédiés (composable hostile). |

## 8. Traçabilité

- Preview XML : s'appuie sur FUSE (ADR 0003/existant), RegistreFermeture I7
  (CYCLE_DE_VIE), `THIRD_PARTY_NOTICES.md` (LayoutLib à ajouter).
- Preview Compose : dépend de LSP_KOTLIN K3-K4 (résolveur), du socle `:preview`
  PE2, et de la décision de distribution pack (LSP_KOTLIN §6).
- LSP_XML §8 : partage des fixtures 50 layouts et de l'index ressources.
- Complète ROADMAP Phase 6 (fonctionnalités IDE) d'une section « Previews ».
