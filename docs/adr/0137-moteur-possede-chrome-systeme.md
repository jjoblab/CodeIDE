# ADR 0137 — Le moteur de rendu possède le chrome système

- **Statut** : **accepté** (livré avec LayoutLib 3.5.0 et CodeIDE v0.169.0).
- **Date** : 2026-10-23
- **Décideurs** : jjoblab (nuances D1–D3 déléguées à l'agent le 2026-10-23 — « je te laisse
  décider pour les 3 nuances du chantier chrome » ; tranchées dans le sens recommandé par
  l'étude `docs/PE13-chrome-systeme-moteur-etude.md` §0)
- **Dépend de** : ADR 0134 (écran principal + menu Calques « Chrome système »), ADR 0135
  (correctif C1 — `windowNoTitle`/`windowActionBar`/`windowFullscreen` lus par
  `?android:attr/…`), G5/Phase 3 v0.133.0 (le chrome IDE), v0.166.4/5 (transport bitmap,
  géométrie feuille, glyphes maquette).
  **Frère** : LayoutLib 3.5.0 (module-inflater `bridge/bars` réel, module-themes contrat
  `isNightMode`, module-resources `ViewType`/`ActionBarCallback` réels).

## Contexte

Depuis v0.133.0, le chrome système de l'aperçu (status bar, action bar, barre de
navigation) est construit CÔTÉ IDE : `:preview:xml` possède `ChromeSysteme` (517 l.),
qui enveloppe la racine inflatée dans un `LinearLayout` décor, et `RenduLayoutLib`
compense ensuite l'offset du contenu dans le bitmap par
`decalageContenuDansArbreRendu` (v0.134.2) — un recalage aval, pur hack IDE.
Pendant ce temps, le dépôt frère LayoutLib porte des STUBS morts (`bridge/bars/*` :
`Config`, `StatusBar`, `NavigationBar`, `TitleBar`, `FrameworkActionBar`,
`NavigationHandle` — zéro usager dans les deux dépôts) et des coquilles
(`resources/api/ViewType`, `ActionBarCallback` — nom/valeur sans sémantique).

L'étude PE13 (croisée avec la source AOSP `com.android.layoutlib.bridge`,
android-32 — `RenderSessionImpl`, `Layout.Builder`, `bars/*`, `SystemViewInfo`)
établit que le moteur de référence fait TOUT l'inverse :

1. **la session de rendu crée et possède le décor** (`Layout extends FrameLayout`,
   `mContentRoot` à `android:id/content`) — l'IDE n'écrit pas un pixel de chrome ;
2. **les dimensions viennent des ressources framework** (`status_bar_height`,
   `actionBarSize`), jamais de constantes IDE ;
3. **les présences des barres sont DÉRIVÉES du thème et du profil matériel**
   (`windowFullscreen`, `hasSoftwareButtons()`), pas passées en drapeaux ;
4. **le moteur dispatche les insets** (machinerie `ViewRootImpl` — le décor est
   racine de fenêtre) et gère la translucence (contenu SOUS les barres) ;
5. **la collecte est DUALE** : `getViewInfos()` = contenu seul, bounds absolues
   bitmap ; `getSystemViewInfos()` = décor marqué `ViewType` — l'IDE ne recalcul RIEN.

L'hypothèse du propriétaire — « le chrome doit vivre au niveau de la session de
rendu, le moteur possède le décor, une seule source de vérité des dimensions,
l'hôte ne passe plus que la configuration » — est **confirmée sur toute la ligne**
par l'étude ; le coût (refactor des deux dépôts, tests de rendu à revoir) est
circonscrit : transport bitmap, zoom et géométrie de feuille ne bougent pas.

## Options

- **A. Statu quo — chrome IDE** : `ChromeSysteme` reste dans `:preview:xml`,
  le recalage `decalageContenuDansArbreRendu` reste. Zéro effort, mais deux
  sources de vérité des hauteurs (IDE 24/56/48 vs stubs LayoutLib), des insets
  impossibles à simuler proprement (le contenu ignore les barres), un arbre
  rendu qui compte le chrome dans « rendue • n vues », et les stubs frères qui
  mentent sur l'architecture.
- **B. Chrome moteur avec collecte duale — recommandé** : les vues maquette
  transportées telles quelles dans `LayoutLib` (`bridge/bars`), un `ConfigChrome`
  de session (titre, surcharges, mode de placement), les insets calculés et
  exposés par le moteur, la collecte duale contenu/système, suppression du
  recalage IDE. L'hôte ne construit plus AUCUNE vue chrome.
- **C. Copie intégrale de la référence** : en plus de B, vraie action bar
  AppCompat par réflexion (`WindowDecorActionBar`), vrais menus `menu.xml`,
  machinerie `ViewRootImpl`/`WindowInsets`. Refusé : le rendu jjoblab est
  DÉTACHÉ (mesure `LayoutEngineImpl`, dessin bitmap — pas de fenêtre réelle,
  les invariants de `ViewRootImpl` n'y vivent pas), et l'action bar réelle
  s'écarte de la maquette (écart produit, fragilité réflexive).

## Décision

Option **B**, exécutée selon les 5 étapes sûres de l'étude (§3) ; chaque
artefact rendu est PIXEL-IDENTIQUE à v0.168.0 en régime nominal (garde-fou G2).

- **D1 — Drapeaux de barres = SURCHARGES utilisateur** (nuance 1). Le défaut est
  DÉRIVÉ du thème par le moteur (`windowFullscreen` retire la status bar ;
  `windowNoTitle`/`windowActionBar` retirent l'action bar — correctif C1/ADR
  0135 conservé), miroir du `Layout.Builder` de la référence : la présence des
  barres est une donnée du THÈME, pas une case IDE. `ConfigChrome` ne porte que
  des surcharges NULLABLES (`surchargeStatusBar`, `surchargeNavigationBar`)
  branchées sur le menu Calques existant (`RequeteRendu.barreNavigation`
  devient la surcharge de la barre de navigation) ; la barre de navigation
  garde son défaut ON (miroir `S.nav` de la maquette, la référence n'offre
  simplement pas ce levier — extension assumée, étude §4).
- **D2 — Le drapeau nuit SUPPRIMÉ** (nuance 2). La nuit est le RÉGIME DES
  RÉSOLVEURS, pas un drapeau du simulateur de barres : le contrat
  `ThemeResolver.isNightMode()` est ajouté au frère (l'implémentation existait
  déjà sur `ThemeResolverImpl`), le chrome relit le régime nuit du résolveur.
  Côté CodeIDE, le repli natif (dossier `res/` absent) passe un
  `ThemeResolverImpl` MINIMAL (aucun thème enregistré) portant
  `setNightMode(requete.nuit)` — les couleurs par défaut jour/nuit de la
  planche restent rendues sans dupliquer le drapeau dans le chrome.
- **D3 — Insets EXPLICITES** (nuance 3). Pas de machinerie `ViewRootImpl` (le
  rendu est détaché) : le moteur CALCULE les insets (`InsetsDecor` :
  haut/bas/gauche/droite en px) depuis les hauteurs de ses propres barres et
  les EXPOSE dans le résultat de collecte. Le mode de placement
  `ModePlacementDecor.PADDE_PAR_DEFAUT` pose le contenu sous/au-dessus des
  barres (règles `BELOW`/`ABOVE` de la référence) — comportement nominal,
  pixel-identique ; `ModePlacementDecor.SURIMPRESSION` mesure le contenu
  PLEIN CADRE et laisse les barres par-dessus (l'équivalent déterministe du
  `setDecorFitsSystemWindows(false)` d'Android 15+), disponible pour une
  future surcouche « cutout » ou un document qui padda lui-même.
- **D4 — Une seule source de vérité des hauteurs, garde-fou G1**. Les hauteurs
  vivent DANS le moteur (`DimensChrome`, seam unique) : status bar 24 dp,
  barre de navigation 48 dp, action bar `actionBarSize` du thème si l'attribut
  est déclaré, repli 56 dp — plus AUCUNE constante IDE (les 24/56/48 dupliqués
  de `ChromeSysteme` IDE partent). **G1 documenté** : le repli du moteur de
  référence serait 25 dp (status bar) — nous gardons 24 dp = maquette
  `apercu-xml.html` (`.sbar`) ; tout écart futur (dossier res framework
  embarqué) se tranchera avec le propriétaire, jamais silencieusement.
- **D5 — Collecte duale, suppression du recalage — garde-fous G3/R3**.
  `ViewInfoCollector.collectDecor(decor, racineContenu)` produit
  `CollecteDecor` : infos CONTENU translatées dans l'espace bitmap (l'offset
  du décor appliqué à la collecte — l'invariant v0.134.2 « draw, hit-test et
  surlignage partagent le même espace de coordonnées » devient une propriété
  DU MOTEUR) et infos SYSTÈME (`SystemViewInfo` + `ViewType` réel —
  `BARRE_STATUT`, `BARRE_NAVIGATION`, `ACTION_BAR`…, miroir du marquage AOSP
  des menus/bouton de débordement). `decalageContenuDansArbreRendu` et sa
  documentation sont SUPPRIMÉS ; le contrat `InfoVueRendu` (bounds absolues
  bitmap) ne bouge pas d'un pixel et la frontière AIDL ne change pas (G3).
  Seul `ResultatRendu.nombreVues` change de SÉMANTIQUE : il compte le
  CONTENU SEUL (la pastille « rendue • n vues » parle des vues de
  l'utilisateur, pas du chrome) — écrit noir sur blanc au CHANGELOG.
- **D6 — Apparence : maquette d'abord, config ensuite** (étape 5, écarts
  assumés de l'étude §4). Les glyphes maquette restent le contenu des barres
  (« 9:41 / 5G ▮▮▮ 100% », trois glyphes `#CFCFD6`) ; `LisibiliteChrome`
  (luminance Rec. 709) décide de la couleur du contenu et HONORE en plus
  `windowLightStatusBar` quand le thème le déclare (la référence v32 n'a pas
  cet attribut — amélioration). Le mode GESTUEL de la barre de navigation
  (`NavigationHandle`, pilule 48×4 dp) et la flèche retour + menu de
  débordement de l'action bar (`ActionBarCallback.homeAsUp`/
  `popupDebordement`, sémantique réelle de la coquille) deviennent des
  CAPACITÉS moteur pilotées par `ConfigChrome`, aux DÉFAUTS MAQUETTE
  (trois boutons, pas de flèche, pas de menu — la maquette ne les montre
  pas) : aucune UI CodeIDE n'est ajoutée hors planche ; le branchement
  d'un réglage reste un chantier séparé si le propriétaire le demande.

## Conséquences

- **LayoutLib 3.5.0** : `bridge/bars` devient réel (`StatusBar`/`NavigationBar`/
  `FrameworkActionBar`/`NavigationHandle` = vues glyphées, `ChromeSysteme` =
  factory du décor, `ConfigChrome`, `InsetsDecor`, `DimensChrome`,
  `LisibiliteChrome` pur) ; stubs `Config`/`TitleBar` supprimés (code mort
  vérifié) ; `ViewType`/`ActionBarCallback` (module-resources) et
  `SystemViewInfo` (bridge/impl) prennent leur sémantique AOSP ; le contrat
  `ThemeResolver.isNightMode()` est ajouté (l'impl existait). Les tests JVM du
  frère couvrent la PARTIE PURE (lisibilité, dérivation des barres, insets,
  hauteurs) ; l'assemblage des vues reste couvert par les tests Robolectric de
  CodeIDE (le frère n'embarque pas Robolectric, philosophie inchangée).
- **CodeIDE v0.169.0** : `:preview:xml` ne construit plus AUCUNE vue chrome —
  `ChromeSysteme.kt` (factory + glyphes + lisibilité) part ENTIER, le pipeline
  passe un `ConfigChrome` au frère ; `decalageContenuDansArbreRendu` part ;
  le repli natif porte le régime nuit par résolveur minimal (D2) ;
  `ChromeSystemeTest` teste l'API du frère (noms `StatusBar`/`NavigationBar`).
  La pastille « rendue • n vues » change de valeur (contenu seul) — CHANGELOG.
- **Risques couverts** : R1 (deux dépôts) par la séquence éprouvée frère-d'abord
  (3.5.0 tag → publication CI → pin catalogue 3.5.0 → 0.169.0) ; R2/R3 (tests,
  sélection) par la reprise pixel des tests chrome existants — les assertions
  24+56 dp @ densité profil, 48 dp de barre de navigation et l'alignement
  sélection (P6/X4) doivent rester VERTES SANS modification des valeurs
  attendues, seule la construction du chrome change de main ; R4 (edge-to-edge
  Android 15+) absorbé par `ModePlacementDecor.SURIMPRESSION` (D3).
- **Suivis** : l'arbre de composants pourra afficher plus tard une section
  « système » depuis les infos système marquées `ViewType` (miroir de
  l'usage AOSP exact) ; une section Calques « mode gestuel » reste un
  chantier séparé (hors maquette v6).
