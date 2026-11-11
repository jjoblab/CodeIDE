# Plan des prochaines étapes — CodeIDE post-v0.125.1

> **Document de planification** (v0.125.1, 14/10/2026). Fait suite à la
> livraison de la Phase B des prompts 1 et 2 (v0.124.2 → v0.125.0) et de
> la montée LayoutLib v3.0.0 (v0.125.1, ADR 0116 P0/P1/P2 résolus).
>
> Ce document recense les travaux restants identifiés dans les audits
> Phase A et les ADR, et propose une priorisation fondée sur l'impact
> utilisateur et l'effort.

## 1. État courant (v0.125.1)

### Missions terminées

| Mission | Versions | CI | ADR |
| --- | --- | --- | --- |
| Prompt 1 Phase B (EditorActivity E1-E7) | v0.124.2 → v0.124.7 | ✅ tous SUCCESS | 0118 |
| Prompt 2 Phase B (écran d'indexation B1-B8) | v0.124.8 → v0.125.0 | ✅ tous SUCCESS | 0117 |
| Montée LayoutLib v3.0.0 (PE4 P0/P1/P2) | v0.125.1 | en cours | 0116 (mis à jour) |

### Missions en cours / continues

- **PE4** (preview XML) : mission **continue** — tout nouveau tag LayoutLib
  déclenche un nouvel audit + bump éventuel. P0/P1/P2 résolus en v3.0.0,
  limitations restantes P3-P6 négligeables en preview statique.
- **code-editor 3.69.0** : montée livrée en v0.122.2. Les améliorations
  RECOMMANDÉES (B5-B15 : barre fil d'Ariane, search bar, fast-scroller,
  zoom mémoire, plis, drag bloc, gutter actions) ne sont pas encore
  consommées côté CodeIDE — chacune se pose en 3 lignes de code.

## 2. Travaux restants identifiés

### 2.1 Preview XML — exploitation de LayoutLib v3.0.0 (PE5+)

LayoutLib v3.0.0 apporte 3 nouvelles capacités qui ne sont pas encore
exposées côté CodeIDE :

| ID | Travail | Source | Effort | Impact |
| --- | --- | --- | --- | --- |
| **PE5a** | Bouton de bascule langue dans la barre de réglages de l'aperçu XML — appelle `ResourceResolver.setLanguage()` (P1 résolu) | ADR 0116 P1 | Faible (3 lignes + 1 bouton) | Moyen — la preview peut basculer FR/EN |
| **PE5b** | Test de non-régression : layout avec `<layer-list>`, `<ripple>`, `<inset>`, `<bitmap>` — vérifier qu'ils sont rendus (P0 résolu) | ADR 0116 P0 | Faible (1 test Robolectric) | Moyen — validation appareil |
| **PE5c** | Test de non-régression : layout ConstraintLayout avec chaînes, guidelines, barrières, ratios — vérifier que le rendu n'est plus approximatif (P2 résolu) | ADR 0116 P2 | Faible (1 test Robolectric) | Moyen — validation appareil |

### 2.2 Bibliothèque code-editor 3.69.0 — améliorations recommandées

Le guide `MISE-A-JOUR-CODEIDE.md` (dépôt `code-editor`) liste 11
améliorations RECOMMANDÉES (§4.1-4.15) qui ne sont pas encore consommées
côté CodeIDE. Chacune se pose en 3 lignes de code :

| ID | Amélioration | Lot code-editor | Effort CodeIDE | Impact |
| --- | --- | --- | --- | --- |
| **CE1** | Barre de fil d'Ariane à actions (B5) | 3.58.0 | 3 lignes | Haut — navigation rapide |
| **CE2** | Trois actions d'IDE (B6) | 3.60.0 | 1 appel | Moyen — actions contextuelles |
| **CE3** | Barre de recherche/remplacement (B10) | 3.64.0 | 3 lignes | Très haut — recherche fichier |
| **CE4** | Bande d'erreurs (B11) | 3.65.0 | 1 ligne optionnelle | Moyen — vue d'ensemble erreurs |
| **CE5** | Clé de zoom par type + numéros relatifs (B12) | 3.66.0 | 3 lignes | Moyen — zoom persistant |
| **CE6** | Item ✥ Move de la pill (B13) | 3.67.0 | 3 lignes | Faible — drag de bloc |
| **CE7** | Replier au niveau N + tout replier/déplier (B14) | 3.68.0 | 3 entrées de menu | Moyen — pliage de masse |
| **CE8** | Icônes de gouttière (B15) | 3.69.0 | 3 lignes | Faible — points d'arrêt |

### 2.3 Prompt 1 — tests appareil (E1-E12)

Les 12 recettes manuelles documentées dans `docs/TESTS_MANUELS.md`
(mission EditorActivity) nécessitent un appareil Android réel pour
valider le comportement (animation IME, barre de navigation par gestes
vs 3 boutons, contraste WCAG AA). Ces tests ne peuvent pas être
automatisés en Robolectric.

| ID | Recette | Validation requise |
| --- | --- | --- |
| **T1-T12** | 12 recettes E1-E12 de `docs/TESTS_MANUELS.md` | Appareil Android 8-15, portrait + paysage, thème clair + sombre |

### 2.4 Prompt 2 — tests appareil (indexation)

L'écran d'indexation (pastille + bottom sheet) nécessite aussi une
validation appareil :

| ID | Recette | Validation requise |
| --- | --- | --- |
| **I1** | Pastille de toolbar affiche les 4 états (construction animée, prêt, partiel, erreur) | Appareil réel |
| **I2** | Appui sur la pastille ouvre le bottom sheet de détail | Appareil réel |
| **I3** | Liste de travail se met à jour en direct pendant l'indexation | Appareil réel |
| **I4** | Bilan du dernier passage affiché au repos | Appareil réel |
| **I5** | Actions (Réindexer / Réessayer les échecs) fonctionnelles | Appareil réel |

### 2.5 Preview Compose (PC1-PC2)

La mission « Preview Compose » (`docs/PREVIEW.md` §4) dépend du socle
`:preview` (PE2, livré) et du LSP Kotlin (K3-K4, livré). Elle n'est pas
encore démarrée.

| ID | Travail | Dépend de | Effort | Impact |
| --- | --- | --- | --- | --- |
| **PC1** | Preview Compose : interpréteur sur l'arbre résolu, rendu interactif hors processus | LSP Kotlin K3-K4 (livré), socle `:preview` (livré) | Très haut | Très haut |
| **PC2** | Module séparé `:preview:compose` | PC1 | Moyen | Isolation des dépendances |

## 3. Priorisation recommandée

| Priorité | Travail | Justification |
| --- | --- | --- |
| **P0 — Immédiat** | Valider CI v0.125.1 (LayoutLib v3.0.0) | La CI tourne — attendre le verdict |
| **P1 — Court terme** | CE3 (barre recherche/remplacement) | Impact utilisateur très haut, effort faible (3 lignes) |
| **P1 — Court terme** | PE5a (bouton bascule langue preview) | Exploite P1 résolu, effort faible |
| **P2 — Moyen terme** | CE1 (fil d'Ariane à actions) | Impact haut, effort faible |
| **P2 — Moyen terme** | CE7 (plis par niveau) | Impact moyen, effort faible |
| **P2 — Moyen terme** | PE5b/PE5c (tests non-régression P0/P2) | Validation appareil des nouvelles capacités LayoutLib |
| **P3 — Long terme** | CE2, CE4, CE5, CE6, CE8 | Améliorations code-editor restantes |
| **P3 — Long terme** | Tests appareil T1-T12 + I1-I5 | Validation finale sur appareil réel |
| **P4 — Très long terme** | PC1/PC2 (Preview Compose) | Effort très haut, dépendances satisfaites |

## 4. Prochaines étapes immédiates

1. **Surveiller la CI v0.125.1** (LayoutLib v3.0.0) — si succès, la
   mission PE4 est complète pour P0/P1/P2.
2. **Si l'utilisateur confirme** : démarrer CE3 (barre
   recherche/remplacement) — effort faible, impact très haut.
3. **Si l'utilisateur confirme** : démarrer PE5a (bouton bascule langue
   preview) — exploite P1 résolu.
4. **Tests appareil** : quand l'utilisateur aura un appareil disponible,
   exécuter T1-T12 (EditorActivity) et I1-I5 (indexation) selon les
   recettes de `docs/TESTS_MANUELS.md`.

## 5. Références

- `docs/PREVIEW.md` §PE4 — état des limitations LayoutLib (mis à jour v3.0.0)
- `docs/TESTS_MANUELS.md` §« Limitations LayoutLib v3.0.0 » + §« Mission EditorActivity »
- `docs/adr/0116-pe4-limitations-layoutlib-v2-2-0-et-priorisation.md` (accepté et mis à jour)
- `docs/adr/0117-ecran-indexation-lsp-pastille-toolbar-sheet-detail-brouillon.md` (accepté)
- `docs/adr/0118-editoractivity-bottom-sheet-clavier-barre-navigation.md` (accepté)
- `docs/audit/INDEXATION_PHASE_A.md` — audit Phase A prompt 2
- `docs/audit/EDITORACTIVITY_PHASE_A.md` — audit Phase A prompt 1
- Guide `MISE-A-JOUR-CODEIDE.md` dans le dépôt `code-editor` — améliorations recommandées §4
