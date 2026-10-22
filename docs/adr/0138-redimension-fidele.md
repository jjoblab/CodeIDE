# ADR 0138 — Le geste de redimensionnement devient fidèle

- **Statut** : **accepté** (livré avec CodeIDE v0.170.0).
- **Date** : 2026-10-23
- **Décideurs** : jjoblab (arbitrages D1–D3 de l'étude
  `docs/PE14-redimensionnement-layoutlib-conformite-compose-etude.md` tranchés
  « C'est oui pour D1-D8 » le 2026-10-23 — dans le sens recommandé par l'étude)
- **Dépend de** : PE11l (le redimensionnement par poignée, maquette v6 § 3.5),
  ADR 0133 (cible tactile 18 dp), v0.166.4 (poignées de la racine actives),
  LayoutLib 3.4.x (poids `BridgeInflater`, marge `marginStart` → `marginLeft`
  renommé par `XmlPreprocessor`).

## Contexte

Retour propriétaire du 2026-10-22 : « la fonctionnalité de
redimensionnement […] elle beugue, les calculs ne sont pas correctes ;
lorsque j'appuie sur l'un des handlers pour redimensionner je n'ai pas le
résultat voulu ». L'étude PE14 § 1 chiffre la cause racine — le **SAUT DE
PRÉHENSION** : la cible vaut `|ancre − doigt|` or le tactile saisit la
poignée quelque part dans son rayon de saisie de 18 dp écran (dérogation
ADR 0133 : « un doigt à 9 dp ne tient pas »), JAMAIS pile sur le coin.
Dès le premier `MOVE`, la cible saute de la longueur du décalage de
saisie — jusqu'à ~18 dp par axe (§ 1.3 : +12 dp sur une saisie à 12 dp du
coin, un tiers de la dimension d'un bouton 88 × 48 dp). Le modèle venait
du designer de la référence (`ResizeBaseTarget`), conçu pour une SOURIS
(curseur sur la cible de ~6 px, décalage ≈ 0) ; `CommonDragTarget`, lui,
compense l'offset d'accroche — le patron existait, le resize ne l'avait
pas.

L'étude chiffre aussi CINQ causes secondaires (§ 1.5) : (A) un enfant
`layout_weight` non nul d'un LinearLayout absorbe l'espace restant
PAR-DESSUS la taille déclarée — le geste écrit `150dp`, le rendu ne bouge
pas d'un pixel ; (B) aucun retour aux tailles sémantiques (la référence
snappe à ±10 dp vers wrap/match — renvoyé à plus tard, D2) ; (C) aucune
compensation par layout parent — chez la référence `RelativeResizeTarget`
réécrit les marges du bord tenu, `ConstraintResizeTarget` pose les
positions absolues du dialecte `tools:` ; (D) le fantôme disparaît au
relâchement quand le re-rendu asynchrone n'est pas encore arrivé —
fenêtre « rien ne s'est passé » ; (E) pas de clamp au parent (renvoyé à
plus tard, non bloquant).

## Options

- **A. Statu quo** : conserver `cible = |ancre − doigt|` à la lettre de
  la maquette v6. Refusé : la maquette v6 décrit la maquette WEB SOURIS
  (§ 1.4 de l'étude) ; sur TACTILE le saut rend le geste inutilisable —
  c'est le bug rapporté.
- **B. Compensation + transition + par-layout — recommandé** : le coin
  VISUEL suit le doigt (`decalage = coinSaisi − contact` au `DOWN`),
  le fantôme persiste jusqu'au rendu suivant (fondu 150 ms), et le
  relâchement écrit les ajustements PAR-LAYOUT dans la MÊME édition
  groupée (poids neutralisé, marges du bord tenu, positions `tools:`).
- **C. Recopier la référence** : en plus de B, snap ±10 dp vers
  wrap/match mesuré par le moteur (exige d'exposer la taille wrap) et
  clamp au parent. Refusé pour cette vague : D2/D3 de l'étude renvoient
  le snap à plus tard (P2) et le clamp n'est pas bloquant — le noyau
  C1+C4+C3 répare le bug rapporté.

## Décision

Option **B** — les correctifs C1/C4/C3 de l'étude § 1.6, arbitrages
D1–D3 du propriétaire (« C'est oui pour D1-D8 »).

- **D1 — Compensation de préhension (C1, le cœur)** : au `ACTION_DOWN`,
  la saisie rapporte le coin SOUS le doigt (`poigneeSous` — coin + ancre
  opposée) et la vue mémorise `decalage = coinSaisi − pointDeContact`
  (px contenu). La cible, le fantôme et la pill dp se calculent depuis
  `point + decalage` : le fantôme part DE LA TAILLE ACTUELLE de la vue,
  le coin visuel suit le doigt — le geste devient l'analogue tactile du
  `CommonDragTarget` de la référence. Un contact PILE sur le coin donne
  `decalage = 0` : le modèle historique PE11l en est le cas particulier
  (C1 RÉTROCOMPATIBLE, les tests existants restent verts). La cible
  voyage jusqu'à l'édition dans `RedimensionApercu.CibleRedimension`
  (dimensions dp + ancre + coin visé) — le par-layout en dérive ses
  ajustements.
- **D2 — Snap sémantique** : RENVOYÉ (P2 de l'étude) — d'abord C1+C4 ;
  le snap exige d'exposer la taille wrap mesurée par le moteur.
- **D3 — Par-layout au relâchement (C3)** : `PreviewViewModel` dérive
  les ajustements de la famille du PARENT RENDU (même vérité que la
  sélection), les règles de position du TEXTE VIVANT (`lire`) :
  - **LINEAR/APPBAR** : un `layout_weight` > 0 CONTREDIT la taille fixe
    écrite (cas A) — il part dans la MÊME édition groupée ;
  - **RELATIVE** : miroir `RelativeResizeTarget`/`updateAlignAttribute`
    — le bord TENU suit le doigt par SA marge (mesurée depuis sa
    référence : bord du parent par défaut/ancrage implicite, vue alignée
    pour `toRightOf`/`below`/`align*`), l'ancre reste fixe par
    construction (la taille est mesurée DEPUIS elle). Un bord DÉRIVÉ de
    l'ancre opposée ne s'écrit pas (il suit seul), un axe CENTRÉ ne
    s'écrit jamais, une référence INTROUVABLE saute le côté, la marge ne
    descend jamais sous zéro (clamp de la référence). Si les DEUX
    variantes `marginStart`/`marginLeft` coexistent au source, la
    seconde est réalignée dans la même édition (le `XmlPreprocessor` du
    frère renomme `marginStart` → `marginLeft` : une variante oubliée
    l'emporterait silencieusement au rendu) ;
  - **CONSTRAINT** : miroir `ConstraintResizeTarget` — le bord DÉPLACÉ
    (gauche/haut tenu) pose `tools:layout_editor_absoluteX/Y` SEULEMENT
    SI le document déclare déjà le dialecte `xmlns:tools` (jamais de
    dialecte imposé) ;
  - sans parent (racine) ou famille inconnue : les deux axes seuls
    (comportement PE11l).
- **C4 — Transition douce post-écriture** : le relâchement GARDE le
  fantôme (rectangle + pill dp) affiché PLEIN jusqu'au RENDU SUIVANT
  (`afficherRendu` amorce le fondu), puis le fond sur
  `RedimensionApercu.DUREE_FONDU_FANTOME_MS` = 150 ms — le panneau
  n'a jamais d'instant vide. Un échec d'écriture (callback `false`), un
  CANCEL, un contact sans dérive, un NOUVEAU geste et
  `reinitialiser()` effacent le fantôme immédiatement (jamais de faux
  confort). L'alpha dérive de `alphaFantome(ecouleMs)` (pur, testé).
- **Édition** : `EditionAttributsXml.ecrireDimensions` devient
  `ecrireRedimension(texte, span, largeurDp, hauteurDp,
  AjustementsRedimension)` — la COMPOSITION reste ATOMIQUE (chaque
  écriture décale le span de son delta, puis la balise ouvrante
  ENTIÈRE est remplacée : un geste = UNE entrée d'historique, miroir
  `mutate`). `auRedimensionner` devient
  `(CibleRedimension) -> Boolean` (le succès pilote le fantôme).

## Conséquences

- Le saut de préhension disparaît : le ressenti « les calculs ne sont
  pas corrects » est réparé au noyau (C1), la vue NE GLISSE PLUS dans
  les conteneurs contraints (C3) et le geste a un RETOUR CONTINU
  jusqu'au nouveau rendu (C4).
- Le re-rendu reste le chemin nominal (une écriture par geste) ; le
  fantôme persistant vit dans le TRACÉ, jamais dans le document.
- Les tests purs couvrent le modèle (saisie décalée, règles par côté,
  frères introuvables, axes centrés, clamp négatif, alpha) ; les tests
  Robolectric couvrent la machine (cible compensée, fantôme persistant,
  échec, nettoyage) et l'éditeur (poids, marge, dialecte `tools:`,
  idempotence, span périmé).
- Hors périmètre (renvoyés par l'étude) : snap ±10 dp (D2), clamp au
  parent (E), compensation des parents EXOTIQUES (CoordinatorLayout
  behaviors, DrawerLayout gravités).
