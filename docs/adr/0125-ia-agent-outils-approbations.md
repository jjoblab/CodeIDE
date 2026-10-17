# ADR 0125 — IA : agent, outils, approbations et réversibilité

- **Statut** : accepté (propriétaire, 2026-10-18 — « GO lot IA-0 »)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0122, 0123, 0124. **S'appuie sur** : historique local (ADR 0104-0106,
  port `HistoriqueLocal`), ADR 0003 (SAF) et le pont `ResoudreRepertoireProjet` (ADR 0038),
  `GradleToolingPort`, diagnostics LSP, moteur de recherche de `feature:editor`.
- **Contexte** :
  - Un agent qui lit et modifie des fichiers peut détruire du travail ou exécuter des actions
    non voulues, y compris sous l'effet d'un contenu piégé (ADR 0124 §4 bis).
  - L'application dispose déjà d'un **filet de sécurité** : l'historique local conserve le
    contenu précédent de chaque écriture (type `MODIFICATION`), la création et la suppression
    — base idéale pour un « Annuler tout ce qu'a fait l'agent ».
  - L'exécution de commandes dans le terminal PRoot est puissante et dangereuse :
    hors périmètre V1.

## Décision

### Boucle d'agent (`:core:ai`, `AgentRunner`)
Cycle borné : **réfléchir → appeler un outil → observer → continuer**, jusqu'à
réponse finale, annulation ou limite atteinte. Limites **configurables avec valeurs par
défaut prudentes** : 25 étapes, plafond de jetons par exécution, délai total, 3 échecs
d'outil consécutifs. Chaque limite atteinte produit un message clair et un état « interrompu ».
Aucun traitement en arrière-plan : l'agent s'exécute dans un service au premier plan
visible (notification) tant qu'il tourne, annulable à tout moment.

### Outils V1 (interface `AiTool`, niveau de risque déclaré)
| Outil | Niveau | Comportement |
|---|---|---|
| `list_files(path)` | LECTURE | Arborescence du projet (hors exclusions ADR 0124) |
| `read_file(path, range?)` | LECTURE | Contenu borné (taille max), refus des fichiers exclus |
| `search_text(query, glob?)` | LECTURE | Réutilise le moteur de recherche existant |
| `get_diagnostics(path?)` | LECTURE | Diagnostics LSP/Gradle courants |
| `propose_edit(path, edits)` | ÉCRITURE (mise en attente) | **N'écrit rien** : ajoute au `ChangeSet` |
| `create_file(path, content)` | ÉCRITURE (mise en attente) | Idem |
| `delete_file(path)` | ÉCRITURE (mise en attente) | Idem, confirmation renforcée |
| `run_gradle(task)` | EXÉCUTION | **Approbation à chaque appel** ; tâches via `GradleToolingPort` ; liste blanche (`assembleDebug`, `lintDebug`, `testDebugUnitTest`, `spotlessCheck`, `detekt`) |

`run_shell` / accès terminal : **non livré en V1** (nouvelle ADR requise).

### Niveaux d'approbation (`ApprovalGate`)
- **LECTURE** : automatique.
- **ÉCRITURE** : jamais directe. Les changements s'accumulent dans un `ChangeSet`
  (diff par fichier) ; l'utilisateur les **examine** (écran Agent → « Examiner les
  changements ») puis **applique tout / fichier par fichier / rien**.
- **EXÉCUTION** : une carte d'approbation par appel (outil, arguments exacts, raison
  donnée par l'agent) ; l'utilisateur **autorise une fois** ou **refuse**. Aucun
  « toujours autoriser » en V1.

### Confinement
Toute opération de chemin passe par un `ProjectPathResolver` : chemin relatif au projet
uniquement, normalisation, refus de `..`, des liens symboliques sortant du projet,
des chemins absolus et des fichiers exclus (ADR 0124). Échec = résultat d'outil
« refusé » renvoyé au modèle (pas d'exception jusqu'à l'UI).

### Réversibilité
- Application du `ChangeSet` = écritures via `FileSystem` **avec marqueur d'étiquette
  d'historique** « IA · <date> » (réutilise `EtiqueteurHistorique`) → chaque application est
  un point de restauration.
- Action **« Annuler cette exécution »** : restaure l'état d'avant l'application depuis
  l'historique local (création → suppression, modification → contenu précédent,
  suppression → pierre tombale restaurée).
- Si l'historique local est **désactivé ou en échec**, l'application d'un `ChangeSet`
  est refusée (garde-fou) avec message explicite.
- Si un fichier a changé depuis la proposition (empreinte différente), le fichier est
  marqué **conflit** et jamais écrasé silencieusement.

## Options considérées

### Option A — Écritures mises en attente + approbation par lot + restauration par historique local (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne-haute |
| Coût | Réutilise historique local, diff (`DiffUnifie`), recherche existante |
| Scalabilité | Outils ajoutables par interface |
| Familiarité | Moyenne (historique local connu, boucle d'agent nouvelle) |

### Option B — Écriture directe + annulation a posteriori
Plus fluide, mais l'éditeur et le LSP réagiraient à des écritures non validées et
une erreur de l'agent toucherait déjà le disque. **Écartée** pour la V1.

### Option C — Assistant en lecture seule (pas d'agent)
Sûr et simple, mais ne répond pas à la demande de fonctionnalités d'agent. Reste le
**mode de repli** (interrupteur « Agent » désactivable dans Réglages).

## Analyse des compromis
A ajoute un écran de revue (coût UX) mais rend l'agent **sûr par construction** :
aucune écriture sans validation, toute application est réversible. C'est le prérequis pour
oser l'étendre (outils supplémentaires) dans des versions ultérieures.

## Conséquences
- **Plus facile** : confiance de l'utilisateur ; tests déterministes (outils purs + faux fournisseur).
- **Plus difficile** : gérer la divergence entre proposition et état courant (conflits) ;
  fournir des diffs lisibles sur mobile.
- **À revoir** : `run_shell`, outils réseau/web, auto-approbation limitée par politique.

## Actions
1. [ ] `AiTool`, `ToolRegistry`, `ChangeSet`, `ApprovalGate`, `AgentRunner` dans `:core:ai` (lot IA-7).
2. [ ] `ProjectPathResolver` + tests d'évasion de chemin (`..`, liens symboliques, chemins absolus) (lot IA-7).
3. [ ] Pont vers `HistoriqueLocal` pour l'étiquette et la restauration (lot IA-7).
4. [ ] Service premier plan + notification d'exécution, annulation (lot IA-7).
5. [ ] Tests « injection » : un fichier contenant des consignes ne déclenche aucun outil d'écriture/exécution sans approbation (lot IA-7).
