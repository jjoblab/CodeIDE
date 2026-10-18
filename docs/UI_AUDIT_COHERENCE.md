# Audit de cohérence visuelle — CodeIDE v0.134.44

> Objectif : que l'application ressemble à l'œuvre d'**une seule main**, et que les
> nouveaux écrans (IA, LSP) n'aggravent rien. Les chiffres ci-dessous sont **mesurés par
> script sur les 10 modules à layouts** (commit `3c57ee4`) : 124 fichiers de layout,
> `feature/*` + `core/crash`. Date : 2026-10-17. Norme associée : ADR 0127.

## 1. Verdict

La **fondation est bonne** : un seul thème (`Theme.CodeIDE`), jetons de couleur, de forme, de
dimension et de typographie déjà présents dans `core:ui`, et une moitié des écrans les
respecte à 100 %. L'incohérence ne vient pas d'un manque de système mais d'**écrans écrits
avant ou à côté du système** (éditeur, aperçu, installation, rapport de plantage) et de
**composants jamais factorisés** (rangées de réglages, cartes, états vides, puces).

## 2. Ce qui est cohérent (à préserver)

| Constat mesuré | Résultat |
|---|---|
| `textAppearance` via attribut de thème ou style `CodeIDE` | **100 %** des 324 usages ; aucun style de texte Material brut |
| Boîtes de dialogue | **100 % Material** (`MaterialAlertDialogBuilder`/bottom sheets) ; aucun `AlertDialog` natif |
| Barres d'outils | **18/18** utilisent `Widget.CodeIDE.Toolbar` |
| Couleurs dans les layouts | **0 couleur en dur** (4 occurrences, toutes en `tools:` = aperçu IDE) |
| Modules entièrement tokenisés (0 `dp` littéral en marge/padding) | `diagnostics`, `home`, `newproject`, `onboarding` |
| Écran Paramètres | 83 % tokenisé ; pattern de carte + rangée clair (ADR 0059) |

## 3. Constats (classés par impact visuel)

### C1 — Échelle d'espacement contournée (🔴 le plus visible)
| Module | `@dimen` | `dp` littéraux | part tokenisée |
|---|---|---|---|
| feature/preview | 15 | **259** | 5 % |
| feature/editor | 131 | **236** | 36 % |
| feature/install | 3 | 42 | 7 % |
| feature/terminal | 19 | 38 | 33 % |
| feature/settings | 123 | 26 | 83 % |
| core/crash | 0 | 22 | 0 % |

Valeurs littérales : `8dp`×130, `6`×71, `4`×64, `10`×62, `2`×60, `12`×55, `16`×51,
**`7`×30, `9`×19, `3`×15, `11`×10, `5`×5, `14`×21** — soit ≈ 100 valeurs **hors de toute
grille** (7, 9, 11, 3, 5, 14). L'échelle actuelle des jetons (`4/8/16/24/32`) n'a ni `2`, ni
`12`, alors que ce sont deux valeurs réellement employées : c'est ce trou qui pousse à
écrire des littéraux.

### C2 — Tailles de texte en dur (🔴)
117 `android:textSize` littéraux : `feature/editor` **87**, `feature/preview` 22, `terminal` 7,
`core/crash` 1. Valeurs : `11sp`×40, `12`×20, `13`×14, **`11.5`×12, `12.5`×9, `10.5`×6, `9.5`×2**
(demi-tailles qui n'existent dans aucune échelle). Les écrans concernés ne suivent donc ni
l'échelle typographique Material ni les réglages d'accessibilité de la même façon.

### C3 — Boutons : quatre « dialectes » (🟠)
Sur 120 `MaterialButton` : `Widget.CodeIDE.Button.*` (69) ; `?attr/materialButtonOutlinedStyle`
(18, surtout Paramètres) ; **`Widget.Material3.*` brut (25 — `preview` et `core/crash`)** ;
**sans style (8)**. S'y ajoutent **29 `<Button>` simples** (`install` 14, `core/crash` 8,
`settings` 4, `editor` 3) et 11 `ImageButton` (5 sans style, 6 en style Material brut)
alors que `Widget.CodeIDE.Button.Icon` existe.
Il n'existe pas de variante **Outlined standard** CodeIDE (seulement `Outlined.Compact`), ce qui
explique la dérive vers l'attribut Material.

### C4 — Cartes : quatre recettes (🟠)
- Remplie + contour 1 dp + forme moyenne : `settings` ×16, `editor` ×2 ;
- rayon par jeton + élévation 0 : `newproject` ×9, `diagnostics` ×1 ;
- rayon **littéral `12dp`** + élévation 0 : `editor` ×1 ;
- **style par défaut** (élevée, ombre) : `home` ×3, `install` ×4, `crash` ×1, `settings` ×1.
Résultat : des cartes tantôt à ombre, tantôt à contour, tantôt à fond plat.

### C5 — Retours à l'utilisateur : Toast ET Snackbar (🟠)
15 `Toast` (crash, editor, newproject, preview, terminal) pour 9 `Snackbar`. Un `Toast` n'est
pas thématisé, n'offre pas d'action (« Annuler ») et n'est pas annoncé de la même façon par
TalkBack : même événement, deux présentations selon l'écran.

### C6 — Rangées de réglages recopiées à la main (🟠)
`ligne_parametre.xml` n'est utilisée que par l'écran maître. Dans les **9 sous-écrans**, les
~25 rangées « icône 24 dp + titre + sous-titre + contrôle » sont **reconstruites en XML** à
chaque fois (marges, `minHeight`, apparence du texte à la main). Chaque nouvel écran
(IA, LSP) serait une 10ᵉ et 11ᵉ copie : c'est exactement le mécanisme qui fabrique des
écrans « faits par des développeurs différents ».

### C7 — États vides/erreur : pas de composant partagé (🟠)
Jetons `state_icon_size/padding/spacing` présents, mais **aucun layout commun** : 5 modules
(`diagnostics`, `editor`, `home`, `newproject`, `terminal`) ont leur variante.

### C8 — Chips et puces sans style maison (🟡)
13 `Chip` en `Widget.Material3.Chip.Filter/Assist` brut ; aucune déclinaison
`Widget.CodeIDE.Chip.*`. Les puces de contexte IA et les pastilles d'état LSP en dépendront.

### C9 — Couleurs en dur dans des *drawables* de forme (🟡)
Hors icônes vectorielles (normales) : 13 fichiers (`preview` 6, `terminal` 3, `install` 2,
`editor` 2). La plupart sont transparent/blanc ; à corriger : `fond_tag_ambre` (`#38B8860B`
= teinte d'avertissement à relier au jeton `warn`), `fond_position_placement`,
`fond_mode_palette`, `fond_point_pastille` (**à vérifier** visuellement avant remplacement).

### C10 — Hors UI mais révélateur (🟡)
Les titres de `docs/LSP_KOTLIN.md` et `docs/LSP_XML.md` (dans CodeIDE **et** `codeide-lsp`)
citent encore une étude d'un projet tiers (règle 13) — voir `docs/LSP_REVUE_REGLAGES.md`.

## 4. Cartographie par module

| Module | Écrans (layouts) | Verdict | Priorité |
|---|---|---|---|
| `diagnostics`, `home`, `newproject`, `onboarding` | 7 · 7 · 19 · 8 | Conformes (cartes/états à unifier : C4, C7) | Basse |
| `settings` | 15 | Bon ; rangées à factoriser (C6) | **Haute** (modèle pour IA/LSP) |
| `terminal` | 6 | À aligner (C1, C2, C9) | Moyenne |
| `install`, `core/crash` | 3 · 1 | Écrits « à part » (C1, C3, C4, C5) | Moyenne (petits) |
| `editor` | 39 | Dense, volontairement compact mais non tokenisé (C1, C2) | **Haute** |
| `preview` | 44 | Quasi entièrement hors système (C1, C2, C3) | **Haute** (le plus gros écart) |

> Les surfaces **denses d'IDE** (barre de symboles, rail, badges) justifient des tailles
> inférieures à l'échelle Material (10–11 sp, 2 dp) : elles passent par des **jetons dédiés**
> (ADR 0127 §2), jamais par des littéraux.

## 5. Plan de remédiation (lots UI)

Numéros de version attribués à l'exécution (le dépôt est à 0.134.44 ; la mission IA n'a pas
commencé). **Règle : UI-1 et UI-2 d'abord** — aucun écran IA ou LSP ne se construit avant.

| Lot | Contenu | Portée | Critère de fin |
|---|---|---|---|
| **UI-1** | Jetons + styles + composants partagés dans `core:ui` (ADR 0127 §2-3) | `core:ui` seul | Tous les jetons/styles du tableau ADR 0127 existent, KDoc, aperçus dans `docs/mockups/` |
| **UI-2** | Garde-fou `checkUiTokens` à **cliquet** (baseline par fichier) branché sur la CI | `build-logic` | La commande échoue si un littéral **nouveau** apparaît ; la baseline ne peut que diminuer |
| **UI-3** | Settings : rangées → `RangeeParametre`, cartes → `Widget.CodeIDE.Card` | `feature:settings` | 0 rangée recopiée ; rendu identique (capture avant/après) |
| **UI-4** | `install`, `core/crash`, `terminal` : alignement complet | 3 modules | Baseline à 0 pour ces modules |
| **UI-5** | `editor` : spacing/typo/boutons/cartes, par **sous-écrans** (≤ 10 fichiers par commit) | `feature:editor` | Baseline à 0 ; checklist visuelle 360 dp, clair/sombre |
| **UI-6** | `preview` : idem | `feature:preview` | Baseline à 0 |
| **UI-7** | Toast → Snackbar partout + règle de bannissement | tous | 0 `Toast` |

**Méthode de migration** (par fichier, jamais en masse) : remplacer par le jeton le plus
proche selon la table de correspondance de l'ADR 0127 §4 ; **capture avant/après** sur appareil
(360 dp, clair et sombre) ; si un écart > 2 dp change la lecture, créer un jeton dédié plutôt
que de garder le littéral. Commit conventionnel `refactor(ui): …`, un module à la fois.

## 6. Amendements aux livrables IA déjà intégrés

Pour que l'IA ne crée pas de nouveaux « dialectes » (`docs/IA_SPEC_ECRANS.md`) :
- `vue_etat_ia` → **`VueEtat`** de `core:ui` (état vide/erreur/hors-ligne) ;
- `Widget.CodeIDE.IaCarte` → **`Widget.CodeIDE.Card`** ; `Widget.CodeIDE.IaPuce` → **`Widget.CodeIDE.Chip.Assist`** ;
- rangées des écrans Paramètres → IA → **`RangeeParametre`** ;
- `ia_*` dimens : ne créer que ce que les jetons ne couvrent pas, et **dans `core:ui`** ;
- retours (copie, erreurs transitoires) → **`Retour.snackbar(…)`**, jamais de `Toast`.
Les lots IA-2 et suivants dépendent donc de **UI-1 et UI-2**.
