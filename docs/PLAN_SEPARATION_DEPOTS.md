# Plan de séparation des dépôts (lsp, tooling) — publication AAR/JAR

Statut : **PLAN préparé, NON exécuté** — v0.134.40, 2026-10-17.
Exécution reportée à la décision du propriétaire, quand les frontières
sont stables (critères de GO en §7). Ce document répond aux trois
questions posées : quoi vérifier sans gaspillage (voir AGENTS.md §7.1),
comment séparer, et historique git vs ré-création (§6).

## 1. Objectif et périmètre

Extraire deux familles de modules en dépôts GitHub séparés, consommés
par CodeIDE comme **binaires versionnés (AAR/JAR) par défaut**, et en
**build composite (source) en mode dev** — le double mode éprouvé avec
LayoutLib (`layoutLib.dir`, substitution de coordonnées dans
`settings.gradle.kts`).

| Dépôt futur | Modules extraits | Volume ~ | Artefacts naturels |
| --- | --- | --- | --- |
| `codeide-lsp` | `:lsp:model`, `:lsp:index`, `:lsp:java`, `:lsp:ecj-art`, `:lsp:testing`, `:lsp:host`, `:lsp:kotlin`, `:lsp:xml` | ~52k lignes | JAR (model/index, Kotlin JVM pur), AAR (host, java), jar build-time (ecj-art, jamais dans l'APK) |
| `codeide-tooling` | `:tooling:protocol`, `:tooling:api`, `:tooling:server`, `:tooling:client`, `:tooling:daemon`, `:tooling:testing` | ~17k lignes | JAR exécutable shadowJar (server → assets), JAR/AAR (protocol/api/client/daemon) |

Gains attendus :
- la chaîne CodeIDE cesse de compiler/detekter/liner ~69k lignes à
  chaque push ; chaque domaine bâtit chez lui, sur sa propre CI ;
- frontières explicites et vérifiées par le compilateur (une dépendance
  illicite devient impossible au lieu de détectée) ;
- réutilisabilité hors CodeIDE (les moteurs lsp sont indépendants de
  tout module interne, ADR 0108/0115 — c'est déjà leur contrat) ;
- synergies D6 : lsp:kotlin/xml sont déjà pensés comme packs langue SPI
  téléchargeables — des dépôts séparés rendent le publishing des packs
  naturel.

Non-gain assumé : **l'APK ne maigrit pas** — les mêmes classes restent
embarquées ; le gain est sur les dépôts, les builds et la CI.

Précédents du dépôt :
- **code-editor** : AAR consommé via JitPack (aucun jeton côté app) ;
- **LayoutLib** : composite SOURCE — décision propriétaire du
  2026-10-13 : GitHub-only, AUCUNE publication JitPack. Ce plan ne
  change RIEN à LayoutLib.

## 2. Décisions à arbitrer (recommandations)

| # | Question | Recommandation |
| --- | --- | --- |
| D1 | Un dépôt par domaine ou un « sdk » unique ? | **Deux dépôts** : cycles de vie indépendants (tooling bouge peu, lsp évolue avec D6), CI plus courtes |
| D2 | Historique conservé ou ré-création propre ? | **filter-repo, historique conservé** — analyse complète en §6 |
| D3 | Séquence de publication ? | **GitHub Packages dès l'extraction** (SNAPSHOT + releases, auth PAT) ; **JitPack en surcouche plus tard**, quand le dépôt est stable et public — miroir code-editor |
| D4 | Granularité des artefacts ? | **Un artefact par module consommé** (miroir LayoutLib `module-*`) ; consolidation éventuelle plus tard si la consommation est pénible |
| D5 | groupId ? | `io.github.jjoblab` (compatible JitPack qui sert `com.github.jjoblab.<repo>` automatiquement) |

## 3. Phases

### Phase 0 — pré-requis (dans CodeIDE, sans séparation)

1. Audit de frontière : `checkModuleDependencies` doit confirmer zéro
   dépendance de lsp/* ou tooling/* vers le reste du graphe (déjà le
   contrat d'ADR 0108/0115 pour java/kotlin/xml ; vérifier host et
   tooling). Le sens restant (CodeIDE → lsp/tooling) donne la liste
   EXACTE des artefacts à publier — seuls les modules réellement
   consommés sont publiés.
2. Inventaire des types d'artefacts (le tableau §1, confirmé par les
   build.gradle.kts de chaque module).
3. Audit d'historique (avant de choisir filter-repo définitivement) :
   blobs lourds (`git rev-list --objects --all` trié par taille — jars ?
   SDK ?) et secrets accidentels. L'historique étant DÉJÀ public dans
   CodeIDE, le filtrage n'expose rien de nouveau ; les blobs lourds
   gonfleraient en revanche les clones des dépôts neufs.
4. Convention de versionnement : tags `vX.Y.Z` INDÉPENDANTS par dépôt,
   compteur propre à chaque domaine (les dépôts démarrent à 1.0.0 même
   si CodeIDE est en 0.134.x).

### Phase 1 — extraction (par domaine, lsp d'abord)

1. Clone neuf → `git filter-repo` (voir §6 pour la commande et les
   vérifications) → push vers le nouveau dépôt GitHub.
2. Adaptation des fichiers de build : les chemins partagés
   (build-logic/, gradle/, catalogues, wrapper) sont conservés par le
   filtre puis le `settings.gradle.kts` du dépôt neuf est réécrit pour
   ne contenir que les modules du domaine.
3. CI du dépôt neuf : miroir de ci.yml SANS les étapes app
   (assembleDebug, publication APK) — chaîne spotless/detekt/tests/lint
   des modules du domaine.
4. Dans CodeIDE (commit NORMAL, jamais de rewrite du dépôt principal) :
   suppression des chemins, retrait des `include(...)`, dépendances
   remplacées par les coordonnées externes :
   - **mode dev** : `includeBuild()` avec substitution
     (`lsp.dir`/`tooling.dir`, miroir exact du bloc LayoutLib —
     propriété Gradle, variable d'environnement, défaut frère) ;
   - **mode par défaut** : binaire (voir §4).

### Phase 2 — publication

- `maven-publish` sur chaque module consommé : AAR =
  `from components.release` ; JAR JVM = plugin
  `java-gradle-plugin`/`kotlin("jvm")` standard ; `:tooling:server` =
  shadowJar existant publié avec classifier (c'est déjà le
  `gradle-server.jar` recopié en assets).
- **GitHub Packages** : `https://maven.pkg.github.com/jjoblab/<repo>` ;
  workflow `publish.yml` : sur tag `vX.Y.Z` (release) +
  `workflow_dispatch` (SNAPSHOT `-SNAPSHOT`). Secret `write:packages`
  dans les dépôts neufs ; consommation CodeIDE avec PAT
  `read:packages` (déjà en place, le dépôt des maven repos se fait dans
  `settings.gradle.kts` — `FAIL_ON_PROJECT_REPOS` l'exige).
- **JitPack plus tard** (stabilité) : `jitpack.yml` (JDK 21, openjdk)
  suffit ; les tags sont construits À LA DEMANDE ; consommation sans
  jeton — le miroir code-editor (`maven { url = uri("https://jitpack.io") }`)
  est déjà dans settings.gradle.kts.

Séquence de bascule : composite (jour 1) → Packages SNAPSHOT →
Packages release → JitPack (quand stable/public).

### Phase 3 — bascule finale de CodeIDE

- `gradle.properties` : `lsp.source=binaire|composite` (miroir
  `layoutLib.dir`), défaut binaire une fois les releases stables.
- CI CodeIDE : consomme les binaires publiés (plus de checkout frère) ;
  les status checks des dépôts neufs deviennent requis.
- ADR unique documentant l'ensemble (0121+) + mise à jour AGENTS.md
  (§5 journal, §7 chaînes : chaque dépôt a sa propre chaîne).

## 4. GitHub Packages vs JitPack — critères

| Critère | GitHub Packages | JitPack |
| --- | --- | --- |
| Auth consommateur | PAT `read:packages` obligatoire | Aucune (dépôt public) |
| Snapshots | Oui, natif | Possible mais inadapté |
| Effort de mise en place | maven-publish + workflow (~1 h) | ~zéro (jitpack.yml) |
| Fraîcheur | Immédiat (workflow déclenché) | Build à la demande (~5 min au premier appel) |
| Visibilité | Publique OU privée | Dépôt public requis |
| Usage recommandé ici | Dev continu, SNAPSHOT, avant stabilisation | Consommation stable publique (miroir code-editor) |

C'est exactement la séquence demandée : Packages maintenant, JitPack
« pour plus tard, quand les dépôts sont vraiment stables ».

## 5. Historique git vs ré-création — analyse

**Recommandation : `git filter-repo`, historique conservé.**

Pourquoi :
- `git blame`/archéologie sur ~52k lignes (moteur vendored, index
  disque, host) : précieux pour les régressions profondes — la valeur
  des messages de commit détaillés du dépôt est conservée ;
- tags et historique cohérents dès le jour 1 dans le dépôt neuf ;
- opération réversible et vérifiable AVANT toute conséquence : clone
  neuf → filtre → build → diff d'arbre vide sur les chemins gardés →
  push. Le dépôt CodeIDE original n'est JAMAIS touché ;
- l'historique lsp/tooling est DÉJÀ public dans CodeIDE : le filtrage
  n'expose rien de nouveau.

Coûts acceptés : les hashes changent dans le dépôt neuf (normal — c'est
un dépôt neuf) ; les fichiers de build partagés demandent une passe
d'adaptation (Phase 1.2).

Mécanique (exemple lsp) :

```bash
git clone --no-local https://github.com/jjoblab/CodeIDE.git codeide-lsp
cd codeide-lsp
git filter-repo --path lsp/ --path build-logic/ --path gradle/ \
    --path gradle.properties --path gradlew --path gradlew.bat \
    --path settings.gradle.kts --path build.gradle.kts
# adapter settings.gradle.kts (modules lsp seulement), vérifier le build
git remote add origin https://github.com/jjoblab/codeide-lsp.git
git push -u origin main
```

- Côté CodeIDE : commit NORMAL « retire lsp/* → dépendance externe ».
  **Jamais de rewrite du dépôt principal** (pas de force-push, issues/
  PR/tags intacts ; l'ancien historique lsp y reste, inoffensif).

**Ré-création (alternative)** — la préférer si :
- blobs lourds détectés dans l'historique (Phase 0.3) et qu'on veut un
  `.git` minimal ;
- secrets accidentels dans l'historique (à purger avant publication —
  mais ils sont déjà publics dans CodeIDE) ;
- l'historique filtré s'avère inutilisable (fichiers sans cesse
  déplacés entre domaines — rare).

Ré-création ≠ réécriture du code : le code est copié À L'IDENTIQUE dans
son état courant, en **UN commit initial** (« Import depuis CodeIDE
v0.134.40 »). On ne réécrit jamais le code à la main, ni en totalité ni
en delta — les changements sont déjà DANS le code. La seule différence
avec filter-repo est la profondeur d'historique (1 commit vs historique
filtré). Le dépôt principal garde son historique complet dans les deux
cas.

## 6. Ordre du jour de l'extraction

Estimation honnête, un domaine à la fois :
- Phase 0 (audit frontière + historique) : ~1 session d'agent ;
- Phase 1 tooling (le plus simple, JAR pur, shadowJar déjà là) : ~1
  session ;
- Phase 1 lsp (8 modules, vendored, AAR+JAR mélangés) : ~2 sessions ;
- Phase 2/3 publication + bascule : ~1 session par dépôt.

## 7. Critères de GO

- Frontières stables depuis plusieurs versions : `checkModuleDependencies`
  vert, aucune fuite nouveau-graphe détectée ;
- D6 (packs langue SPI téléchargeables) arbitrée — la forme du
  publishing lsp en dépend directement ;
- PAT `write:packages` en secret des dépôts neufs, `read:packages`
  consommable par la CI CodeIDE ;
- fenêtre sans chantier en vol (l'extraction touche settings.gradle.kts
  + CI — c'est un chantier « chaîne racine » au sens AGENTS.md §7).
