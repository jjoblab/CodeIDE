# ADR 0139 — La section « Système » de l'arbre et le réglage « Mode gestuel »

- **Statut** : **accepté** (livré avec CodeIDE v0.170.0).
- **Date** : 2026-10-23
- **Décideurs** : jjoblab (chantiers « section système » et « mode gestuel »
  confirmés à la suite de PE13 — « Oui [pour] la section système, le mode
  gestuel » ; séquencés par l'étude PE14 S1 : « PE13-suite », arbitrages
  D1–D8 « C'est oui pour D1-D8 » le 2026-10-23)
- **Dépend de** : ADR 0137 (le moteur possède le chrome — collecte duale
  `ViewInfoCollector.collectDecor`, `SystemViewInfo` marqué `ViewType`,
  `ConfigChrome.modeNavigation`, `NavigationHandle`), ADR 0134 (menu
  Calques, groupe « Chrome système »), LayoutLib 3.5.0.

## Contexte

PE13 (ADR 0137) a déplacé le chrome système dans le MOTEUR : la collecte
duale produit, à chaque rendu, un arbre SYSTÈME marqué `ViewType`
(`SystemViewInfo`) que l'IDE peut exclure — ou RÉINTRODUIRE. L'étude PE13
listait deux « suivis possibles » que le propriétaire a confirmés :
consommer les `ViewType` dans l'arbre de la feuille (une section
« système » interprétable, exactement l'usage que la référence fait de
ses propres infos système), et brancher le réglage « mode gestuel » au
menu Calques (`ModeNavigation`/`NavigationHandle` existaient côté moteur,
AUCUNE UI ne les branchait — « capacités étape 5 aux DÉFAUTS MAQUETTE »).

L'étude PE14 § 0 les séquence en tête de son plan (S1, « PE13-suite ») :
le socle de connaissances est posé (§ 2 : ce que le moteur sait déjà ;
§ 3 : conformité), aucune décision d'architecture nouvelle n'est requise —
ce sont des CONSOMMATIONS de l'ADR 0137.

## Options

- **A. Statu quo** : l'arbre ne montre que le contenu ; la pilule
  gestuelle reste une capacité moteur invisible. Refusé : les deux
  chantiers sont confirmés par le propriétaire.
- **B. Consommation directe de la collecte duale + passe-plat de
  session — recommandé** : le pipeline transporte les BARRES de premier
  rang (nouveau parcelable `InfoVueSysteme`), la feuille les affiche en
  section TERMINALE de l'arbre, et `RequeteRendu.modeGestuel` traverse la
  frontière comme `barreNavigation` (PE11j).
- **C. Arbre système complet** : transporter TOUT l'arbre du décor
  (conteneurs, glyphes internes, enfants de l'action bar) et le
  présenter indenté. Refusé : la mécanique INTERNE des barres (glyphes,
  titres) est du MOTEUR, pas des composants — le bruit de rangées
  sans signification dégraderait l'arbre du document.

## Décision

Option **B**.

- **D1 — La section ne montre que les BARRES de premier rang**
  (`RenduLayoutLib.collecterVuesSysteme`) : les enfants DIRECTS du décor
  marqués `ViewType` — status bar, action bar, barre de navigation ou
  PILULE. Leurs enfants (glyphes, titres) restent au moteur. Sans chrome
  (`themeName` absent, hors LAYOUT) la section N'EXISTE PAS — la liste
  reste celle du document.
- **D2 — Le transport suit le contrat des miroirs AIDL** : le nouveau
  parcelable `InfoVueSysteme` (bornes absolues bitmap, nom simple de
  classe, marquage `ViewType`, profondeur) voyage dans
  `ResultatRendu.vuesSysteme` → `ResultatTransport.Succes` →
  `EtatPreviewXml.RenduValide.vuesSysteme` (champs À LA FIN des
  contrats Parcel, lus/écrits champ pour champ — même convention que
  `InfoVueRendu`). Les fichiers `.aidl` ne bougent pas (les parcelables
  sont opaques à la frontière).
- **D3 — Les rangées système ne sont JAMAIS sélectionnables** : aucune
  balise XML source ne les porte (miroir le toast de la maquette :
  « Barre système : aucune balise XML à sélectionner »).
  `FeuilleApercu.EntreeArbre` gagne un `genre`
  (`CONTENU` / `ENTETE_SYSTEME` / `VUE_SYSTEME`) ; l'adaptateur ne pose
  AUCUN écouteur sur les rangées système, l'ENTÊTE masque monogramme et
  badge ; les indices sont NÉGATIFS et DISTINCTS (entête −1, barres
  −2, −3…) — jamais des cibles de sélection, clés DiffUtil uniques.
- **D4 — Libellés et badges** : l'entête « Système » et les libellés des
  barres sont des CHAÎNES de l'app (résolues du `ViewType` : « Barre de
  statut », « Barre de navigation », « Pilule gestuelle » — la classe
  `NavigationHandle` distingue la pilule de la barre à trois glyphes,
  le marquage `NAVIGATION_BAR` désigne la ZONE ; repli défensif sur le
  nom de classe). Le monogramme est l'initiale de la CLASSE (« S », «
  N », « F » — les initiales de libellés se confondraient), le badge
  porte les dimensions du PROFIL (même convention que le contenu).
- **D5 — Le mode gestuel est un passe-plat de session** :
  `RequeteRendu.modeGestuel` (défaut OFF, trois boutons — défauts
  maquette ADR 0137 D6) → `ConfigChrome.Builder.modeNavigation` ; le
  moteur fait TOUT le reste (la pilule OCCUPE la même zone de 48 dp,
  `NavigationHandle` au lieu des trois glyphes — la hauteur ne bouge
  jamais). `MoteurPreviewXml.definirModeGestuel` relance un rendu
  immédiat (patron `definirBarreNavigation` PE11j). L'interrupteur vit
  dans le groupe « Chrome système » du menu Calques, INDÉPENDANT de «
  Barre de navigation » (sans barre rendue, l'état reste posé mais ne
  change rien de visible — miroir un appareil réel) ; ÉTAT DE SESSION,
  non persisté (même régime que « Barre de navigation », contrairement
  aux réglages de PROFIL PE6e-f qui vivent dans `SettingsDataStore`).

## Conséquences

- L'arbre de la feuille parle du VRAI décor : le propriétaire voit (et
  compte) les barres que le moteur possède ; l'action bar apparaît quand
  le thème la demande — la section EST la lecture du `Layout.Builder`.
- La frontière AIDL reste stable (champs ajoutés en queue, `.aidl`
  inchangés) ; `nombreVues` continue de compter le CONTENU SEUL
  (contrat ADR 0137 D5 inchangé).
- Le mode gestuel complète le trio chrome du menu Calques (« Barre de
  navigation », « Mode gestuel ») sans toucher au moteur —
  `LayoutLib 3.5.0` SUFFIT (aucune montée de version du frère).
- Hors périmètre : sélection/surlignage des vues système (aucune source),
  édition des réglages chrome dans l'arbre, cutout/surimpression côté UI
  (le mode `SURIMPRESSION` reste une donnée du moteur).
