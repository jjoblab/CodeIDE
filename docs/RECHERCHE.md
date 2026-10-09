# Spécification Recherche — section Recherche du tiroir CodeIDE

Référence de comportement : Android Studio « Find in Files » avec la
**structure de VS Code** (vue Source Control-like : champ de recherche
avec bascules, résultats groupés par fichier, vue liste/arbre).

## 1. Périmètre

La section Recherche du tiroir (deuxième destination du rail) gère :
recherche de texte dans tous les fichiers du projet, remplacement
multi-fichiers, recherche de fichiers par nom (« Go to File »).
**Hors périmètre** : recherche de symboles (LSP), correspondance
multiligne, index persistant.

## 2. Moteur

**Balayage Kotlin pur en flux** (ADR 0094) sur le chemin FUSE réel.
Port `RechercheMoteur` (`core:domain`), implémentation
`RechercheMoteurBalayage` (pur JVM, `java.nio`, coroutines).

## 3. Règles d'exclusion

- Toujours exclure : `.git/`, `build/`, `.gradle/`, `node_modules/`
- Respecter `.gitignore` (option activée par défaut)
- Ignorer les fichiers binaires (NUL dans les 8 premiers Ko)
- Taille max par fichier : 2 Mo (configurable)
- Plafond de résultats : 20 000 (comme VS Code)

## 4. Couleurs de surbrillance

| Élément | Sombre | Clair |
|---|---|---|
| Résultat dans la liste (`TEXT_SEARCH_RESULT_ATTRIBUTES`) | fond `#114957`, souligné `#165e70` | fond `#FCD47E`, texte noir |
| Résultat dans l'éditeur (`SEARCH_RESULT_ATTRIBUTES`) | fond `#2D543F` | à relire |

## 5. Icônes

`platform/icons/src/expui/inline` : `matchCase`, `exactWords`, `regex`,
`preserveCase`. `platform/icons/src/expui/general` : `search`, `filter`,
`collapseAll`, `expandAll`, `closeSmall`. `platform/icons/src/expui/actions`
: `replace`, `groupByFile`, `findBackward`, `findForward`. Licence
Apache 2.0.

## 6. Écrans

### 6.1 Fragment Recherche (saisie et résultats)

Entête : emblème orange, actions tout réduire / tout développer, effacer,
actualiser. Corps : champ de requête avec bascules *Aa*, mot entier,
*.\**, historique (20 dernières requêtes en DataStore) ; bouton pour
dérouler la ligne **Remplacer** (avec « conserver la casse ») ; zone
dépliable **Fichiers à inclure / à exclure** (globs séparés par
virgules) et **Portée** : Projet, Dossier courant, Fichiers ouverts.
Recherche en direct (≈ 300 ms de temporisation). Résumé « N résultats
dans M fichiers ». Résultats groupés par fichier (icône, nom, chemin,
compteur, repliable) puis lignes d'occurrence. Bascule liste/arbre.

### 6.2 Navigation (S3)

Toucher une occurrence ouvre le fichier à la ligne/colonne, occurrence
sélectionnée et visible, tiroir refermé. Boutons précédent/suivant.
Toutes les occurrences surlignées dans le fichier ouvert.

### 6.3 Remplacement (S4)

Aperçu avant application, exclusion d'une occurrence. Regex avec groupes
de capture (`$1`). Conservation de la casse. Confirmation avant « tout
remplacer ». Application par fichier atomique. Annulation via snackbar.

### 6.4 Recherche de fichiers par nom (S5)

Bascule **Texte | Fichiers**. Correspondance floue (initiales camelCase,
segments de chemin). Suffixe `:ligne`. Index des noms en mémoire.

## 7. Critères d'acceptation

1. Recherche en direct avec temporisation 300 ms.
2. Résultats groupés par fichier, repliables.
3. Plafond 20 000 avec message explicite.
4. Bascules Aa / mot entier / regex fonctionnelles.
5. Remplacement avec aperçu, exclusion, confirmation.
6. Navigation précédent/suivant depuis l'éditeur.
7. Go to File avec correspondance floue.
8. Annulation immédiate quand la requête change.
