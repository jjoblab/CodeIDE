# ADR 0157 — Outil `tools/generateur` : commandes multiples et matrice dérivée des manifestes

- **Statut** : accepté (chantier générateur/Nouveau projet — lot L0) ;
  **implémenté au lot L2** (v0.210.0) — cinq commandes, protocole
  `PROTOCOLE=2`, matrice dérivée, lint, déterminisme, script refondu,
  cas nommés déplacés dans `scripts/verify-templates-cas-nomes.json`.
- **S'appuie sur** : ADR 0019 (harnais JVM, plan figé déversé), ADR 0153
  (contraintes), ADR 0155 (étapes), ADR 0156 (`commun:`).
- **Contexte** : l'audit L0 confirme (C1-C4) que la matrice des 37
  combinaisons est **codée à la main** dans un bloc Python intégré à
  `scripts/verify-templates.sh` (aucune lecture des manifestes), que la CLI
  n'a qu'un seul mode (`--assets --sortie --combos --annee --auteur
  --generateur`), que la documentation est périmée (README « v0.10.0 »,
  `Module.md` limité à kotlin-jvm/java, KDoc de `Combinaisons.kt` idem,
  en-tête du script affirmant « tous les triplets langage × type × build »)
  et que le cas « outillage manquant » n'est documenté nulle part.
  Ajouter une option à un modèle ne signale aucun trou de couverture.

## Décision

### 1. Cinq commandes, protocole de sortie versionné

`GenerateurModeles.kt` devient multi-commandes : `lister`, `generer`,
`matrice`, `valider-manifestes`, `determinisme`. Le protocole historique
(`OK <id> (n fichiers)` / `ECHEC <id> : <détail>` / `GENERE ok/total`,
codes 0/1/2) est **conservé** et explicitement versionné par une ligne
`PROTOCOLE=2` en tête de sortie ; les rapports nouveaux (lint, matrice,
déterminisme) sortent en **JSONL** (une ligne JSON par objet), consommable
par le script et par la CI.

- `lister [--modele <id>] [--json]` : modèles, paramètres (type, défaut,
  choix, `visibleWhen`, `enabledWhen`, `section`, `groupe`, `avance`,
  `persist`), étapes `wizard`, préréglages, contraintes — l'état réel des
  manifestes, plus aucun résumé à la main.
- `generer --modele <id> [--param k=v]… [options communes]` : génération
  **unitaire** par arguments (nom, description, options
  readme/gitignore/editorconfig/licence/langue, renommages) — même plan
  figé, même protocole `OK/ECHEC/GENERE`.
- `matrice --strategie exhaustif|paires|bornes [--sortie combos.json]` :
  **dérive les combinaisons des manifestes**. Stratégies :
  - `exhaustif` quand l'espace est petit (seuil configuré, défaut ≤ 64) ;
  - `paires` (pairwise) : couverture de toutes les paires de valeurs —
    algorithme glouton maison (aucune dépendance nouvelle, règle 9),
    testé par **propriété** (toute paire atteignable est couverte) ;
  - `bornes` : chaque valeur de chaque paramètre au moins une fois.
  Respecte `visibleWhen`, `enabledWhen` et les contraintes : un paramètre
  masqué ou désactivé **ne multiplie pas** les cas ; les combinaisons
  contrevenant à une règle `ERREUR` sont exclues (et signalées). Sortie
  JSON (`templateId`, params, options communes, étiquette de cas) consommée
  directement par le script.
- `valider-manifestes [--json]` : **lint** — chaque paramètre, choix, règle,
  groupe, étape et préréglage a ses clés i18n en `fr` **et** en ; tout
  identifiant cité dans `when`/`visibleWhen`/`enabledWhen`/`quand`/`autoDefinit`
  existe ; toute source (`files/…` et `commun:…`) existe ; aucun chemin
  dupliqué ; tout choix déclaré est **atteignable** (aucun choix d'un
  `CHOICE` rendu impossible par les visibilités combinées) ; icônes logiques
  connues de `core:ui` ; bornes respectées. Code de sortie non nul au
  moindre écart — c'est lui qui sécurise les lots L3-L5.
- `determinisme --combos <fichier>` : deux générations successives
  comparées **octet pour octet** (arborescences complètes), échec explicite
  sinon.

### 2. `verify-templates.sh` refondu (C-1, C-2)

- La matrice vient de `generateur matrice` (JSON) ; le script ne contient
  **plus** de bloc Python de combinaisons. Seuls les **cas nommés de
  régression** restent écrits à la main (noms et descriptions hostiles,
  couverture des langues/licences, renommages `kt-app-renoms`) — injectés
  dans la matrice comme cas additionnels étiquetés.
- Conservés : cache `.verifie` + empreinte SHA-256 des assets, filtre
  `CODEIDE_COMBINAISONS`, code de sortie non nul au moindre échec,
  vérifications structurelles (aucun `{{` résiduel, BOM, LF/CRLF, checksum
  wrapper), commandes de build par famille.
- Ajoutés : **rapport Markdown** (tableau combinaison → résultat, durée)
  et **JUnit XML** pour la CI ; message explicite (exit 1 avec conduite à
  tenir) si JDK 17/21, Maven ou SDK manquent — jamais de contournement
  (C-4) ; en-tête du script réécrit (plus d'affirmation « triplets »
  globale), la doc étant traitée au même lot (README, `Module.md`, KDoc
  `Combinaisons.kt`, `docs/TEMPLATES.md`).

### 3. Couverture minimale par modèle (C-3)

- **Toutes** les combinaisons valides passent les tests de **structure sans
  compilation** (génération dry-run, rapide) : aucun `{{` résiduel, chemins
  valides, aucun doublon, déterminisme, **invariants d'effet** (un fichier
  `.kt` ssi langage Kotlin ; permission `INTERNET` ssi Retrofit ;
  `NavHostFragment` ssi Navigation ; configuration Actuator ssi Actuator… —
  table tenue dans `docs/TEMPLATES.md`).
- Les tests **unitaires** du générateur de matrice portent la propriété de
  couverture des paires et le respect des masquages.
- Les **builds réels** couvrent au minimum la couverture `paires` de chaque
  modèle (budget : stratégies filtrables par `CODEIDE_COMBINAISONS`).

## Conséquences

- Ajouter une option à un modèle **signale** immédiatement le trou de
  couverture (la matrice est dérivée) et, si les clés i18n manquent, échoue
  au lint.
- La dette documentaire de l'outil est soldée en L2 (C-2/C-4) ; la section
  « Si l'outillage manque » entre dans `docs/TEMPLATES.md`.
- L'API du module `:tools:generateur` (analyse d'arguments, sorties) change :
  tests existants mis à jour, protocole historique maintenu pour la partie
  génération.

## Amendements d'implémentation (L2)

- **Lint i18n** : les clés i18n **référencées** par le manifeste (libellés,
  aides, messages de règles, groupes, raisons de désactivation) sont
  obligatoires en `fr` **et** `en` ; les clés optionnelles des choix
  (`param.<id>.choix.<v>.*`) sont contrôlées en **symétrie** (présente d'un
  côté → présente des deux) — l'absence même de `libelle` reste permise
  (repli sur la valeur brute, ADR 0154 §1) tant que l'i18n de choix n'est
  pas généralisé aux manifestes (lots L3/L4).
- **Chemins dupliqués** : déjà refusés au chargement du manifeste (parseur,
  TEMPLATES.md) — le lint n'a rien à redoubler, un catalogue qui arrive à
  l'évaluation n'en contient pas.
- **Outils absents** : le script annonce en tête les outils manquants
  (conduite à tenir) et fait échouer **les combinaisons qui les exigent**
  (code de sortie non nul) sans bloquer les familles vérifiables —
  `CODEIDE_COMBINAISONS` découpe les preuves par tranches ; jamais de
  contournement silencieux.
- **`generer` unitaire** : au-delà du lot `--combos`, un projet unique se
  génère par arguments (`--modele`, `--param k=v`, options communes) — même
  plan figé, même protocole.
- **Masquage et `autoDefinit`** : la matrice évalue chaque candidat comme un
  formulaire laissé au moteur (aucun paramètre « modifié à la main ») — les
  dépendances automatiques peuvent donc forcer leurs cibles, et les
  combinaisons émises portent les valeurs **effectives** (jamais la valeur
  choisie d'un paramètre que le moteur a corrigé).
