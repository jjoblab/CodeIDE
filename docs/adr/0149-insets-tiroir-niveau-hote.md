# ADR 0149 — Les insets du tiroir traités au niveau de l'HÔTE (rapport IA, lot 3 — bug B1c)

- **Statut** : **accepté** (livré avec CodeIDE v0.188.0).
- **Date** : 2026-10-25.
- **Décideurs** : jjoblab (rapport `docs/IA_REVUE_ET_PROMPT.md` §5 Lot 3,
  point 4 : « traiter au niveau de l'hôte pour que tous les fragments
  du tiroir en profitent »).
- **Dépend de** : ADR 0118 (gestionnaire unique des insets de l'espace
  de travail), ADR 0055 (chrome de l'hôte / fragments du tiroir),
  `docs/IA_REVUE_ET_PROMPT.md` (B1c « Probable » : confirmé par lecture
  — le tiroir ne recevait que `applySystemBarsInsetsMargins()`, aucun
  traitement IME nulle part sur son chemin).

## Contexte

Le tiroir de l'espace de travail héberge les fragments de navigation
(Fichiers, Recherche, Git, Terminal) ET le tiroir IA (Chat, Agent,
Historique). Son seul traitement d'insets était
`applySystemBarsInsetsMargins()` — un écouteur propre au tiroir qui ne
connaît QUE les barres système : aucun IME. Le tiroir se termine par un
rail de fragments de 66 dp ; quand le clavier monte pour la saisie du
chat (ou de la recherche), l'inset IME n'était appliqué NULLE PART : le
clavier recouvrait le compositeur — la frappe se faisait à l'aveugle
(B1c). Chaque fragment aurait pu appeler un utilitaire d'insets, mais
aucun ne le fait : seul `EtapeInformationsFragment` (wizard) utilise
`applyImeBottomInset`.

## Décision

1. **Le traitement vit au niveau de l'HÔTE** :
   `GestionnaireInsetsEditeur.surInsetsTiroir(insetIme)` — le listener
   UNIQUE de la racine (déjà en place depuis v0.183.0) appelle le
   gestionnaire avec le bas de l'IME à CHAQUE distribution d'insets
   (les trames de l'animation du clavier comprises) : TOUS les
   fragments du tiroir en profitent sans rien connaître des insets.
2. **Le calcul est pur et testé** :
   `GestionnaireInsetsEditeur.calculerPaddingBasTiroir(insetIme,
   insetBarres) = max(0, insetIme − insetBarres)` — le tiroir est déjà
   MARGÉ de la barre de navigation (`bottomMargin`), la part de l'IME
   recouverte par cette marge n'est PAS comptée deux fois ; clavier
   fermé le padding est nul (comportement inchangé).
3. **Marges inchangées** : statut en haut, navigation en bas — le tiroir
   ne passe jamais derrière les barres (v0.32.1).
4. **Le rail de fragments (66 dp) est MASQUÉ tant que le clavier est
   visible** — la surface de frappe prime ; il revient au repli du
   clavier (la visibilité suit la même distribution d'insets, pas un
   état supplémentaire).

## Conséquences

- Le compositeur du chat (identifié `composeur_ia`, jamais recouvert par
  le calque d'état — B1a/B1b, même lot) reste AU-DESSUS du clavier :
  le critère d'appareil T6 (« le champ reste visible au-dessus du
  clavier ») devient atteignable.
- L'ancien écouteur propre au tiroir (`applySystemBarsInsetsMargins`)
  disparaît : le gestionnaire est le SEUL point d'entrée — plus de
  double écouteur possible sur la même vue (leçon v0.183.0).
- Rotation et clavier flottant : la marge suit `barres` à chaque
  distribution ; **à valider sur appareil** (rotation, mode fenêtré —
  le point « Probable » du rapport ne se prouve qu'à l'écran).
- Test de non-régression : `GestionnaireInsetsEditeurTest` (calcul pur
  ×4 : clavier fermé, clavier au-dessus de la barre, clavier plus petit
  que la barre, aucun inset).
