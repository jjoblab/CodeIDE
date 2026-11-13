# ADR 0133 — Aperçu : typographie, unités et cibles tactiles du volet Attributs

- **Statut** : proposé — les points 1 et 2 découlent de la demande « design identique » ; **le point 3
  (cibles tactiles) est une dérogation bornée à confirmer par le propriétaire** avant PE11b.
- **Date** : 2026-10-18
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0131, ADR 0132. **S'appuie sur** : `core:ui` `touch_target_min` (48 dp, accessibilité,
  section 12.1 du prompt maître).
- **Contexte** :
  - La maquette utilise trois familles de police sur sa **page** (sans, mono, titres) ; l'écran Attributs
    lui-même utilise **sans 11-13 px** et **mono 9-12 px**, en graisses 400/500/600.
  - Le dépôt n'embarque aucune police ; l'app s'appuie sur la police système.
  - La maquette est **dense** : segments 26 px, drapeaux 25 px, en-tête de carte 30 px, bouton ↺
    25 × 20 px. L'app impose 48 dp (`touch_target_min`) pour ses écrans courants ; les cartes actuelles
    de l'aperçu sont déjà à `minHeight 48dp`, ce qui est une des causes de l'écart visuel.

## Décision

1. **Polices** : *sans* = police système (Roboto) ; *mono* = `monospace`. **Pas de police embarquée.**
   Les tailles (en `sp`), graisses (`sans-serif-medium` pour 500/600 ; mono 600 = gras ; mono 500 =
   normal), interlignes (`lineHeight` sans préfixe `android:`) et interlettrages sont **exacts** (table
   `PREVIEW_UI.md` § 6). Aucun `textFontWeight` (API 28, alerte lint sous minSdk 26).
2. **Unités** : 1 px de la maquette = **1 dp** ; px de texte → **sp**. Une bordure CSS extérieure au
   padding s'ajoute au padding Android (le trait du `<shape>` est intérieur) ; les dimens
   `apercu_champ_pad_*` l'incluent.
3. **Cibles tactiles du volet Attributs** : plancher de **24 × 24 dp** (WCAG 2.2, critère 2.5.8, niveau
   AA) à la place de 48 dp, **limité au volet Attributs** (outil d'édition dense, équivalent d'un
   inspecteur de propriétés). Compensations : en-têtes de carte pleine largeur (396 dp) ; espace de 9 dp
   entre contrôles ; `contentDescription` sur chaque contrôle sans texte ; bouton ↺ à
   `minWidth/minHeight 24dp` ; `Chip` avec `ensureMinTouchTargetSize=false` (sinon les hauteurs de la
   maquette ne sont pas tenues). **Le reste de l'aperçu et de l'app garde 48 dp.**

## Options considérées

### Option A — Polices système, dérogation 24 dp bornée (retenue)
| Dimension | Évaluation |
|---|---|
| Fidélité | Tailles/espacements exacts ; seuls les dessins de glyphes diffèrent |
| Coût | Nul (aucun fichier de police, aucune licence à notifier) |
| Accessibilité | Conforme WCAG 2.2 AA ; en deçà de la recommandation Material (48) sur ce seul volet |

### Option B — Embarquer deux polices libres (sans + mono) via `res/font`
Glyphes identiques à la maquette. Coût : poids de l'APK (quelques centaines de Ko), licence à inscrire
dans `THIRD_PARTY_NOTICES.md`, rendu à vérifier sur API 26. **Écartée pour l'instant** ; à rouvrir si
l'écart de glyphes gêne à la revue.

### Option C — Garder 48 dp partout
Conforme à la règle générale mais **incompatible** avec « identique à la maquette » (cartes +18 dp,
segments +22 dp) et avec le mode « Tous ». **Écartée.**

### Option D — Zone tactile étendue sans changer le visuel (`TouchDelegate`)
Tient les deux exigences mais complexifie chaque contrôle (délégués, chevauchements entre contrôles à
9 dp d'écart). **Écartée** pour la complexité ; applicable ponctuellement si un test d'usage montre des
erreurs de pointage.

## Analyse des compromis
On accepte une cible plus petite sur un écran d'expert contre la densité qui rend le mode « Tous »
utilisable sur un téléphone. Le critère WCAG 2.2 AA est tenu ; la recommandation Material ne l'est pas
sur ce volet, et c'est explicitement noté.

## Conséquences
- **Plus facile** : reproduire les cotes de la maquette sans compensation par contrôle.
- **Plus difficile** : pointage au doigt moins tolérant sur segments et drapeaux ; à mesurer sur appareil.
- **À revoir** : après un test d'usage sur appareil (PE11b), rouvrir si des erreurs de pointage
  apparaissent (option D) ; option B si l'écart de glyphes est jugé gênant.

## Actions
1. [ ] Le propriétaire confirme (ou refuse) le point 3 ; en cas de refus, PE11b passe à 48 dp et la
   fidélité en hauteur est dégradée de façon documentée.
2. [ ] Styles de § 6 posés ; `Widget.CodeIDE.Apercu.Texte` sur tout `TextView`.
3. [ ] Revue sur appareil : segments, drapeaux, ↺, en-têtes de carte.
