# ADR 0162 — Surfaces, module et navigation du Centre de sorties

- Statut : proposé (2026-11-10, lot L0 — branche
  `agent/sorties-l0-conception`). Renuméroté depuis 0156 (créneau
  occupé par le chantier générateur).
- Contexte : audit § 5 (panneau étroit), § 4 (incident éclaté) ;
  `docs/SORTIES_SPEC.md` § 7 ; ADR 0028 (zone centrale à onglets),
  0029/0118 (panneau inférieur), 0147 (buffers lecture seule) ;
  tableau des dépendances `docs/ARCHITECTURE.md` ;
  `checkModuleDependencies` (`build-logic` `ModuleRulesPlugin`).

## Contexte

`feature:editor` compte **27 038 lignes Kotlin** en `src/main`
(mesuré sur `22097a96`) et concentre déjà la console, le Logcat, le
journal compact, la zone centrale et le panneau. Le composant de
lecture unifié a besoin de jetons, d'une vue et d'une logique de tampon
réutilisables par `feature:editor`, `feature:diagnostics` et les points
d'entrée contextuels — sans grossir davantage `feature:editor` ni
croiser les dépendances `feature:*` (interdites).

## Décision

1. **Nouveau module `feature:sorties`** (UI + contrôleurs du lecteur
   unifié) ; le **contrat visuel partagé** (jetons et composants de
   rangée réutilisables hors feature — pastilles de niveau, bandeaux)
   vit dans **`core:ui`**, comme le tiroir terminal. La logique de
   tampon/projection est dans `core:domain` (ADR 0160), pas dans la
   feature. Arêtes : `feature:sorties` → `core:ui`, `core:domain`,
   `core:model` ; `feature:editor` → `feature:sorties` (le panneau et
   les points d'entrée l'ouvrent) ; `feature:diagnostics` →
   `feature:sorties` (écran Journaux consommateur). Aucune arête
   `feature:sorties` → autre feature. Justification du graphe :
   ajout au tableau `docs/ARCHITECTURE.md` + `build-logic`
   (allow-list), vérifié par `checkModuleDependencies`.
2. **Trois surfaces, un composant** :
   - **panneau compact** (BottomSheet 3 états conservé, ADR 0029/0118) :
     fenêtre bornée sur le composant — il n'embarque plus de moteur ;
   - **éditeur dédié** en **onglet de la zone centrale** (comme un
     fichier, ADR 0028) et en **plein écran** — la recherche, les
     filtres avancés et l'historique vivent là ;
   - **tablette** : volet **à côté du code** (jamais par-dessus).
3. **Points d'entrée contextuels** : bandeau « Voir les sorties » sur
   échec de build (étend le bandeau existant INC-21), notification de
   fin, « Voir la trace » (existant), ouverture de la session Logcat
   après lancement. **Fil de l'incident** : depuis un build échoué, un
   menu « plus » mène aux diagnostics, aux journaux serveur de
   l'exécution et, si un lancement a suivi, à la session Logcat — deux
   gestes au plus.
4. **Migration** : les anciens onglets Console et Logcat du panneau et
   l'écran journal de `feature:diagnostics` sont **remplacés** dans le
   MÊME lot que leur remplacement (L4/L5 : pas de code mort, pas de
   double affichage — leçon de gouvernance des ADR 0078/0147) ;
   l'onglet Problèmes reste tel quel au départ (référence de
   comportement des sauts au source).
5. **Nom visible** : « Sorties » (§ 9 Q9) ; le glossaire de
   `docs/TOOLING.md` est mis à jour (« Console » désigne l'ancien
   onglet).

## Alternatives rejetées

- **Grossir `feature:editor`** : +kK de lignes dans un module déjà à
  27 kK, pour un composant consommé aussi par `feature:diagnostics` —
  violerait le sens du découpage et compliquerait les propriétés de
  modules entre agents (`STRATEGIE_VERIFICATION.md` § 5) ; rejeté.
- **Un module `core:ui` complet** (lecteur dans core) : `core:ui` est le
  point de partage des jetons/composants, pas un hôte de ViewModels ni
  de logique de tampon (frontières du tableau) ; le lecteur est une
  FEATURE, ses briques visuelles dans `core:ui` ; rejeté.
- **Plein écran uniquement** (sans onglet central) : casserait le
  parallèle fichier/sorties (ADR 0028) et le défilement code↔sorties
  sur tablette ; l'onglet est le conteneur naturel — rejeté.
- **Volet tablette par-dessus le code** (recouvrement) : masque le
  contexte de correction (l'erreur ET le code) ; le volet latéral
  préserve la lecture simultanée — rejeté.
- **Garder Problèmes + un second flux d'erreurs cliquables** : dupliquer
  l'information (interdit ADR 0078) — les erreurs du flux renvoient au
  MÊME contrat de saut, pas à une seconde liste.

## Conséquences

- L2 pose `feature:sorties` + jetons `core:ui` (avec KDoc et aperçu
  `docs/mockups/ui/`, règle 3) ; L3 raccord Gradle ; L4 migration
  Logcat/Journal ; L5a onglet/plein écran, L5b panneau compact
  rebranché, L5c volet tablette, L5d points d'entrée et fil de
  l'incident ; suppression des anciens fragments dans le même lot.
- `ActivityEditorLayoutTest` et les tests d' insets/clavier restent
  verts à chaque étape.
- L'ajout du module passe d'abord par `checkModuleDependencies` en
  échec → mise à jour de l'allow-list justifiée par cet ADR dans le
  même commit.
