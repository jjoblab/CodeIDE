# Explorateur de fichiers v2 — spécification de reproduction à l'identique

> **Étape 31 · cible v0.32.0.** Ce document décrit **exactement** la maquette
> interactive validée le 2026-09-25 (`docs/preview/explorateur-v2.html`,
> 88 Ko, zéro dépendance externe — l'ouvrir dans un navigateur sert de
> référence visuelle et comportementale pendant toute l'implémentation).
> La règle est la reproduction **à 100 %** : toute valeur numérique, couleur,
> durée, libellé ou comportement décrit ici fait foi. Un écart constaté sur
> l'appareil = un défaut de l'étape.

La maquette est en thème **nuit** ; les valeurs ci-dessous sont les valeurs de
référence. À l'implémentation, elles deviennent des tokens du thème (les
noms « preview » sont conservés pour la traçabilité) ; le thème jour reste
à la discrétion de Material 3 dynamique mais la structure, les dimensions et
les comportements sont identiques dans les deux thèmes.

## 1. Périmètre

- **Tiroir à fragments** : l'empilement de vues dans le layout
  `EditorActivity` disparaît ; chaque destination du tiroir (Explorateur,
  Recherche, Git, Terminal) devient un **fragment** portant **son propre
  entête** — plus d'entête commun. Seul le fragment **Explorateur** est
  implémenté à l'étape 31 ; Recherche/Git/Terminal restent des aperçus
  statiques (structure d'accueil, lot ultérieur).
- **Poignée ⋮** de redimensionnement du tiroir par glissement.
- **Explorateur de fichiers refondu** : arbre « treeview » (guides fins,
  chevrons, points d'état), vraies icônes par type, bascule
  **Projet / Privé**, popover maison ancré au doigt, mutations **par nœud**
  (nouveau, renommer, supprimer, copier, couper, coller, déplacer).

## 2. Architecture générale

L'écran (414 × 860 dans la maquette) se compose, de haut en bas :

| Zone | Dimensions | Notes |
|---|---|---|
| Barre de statut | h 30 px | décorative dans la maquette |
| AppBar | h 52 px | menu tiroir, titre « CodeIDE » + version, Exécuter, options |
| Onglets de fichiers | h 42 px | défilement horizontal, séparateurs, onglet actif marqué en haut |
| Zone d'édition | le reste | coloration simulée, vide si aucun onglet |
| **Tiroir** (par-dessus) | par défaut **262 px** de large, bord gauche, toute la hauteur | `z` au-dessus de l'éditeur, ombre portée vers la droite |

Le tiroir contient, de haut en bas : **entête du fragment** (propre à chaque
fragment), **corps** (l'arbre pour l'explorateur), **barre presse-papiers**
(conditionnelle), et en bas un **rail de fragments** (h 66 px) commun au
tiroir. À l'extérieur, sur le bord droit : la **poignée ⋮** de
redimensionnement.

L'ouverture/fermeture du tiroir se fait par le bouton menu de l'AppBar ;
l'animation est une translation `translateX(-102 %)` de 0,26 s,
courbe `cubic-bezier(.4,.1,.2,1)`.

## 3. Jetons de design (thème nuit de référence)

### 3.1 Couleurs

| Token | Valeur | Usage |
|---|---|---|
| fond-page | `#0b0d11` | décor maquette |
| fond-ecran | `#14171d` | fond de l'éditeur / onglets actifs |
| fond-tiroir | `#1a1e25` | fond du tiroir |
| fond-surface | `#20252e` | cartes |
| fond-surface-2 | `#262c37` | popovers, poignée |
| fond-surface-3 | `#2d3441` | survols de popover, boutons |
| bordure | `#2c323d` | bordures fortes |
| bordure-fine | `#242a33` | bordures discrètes |
| texte | `#dde3ec` | texte principal |
| texte-2 | `#99a2ae` | texte secondaire |
| texte-3 | `#6c7581` | texte tertiaire, chemins, compteurs |
| accent | `#7ab8f5` | sélection, éléments actifs |
| accent-fort | `#4f9be8` | barre de sélection, boutons primaires |
| accent-doux | `rgba(122,184,245,.14)` | fonds de sélection, halos |
| vert | `#6fc47a` | fichier ouvert dans l'onglet actif, succès |
| vert-doux | `rgba(111,196,122,.14)` | barre presse-papiers |
| orange | `#eda63f` | emblème Recherche, badge « M » |
| rouge | `#e5584f` | actions dangereuses |
| rouge-doux | `rgba(229,88,79,.12)` | survol action dangereuse |
| violet | `#9c86d8` | emblème Terminal, accents privés |
| guide | `#343b47` | traits fins reliant les nœuds |
| dossier-ic | `#566175` | icône dossier standard |

Couleurs calculées complémentaires : survol de ligne
`rgba(255,255,255,.035)` ; nom de dossier `#e2e8f1` ; nom de fichier
`#c7ced9` ; noms dans l'arbre privé `#cdbaf2` ; segment « Privé » actif
`#cdbaf2` avec halo `rgba(156,134,216,.45)` ; segment « Projet » actif
`accent` avec halo `rgba(122,184,245,.35)`.

### 3.2 Géométrie

- Rayons : `r-l` 14 px (popovers, snackbar 12 px), `r-m` 10 px,
  `r-s` 7 px.
- Ombres : `ombre-1` `0 2px 10px rgba(0,0,0,.35)` ;
  `ombre-2` `0 10px 34px rgba(0,0,0,.5)` ; tiroir
  `6px 0 26px rgba(0,0,0,.42)`.
- Typographie : interface `"Segoe UI", system-ui, Roboto` ;
  chemins et code `ui-monospace, "Cascadia Mono", Consolas,
  "JetBrains Mono", Menlo`.

## 4. Entête du fragment Explorateur

Fond dégradé `#20252e → #1c212a`, bord inférieur 1 px `bordure-fine`,
padding `13 px 14 px 11 px`. De haut en bas :

1. **Ligne de titre** — emblème 32 × 32 (rayon `r-s`, fond `accent-doux`,
   icône `accent`, 17 px) ; titre « Explorateur de fichiers » 14 px / 650 ;
   sous-titre **chemin de la racine affichée** en mono 10,5 px `texte-3`
   (`/storage/emulated/0/CodeIDE` en mode Projet,
   `/data/user/0/jo.codeide` en mode Privé), tronqué par points de
   suspension.
2. **Ligne d'actions** (marge haute 10 px, boutons 32 × 32, rayon `r-s`,
   écart 3 px, icônes 17 px, survol `fond-surface-2`, bouton « Actualiser »
   avec rotation d'icône 0,65 s au clic) :
   - **Nouveau** (icône fichier+), titre « Nouveau (dans le dossier
     sélectionné) » ;
   - **Replier tout** (chevrons convergents) ;
   - **Actualiser** (flèches circulaires) ;
   - **Légende** (point d'interrogation).
3. **Bascule Projet / Privé** — voir § 5.
4. **Fil d'Ariane** — conteneur `#15181f`, bordure `bordure-fine`, rayon
   `r-s`, padding `6 px 9 px`, marge haute 10 px ; icône maison à gauche,
   puis une étiquette par ancêtre (mono 11 px, clic = sélection du nœud,
   séparateur `›` 10 px `texte-3`, dernière étiquette en `accent`), survol
   `fond-surface-2`. Reflète **la sélection courante** ; maison seule si
   aucune.

Les autres fragments (aperçus statiques) portent le même entête avec leur
emblème coloré : Recherche (orange), Git (vert), Terminal (violet), titre +
sous-titre mono, et une ligne d'actions propre le cas échéant (Git : bouton
commit ; Terminal : bouton nouvelle session).

## 5. Bascule Projet / Privé

**Composant** : deux segments dans un conteneur partagé. Le conteneur :
flex, écart 4 px, padding 3 px, fond `#15181f`, bordure 1 px
`bordure-fine`, rayon 9 px, marge haute 9 px. Chaque segment : flex 1,
centré, écart 7 px, padding `7 px 6 px`, rayon 7 px, texte 12 px / 600,
icône 17 px, fond transparent, transitions fond/couleur/halo 0,15 s.

- Segment **Projet** : icône explorateur (dossier). Actif → fond
  `fond-surface-3`, texte `accent`, halo interne 1 px
  `rgba(122,184,245,.35)`.
- Segment **Privé** : icône cadenas. Actif → fond `fond-surface-3`, texte
  `#cdbaf2`, halo interne 1 px `rgba(156,134,216,.45)`.

**Comportement** (remplace l'ancien bouton cadenas « afficher/masquer ») :

- Les deux arborescences sont **exclusives** : le fragment affiche **soit**
  l'arbre du projet, **soit** l'arbre du stockage privé — jamais les deux.
- Un appui sur le segment actif ne fait rien. Un appui sur l'autre :
  ferme tout popover ouvert, **réinitialise la sélection** (fil d'Ariane →
  maison seule), re-rend l'arbre sur la nouvelle racine, met à jour le
  sous-titre d'entête (chemin de la racine) et montre un snackbar
  (« Stockage privé — arborescence /data/user/0/jo.codeide » ou
  « Arborescence du projet ») portant le chemin en seconde ligne.
- La sélection initiale au démarrage est **Projet**. Les onglets ouverts de
  l'éditeur ne sont **pas** affectés par la bascule (ils concernent
  l'éditeur, pas l'arbre affiché).

## 6. Rendu de l'arbre (treeview)

### 6.1 Ligne de nœud

Hauteur **34 px** (racines : **38 px**), rayon 7 px, padding droit 8 px,
curseur « pointer ». Structure interne, dans l'ordre :

- **Dossier** : chevron (largeur 20 px, triangle plein 13 px `texte-3`,
  rotation 90° sur 0,16 s quand déplié → couleur `accent`) ; icône dossier
  colorée (17 px) ; **nom** (13 px, `#e2e8f1`, graisse 500) ; compteur
  d'enfants aligné à droite (mono 10 px `texte-3`, masqué si vide) —
  uniquement pour les dossiers non racines.
- **Fichier** : **point d'état** (largeur 20 px, disque 7 px, bordure 2 px,
  § 7) ; icône de type (17 px) ; nom (13 px, `#c7ced9`).
- **Racine** uniquement : badge de chemin à droite (mono 9,5 px, fond
  `#15181f`, bordure `bordure-fine`, rayon 5 px, padding `2,5 px 7 px`,
  tronqué à 46 % de la largeur, attribut `title` = chemin complet).

**Il n'y a ni crochets, ni aucun autre caractère de notation autour des
noms** — les dossiers se distinguent par le chevron + l'icône et les
fichiers par le point d'état (le fil d'Ariane et les guides font le reste).

États de ligne : survol `rgba(255,255,255,.035)` ; sélection → fond
dégradé `accent-doux → transparent` (72 %) + barre interne 2,5 px
`accent-fort` à gauche ; ligne « coupée » → opacité 50 % + nom barré
(`rgba(229,88,79,.55)`) ; ligne « flash » après mutation → 1,1 s sur le
fond de sélection.

### 6.2 Guides (traits fins)

Sous-liste : `margin-left` 10 px + `padding-left` 12 px. Pour chaque nœud
enfant :

- trait vertical 1 px `guide`, de la hauteur de la liste, à 10 px à gauche ;
  raccourci à 17 px de haut pour le **dernier** enfant ;
- coude horizontal 1 px `guide`, 10 px de large, à 16 px du haut, reliant
  le trait vertical à la ligne.

Les guides doivent rester **dans la zone non rognée** (le rognage
`overflow:hidden` du repliage ne doit jamais couper les traits — d'où le
padding asymétrique).

### 6.3 Repliage

Chaque dossier porte un conteneur `grid-template-rows: 0fr → 1fr`
(0,2 s) — l'animation anime la hauteur, le contenu reste mesuré. Clic sur la
ligne de dossier = sélection **+** bascule déplié/replié.

### 6.4 Tri (ADR 0027)

Dossiers avant fichiers, puis `localeCompare("fr", insensible à la casse,
numérique)`. Toute insertion (création, collage, déplacement) **respire**
avec ce tri : l'élément apparaît à sa place, puis flash + défilement doux
vers lui.

### 6.5 Défilement

Barres de défilement discrètes (8 px, pouce `#2e3542` rayonné 6 px, survol
`#3a4351`). Padding de l'arbre : `9 px 5 px 20 px 2 px`.

## 7. Point d'état des fichiers

Disque de 7 px, bordure 2 px, au chevron des dossiers. Les états,
**par précédence croissante** :

| État | Rendu | Condition |
|---|---|---|
| défaut | bordure `#5a6270`, fond transparent | fichier non ouvert |
| ouvert (inactif) | bordure `vert`, fond transparent | ouvert dans un onglet non actif |
| **onglet actif** | bordure `vert`, **fond** `vert`, halo `0 0 7 px rgba(111,196,122,.5)` | fichier de l'onglet actif de l'éditeur |
| **sélectionné** | bordure `accent`, **fond** `accent` | nœud sélectionné dans l'arbre |
| sélectionné **et** actif | bordure `accent`, fond `vert`, anneau externe 3 px `accent-doux` | les deux à la fois |

La sélection **prime** sur l'onglet (bordure), mais le fond reste vert si
le fichier est celui de l'onglet actif — c'est le seul cas de fond vert
avec bordure bleue. Le changement d'état est animé (0,16 s, toutes
propriétés).

## 8. Icônes par type de fichier

Icônes « marques » 24 × 24 rendues à 17 px, marge gauche 5 px / droite 2 px.

| Extension(s) | Marque | Couleur dominante | Motif |
|---|---|---|---|
| `kt` | Kotlin | `#7F52FF` | carré arrondi + chevron K blanc |
| `java` | Java | `#f89820` | tasse fumante + anse |
| `xml` | XML | `#FF7043` | feuille + chevrons `</>` blancs |
| `kts`, scripts Gradle | Gradle | `#1B7EA6` | carré arrondi + « G » blanc |
| `md` | Markdown | `#519aba` | rectangle + « M ↓ » blancs |
| `json` | JSON | `#a9b838` | carré arrondi + `{ }` blancs |
| `properties`, `pro` | propriétés | `#a074c4` | carré + engrenage blanc |
| `toml` | TOML | `#9d7bd8` | carré + « T » blanc |
| `.git*` (fichiers cachés) | git | `#F05033` | carré + branche de commits |
| `db`, `jar` | base | `#26a69a` | cylindre de base de données |
| `log`, `txt` | journal | `#79828f` | feuille + lignes |
| sans extension | script | `#98b06a` | feuille + invite `>_` |
| autres | fichier | `#8a93a3` | feuille grise |

Règle des cachés : un nom **commençant** par un point (`.gitignore`,
`.gitattributes`) porte la marque git — le point initial n'est jamais traité
comme une extension (cohérent avec `mimeFichierTexte` v0.31.7). Un nom
sans point du tout porte la marque script.

### Icônes de dossier

Dossier 24 × 24 rempli, teinte selon le contexte : standard
`#566175` ; racine projet `#6b96c4` ; racine privée `#7c6aa8` ; sous-dossier
privé `#6d5f9b`. Toute entrée **privée** porte en plus un petit cadenas
blanc (corps 4,6 × 3,6 + anse) posé sur le dossier. Un liseré horizontal
`rgba(255,255,255,.16)` souligne la facette du dossier.

## 9. Arborescence du stockage privé

Affichée **uniquement** quand le segment Privé est actif (§ 5). Racine
« Stockage privé », badge `/data/user/0/jo.codeide`, icône dossier
violet + cadenas. Contenu de la maquette (à cartographier sur les vrais
répertoires à l'implémentation — `filesDir`, `cacheDir`, `codeCacheDir`,
`databasesDir`, `sharedPrefsDir` via le port `FileSystem` étendu au privé) :

- `files/bootstrap/` (jdk-21.tar.zst, apache-aapt2.zip), `files/registre.json`
- `cache/exports/` (journal-AAAA-MM-JJ.log)
- `code_cache/` (vide)
- `databases/` (codeide.db)
- `shared_prefs/` (jo.codeide_preferences.xml)

Dossiers dépliés par défaut : `files`, `shared_prefs`. Les règles de tri,
de point d'état, de popover et de mutation s'appliquent **à l'identique**
dans l'arbre privé ; les racines ne proposent ni copier, ni couper, ni
déplacer, ni renommer, ni supprimer (seulement nouveau/coller — § 10).

## 10. Popover maison ancré

**Jamais de menu système** : popover dessiné par l'application, ancré à la
**position exacte du doigt** (appui long) ou du pointeur (clic droit).

### 10.1 Coque

Largeur **258 px**, fond `fond-surface-2`, bordure 1 px `#333b48`, rayon
`r-l`, ombre `ombre-2`, padding 6 px. Apparition : `scale .94 → 1` + fondu,
0,15 s, origine = point d'ancrage. Une **flèche** 12 × 12 (coin du carré
tourné à 45°, mêmes fond/bordure) pointe vers la ligne.

### 10.2 Ancrage (algorithme exact)

- Cible : `x - 30` (flèche décalée de 30 px à droite du point), bornée dans
  l'écran avec 6 px de marge ; `y + 16` sous le doigt.
- Si le bas dépasserait l'écran → **retournement au-dessus** :
  `y - hauteur - 14`.
- La flèche suit le point horizontalement, bornée entre 20 px et
  `largeur - 20` du bord gauche du popover ; l'origine du zoom d'apparition
  suit la flèche.

### 10.3 Tête du popover

Icône du nœud + **nom** (13 px / 600, ellipsé à 168 px) + **chemin
absolu** (mono 10 px `texte-3`, jusqu'à 2 lignes ellipsées, `title` =
chemin complet). En dessous, ligne de **contexte** (mono 10 px `accent`,
une ligne) selon l'action :

- menu dossier : « Cible — `chemin relatif` » ;
- déplacement : « Déplacement — `chemin relatif de l'élément` » ;
- création : « Créer dans — `chemin relatif du dossier` » ;
- menu fichier / confirmation : pas de ligne de contexte (le chemin est
  dans la tête).

### 10.4 Actions

Boutons pleine largeur : padding `9 px 10 px`, écart 11 px, rayon `r-s`,
texte 13 px, icône 15 px, survol `fond-surface-3`. Action **dangereuse**
(rouge, survol `rouge-doux`). Action **désactivée** : opacité 35 %, non
cliquable, peut porter une note en marge droite (nom de l'élément du
presse-papiers pour « Coller »). Séparateur 1 px `#39414e`, marges
`5 px 8 px`.

**Fichier** : Ouvrir · — · Copier, Couper, Déplacer vers…, Renommer · — ·
Supprimer (dangereuse).

**Dossier** : Nouveau fichier, Nouveau dossier, Coller (désactivée sans
presse-papiers valide, ou si la cible est l'élément coupé/l'un de ses
descendants — note = nom du presse-papiers) ; si non-racine, en plus · — ·
Copier, Couper, Déplacer vers…, Renommer · — · Supprimer (dangereuse).
**Racine** : seulement Nouveau fichier, Nouveau dossier, Coller.

### 10.5 Popovers secondaires

- **Déplacer vers…** : champs « Origine » (chemin relatif statique, mono
  11 px, fond `#151920`, bordure fine, rayon 6 px) et « Destination »
  (champ mono 11,5 px, focus → bordure 1,5 px `accent-fort`, autocomplétion
  par datalist des dossiers valides de la racine, valeur initiale = parent
  actuel). Boutons Annuler (secondaire) / Déplacer (primaire, fond
  `accent-fort`, texte `#0d1420`). Entrée = valider. Erreurs en ligne
  (rouge 11 px) : « Dossier introuvable — chemin relatif attendu »,
  « Déjà à cet endroit. », « Impossible : la destination est dans
  l'élément déplacé. »
- **Supprimer** : texte « Supprimer *nom* [et tout son contenu] ? Action
  définitive. », boutons Annuler / Supprimer (fond `rouge`, texte blanc).
- **Légende** (bouton d'entête) : sections « Points d'état » (les 4 états
  du § 7 avec pastilles) et « Notation » — `▸` dossier (chevron de
  dépliage), `└─` trait fin parent → enfant, rappel « Appui long sur une
  ligne : menu d'actions ».
- **Nouveau** (bouton d'entête) : ancré sous le bouton, cible = dossier
  sélectionné (sinon racine), contexte « Créer dans — … », deux actions
  Nouveau fichier / Nouveau dossier.

Fermeture : clic extérieur (capture de phase), touche Échap, ou action
exécutée. Un seul popover à la fois.

## 11. Mutations — toujours sur le nœud concerné

**Aucune mutation ne recharge l'arbre entier.** Chaque action insère,
retire, renomme ou re-parente **le nœud visé**, rafraîchit sa ligne (et
celle du parent), et laisse le reste intact.

- **Créer** (fichier ou dossier) : déplie le dossier cible, insère un
  éditeur **inline dans la liste du nœud** (icône de type, champ avec
  repère selon le type `nom-fichier.kt` / `nom-dossier`, boutons valider
  vert / annuler, Enter = valider, Échap = annuler). À la validation :
  insertion triée (§ 6.4), animation d'apparition (0,22 s, fondu + montée
  de 5 px), flash de la ligne, défilement doux vers le nœud, sélection, et
  **un fichier créé s'ouvre dans l'onglet actif** (ADR 0030 « créer →
  éditer »). Snackbar « Créé — *nom* » + chemin.
- **Renommer** : éditeur inline **à la place de la ligne du nœud** (valeur
  = nom courant, sélectionné). L'onglet ouvert suit (URI migrée, ADR 0030)
  — son libellé change, le contenu reste. Snackbar « Renommé — ancien →
  nouveau ».
- **Supprimer** : confirmation (§ 10.5) ; à la confirmation, ferme les
  onglets touchés (l'élément et, pour un dossier, tout descendant ouvert),
  la sélection remonte au parent, disparition animée (0,22 s, fondu +
  glisse gauche de 8 px), puis re-rendu de la liste du parent. Snackbar
  « Supprimé — *nom* » + **Annuler** qui restaure l'élément à sa place
  (tri) et rouvre ses onglets, y compris l'onglet actif s'il n'y en a
  plus.
- **Copier** : presse-papiers `{ noeud, mode: copier }`, snackbar « Copié
  dans le presse-papiers » + chemin. **Coller** dans un dossier : clone
  complet, nom suffixé « (copie N) » dès la deuxième collision (insensible
  à la casse), insertion triée + flash.
- **Couper** : presse-papiers `{ noeud, mode: couper }`, ligne grisée à
  50 % et nom barré. **Coller** : retire de l'ancien parent, insère dans
  la cible (suffixe anti-collision identique), presse-papiers vidé,
  snackbar « Déplacé dans *dossier* ».
- **Coller** interdit si la destination est l'élément ou un descendant de
  l'élément (snackbar « Collage impossible — la destination est dans la
  source ») ; « Déjà dans ce dossier » si le parent est déjà la cible en
  mode couper.
- **Déplacer vers…** : par chemin saisi (§ 10.5), même logique
  d'insertion, la sélection suit l'élément déplacé.

### 11.1 Validation des noms (éditeur inline)

Nom obligatoire ; `.` et `..` réservés ; aucun séparateur `/` ou `\` ;
64 caractères maximum ; unicité dans le dossier parent (insensible à la
casse). Refus = secousse du champ (0,28 s, ±3 px) + maintien du focus —
jamais de fermeture silencieuse.

## 12. Barre presse-papiers

Sous l'arbre (au-dessus du rail), **conditionnelle** : fond `vert-doux`,
bordure haute 1 px `rgba(111,196,122,.25)`, padding `7 px 11 px`, texte
11,5 px `#a9d8ae`, icône presse-papiers, « Presse-papiers : *nom*
(copier|couper) » en mono `#cfe9d1` graisse 600, bouton « Vider » souligné
au survol à droite. Se masque quand le presse-papiers est vidé.

## 13. Poignée de redimensionnement ⋮

**Bouton vertical** collé au bord droit du tiroir, à mi-hauteur :
26 × 78 px (il **déborde à moitié** : `right: -13 px`), rayon 13 px, fond
`fond-surface-2`, bordure 1 px `#38404d`, ombre `ombre-1`, trois points
de 3,5 px écartés de 4 px (`texte-2`), curseur `col-resize`, `touch-action:
none`.

- **Glissement horizontal** = redimensionnement du tiroir, bornes
  **45 % / 98 %** de la largeur d'écran.
- **Aimants** à 55 %, 69 %, 85 %, 98 % (tolérance ±12 px, appliqués au
  relâchement).
- Pendant le glissement : bordure `accent-fort`, fond `#2c3644`,
  agrandissement ×1,08, points `accent` ; le tiroir anime sa largeur
  (0,18 s) hors glissement.
- **Pastille de taille** au-dessus de la poignée : mono 11 px `accent`,
  fond `#10141b`, bordure `bordure`, rayon 7 px, padding `4 px 9 px`,
  affichée pendant le glissement puis fondu (0,12 s) 380 ms après le
  relâchement — libellé « NN % ».

## 14. Rail de fragments (bas du tiroir)

Hauteur 66 px, fond `#171a21`, bordure haute `bordure-fine`, padding
`6 px 4 px 4px`. Quatre destinations, flex 1 : colonne icône (20 px) +
libellé 10 px / 600, écart 4 px, `texte-3` ; survol `texte-2`. Destination
active : `accent` + halo (encoches inset `-6 px -8 px`, rayon `r-m`, fond
`accent-doux`) autour de l'icône. Ordre : **Fichiers, Recherche, Git,
Terminal**. Le changement de fragment affiche le fragment correspondant
(entête propre, corps propre) — le rail lui-même est commun au tiroir.

## 15. Snackbar

Centré en bas, à 82 px au-dessus du bas de l'écran (juste au-dessus du
rail),
fond `#2b323e`, bordure `#3a4351`, rayon 12 px, ombre `ombre-2`, padding
`10 px 8 px 10 px 16 px`, texte 12,5 px, largeur max 90 %. Apparition :
fondu + montée de 18 px, 0,22 s. Masquage automatique après **4 600 ms**.
Chemin en seconde ligne (mono 10,5 px `texte-3`). Action unique à droite
(« Annuler », `accent`, graisse 700, padding `4 px 8 px`) quand une
annulation existe.

## 16. Récapitulatif des durées d'animation

| Animation | Durée | Courbe |
|---|---|---|
| ouverture/fermeture tiroir | 0,26 s | `cubic-bezier(.4,.1,.2,1)` |
| redimensionnement tiroir | 0,18 s | ease |
| repliage de dossier | 0,20 s | ease |
| rotation chevron | 0,16 s | ease |
| changement point d'état | 0,16 s | ease |
| apparition de nœud | 0,22 s | ease (fondu + -5 px) |
| disparition de nœud | 0,22 s | ease (fondu + -8 px) |
| flash de ligne mutée | 1,10 s | ease |
| popover | 0,15 s | ease (scale .94→1) |
| secousse validation | 0,28 s | par à-coups (±3 px) |
| snackbar | 0,22 s | ease |
| rotation Actualiser | 0,65 s | ease |
| survols génériques | 0,12–0,15 s | ease |

## 17. Gestes et interactions

- **Clic (tap) dossier** : sélection + dépliage/repliage.
- **Clic (tap) fichier** : sélection + ouverture en onglet **actif** (crée
  l'onglet si absent).
- **Appui long 420 ms** (annulé au-delà de 9 px de déplacement) sur une
  ligne : popover d'actions **ancré au point de contact** + vibration
  12 ms.
- **Clic droit** : même popover (pratique souris de la maquette).
- **Croix d'onglet** : fermeture ; l'onglet actif passe au voisin le plus
  proche.
- **Échap / clic extérieur** : ferme le popover courant.
- **Bouton menu AppBar** : ouvre/ferme le tiroir.

## 18. Données de démonstration de la maquette

Arbre projet : structure réaliste du dépôt (app, core/*, feature/*,
docs/adr, gradle, build-logic, tools ; fichiers racine build.gradle.kts,
settings.gradle.kts, gradle.properties, gradlew(.bat), version.properties,
.gitignore, README.md, CHANGELOG.md). Dossiers dépliés au départ :
racine, `feature`, `feature/editor`, `feature/terminal`, `docs`,
`docs/adr`. Onglets ouverts au départ : `EditorActivity.kt` (actif) et
`TerminalActivity.kt` (inactif) — le premier porte donc un point vert
plein, le second un point vert creux. Au démarrage, snackbar d'astuce
après 1,1 s : « Astuce : appui long (ou clic droit) sur une ligne pour
ouvrir le menu d'actions ».

## 19. Transposition Android (implémentation)

- `feature/editor` : `ExplorateurFragment` (+ fragments Recherche, Git,
  Terminal — aperçus), `FragmentContainerView` dans le tiroir de
  `activity_editor.xml` (ADR à rédiger : « tiroir à fragments ») ;
  suppression de l'entête commun actuel.
- Poignée : vue dédiée sur le bord du tiroir, redimensionnement du
  `DrawerLayout` par bornes/aimants du § 13 (état mémorisé par session).
- Arbre : `RecyclerView` (adapter existant étendu) avec guides dessinés
  (drawables par indentation), chevron, point d'état (états view-state),
  icônes `core/ui/IconesFichiers` complétées au mapping du § 8.
- Bascule Projet/Privé : `MaterialButtonToggleGroup` ou équivalent maison
  stylé au § 5 ; la source privée passe par un adaptateur dédié du port
  `FileSystem` (`filesDir`, `cacheDir`, `codeCacheDir`, `databases`,
  `shared_prefs` — **aucun `File` direct**, ADR 0003 inchangée).
- Popover : `PopupWindow` maison ancré aux coordonnées du appui long
  (algorithme du § 10.2 : clamp écran, retournement, flèche), layout
  custom — **jamais** de menu système.
- Mutations : à rebaser sur l'existant ADR 0030 (rafraîchissement ciblé,
  URI migrée, « créer → éditer ») ; presse-papiers d'arbre = simple état
  mémoire (pas le presse-papiers système) ; snackbar Material avec action
  Annuler (suppression).
- Onglets / points d'état : l'état des onglets de l'éditeur pilote le
  point d'état des nœuds (vert creux/plein) — réutiliser le flux existant
  `EtatEditor`.
- Points d'attention : thème jour (tokens M3 équivalents), grandes
  polices (ELTS), profondes arborescences (perf. du RecyclerView, guides
  dessinés, PAS de vues imbriquées), appui long vs défilement
  (distinction 9 px), RTL (chevrons et guides miroirs).

## 20. Critères d'acceptation

1. Aucun crochet/chevron fantôme : dossiers = chevron + icône + nom,
   fichiers = point + icône + nom, à la pixel près sur la maquette.
2. Bascule Projet/Privé **exclusive**, sélection réinitialisée, sous-titre
   et snackbar conformes au § 5 ; onglets éditeur inchangés.
3. Popover ancré au doigt (appui long), flèche, retournement près du bas,
   chemin contextuel exact par action, actions désactivées/dangereuses.
4. Chaque mutation (créer, renommer, supprimer+annuler, copier, couper,
   coller, déplacer) s'applique **au nœud**, insertion triée ADR 0027,
   flash, défilement, onglet suit (créer → ouvrir, renommer → URI migrée).
5. Points d'état : 4 états + précédence du § 7 vérifiés en direct sur les
   onglets ouverts.
6. Poignée ⋮ : bornes 45–98 %, aimants 55/69/85/98, pastille de taille,
   glissement fluide.
7. Zéro rechargement d'arbre entier lors d'une mutation (vérifié en
   journal : une seule re-liaison de nœud par mutation).
8. Vérification standard verte (AGENTS.md) : qualité + `assembleDebug`.
9. Tests : tri, validation inline, anti-collision « (copie N) », coller
   interdit source→descendant, restauration après Annuler, bascule
   exclusive, état des onglets → points.

