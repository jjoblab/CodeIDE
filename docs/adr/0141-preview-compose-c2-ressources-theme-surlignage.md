# ADR 0141 — La preview Compose C2 résout les ressources et le thème du projet, et le caret surligne le nœud dans la carte

- **Statut** : **accepté** (livré avec CodeIDE v0.175.0).
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (instruction propriétaire « GO C2 » de la
  mission « Previews » ; cadrage antérieur PREVIEW.md §4.4 étape C2 «
  Ressources et thème du projet dans la composition distante
  (réutilise les résolveurs PE1 + index LSP_XML) » ; étude PE14 §5.4 —
  écho `ComposeViewInfoParser` : « c'est le sésame
  sélection/surlignage/navigation »).
- **Dépend de** : ADR 0140 (C1 — la carte, le moteur, la session
  persistante), LayoutLib 3.7.0 (`module-resources`/`module-themes` —
  RÉUTILISATION PE1, pas de montée nécessaire), PE1 (résolveurs
  `dossierRes`/thème manifeste du frère XML), cel-ui B27 (contrat de
  la carte — `drawDropPreview` dessiné par l'HÔTE).

## Contexte

ADR 0140 a livré le C1 : la carte « goutte d'eau » rend le programme
interprété en session persistante, mais « les ressources et thèmes du
PROJET ne sont pas encore résolus dans la composition (C2 — les
couleurs/material par défaut du runtime se rendent), l'extraction
bounds/fichier:ligne pour le surlignage est C2 ». Le cadrage C0–C3
(PREVIEW.md §4.4, étude §5.4) : C2 = ressources/thème du projet par
RÉUTILISATION des résolveurs + extraction bounds/fichier:ligne pour
l'overlay.

## Décisions

- **D1 — La requête transporte le PROJET** :
  `RequeteRenduCompose.dossierRes/langue/themeName`, champs en QUEUE
  du parcel (miroir `vuesSysteme` du frère XML : l'AIDL reste opaque,
  lecteur et écrivain symétriques). `null` = comportement C1 EXACT (les
  couleurs/material par défaut du runtime se rendent) — un projet
  sans `res/` ni manifeste n'échoue JAMAIS.
- **D2 — RÉUTILISATION PE1, résolution au RENDU** : la composition
  CONSULTE les MÊMES classes que le moteur XML du même processus
  `:preview` — `ResourceResolverImpl` (qualifiers `values-night/` /
  `values-<lang>/`, chaînage) et `ThemeResolverImpl` (chaîne
  d'héritage des styles) de LayoutLib 3.7.0, coordonnées EXTERNES
  hors audit ProjectDependency de `:preview:compose` (même statut que
  lsp-kotlin, ADR 0140). La résolution vit au RENDU (processus
  `:preview`), PAS à la conversion : le digeste couvre le NOM de la
  référence, jamais la valeur résolue — un `colors.xml` édité re-rend
  au prochain rendu du document, jamais de valeur gelée dans le
  diff d'identité. Cache d'UN scan disque par combinaison
  (dossier, nuit, langue, thème, densité) — les bascules jour/nuit de
  la carte ne re-scannent rien.
- **D3 — Le THÈME du projet devient le SCHÉMA de la composition** :
  la base claire/sombre du runtime OÙ chaque slot résolu REMPLACE son
  défaut — table unique `SlotsThemeCompose` (21 slots principaux M3 ↔
  attributs XML : `primary` ← `colorPrimary`, `background` ←
  `android:colorBackground`…) consommée par le convertisseur
  (validation des slots cités), le rendu (résolution d'UN slot cité
  par son attribut — `MaterialTheme.colorScheme.primary` désigne la
  couleur du thème DU PROJET, sémantique exacte) et le schéma complet
  (miroir S7 du frère XML : « le thème prévisualisé devient le thème
  des vues »). Thème absent/slot absent → défault du runtime (C1).
- **D4 — Les RÉFÉRENCES voyagent par leur NOM, erreurs typées en
  données** : le convertisseur reconnaît `R.string.NOM`, `R.color.NOM`,
  `R.dimen.NOM`, `MaterialTheme.colorScheme.SLOT` (genres
  `REFERENCE_*` en QUEUE du parcel ordinal) ; un slot hors table ou un
  nom malformé reste NON interprété (erreur `ARGUMENT_NON_INTERPRETABLE`
  existante). Une référence INTROUVABLE au rendu est l'erreur TYPIQUE
  `RESSOURCE_INCONNUE` (genre en queue) qui accompagne le rendu :
  l'argument retombe sur son DÉFAUT honnête (miroir `createMockView` :
  jamais un faux rendu, jamais un échec de tout-ou-rien, jamais
  d'exception à travers Binder).
- **D5 — Extraction bounds/fichier:ligne** : chaque nœud du registre
  publie sa géométrie — `onGloballyPositioned` posé en QUEUE de chaîne
  du récepteur (l'étendue publiée est l'étendue DESSINÉE),
  `boundsInRoot` donnant le repère du coin haut-gauche du canevas de
  composition (l'origine du bitmap — la vue est posée à (0, 0) au
  rendu off-screen) + la LIGNE source de l'appel. Les
  `InfoNoeudCompose` traversent la frontière en QUEUE du
  `ResultatRenduCompose` et nourrissent `RenduPreviewCompose.noeuds`.
  Le collecteur est FRAIS par rendu (les bornes n'appartiennent qu'à CE
  rendu — aucune pollution entre les rendus d'une même session
  persistante).
- **D6 — Le SURLIGNAGE vit dans la carte (caret → nœud)** : le caret de
  l'éditeur sélectionne le PREMIER nœud de SA ligne source ; la carte
  dessine par-dessus le bitmap FIT un CONTOUR 2 dp + remplissage
  translucide (14 %) de la MÊME teinte d'appui que le chrome de la
  planche XML (`#43559E` — une seule teinte « vous êtes ici »),
  À CHAQUE frame (le surlignage suit le caret en direct — miroir
  « Ligne n » du frère XML). Sans vue, sans nœud de la ligne, ou
  géométrie vide : RIEN (aucun faux rectangle).
- **D7 — La LANGUE est transportée mais reste `null` au C2** : aucun
  sélecteur de langue dans la carte (miroir ADR 0140 qui reporte les
  sélecteurs profil/jour-nuit — « un chantier ultérieur les exposera
  à la carte ») ; le paramètre existe dans la requête et le moteur
  (`definirProjet`), le résolveur applique la locale système par
  défaut.

## Options rejetées

- **Résolution à la CONVERSION (app side)** : le digeste gèlerait les
  VALEURS résolues — un `colors.xml` édité ne re-rendrait jamais (pire
  qu'un repli : un FAUX rendu périmé).
- **Un résolveur maison dans `:preview:compose`** : réinventer les
  qualifiers, le chaînage des références et l'héritage des styles que
  LayoutLib fait déjà — l'étude §5.3-1 tranche : « une seule
  machinerie, deux dialectes », on RÉUTILISE.
- **L'overlay par évolution cel-ui immédiate** : le sens inverse (tap
  sur un nœud → saut source) exige un callback de tap du CORPS de la
  carte — or le contrat B27 ENGOUFIT les taps du corps (« corps →
  englouti ») ; une évolution de la bibliothèque serait nécessaire et
  son client principal est C3 (l'interactif) : reporté au chantier
  C3, documenté.
- **`Color.Red` et palettes du runtime** : ce ne sont PAS des
  ressources du projet — hors du cadrage C2 (PREVIEW.md §4.4),
  candidat pour un chantier ultérieur si le propriétaire l'arbitre.

## Conséquences

- `:preview:compose` dépend des coordonnées `layoutlib-resources` /
  `layoutlib-themes` (externes, hors audit — catalogue commenté) ;
  l'index LSP_XML du cadrage original n'est PAS consommé : les
  résolveurs PE1 lisent `res/` DIRECTEMENT (même source de vérité que
  le frère XML, un seul chemin de résolution).
- `EditorViewModel` duplique volontairement les ~40 lignes de
  résolution PE1 (`dossierResProjet`/`lireManifeste`/
  `resoudreThemeActivite`) en miroir documenté du PreviewViewModel —
  l'unification vivra le jour où la feature :preview expose une
  use case partagée (chantier ultérieur, dette assumée).
- Tests : +21 (convertisseur 7, contrats 4, résolveurs 8, moteur 2,
  hôte 2 Robolectric pixel NATIF + le relais FauxMoteur).
- Le sésame bounds/fichier:ligne est EN PLACE : C3 (interactif :
  forwarding pointer, horloge maîtrisée, 30 fps, watchdog KILL, tap
  nœud → source via évolution cel-ui) peut s'y brancher sans toucher
  aux contrats.
