# Spécification Historique local — le filet de sécurité indépendant de Git

Référence de comportement : **Local History** d'Android Studio / IntelliJ
(comportement établi dans le code source — ré-implémentation intégrale).

Décisions : ADR 0104 (stockage), ADR 0105 (politique), ADR 0106
(capture + détection externe).

## 1. Périmètre

Un filet **indépendant de Git** : CodeIDE enregistre automatiquement
les versions successives des fichiers du projet, permet de **voir
l'historique** d'un fichier ou d'un dossier, **comparer**,
**restaurer** une version, **retrouver un fichier supprimé**, et poser
des **étiquettes**.

**Hors périmètre** : synchronisation entre appareils, historique des
fichiers binaires (entrées sans contenu), intégration aux commits Git
(les deux restent indépendants).

| Phase | Contenu | État |
|---|---|---|
| H0 | investigation, ADR 0104-0106, maquette, cette spécification | ✅ v0.82.0 |
| H1 | moteur (port + stockage + décorateur FileSystem) | ✅ v0.82.0 |
| H2 | afficher l'historique d'un fichier + restaurer | ✅ v0.87.0 |
| H3 | historique de dossier + fichiers supprimés + Modifications récentes | ✅ v0.88.0 |
| H4 | étiquettes (utilisateur + système) | à venir |
| H6 | réglages et entretien | à venir |

## 2. Architecture

| Brique | Emplacement | Note |
|---|---|---|
| Port `HistoriqueLocal` + modèles | `core:domain` | JVM pur |
| `MoteurHistoriqueLocal` | `core:domain` | blobs + index JSON, atomicité par renommage |
| `HistoriqueFileSystem` | `core:domain` | décorateur du port `FileSystem` |
| `SourceProjetHistorique` | `core:domain` | racine du projet ouvert |
| Liaison décorée | `core:storage` | `StorageBindsModule` |
| UI (H2+) | `feature:editor` | popover § 10 + visionneuse de diff partagée |

Stockage : `files/historique/<empreinte-racine>/` — `index.json`
(entrées) + `blobs/<sha256>` (contenus dédupliqués). Jamais dans le
dossier du projet. Politique par défaut : **5 jours**, **256 Mo** par
projet, **2 Mo** par fichier, secrets toujours exclus.

## 3. Modèle des entrées

Chaque entrée porte : identifiant, **chemin relatif au projet**,
horodatage, type, empreinte SHA-256 du contenu AVANT (ou `null` :
binaire/trop grand — « contenu indisponible »), taille, libellé
(ancien nom pour un renommage).

| Type | Quand | Contenu AVANT |
|---|---|---|
| MODIFICATION | écriture d'un fichier existant | contenu précédent |
| CRÉATION | premier enregistrement d'un chemin | — |
| SUPPRESSION | suppression d'un fichier | dernier contenu (pierre tombale) |
| RENOMMAGE | renommage | contenu courant (blob dédupliqué) |
| EXTERNE | écart détecté à l'ouverture/rafraîchissement | dernière révision connue |
| RESTAURATION | (H2, automatique via l'écriture) | contenu écrasé |
| ÉTIQUETTE | (H4) libellé nommé, sans blob | — |

**Pas d'entrée** si le contenu est identique à la dernière révision du
même chemin. La **restauration est annulable par construction** :
réécrire via le port crée la révision « avant restauration ».

## 4. Comportements (H1 — moteur)

1. **Capture transparente** : toutes les écritures/suppressions/
   renommages du port FileSystem du projet ouvert produisent des
   entrées — sans qu'aucun appelant ne change.
2. **Silence obligatoire** : un échec d'historique n'échoue JAMAAS
   l'opération de fichier (journal seul).
3. **Intégrité** : blob écrit puis renommé, index réécrit puis renommé
   — la mort du processus ne corrompt rien ; index corrompu → historique
   vide, blobs orphelins purgés.
4. **Purge** : à l'ouverture du projet (E/S, hors fil principal) — par
   âge (5 jours), par quota (256 Mo, plus vieilles entrées d'abord),
   par nombre (5 000) ; les blobs sans entrée référencée sont supprimés.
5. **Exclusions** (chemin relatif) : `build/`, `.gradle/`, `.git/`,
   `.codeide/`, `.idea/`, `node_modules/`, et les **secrets**
   (`local.properties`, `*.jks`, `*.keystore`, `*.env`, `*.pem`,
   `*.p12`, `*.key`, `google-services.json`) — jamais capturés.
6. **Lecture** : `listerRevisions(chemin)` renvoie les entrées du
   chemin (les plus récentes d'abord) ; `lireContenu(id)` le texte
   stocké, ou `null` (binaire/trop grand — l'interface le dit).

## 5. Interface (H2 — aperçu, H3 — dossier et supprimés)

- Menu contextuel de l'explorateur (popover § 10) et menu de l'éditeur :
  **« Afficher l'historique »** (fichiers ET dossiers — l'arbre privé
  n'est pas capturé, aucune entrée) ; la RACINE du projet ouvre
  **« Modifications récentes »** (tout le projet).
- Vue : liste des révisions (date/heure relative, taille, type,
  étiquette éventuelle), **diff** entre une révision et le contenu
  actuel ou la révision précédente (composant PARTAGÉ `core:ui` — la
  future section Git s'en sert aussi), **restaurer** avec confirmation
  (« Restaurer cette version ? La version actuelle sera conservée dans
  l'historique »).
- Modes dossier/projet : rangées au NOM DU FICHIER (les révisions
  mélangent les fichiers) ; **filtre « Supprimés seuls »** — les
  pierres tombales retrouvables d'un coup ; la « révision précédente »
  d'une sélection est la plus ancienne du MÊME chemin.
- **Fichier supprimé** : restaurer une pierre tombale RECRÉE le
  fichier dans son dossier d'origine (parent résolu segment par
  segment — jamais d'URI SAF construite) ; impasse honnête si le
  dossier parent a disparu ; « Annuler » resupprime immédiatement.
- Dates relatives lisibles (« il y a 4 min », « hier 14:02 »,
  « 3 oct. à 09:12 »).

## 6. Critères d'acceptation (H1)

1. Décorateur : toute mutation du port produit une entrée (vérifié en
   JVM avec `FakeFileSystem` + moteur réel sur dossier temporaire).
2. Déduplication : N révisions identiques = 1 blob.
3. Pierre tombale : un fichier supprimé reste lisible dans
   l'historique.
4. Aucune entrée à contenu identique.
5. Écriture interrompue simulée : index intact, blob orphelin purgé.
6. Purge : par âge (horloge factice), par quota, par nombre ; secrets
   jamais stockés.
7. Chaîne de vérification verte sur les modules touchés.

## 7. Limites assumées (affichées, pas cachées)

- Contenu des binaires et fichiers > 2 Mo : entrée sans contenu
  (« Contenu indisponible »).
- Dossier supprimé : les fichiers du dossier ne reçoivent pas de
  tombstone individuelle en H1 (l'annulation d'explorateur existante
  couvre l'immédiat ; H3 ajoute la vue d'ensemble).
- Terminal/git : détectés comme « Changement externe » à
  l'ouverture/rafraîchissement, pas en direct.
- Les listes de dossier/projet sont BORNÉES (200 révisions, les plus
  récentes) — l'historique complet vit dans le stockage, la feuille
  n'en rend qu'une fenêtre (H3).
