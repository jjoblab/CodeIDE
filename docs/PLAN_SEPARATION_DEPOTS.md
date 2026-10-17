# Plan de séparation des dépôts (lsp, tooling) — publication AAR/JAR

Statut : **EN EXÉCUTION** — Phase 0 + Phase 1 (codeide-tooling) FAITES le
2026-10-17, journal en §8. Ce document répond aux trois questions posées :
quoi vérifier sans gaspillage (voir AGENTS.md §7.1), comment séparer, et
historique git vs ré-création (§6).

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

## 8. Journal d'exécution (2026-10-17)

### Phase 0 — audit de frontière (conclusions)

- `tooling:client` et `tooling:daemon` dépendent de **`:core:domain`**
  (et `:core:testing` en test) → ils ne peuvent pas être extraits tant
  que les modules `core:*` ne sont pas publiés en binaire. **Ils
  restent dans CodeIDE** — leur historique vit déjà dans le dépôt
  tooling filtré, leur retour futur se fera par restauration de
  chemins.
- `lsp:model` dépend de **`:tooling:protocol`** → l'extraction lsp
  exige que protocol soit publié en binaire AVANT.
- `protocol`, `api`, `server`, `testing` : **zéro dépendance hors
  tooling** — extraction immédiate possible.
- lsp (8 modules) : **aucune dépendance `core:*`** — frontière propre,
  extraction possible dès protocol publié.
- `.gh-token` : absent de l'historique (vérifié).

Séquencement CORRIGÉ (remplace « lsp d'abord » de §3) : **tooling JVM
pur d'abord** (le plus simple) → publication protocol/api → bascule
CodeIDE → **lsp** (consommera le binaire protocol).

### Phase 1 — codeide-tooling : FAIT

- Dépôts **jjoblab/codeide-tooling** et **jjoblab/codeide-lsp** créés
  via API (publics, auto_init=false, descriptions FR) — le PAT du
  propriétaire suffit, aucune action manuelle requise.
- Clone neuf de CodeIDE → `git filter-repo` multi-chemins (tooling/,
  build-logic/, gradle/, fichiers racine de build) → **315 commits
  conservés** sur 475. La mécanique D2 (filter-repo, historique
  conservé) est donc VALIDÉE en conditions réelles.
- 4 commits d'adaptation : settings tooling-only (retrait du bloc
  LayoutLib composite et des dépôts app), retrait client/daemon
  (restent dans CodeIDE), config detekt partagée importée,
  config/signature retirée (JVM pur), README + CHANGELOG 1.0.0 +
  workflow CI JVM (JDK 21, sans SDK Android).
- Versionnement indépendant : **1.0.0** (VERSION_CODE 10000).
- Push : 319 commits sur `main`.
- Vérification locale (dépôt autonome, JAVA_HOME 21, tas borné) :
  spotless + detekt + checkModuleDependencies verts ; **146 tests, 0
  échec** ; `gradle-server.jar` **7,7 Mo** reconstruit.
- Leçon mémoire : le démon Gradle peut mourir en chaîne unique sur
  cette machine — découper en étapes (spotless/detekt → test →
  shadowJar), comme pour CodeIDE.

### Reste à faire

**Tout est livré** (extraction tooling + lsp, publication configurée,
CI en place) — il ne reste que LA BASCULE de CodeIDE : recette complète
en §9, à exécuter avec le propriétaire.

### Phase 1 — codeide-lsp : FAIT (nuit du 17/10, hors monitoring propriétaire)

Suite à la directive « fais toutes les séparations, sauf le branchement
à CodeIDE » — tout ce qui suit est ADDITIF (nouveaux dépôts), CodeIDE
restant inchangé côté code.

- Extraction : clone neuf → filter-repo multi-chemins (lsp/,
  build-logic/, gradle/, config/detekt/, docs/adr/, docs LSP,
  THIRD_PARTY_NOTICES, fichiers racine de build) → **339 commits
  conservés** + 2 commits d'adaptation = 341 pushés.
- Adaptations : settings lsp-only (8 modules ; JitPack conservé pour
  cel-lsp/:lsp:host, cache-redirector JetBrains conservé pour
  syntax-api/:lsp:kotlin) ; **:lsp:model → coordonnées externes
  `com.github.jjoblab.codeide-tooling:protocol:1.0.0`** avec composite
  `tooling.dir` en dev (miroir LayoutLib, GradleException explicite) ;
  version 1.0.0 ; baseline de parité régénérée pour le corpus du
  nouveau dépôt (1022 → 208 fichiers, diff = 1 ligne, rituel relu).
- Vérification locale INTÉGRALE (étapes séparées, démon OOM écarté) :
  spotless + detekt + checkModuleDependencies verts ; **1527 tests, 0
  échec** (model/index/xml 336, kotlin 176 dont parité, java+host+testing
  1015) ; koverVerify + lintDebug verts ; assembleRelease —
  `host-release.aar` + jars des 8 modules construits.
- CI complète en place (miroir CodeIDE sans l'app : clone frère
  codeide-tooling hors workspace, SDK, chaîne spotless→koverVerify +
  assembleRelease). Publication du dépôt : différée (coordonnées
  com.github.jjoblab.codeide-lsp:*), quand stabilisé.
- Enseignement : `:lsp:java` est un `java-library` PUR (android.jar en
  dépendance, ADR 0112) — pas de variante debug ; ses tests = `test`,
  ceux de :lsp:host = `testDebugUnitTest`. `koverVerify` racine est ce
  qui tire les tests des modules JVM dans la chaîne CI.

### Publication codeide-tooling : CONFIGURÉE (nuit du 17/10)

- `maven-publish` sur les 4 modules ; coordonnées convention JitPack
  multi-modules `com.github.jjoblab.codeide-tooling:<module>` — la
  consommation composite (dev) et binaire (Packages/JitPack) partage
  les MÊMES coordonnées ; `server` publie le composant SHADOW
  (gradle-server.jar).
- Workflow `publish.yml` : tag `v*` → `./gradlew publish` avec le
  GITHUB_TOKEN du dépôt (packages:write) — AUCUN secret à créer.
  **Vérifié** : `publishToMavenLocal` vert, 4 artefacts en 1.0.0.
- CI verte sur les deux pushes (runs 9cef4633, 8b19f8c3).
- Pour publier PLUS TARD (décision propriétaire, dépôt stable) :
  `git tag -a v1.0.0 -m "v1.0.0" && git push origin v1.0.0` — le
  workflow fait le reste.

### Bilan de la nuit (17/10, ~08:40 UTC) — TOUT VERT, RIEN EN VOL

CI confirmées SUCCESS (API GitHub, PAT lecture) :

| Dépôt | Commit | CI | Contenu |
|---|---|---|---|
| codeide-lsp | 1376c46d | SUCCESS | chaîne complète + assembleRelease |
| codeide-tooling | 9cef4633 + 8b19f8c3 | SUCCESS ×2 | chaîne JVM + publication |
| CodeIDE | 162c4845 | SUCCESS | docs seulement (§8/§9) |

État final de la directive « tout sauf la bascule » : **EXÉCUTÉ EN
TOTALITÉ**. Les trois dépôts sont autosuffisants (code + historique +
CI + docs sur GitHub — survivent à tout reset d'environnement). Seule
la bascule (§9) reste, volontairement, pour une session monitorée.

## 9. LA BASCULE de CodeIDE — FAIT (17/10, avec le propriétaire, v0.134.41)

Recette exécutée intégrale :

1. `:tooling:protocol`/`:tooling:api` consommés par :tooling:client et
   :tooling:daemon → coordonnées `com.github.jjoblab.codeide-tooling:protocol/api`
   (libs.tooling.*) — composite `tooling.dir` en dev, binaire ensuite ;
2. le `gradle-server.jar` des assets → tâches inter-builds
   `copierJarServeurAssets`/`controlerJarServeur` dans :app (shadowJar du
   frère, copie vers assets/tooling, contrôle §4.7) — validé par
   `:app:controlerJarServeur` (8,0 Mo recopiés) ;
3. retrait des chemins `tooling/{protocol,api,server,testing}` + includes
   correspondants (client/daemon CONSERVÉS) + convention plugin
   `codeide.tooling.server` supprimé (dead code) + entrée shadow des
   catalogues retirée + config detekt du serveur partie ;
4. `:lsp:*` → `codeide-lsp` : répertoire lsp/ retiré (git rm), app +
   feature:editor en `libs.lsp.host`, composite `lsp.dir` ;
5. **plus de parité dans CodeIDE** (la suite vivait dans :lsp:kotlin —
   désormais responsabilité du dépôt codeide-lsp ; AGENTS.md §7 annoté) ;
   checkModuleDependencies élagué (MODULES_PURS = trio core, branches
   tooling/lsp retirées, coordonnées externes hors audit) ;
6. chaîne RACINE locale verte (spotless, detekt, règles, tests,
   koverVerify, lintDebug) + sonde composite
   (`:feature:editor:compileDebugKotlin`) → bump 0.134.41 → push →
   **CI e315ecee SUCCESS (~25 min)**. Pré-requis remplis : **tags
   v1.0.0 des deux dépôts neufs poussés** (publication Packages
   déclenchée côté tooling : SUCCESS, 4 paquets Maven publics).

Suivis (non bloquants, à arbitrer plus tard) :

**Réglés (passage docs/scripts post-bascule) :**
- `scripts/scenarios-tooling.sh` : ROOT relatif au script (plus de chemin
  codé en dur), JAVA_HOME/ANDROID_HOME à jour, en-tête annoté (le
  SyncHandler vit dans codeide-tooling ; le script reste ici car il
  alimente docs/tooling-scenarios/ de ce dépôt) ;
- `scripts/lsp-index/construire-jeux.sh` : JAVA_HOME à jour, en-tête
  annoté (la cible du banc `:lsp:index` vit dans codeide-lsp ; le jeu
  « moyen » résout toujours le classpath de :app local) ;
- `docs/ARCHITECTURE.md` : section « Séparation des dépôts (v0.134.41) »
  en tête, modules déplacés marqués **[codeide-tooling]** /
  **[codeide-lsp]** dans les tableaux, sémantique coordonnées/composite
  documentée après les règles de dépendance.

**Restant (arbitrage propriétaire) :**
- publication binaire (Packages/JitPack) des consommateurs : passer de
  la consommation composite à `maven.pkg.github.com` quand arbitrée
  (PAT read:packages côté CI CodeIDE).
