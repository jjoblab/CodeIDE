# Spécification Git — section Git du tiroir CodeIDE

Référence de comportement : Android Studio (fenêtre Commit, popup des
branches, Git Log) avec la **structure de VS Code** (vue Source Control).
Le tiroir est étroit : la structure de VS Code est retenue, le
vocabulaire et les couleurs sont ceux d'Android Studio.

## 1. Périmètre

La section Git du tiroir (quatrième destination du rail, § 14) gère :
statut des fichiers, commit, push/pull, branches, historique, stash,
résolution de conflits. **Hors périmètre** : sous-modules, LFS, worktrees,
rebase interactif, signature GPG, intégration PR, blame/annotate.

## 2. Moteur

**git CLI via `NativeProcessLauncher`** (ADR 0092). Le binaire `git`
doit être installé via `pkg install git` (même pattern que le JDK). Le
port `MoteurGit` (`core:domain`) isole l'UI du moteur — l'implémentation
de production (`MoteurGitCli`, `core:bootstrap`) utilise le pont FUSE
(`ResoudreRepertoireProjet`, ADR 0038) pour obtenir le chemin réel du
projet.

### 2.1 État du dépôt typé (v0.90.1, mission « section Git figée »)

`MoteurGit.etatDepot(cheminFuse)` retourne `EtatDepot` :

| État | Signification | Affichage |
|---|---|---|
| `Depot` | `git rev-parse --is-inside-work-tree` répond `true` (code 0) | Statut, branche, actions |
| `PasUnDepot` | git répond **explicitement** « not a git repository » (stderr) ou `false` (stdout, répertoire `.git` interne) | « Initialiser un dépôt » |
| `Inaccessible(raison, codeSortie, stderrExpurge)` | Tout le reste : binaire absent, lancement impossible, refus de git (dubious ownership, permission refusée…), stdout incohérent | Erreur observable + Actualiser — **jamais** « Initialiser » |

Règle d'invariance : l'état `Inaccessible` signifie que l'état du dépôt
est **indéterminé** — proposer « Initialiser un dépôt » reviendrait à
`git init` sur un dépôt sain que git refuse de lire (le `git init` sur
un `.git` existant réussit silencieusement, le symptôme revient).

Chaque exécution git est journalisée (tag `git-cli`, `AppLogger`) :
commande, code de sortie, stderr — succès en DEBUG, échec en WARN,
expurgé par le pipeline (règle 11).

### 2.2 Diagnostic Git (v0.90.1, étape A)

L'écran **Diagnostic** porte un onglet **Git** qui exécute et affiche,
pour le projet le plus récemment ouvert : chemin et version du binaire
git ; uid effectif de l'application et uid propriétaire du dossier
(`st_uid`) — divergents ⇒ piste « dubious ownership » (git ≥ 2.35.2) ;
point de montage et type (fuse/sdcardfs/ext4) ; sortie complète de
`git rev-parse --is-inside-work-tree` (code + stdout + stderr) et de
`git config --list --show-origin` (recherche de `safe.directory`) ;
variables d'environnement (`HOME`, `PATH`, `TMPDIR`, `GIT_*`).

« Copier le diagnostic » colle une version **expurgée** (nom de projet,
chemins, courriels, identités et jetons masqués) — les types d'erreur,
uid et codes de sortie survivent au masquage : la copie reste
diagnostique. Le rapport ne présuppose rien : une sonde indisponible
vaut `(indisponible)`, jamais une supposition.

### 2.3 Preuve appareil reçue (v0.92.0) — hypothèse H1 DÉMONTRÉE, reste à faire

Le diagnostic Git exécuté **sur l'appareil** par l'utilisateur (v0.90.1,
sortie expurgée reçue le 2026-10-11) établit enfin la preuve que les
trois correctifs précédents (v0.80.4/.5/.7) n'avaient pas :

- **`git rev-parse --is-inside-work-tree` échoue en « dubious
  ownership »** (`fatal: detected dubious ownership in repository at …`,
  code 128) — le refus déterministe de git, exactement le symptôme C2
  (clone OK, première lecture KO, jamais de rétablissement) ;
- **uid effectif de l'application ≠ uid propriétaire du dossier**
  (10298 vs 10217) sur un point de montage **fuse** — la condition de
  refus de git ≥ 2.35.2 (H1, la piste n°1 du prompt, C3 : aucune
  configuration `safe.directory` dans le dépôt) ;
- `git config --list --show-origin` répond **vide** (code 0) : aucun
  `gitconfig` global dans le `HOME` du bootstrap — le mécanisme de
  confiance ne peut venir que de l'application ;
- binaire git **2.55.0**, environnement FUSE/Termux-like conforme aux
  sondes (`GRADLE_USER_HOME`, `HOME`, `LD_LIBRARY_PATH`, `PATH` du
  bootstrap).

**Conséquence** : la cause est démontrée par observation (règle 1 du
prompt 1) — l'étape B peut corriger à la racine. Travail restant
(documenté, exécution à valider par le « GO » utilisateur) :

| Étape | Contenu | Statut |
|---|---|---|
| **B — Correctif safe.directory** | Appliquer `safe.directory` pour le chemin exact du projet à TOUTES les commandes de `MoteurGitCli` (pas seulement `rev-parse`) ; vérifier empiriquement le mécanisme honoré par git 2.55 (`-c safe.directory=<chemin>` vs `GIT_CONFIG_COUNT/KEY_n/VALUE_n` vs entrée dans le `gitconfig` global du `HOME` du bootstrap) ; ADR de cohérence terminal (config globale = le terminal en profite aussi ; par invocation = non) | **prêt** (preuve reçue) |
| C — États UI complets | Chargement / dépôt valide / vraiment pas un dépôt / erreur git (dont `.git` présent) — invariance : `.git` existe ⇒ jamais « Initialiser » | spécifié §7.1 |
| D — Parcours clone bout en bout | Clone → ouverture = statut correct sans action ; latence FUSE → relance bornée (exponentielle plafonnée, annulable) ; `core.fileMode`/`core.symlinks` (second symptôme probable après le correctif — les bits de mode ne sont pas conservés sur FUSE) | à faire |
| E — Tests de non-régression | Faux `git` scripté qui émet « dubious ownership » tant que l'argument de confiance n'est pas fourni — prouver que TOUTES les commandes le fournissent (test rouge avant / vert après) ; classification stderr ; intégration vrai git | à faire |
| F — Documentation et traçabilité | CHANGELOG, ADR safe.directory (mécanisme, périmètre, cohérence terminal), docs/GIT.md, critères §6 (clone depuis l'accueil, stockage partagé ET privé, `git init` dans le terminal, panne binaire, réouverture) | à faire |

## 3. Authentification

Jeton d'accès personnel GitHub (HTTPS), stocké chiffré (Android
Keystore, AES-GCM). Jamais journalisé, jamais dans les sauvegardes,
jamais dans l'URL du remote. Passé à git via `http.extraHeader` ou
`~/.git-credentials` dans le HOME du shell.

## 4. Identité

`user.name` / `user.email` viennent des Paramètres utilisateur, jamais
codés en dur. Invite claire si absents au premier commit.

## 5. Couleurs de statut (thème New UI)

| Statut | Sombre | Clair |
|---|---|---|
| Ajouté | `#73BD79` | `#067D17` |
| Modifié / renommé | `#70AEFF` | `#0033B3` |
| Supprimé | `#6F737A` | `#6C707E` |
| Non suivi | `#E88F89` | `#B23247` |
| Ignoré | `#D69A6B` | `#8C4F00` |
| Conflit | `#DE6A66` | `#DE1B2E` |
| Fusionné | `#CF84CF` | `#643CB8` |

Le nom d'un fichier prend la couleur de son statut ; un dossier
contenant des changements prend la couleur « modifié ».

## 6. Icônes

Icônes IntelliJ Platform New UI (`platform/icons/src/expui/vcs`) :
`commit`, `push`, `fetch`, `merge`, `revert`, `diff`, `changes`,
`changelist`, `abort`, `remove`. Classiques (`platform/icons/src/vcs`) :
`branch`, `branchNode`, `commitNode`, `history`, `clone`, `author`,
`ignore_file`, `merge`. Étiquettes de branche (`platform/dvcs-impl/…`).
Propres à Git (`plugins/git4idea/resources`). Licence Apache 2.0,
mentions dans `docs/THIRD_PARTY_NOTICES.md`.

## 7. Écrans

### 7.1 État « pas un dépôt »

Si le projet n'est pas un dépôt Git : message « Ce projet n'est pas un
dépôt Git » avec deux actions : **Initialiser** (`git init`) et **Cloner**
(saisie d'URL).

### 7.2 Fragment Git (changements et commit)

Entête : emblème vert, **branche courante** (étiquette cliquable → § 7.4),
actualiser, compteur de changements.

Corps (structure VS Code) :
- champ de message de commit ;
- groupes : **Conflits** (si besoin), **Modifications indexées**,
  **Modifications**, **Fichiers non suivis** ;
- chaque ligne : icône du type de fichier, nom, chemin en gris,
  lettre/couleur de statut ;
- actions par ligne (popover § 10) : indexer / désindexer, **annuler les
  modifications** (confirmation obligatoire), ouvrir le fichier, ouvrir
  le diff, ajouter à `.gitignore` ;
- actions de groupe : tout indexer, tout désindexer, tout annuler
  (confirmation).

Bouton **Commit** (désactivé sans message ni changement indexé) avec
menu : *Commit & Push*, *Amend* (avertissement si déjà poussé).

### 7.3 Visionneuse de diff

Écran dédié : unifié par défaut, côte à côte en paysage. Coloration
syntaxique cohérente avec l'éditeur. Navigation entre blocs.
**Indexer / annuler un bloc** (hunk). Fichiers binaires et très gros
fichiers gérés. **Gouttière de diff dans l'éditeur** : barres colorées,
toucher une barre affiche le bloc et propose *Annuler ce bloc*.

### 7.4 Branches

Feuille « Branches » : recherche, sections *Récentes*, *Locales*,
*Distantes*. Actions : basculer, nouvelle branche à partir de…, renommer,
supprimer, fusionner dans la courante. Basculer avec modifications
locales : proposer stash ou bloquer avec message — jamais d'écrasement
silencieux.

### 7.5 Distant

*Fetch*, *Pull* (fusion ou rebase, au choix mémorisé), *Push* (propose
de définir l'amont à la première fois), indicateurs ↑↓ dans l'entête,
bouton **Synchroniser**. Progression visible, **annulation possible**.
Erreurs réseau/auth traduites en français. *Force push* : uniquement
`--force-with-lease`, derrière confirmation explicite.

### 7.6 Historique

Journal des commits avec graphe de branches. Détails d'un commit
(message, auteur, fichiers modifiés, diff). Historique d'un fichier.
Pagination paresseuse, pas de chargement complet.

### 7.7 Stash et conflits

Stash nommé / restauration / suppression. Conflits : liste dans le
groupe **Conflits**, résolution assistée (« Garder le mien », « Garder le
leur », « Garder les deux »), indexation automatique, *Abandonner la
fusion*.

## 8. Exigences transverses

- **Sécurité** : toute opération destructrice demande confirmation
  nominative. État cohérent en cas d'échec.
- **Réactivité** : aucune opération Git sur le fil principal ; annulable ;
  un verrou d'écriture par dépôt.
- **Cohérence** : un fichier ouvert modifié est enregistré avant une
  opération qui le touche ; l'onglet se recharge si le fichier change.
- **UI** : jetons de design de l'explorateur, textes français, cibles
  ≥ 48 dp, états vide / chargement / erreur.

## 9. Critères d'acceptation

1. `git status` affiché en temps réel (pas de scrutation, rafraîchi
   après enregistrement ou opération Git).
2. Commit fonctionne avec message + identity utilisateur.
3. Push/pull avec jeton chiffré, erreurs traduites.
4. Branches : bascule, création, fusion, suppression.
5. Diff unifié et côte à côte, gouttière éditeur.
6. Historique paginé, graphe de branches.
7. Stash et résolution de conflits.
8. `pkg install git` détecté et proposé si absent.
