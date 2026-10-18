# ADR 0129 — IA : complétion « texte fantôme » (ghost text)

- **Statut** : accepté (propriétaire, 2026-10-18 — « GO IA-5, IA-6, IA-7 et IA-8 »)
- **Date** : 2026-10-18
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0121 (garde-fous réseau), 0123 (fournisseurs), 0124
  (confidentialité), 0125 (agent — hors périmètre : la complétion est une
  fonction DISTINCTE de l'agent), 0126 (intégration éditeur). S'appuie sur
  `EditorDecorationPainter` de `cel-ui` (rendu de décorations dans la vue
  d'édition) et le routage IA (IA-2).
- **Contexte** :
  - La complétion « texte fantôme » (suggestion en ligne, acceptée par
    Tab) est une fonction à latence ET à coût par frappe : sans garde,
    elle génère des requêtes à chaque pause de saisie — contraire à
    ADR 0121 point 3 (« aucune requête sans action utilisateur ») si elle
    n'est pas explicitement consentie et plafonnée.
  - L'API `ViewZone` n'est pas consommée par `cel-ui` (ADR 0126) mais
    `EditorDecorationPainter` l'EST : le fantôme se dessine comme
    décoration (gris, après le curseur), pas comme une vue Android.
  - Les fournisseurs exposent des modes différents : FIM (fill-in-middle,
    Anthropic/OpenAI via préfixe/suffixe) vs chat simple. Le mode FIM
    n'est PAS portable d'un fournisseur à l'autre — il se négocie.

## Décision

### 1. Désactivée par défaut, opt-in explicite
L'interrupteur « Complétion fantôme » vit dans Paramètres → IA (carte
propre, défaut **désactivé**). L'activation affiche le plafond de coût
(§3) et le rappel de confidentialité (ADR 0124 : le préfixe/suffixe
envoyé suit les mêmes exclusions que le chat — `ExclusionsIa`, lecture
bornée).

### 2. Déclenchement et annulation
- **Pause de saisie** : une requête ne part qu'après **800 ms** sans
  frappe (debounce) ET à la condition que le curseur soit en fin de mot
  (pas au milieu d'un collage, pas pendant une session d'annulation).
- **Annulation à la frappe** : toute frappe annule la requête en vol
  (Job annulé — la socket se ferme, même contrat que « Arrêter » du
  chat, ADR 0123 point 4) et réarme le débounce.
- **Une requête en vol au maximum** : pas de file d'attente.

### 3. Plafond de coût visible
- Compteur de **caractères suggérés acceptés** par session, affiché dans
  la carte de réglage ; plafond mensuel configurable (défaut : 200 k
  caractères) — au plafond, la fonction se désactive seule avec un
  message explicite (aucun coût invisible).
- Les requêtes de complétion sont **comptées séparément** du chat
  (l'étiquette de journalisation reste sobre — ADR 0124 §3 : aucun
  contenu dans les journaux, seulement les compteurs).

### 4. Rendu et acceptation
- Le fantôme se dessine via **`EditorDecorationPainter`** (texte gris
  `onSurfaceVariant`, alpha atténué) **après le curseur** ; il ne décale
  jamais la saisie (aucune vue Android intercalée — cf. ADR 0126
  ViewZone).
- **Tab** accepte (insertion en UN LOT — `beginBatch/endBatch`, un seul
  Annuler), **Échap** ou toute frappe rejette ; acceptation partielle
  (mot par mot, Ctrl+→) : **différée** (IA-8b, dépend du découpage
  par mots fiable sur mobile).
- Le fantôme n'est jamais annoncé à TalkBack (décoration pure) ;
  l'acceptation l'est (« suggestion insérée »).

### 5. Mode fournisseur
- V1 : **chat simple** (préfixe+suffixe encadrés « contenu non fiable »,
  consigne système dédiée « compléter uniquement, sans explication ») —
  portable sur les deux fournisseurs.
- Le mode **FIM natif** (blocs préfixe/suffixe du fournisseur) est une
  extension par fournisseur, ajoutée quand vérifiée (montée de version
  du catalogue si un changement de `:ai:providers` est nécessaire).

## Options considérées

### Option A — Fantôme opt-in, chat simple, plafond visible (retenue)
| Dimension | Évaluation |
|---|---|
| Coût | Borné et visible (plafond, compteurs) |
| Confidentialité | Mêmes exclusions que le chat, préfixe borné |
| Portabilité | Chat simple = tous fournisseurs |
| Risque | Faible (désactivée par défaut, annulation à la frappe) |

### Option B — FIM natif dès la V1
Meilleure qualité de suggestion sur les fournisseurs qui le supportent,
mais deux chemins de code par fournisseur + négociation de mode —
reportée en IA-8b.

### Option C — Toujours activé après le premier consentement
Rejetée : le coût par frappe doit rester un choix VISIBLE et réversible.

## Analyse des compromis
Le fantôme est la seule fonction IA qui part SANS geste par instance :
l'opt-in + le plafond + l'annulation à la frappe en font un consentement
continu et honnête, conforme à ADR 0121 point 3 dans l'esprit (l'activation
initiale EST le geste).

## Conséquences
- **Plus facile** : aucune écriture, aucun outil — surface de risque
  minimale ; réutilise le routage IA et les exclusions existants.
- **Plus difficile** : latence perçue (800 ms) ; qualité dépendante du
  fournisseur en mode chat simple.
- **À revoir** : acceptation partielle, FIM natif, cache de suggestions
  par emplacement (IA-8b).

## Actions
1. [ ] Interrupteur + plafond dans Paramètres → IA (IA-8b).
2. [ ] `CompletionGhostIa` (debounce, annulation, plafond) dans `:core:ai` (IA-8b).
3. [ ] Rendu `EditorDecorationPainter` + Tab/Échap dans `:feature:editor` (IA-8b).
4. [ ] Tests : annulation à la frappe, plafond atteint, exclusions appliquées (IA-8b).
