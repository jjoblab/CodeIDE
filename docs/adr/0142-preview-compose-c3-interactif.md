# ADR 0142 — La preview Compose C3 devient interactive : forwarding du pointeur, horloge vivante, watchdog KILL et appui long → saut source

- **Statut** : **accepté** (livré avec CodeIDE v0.176.0).
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (instruction propriétaire « GO C3 » de la
  mission « Previews » ; cadrage antérieur PREVIEW.md §4.3 PC3 « livré
  en deux temps : statique d'abord (C1), interactif ensuite (C3) » et
  PC4 « garde-fous obligatoires », étape C3 §4.4 ; étude PE14 §5.4 —
  écho référence : `triggerTouchEvent` + `RealTimeSessionClock`).
- **Dépend de** : ADR 0140 (C1 — la carte, le moteur, la session
  persistante), ADR 0141 (C2 — le sésame bounds/fichier:ligne :
  `InfoNoeudCompose` nourrit le hit-test de l'appui long), cel-ui
  4.1.0 (B27-C3 : le contrat interactif du corps de la carte — le
  verrou « le corps de la carte engloutit les taps » documenté en
  0141), PREVIEW.md §6 (cible « frame interactive 30 fps sur device
  de référence »).

## Contexte

C0–C2 ont livré une preview Compose INTERPRÉTÉE, persistante, résolue
sur le projet, surlignée au caret — mais STATIQUE : le corps de la
carte engloutit les taps (contrat cel-ui B27 historique), le bouton du
registre ne réagit pas au doigt, les animations du runtime n'ont
aucune horloge pour tourner, et le watchdog de gel du processus
`:preview` reste à poser (PC4, ADR 0140 D5 : « le watchdog de gel avec
kill reste le chantier C3 »). PREVIEW.md §4.4 cadrée l'étape :
« Interactif : forwarding des entrées, animations (frame clock réelle),
garde-fous PC4 complets (watchdog KILL), tap nœud → saut source
(évolution cel-ui : le corps de la carte engloutit les taps) ».

## Décisions

- **D1 — Le corps de la carte devient tactile par CONTRAT cel-ui 4.1.0
  (B27-C3, opt-in)** : trois méthodes À DÉFAUT RAISONNABLE additives
  sur `EditorPreviewHost` — `wantsInteractiveDropPreview()` (faux = le
  comportement historique au bit près : les hôtes Markdown/HTML et la
  démo ne surchargent rien), `onPreviewDropTouch(action, sx, sy)`
  (DOWN/MOVE/UP/CANCEL du PREMIER doigt, coords LOCALES ÉCRAN dans le
  REPÈRE EXACT de `drawDropPreview`, indépendantes du facteur de
  taille — la carte touche au pixel près ce qu'elle dessine) et
  `onPreviewDropLongPress(sx, sy)`. Le routeur d'entrées de cel-ui
  possède un GESTE DE CORPS dédié : doigt sorti de l'écran CLAMPÉ au
  bord, premier slop franchi retire l'appui long (un drag est un
  drag), carte refermée en vol de geste = CANCEL transféré (le hôte
  n'attend jamais un UP fantôme), DOWN DÉCLINÉ par le hôte = corps
  inerte POUR CE GESTE. Les onglets, le ✕ et la barre de taille
  gardent leurs gestes propres. Montée 4.0.0 → 4.1.0 (bump-frère,
  instruction propriétaire « mettez a jour la version des dépôts
  frères », JitPack `v4.1.0` vérifié téléchargeable).
- **D2 — Tap court = INTERAGIR, appui long = SAUTER SOURCE
  (l'arbitrage mobile)** : tout nœud RENDU porte une ligne source — un
  tap sur un bouton ne peut pas être À LA FOIS le clic et la
  navigation. Le tap court part à la composition (la RIPPLE du bouton
  M3 est réelle, le pointer est transmis) ; l'APPUIS LONG est
  l'« inspection » — le Ctrl+clic mobile — : cel-ui ANNULE le geste
  par un CANCEL PUIS notifie le hôte, qui hit-teste les
  `InfoNoeudCompose` (C2) au point (le nœud de plus PETITE aire
  gagne : l'enfant bat le conteneur qui l'englobe) et SAUTE le caret à
  la ligne source (`setSelection(lineStart) + scrollToLine`, la carte
  esquivant le bloc de frappe au frame suivant). Sans nœud sous le
  doigt : RIEN (aucun faux saut).
- **D3 — Forwarding par CONTRAT + AIDL `transmettre` (écho
  `triggerTouchEvent`)** : l'événement voyage ENTIÈR dans le parcelable
  `EvenementTactileCompose` (action, point en PX BITMAP — l'hôte a
  inversé le FIT de la carte, densité du profil incluse, horodatages
  `uptimeMillis` du geste, pression) à travers la MÉTHODE AIDL
  additive `IRenduCompose.transmettre(identite, evenement)`. Le
  processus `:preview` RECONSTRUIT un vrai `MotionEvent` et le
  dispatch sur le FIL DE COMPOSITION (`ComposeView.dispatchTouchEvent`
  d'un arbre mesuré/posé) ; les coordonnées sont CLAMPÉES au canevas ;
  session inconnue = `false` en donnée (une touche perdue n'est ni une
  exception ni une panne) ; le transport borne l'appel à 2 s (une
  touche n'attend JAMAIS un rendu en vol) et la POLITIQUE d'isolation
  ne compte AUCUNE panne pour un doigt (le pompier porte la panne
  réelle, lui). Le `onClick` du bouton du registre reste un no-op
  honnête : la SÉMANTIQUE du source n'est pas interprétée (frontière
  C1, D8), la RIPPLE et l'état pressed, eux, sont RÉELS.
- **D4 — L'horloge interactive est un POMPIR PULL app-side (sémantique
  `RealTimeSessionClock`, sans le push)** : tant que la carte est
  OUVERTE sur un onglet RENDU, le moteur relance le rendu de CETTE
  identité à cadence CIBLE 33 ms (≈ 30 i/s, PREVIEW.md §6), ADAPTATIVE
  par paliers (une frame qui déborde son budget DOUBLE la cadence,
  30 → 15 → 8 → 4 i/s, plancher honnête 250 ms ; une frame à l'aise la
  DIVISE en retour — écho `DefaultRenderQualityPolicy`). Chaque frame
  REJOUE la requête du dernier rendu (digeste inchangé : ZÉRO
  recomposition, seule l'horloge `BroadcastFrameClock` de la session
  avance, au TIMESTAMP RÉEL — les animations suivent le temps mur) ;
  le pompier CÈDE le verrou aux rendus d'ÉDITION par tryLock (le
  live-edit passe toujours en premier, l'animation saute SA frame) ;
  la carte fermée l'ÉTEINT (jamais d'animation en arrière-fond, le
  processus redevient inactif à l'idle) ; TROIS frames échouées de
  suite l'éteignent honnêtement (le dernier rendu VALIDE reste —
  jamais de carte vide). L'hôte INVALIDIDE la vue éditeur à chaque
  état (la carte suit les frames, cel-ui ne redessine que sur
  invalidation).
- **D5 — WATCHDOG DE GEL avec KILL du processus (PC4 complet)** : le
  compteur de ZOMBIES (rendus dont le tryLock échoue : un rendu
  précédent tient le fil de composition en otage) vit dans
  l'exécuteur ; DEUX zombies CONSÉCUTIFS prouvent que le fil ne se
  libérera JAMAIS seul (une boucle mesure/pose/dessin ne rend pas son
  verrou, les budgets deviennent inertes) : le processus SE TUE
  (`Process.killProcess(myPid())`, tueur INJECTABLE — les tests prouvent
  la DÉCISION sans tuer le runner) après avoir journalisé ; l'app voit
  la mort par DeathRecipient et sa POLITIQUE existante reprend (une
  reprise, puis INDISPONIBLE). Un rendu MENÉ À BIEN purge le compteur
  (la trace de gel ne survit pas à un succès).
- **D6 — Le registre gagne l'ANIMATION INFINIE (la démo vivante de
  l'horloge)** : `CircularProgressIndicator` et `LinearProgressIndicator`
  (M3, indéterminés par défaut — sans aucun argument à interpréter)
  rejoignent le registre du convertisseur et rendent leurs composables
  RÉELS : l'horloge interactive les fait TOURNER (30 i/s cible), le
  rendu statique les FIGE à leur pose (jamais un faux mouvement). Les
  deux genres vivent EN QUEUE de `GenreNoeudCompose` (l'ordinal reste
  RÉTROCOMPATIBLE : un ancien parcel se lit, un ordinal inconnu
  retombe INCONNU), et le registre quitte le fichier de l'exécuteur
  pour le sien (`RegistreCompose.kt` — le fichier comptait plus de
  fonctions que le seuil detekt, le registre est un concept cohérent
  depuis C1).
- **D7 — Stateful par SESSION, honnête (PC1 intact)** : l'état
  `remember` de la composition vivante SURVIT aux frames du pompier et
  aux éditions (diff d'identité, ADR 0140) ; l'interactif n'invente
  PAS d'état utilisateur — un `Checkbox` sans `onCheckedChange`
  interprété ne bascule PAS (sémantique réelle de Compose : un widget
  d'affichage ne triche jamais), les lambdas du source ne s'exécutent
  JAMAIS. Ce que C3 rend interactif est RÉEL par construction M3 :
  ripple, pressed, animations indéterminées.
- **D8 — Câblage : la carte ARME, le moteur POMPE** : l'hôte branche
  l'horloge sur l'onglet AFFICHÉ (`armerInteractif` à l'ouverture, au
  changement d'onglet, à chaque `RenduValide` — idempotent côté moteur,
  re-brancher une identité morte la REPART) ; l'onglet VISIBLE s'anime,
  les autres attendent (jamais de re-rendu des onglets cachés) ; les
  touches relayées par le port `transmettreTouch(evenement)` (le
  CONTRAT voyage entier — le port ne déroule jamais les six champs).

## Options rejetées

- **PUSH par callback AIDL (le :preview pousse ses frames)** : miroir
  plus exact du transport d'images d'Android Studio, mais une boucle de
  push côté processus peut TOURNER SEULE (panne de contrôle), la
  backpressure s'invente (buffers, drops), l'arrêt devient un protocole
  (fermer le flux, gérer la mort du récepteur) — le PULL rend les
  trois GRATUITS : le prochain appel attend le retour du précédent, la
  carte fermée cesse d'appeler, le pompier s'éteint seul.
- **Tap court = saut source** : TOUT nœud rendu a une ligne — le tap
  ne pourrait plus CLIQUER quoi que ce soit ; l'appui long est le geste
  mobile d'inspection standard (le « Ctrl+clic » du bureau n'existe
  pas sur téléphone).
- **Interpréter les lambdas (`onClick = { n++ }`)** : c'est la « voie
  compilateur par frappe » rejetée en PC1 ; l'interpréteur rend des
  APPELS, pas des sémantiques — la frontière C1 tient.
- **Un widget d'état FACTICE (checkbox qui bascule visuellement sans
  handler interprété)** : un FAUX rendu de sémantique, exactement ce
  que PC1/PC4 interdisent — la vraie réponse de Compose (pas de
  basculement sans `onCheckedChange`) EST le comportement honnête.
- **Pompe sur TOUS les onglets** : multiplier les rendus par onglet
  visible ×1 pour un écran qui n'en montre qu'un — l'onglet AFFICHÉ
  seul s'anime.

## Conséquences

- **code-editor 4.0.0 → 4.1.0** (bump-frère, +8 tests Robolectric
  cel-ui, tag `v4.1.0`, JitPack vérifié — la coordonnée consommée par
  le catalogue est `v4.1.0` : l'alias JitPack sans préfixe n'est pas
  encore résolu au moment de la livraison, la forme préfixée l'est).
- **+18 tests CodeIDE** : 13 JVM dans `:preview:compose` (contrats 1,
  convertisseur 1, moteur 5 — pompier, cadence, extinction sur
  erreurs, relais des touches, idempotence — rendu 3 — dispatch
  session vivante/inconnue, watchdog DÉCISION par tueur injecté,
  indicateur animé rendu — service 1, transport 2) + 5 Robolectric
  dans feature:editor (corps interactif, mapping FIT→bitmap, DOWN
  décliné, cycle d'armement, appui long → saut source).
- **Chaîne locale VERTE** : spotlessCheck, detekt (les violations en
  route — LongParameterList, ReturnCount ×3, CyclomaticComplexMethod,
  TooManyFunctions fichier, NestedBlockDepth — TUES PAR
  RESTRUCTURE : contrat-objet, when-expression, extractions
  RegistreCompose/RendreErreur/RendreIndicateur/issueDuResultat,
  fusion dimensionDe, zéro desserrage), checkModuleDependencies,
  checkUiTokens, :preview:compose 82 tests, :feature:editor 436
  tests — 0 échec.
- **Mémoire** : une frame ≈ un bitmap plein profil (~10 Mo natifs à
  393×851 dp @440 dpi) — le pompier en alloue ~30/s en régime cible,
  le GC natif suit (une bitmap vivante par onglet + une en vol) ; les
  métriques de chaque frame portent la durée MESURÉE, la cadence
  s'adapte au device réel — ressenti et tenue à valider au device
  (TESTS_MANUELS).
- **Restent hors C3** (chantiers ultérieurs arbitrés plus tard) :
  surligner les ERREURS typées dans le code source (le catalogue
  traverse déjà l'état), sélecteurs profil/jour-nuit/langue dans la
  carte, consommation `hintViewport` de code-editor, `Color.Red` &
  palettes du runtime, interprétation élargie (TextField, scrollables)
  — la frontière « registre + littéraux » bougera au fil des
  arbitrages propriétaire.
