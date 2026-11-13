# ADR 0127 — UI : cohérence visuelle, composants partagés et garde-fou à cliquet

- **Statut** : accepté (GO propriétaire du 2026-10-20 — lots livrés depuis : UI-1 v0.158.0,
  UI-2 v0.159.0, UI-3 v0.160.0, UI-4 v0.163.0, UI-5 v0.164.0, UI-6 v0.165.0,
  UI-7 v0.166.0 — la MISSION UI est CLOSE, le cliquet poursuit la dette
  résiduelle inscrite ; la mission LSP sur cet ADR est close)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **S'appuie sur** : ADR 0059 (jetons de thème, Paramètres), 0060 (palettes/couleurs dynamiques),
  0002 (vues XML, pas de Compose). **Données** : `docs/UI_AUDIT_COHERENCE.md` (mesures C1–C10).
- **Contexte** : `core:ui` fournit déjà un thème unique, des jetons de couleur/forme/typographie et
  quelques styles `Widget.CodeIDE.*`. Mais 623 `dp` littéraux, 117 tailles de texte littérales
  (dont des demi-tailles), 4 recettes de cartes, 4 « dialectes » de boutons, 15 `Toast` face à
  9 `Snackbar` et ~25 rangées de réglages recopiées à la main montrent que le système **n'est
  ni complet ni imposé**. Deux nouveaux ensembles d'écrans (IA, réglages LSP) arrivent : sans
  règle vérifiable, ils ajouteraient leurs propres variantes.

## Décision

### 1. Principes
1. **`core:ui` est l'unique source** des jetons, styles et composants visuels. Une feature
   n'invente ni couleur, ni dimension, ni taille de texte, ni style de widget.
2. **Zéro littéral dans le code nouveau** ; le code existant converge par un **cliquet** (§5) :
   la dette ne peut que diminuer.
3. Un besoin visuel absent du système = **on ajoute un jeton/composant à `core:ui`**
   (avec aperçu et KDoc), on ne le contourne pas localement.
4. Couleurs : **rôles de thème uniquement** (`?attr/color…`), jamais `#hex` ni `@color/…`
   brut dans un layout ; les teintes sémantiques (succès/avertissement/erreur) passent par les
   jetons existants. Thèmes clair, sombre et couleurs dynamiques doivent tous rester corrects.

### 2. Jetons à ajouter dans `core:ui`
| Jeton | Valeur | Raison (mesure) |
|---|---|---|
| `spacing_2xs` | 2dp | 60 usages littéraux ; filets, séparateurs, icônes serrées |
| `spacing_md_compact` | 12dp | 55 usages littéraux ; entre `sm` (8) et `md` (16) |
| `TextAppearance.CodeIDE.Micro` | 10sp, `Medium` | rail du tiroir, badges, étiquettes d'outils (10–10.5 sp) |
| `TextAppearance.CodeIDE.SectionLabel` | `TitleSmall`, `colorOnSurfaceVariant` | libellé de carte de réglages (recopié aujourd'hui) |
| `icon_size_md` / `icon_size_sm` | 24dp / 20dp | icônes de rangée et de rail (littéraux `24dp`/`20dp`) |

Échelle **officielle** d'espacement : `2xs 2 · xs 4 · sm 8 · md_compact 12 · md 16 · lg 24 · xl 32`.
Aucune autre valeur en marge/padding.

### 3. Styles et composants partagés
| Élément | Contrat | Remplace |
|---|---|---|
| `Widget.CodeIDE.Card` | carte **remplie**, contour 1 dp `colorOutlineVariant`, forme `Medium`, élévation 0 | 4 recettes (C4) |
| `Widget.CodeIDE.Button.Outlined` | variante standard (la `Compact` reste pour les barres) | `?attr/materialButtonOutlinedStyle`, `Widget.Material3.Button.OutlinedButton` |
| `Widget.CodeIDE.Chip.Filter` / `.Assist` / `.Input` | chips thématisées | `Widget.Material3.Chip.*` brut |
| `Widget.CodeIDE.Button.Icon` (existant) | bouton icône 48 dp | `ImageButton` nu |
| **`RangeeParametre`** (vue de `core:ui`) | attributs `icone`, `titre`, `sousTitre`, `type` = `chevron` \| `interrupteur` \| `valeur` \| `aucun`, `bientot` ; expose `setOnCheckedChange`, `setValeur`, état désactivé | rangées recopiées (C6) ; absorbe `ligne_parametre.xml` |
| **`VueEtat`** (vue de `core:ui`) | icône, titre, message, action optionnelle ; variantes `vide`, `erreur`, `horsLigne`, `chargement` ; jetons `state_*` | 5 variantes d'états vides (C7) |
| **`IndicateurEtat`** (pastille) | quatre états `ok`/`avertissement`/`erreur`/`neutre`, texte + couleur **et forme/icône** (pas la couleur seule) | pastilles ad hoc (LSP, IA, indexation) |
| `Retour.snackbar(vue, message, action?)` | `Snackbar` thématisé + annonce d'accessibilité | `Toast` (C5) |
| `EnteteSection` | style pour le libellé de section d'un écran de réglages | recopie |

Chaque composant est livré avec : KDoc FR, `contentDescription`/état d'accessibilité,
aperçu dans `docs/mockups/ui/` (clair + sombre), test d'instrumentation léger ou test
Robolectric de rendu quand c'est utile.

### 4. Table de correspondance pour la migration (arrondi vers le jeton le plus proche)
| Littéral | → | Littéral | → |
|---|---|---|---|
| 2 | `2xs` | 10 | `sm` (8) ou `md_compact` (12) selon l'alignement voisin |
| 3, 5 | `xs` (4) | 11 | `md_compact` |
| 4 | `xs` | 12 | `md_compact` |
| 6 | `xs` (4) ou `sm` (8) selon l'alignement voisin | 14 | `md_compact` ou `md` |
| 7, 9 | `sm` (8) | 16 / 24 / 32 | `md` / `lg` / `xl` |
| **sp** 9.5/10/10.5 | `Micro` | **sp** 11/11.5 | `LabelSmall` |
| **sp** 12/12.5 | `LabelMedium` ou `BodySmall` (selon rôle) | **sp** 13 | `BodySmall` (12) ou `BodyMedium` (14) — **ne jamais inventer 13** |

Quand l'arrondi décale la lecture de plus de 2 dp, on crée un jeton dédié (§1.3).
**Exceptions assumées** : taille de police du code (réglage utilisateur de l'éditeur), surfaces
de rendu (canvas, aperçu XML rendu), icônes vectorielles (`fillColor`), `1dp` de filet/contour,
`0dp` de contrainte.

### 5. Garde-fou `checkUiTokens` (cliquet)
Tâche Gradle de `build-logic` (même famille que `checkModuleDependencies`), scan **texte** des
`res/layout*`, `res/drawable*` (hors `ic_*`) et des sources Kotlin :

| Règle | Interdit |
|---|---|
| R1 | `dp` littéral dans `layout_margin*` / `padding*` (hors `0dp`, `1dp`) |
| R2 | `android:textSize` littéral |
| R3 | `#RRGGBB[AA]` hors `tools:` dans layouts et drawables non-icônes |
| R4 | `style="@style/Widget.Material3.*"`, `?attr/materialButton*Style`, `Widget.MaterialComponents.*` |
| R5 | `<Button>`, `<ImageButton>` (**fins de ligne comprises** — le XML formaté met la balise seule en fin de ligne ; **un style maison `@style/Widget.CodeIDE.*` sur la balise exonère** : c'est le composant dédié, ADR §3 — p. ex. `Widget.CodeIDE.Apercu.BoutonIcone`, `parent=""` issu de la maquette, ImageButton par nature), `androidx.cardview.widget.CardView` |
| R6 | `Toast.makeText` dans le code Kotlin — **QUALIFIÉ et MULTI-LIGNES compris** (`android.widget.Toast\n.makeText(…)` n'échappe plus) |
| R7 | `cardCornerRadius` / `cardElevation` littéraux sur une `MaterialCardView` |

- **Baseline** versionnée `config/ui-baseline.json` : `{ "chemin": { "R1": n, … } }`, générée une
  fois (état actuel). La tâche **échoue** si un fichier dépasse sa baseline ou si un **nouveau
  fichier** a la moindre occurrence. `-Pui.baseline.ratchet` réécrit la baseline à la baisse
  (jamais à la hausse) ; toute hausse manuelle est refusée en revue.
- Sortie lisible : `fichier:ligne: R1 — utiliser @dimen/spacing_*`.
- **Durcissement UI-7 (v0.166.0) — règle de bannissement** : le scan ligne à
  ligne laissait ÉCHAPPER deux formes de code formaté, la règle ne « tirait »
  jamais en pratique :
  - **R5** : dans le XML formaté (un attribut par ligne), `<ImageButton` vit en
    fin de ligne — la règle attrape désormais la fin de ligne, en EXONÉRANT
    la balise porteuse d'un style maison `@style/Widget.CodeIDE.*` (composant
    dédié, §3) ; le regard s'arrête à la ligne qui ferme la balise (le style
    d'une balise suivante n'exonère jamais).
  - **R6** : l'appel QUALIFIÉ multi-lignes (`android.widget.Toast` puis
    `.makeText` à la ligne) échappait à `contains("Toast.makeText")` ; le
    texte ENTIER est désormais parcouru, espacements traversés.
  - **Inscription de la dette rendue visible** : le durcissement a fait
    apparaître 13 occurrences R5 (icônes sans composant maison — audit C3,
    12 fichiers listés au CHANGELOG v0.166.0) et 2 occurrences R6 : elles
    entrent à la baseline **au constat exact** (comme l'initialisation
    UI-2), gelées par le cliquet ; leur migration (avec capture
    avant/après sur appareil) est un lot futur.
  - **Les DEUX exceptions R6 assumées** (les seuls `Toast` survivants, hors
    dossier de migration car un Snackbar **meurt avec la vue**) :
    1. `EditorActivity` — l'annonce « build continue en arrière-plan » part
       AVANT `finish()` (décision §2.1c de `docs/CYCLE_DE_VIE.md`, ratifiée
       par le propriétaire) et doit SURVIVRE à l'écran ; la notification
       `ToolingService` reste le détail durable.
    2. `CopieurPressePapiersAndroid` (`core:terminal-runtime`) — confirmation
       de copie OSC 52 sur Android < 13 : module SANS UI (contexte
       d'application seul), copie possible SANS écran visible (sessions dans
       `TerminalService`), cible pre-33 au cœur de l'audience (targetSdk 28,
       ADR 0045).
    Chacune est plafonnée à R6=1 pour toujours (toute remontée échoue le
    build) ; TOUT nouveau `Toast`, n'importe où, échoue le build.
- Ajoutée à la chaîne de vérification racine et à la CI ; le moteur de règles est testé
  (cas passants/échouants par règle).

### 6. Revue UI en 10 points (checklist de PR)
Jetons seuls · composants partagés · 4 états gérés (chargement/vide/erreur/contenu) · zones
tactiles ≥ 48 dp · contraste ≥ 4,5:1 · clair/sombre/dynamique · 360 dp et paysage ·
police système ×1,3 · TalkBack (rôles, états, annonces) · FR **et** EN.

## Options considérées

### Option A — Système complété + cliquet automatique (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (un lot `core:ui`, une tâche Gradle, migrations module par module) |
| Coût | Étalé ; chaque lot est petit et vérifiable |
| Scalabilité | Le cliquet protège tous les futurs écrans, IA et LSP compris |
| Familiarité | Élevée (même philosophie que `checkModuleDependencies`) |

### Option B — Migration « big bang » de tous les écrans
Résultat rapide sur le papier, mais plus de 700 littéraux à convertir sans filet visuel : risque de
régressions d'insets/clavier (ADR 0118). **Écartée** ; la migration se fait par lots avec capture.

### Option C — Documentation et revue humaine seulement
Zéro coût d'outillage, mais c'est le régime actuel qui a produit l'écart. **Écartée.**

## Analyse des compromis
A coûte un échafaudage et accepte une **baseline** (dette consignée, visible, décroissante) ; en
échange, plus aucune dérive silencieuse. La table d'arrondi (§4) assume que quelques écrans
bougeront de 1–2 dp : c'est le prix de l'unité, atténué par la capture avant/après.

## Conséquences
- **Plus facile** : un nouvel écran = assembler des composants ; revue réduite à « le cliquet est vert ».
- **Plus difficile** : un besoin inédit passe par `core:ui` (un lot de plus, mais réutilisable).
- **À revoir** : élargir le cliquet à `strings` (FR/EN) et aux `contentDescription` manquants.

## Actions
1. [x] Jetons, styles, `RangeeParametre`, `VueEtat`, `IndicateurEtat`, `Retour` dans `core:ui` (lot UI-1) — livré v0.158.0.
2. [x] `checkUiTokens` + `config/ui-baseline.json` + CI + tests du moteur de règles (lot UI-2) — livré v0.159.0.
3. [ ] Migrations par module (UI-3 à UI-7), baseline mise à jour à chaque lot — UI-3 v0.160.0 ✅
   (Settings hors baseline) ; UI-4 v0.163.0 ✅ (install, crash, terminal hors baseline —
   85 → 69 fichiers) ; UI-5 v0.164.0 ✅ (editor hors baseline — 354 occurrences → 0,
   69 → 34 fichiers ; nouveau style `Widget.CodeIDE.CircularProgressIndicator`,
   4 jetons dédiés editor pour les cotes composées) ; UI-6 v0.165.0 ✅
   (preview hors baseline — 16 fichiers, 30 occurrences → 0, baseline
   34 → 18 fichiers : constantes framework/couleurs nommées dans les 15
   drawables plafonnés, 3 orphelins supprimés, les 2 derniers Toast du
   module passent par le toast maison `ToastApercu` d'ADR 0134 D6) ;
   UI-7 v0.166.0 ✅
   (mission CLOSE — 7 Toast « en fenêtre » migrés vers `Retour.snackbar`
   (ai ×6, newproject ×1) après preview ×2 en UI-6 ; scanner DURCI : R5
   attrape les fins de ligne (13 occurrences C3 inscrites à la baseline,
   migration = lot futur) et R6 attrape le multi-lignes qualifié ; les DEUX
   exceptions R6 assumées sont plafonnées à 1 — voir §5).
4. [x] Mettre à jour `docs/CONVENTIONS.md` (liens vers cet ADR) et `AGENTS.md` (règle 15 :
   « aucun littéral UI ») — livré par UI-2 v0.159.0.
