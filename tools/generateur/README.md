# tools/generateur — Harnais de génération sur disque

> Statut : créé à l'étape 9 (v0.10.0, ADR 0019).

Outil Kotlin JVM en ligne de commande, **hors application Android** : il
produit sur disque des projets générés depuis les **vrais** assets embarqués
du dépôt (`app/src/main/assets`), en réutilisant tel quel le moteur de
templates de `core:domain`.

## Pourquoi ce module

La validation réelle des modèles (étape 9, section 11 du prompt maître)
exige de générer les combinaisons sur disque puis de les **compiler, les
tester et les exécuter** avec les vrais outils. Le choix retenu (ADR 0019) :
un module JVM dédié plutôt qu'un test Robolectric piloté par variables
d'environnement — invocation directe, protocole de sortie explicite, aucune
machinerie Android dans la boucle.

Ce que l'outil écrit est **exactement** ce que l'application écrirait : le
plan figé de `PlanProjectCreationUseCase` (ADR 0017), déversé sur disque
au lieu du SAF.

## Usage

```bash
./gradlew :tools:generateur:run --args="--assets app/src/main/assets \
    --sortie build/modeles --combos build/combos.json \
    --annee 2026 --auteur 'Ada Lovelace' --generateur 0.10.0"
```

`--combos` désigne un JSON d'entrées `Combinaison` (voir `Combinaisons.kt`)
produit par `scripts/verify-templates.sh`. Protocole de sortie : une ligne
`OK <id>` ou `ECHEC <id> : <détail>` par combinaison, puis
`GENERE <ok>/<total>` ; code de sortie non nul au moindre échec.

## Dépendances

- `:core:domain` (moteur de templates, cas d'usage) ;
- `kotlinx-serialization-json` (lecture du fichier de combinaisons).

Le module applique la convention `codeide.kotlin.library` (detekt, spotless,
kover, `explicitApi`) — l'exemption detekt ciblée pour l'impression console
est documentée dans `config/detekt/detekt-sortie-console-autorisee.yml` :
la sortie standard de l'outil est le **résultat**, pas une journalisation.

## Tests

```bash
./gradlew :tools:generateur:test
```

Port d'assets sur fichiers (garanties de sécurité identiques à
l'implémentation Android), décodage des combinaisons, et pipeline complet
sur un modèle *fixture* (génération réelle en répertoire temporaire).
