# feature/ai — Tiroir IA — chat, agent, historique, réglages

Feature Android du chantier IA (ADR 0122). Entrée dans le tiroir de
l'éditeur via `FabriqueFragmentIaTiroir` (patron ADR 0053, liée par
Hilt dans `di/TiroirIaBindsModule`) ; 6ᵉ destination du rail
(`feature:editor`, câblage ≲ 40 lignes — lot IA-3).

## Dépendances autorisées

Trio standard (core:ui / core:domain / core:model, ajouté par la
convention feature) + `:core:ai` (exception explicite ADR 0122).
Interdits : autres features, `:preview:xml`, réseau direct (tout passe
par les cas d'usage de `:core:ai`).

## Contenu prévu

- IA-2 : écrans Paramètres → IA (fournisseurs, détail, confidentialité,
  limites), consentement, test de connexion, « Actualiser les modèles » ;
- IA-3 : `IaTiroirFragment` (sous-onglets Chat · Agent · Historique),
  `ChatFragment`/`ChatViewModel`, rendu Markdown maison, streaming,
  Arrêter/Régénérer ;
- IA-4 : puces de contexte, sélecteur `@`, budget de jetons visible ;
- IA-6 : écran Historique (recherche, renommer, supprimer, rétention) ;
- IA-7 : écran Agent (plan, étapes, approbations, revue des changements).

Écran « Recherche » : RÉSERVÉ (livraison après la mission IA,
`docs/RECHERCHE.md`).
