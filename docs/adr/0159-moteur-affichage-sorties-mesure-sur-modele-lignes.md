# ADR 0159 — Moteur d'affichage du Centre de sorties : décision par mesure, sur un modèle de lignes virtualisé

- Statut : proposé (2026-11-10, lot L0 du chantier « Centre de sorties »
  — branche `agent/sorties-l0-conception`, sans fusion). Renuméroté :
  le créneau 0153 demandé à l'origine est occupé (ADR 0153–0158,
  chantier générateur).
- Contexte : audit `docs/SORTIES_AUDIT.md` § 1, 2, 6 ; maquette
  `docs/mockups/sorties/sorties.html` (M2/M3/M7) ; budgets
  `docs/SORTIES_SPEC.md` § 10.2 ; banc `docs/SORTIES_BANC_MESURE.md`.

## Contexte

La console actuelle en `TextView` par canal est le résultat mesuré des
ADR 0074 puis 0078 (O(N²) corrigé, duplication supprimée). Le Centre de
sorties exige en plus : recherche avec compteur et repères, filtres sans
réécrire le tampon, repli par tâche avec en-tête collant, regroupement
des répétitions, sélection multi-lignes, repères de défilement, zoom.
Un `TextView` ne peut porter tout cela sans re-dessiner des mégaoctets de
`Editable` à chaque filtre : un moteur différent est nécessaire — mais le
choix est le risque n° 1 de régression de latence (ADR 0151/0152), donc
il se juge sur mesure, pas sur goût.

Mesures déjà disponibles (lot L0) : le parseur JVM de classification
débute à ~92 000 lignes/s à froid et atteint ~1,06 M lignes/s en régime
établi (banc `scripts/banc-parseur-sorties/`) — la classification n'est
pas le goulot ; le goulot est le rendu.

## Décision

1. **Le moteur se décide par la mesure du banc** (`SORTIES_BANC_MESURE.md`,
   séquences S1–S8, critères chiffrés § 6) exécutée par le propriétaire
   sur son appareil ; aucun code de production n'écrit avant « GO L2 »
   accompagné des PV.
2. **Recommandation d'implémentation** (à confirmer par la mesure) : un
   **RecyclerView virtualisé sur le modèle de lignes** (`LigneSortie`,
   ADR 0160), sans DiffUtil (leçon 0074 : append par lot, notifications
   bornées), avec :
   - un index de projection brut/regroupé (le tampon est unique, la
     projection est une dérivation bornée) ;
   - des ViewHolder de hauteur variable (retour à la ligne) et un
     en-tête de tâche courante par item collant (implémentable
     nativement) ;
   - recherche hors thread principal sur le tampon, jamais sur la vue ;
   - repères de défilement calculés sur l'index, pas sur les vues.
3. **La sonde T5/T6 de l'ADR 0152 est le juge de paix** : la latence
   émission → pixel ne doit pas régresser par rapport à la console
   `TextView` actuelle (S8). Toute régression bloque le lot L2.

## Alternatives rejetées

- **Garder le `TextView` par canal** : aucune recherche/filtre/repli
  raisonnable (redessin complet par filtre) ; c'est la limite constatée
  qui motive le chantier — rejeté, mais son **chemin chaud** (append
  loti par trame, suivi honnête du bas) est conservé comme modèle.
- **`codeeditor-ui` en lecture seule** (candidat A) : recherche et
  sélection gratuites, mais buffer d'édition coûteux en mémoire pour un
  flux append-only, en-tête collant et regroupement à composer contre
  la bibliothèque, et le chemin « lignes ajoutées en vol » n'est pas son
  cas nominal — maintenu comme candidat de mesure (si ses PV tiennent
  les budgets et que sa mémoire plafonne, il redevient préféré pour ses
  sélections gratuites).
- **Vue `Canvas` personnalisée** (candidat C) : contrôle total mais coût
  de construction élevé (sélection, TalkBack, marqueurs à réécrire) ;
  réservé au cas où RecyclerView échoue aux budgets S1–S4 — rejeté par
  défaut, mesuré si besoin.
- **Chargement paginé avec base de données** (paging disque) : ajoute une
  couche de latence au chemin chaud et duplique la mémoire ; l'historique
  (ADR 0161) couvre la persistance — rejeté pour le lecteur live.

## Conséquences

- L2 implémente le moteur retenu derrière le contrat `LecteurSorties`
  (interface interne du module décidé par l'ADR 0162), branché sur une
  source factice uniquement.
- Les PV du banc sont des livrables de L2 : sans mesures, pas de
  fusion.
- Si la mesure invalide la recommandation (2), le contrat ne change pas —
  seul le moteur change ; c'est le but du banc.
