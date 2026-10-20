# Stratégie de vérification et de collaboration multi-agents

> **Prime sur toutes les directives de vérification d'AGENTS.md**
> (section « Commandes », « Workflow d'agent IA asynchrone » §0, §1, §7, §7.1).
> En cas de contradiction, CE fichier gagne. L'historique complet des
> directives remplacées est conservé dans
> `docs/archive/AGENTS_VERIFICATION_HISTORIQUE.md`.
>
> Directives du 2026-10-19 (fondées sur les mesures ci-dessous, section 6).
> Auteur : jjoblab. ADR associé : 0130 (CI en trois jobs).

## 1. Principe

**Le local attrape le bon marché et le déterministe ; la CI GitHub porte le
lourd. Le local ne remplace jamais la CI.**

- Local = ce qui échoue en secondes : formatage (spotless), analyse
  statique (detekt), frontières de modules (checkModuleDependencies),
  compilation et tests unitaires des modules touchés. Objectif : ne
  jamais pousser un commit qui va rouler la CI pour rien.
- CI = ce qui est lourd ou nécessite un environnement sain : lintDebug
  complet, koverVerify, assembleDebug, APK signé, garantie
  from-scratch, intégration des paquets publiés (binaire Packages).
- Un push n'est jamais « vérifié » : il est *couvert* par la CI. Tant
  que le run n'est pas vert, le chantier n'est pas terminé.
- L'inverse est vrai aussi : la CI n'est pas un bac à sable. Un
  push sous-optimal coûte 20 à 30 minutes de runner ; d'où les règles
  des sections 2 à 4.

## 2. Chaîne LOCALE (toujours)

À la racine du dépôt, AVANT tout push, dans cet ordre :

```bash
source scripts/env.sh
./gradlew spotlessApply          # 1. formatage — JAMAIS de push sans lui
./gradlew spotlessCheck          # 2. vérif formatage (contrôle, pas réparation)
./gradlew detekt                 # 3. analyse statique RACINE (tous modules)
./gradlew checkModuleDependencies  # 4. frontières de modules RACINE
./gradlew checkUiTokens           # 5. littéraux UI à CLIQUET (ADR 0127 §5 :
                                   #    la baseline ne peut que descendre)
./gradlew :<mod>:compileDebugKotlin :<mod>:compileDebugUnitTestKotlin \
          :<mod>:testDebugUnitTest   # 6. modules TOUCHÉS seulement
```

Le script `scripts/modules-touches.sh` imprime la liste des modules
touchés à partir de `git diff --name-only` (voir section 3) :

```bash
./gradlew $(./scripts/modules-touches.sh | sed 's/^/:/; s#/#:#g; s/$/:compileDebugKotlin/' )
```

**INTERDIT en local** (réservés à la CI, ADR 0130) :

- `assembleDebug` — jamais ; la CI assemble et signe l'APK à chaque
  push, l'APK du run est le produit ;
- `lintDebug` — jamais (15 à 20 minutes locales pour un résultat que
  la CI produit en parallèle) ;
- `koverVerify` — jamais (dépend des données de tous les tests) ;
- les tests des modules **non touchés** — la CI les porte ; seules
  exceptions : les modules qui dépendent d'une constante ou d'une API
  que le chantier a changée (catalogue partagé, port public — voir
  section 3).

Historique fondateur (détails dans l'archive) : v0.31.1 (chaîne légère
+ assembleDebug), v0.79.0 (chaîne complète par commit), v0.120.1
(checkModuleDependencies local obligatoire, assembleDebug plus
bloquant), v0.134.27 (le detekt RACINE attrape ce que la chaîne
par-module laisse passer), v0.134.40 (vérification proportionnée au
type de changement — premier push vert du premier coup).

## 3. Quand passer à la compilation RACINE

La chaîne RACINE complète hors assembleDebug —
`spotlessApply && spotlessCheck detekt checkModuleDependencies` +
`compileDebugKotlin compileDebugUnitTestKotlin testDebugUnitTest` des
modules touchés — est OBLIGATOIRE dès que le chantier touche :

- le **port ou la signature publique d'un module consommé ailleurs**
  (tout `:core:*` dès qu'un autre module compile contre lui) ;
- un fichier `build.gradle.kts` (quel que soit le module) ;
- le **catalogue** `gradle/libs.versions.toml` ;
- les **plugins** (`build-logic/**`) ;
- `.github/workflows/ci.yml` ou l'outillage de build partagé
  (`gradle.properties`, `settings.gradle.kts`, `config/**`) ;
- un **refactor transversal** (plusieurs modules, renommage global).

En cas de doute → chaîne RACINE. Le coût local d'une chaîne racine
verte (5 à 10 minutes) est toujours inférieur à un aller-retour CI
rouge (25 à 60 minutes, humain inclus).

### scripts/modules-touches.sh

Établit la correspondance « fichiers modifiés → coordonnées Gradle » :

```bash
./scripts/modules-touches.sh                  # arbre de travail vs HEAD
./scripts/modules-touches.sh origin/main...HEAD  # série de commits vs main
```

Sortie : une coordonnée par module touché (`:feature:editor` …) ;
`RACINE` si un fichier de build partagé est touché (→ chaîne racine
obligatoire) ; rien pour `docs/**`, `**.md`, `.github/**`, `scripts/**`
(docs et outillage n'affectent pas le graphe de modules).

## 4. Boucle CI

- **Pousser par SÉRIE de chantiers : un push, pas un par chantier.**
  Les commits s'empilent localement (chaque chantier garde sa chaîne
  locale verte) et le push part quand la série est cohérente. Un push
  par chantier paie N fois le setup du runner (~2 min) et annule le
  run précédent en vol (`cancel-in-progress`) — gâchis double.
- **Surveiller le run via l'API toutes les 5 minutes, sans bloquer** :
  `GET /repos/jjoblab/CodeIDE/actions/runs?per_page=5`. Le chantier
  suivant démarre immédiatement en parallèle ; l'agent ne reste jamais
  inactif devant une CI en vol.
- **En cas d'échec : lire le log AVANT de corriger.**
  `GET /runs/{id}/jobs` → job en échec →
  `GET /jobs/{job_id}/logs` → lignes `##[error]` / `FAILED` /
  `* What went wrong:`. Corriger par commit `fix(ci): …` (ou dans le
  chantier en cours s'il est concerné). Jamais de correctif à
  l'aveugle : un reroll sans lecture reproduit l'échec.
- CI rouge persistante : le chantier suivant re-base
  (`git pull --rebase`) avant son propre push. Jamais de force-push.

## 5. Collaboration multi-agents

- **Une branche et une PR par agent.** Nom : `agent/<sujet>`
  (ex. `agent/ia-5-editeur`, `agent/preview-attributs`). La tête de PR
  déclare la **propriété des modules** : « je touche X, Y, Z — rien
  d'autre ». Les fichiers hors propriété déclarée sont des conflits
  assumés et reprochés.
- **Fichiers partagés réservés à l'agent INTÉGRATEUR** (un seul agent
  à la fois, c'est lui qui pousse `main`) :
  `version.properties`, `CHANGELOG.md`, `AGENTS.md`,
  `gradle/libs.versions.toml`, `settings.gradle.kts`,
  `.github/workflows/ci.yml`. Un agent de chantier qui a besoin de
  l'un de ces fichiers ouvre un ticket dans sa PR (« dépendance
  intégration : bump tooling 1.0.1 ») et NE les touche PAS.
- **Version et CHANGELOG faits à l'intégration seulement** : l'agent
  intégrateur bump la version (`scripts/bump-version.sh`), écrit
  l'entrée CHANGELOG (une entrée par lot intégré, pas par PR) et met
  à jour `docs/ROADMAP.md`. Les branches de chantier ne contiennent
  donc JAMAIS de conflit de version.
- **Chantiers sur les ports `:core:*` sérialisés** : deux agents ne
  peuvent pas étendre le même port en parallèle (fusions garanties
  rouges). La file d'attente est tenue par l'intégrateur ; les
  chantiers UI/feature parallèles se coordonnent par propriété de
  modules.
- **Seul le propriétaire d'une branche corrige sa CI rouge** : un
  agent tiers ne pousse JAMAIS un `fix(ci)` sur la branche d'un autre ;
  il signale dans la PR. Sur `main`, l'intégrateur seul corrige.
- **Le moteur LSP se travaille dans `codeide-lsp`** (dépôt frère) :
  aucun edit de moteur dans CodeIDE. Les écrans/ports côté CodeIDE
  restent ici (frontières ADR 0122/0128).
- **Seul l'intégrateur fait `scripts/bump-frere.sh`** : l'alignement
  des versions frères (catalogue + publication Packages) est une
  opération d'intégration, jamais un effet de bord de chantier.

## 6. Mesures (étape 0 — 2026-10-19) et leçon

### 6.1 Dix derniers runs de `ci.yml` (API GitHub, `/actions/runs`)

| Run | Commit | Branche | Résultat | Durée totale |
|---|---|---|---|---|
| 39158064126 | 68a061e | main | success | **29,8 min** |
| 39158063958 | 68a061e | v0.145.0 | success | **21,7 min** |
| 39151334049 | 790bd1a | main | success | 9,3 min |
| 39151333834 | 790bd1a | v0.144.0 | success | 7,5 min |
| 39145673325 | b6e78ad | main | success | 21,5 min |
| 39145673290 | b6e78ad | v0.143.1 | success | **29,7 min** |
| 39140535564 | ea89287 | v0.143.0 | failure | 2,0 min |
| 39140533317 | ea89287 | main | failure | 3,0 min |
| 39140441896 | 0e3975f | v0.142.0 | failure | 1,8 min |
| 39140440301 | 0e3975f | main | cancelled | 1,5 min |

Médiane des runs success ≈ 21,6 min ; aucun temps d'attente de runner
(attente ≈ 0 min — la capacité GitHub n'est pas le goulot).

### 6.2 Durées par step (runs success 39158064126 / 39158063958)

| Step | Run main (29,8 min) | Run tag (21,7 min) |
|---|---|---|
| Gradle setup (cache wrapper + dépendances) | 47 s | 1 min 10 s |
| **Vérification complète (sans clean)** | **26 min 34 s** | **20 min 17 s** |
| Post Gradle (sauvegarde caches) | 2 min 15 s | — |

Le step « Vérification complète » = **~90 % de la durée du run**.
Tout gain doit venir de lui.

### 6.3 Cache Gradle (résumé `gradle/actions/setup-gradle`)

- Tâches **FROM-CACHE : 707** (resp. 704) — soit ~27 % des ~2 600
  tâches actionnables ; **UP-TO-DATE : ~233** ; le reste exécuté.
- Entrées restaurées : `gradle-home-v1`, `gradle-dependencies`,
  `gradle-transforms`, `gradle-kotlin-dsl`, `gradle-wrapper-zips`,
  `gradle-instrumented-jars`, `gradle-groovy-dsl`,
  `gradle-generated-gradle-jars` — le cache est sain et complet.
- Répartition FROM-CACHE : `:core` 289, `:feature` 274, `:tooling` 54,
  `:applog-runtime` 33, `:preview` 26, `:app` 25.
- **Verdict** : le cache ne manque pas ; c'est le **parallélisme** qui
  manque (voir 6.4).

### 6.4 Runner

- Image : **Ubuntu 24.04.5**, Hosted Compute Agent 20261012.601,
  Azure (régions eastus / westus3), groupe `GitHub Actions`
  (runner hébergé standard `ubuntu-latest`).
- Spec officielle GitHub du runner standard `ubuntu-latest` :
  **4 vCPU / 16 Go de RAM** → condition de l'ADR 0130 confirmée
  (pas d'injection `nproc`/`free` : un run parasite pour lire ce que
  la spec documente n'était pas justifié ; la lecture des logs
  confirme le label et l'image).
- **Constat décisif** : `gradle.properties` est réglé pour la sandbox
  locale 4 Go / 2 cœurs — `org.gradle.parallel=false`,
  `org.gradle.workers.max=1`, tas démon 2536m,
  `kotlin.compiler.execution.strategy=in-process`. La CI exécute donc
  la chaîne **séquentielle** sur une machine 4 cœurs : un cœur
  travaille, trois dorment. C'est la cause principale des 20-30 min.
  L'ADR 0130 corrige par options CLI (gradle.properties inchangé),
  et la chaîne est éclatée en 3 jobs parallèles.

### 6.5 Avant / après (ÉTAPE 3 — premier run ADR 0130, 2026-10-19)

| | Avant (chaîne séquentielle, 1 job) | Après (3 jobs parallèles, ADR 0130) |
|---|---|---|
| Durée murale, run `main` | 29,8 min (run 39158064126) | **5,8 min** (run 39162386499) |
| Durée murale, run tag | 21,7 min (run 39158063958) | **5,1 min** (run 39162388597, v0.145.1) |
| Découpage | 1 job — `spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify assembleDebug` | 3 jobs en vol simultané |
| Durées par job | « Vérification complète » : 26m34s | statique **2,5 min** / tests **5,7 min** / lint+assemble **5,0 min** |
| Premier signal en cas de push sale | après ~20-30 min (fin de chaîne) | **~2,5 min** (job statique) |

- **Gain mesuré : -80 %** — le MÊME commit (`68a061e1`, IA-8b) a servi de
  cas d'étude : 29,8 min en séquentiel (run du 2026-10-18) contre
  5,8 min en trois jobs parallèles (run du 2026-10-19). Médiane des dix
  runs avant : 21,6 min → les runs v0.145.1 passent sous 6 minutes.
- Les deux runs (main + tag v0.145.1) sont verts du premier coup,
  sans OOM : `workers.max=3` + tas 6g tiennent confortablement dans
  les 16 Go (marge confirmée ; l'option `-Xmx8g+` reste inutile).
- Écart statique 2,5 min (main) vs 4,7 min (tag) : les deux runs ont
  démarré à quelques secondes d'intervalle et se disputaient
  l'écriture initiale du cache — les runs suivants convergent vers le
  chiffre bas.
- Lecture pour la suite : le goulot résiduel est le job **tests**
  (5,7 min — exécution réelle des JVM de test, non compressible par
  cache). Un chantier futur (hors périmètre) pourrait envisager une
  matrice de sharding de tests si les lots redeviennent lourds.

### 6.6 Leçon

Les CI rouges v0.141–v0.143 venaient de `spotlessKotlinCheck` —
échecs rapides (1,8 à 3,0 min, tableau 6.1) typiques d'un rejet de
formatage avant toute compilation. **Jamais de push sans
`spotlessApply`** (section 2, étape 1 de la chaîne locale). Le
formatage coûte quelques secondes en local ; raté, il coûte un run
CI entier et un aller-retour de 30 minutes.
