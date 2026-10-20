# Rapport d'analyse — LSP Java de CodeIDE

**Objectif : stabiliser le LSP Java. Gaps, incohérences, améliorations.**

| | |
|---|---|
| **Dépôt analysé** | `jjoblab/CodeIDE` — clone `/home/z/my-project/repos/CodeIDE` (lecture seule, arbre vérifié propre) |
| **Version analysée** | v0.124.7 (VERSION_CODE 12407, commit `bd6f458`, 2026-10-14) |
| **Document de référence** | `docs/LSP_JAVA.md` (1 806 lignes — étude étape 0 v0.91.0 + journal de livraison jusqu'à v0.108.0) |
| **Code audité** | `lsp/java` : 51 fichiers main, 20 698 lignes ; modules connexes `lsp:host`, `lsp:index`, `lsp:model`, `lsp:testing`, `lsp:ecj-art` |
| **Méthode** | Lecture intégrale de la référence ; lecture ciblée du code (capacités déclarées, handlers LSP4J, services, orchestrateur hôte, session) ; exécution fraîche de la suite de tests (lecture seule) ; comparaison avec Eclipse JDT.LS (README officiel master, vérifié le 2026-10-14), java-language-server, le projet de référence, Android Studio |
| **Date du rapport** | 2026-10-14 |

---

## 1. Résumé exécutif

Le LSP Java de CodeIDE est **architecturalement sain et fonctionnellement déjà riche** : moteur ecj vendored patché ART (in-process, ADR 0109), modèle de projet empreinté et persisté (`lsp:model`), index disque par segments avec fusion LSM (`lsp:index`, ADR 0111), moteurs par module avec verrous cloisonnés, mode instantané « dumb mode » à la Android Studio, complétion membres/types/imports, diagnostics avec supersession coopérative et annulation, hover par bindings, navigation étendue (definition/typeDefinition/implementation/superDefinition/references), renommage multi-fichiers, formatage, repli, inlay hints, surlignage sémantique, quick-fixes. L'exécution fraîche de la suite ce jour confirme la stabilité de base : **498 tests, 0 échec, 76 ignorés** (dépendants de l'environnement) sur les cinq modules LSP.

Cinq constats majeurs tempèrent ce bilan et définissent le chantier de stabilisation :

1. **Le risque n'est pas la qualité du code, mais la dette de provenance (D3/ADR 0110), toujours non réglée** : ~19 fichiers de niveau B dérivés du projet de référence (GPL-3.0) attendent leur réécriture « lot G » (§16.3 de la référence). Tant que ce lot n'est pas fait — ou autorisé formellement par le propriétaire — toute publication/extraction du moteur est juridiquement bloquée, et la base de code reste un passif.
2. **La conformité protocolaire LSP 3.17 est bonne sur ce qui est déclaré, mais le serveur reste en dessous du socle feature d'Eclipse JDT.LS** sur des méthodes structurantes pour l'expérience éditeur : `prepareRename` (absent → renommage qui échoue en silence), `workspace/symbol` (aucune recherche de type inter-fichiers à l'échelle du projet hors navigation par symbole), `rangeFormatting`/`onTypeFormatting`, `selectionRange`, tags de diagnostics, hiérarchies d'appels/de types, `codeLens`.
3. **La conformité de compilation est figée à « 17 »** (constante par défaut de `CompilationEnvironment`) : les diagnostics de niveau de langage sont faux pour un projet Java 8 (acceptera du code illégal) ou Java 21+ (signalera à tort des syntaxes légales). La référence l'identifie déjà (§17.3, « candidat protocole v10 ») — c'est le gap fonctionnel le plus visible côté utilisateur après le renommage silencieux.
4. **Quelques incohérences doc ↔ code et de convention internes** fragilisent la maintenabilité : négociation de clientCapabilities documentée mais inopérante, machine à états lifecycle documentée comme « doit rejeter » mais jamais consultée par les handlers, convention `null` vs liste vide divergente entre handlers, `ServerInfo.version` codé en dur (« 1.2.0 ») désynchronisé de la version du dépôt, messages de remède français concaténés à des diagnostics ecj anglais.
5. **La validation de stabilité reste incomplète** : le protocole de mesure sur appareil (§11.2 de la référence) n'a jamais été exécuté en tant que tel, il n'existe ni test de robustesse prolongée (soak/charge en frappe), ni test de non-régression CRLF/encodage des positions, et deux fragilités d'infrastructure sont connues (isolation de `LspEditorViewModelTest` sous `--rerun-tasks` à 4 Go ; `--rerun-tasks` casse le bootstrap build-logic).

**Recommandation centrale** : un plan de stabilisation en quatre séquences (S0→S3, §11) respectant les conventions du dépôt (lots versionnés, tests verts, push immédiat), priorisant S0 = décision D3 + mesures appareil + micro-correctifs protocolaires (faible coût, gain immédiat de fiabilité perçue), puis S1 = conformité par projet + `prepareRename` + tags diagnostics + `workspace/symbol`. Le détail chiffré et les critères d'acceptation sont en §11.

---

## 2. Périmètre, méthode et matériaux

### 2.1 Ce qui a été fait

L'analyse a couvert, dans l'ordre : la lecture intégrale de `docs/LSP_JAVA.md` (1 806 lignes, du cadrage W1–W10 aux correctifs d'exploitation v0.108.0) ; la lecture des passages structurants des ADR 0108 (politique de langage/vendored), 0109 (modèle in-process), 0110 (provenance/licence), 0111 (index disque), 0112 (plateforme android.jar), 0113 (source classpath AGP v2) et 0117 (écran d'indexation, brouillon phase A) ; la lecture du code du module `lsp/java` en privilégiant la couche protocolaire (`ServerCapabilitiesProvider`, `JavaLanguageServer`, `JavaTextDocumentService`, `JavaWorkspaceService`, services `Diagnostic`/`Hover`/`Navigation`/`CodeAction`) et la couche moteur aux points signalés par la référence (`EcjCompletionEngine`, `EcjRenameEngine`, `DiagnosticService`, `StubsIntrinseques`) ; la lecture de l'hôte (`SessionLsp.kt`, `OrchestrateurLsp.kt`) ; l'exécution fraîche et complète des suites de tests des cinq modules LSP sur le clone lecture seule ; enfin la comparaison avec les serveurs de référence, adossée au README officiel d'Eclipse JDT.LS (branche master, consulté le 2026-10-14) et aux faits documentés dans la référence elle-même (audit de provenance du projet de référence, banc de mesures).

### 2.2 Limites assumées

Trois limites bornent la portée de ce rapport. Premièrement, le client LSP réel est la bibliothèque `cel-lsp` (code-editor de jjoblab via JitPack) : son comportement exact de filtrage de complétion et de rendu n'a pas été audité fichier par fichier — les risques liés (par exemple l'absence de `filterText` côté serveur, §6.3) sont signalés « à vérifier côté client » quand la vérification exigeait cette lecture. Deuxièmement, aucune mesure de performance n'a été rejouée ici : le banc `:lsp:testing:bench` existe et ses derniers résultats (v0.98.0) sont repris tels quels de la référence ; la machine d'audit (2 cœurs, ~4 Go) n'est pas représentative d'un appareil Android et le protocole appareil §11.2 reste la voie officielle. Troisièmement, l'analyse s'est concentrée sur la brique Java : les LSP Kotlin et XML ne sont convoqués que lorsque la cohérence inter-langues de l'hôte est en jeu (état d'indexation, ADR 0117).

---

## 3. Architecture du LSP Java (état constaté)

### 3.1 Modules et responsabilités

| Module | Langue | Rôle | Constat de volume |
|---|---|---|---|
| `lsp:java` | Java (vendored, exempté detekt/spotless — ADR 0108) | Moteur ecj + façade LSP4J complète | 51 fichiers main, 20 698 lignes ; 315 tests (76 ignorés env) |
| `lsp:ecj-art` | Java (build) | Passe ASM de relocalisation des API JDK manquantes sur ART | artefact de build, 2 tests |
| `lsp:model` | Kotlin JVM pur | Modèle de projet immuable (modules, sourcesets, empreintes W6, association fichier→module, persistance validée W7) | 35 tests |
| `lsp:index` | Kotlin JVM pur | Index disque CIDX par segments (un par jar), fusion LSM à la requête, cache LRU borné, auto-guérison | 64 tests |
| `lsp:host` | Kotlin | Façade session (transport in-process cel-lsp), orchestrateur modèle/index/moteurs, persistance du modèle, état d'indexation | 72 tests |
| `lsp:testing` | Java | Point de contact sanctionné moteur ↔ index (le module vendored ne dépend d'aucun module interne — ADR 0108 §5.2) ; banc moteur | 12 tests |

L'isolation est la propriété structurante la plus réussie du design : le moteur vendored ne connaît ni Gradle, ni l'index (SPI `jo.lspjava.api.IndexNoms` injectée par l'hôte), ni Android ; `lsp:host` est l'unique point de rencontre entre tooling et moteur ; l'app est l'unique point où les liaisons Hilt se posent. Cette frontière nette est explicitement testée (`checkModuleDependencies`, allow-list §5.2) — c'est un garde-fou de stabilité à préserver absolument.

### 3.2 Flux nominal d'une session

À l'ouverture d'un projet (détail en `SessionLsp.kt` et §16 de la référence) : la session est créée avec le stockage d'index injecté (répertoire privé de l'app sous ART), le `android.jar` du toolchain est branché immédiatement au contexte par défaut (v0.102.0, écart 1 — parité « Android Studio branche la plateforme dès la config SDK »), le modèle persisté `.codeide/local/lsp-modele.json` est relu et rejoué s'il est frais (W7 : validation taille/horodatage, écarts signalés jamais fatals). Chaque connexion d'onglet obtient un `JavaLanguageServer` LSP4J neuf via `InProcessStreamConnectionProvider` ; la sync Gradle produit un `ClasspathResult` v8 converti en `ProjetLsp`, l'union indexable (jars + plateforme par module) est dédupliquée par empreinte de contenu, la vue fusion LSM est construite en arrière-plan (états CONSTRUCTION/PRÊT/PARTIEL/ERREUR publiés en `StateFlow`), et un contexte moteur par module est poussé via `remplacerContexteModule` (racines sources du module + frères déclarés en entrée MODULE, jars + android.jar du module). À la fermeture : `close()` annule le scope, **attend** l'arrêt effectif des coroutines dans une borne de 2 s (correctif v0.121.1 — des écritures de segments survivaient au retour de `close`), dispose les moteurs, vide le modèle ; le test de fuite `SessionLspTest` prouve que la chaîne serveur→environnement→moteurs→adaptateur→cache meurt avec la session.

### 3.3 Les trois régimes de service

Le serveur monte en puissance automatiquement, sans jamais mentir (philosophie « zéro devinette » revendiquée et globalement tenue dans le code) :

1. **Régime instantané** (v0.101.0) : sans plateforme, `parseSeul` (diet parse + `getMethodBodies`) publie les diagnostics de syntaxe seuls (bit `IProblem.Syntax`) et `EcjSyntaxCompletion` propose les symboles déclarés du fichier + mots-clés ; aucune API du JDK n'est devinée ; le `didOpen` annonce le régime d'une ligne de journal.
2. **Régime pré-classpath** (v0.102.0) : `android.jar` branché au contexte par défaut dès l'ouverture — `android.*` et `java.*` (boot classpath) résolvent en régime plein avant toute sync.
3. **Régime plein** : classpath Gradle par module + index disque PRÊT (localisateur autoritatif, négatifs définitifs — P7/P8 de l'ADR 0111).

La montée entre régimes est automatique par le live-republish du `ContextListener` (ré-analyse de tous les documents ouverts au `replaceAll` effectif) — un choix d'architecture qui élimine toute coordination utilisateur.

### 3.4 Discipline d'exécution

Le `EcjEngineScheduler` sépare lanes interactive/analyse avec timeouts (3 s interactif, 15 s analyse), supersession par fichier, watchdog ; `DiagnosticService` annule coopérativement la passe en vol du même fichier avant d'en lancer une autre (tokens par fichier, checkpoints dans l'environnement de noms) ; les requêtes lourdes (references/rename) passent par `CancelToken.timeout(15 s)` avec rendu partiel ; la complétion rend honnêtement `isIncomplete=true` en cas de timeout/supersession/erreur pour que le client re-demande. C'est un niveau de discipline concurrente rare dans un serveur embarqué — à préserver.

---

## 4. État de stabilité mesuré

### 4.1 Run de vérification du jour (2026-10-14, clone lecture seule)

Exécution séquentielle module par module (JVM de build bornée à 1 300 Mo, workers=1 — le sandbox 4 Go OOM-kill le daemon aux réglages par défaut du `gradle.properties` ; voir §11.4 pour l'impact pratique de ce constat) :

| Module | Tests | Échecs | Erreurs | Ignorés | Verdict |
|---|---:|---:|---:|---:|---|
| `:lsp:java:test` | 315 | 0 | 0 | 76 | VERT (46 s, patch ART inclus) |
| `:lsp:model:test` | 35 | 0 | 0 | 0 | VERT |
| `:lsp:index:test` | 64 | 0 | 0 | 0 | VERT |
| `:lsp:testing:test` | 12 | 0 | 0 | 0 | VERT |
| `:lsp:host:test` (`testDebugUnitTest`) | 72 | 0 | 0 | 0 | VERT |
| **Total** | **498** | **0** | **0** | **76** | **VERT** |

L'arbre du dépôt est resté strictement propre (0 fichier suivi modifié) — la contrainte « CodeIDE en lecture seule » est respectée par la méthode (seuls `build/` non versionnés ont été produits).

Lecture des écarts avec la baseline documentée (§15.0 de la référence, époque v0.98.0 : 295 tests / 69 ignorés pour `lsp:java`, 60 pour `lsp:index`, 44 pour `lsp:host`) : `lsp:java` a gagné ~20 tests (correctifs v0.108.0 — `StringConcatFactoryReproTest` 5 tests, `DatabindingImportDowngradeTest` 3, complétion/inlay divers), `lsp:index` 4, `lsp:host` 28 (couverture Kover sous le plancher corrigée en v0.114.1 + suites Kotlin/XML ultérieures). Les 76 ignorés restent environnement-dépendants (sondes JDK/`JO_TEST_JDK_HOME`, android.jar selon la machine) : c'est un comportement assumé (`Assumptions`), mais un suivi du **taux de skip par module** dans la CI éviterait qu'un skip nouveau passe inaperçu (§11.3).

### 4.2 Historique de stabilité (ce que raconte la référence)

La trajectoire v0.100.0 → v0.100.3 est le meilleur indicateur de robustesse réelle : quatre retours appareil successifs ont chacun fait remonter des défauts graves (crash `NoClassDefFoundError: ProcessHandle` à la première écriture de segment ; `readNBytes` latent API 33 ; course de déploiement de jar orchestrateur ; fuite de session LSP au démarrage ; vue d'état qui écrasait l'éditeur ; `ClassCastException` de restauration d'état ; branchement muet par pont détourné) — tous corrigés avec tests de non-régression et règles générales dérivées (« tout module JVM pur destiné à être DEXÉ s'en tient au sous-ensemble OpenJDK-8 + API-26-sûres »). Puis v0.108.0 a clos deux diagnostics « incurables » issus d'un projet réel (StringConcatFactory stubbé en dernier recours ; databinding downgradé honnêtement ERROR→HINT avec coupure dès la première classe générée). Ce pattern — défaut réel → repro test → correctif → règle générale — est la pratique de stabilisation la plus saine du dépôt ; le plan §11 l'étend aux domaines non couverts.

### 4.3 Ce qui manque à la démonstration de stabilité

Quatre trous de couverture ressortent. (1) **Aucune mesure appareil bout-en-bout** : le protocole §11.2 (p95 complétion, mémoire, sur appareil milieu et entrée de gamme) est écrit mais jamais exécuté en tant que séance dédiée — reporté à chaque étape depuis l'origine. (2) **Aucun test de charge/soak** : le comportement en frappe prolongée (fuite de caches, fragmentation du tas, croissance des poignées de fichiers au-delà du pool LRU 24) n'est éprouvé que par le banc JVM 30 itérations, pas en continu. (3) **Fragilités d'infrastructure connues et non tracées en issue** : isolation de `LspEditorViewModelTest` sous `--rerun-tasks` à 4 Go RAM (notée en commentaire de CHANGELOG v0.124.5) et `--rerun-tasks` qui casse le bootstrap build-logic (constat d'agent documenté hors dépôt) — deux handicaps quotidiens pour toute investigation de non-régression. (4) **Le banc moteur n'est pas une gate** : `:lsp:testing:bench` est explicitement hors de la chaîne de vérification (voulu — jamais dans `check`), ce qui est raisonnable en CI, mais aucune exécution périodique planifiée ne surveille la dérive des p95.

---

## 5. Conformité au document de référence `LSP_JAVA.md`

### 5.1 Promesses tenues (vérifiées dans le code)

| Engagement de la référence | Où c'est livré | Vérification de ce jour |
|---|---|---|
| Étape 1 — modèle de projet empreinté, persisté, validé (W5/W6/W7) | `lsp:model` + source classpath AGP v2 (ADR 0113) | Code lu (`ConvertisseurModeleLsp`, `PersistanceModeleLsp`, `AssociationFichier`) ; 35 tests verts |
| Étape 2 — index disque par segments, reprise < 1,5 s, +1 dépendance = seul jar indexé | `lsp:index` (CIDX, ADR 0111) | Code lu (`ServiceIndexDisque`, `VueIndex`, `CacheSegments`) ; 64 tests verts |
| Étape 3 — branchement moteur ↔ index, cache parse multi-entrées, moteur par module, correctif « 0 item », banc | lots A→F de §15 | SPI `IndexNoms` branchée, `EcjParseCache` LRU 8 entrées, `MoteurParModuleTest`, `NomZeroItemRegressionTest` présents et verts |
| Étape 4 — hôte, cycle de vie, branchement éditeur, test de fuite | §16 (h1/h2 + v0.100.1–3, v0.101/102) | `SessionLsp`/`OrchestrateurLsp` lus ; 72 tests verts dont test de fuite |
| Correctifs d'exploitation (StringConcatFactory, databinding) | §17, v0.108.0 | `StubsIntrinseques.java`, `maybeDowngradeUnresolvedDatabinding` lus ; tests repro verts |
| Cibles non fonctionnelles §9 | complétion types p95 < 100 ms tenue (27,7 ms lourd) ; +1 dépendance ~85 ms ; poignées constantes ; dispose propre | Dernières mesures = banc v0.98.0 de la référence (non rejouées ici, cf. §2.2) |

Le document de référence est **fidèle au code** : chaque affirmation testable de §13–§17 (champs remplis, clés de segments, budget par jar, attente bornée de `close`, etc.) a trouvé son exact pendant dans les sources lues. C'est rare et précieux — le journal *est* la documentation de stabilisation ; il faut le maintenir (voir I15, §9).

### 5.2 Restes explicitement ouverts dans la référence (et donc attendus)

- **Lot G — D3 lots 2–4** (§16.3/§15.1) : ~19 fichiers niveau B à réécrire depuis spécification, à étaler sur plusieurs sessions. D3 reste « PROPOSÉ — décision propriétaire ». **C'est le plus gros risque du périmètre** (publication/extraction bloquées).
- **Mesures appareil §11.2** (§16.3) : reportées sans échéance.
- **Lot h3** (§16.3) : onglet Problèmes, navigation inter-fichiers côté client, source décompilée — reportés au chantier « Cycle de vie ».
- **ADR 0112 strict** : modules Android sans image jrt (jdkHome partagé au niveau environnement) — reporté, documenté §16.5.
- **Compliance par projet** (§17.3) : niveau « 17 » fixe, champ absent du protocole v8/v9 — candidat protocole v10.
- **Génération post-sync des classes databinding/R** (§17.3) : l'option « exécuter `generate<Variant>Sources` en fin de sync » n'est pas tranchée (S6/démon).
- **ADR 0117** (v0.124.0) : écran d'indexation — phase A (audit + brouillon + maquette) livrée, phase B en attente de GO.
- **RemoteEndpoint/gson complet** (§16.1) : vit avec l'usage ; le branchement in-process actuel n'y passe pas.

### 5.3 Écarts référence ↔ code (légers)

Deux affirmations du corpus documentaire ne sont pas exactes au regard du code actuel, sans gravité fonctionnelle : le javadoc de `ServerCapabilitiesProvider` (L10–13 et L26–28) promet une négociation qui désactiverait des capacités selon le client, alors que `createCapabilities()` ne lit jamais `clientCapabilities` (détail I1, §9) ; et le javadoc du lifecycle de `JavaLanguageServer` (L52–56) énonce des rejets `ServerNotInitialized`/`InvalidRequest` qu'aucun handler n'implémente — `lifecycleState()` n'est référencé nulle part hors de la classe (I2). Par ailleurs, la référence s'arrête à v0.108.0 : les quinze versions suivantes (Kotlin LSP, XML LSP, éditeur, indexation phase A) n'ont plus alimenté `LSP_JAVA.md` — le document n'est plus la source de vérité continue qu'il a été (I15).

---

## 6. Audit protocolaire LSP 3.17

### 6.1 Capacités : déclaré vs implémenté vs conforme

Source : `ServerCapabilitiesProvider.createCapabilities()` (lignes 35–142) confronté aux handlers de `JavaTextDocumentService`/`JavaWorkspaceService`.

| Méthode / capacité LSP | Déclaré | Implémenté | Conformité et nuances |
|---|---|---|---|
| `initialize`/`initialized`/`shutdown`/`exit` | — | Oui | Machine à états explicite (enum `LifecycleState`), `exit` ne `System.exit` qu'en standalone — conforme in-process. **Écart** : double `initialize` → simple warning (la spec attend une erreur) ; rejets d'état documentés mais non implémentés (I2) |
| `textDocument/didOpen`/`didChange`/`didClose`/`didSave` | `Incremental` | Oui | didChange : validation atomique de version, application incrémentale avec clamp des bornes, invalidation du cache de parse, debounce 50 ms des diagnostics — solide. **Écart** : `didSave` ne re-diagnostic que si `includeText` (I5) |
| `textDocument/completion` | Oui (triggers `.`, ` `, `(`, `,` ; `resolveProvider=false`) | Oui | `isIncomplete=true` honnête sur timeout/supersession/erreur ; fallback synchrone si scheduler indisponible ; mode instantané. **Écart** : `filterText` jamais rempli (I12) |
| `completionItem/resolve` | `false` | No-op documenté | Cohérent — pas de round-trip inutile |
| `textDocument/hover` | Oui | Oui | Contenu texte plat (signature exacte par bindings) ; pas de négociation markdown, pas de javadoc riche |
| `textDocument/definition` | Oui | Oui | Chemin bindings + fallback binaire `jdt://decompiled/<fqcn>.java` + méthode custom `decompiledSource` — parité « source attachée ou stub » correcte |
| `textDocument/typeDefinition` | Oui | Oui | Idem, targetType=true |
| `textDocument/implementation` | Oui | Oui | Héritiers directs, multi-fichiers via balayage des racines sources (overlay tampons ouverts d'abord) |
| `textDocument/superDefinition` (custom) | `experimental` | Oui | Pattern jdt.ls ; DTO `NavigationTarget` avec offset exact — propre |
| `textDocument/decompiledSource` (custom) | `experimental` | Oui | Source attachée (src.zip, -sources.jar, caches Gradle) puis stub binaire |
| `textDocument/codeAction` | Oui (`quickfix`, `refactor`, `refactor.extract`, `refactor.rewrite`, `source.organizeImports`) | Oui | Filtre par `context.only` ; diagnostics portent `code` + `data` (arguments ecj structurés) — branchage ID+args et non message localisé, très bon pattern |
| `textDocument/documentSymbol` | Oui | Oui | — |
| `textDocument/documentHighlight` | Oui | Oui | — |
| `textDocument/references` | Oui | Oui | 15 s CancelToken, rendu partiel ; overlay des tampons ouverts |
| `textDocument/rename` | Oui (booléen) | Oui | `WorkspaceEdit.changes` (forme héritée). **Écarts** : pas de `prepareRename`, échec silencieux (edit vide, jamais de `ResponseError`), pas de `documentChanges`/version (I8) |
| `textDocument/formatting` | Oui | Oui | Édit unique pleine plage ; options LSP (`tabSize`, `insertSpaces`) non consommées ; formateur Eclipse in-process |
| `textDocument/foldingRange` | Oui | Oui | Retourne `null` si moteur indisponible (légal 3.17, justifié « le client garde ses folds lexicaux ») — mais convention divergente avec inlayHint (I9) |
| `textDocument/inlayHint` | Oui (`resolveProvider=false`) | Oui | Paramètres aux call sites + chaining + lambdas + `var` (bindings, repli textuel littéraux si moteur absent) |
| `textDocument/signatureHelp` | Oui (triggers `(`, `,`) | Oui | — |
| `textDocument/semanticTokens/full` | Oui (légende 11 types, 0 modificateurs) | Oui | Légende minimale ; pas de `/range`, pas de modificateurs static/deprecated (I10) |
| `positionEncoding` | `UTF-16` | — | Correct (char Java = unité UTF-16) |
| `serverInfo` | — | Oui | **`version` « 1.2.0 » codée en dur**, désynchronisée du dépôt (I7) |
| `workspace/didChangeConfiguration` | — | No-op | Commenté « hardcoded » — vrai (formatage/imports non configurables) |
| `workspace/didChangeWatchedFiles` | — | Invalide le cache de parse `.java` | **Aucun watcher branché côté hôte in-process** → voie protocole muette en production (I4) |
| `workspace/executeCommand` | — | No-op loggué | Acceptable tant que rien n'est annoncé |
| `workspace/symbol` | **Non déclaré** | **Non implémenté** | Gap vs JDT.LS (« type search ») |
| `$/progress` côté serveur | — | Non | L'état d'indexation voyage par `StateFlow` Kotlin côté hôte, pas par le canal LSP — choix in-process cohérent, mais à documenter (I6) |

### 6.2 Méthodes LSP 3.17 non implémentées, avec verdict d'utilité CodeIDE

| Méthode absente | Utilité pour CodeIDE | Verdict |
|---|---|---|
| `textDocument/prepareRename` | Élevée — valide la position avant le renommage, donne le placeholder au client | **À faire (S0/S1)** — petit coût, gain UX immédiat |
| `workspace/symbol` | Élevée — « chercher un type dans tout le projet » ; l'index disque a déjà `nomsParPrefixe` | **À faire (S1)** — le substrat existe, il manque la façade LSP |
| `textDocument/rangeFormatting` | Moyenne — formater la sélection ; le formateur Eclipse sait le faire | S1/S2 |
| `textDocument/onTypeFormatting` | Moyenne — auto-format `;`/`{` (JDT.LS : « formatting on-type/selection/file ») | S2 |
| `textDocument/selectionRange` | Moyenne — sélection sémantique croissante (JDT.LS « semantic selection ») ; le client doit aussi le consommer | S2 (dépend cel-lsp) |
| `textDocument/codeLens` | Moyenne — compteurs références/implémentations ; coût de recalcul à la frappe | S2, optionnel |
| `textDocument/prepareCallHierarchy`/`callHierarchy/incoming`/`outgoing` | Moyenne — navigation structurelle ; JDT.LS l'a | S2/S3 |
| `textDocument/prepareTypeHierarchy` + `supertypes`/`subtypes` | Moyenne — le moteur a déjà `findImplementations` et le super ; généralisation naturelle | S2/S3 |
| `textDocument/linkedEditingRange` | Basse —renom de balise simultané, peu critique en Java | Plus tard |
| `textDocument/documentLink`, `documentColor`/`colorPresentation` | Basse | Plus tard |
| `textDocument/willSave`/`willSaveWaitUntil` | Moyenne — « organiser les imports à la sauvegarde » serait le premier usage | S2 (avec la politique d'auto-save de l'app) |
| `textDocument/diagnostic` + `workspace/diagnostic` (pull 3.17) | Basse en in-process (le push debounce 50 ms suffit) | Tant que le client est cel-lsp : non |
| `workspace/didChangeWorkspaceFolders` | Basse — mono-projet par session par design (ADR 0114 registre de sessions) | Documenté comme choix |
| Enregistrement dynamique de capacités (`client/registerCapability`) | Moyenne — notamment pour déclarer les watchers de fichiers | **Lié à I4** |

### 6.3 Incohérences protocolaires et de convention (détail)

Ces constats sont volontairement granulaires (fichier, lignes) pour être actionnables directement en lots.

**I1 — Négociation de capabilities inopérante mais documentée.** `ServerCapabilitiesProvider.applyClientCapabilities()` (L31–33) stocke les `ClientCapabilities`, et le javadoc de classe (L10–13) comme celui de la méthode (L26–28) promettent que « le provider peut désactiver les capabilities non supportées par le client ». Or `createCapabilities()` (L35–142) ne consulte jamais le champ. Aucun bug visible aujourd'hui (le client réel est cel-lsp in-process, qui consomme tout), mais le contrat écrit est faux, et l'occasion est manquée : c'est le mécanisme naturel pour ne pas annoncer inlayHint/semanticTokens à un client qui ne les rend pas. Correction en deux volets : soit implémenter une vraie négociation minimale (lire `getTextDocument().getInlayHint()`, etc.), soit corriger le javadoc et supprimer le champ.

**I2 — Lifecycle documenté « doit rejeter », jamais appliqué.** Le javadoc de `JavaLanguageServer.LifecycleState` (L52–56) énonce les obligations spec (rejeter toute requête avant INITIALIZED avec `ServerNotInitialized`, après SHUTDOWN avec `InvalidRequest`) et le commentaire de `shutdown()` (L178–181) précise que « les handlers peuvent tester lifecycleState() ». Vérification faite : `lifecycleState()` n'est référencé dans aucun service — seul `JavaLanguageServer` lui-même l'utilise (transitions + accesseur). En in-process avec un unique client cel-lsp discipliné, l'impact pratique est quasi nul ; le risque apparaît avec un client tiers (ou un bug de séquence côté hôte) : requêtes servies après `shutdown` sur des moteurs disposés. Correction peu coûteuse : un contrôle centralisé dans `JavaTextDocumentService` (méthode garde au sommet de chaque handler, ou wrapper de `submitToScheduler`).

**I3 — `initialize` réentrant.** La spec attend une erreur si `initialize` est appelé deux fois ; le code se contente d'un warning (`JavaLanguageServer.java` L125–127) et re-transitionne l'état. Sans conséquence avec le client cel-lsp actuel (une connexion par session, le supplier de `serveur()` ne sert qu'une connexion), mais la divergence devrait être tranchée : erreur `InvalidRequest` propre, ou warning assumé et documenté comme écart in-process.

**I4 — `didChangeWatchedFiles` : une voie protocole muette en production.** Le handler (`JavaWorkspaceService` L50–80) invalide proprement le cache de parse pour les `.java` modifiés hors éditeur — mais l'hôte in-process n'envoie jamais ces notifications (aucun observateur de fichiers côté `lsp:host`, aucune inscription dynamique de watchers). Concrètement : un `git pull`/`checkout` réalisé depuis CodeIDE change des `.java` non ouverts ; les contenus relus depuis le disque par les balayages rename/references sont frais, et le `EcjParseCache` (clé chemin+longueur+hash de contenu) re-parse naturellement un fichier changé — donc le statu quo n'est pas incorrect — mais la sémantique fine (suppression de fichier, renommage externe, invalidation du `EcjLocateCache` sur changement de jar) n'est couverte par personne. Deux remèdes : (a) brancher un `FileObserver`/balayage côté hôte qui pousse des `FileEvent` synthétiques dans le serveur ; (b) supposer le mono-client et documenter que l'invalidation est tirée par les empreintes. Recommandé : (a) limité au répertoire du projet + `.java`, en S0/S1.

**I5 — `didSave` ne re-diagnostic pas sans `includeText`.** `JavaTextDocumentService.didSave` (L261–289) invalide le cache de parse « pour forcer un re-diagnostic propre » (commentaire), mais ne republie des diagnostics que si `params.getText()` est non null. Si le client n'envoie pas le texte (et n'émet pas de `didChange`), aucune republication n'a lieu : le commentaire promet plus que le code. Soit republier systématiquement depuis le buffer courant (le buffer EST la source de vérité du serveur), soit aligner le commentaire. Cas d'usage réel : re-diagnostic post-sauvegarde après génération de classes (R, databinding) — précisément le moment où l'utilisateur s'attend à voir les HINT disparaître.

**I6 — Canaux d'état parallèles (LSP vs StateFlow hôte).** L'état d'indexation et la progression ne transitent pas par `$/progress`/`window/workDoneProgress` mais par les `StateFlow` Kotlin de l'hôte, consommés par l'UI Android. Cohérent in-process et plus riche (progression par jar, avertissements) — mais cela fait de `lsp:host` un composant obligatoire du protocole de fait : aucun client LSP standard ne pourrait afficher la progression. À documenter dans `LSP_JAVA.md` (et à garder tel quel tant que le transport est in-process ; le jour du transport socket — plan B ADR 0109 — `$/progress` devient le canal naturel).

**I7 — `ServerInfo.version` fantôme.** `initialize()` (L148–151) annonce `"lspjava" 1.2.0`, codé en dur. La version du moteur suit le dépôt (v0.124.7 au jour du rapport) ; une version figée rend les journaux/diagnostics client trompeurs quand plusieurs builds coexistent sur un appareil. Correction triviale : injecter la version par ressource générée du build (pattern déjà utilisé par `lsp:kotlin`/`lsp:xml` — `pack/version.properties` dans leurs `resources`).

**I8 — Renommage : échec silencieux et absence de garde.** `rename()` (L1434–1503) retourne un `WorkspaceEdit` **vide** quand `targetAt` ne trouve rien, sans `ResponseError` : côté éditeur, « rien ne se passe » sans explication (à distinguer d'un renommage légitime sans occurrence — même réponse). LSP 3.17 autorise une erreur typée (ex. `RequestFailed`, code 0) avec message utilisateur. Combiné à l'absence de `prepareRename` (le client ne peut ni valider la position, ni afficher le nom courant comme placeholder), le renommage est la feature dont l'UX d'échec est la plus fragile. Enfin l'edit utilise `changes` (forme héritée) sans `documentChanges` versionnés : acceptable avec cel-lsp, à documenter.

**I9 — Conventions `null` vs liste vide divergentes entre handlers.** `foldingRange` rend `null` si le moteur est absent (« le client garde ses folds lexicaux »), `inlayHint` rend une liste vide, `completion` rend une liste (avec `isIncomplete` honnête), `semanticTokensFull` rend des tokens vides. Chaque choix est localement justifié en commentaire, mais l'ensemble n'est pas gouverné par une règle unique « que rend le serveur quand il ne peut pas répondre honnêtement ? ». Pour la stabilisation : écrire la convention (proposition : `null` = « capacité indisponible ce tour-ci, le client garde son repli » ; liste vide = « capacité disponible, aucune donnée ») et l'appliquer uniformément, avec un test de parité.

**I10 — Légende sémantique minimale.** La légende `semanticTokens` (L122–132) déclare 11 types de tokens et **aucun modificateur** : pas de `static`, `deprecated`, `abstract`, `async`… alors que le highlighter travaille par bindings ecj (il connaît ces natures) et que JDT.LS rend « diagnostic tags » et modificateurs. Enrichir la légende est peu risqué (le client ignore les indices inconnus — à confirmer côté cel-lsp) et améliore immédiatement le rendu.

**I11 — Positions O(n) par conversion.** `toPosition`/`toOffset` (L1869–1886) balayent la source à chaque appel. Sur un renommage à N occurrences dans un fichier de L caractères, la conversion des plages coûte O(N×L) — non négligeable sur ART pour les gros fichiers (les diagnostics renommés dépassent rarement quelques centaines d'occurrences, mais les `references` sur symbole très utilisé le peuvent). Un cache d'offsets de lignes par (uri, version) — recalculé une fois par didChange — supprime le coût. Point de vigilance annexe : le comptage traite `\r` comme caractère de fin de ligne (seul `\n` réinitialise) ; le round-trip interne est cohérent, mais aucun test CRLF ne verrouille le comportement côté client réel.

**I12 — `filterText` absent des items de complétion.** `toLspCompletionItem` (L1773–1846) remplit label/kind/detail/insertText/snippet/sortText/additionalTextEdits (auto-import — très bon), mais jamais `filterText`. Le moteur matche camel-hump/substring (`EcjPrefixMatcher`) : le label d'un candidat hump (ex. préfixe saisi `AOVL`) ne contient pas forcément le préfixe tapé comme sous-chaîne prefixe. Si le client (cel-lsp/code-editor) re-filtre par préfixe strict du label — comportement fréquent — les candidats hump seront éjectés silencieusement après avoir été payés côté serveur. JDT.LS remplit `filterText` exactement pour ça. Action : vérifier la politique de filtrage de cel-lsp ; si elle filtre, remplir `filterText` (le matcher sait déjà pourquoi il a retenu le candidat).

**I14 — Remèdes français concaténés à des diagnostics anglais.** Les downgrades honnêtes R/databinding (L1048–1050, L1086–1089) réécrivent le message : message ecj anglais + « — la classe R est générée par aapt2 lors de la PREMIÈRE construction. Lance un Build… » français. Le reste des diagnostics arrive en locale du serveur (anglais sur ART, `-Duser.language=en`). Résultat : messages bilingues, dont la partie remède n'est pas localisable côté client. Meilleur pattern : transporter le remède dans `data` (champ structuré, déjà utilisé pour les arguments ecj) et laisser le client le rendre selon sa locale — ou assumer un fichier de messages FR coté serveur pour TOUTES les surcharges.

**I15 — `LSP_JAVA.md` s'arrête à v0.108.0.** Le journal a été la discipline de stabilisation du chantier (convention §15.0 point 6 : « LSP_JAVA.md §15.1 mis à jour à chaque lot »), mais quinze versions sont passées sans entrée Java — pendant ce temps l'hôte a gagné les sessions Kotlin/XML, la couverture host a doublé, ADR 0117 phase A est arrivé. Reprendre la convention (une entrée par lot LSP Java, même brève) fait partie du plan de stabilisation : un serveur « stabilisé » dont le journal s'arrête deux mois plus tôt n'est pas auditable.

---

## 7. Comparaison avec les LSP Java de référence

### 7.1 Eclipse JDT.LS (référence de facto — README master vérifié le 2026-10-14)

Le README officiel liste : compilation Java 1.8→25 ; support Maven ; support Gradle (import Android **expérimental**) ; fichiers standalone ; erreurs à la frappe ; complétion ; hovers javadoc ; organisation des imports ; recherche de types ; code actions (quickfixes, actions sources, refactorings) ; outline ; folding ; navigation ; code lens (références/implémentations) ; formatage on-type/sélection/fichier ; snippets ; surlignage sémantique ; sélection sémantique ; tags de diagnostics ; hiérarchie d'appels ; hiérarchie de types ; support annotation processing (Maven) ; résolution automatique des sources des jars Maven ; extensibilité. Runtime : Java 21 minimum, `-Xmx1G` typique, équipes d'exécution Eclipse/OSGi.

| Domaine | Eclipse JDT.LS | CodeIDE (lspjava) | Écart et lecture |
|---|---|---|---|
| Runtime | JVM desktop, processus dédié | **In-process sur ART** (DEX, multidex, 512 Kio/jar de budget cache, CloseGuard traqués) | Avantage décisif CodeIDE sur la cible ; aucun équivalent connu à ce niveau de contrainte |
| Modèle de projet | Import Maven/Gradle (m2e/buildship), invisible project, multi-root | Gradle Tooling + modèles AGP v2 (ADR 0113), module par moteur, android.jar par module | Parité sur le périmètre Android **au-delà** de JDT.LS (dont l'import Android est expérimental) ; CodeIDE n'a ni Maven ni multi-root — hors périmètre légitime |
| Diagnostics | As-you-type, tags, quickfixes riches | As-you-type (debounce 50 ms, supersession), downgrades honnêtes R/databinding, stubs d'intrinsèques | CodeIDE perd sur tags/relatedInformation (I10, S1) et sur la compliance par projet (S1) ; gagne sur l'honnêteté des générés AGP |
| Complétion | Très riche, resolve lazy, filtre fuzzy | Membres/types/imports/keywords/templates live+postfix, ranking codé, auto-import à l'acceptation | Proche sur l'essentiel ; `resolveProvider=false` limite la richesse différée ; `filterText` manquant (I12) |
| Hover | Javadoc markdown | Signature exacte (texte plat) | Écart net — S2 |
| Navigation | definition/typeDef/impl/references/highlight + hierarchies | idem **+** superDefinition/decompiledSource custom | Parité cœur + deux extras ; manquent call/type hierarchy (S2/S3) |
| Refactoring | Extract/inline/move/generate…, rename avec prepareRename et preview | Rename multi-fichiers robuste (overlay, imports qualifiés) ; createMethod, surround, cast, organize imports | Écart net en richesse ; rename UX fragile (I8) — S0/S1 |
| Formatage | on-type/selection/file, profil Eclipse configurable | file only, options non consommées | S1/S2 |
| Recherche | workspace/symbol, type search | documentSymbol only | S1 |
| État/progression | `$/progress`, registration dynamique | StateFlow hôte + barre d'état | Choix in-process (I6) ; ADR 0117 pour la richesse UI |

Lecture d'ensemble : **CodeIDE couvre ~70 % du socle feature JDT.LS qui compte pour un éditeur embarqué Android, avec des fondations (index, modèle, isolation, discipline concurrentielle) que beaucoup de serveurs desktop n'ont pas** — l'écart est concentré sur les méthodes « de confort structurant » (hierarchies, sélection, formatage riche, refactoring riche) et sur la couche de négociation/config, typique de l'état d'un serveur jeune mais bien construit.

### 7.2 java-language-server (Georgiy Tugai)

Serveur léger standalone (javac/javax.lang.model, classpath plat passé en paramètre). Complétion/hover/definition/references/rename/formatting de base, pas d'import de build, index simple. Par rapport à lui, CodeIDE est structurellement en avance (modèle Gradle réel, index disque versionné, moteurs par module, quick-fixes typés). Il n'apporte pas d'idée supplémentaire au plan de stabilisation, si ce n'est la confirmation que la valeur de CodeIDE est précisément dans l'intégration Gradle/Android que les serveurs génériques n'ont pas.

### 7.3 Projet tiers de référence — la provenance

L'audit D3 (§5 de la référence) a établi la dérivation : 3 fichiers niveau A (réécrits — lot A v0.94.0), ~31 fichiers mentionnant le projet de référence dont ~19 de niveau B à réécrire (lot G, non fait), 6 noms de fichiers identiques (renommés au lot B v0.95.0), constantes de scoring identiques à `CompletionRanker`. La GPL-3.0 du projet de référence rend la situation bloquante pour toute publication du moteur tant que le lot G n'est pas traité ou autorisé. **C'est le point unique qui conditionne l'avenir « bibliothèque » du module** (l'étude d'extractibilité §8 concluait déjà : extraire plus tard, jamais avant règlement D3).

### 7.4 Android Studio (la référence UX implicite)

La référence compare explicitement CodeIDE à Android Studio sur quatre dimensions : branchement pré-sync (atteint — v0.101/102), réouverture rapide (atteinte — modèle persisté, v0.102), honnêteté des générés (R/databinding couverts ; ViewBinding/Hilt/Room/KSP explicitement non — §17.3), dumb mode (atteint — mode instantané). Restent hors de portée assumée : la richesse des quick-fixes IntelliJ, l'analyse Lint, la résolution de ressources Android dans le code Java (les références `R.` ne sont pas cliquables vers les XML — le LSP XML existe côté CodeIDE, un pont Java↔XML serait le différenciateur long-terme).

---

## 8. Gaps fonctionnels par domaine (synthèse orientée stabilisation)

### 8.1 Diagnostics

Le pipeline (push, debounce 50 ms, supersession par fichier, annulation coopérative, downgrades honnêtes) est solide. Manquent : **tags LSP** (`Unnecessary` sur les « unused » ecj, `Deprecated`) — JDT.LS en fait un argument de vente ; **`relatedInformation`** (les messages « indirectly referenced from required .class files » gagneraient à pointer le fichier binaire fautif) ; **`codeDescription`/`data` de remède structuré** (cf. I14) ; la **compliance par projet** (diagnostics de niveau faux hors Java 17 — le gap n°1 de ce domaine) ; et le **pull model 3.17** (non prioritaire in-process). S'ajoute la question non tranchée de la **génération post-sync** des sources AGP (option `generate<Variant>Sources` en fin de sync, §17.3 — trancher avec la politique du démon).

### 8.2 Complétion

La complétion est la feature la plus aboutie (membres par bindings, types par index préfixe, imports avec auto-insertion à l'acceptation, templates live/postfix avec snippets et éditions additionnelles, ranking). Gaps : `filterText` (I12 — à coupler avec la complétion native par mots-clés de code-editor 3.49.2 pour éviter le double emploi) ; `resolveProvider` (la documentation des items — javadoc — devrait être lazy pour alléger les réponses) ; pas de complétion dans les javadocs/annotations arguments ; limite d'items par requête non exposée (l'index a une limite interne, le client n'apprend jamais qu'il existe une suite).

### 8.3 Navigation et recherche

Cœur complet et même enrichi (superDefinition, decompiledSource). Manquent : **`workspace/symbol`** (le substrat existe : `nomsParPrefixe` de l'index — c'est la façade LSP qui manque) ; **callHierarchy/typeHierarchy** (le moteur a héritiers et super — généralisation naturelle) ; **liens symboliques vers les ressources Android** (différenciateur produit, cf. §7.4).

### 8.4 Refactoring et actions

Inventaire constaté dans `CodeActionService` : importer un FQN, retirer import inutilisé, point-virgule manquant, créer une méthode depuis l'usage, surround try/catch, declare throws, cast, organiser les imports. C'est honnête mais loin du socle JDT.LS (extract variable/constant/method, inline, move, override/implement, generate toString/equals/hashCode/constructors/getters, anonym→classe nommée). Pour un IDE Android embarqué, les trois à plus forte valeur utilisateur sont **override/implement methods**, **extract method** et **generate constructor/getters** — dans cet ordre. Prioritaire d'abord : fiabiliser rename (I8) avant d'étendre.

### 8.5 Formatage

Édit unique pleine plage, `EcjFormatter` (moteur Eclipse), source imparsable → no-op (bon contrat). Manquent : `rangeFormatting` ; consommation des `FormattingOptions` (`tabSize`/`insertSpaces` — aujourd'hui ignorées) ; profil configurable (le JDT.LS lit les `.settings` Eclipse — pour CodeIDE, des réglages d'app suffisent) ; `onTypeFormatting` (« ; »/« { ») une fois le client capable.

### 8.6 Affordances éditeur

`selectionRange` (sélection sémantique croissante), `codeLens` (compteurs), `linkedEditingRange`, modificateurs sémantiques (I10) : autant d'améliorations de confort à arbitrer avec la feuille de route cel-lsp/code-editor (elles exigent souvent un travail client symétrique).

### 8.7 Modèle de projet / plateforme

Quatre limites assumées par la référence : compliance par module (protocole v10 — champ optionnel, `null`=17, compatible v8+) ; androidTest non livré (une portée de plus, pas de changement de format) ; ADR 0112 strict (jdkHome par module — l'API moteur existe déjà par contexte, il manque la descente des champs) ; mono-workspace par session (registre ADR 0114 — documenté, non contraignant).

---

## 9. Registre consolidé des incohérences / dettes (sévérité et effort)

| # | Constat | Emplacement | Sévérité | Effort correctif |
|---|---|---|---|---|
| I1 | Négociation capabilities documentée, inopérante | `ServerCapabilitiesProvider.java` L10–33 vs L35–142 | Faible (doc) | XS (javadoc) / S (vraie négociation) |
| I2 | Lifecycle « doit rejeter » jamais appliqué | `JavaLanguageServer.java` L52–56 ; aucun handler | Moyenne (client tiers) | S |
| I3 | `initialize` réentré → warning au lieu d'erreur | `JavaLanguageServer.java` L125–127 | Faible | XS |
| I4 | `didChangeWatchedFiles` : voie muette (pas de watcher hôte) | `JavaWorkspaceService.java` L50–80 ; rien côté `lsp:host` | Moyenne | M |
| I5 | `didSave` sans texte → pas de republication (contraire au commentaire) | `JavaTextDocumentService.java` L261–289 | Faible | XS |
| I6 | Doubles canaux d'état (LSP `$/progress` absent — choix in-process) | `SessionLsp.kt` / `OrchestrateurLsp.kt` | Faible (choix) | Doc |
| I7 | `ServerInfo.version` « 1.2.0 » codée en dur | `JavaLanguageServer.java` L148–151 | Faible | XS |
| I8 | rename : échec silencieux, pas de prepareRename, `changes` héritée | `JavaTextDocumentService.java` L1434–1503 | **Haute (UX)** | S |
| I9 | Convention null/vide divergente entre handlers | folding L1562 vs inlay L1604+ | Faible (cohérence) | S |
| I10 | Légende semanticTokens minimale (0 modificateur) | `ServerCapabilitiesProvider.java` L122–132 | Moyenne (rendu) | M |
| I11 | Positions O(n) par conversion ; CRLF non testé | `JavaTextDocumentService.java` L1869–1886 | Moyenne (perf ART) | S |
| I12 | `filterText` jamais rempli (risque filtrage client) | `JavaTextDocumentService.java` L1773–1846 | **À vérifier (haute si le client filtre)** | S |
| I14 | Remèdes FR concaténés aux messages ecj EN | L1048–1050, L1086–1089 | Faible (i18n) | S |
| I15 | `LSP_JAVA.md` arrêté à v0.108.0 | `docs/LSP_JAVA.md` | Moyenne (maintenabilité) | Continu |
| D3/G | ~19 fichiers niveau B dérivés du projet de référence non réécrits | `lsp/java` (liste §5.3 de la référence) | **Critique (juridique)** | L (multi-sessions) |

Les W1–W10 historiques, à l'inverse, sont **clos** : W1 (index persisté), W2/W3 (index préfixe), W5 (sourcesets par module), W6 (empreintes de contenu), W7 (persistance validée), W8 (parse cache multi-entrées + moteur par module), W9 (pool LRU conservé), W10 (R/databinding couverts ; les autres générés restent sur constat — assumé).

---

## 10. Points forts à préserver (garde-fous de la stabilisation)

1. **Isolation stricte moteur ↔ monde** : zéro dépendance interne du module vendored (SPI `IndexNoms`), `checkModuleDependencies` en CI, `lsp:testing` comme unique point de contact. Toute évolution doit passer par ces coutures, jamais en contournant.
2. **« Zéro déglutition silencieuse »** : la règle AGENTS est réellement appliquée dans le code lu (chaque catch trace en WARNING avec contexte) — la conserver pendant les refactorings à venir est ce qui maintiendra le serveur diagnostiquable sur appareil.
3. **Honnêteté des régimes** : mode instantané annoncé, `isIncomplete` honnête, downgrades réversibles (coupure dès que la première classe générée existe), négatifs autoritatifs seulement à l'état PRÊT. C'est le contrat de confiance de tout le LSP — ne jamais le sacrifier à une métrique.
4. **Tests repro sur bugs réels** : chaque défaut appareil/propriétaire est devenu un test (`StringConcatFactoryReproTest`, `DatabindingImportDowngradeTest`, `DeviceFqcnReproTest`, `RealClasspathDeviceReproTest`…) — le plan §11 demande la même discipline pour les nouveaux domaines (CRLF, positions, rename UX).
5. **Le test de fuite de session + l'attente bornée de `close()`** : la seule garantie « 0 ressource orpheline » du périmètre — à réexécuter à chaque lot touchant l'hôte.
6. **Le journal `LSP_JAVA.md` comme discipline** : reprise de la convention une-entrée-par-lot (I15).
7. **Le banc versionné** (`:lsp:testing:bench` + recette des jeux) : l'outil anti-dérive de performance existe déjà — il ne manque qu'un rythme (§11.3).

---

## 11. Plan de stabilisation recommandé

### 11.1 Principes d'exécution (conventions du dépôt, non négociables)

Chaque lot = un commit `feat(lsp-java): vX.Y.Z — <lot>`, `version.properties` bumpé, CHANGELOG en tête, entrée AGENTS.md, `LSP_JAVA.md` mis à jour (reprise de la convention §15.0 point 6), tests verts **avant** push, push immédiat (le travail non poussé est perdu au reset), surveillance CI en arrière-plan (directive v0.122.2 d'AGENTS.md). Le module vendored reste exempté detekt/spotless et jamais reformaté. Frontière de lot = dépôt vert.

### 11.2 Séquences proposées

**S0 — Fiabiliser ce qui existe (2-3 lots, effort S chacun, gain immédiat)**

| Lot | Contenu | Critère d'acceptation |
|---|---|---|
| S0-a « hygiène protocolaire » | I2 (garde lifecycle centralisée) + I3 + I5 + I7 (version injectée) + I9 (convention null/vide écrite et appliquée) | Tests lifecycle (requête avant init / après shutdown rejetée), test didSave sans includeText, `serverInfo.version` = version du dépôt ; suite verte |
| S0-b « rename honnête » | I8 : `prepareRename` (plage + placeholder), `rename` en erreur typée quand aucune cible (message FR via data), `documentChanges` versionnés si cel-lsp les accepte | Tests LSP4J client espion : placeholder exact, erreur explicite, renommage nominal inchangé |
| S0-c « décision & mesure » | **Décision D3 du propriétaire** (lot G maintenant/plus tard/jamais-publier) + exécution du protocole appareil §11.2 (séance dédiée, résultats dans `LSP_JAVA.md` §18) | PV de mesure appareil archivé ; décision D3 actée (ADR 0110 mis à jour) |

**S1 — Parité utile JDT.LS (3-4 lots, effort M)**

| Lot | Contenu | Critère |
|---|---|---|
| S1-a « compliance par projet » | Champ optionnel `sourceCompatibility`/`targetCompatibility` dans `ClasspathModule` (protocole v10, `null`=17 — rétrocompatible), descente jusqu'aux moteurs (compliance par contexte module — l'API du lot E l'accueille déjà) | Fixture multi-projets Java 8/21 : `var` refusé à 8, records/switch signalés conformes à 21 ; goldens v10 régénérés |
| S1-b « diagnostics riches » | Tags `Unnecessary`/`Deprecated` (bits ecj → tags LSP), `relatedInformation` sur les « indirectly referenced », remède dans `data` (I14) | Tests de mapping ; rendu vérifié côté éditeur |
| S1-c « workspace/symbol » | Façade LSP sur `nomsParPrefixe` + symboles des sources (kind + conteneur), plafond + `workspaceSymbol/resolve` si besoin | Test : recherche par préfixe sur projet multi-modules + jars ; latence p95 < 50 ms (index PRÊT) |
| S1-d « watched files côté hôte » | I4 : observateur du répertoire projet (`.java`, limité) poussant `FileEvent` au serveur ; invalidation locate/parse ciblée | Test : modification/suppression externe → diagnostics et navigation à jour sans réouverture |

**S2 — Confort structurant (arbitré selon feuille de route cel-lsp)**

`rangeFormatting` + consommation des `FormattingOptions` ; `selectionRange` ; `onTypeFormatting` ; `codeLens` (références/implémentations, rafraîchi avec les diagnostics) ; hiérarchies appel/types (généralisation de `findImplementations`/`findSuper`) ; légende sémantique étendue (I10) ; hover javadoc (markdown négocié) ; `filterText` (I12, après vérification client) ; cache d'offsets de lignes (I11) ; `willSaveWaitUntil` « organiser les imports à la sauvegarde » (option réglage). Chacun de ces items est indépendant : découper en lots autonomes, un arbitrage cel-lsp par item (le client doit consommer).

**S3 — Structurels / différés (déjà documentés par la référence)**

Lot G (réécriture D3 des ~19 fichiers, multi-sessions — déclenché par la décision S0-c) ; ADR 0117 phase B (écran d'indexation — dépend du GO utilisateur) ; ADR 0112 strict (jdkHome par module) ; androidTest (portée de modèle) ; pont Java↔XML sur `R.` (différenciateur produit) ; transport socket (plan B ADR 0109 — uniquement si les mesures appareil S0-c l'exigent) ; extraction bibliothèque (après D3 uniquement).

### 11.3 Garde-fous de stabilité à instaurer

1. **Suivi du taux de tests ignorés** par module dans la CI (alerte si delta > 0 sans justification) — les 76 skips actuels sont environnement, mais un skip silencieux est une régression déguisée.
2. **Exécution périodique du banc** (`:lsp:testing:bench`, jeux petit/moyen/lourd) — hebdomadaire ou par release mineure, résultats archivés dans `LSP_JAVA.md` ; cible de garde : complétion types p95 < 100 ms (§9 de la référence).
3. **Un test CRLF/positions** (documents `\r\n`, positions en plein milieu d'une fin de ligne, renommage sur fichier CRLF) — verrouille I11.
4. **Un test de soak JVM minimal** : N=5 000 didChange + complétions + diagnostics alternés sur 3 fichiers, assertions sur tas stable/poignées constantes — étend le contrat dispose() du banc au régime prolongé.
5. **Kover ≥ 80 % maintenu** sur `lsp:host` (règle déjà en place — v0.114.1 l'a montré utile).
6. **Traquer en issues** les deux irritants d'infrastructure connus (isolation `LspEditorViewModelTest` sous `--rerun-tasks` ; `--rerun-tasks` vs bootstrap build-logic) pour qu'ils cessent d'être des connaissances tribales.

### 11.4 Quick wins (≤ une demi-journée chacun)

| Quick win | Référence | Impact |
|---|---|---|
| Version moteur injectée depuis le build | I7 | Diagnostic client fiable |
| Javadoc de `ServerCapabilitiesProvider` corrigé (ou négociation minimale inlayHint/semanticTokens) | I1 | Vérité du contrat |
| `didSave` republie depuis le buffer | I5 | Cohérence post-build |
| Convention null/vide uniformisée + test de parité | I9 | Maintenabilité |
| Remède downgrades dans `data` (structure) puis rendu localisé côté client | I14 | i18n propre |
| `filterText` rempli (après lecture de la politique cel-lsp) | I12 | Complétion hump garantie |
| Entrée `LSP_JAVA.md` « état v0.124.7 » (bilan post-v0.108) | I15 | Reprise du journal |

### 11.5 Note d'environnement de développement (constat du run de tests)

Sur la machine d'audit (2 cœurs, ~4 Go), l'exécution simultanée des cinq modules LSP avec les réglages par défaut du `gradle.properties` provoque un **OOM-kill du daemon Gradle** (kernel : `Out of memory: Killed process … anon-rss ≈ 2,3 Go`). La passe réussie a requis : exécution **séquentielle module par module**, `--no-daemon`, `-Dorg.gradle.jvmargs="-Xmx1300m -XX:MaxMetaspaceSize=384m"`, `-Dorg.gradle.workers.max=1`, `-Dkotlin.daemon.jvmargs="-Xmx700m"`, et l'arrêt préalable des daemons résiduels. Ce n'est pas un défaut de CodeIDE (les JVM de test sont déjà bornées à 640 Mo par le convention plugin — `KotlinLibraryConventionPlugin` L47), mais c'est une donnée opérationnelle pour tout agent/CI contraint : à consigner dans le journal de travail partagé et, idéalement, dans `docs/ENVIRONNEMENT.md` côté dépôt (contribution documentation seulement — pas de changement de build requis).

---

## 12. Annexes

### A. Inventaire du module `lsp/java` (main, 51 fichiers)

- **Façade LSP4J** (`jo.lspjava.lsp`) : `JavaLanguageServer` (349 l.), `JavaTextDocumentService` (1 990 l. — tous les handlers), `JavaWorkspaceService` (94 l.), `ServerCapabilitiesProvider` (144 l.), `NavLanguageServer`/`NavTextDocumentService` (interfaces des méthodes custom superDefinition/decompiledSource).
- **Services** (`jo.lspjava.services`) : `DiagnosticService` (101 l.), `HoverService` (68 l.), `NavigationService` (152 l.), `CodeActionService` (915 l.), `JavaProblemFamily` (193 l.).
- **Moteur ecj** (`jo.lspjava.ecj`) : `EcjCompilerEngine` (2 248 l.), `EcjCompilerEnvironment` (1 695 l.), `EcjCompletionEngine` (2 624 l.), `EcjRenameEngine` (976 l.), `EcjNavigationEngine` (768 l.), `EcjInlayHints` (464 l.), `EcjSemanticHighlighter` (453 l.), `EcjPostfixTemplates` (404 l.), `ClassFileStubBuilder` (399 l.), `EcjImportOrganizer` (371 l.), `EcjFastDiagnose` (556 l.), `SourceFocuser` (509 l.), `EcjBinarySourceNames` (614 l.), `EcjSignatureHelp` (587 l.), `EcjFormatter` (229 l.), `EcjCodeFolder` (243 l.), `EcjSyntaxCompletion` (278 l.), `EcjLocateCache`, `EcjParseCache`, `EcjPrefixMatcher`, `EcjAst`, `EcjSourceUnit`, `RawZipReader` (302 l.), `StubsIntrinseques`.
- **Engine** (`jo.lspjava.engine`) : `CompilationEnvironment` (636 l. — moteurs par module, lot E), `EcjEngineScheduler` (486 l. — lanes/timeout/watchdog), `CancelToken`, `OperationCanceledException`.
- **API publique** (`jo.lspjava.api`) : `CompletionItem`, `Diagnostic`, `IndexNoms` (SPI), `NavigationTarget`, `DecompiledSourceParams/Result`, `Severity`, `TextRange`.
- **Compat ART** (`jo.lspjava.ecj.compat`) : `ArtCompatVersion`, `ArtCompatStackWalker`, `ArtCompatInputStream` (réécrits depuis spec JDK — lot A).

### B. Méthodes LSP effectivement servies (constat du grep des handlers)

`initialize`, `initialized`, `shutdown`, `exit` ; `didOpen`, `didChange`, `didClose`, `didSave` ; `completion`, `resolveCompletionItem` (no-op), `hover`, `definition`, `typeDefinition`, `implementation`, `references`, `documentHighlight`, `documentSymbol`, `codeAction`, `rename`, `formatting`, `foldingRange`, `inlayHint`, `signatureHelp`, `semanticTokens/full` ; customs `textDocument/superDefinition`, `textDocument/decompiledSource` ; workspace `didChangeConfiguration` (no-op), `didChangeWatchedFiles` (invalidation parse), `executeCommand` (no-op loggué).

### C. Références

- `docs/LSP_JAVA.md` (référence de mission, 1 806 l.) ; `docs/adr/0108|0109|0110|0111|0112|0113|0117` ; `docs/audit/INDEXATION_PHASE_A.md` ; `AGENTS.md` (règles impératives + workflow agent IA asynchrone v0.122.2) ; `CHANGELOG.md` (v0.100.0→v0.124.7).
- Eclipse JDT Language Server — README officiel branche master (liste des features, consulté le 2026-10-14) : compilation 1.8–25, Maven/Gradle (import Android expérimental), as-you-type, complétion, hovers javadoc, organize imports, type search, code actions, outline, folding, navigation, code lens, formatage on-type/selection/file, snippets, surlignage sémantique, sélection sémantique, tags de diagnostics, call/type hierarchy, annotation processing, résolution de sources Maven ; runtime Java 21 min.
- java-language-server (Georgiy Tugai) — positionnement léger/générique.
- projet tiers de référence (GPL-3.0) — source de provenance du moteur (audit D3 §5 de la référence).
- Run de vérification du jour : suites des 5 modules LSP exécutées le 2026-10-14 sur clone lecture seule (498 tests / 0 échec / 76 ignorés), arbre du dépôt resté propre.

*Fin du rapport.*
