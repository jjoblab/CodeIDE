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
