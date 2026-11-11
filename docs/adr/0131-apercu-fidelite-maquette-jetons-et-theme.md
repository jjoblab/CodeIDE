# ADR 0131 — Aperçu : fidélité à la maquette, jetons exacts et thème dédié

- **Statut** : accepté (propriétaire, 2026-10-18 — retour : « le design ne ressemble pas à la maquette,
  l'écran Attributs doit la reproduire à l'identique »)
- **Date** : 2026-10-18
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0002 (vues XML, pas de Compose), ADR 0008 (couleurs dynamiques à la demande),
  ADR 0059 (jetons de thème partagés). **Précise** : `docs/PREVIEW.md` § 3.4.1 / § 3.4.2 (écarts
  intentionnels PE9/PE10), qui restent valables.
- **Numéro** : 0127 était libre au commit `6a9325b` ; si un autre ADR l'a pris entre-temps, renuméroter
  les trois ADR de cette série (0127-0129) et les renvois de `docs/PREVIEW_UI.md`.
- **Contexte** (vérifié dans `feature/preview/src/main/res/`, v0.135.0) :
  - La maquette `docs/mockups/apercu-xml.html` définit **27 jetons de couleur** (bloc `:root` + variante
    sombre) dont des **neutres propres** (surface blanche `#FFFFFF`, surface 2 `#EEEEF7`, trait `#D6D5E0`,
    gris doux `#5B5C66`, accent bleu `#1E6EC8`, violet « nouveau » `#7C3AED`, etc.).
  - Le module les approche par les **rôles du thème de l'app** (`colorSurface` = `#F9F9FF`,
    `colorOutlineVariant` = `#C6C5D0`, `colorSecondaryContainer` = `#DDE1F9`…) : les teintes dérivent
    partout (champs bleutés, cartes grisées, traits plus foncés).
  - Les jetons `preview_*` existants contiennent deux **erreurs** (ambre `#B8860B` au lieu de `#9A6700` ;
    fond de badge nuit translucide au lieu de `#3A2A66`) et **n'ont pas de variante nuit** pour les
    couleurs de problèmes.
  - Les layouts écrivent ~70 littéraux `dp` et appellent `textAppearanceLabel*` du thème Material :
    impossible de corriger l'apparence globalement.
  - Retour propriétaire de PE9 : « mêmes thèmes/palettes que l'app » — à **conserver**.

## Décision

1. **La maquette est la source de vérité visuelle.** En cas de divergence entre la maquette, le code et
   `docs/PREVIEW_UI.md`, la maquette gagne ; le document et le code sont corrigés. Tout écart voulu
   s'ajoute à `PREVIEW.md` § 3.4.x **avec sa date et son motif** (comme PE9/PE10).
2. **Un attribut de thème par jeton** (`apercuFond`, `apercuSurface`, `apercuTrait`, `apercuNouveau`…,
   `values/attrs.xml`) et **une couleur par jeton** (`apercu_*`, `values/colors.xml` +
   `values-night/colors.xml`). Les layouts, drawables et styles n'écrivent que `?attr/apercu*`
   (règle 3 : « un écran ne référence jamais une couleur directement »).
3. **Thème `Theme.CodeIDE.Apercu`**, enfant de `Theme.CodeIDE`, posé sur `PreviewActivity`. Il (a) câble
   les `apercu*`, (b) **recâble les rôles Material 3** lus par les composants (surface, texte,
   variante de texte, contours, conteneur secondaire, primaire, erreur) sur les jetons de la maquette.
   Primaire, fond et erreur ont **déjà les mêmes hexadécimaux** que l'app : le thème ne change que
   les neutres.
4. **Couleurs dynamiques (Android 12+) non appliquées** à l'aperçu : `PreviewActivity` n'appelle pas
   `applyDynamicColorsIfAvailable()`. Une couleur dynamique remplacerait le primaire mais pas les
   neutres fixes, ce qui casserait l'harmonie de la maquette.
5. **Migration des jetons existants** (table dans `PREVIEW_UI.md` § 5) : `preview_probleme_*` et
   `preview_section_badge_*` sont supprimés au profit des `apercu_*` ; `preview_bulle_*` /
   `preview_palette_icone` deviennent des alias ; `preview_menu_fond` est **conservé** (translucidité
   demandée, écart documenté).
6. **Test de parité en CI** (`JetonsMaquetteTest`, JVM pur) : compare `values/colors.xml` et
   `values-night/colors.xml` à `docs/mockups/ref/jetons-maquette.json` (extrait de la maquette), vérifie le
   câblage `attrs.xml` ↔ `themes.xml` et l'égalité primaire/erreur avec `core:ui`. Un jeton modifié d'un
   seul côté fait échouer la chaîne.

## Options considérées

### Option A — Thème dédié + attributs par jeton (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne : 1 fichier d'attributs, 1 thème, 2 fichiers de couleurs |
| Coût | Une migration des `preview_*` ; aucune dépendance |
| Fidélité | Exacte ; les composants Material suivent sans code |
| Évolutivité | Changer une teinte = une ligne ; le test garde la maquette |
| Familiarité | Élevée : même mécanisme que ADR 0059 (jetons exposés par le thème) |

### Option B — Corriger `Theme.CodeIDE` pour toute l'app
Alignerait `colorSurface` & co. sur la maquette partout. **Écartée** : change l'apparence de tous les
écrans pour satisfaire un seul, risque de régressions visuelles hors périmètre.

### Option C — `@color/apercu_*` directement dans les layouts (sans attributs)
Plus court, mais contourne la règle « rôle via le thème », interdit un futur thème alternatif et
disperse les changements. **Écartée**.

### Option D — Palette dynamique + neutres de la maquette
Incohérente (voir décision 4). **Écartée**.

## Analyse des compromis
On échange un second jeu de couleurs dans le module contre une fidélité mesurable. Le coût de
maintenance est borné par le test de parité ; le risque principal (un composant Material qui lit un
rôle non recâblé, ex. `colorSurfaceContainer*`) se détecte à la revue visuelle (`PREVIEW_UI.md` § 12.3) et
se corrige par une ligne de thème.

## Conséquences
- **Plus facile** : corriger une teinte partout ; thème clair/nuit sans layout `-night` ; revue par
  comparaison de captures.
- **Plus difficile** : l'aperçu ne suit plus Material You ; deux familles de couleurs coexistent (app
  / aperçu) — c'est assumé et documenté.
- **À revoir** : si le propriétaire veut des couleurs dynamiques dans l'aperçu, il faudra rendre les
  neutres dérivés du primaire (nouvel ADR).

## Actions
1. [ ] Copier `values/{attrs,colors,dimens,styles,themes}.xml` et `values-night/colors.xml` de
   `docs/mockups/ref/ressources/` ; poser le thème sur `PreviewActivity` (manifeste du module).
2. [ ] Migrer les `preview_*` (table `PREVIEW_UI.md` § 5), supprimer les doublons.
3. [ ] `JetonsMaquetteTest` + ligne dans `docs/PREVIEW.md` (§ 3.4) renvoyant à cet ADR.
4. [ ] Capture clair/nuit avant/après jointe au compte rendu du chantier (PE11a).
