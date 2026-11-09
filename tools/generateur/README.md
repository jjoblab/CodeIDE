# tools/generateur — Outil des modèles : matrice, génération, lint, déterminisme

> Statut : créé à l'étape 9 (v0.10.0, ADR 0019) ; multi-commandes au lot L2
> (v0.210.0, ADR 0157).

Outil Kotlin JVM en ligne de commande, **hors application Android** : il
lit les **vrais** assets embarqués du dépôt (`app/src/main/assets`) en
réutilisant tel quel le moteur de templates de `core:domain` — ce que
l'outil écrit est exactement ce que l'application écrirait, à l'octet près
(plan figé de `PlanProjectCreationUseCase`, ADR 0017).

## Les cinq commandes (protocole `PROTOCOLE=2`)

Toute exécution commence par la ligne `PROTOCOLE=2` (version du protocole
de sortie, ADR 0157 §1). Codes de sortie : `0` succès, `1` échec explicite,
`2` arguments invalides.

| Commande | Rôle | Sortie |
|---|---|---|
| `lister [--modele <id>] [--json]` | l'état réel des manifestes : paramètres (type, défaut, choix, `visibleWhen`, `enabledWhen`, section, groupe, avance, persistance), variables calculées, contraintes, `autoDefinit`, fichiers | JSON (tableau, `--json`) ou texte lisible |
| `generer --combos <fichier.json> …` | génération sur disque d'un **lot** de combinaisons | `OK <id> (n fichiers)` / `ECHEC <id> : <détail>`, puis `GENERE <ok>/<total>` (protocole historique conservé) |
| `generer --modele <id> [--param k=v]… …` | génération d'un **projet unitaire** par arguments | `OK` / `ECHEC` |
| `matrice [--strategie exhaustif\|paires\|bornes] [--sortie combos.json]` | **dérivation des combinaisons depuis les manifestes** (ADR 0157 §1) : paramètres énumérables (`BOOLEAN`, `CHOICE`), masquages respectés (un paramètre masqué ou désactivé ne multiplie pas les cas), règles `ERREUR` exclues et signalées | JSON des combinaisons (fichier ou sortie standard) ; exclusions en JSONL sur la sortie d'erreur ; ligne résumé `MATRICE <modeles> <candidats> <retenues> <exclues>` |
| `valider-manifestes` | **lint** : clés i18n référencées en `fr` **et** `en` (et jamais d'asymétrie), expressions évaluables (identifiants inconnus, bornes), sources existantes, choix `CHOICE` tous atteignables | JSONL des écarts + `VALIDATION <modeles> <ecarts>` ; code non nul au moindre écart |
| `determinisme --combos <fichier.json> --sortie <dir>` | deux générations successives comparées **octet pour octet** (arborescences complètes) | JSONL des bilans + `DETERMINISME <ok>/<total>` |

La lecture des combinaisons tolère les clés inconnues (`PROTOCOLE=2`) :
`matrice` peut enrichir ses entrées (champ `etiquette`) sans casser
`generer`.

### Stratégies de matrice

- `exhaustif` — tout l'espace valide, quand il est petit (seuil configuré,
  `--seuil`, défaut 64) ; au-delà, refus explicite ;
- `paires` (défaut) — **couverture de toutes les paires de valeurs
  atteignables**, algorithme glouton maison (aucune dépendance nouvelle,
  règle 9), propriété testée par les tests unitaires ; la garde de fin
  compare les paires couvertes aux paires atteignables ;
- `bornes` — chaque valeur de chaque paramètre au moins une fois (la
  combinaison « tout par défaut » d'abord).

Les dérivations sont **déterministes** : même manifeste + même stratégie →
même matrice.

## Exemples

```bash
# État des manifestes (JSON) puis lint complet :
./gradlew :tools:generateur:run --args="lister --assets app/src/main/assets --json"
./gradlew :tools:generateur:run --args="valider-manifestes --assets app/src/main/assets"

# Matrice pairwise consommée par verify-templates.sh :
./gradlew :tools:generateur:run --args="matrice --assets app/src/main/assets \
    --strategie paires --sortie build/combos.json"

# Génération d'un projet isolé (débogage d'une combinaison) :
./gradlew :tools:generateur:run --args="generer --assets app/src/main/assets \
    --modele spring-boot --sortie build/modele --annee 2026 --auteur 'Ada Lovelace' \
    --generateur 0.210.0 --nom 'Api Démo' --langue fr --licence mit --param includeActuator=true"

# Déterminisme d'un lot :
./gradlew :tools:generateur:run --args="determinisme --assets app/src/main/assets \
    --combos build/combos.json --sortie build/det --annee 2026 --auteur CodeIDE --generateur 0.210.0"
```

(En local, préfixer `JAVA_HOME=/home/z/tools-jdk ./gradlew -Pfreres.sources …`
si l'environnement l'exige ; le répertoire `--assets` doit exister.)

## Intégration `verify-templates.sh`

`scripts/verify-templates.sh` appelle l'outil trois fois : `matrice`
(matrice dérivée + cas nommés `scripts/verify-templates-cas-nomes.json`),
`generer`, `determinisme` — puis compile, teste et exécute chaque projet
généré avec les vrais outils. Les combinaisons déjà vérifiées (marqueur
`.verifie` + empreinte des assets) sont sautées ; le filtre
`CODEIDE_COMBINAISONS` découpe par tranches ; les rapports
`rapport.md`/`rapport-junit.xml` portent le détail.

## Si l'outillage manque

Les outils requis par les modèles (JDK 17/21, Maven, SDK Android, Gradle)
sont annoncés en tête d'exécution avec la **conduite à tenir** (variable
`CODEIDE_*` à exporter ou outil à installer) ; les combinaisons qui les
exigent échouent explicitement et font échouer le script — **jamais de
contournement, jamais de vérification désactivée** (ADR 0157 §2). Les
familles vérifiables restent prouvables par tranches via
`CODEIDE_COMBINAISONS` ; une impossibilité (ex. réseau bloqué vers Maven
Central) se **signale dans le rapport**, elle ne se contourne pas.

## Dépendances

- `:core:domain` (moteur de templates, cas d'usage) ;
- `kotlinx-serialization-json` (combinaisons et rapports JSONL).

Le module applique la convention `codeide.kotlin.library` (detekt, spotless,
kover, `explicitApi`) — l'exemption detekt ciblée pour l'impression console
est documentée dans `config/detekt/detekt-sortie-console-autorisee.yml` :
la sortie standard de l'outil est le **résultat**, pas une journalisation.

## Tests

```bash
./gradlew :tools:generateur:test
```

- `MatriceModelesTest` : propriétés de la matrice sur le modèle
  *fixture-matrice* — couverture des paires, respect des masquages
  (`visibleWhen`/`enabledWhen`), exclusions `ERREUR` signalées, stratégie
  `bornes`, dépendances automatiques, déterminisme, seuil ;
- `ValidateurManifestesTest` : zéro écart sur le catalogue embarqué réel
  (la porte des lots L3-L5) et un écart précis par défaut injecté dans des
  assets bâtis à la volée ;
- `InvariantsEffetModelesTest` : structure sans compilation sur TOUTES les
  combinaisons des matrices réelles (dry-run) — aucun `{{` résiduel, chemins
  valides sans doublon, déterminisme, **invariants d'effet** (table dans
  `docs/TEMPLATES.md`) ;
- `GenerateurModelesTest` : pipeline complet sur fixtures, protocole
  `PROTOCOLE=2`, deux modes de `generer`, erreurs d'usage.
