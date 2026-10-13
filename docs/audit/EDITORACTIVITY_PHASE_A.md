# Phase A — Prompt 1 : EditorActivity bottom sheet, clavier, barre de navigation

> **Document de phase A** (prompt 1 du 2026-10-13 — « EditorActivity
> bottom sheet, clavier, barre de navigation »). Ce document vérifie
> chacun des constats du §2 du prompt par lecture directe du code de
> CodeIDE au commit actuel (`HEAD` à la rédaction, v0.124.0). **Aucun
> code de production n'est modifié en phase A** : les correctifs (étape
> B, exigences E1-E7) sont proposés mais non implémentés dans cette
> session — ils seront livrés en phase B après le GO du propriétaire.
>
> **Règle absolue** : aucune mention de la référence privée dans aucun
> livrable. Les principes observés dans la bibliothèque externe servent
> de lecture, jamais d'attribution.

## 0. Périmètre et contraintes de l'audit

Le prompt 1 originellement rédigé pour v0.115.0 (commit `590e60b`).
Le dépôt a progressé depuis (v0.124.0 à la rédaction) ; les ADR et
CHANGELOG ont été enrichis. Cet audit vérifie donc l'état courant du
code (v0.124.0) — les constats qui auraient déjà été rattrapés entre
v0.115.0 et v0.124.0 sont infirmés avec preuve.

L'audit a été mené sans accès aux sources de Material 1.14.0 (pas
de cache Gradle disponible dans l'environnement d'audit). Les comportements
de `BottomSheetBehavior` qui ne peuvent PAS être vérifiés par lecture du
code CodeIDE seul sont tracés comme « à vérifier dans Material » et
relevés pour la phase B.

## 1. Constats vérifiés — tableau §2 du prompt

### Constat #1 — Éditeur partiellement masqué

**Prompt** : « Le sheet se pose PAR-DESSUS `zone_centrale`. La seule
protection est un `padding bottom = peekHeight` posé sur `zone_centrale`
(`appliquerReservePanneau`). Ce padding n'ajoute jamais l'inset de la
barre de navigation, et le peek est dynamique (`majPeekPanneau` : 48 dp,
ou ligne tooling + progression + onglets selon l'onglet) alors que la
réserve n'est recalée que sur callbacks d'état et `surPeekChange`. En
mi-hauteur/étendu, le sheet recouvre volontairement le bas de l'éditeur
(décision v0.80.2) sans que le curseur soit gardé visible. »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `feature/editor/src/main/kotlin/jo/codeide/feature/editor/EditorActivity.kt:1123-1126`
  `appliquerReservePanneau` :
  ```kotlin
  internal fun appliquerReservePanneau() {
      if (clavierVisible) return
      liaison.zoneCentrale.updatePadding(bottom = comportementPanneau.peekHeight)
  }
  ```
  Le padding ne prend QUE `peekHeight` — aucun inset de barre de
  navigation n'est ajouté.
- `feature/editor/src/main/kotlin/jo/codeide/feature/editor/PanneauToolingController.kt:346-361`
  `majPeekPanneau` : le peek est calculé en fonction de `ongletCourant`
  (CONSOLE vs Problèmes/Journal) et de `ligneActivee` (ligne tooling +
  progression si visible). Donc peek **dynamique** confirmé.
- `EditorActivity.kt:1223` branche `surPeekChange = { appliquerReservePanneau() }`
  sur le contrôleur tooling — donc la réserve est recalée à chaque
  changement de peek.
- `EditorActivity.kt:1234-1263` ajoute un `BottomSheetCallback` qui
  appelle `appliquerReservePanneau()` sur `onSlide` et `onStateChanged`.
  Donc les transitions de sheet recalculent la réserve.
- ADR 0056 (v0.80.2) — la décision « sheet recouvre le bas en
  mi-hauteur/étendu, l'éditeur ne change pas de taille » est documentée
  dans `appliquerReservePanneau` (commentaire `v0.80.2 (retour
  utilisateur, bug de l'état vide)`).
- **Le curseur n'est PAS gardé visible** en mi-hauteur/étendu : aucun
  appel à `EditorView.ensureCursorVisible()` ou équivalent n'est fait
  dans `appliquerReservePanneau` ou dans les callbacks du sheet.

### Constat #1b — Désalignements pendant le glissement (scaleX/scaleY)

**Prompt** : « `appliquerEchelleSheet` (`ECHELLE_SHEET_ETENDU = 0.87f`)
met `scaleX/scaleY` sur `vue_editeur` avec couche matérielle : l'échelle
part du centre de la vue, les popups et poignées de sélection de la
bibliothèque ne suivent pas, et la zone visible ne correspond plus à la
réserve calculée. »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `EditorActivity.kt:1135-1148` `appliquerEchelleSheet` :
  ```kotlin
  private fun appliquerEchelleSheet(glissement: Float) {
      val editeur = liaison.vueEditeur ?: return
      if (glissement <= 0f) {
          editeur.scaleX = 1f; editeur.scaleY = 1f
          editeur.setLayerType(View.LAYER_TYPE_NONE, null)
          return
      }
      val echelleMin = ECHELLE_SHEET_ETENDU  // = 0.87f
      val echelle = 1f - (1f - echelleMin) * glissement
      editeur.scaleX = echelle
      editeur.scaleY = echelle
      editeur.setLayerType(View.LAYER_TYPE_HARDWARE, null)
  }
  ```
  `scaleX`/`scaleY` sont appliqués sur `vue_editeur` directement, avec
  `LAYER_TYPE_HARDWARE`. Le pivot par défaut d'une vue Android est son
  CENTRE (50 %, 50 %) — l'échelle part du centre, pas du coin haut-gauche.
  Les popups et poignées de sélection de la bibliothèque `code-editor`
  vivent dans leur propre hiérarchie (windows séparés via
  `WindowManager` ou attachés à la vue parent), ils ne reçoivent pas
  la transformation — confirmation à valider en phase B contre les
  sources de la bibliothèque `code-editor` (mais le comportement
  décrit est cohérent avec l'API Android `View.scaleX`/`scaleY`).
- `EditorActivity.kt:1292` `appliquerEchelleSheet(glissement)` est
  appelé dans le `onSlide` du `BottomSheetCallback`.
- `EditorActivity.kt:2615` — recherche du `ECHELLE_SHEET_ETENDU` :
  la constante est `0.87f`. La zone visible ne correspond PLUS à la
  réserve calculée (qui reste `peekHeight` constant) — l'éditeur
  apparaît visuellement plus petit mais le padding bottom reste
  identique.

### Constat #2 — Touches qui traversent

**Prompt** : « `panneau_inferieur` n'est ni `clickable` ni `focusable`,
et la plupart des racines de fragments du panneau non plus. Une touche
tombant sur une zone du sheet qu'aucun enfant ne consomme (en-tête,
espaces, contenu non cliquable) fait renvoyer `false` au sheet :
`CoordinatorLayout` passe alors au frère suivant, `zone_centrale`, donc
à l'`EditorView` (curseur déplacé, focus, clavier ouvert). »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `feature/editor/src/main/res/layout/activity_editor.xml:358-369`
  `panneau_inferieur` :
  ```xml
  <LinearLayout
      android:id="@+id/panneau_inferieur"
      android:layout_width="match_parent"
      android:layout_height="match_parent"
      android:background="@drawable/fond_panneau_inferieur"
      android:elevation="8dp"
      android:orientation="vertical"
      app:behavior_fitToContents="false"
      app:behavior_halfExpandedRatio="0.5"
      app:behavior_peekHeight="@dimen/editor_panneau_replie"
      app:behavior_skipCollapsed="false"
      app:layout_behavior="com.google.android.material.bottomsheet.BottomSheetBehavior">
  ```
  Aucun `android:clickable="true"` ni `android:focusable="true"` n'est
  posé sur `panneau_inferieur`. La vue n'est pas marquée consommatrice
  de touches.
- Le `BottomSheetBehavior` de Material 1.14.0 ne pose pas `clickable`
  par défaut sur la vue à laquelle il s'attache (vérification à faire
  dans les sources Material en phase B). Le comportement standard du
  `CoordinatorLayout` est de passer au frère suivant si une touche
  n'est pas consommée — donc le risque décrit dans le prompt est réel.
- Les fragments enfants (Console, Problèmes, Journal) sont attachés à
  un `FragmentContainerView` non vérifié dans cet audit. À confirmer
  en phase B : les racines de fragments ne portent pas non plus de
  `clickable`/`focusable`.

### Constat #3 — Bug avec le clavier

**Prompt** : « `appliquerClavier` bascule `panneau.isVisible = false/true`
à chaque changement d'état du clavier puis appelle `restaurerEtatPanneau`
qui réécrit `comportementPanneau.state` en plein relayout. Deux
détecteurs (insets IME + rétrécissement du root) et un
`WindowInsetsAnimationCompat.Callback` écrivent le même état ;
`clavierParInsets = ime > 0` reste vrai jusqu'à la dernière trame de
fermeture, donc le sheet réapparaît d'un coup à la fin. Enchaînement
probable avec #2 : toucher le vide du sheet donne le focus à l'éditeur,
le clavier s'ouvre, le sheet disparaît. »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `EditorActivity.kt:1085-1109` `appliquerClavier` :
  ```kotlin
  private fun appliquerClavier() {
      val clavier = clavierVisible
      ...
      val panneau = liaison.panneauInferieur
      if (clavier) {
          if (panneau.isVisible) panneau.isVisible = false
      } else if (!panneau.isVisible) {
          panneau.isVisible = true
          restaurerEtatPanneau()  // <-- écrit comportementPanneau.state
      }
  }
  ```
  Donc `panneau.isVisible` est basculé à chaque changement, et
  `restaurerEtatPanneau` écrit `comportementPanneau.state` (cf. L1161).
- `EditorActivity.kt:1152-1163` `restaurerEtatPanneau` :
  ```kotlin
  private fun restaurerEtatPanneau() {
      if (clavierVisible) return
      val cible = when (viewModel.etat.value.etatPanneau) {
          EtatPanneau.REPLIE -> STATE_COLLAPSED
          EtatPanneau.MI_HAUTEUR -> STATE_HALF_EXPANDED
          EtatPanneau.ETENDU -> STATE_EXPANDED
      }
      if (comportementPanneau.state != cible) {
          comportementPanneau.state = cible  // <-- écriture pendant le relayout
      }
  }
  ```
  L'écriture de `state` pendant un relayout est documentée comme
  problématique par le prompt (peut causer des sauts visuels, des
  re-layouts en chaîne).
- `EditorActivity.kt:1032-1078` `observerClavier` :
  - Trois sources d'état clavier : (1) `setOnApplyWindowInsetsListener`
    lit `ime > 0` et bascule `clavierParInsets`, (2)
    `addOnGlobalLayoutListener` compare `hauteurRacineMax - hauteur` à
    un seuil et bascule `clavierParHauteur`, (3)
    `setWindowInsetsAnimationCallback` avec `onProgress` reçoit les
    valeurs intermédiaires pendant l'animation.
  - Les trois peuvent appeler `appliquerClavier()` indépendamment —
    risque d'écritures concurrentes de l'état.
- `EditorActivity.kt:1035` `clavierParInsets = ime > 0` — reste vrai
  jusqu'à la dernière trame de fermeture (où `ime == 0`). Donc
  `clavierVisible` reste vrai pendant toute la fermeture, le sheet ne
  réapparaît qu'à la fin — confirmation exacte du constat du prompt.

### Constat #3b — Mode de redimensionnement non défini

**Prompt** : « L'entrée `EditorActivity` du manifeste de `feature/editor`
n'a ni `windowSoftInputMode` ni thème ; seule `MainActivity` déclare
`adjustResize`. »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `feature/editor/src/main/AndroidManifest.xml:19-22` :
  ```xml
  <activity
      android:name="jo.codeide.feature.editor.EditorActivity"
      android:exported="false"
      android:label="@string/editor_title" />
  ```
  Aucun `android:windowSoftInputMode`, aucun `android:theme`. La valeur
  par défaut du système (`SOFT_INPUT_STATE_UNSPECIFIED` +
  `SOFT_INPUT_ADJUST_RESIZE` selon la cible) s'applique — mais le code
  de `observerClavier` documente lui-même que les insets IME sont
  silencieux sur certains appareils, d'où le détecteur de hauteur de
  secours.
- `app/src/main/AndroidManifest.xml:87` : `MainActivity` déclare
  `android:windowSoftInputMode="adjustResize"` — mais cette valeur
  n'est pas héritée par `EditorActivity` (qui est dans un autre module
  et un autre manifeste fusionné).

### Constat #4 — Barre de navigation

**Prompt** : « `brancherInsets` ne traite que la toolbar (haut) et la
marge basse du tiroir. Aucun code de `feature/editor` ne consomme
`systemBars().bottom` pour la colonne centrale ni pour le sheet :
l'en-tête replié (48 dp) se retrouve sous la barre de navigation ou la
pilule de gestes, hors de portée du doigt. `enableEdgeToEdge()` est
appelé sans style explicite, `gestureInsetBottomIgnored` n'est jamais
réglé, et rien ne règle `navigationBarColor`, le contraste forcé ni
`windowLightNavigationBar` pour cette activité. Le fond opaque du sheet
ne descend pas jusqu'au bord de l'écran derrière la barre transparente. »

**Vérification au code v0.124.0** : **CONFIRMÉ**.

- `EditorActivity.kt:409-412` `brancherInsets` :
  ```kotlin
  private fun brancherInsets() {
      liaison.toolbarEditeur.applySystemBarsInsets(top = true, bottom = false)
      liaison.tiroir.applySystemBarsInsetsMargins()
  }
  ```
  Seuls `toolbarEditeur` (top) et `tiroir` (marges) reçoivent les
  insets. Aucun `bottom = true` sur la toolbar, aucun inset consommé
  pour `zoneCentrale` ni pour `panneauInferieur`.
- `EditorActivity.kt:291` `enableEdgeToEdge()` — appelé sans argument
  `SystemBarStyle` explicite (utilise les valeurs par défaut).
- `EditorActivity.kt:197` `comportementPanneau` déclaré,
  `gestureInsetBottomIgnored` n'est jamais réglé (vérification par
  recherche sur tout `feature/editor`).
- Aucune référence à `navigationBarColor`, `windowLightNavigationBar`
  ou `isNavigationBarContrastEnforced` dans `feature/editor` (recherche
  par grep).
- Le fond opaque du sheet est `@drawable/fond_panneau_inferieur`
  (vérification du drawable non faite dans cet audit, mais le
  comportement décrit — sheet ne descendant pas jusqu'au bord bas de
  l'écran derrière la barre transparente — est cohérent avec
  `brancherInsets` qui ne consomme pas `systemBars().bottom`).

## 2. Constats liés à TiroirPoussantLayout et calque poignée

**Prompt §2** : « `TiroirPoussantLayout` et le calque poignée n'ont pas
été identifiés comme causes : vérifie-le aussi. »

**Vérification au code v0.124.0** : **INFIRMÉ pour les symptômes #1 à #4**.

- `TiroirPoussantLayout` est un `DrawerLayout` custom (v0.80.3, ADR
  0100) : il pousse le contenu au lieu de le recouvrir (transparency
  scrim). Il n'interagit PAS avec `BottomSheetBehavior` — le sheet vit
  DANS `zone_centrale` (le contenu), pas dans le tiroir.
- Le calque poignée (id `conteneur_poignee`, DERNIER enfant du
  `FrameLayout` racine) vit HORS du `DrawerLayout` (v0.80.3, ADR 0100)
  pour éviter que `DrawerLayout.onInterceptTouchEvent` ne bloque les
  touches du tiroir. Ce calque ne reçoit QUE les touches destinées à
  la poignée — il n'affecte pas le bottom sheet.
- **Conclusion** : `TiroirPoussantLayout` et le calque poignée ne sont
  PAS des causes des symptômes #1 à #4. Le prompt avait raison de
  demander la vérification — confirmée par lecture.

## 3. Synthèse : 5 constats confirmés sur 5 (+ 1 infirmé)

| # | Constat | Statut | Preuve |
| --- | --- | --- | --- |
| #1 | Éditeur partiellement masqué | **Confirmé** | `EditorActivity.kt:1123-1126`, `PanneauToolingController.kt:346-361` |
| #1b | Désalignements pendant le glissement (scaleX/scaleY) | **Confirmé** | `EditorActivity.kt:1135-1148`, `EditorActivity.kt:1292` |
| #2 | Touches qui traversent | **Confirmé** | `activity_editor.xml:358-369` (pas de `clickable`) |
| #3 | Bug avec le clavier | **Confirmé** | `EditorActivity.kt:1085-1109, 1032-1078, 1152-1163` |
| #3b | Mode de redimensionnement non défini | **Confirmé** | `feature/editor/src/main/AndroidManifest.xml:19-22` |
| #4 | Barre de navigation | **Confirmé** | `EditorActivity.kt:409-412, 291`, aucun `gestureInsetBottomIgnored` ni `navigationBarColor` |
| TiroirPoussantLayout / poignée | Causes à vérifier | **Infirmé** (pas causes) | `TiroirPoussantLayout` et calque poignée vivent hors de la zone sheet — pas d'interaction |

## 4. Exigences E1-E7 — plan de correctif proposé (NON implémenté en phase A)

> **Avertissement** : ce qui suit est le plan de correctif. Aucune ligne
> de code n'est modifiée dans cette session. La phase B (implémentation)
> nécessite :
> - un environnement de build Android (Android SDK + Gradle) non
>   disponible dans cette session d'audit ;
> - une chaîne de tests Robolectric pour valider chaque exigence ;
> - plusieurs cycles CI (20-30 min chacun) pour rattraper les erreurs
>   de compilation et de lint.
>
> Chaque exigence est décortiquée pour préparer la phase B.

### E1 — Un propriétaire des insets

**Plan** : extraire d'`EditorActivity` une classe dédiée
`GestionnaireInsetsEditeur` (patron de `PanneauToolingController` —
l'activité fait 2686 lignes à v0.124.0). Reçoit les insets une seule
fois et pilote : padding de `zone_centrale`, réserve sous l'éditeur,
padding interne du sheet. Le calcul de la réserve est une fonction
pure (entrées : peek, inset de la barre de navigation, inset IME, état
du sheet ; sortie : réserve basse), couverte par des tests unitaires.

**Fichiers touchés** : nouveau
`feature/editor/src/main/kotlin/jo/codeide/feature/editor/GestionnaireInsetsEditeur.kt`,
refactor de `EditorActivity.kt` (retrait de `appliquerReservePanneau`,
`brancherInsets`, `observerClavier`, `appliquerClavier` qui migrent).

**Risques** : refactor d'une activité de 2686 lignes — découpage prudent,
tests Robolectric à ajouter en parallèle.

### E2 — Barre de navigation

**Plan** :
- Étendre `brancherInsets` pour consommer `systemBars().bottom` et
  `systemBars().left`/`right` (paysage).
- Poser `gestureInsetBottomIgnored` sur le `BottomSheetBehavior` si
  la lecture de Material 1.14.0 le justifie (à vérifier).
- Faire descendre le fond opaque du sheet jusqu'au bord bas de l'écran
  (padding bas du sheet = inset navigation, OU `peekHeight += inset`).
- `enableEdgeToEdge(SystemBarStyle.light/dark(...), SystemBarStyle.light/dark(...))`
  avec style explicite selon le thème jour/nuit.
- Désactiver le contraste forcé (`isNavigationBarContrastEnforced`, API 29+)
  si le sheet gère lui-même son fond.

**Fichiers touchés** : `EditorActivity.kt:291, 409-412`, `activity_editor.xml`
(ajout `android:fitsSystemWindows="false"` sur la racine si nécessaire,
test du drawable `fond_panneau_inferieur`).

### E3 — Réserve sous l'éditeur

**Plan** :
- Sheet replié : la réserve = `peekHeight + insetNavigation` (et non
  juste `peekHeight`). Recalcul à chaque changement de peek.
- Mi-hauteur/étendu : conserver la décision v0.80.2 (zone d'édition de
  taille constante). Pour garder le curseur visible : appeler
  `EditorView.ensureCursorVisible()` (méthode à vérifier dans la
  bibliothèque `code-editor` 3.69.0) après chaque `onStateChanged` en
  mi-hauteur/étendu.

**Fichiers touchés** : `GestionnaireInsetsEditeur.kt` (E1),
`EditorActivity.kt` (callback `onStateChanged`).

### E4 — Touches

**Plan** :
- Poser `android:clickable="true"` et `android:focusable="true"` sur
  `panneau_inferieur` dans `activity_editor.xml`.
- Vérifier les racines de fragments (Console, Problèmes, Journal) :
  si elles ne sont pas `clickable`, les marquer.
- Vérifier `BottomSheetBehavior.findScrollingChild` quand le contenu
  est un `FragmentContainerView` dont les fragments sont
  montrés/cachés (risque d'enfant périmé — l'enfant qui défile
  imbriqué peut ne plus être trouvé après un `show`/`hide`). Si c'est
  le cas, surcharger `findScrollingChild` ou utiliser
  `setNestedScrollingEnabled(true)` sur les `RecyclerView` enfants.

**Fichiers touchés** : `activity_editor.xml:358-369`, layouts de
fragments (à identifier), `EditorActivity.kt`
(éventuelle surcharge de `BottomSheetBehavior`).

### E5 — Clavier

**Plan** : évaluer les pistes (a) sheet reste ancré et clavier le
recouvre, (b) `translationY` ou `STATE_HIDDEN` piloté par la
progression IME, (c) autre. Recommandation préliminaire de l'audit :
**piste (b)** — `translationY` piloté par `onProgress` de
`WindowInsetsAnimationCompat.Callback`, SANS basculer `isVisible` ni
écrire `state` pendant l'animation. Le sheet glisse vers le bas
pendant l'ouverture du clavier, remonte pendant la fermeture —
animation continue, pas de saut. État du sheet (replié/mi-hauteur/
étendu) préservé à l'identique après le cycle.

**Fichiers touchés** : `GestionnaireInsetsEditeur.kt` (E1),
`EditorActivity.kt:1085-1109, 1032-1078` (refactor de `appliquerClavier`
et `observerClavier`).

**À valider en phase B** : la piste (b) peut entrer en conflit avec
`scaleX`/`scaleY` (E1b). Décision à prendre en ADR dédié.

### E6 — Thème et manifeste

**Plan** :
- `feature/editor/src/main/AndroidManifest.xml:19-22` : ajouter
  `android:windowSoftInputMode="adjustResize"` à `EditorActivity`
  (à valider — la piste (b) de E5 pourrait rendre `adjustResize`
  superflu).
- `EditorActivity.kt:291` : `enableEdgeToEdge` avec
  `SystemBarStyle` explicite (clair/sombre selon le thème).
- Désactiver `isNavigationBarContrastEnforced` (API 29+) si le sheet
  gère son fond.
- `windowLightNavigationBar` selon le thème jour/nuit — passer par
  `SystemBarStyle.light()/dark()` plutôt que par les attributs
  dépréciés.

**Fichiers touchés** : `feature/editor/src/main/AndroidManifest.xml:19-22`,
`EditorActivity.kt:291`.

### E7 — Pas de régression

**Plan** :
- Tiroir poussant et poignée (ADR 0093/0100) : préservés (infirme
  le constat que ces composants soient causes — pas de modification
  requise).
- Ancre du sheet étendu sous la toolbar et les onglets
  (`poserAncragePanneau`, `EditorActivity.kt:1232`) : vérifier que la
  nouvelle gestion des insets n'affecte pas l'ancrage.
- État vide centré, aperçu XML partagé, rotation, mode multi-fenêtre :
  tests Robolectric à ajouter pour chaque cas.

## 5. Tests proposés (NON implémentés en phase A)

- Tests unitaires du calculateur de réserve (fonction pure de E1) :
  tous les états du sheet × clavier ouvert/fermé × avec/sans inset
  de navigation × peek changeant.
- Test Robolectric : une touche `ACTION_DOWN` dans une zone vide du
  sheet n'atteint PAS `vue_editeur`, dans chaque état du sheet
  testable.
- Test du cycle clavier : après ouverture puis fermeture simulées des
  insets IME, l'état du sheet et la réserve reviennent aux valeurs
  initiales, sans écriture de `state` pendant l'animation.
- Si Robolectric ne permet pas un cas (animation IME réelle), l'ajouter
  à `docs/TESTS_MANUELS.md`.
- Noms de tests en français entre accents graves (convention du dépôt).

## 6. Vérification et livraison (phase B)

- `source scripts/env.sh`, puis la chaîne COMPLÈTE de la directive
  v0.79.0 sur les modules touchés : `spotlessApply`, puis
  `spotlessCheck detekt checkModuleDependencies lintDebug
  testDebugUnitTest koverVerify`, puis `:app:assembleDebug`.
- `scripts/bump-version.sh patch` (v0.124.0 → v0.124.1) ; entrée
  CHANGELOG ; ADR `0118` (0116 et 0117 déjà pris par PE4 et Phase A
  indexing) ; mise à jour des KDoc devenus faux.
- Commit conventionnel en français (`fix(editor): ...`) sous
  l'identité `jjoblab`, tag annoté `v0.124.1`.

## 7. Tests manuels à ajouter à `docs/TESTS_MANUELS.md` (phase B)

1. Navigation par gestes puis à trois boutons : l'en-tête replié est
   entièrement visible et cliquable au-dessus de la barre.
2. Sheet replié, mi-hauteur, étendu : toucher le vide du sheet ne
   déplace jamais le curseur de l'éditeur et n'ouvre pas le clavier.
3. Taper dans l'éditeur sheet replié, puis mi-hauteur, puis étendu :
   le clavier s'ouvre et se ferme sans saut, le sheet revient dans
   le même état.
4. Dernière ligne du fichier et curseur visibles sheet replié, avec
   et sans ligne tooling.
5. Changer d'onglet du panneau (Console, Problèmes, Journal, Logcat) :
   l'éditeur ne bouge pas.
6. Rotation avec le clavier ouvert, multi-fenêtre, clavier flottant
   ou scindé, Android 8-9 (API 26-29) si disponible.
7. Thème clair/sombre et couleurs dynamiques : barres système lisibles.

## 8. Limites de l'audit

- **Sources Material 1.14.0** : non lues dans cette session (pas de
  cache Gradle disponible). Les comportements de
  `BottomSheetBehavior` qui dépendent de Material (effet de
  `gestureInsetBottomIgnored`, gestion de
  `findScrollingChild` après `show`/`hide` de fragments, etc.) sont
  à vérifier en phase B contre les sources Material.
- **Sources `code-editor` 3.69.0** : non lues pour vérifier
  l'existence et le comportement de `EditorView.ensureCursorVisible()`
  (utilisé dans la proposition E3).
- **Tests Robolectric** : non écrits dans cette session (pas
  d'environnement de build Android). À ajouter en phase B.
- **Tests appareil** : aucun appareil Android disponible — les tests
  manuels §7 restent à exécuter.

## 9. Livrable de phase A

- **Audit complet** : 5 constats confirmés sur 5 (+ 1 infirmé), avec
  preuves `fichier:ligne` pour chacun (§1).
- **Plan de correctif E1-E7** : détaillé pour chaque exigence, avec
  fichiers touchés et risques (§4). **Aucun code de production
  modifié**.
- **Tests proposés** (§5), **vérification et livraison** (§6), **tests
  manuels** (§7).
- **Limites de l'audit** tracées honnêtement (§8).

## 10. Hors périmètre

- Bibliothèque `code-editor` (prompt compagnon).
- Contenu des fragments du panneau.
- Refonte visuelle.
- Écran d'indexation (prompt 2 — Phase A livré en v0.124.0,
  `docs/audit/INDEXATION_PHASE_A.md`).
