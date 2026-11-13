# ADR 0018 — Mini-langage d'expressions : parseur maison, borné, sans évaluation de code

- Date : 2026-09-22 (étape 8, v0.9.0)
- Statut : accepté — **amendé le 2026-11-09** (lot L1 du chantier
  générateur : nombres et comparaisons ordonnées, cf. amendement en fin de
  document ; référencé par l'ADR 0153 §3)

## Contexte

Les manifestes de modèles (`template.json`) doivent exprimer des
**conditions** (visibilité d'un paramètre, inclusion d'un fichier) et des
**variables calculées** (ex. `estApplication = projectType == "application"`).
Le texte des fichiers `.tpl` porte des balises `{{#if expr}}`.

Le langage nécessaire est minuscule : identifiants, littéraux chaîne et
booléen, `==`, `!=`, `&&`, `||`, `!`, parenthèses. Mais il est alimenté par
des **assets** — des fichiers, pas du code compilé. Toute approche
générique d'évaluation (moteur d'expression en réflexion, `ScriptEngine`,
`eval`) transformerait un manifeste hostile en exécution arbitraire.

## Décision

1. **Parseur écrit à la main** (`ExpressionParser`) : lexique puis descente
   récursive sur une grammaire explicite (`ou → et → égalité → unaire →
   primaire`), documentée dans `Expression.kt`. Aucune dépendance de
   parsing générique, aucune réflexion, aucune évaluation de code fourni
   par le manifeste.

2. **Bornes de sécurité vérifiées avant la moindre récursion** :
   - longueur maximale d'une expression : 512 caractères ;
   - nombre maximal de jetons : 128 ;
   - profondeur d'imbrication maximale : 16 (chaque parenthèse ou négation
     consomme un cran — 15 parenthèses imbriquées passent, la 16e échoue).
   Un manifeste hostile ne peut ni saturer la pile ni diverger.

3. **Typage strict à l'évaluation** (`ExpressionEvaluator`) : valeurs
   `Chaine`/`Booleen` uniquement ; `&&`/`||`/`!` exigent des booléens,
   `==`/`!=` refusent les types mêlés — toutes les erreurs sont typées
   (`ExpressionException`) avec position, et listent les identifiants
   disponibles.

4. **Les erreurs sont françaises et positionnées** : elles alimentent les
   journaux et le bloc « copier les détails » ; l'UI (étape 10) les
   remplacera par des ressources.

5. **Même grammaire partout** : `visibleWhen` des paramètres, `when` des
   fichiers, `computed` du manifeste et `{{#if}}` des templates passent
   par le même analyseur — un seul dialecte à documenter
   (`docs/TEMPLATES.md`).

6. **Le curseur de jetons est sûr en fin de flux** : au-delà du dernier
   jeton, il tient la fin pour acquise (`getOrElse { Fin }`) — une
   expression tronquée échoue avec `ExpressionException` positionnée,
   jamais avec `IndexOutOfBoundsException`.

## Conséquences

- Le rendu ne peut échouer que sur des **valeurs** (identifiant inconnu à
  l'exécution, clé i18n absente) — toujours explicitement, jamais de
  `{{…}}` résiduel dans la sortie.
- Étendre le langage (nombres, comparaisons ordonnées) passera par la
  grammaire et ses tests exhaustifs — pas par une dépendance externe.
- Les manifestes des étapes futures (modèles Kotlin/Java, plugins)
  bénéficient d'un contrat stable et vérifié au chargement.

## Amendement (2026-11-09 — lot L1 du chantier générateur)

Les règles de projet déclaratives (ADR 0153 §3) exigent une comparaison
ordonnée (`targetSdk > plateformeMajeure`). L'extension annoncée ici est
livrée **dans la grammaire existante**, avec les mêmes garanties :

1. **Littéraux entiers non signés** (lèxème de purs chiffres, 32 bits au
   maximum) et **opérateurs `>`, `>=`, `<`, `<=`** — nouveau niveau
   `comparaison` entre l'égalité et l'uninaire ; même parseur maison,
   mêmes bornes 512/128/16.
2. **Valeur `Nombre`** (entier 32 bits) dans l'évaluateur : les
   comparaisons ordonnées exigent **deux nombres** — jamais de comparaison
   lexicographique de chaînes (piège « 9 » > « 36 »), jamais de coercition
   implicite ; erreur explicite positionnée sinon.
3. **Typage du contexte par valeur, uniforme** : un paramètre `BOOLEAN`
   reste booléen ; une valeur **entier canonique** (sans zéro de tête —
   « 026 » reste une chaîne pour que `{{param}}` ne mute jamais la saisie)
   est un nombre ; tout le reste reste une chaîne. Aucune expression
   existante du catalogue n'est affectée (vérifié : aucune ne compare des
   valeurs numériques) ; les tests du parseur et du moteur verrouillent la
   rétrocompatibilité.
4. Un mot commençant par un chiffre sans être un nombre est refusé au
   lexique (un identifiant légal n'y commence jamais — erreur explicite au
   lieu d'un identifiant inconnu à l'évaluation).
