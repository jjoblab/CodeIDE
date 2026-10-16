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
