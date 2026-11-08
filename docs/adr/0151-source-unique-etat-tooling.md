# ADR 0151 — Source unique de l'état tooling : une dérivation par concept, zéro cascade dans les vues

- Statut : proposé (2026-11-08, volet C du prompt « retard d'affichage des
  sorties de build » — branche `fix/tooling-coherence`, sans fusion)
- Contexte : INC-01 à INC-22 de ce prompt ; suites naturelles des ADR 0078
  (console flux brut unique), 0079 (le dernier verdict gagne) et 0081
  (lecture par fil dédié).

## Contexte

Le constat fondateur du volet C : **cinq cascades décidaient de la même
question** (« quel canal afficher ? quel statut ? quel libellé ? »), chacune
avec ses propres replis :

1. l'en-tête du panneau : `canalActif ?: canalDernierResultat` ;
2. le chip de la console (`rendreChipAction`) : `SYNC` dès qu'une sync
   avait vécu — y compris PENDANT un build lancé après une sync réussie ;
3. l'icône de canal (`baliserCanalStatut`) : copie conforme de (2) ;
4. la ligne de statut (`libelleStatut`) : priorité sync-puis-build avec des
   replis VOLONTAIREMENT divergents (une sync partielle y était
   « Synchronisée » quand l'en-tête affichait un échec) ;
5. la console visible (`EtatEditor.filtreConsole`) : poussée par l'action
   qui démarre.

Résultat mesuré dans le journal du propriétaire (2026-10-31) : après une
sync réussie puis un build échoué, la console du build s'affichait avec son
bouton Annuler, mais le chip disait « Sync », l'icône celle de la sync, le
statut « Synchronisé en X » — pendant que la notification finale disait la
sync réussie (INC-01). Chaque correction locale d'une cascade ne faisait
que déplacer l'incohérence.

## Décision

### 1. Une seule dérivation par concept, dans le présentateur pur

Toute règle « quel canal ? quel statut ? quel verdict ? quel message ? »
vit **une fois**, dans `PresentationTooling` (objet pur, testable sans
cadre Android), et TOUS les consommateurs l'appellent :

- `canalAffiche(etat, choix)` : le canal des écrans console — le choix
  d'affichage (poussé par l'action qui démarre, choisi par l'utilisateur
  avec l'INC-20) désigne la console ; `null` à l'état vierge ; `TACHES`
  n'est JAMAIS renvoyé (transitoire de l'en-tête uniquement, INC-03) ;
- `libelleActivite(etat, canal)` : LA cascade de titres (verdicts courts
  localisés, INC-15) — elle alimente la ligne enrichie, les indicateurs
  compacts (INC-10) et la notification ;
- `messageEchecCanal` / `reessaiPossible` : le bandeau d'échec par canal
  (INC-21) ;
- `dureeFigee`, `statutVisuel` (avec `ANNULE`), `sousTitre` PAR CANAL :
  l'état d'en-tête complet (`etatEntete`).

**Interdit** (déjà règle 9 des interdits du prompt, désormais outillé par
le test croisé) : toute copie de cascade « canal / statut / libellé » dans
une vue, un fragment ou un contrôleur.

### 2. Deux rôles distincts, assumés

- **L'en-tête du sheet suit l'ACTIVITÉ** (`canalActif ?:
  canalDernierResultat`, chronologie par repères d'ordre — INC-01) : il
  annonce ce qui se passe, même si l'utilisateur consulte l'autre console.
- **Les écrans console suivent le CHOIX d'affichage**
  (`EtatEditor.filtreConsole`, utilisateur + automatique — INC-20) : le
  sélecteur segmenté Sync | Build EST ce choix, la console visible, le
  bandeau et le point d'actualité le dérivent ensemble.

Les deux rôles se rejoignent tant que l'utilisateur ne déroge pas — le
test de cohérence croisée (`CoherenceCanalAfficheTest`) le vérifie sur
toute la table des états service-publiables.

### 3. Le test croisé verrouille la cohérence

`CoherenceCanalAfficheTest` (et son aîné `CoherenceDernierResultatTest`)
parcourent les combinaisons {sync, build} × {vierge, en vol, réussi,
échoué, annulé, listage} : les sorties des consommateurs doivent désigner
le même canal, et l'en-tête rester cohérent sans dérogation. Toute
nouvelle cascade locale fait échouer ce test par construction.

### 4. L'information vit dans le flux ou dans l'en-tête — jamais aux deux

Application stricte de l'ADR 0078 au-delà de la console : la ligne de
statut de la console ne porte plus que l'état que l'en-tête ne peut pas
porter (l'orchestrateur déconnecté avant toute activité — INC-22) ; le
message complet d'un échec vit dans le bandeau et le flux, le verdict
court dans l'en-tête, la cause courte en sous-titre (INC-15).

## Conséquences

- Ajouter un consommateur de l'état tooling = appeler une dérivation
  existante (ou en extraire une nouvelle du présentateur), jamais recopier
  une cascade.
- Les tests verrouillent la STRUCTURE (identifiants de ressources,
  arguments, texte brut) côté présentateur, et la COHÉRENCE croisée côté
  consommateurs — les deux ensemble empêchent la régression silencieuse.
- Le glossaire visible (Console / Build / Sync / Tâches / Serveur de
  build / Daemon Gradle) vit dans `docs/TOOLING.md` ; « Orchestrateur »
  reste un terme de journal interne.
- Les fonctions du présentateur acceptent l'état + le choix d'affichage :
  le choix est TOUJOURS une entrée explicite (jamais lu depuis une vue),
  ce qui garde le présentateur pur et testable.
