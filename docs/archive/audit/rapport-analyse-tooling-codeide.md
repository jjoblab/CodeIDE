# Rapport d'analyse — Tooling Gradle de CodeIDE (chaîne client-serveur)

- **Date** : 2026-10-14
- **Référence étudiée** : `docs/TOOLING.md` (288 l., « Tooling Gradle (client-serveur) ») + `docs/tooling-scenarios/RAPPORT.md` (369 l., phase 0 de G12)
- **Dépôt** : CodeIDE, commit `bd6f458` (v0.124.7, 2026-10-14) — **strictement en lecture seule**
- **Chaîne analysée** : modules `tooling:protocol`, `tooling:api`, `tooling:server`, `tooling:client`, `tooling:daemon`, `tooling:testing` + consommateurs (`core:domain`, `feature:editor`)
- **Objectif** : identifier gaps, incohérences et améliorations en vue de **stabiliser** la brique — même méthode que les rapports LSP Java (`rapport-analyse-lsp-java-codeide.md`), LSP Kotlin et LSP XML
- **Hors périmètre** : `tools/generateur` (générateur de modèles de projets — mission Templates, doc propre), bootstrap d'installation (`docs/ENVIRONNEMENT.md`, ADR 0085-0091) — la référence `TOOLING.md` couvre la chaîne Gradle client-serveur uniquement

---

## 1. Synthèse exécutive

La chaîne Tooling Gradle de CodeIDE est **la brique la plus solide des quatre étudiées** (Java, Kotlin, XML, Tooling) sur le plan architectural et expérimental : inversion client-serveur exemplaire (l'app écoute, l'orchestrateur meurt seul — zéro orphelin prouvé par des tests de chaos sur VRAI `kill -9`), protocole versionné à handshake strict figé par 38 fichiers dorés, 22 tests d'intégration contre un **vrai Gradle** (annulation d'un build de 60 s en ~2,6 s, classpath des modules Android par le modèle v2 d'AGP), discipline « jamais d'affirmation devinée » concrétisée par un rapport de scénarios qui **invalide ses propres hypothèses** (phase 0 de G12), injection de journaux sans adb réputée impossible et pourtant livrée proprement (v0.83.0, ADR 0103).

MAIS la mission déclarée « finie » (G1→G12, dernier jalon v0.41.0) s'est **fragmentée silencieusement** pendant les 83 versions suivantes : le protocole est passé v7→v8→v9 sous l'impulsion d'AUTRES missions (Projet, LSP Java, Cycle de vie) sans que `docs/TOOLING.md` ne soit rattrapé (il documente encore « v4 » comme version courante), et deux des six messages du protocole v7 sont des **fantômes de bout en bout** — `ResolvedDependenciesRequest` et `BuildVariantsRequest` n'ont **aucun émetteur en production** (vérifié par recherche exhaustive : seuls les échantillons de test les construisent), **aucun test** (zéro des 210 tests ne les exerce), et débouchent sur un onglet UI « Variantes » coquille vide marqué « P5 (à venir) ». Le comble : `BuildVariantsHandler` publie une liste vide en invoquant l'absence du modèle AGP — **qui est dans le classpath du serveur depuis la v8** (`ModelesAndroidV2.kt` lit déjà `basic.variants` quelques fichiers plus loin).

À cela s'ajoutent deux mensonges de diagnostic et un angle mort de conformité : `ServerVersion.CURRENT = "0.50.0"` codée dur (74 versions de retard sur l'app, KDoc promettant un alignement manuel à chaque livraison — même pathologie que `version.properties` du LSP Kotlin), et un fat jar `gradle-server.jar` (~7,6 Mo) embarquant gradle-tooling-api, builder-model, kotlin-stdlib et kotlinx-serialization **absents de `THIRD_PARTY_NOTICES.md`** (vérifiées le 2026-09-23, deux jours AVANT que G2 ne livre le premier jar). Enfin, la couverture CI des modules JVM repose entièrement sur un effet de bord (`koverVerify` tire `:tooling:server:test`) — le module `tooling:testing` le prouve : ses tests n'apparaissent **nulle part** dans la chaîne CI.

**Verdict : socle exemplaire, périmètre non tenu.** 18 constats priorisés TP1-TP18, plan de stabilisation S0-S3 estimé à ~13 jours.

---

## 2. Périmètre et méthode

### 2.1. Documents lus intégralement

- `docs/TOOLING.md` — 288 l. : versions vérifiées, architecture livrée (fin G6 + v4/v5), délais de garde §7.5, comportement au chaos, avertissement bénin du daemon, journalisation, CI, étapes G1-G12 (v0.26.0 → v0.41.0).
- `docs/tooling-scenarios/RAPPORT.md` — 369 l. : matrice des 10 scénarios réels exécutés (2/12 non couverts), signaux par phase, catalogue d'étapes dynamiques, 5 hypothèses invalidées, recommandations C/D/E, 6 points non traités.
- ADR pertinents : 0037 (CI), 0039-0044 (G1-G6), 0057-0058, 0065, 0070-0074, 0078-0082 (correctifs v0.36-v0.51), 0095-0096 (tiroir Projet + protocole v7), 0103 (applog), 0113 (classpath Android modèle v2), 0114 (cycle de vie/ADR sessions).
- `AGENTS.md` (règles tooling : JAR artefact de build, fixtures, ModuleRules gelées), `CHANGELOG.md` v0.41.0 → v0.124.7 (entrées tooling : v0.47-v0.51, v0.83, v0.99.1, v0.100.1, v0.104-0.105).

### 2.2. Code cartographié

| Module | Type | Main | Tests |
|---|---|---|---|
| `tooling:protocol` | Kotlin/JVM + serialization | 5 fichiers / 1 293 l. | 4 fichiers / 683 l. |
| `tooling:api` | Kotlin/JVM | 2 fichiers / 217 l. | 1 fichier / 140 l. |
| `tooling:server` | Kotlin/JVM (shadow fat jar) | 35 fichiers / 4 857 l. | 15 fichiers / 3 292 l. |
| `tooling:client` | Bibliothèque Android | 6 fichiers / 2 227 l. | 4 fichiers / 1 158 l. |
| `tooling:daemon` | Bibliothèque Android | 5 fichiers / 1 330 l. | 5 fichiers / 1 849 l. |
| `tooling:testing` | Kotlin/JVM (fixtures) | 1 fichier / 79 l. | 1 fichier / 50 l. |
| **Total** | | **54 fichiers / 10 003 l.** | **30 fichiers / 7 172 l.** |

Fonctionnalités suivies au fil du câble : 49 commits touchant `tooling/` ; gel de fait — `tooling:api` inchangé depuis 2026-09-29 (38d7933), `tooling:testing` depuis v0.92.0 (2026-10-11), `protocol`/`server`/`client` depuis v0.105.0 (04a65ea, protocole v9), `daemon` depuis v0.100.1 (59230bf, correctif retour d'appareil ProcessHandle ART + course JarDeployer).

### 2.3. Vérifications exécutées (lecture seule, arbre resté propre)

- Tests des 6 modules, séquentiels, `--no-daemon`, heap borné (machine 2 cœurs/4 Go) — résultats en §4.
- `:tooling:server:koverVerify` — RÉUSSI (seuil 75 %, cf. TP3).
- Dry-runs Gradle pour tracer ce que la CI déclenche réellement (`testDebugUnitTest`, `koverVerify`).
- Recherche exhaustive des constructeurs de requêtes protocolaires (`grep -rn` sur tout le dépôt) — preuve TP1.
- Historique des bumps `PROTOCOL_VERSION` par `git log -S` — preuve TP9.
- Comparaison web (2026-10-14) : documentation officielle de la Tooling API (docs.gradle.org), issues gradle/gradle#8219 (`stopDaemons()` réclamé par les IDE) et IDEA-375876, documentation AndroidIDE 2.7, dépôt CodeOnTheGo — §9.

---

## 3. Le socle — ce que la brique fait remarquablement bien

Il faut le dire avant les gaps, parce que ce socle est rare : **la discipline d'ingénierie de cette chaîne est supérieure à celle de beaucoup d'outils professionnels**, y compris sur certains points à Android Studio.

1. **L'inversion client-serveur est architecturalement saine.** L'app Android EST le serveur du socket Unix (écoute ouverte AVANT le lancement du process — aucun fichier de découverte, aucun polling) ; l'orchestrateur est le client et **meurt seul** à la mort de l'app (EOF → code 0, prouvé par `ChaosToolingTest` réel). Aucun orphelin, aucun répertoire de découverte, namespace fichier en répertoire privé `0700`, secret aléatoire échangé en argument de lancement, handshake exigeant l'**égalité exacte** des versions de protocole avec refus typé `PROTOCOL_VERSION_MISMATCH` AVANT tout handler.
2. **Le protocole est doré et versionné.** 38 fichiers JSON figeant le format câble ; tout renommage ou retrait de champ fait échouer le test d'adéquation (`REGENERER_DORES=1` pour la régénération dédiée). Chaque version de protocole (v1→v9) est documentée dans la KDoc de `GradleProtocol.PROTOCOL_VERSION` avec sa raison d'être et ses conséquences de compatibilité — une historique vivante du câble.
3. **Les intégrations sont RÉELLES.** `ServeurIntegrationTest` (22 tests) lance le vrai `ServerMain` sur un vrai socket Unix JDK contre 4 fixtures Gradle réelles : annulation d'une tâche de 60 s en ~2,6 s, diagnostics positionnés sur erreur de compilation, classpath des modules Android par le **modèle v2 d'AGP** (`ModelesAndroidV2.kt`, ADR 0113 — les 26 modules Android du dépôt de référence livrent enfin sources/classpath/android.jar), fermeture de projet idempotente (v9), requête inconnue sans coupure de session. `ScriptAppLogIntegrationTest` prouve l'injection Gradle sur un projet témoin hostile (`FAIL_ON_PROJECT_REPOS`, variantes nommées AGP).
4. **Le chaos est éprouvé, pas promis.** `kill -9` en plein build → builds en cours conclus `ECHOUE` (« connexion perdue ») + canaux fermés, jamais de suspension infinie ; orchestrateur muet → ping/pong 5 s/15 s → kill + relance bornée (5) ; JDK introuvable → `DECONNECTEE` sans boucle ; épuisement des relances → échec définitif. Les correctifs de robustesse sont documentés en ADR avec leur cause racine (0079 flux ordonné, 0080 santé ingelable, 0081 fil de lecture dédié « le réveil appartient au noyau », 0082 binaires SDK recompilés).
5. **La discipline anti-menteur est unique.** La phase 0 de G12 (`tooling-scenarios/RAPPORT.md`) a exécuté 10 projets Gradle réels pour observer les signaux AVANT de construire l'UI — et **invalide noir sur blanc 4 hypothèses** (la phase DAEMON n'est pas détectable via la Tooling API ; un projet sans dépendances runtime télécharge quand même les plugins ; la distribution se re-télécharge à chaque changement d'empreinte du wrapper ; `gradle tasks` ne résout pas les dépendances). Toute étape dont le signal n'est pas fiable est retirée du catalogue affiché — « jamais d'affirmation devinée ».
6. **L'orchestrateur est un citoyen mémoire honnête** : `-Xmx256m`, heap monitoré (`HeapMonitor` → `observeHeap()`), JAR déployé à marqueur SHA-256 par `JarDeployer` (recopie seulement au changement), jamais versionné (artefact de build `preBuild`, ADR 0040).
7. **L'injection de journaux sans adb (v0.83.0, ADR 0103)** est un exploit discret : script d'init Gradle n'utilisant que des API publiques (`beforeSettings`, dépôt local au niveau RÉGLAGES compatible `FAIL_ON_PROJECT_REPOS`), ajout aux `*RuntimeClasspath` **debug uniquement**, interrupteur honnête `codeide.applog.isEnabled=false` dans le `gradle.properties` du projet — l'utilisateur garde le dernier mot sur SON build.
8. **La sortie est apaisée** : `--console=plain` forcé en dernier argument de tout build, avertissement bénin du daemon Gradle (`native-platform` sans binding Android) rendu en style informatif — l'utilisateur n'est pas alarmé pour un diagnostic connu.

---

## 4. État vérifié fraîchement (2026-10-14, lecture seule)

| Commande (séquentielle, `--no-daemon`, `-Xmx1300m`, workers=1) | Résultat |
|---|---|
| `:tooling:protocol:test` | **19 tests, 0 échec, 1 ignoré** (le régénérateur de dorés, exécuté seulement avec `REGENERER_DORES=1` — bénin) |
| `:tooling:api:test` | **8 tests, 0 échec** |
| `:tooling:server:test` | **115 tests, 0 échec** (dont 22 intégration réelle + 2 applog sur vrai Gradle) |
| `:tooling:client:testDebugUnitTest` | **30 tests, 0 échec** |
| `:tooling:daemon:testDebugUnitTest` | **35 tests, 0 échec** |
| `:tooling:testing:test` | **3 tests, 0 échec** |
| **Total** | **210 tests, 0 échec, 1 ignoré** |

- `git status` : arbre **propre**, aucun fichier suivi modifié — l'intégrité du dépôt en lecture seule est préservée.
- `:tooling:server:koverVerify` : **RÉUSSI** — mais au seuil de **75 %** (`build.gradle.kts` L49-56), le plus bas des six modules à seuil (client/daemon : 80 %).
- `version.properties` de l'app : `0.124.7` — cohérent avec le tag. À comparer avec `ServerVersion.CURRENT = "0.50.0"` (TP4).

Le socle test est donc **vert au moment de l'analyse** — aucune régression rouge à la Kotlin (baseline de parité). Les constats qui suivent sont des gaps de périmètre, d'honnêteté documentaire et de conformité, pas des cassures.

---
## 5. Constats priorisés (TP1-TP18)

Sévérités : **C**ritique (bloque une promesse ou expose juridiquement) · **M**ajeur (fonctionnalité mensongère ou dette lourde) · **m**ineur (hygiène, doc, confort).

### TP1 (C, fonctionnel) — P4/P5 : six messages fantômes de bout en bout

La famille v7 « tiroir Projet » (ADR 0096) est **définie, dorée, routée… et jamais invoquée** :

- **Protocole** : `ResolvedDependenciesRequest/Result`, `BuildVariantsRequest/Result`, `ResolvedDependencyNode`, `ResolvedDependencyKind`, `BuildVariantInfo` existent dans `Messages.kt` et sont figés par des dorés (`EchantillonsMessages.kt` les construit — **seuls constructeurs de tout le dépôt**, vérifié par `grep -rn` exhaustif).
- **Serveur** : `ResolvedDependenciesHandler.resoudre()` est implémenté (v0.105-era, branché sur `IdeaProject`), `BuildVariantsHandler.variantes()` publie **toujours `emptyList()`**.
- **Client** : `GradleApiImpl` route les deux résultats vers des promesses (L788/L795) — branches **inatteignables en production** puisque aucune méthode publique ne crée ces requêtes (`scriptsBuild()` existe pour P1 ; rien pour P4/P5).
- **Domaine** : `GradleToolingPort` n'expose NI `dependancesResolues()` NI `variantes()` (vérifié : aucune des 16 méthodes du port).
- **UI** : `OngletProjet.VARIANTES` est déclaré « **P5 (à venir)** : variantes de build » (`ProjetViewModel.kt` L214) — l'onglet s'affiche dans le tiroir (`ProjetFragment` L94) sans donnée derrière.

**Conséquence** : trois couches de code mort semi-actif (branches routeur, handler vide, onglet coquille) + un protocole qui transporte des messages que personne n'émet. Un lecteur de `docs/TOOLING.md` ou de l'ADR 0096 croit que le tiroir Projet expose dépendances résolues et variantes ; **l'utilisateur voit « aucune variante » pour tout projet, à tout jamais**. Le test d'exhaustivité proposé en §12.2 aurait attrapé ça à la compilation du test.

### TP2 (C, juridique) — Le fat jar distribue du code tiers non déclaré

`gradle-server.jar` (~7,6 Mo, artefact `preBuild` recopié dans `app/src/main/assets/tooling/`, AGENTS.md L132) est un fat jar **shadow** qui embarque : `org.gradle:gradle-tooling-api` 9.7.1 (Apache-2.0), `com.android.tools:builder-model` (Apache-2.0, depuis v0.92.0), `kotlin-stdlib` (Apache-2.0), `kotlinx-serialization-json` (MIT), `kotlinx-coroutines-core-jvm` (MIT).

`docs/THIRD_PARTY_NOTICES.md` (36 l., « Bibliothèques tierces **distribuées dans les binaires** de CodeIDE ») liste 9 entrées — cel-ui, termux ×2, icônes IntelliJ/AS, grammaire Kotlin, syntax-api, LayoutLib, NewTestParser — **aucune pour les cinq dépendances du fat jar**. Les « Notes de vérification » datent du **2026-09-23**, deux jours avant que G2 (v0.27.0) ne livre le premier jar : les notices n'ont jamais été rattrapées quand la chaîne a commencé à redistribuer. Apache-2.0 §4 exige la copie de la licence et la rétention des attributions NOTICE pour la redistribution ; MIT exige licence + copyright. Tant que l'APK n'est pas distribué publiquement l'exposition est faible, mais le dépôt est public et le standard que le projet s'est fixé (« chaque entrée vérifiée à la date d'ajout ») est rompu. **À vérifier aussi** : les textes de licence sont-ils fusionnés dans le jar (tâche shadow dédiée) ? Rien ne l'indique dans `build.gradle.kts`.

Sévérité moindre que le lot G du LSP Java (GPL-3.0 — incompatibilité) : ici licences permissives, il s'agit d'**attribution manquante**, pas de compatibilité. Mais c'est le même réflexe de conformité à instaurer.

### TP3 (M, doc) — `docs/TOOLING.md` décrit un câble qui n'existe plus

Le document de référence est figé à l'ère v0.51.0 (dernières mentions : ADR 0080/0081/0082 dans la table chaos) alors que le câble est à **v9** :

| Affirmation du doc | Réalité au code (v0.124.7) |
|---|---|
| « Protocole client ↔ orchestrateur : **v4** (v0.38.0) » (table des versions) | `PROTOCOL_VERSION = 9` (v7 : scripts+dépendances+variantes ADR 0096 ; v8 : classpath Android AGP v2 ADR 0113 ; v9 : fermeture de projet ADR 0114/CYCLE_DE_VIE §7) |
| « 24 fichiers dorés » (G1) puis « 28 » (v5/v6) | **38** dorés dans `tooling/protocol/src/test/resources/golden/` |
| « kover ≥ 80 % sur les six modules à seuil » (§CI) | `tooling:server` a un seuil de **75 %** ; et les modules JVM n'entrent dans la CI que par l'effet de bord `koverVerify` (TP8) |
| Table §7.5 : « Client — synchronisation : 5 min » | Le code applique **90 s d'inactivité réarmables** (`DELAI_INACTIVITE_SYNC_MS = 90_000`, `GradleApiImpl` L1516) — la v4 l'a remplacé, la table ne l'a jamais dit ; seule la section v4 corrige |
| Étapes G1→G12 (table §9) | 83 versions ont suivi ; l'évolution tooling a vécu dans CHANGELOG + ADR 0096/0103/0113/0114 + la KDoc du protocole — la « référence » n'est plus la référence |
| Jar « 7,6 Mo » | Plausible mais non re-mesuré depuis l'ajout de builder-model (v0.92.0) et l'applog (v0.83.0) |

Un nouvel arrivant (humain ou agent) qui lit `TOOLING.md` pour comprendre le câble apprend un protocole vieux de 5 versions et un périmètre de messages incomplet. C'est exactement le type d'écart doc↔code qui a nourri les trois rapports précédents — ici c'est le plus grand écart relatif des quatre briques (doc 5 versions derrière).

### TP4 (M, honnêteté) — `ServerVersion.CURRENT = "0.50.0"` : diagnostic mensonger

`ServerVersion.kt` : `CURRENT = "0.50.0"`, avec une KDoc qui dit « Alignée **manuellement** sur la version de livraison de chaque étape (ADR 0040) ». L'app est à 0.124.7 ; l'orchestrateur a changé au moins 5 fois depuis 0.50.0 (v7/v8/v9 du protocole, applog, fermeture de projet, correctif ProcessHandle). La version voyage dans `HelloRequest.clientVersion` et dans la ligne de journal `orchestrateur 0.50.0 — socket …` : **tout diagnostic affichera une version fausse**, y compris quand un utilisateur rapportera un problème avec un jar v8 ou v9. Même pathologie que `version.properties` 0.113.0 du LSP Kotlin (KP4/K5) — la correction est la même et tout aussi facile : injecter `VERSION_NAME` à la construction du fat jar (filtrage de ressource), éliminer la constante.

### TP5 (M, fonctionnel) — L'écran de configuration §7 enrichi : différé depuis 85 versions

Livrables promis au prompt §7 et explicitement reportés en v0.39.0/v0.40.0 (CHANGELOG L5103-5112) : **commande effective** (concat des arguments appliqués), **recherche** dans les tâches du feuille, **détection de conflits** d'arguments, disposition 2 colonnes. À v0.124.7 : toujours absents (vérifié — aucun `commandeEffective` nulle part ; `OptionsTooling` n'expose que `--offline` + arguments libres). La config actuelle (104 l. de ViewModel + 165 l. de fragment) reste le minimum viable de v0.36.0. Entre-temps Android Studio (référence revendiquée par le projet) affiche la commande effective et la résolution JDK par projet. C'est le plus vieil item différé vivant du projet côté tooling.

### TP6 (M, tests) — Zéro test sur les deux handlers P4/P5

Aucun des 210 tests n'exerce `ResolvedDependenciesHandler` ni `BuildVariantsHandler` (vérifié : aucune occurrence dans `tooling/server/src/test/`). Les 22 tests d'intégration couvrent handshake, build, annulation, tâches, sync, cache, classpath JVM+Android, dépendances plates, fermeture, ping, heap — **pas les requêtes v7 du tiroir Projet** (hors P1 scripts). Corollaire de TP1 : du code mort ne se teste pas — mais si on le branche (S1), il partira de zéro.

### TP7 (M, fonctionnel) — `ResolvedDependenciesHandler` : la `configuration` est échoée, jamais filtrée

Le handler accepte un paramètre `configuration` (`debugRuntimeClasspath`…), le recopie dans chaque nœud — et retourne **la même liste quelle que soit la configuration demandée** (`module.dependencies.all` d'`IdeaProject` ne connaît pas les configurations ; le KDoc de la classe documente les autres limites : `versionDemandee == versionRetenue` sans `dependencyInsight`, `transitives = emptyList()` car `IdeaModule.dependencies` ne liste que les directes, `raison` vide). L'écart avec l'ADR 0096 (« arbre **avec transitives**… versions demandée vs retenue (conflits) ») est donc total sur ces trois promesses. La voie honnête existe pourtant déjà dans le serveur : `ModelesAndroidV2` résout **`VariantDependencies` par variante** (c'est-à-dire par configuration réelle AGP) depuis la v8 — les dépendances runtime de `debugRuntimeClasspath` y sont déjà exposées pour le classpath.

### TP8 (M, CI) — La couverture des modules JVM repose sur un effet de bord, et `tooling:testing` y échappe

La CI (ADR 0037, workflow L78) exécute `spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify assembleDebug`. Vérifié par dry-run :

- `testDebugUnitTest` ne déclenche que `:tooling:client:` et `:tooling:daemon:` (modules Android).
- Les 145 tests JVM (`server` 115, `protocol` 19, `api` 8) n'entrent dans la chaîne **que parce que `koverVerify`** (racine) tire leurs tâches `test` au passage — un effet de bord de la présence d'une règle kover, pas une décision.
- **`tooling:testing:test` (3 tests) n'est déclenché par RIEN** : module JVM sans règle kover → pas de `koverVerify` → ni `testDebugUnitTest` ni aucune autre tâche de la chaîne ne l'atteint. C'est la preuve vivante de la fragilité : un module JVM qui perd sa règle kover **sort de la CI en silence**.

### TP9 (m, discipline protocole) — Nouveaux types sans bump : la règle posée par la v7 a été rompue par la v7 elle-même

La KDoc de `PROTOCOL_VERSION` (v7) pose le principe : « Un nouveau TYPE d'événement n'est PAS décodable par un client antérieur (le discriminant `type` échoue avant `ignoreUnknownKeys`) » — d'où le bump. Or `7ff53ce` (« P4+P5 protocole — Dépendances résolues + Variantes ») a ajouté 6 types **sans toucher à la version** (vérifié : `PROTOCOL_VERSION=7` avant et après ; l'ADR 0096 l'assume en regroupant P1/P4/P5 sous « v7 »). Sans effet aujourd'hui (les deux bouts vivent dans le même APK, le bump v8 est venu 4 jours plus tard d'une autre mission), mais la discipline « nouveau type → bump » devient inapplicable si les versions sont posées par « regroupement logique ». Trancher : soit assumer « bump = lot fonctionnel » (et le documenter dans la KDoc), soit bump strict par type.

### TP10 (m, fonctionnel) — Les daemons Gradle ne sont jamais éteints volontairement

`GradleConnectorPool.fermer()` (v9, fermeture de projet) libère la `ProjectConnection` et purge le `CacheSync` — mais **aucun code n'arrête les daemons Gradle** (et la Tooling API n'offre rien : c'est l'objet de gradle/gradle#8219, ouvert depuis 2019 — `stopDaemons()` réclamé par les IDE). Sur l'appareil, un daemon consomme son heap (par défaut, plusieurs centaines de Mo) jusqu'à l'expiration d'inactivité (~3 h — l'heuristique du RAPPORT.md §5.1 le confirme). La doc CYCLE_DE_VIE reporte la politique de durée de vie « aux mesures §4.5/S6 » — mais aucune mesure appareil n'existe (cf. TP13). Un contournement propre existe : lancer une build vide `--stop`-sémantique impossible en TAPI, mais `gradle help` via `withArguments("--stop")` sur un `executeBuild` jetable fonctionne (c'est ce que font certains clients TAPI).

### TP11 (m, doc) — Scénarios de la phase 0 jamais rattrapés

Le RAPPORT.md liste 6 « points non traités » : **app Android mono-module** (le cas d'usage N°1 du produit !), **Kotlin Multiplatform**, **version Gradle modifiée** (état de cache e), **configuration cache activé**, **toolchain JDK à provisionner** (foojay), reproduction visuelle du bug circulaire. Vérifié : aucune occurrence de `configuration-cache`, `foojay` ou fixture KMP dans les tests du serveur — **aucun n'a été exécuté depuis** (octobre 2026). La matrice d'états de cache reste 5/7. La promesse « À exécuter dans une session avec ANDROID_HOME complet » est tenable : le composite LayoutLib (projet Android réel, 26 modules) est disponible dans l'écosystème comme fixture.

### TP12 (m, fonctionnel) — P5 incohérent avec la v8 : la justification du stub est devenue fausse

La KDoc de `BuildVariantsHandler` (et l'ADR 0096) justifie la réponse vide par « le modèle exact `AndroidProject` d'AGP n'est PAS dans le classpath du serveur (dépendance facultative qui alourdirait le JAR de ~10 Mo) ». **C'est faux depuis la v8** (ADR 0113) : `com.android.tools:builder-model` (~300 Ko) est dans le classpath et `ModelesAndroidV2.kt` fait exactement ce que le handler prétend impossible — `AndroidProject.basic.variants` par module, `choisirVariante()` (« debug » sinon première), `VariantDependencies` par variante. Le branchement P5 est à portée de main : mêmes types, même pool de connexions, même bus. Voir §8.

### TP13 (m, mesures) — Aucune mesure appareil de la chaîne

Les ADR 0080/0081 (santé ingelable, fil dédié) documentent des pathologies observées **en production** (retour terrain v0.47-v0.50) — mais il n'existe aucune mesure post-v0.105 de : latence push→écran (les « sondes de latence console » de 9aa1e86 existent-elles encore ?), mémoire de l'orchestrateur `-Xmx256m` face à `IdeaProject` + `ModelesAndroidV2` sur le composite (26 modules), nombre de relances daemon réelles, pression mémoire des daemons Gradle cumulés (TP10). Le prompt maître §11.2 (mesures appareil) est cité par le rapport Java (I12) : même lacune ici.

### TP14 (m, architecture) — La « frontière unique » de G2 s'est diluée

`tooling:api` (217 l., gelé depuis 09-29) devait être « modèles partagés + mappers protocol → api, frontière unique » (G2/ADR 0040). En pratique, les DTO câble (`Messages.kt`, 1 000+ l.) sont devenus LE modèle partagé : `ClasspathModule` et ses 10 champs de stats vivent dans le protocole ; le client convertit via `ConversionsDomaine.kt`, le serveur via `ConversionClasspath.kt`. Le module api ne porte plus que build/diagnostic/tâches/état. Pas de bug — mais la frontière revendiquée dans la doc n'est plus celle du code, et `Messages.kt` concentre à lui seul tout le contrat (les dorés le compensent).

### TP15 (m, doc) — Interaction terminal ↔ tooling via `GRADLE_USER_HOME` non documentée

Le process orchestrateur reçoit un environnement canonique (`baseEnvironment()`: `HOME`, `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`… — couvert par `LanceurProcessusNatifsTest`), et l'avertissement bénin du daemon est correctement expliqué par ce biais. Mais `TOOLING.md` ne dit pas un mot du **partage du `GRADLE_USER_HOME` avec le terminal Termux et le bootstrap** (ADR 0083 `codeide-env`) : deux clients du même cache de dépendances et des mêmes daemons (un `gradle` tapé au terminal et la sync de l'app se battent pour le même daemon avec des JVM args potentiellement différents → daemons multiples). Comportement plausible, mais invisible dans la doc de référence.

### TP16 (m, UI) — Le deferred « téléchargements dans la vue Build » est résolu, mais l'annonce n'a jamais été faite

Le CHANGELOG v0.40.0 disait le canal `observeTelechargementsBuild` « pas encore consommé — inchangé depuis 0.39.0 ». C'est **résolu** depuis v0.104.0 : `PompeBuildTooling.kt` (282 l., ADR 0114) collecte le canal et `GradleService` agrège octets/compteur (L828-835). Aucune entrée de CHANGELOG ne l'annonce comme la clôture du deferred — le suivi de dette s'est évanoui en route. (Bonne nouvelle à créditer, mais le **registre des différés n'existe pas** : même cause que TP3/TP5/TP11.)

### TP17 (m, ROADMAP) — « Aucun problème connu » contredit cinq différés vivants

`docs/ROADMAP.md` (« État actuel v0.45.0 », dernière mise à jour 2026-10-01 — 80 versions de retard) affiche « Problèmes connus : (aucun ouvert) » alors que vivent au moins : config §7 enrichie (TP5), P4/P5 fantômes (TP1), scénarios phase 0 (TP11), notices fat jar (TP2), mesures appareil (TP13). Le ROADMAP n'est plus maintenu depuis la phase Wizard — la seule « source de vérité » restante pour l'état d'ensemble est le CHANGELOG, qui ne suit pas les différés (TP16).

### TP18 (m, robustesse) — Correctifs v0.99.1/v0.100.1 : la fragilité appareil est récente et encore chaude

`454f44f` (v0.99.1) : « déploiement du JAR orchestrateur observable et retentable » ; `59230bf` (v0.100.1) : « retour d'appareil, crash `ProcessHandle` ART + course JarDeployer + fuite de session ». Deux correctifs d'un mois d'âge sur le chemin critique de démarrage (déploiement → lancement → handshake), découverts **sur appareil réel** alors que la CI est verte. Signal positif (le retour d'appareil existe et corrige), mais aussi signal que la classe des bugs de la brique vit hors des 210 tests JVM — le soak/banc appareil (TP13) est le seul filet possible.

---
## 6. Analyse approfondie — l'anatomie du fantôme P4/P5 (leçons)

Le cas mérite sa section : c'est un **modèle d'échec silencieux** différent de ceux des rapports précédents (faux positifs du LSP XML, baseline rouge du LSP Kotlin). Ici, aucune erreur ne remonte nulle part — la chaîne est **verte à chaque niveau** :

```
ADR 0096 promet 6 messages ──✅ protocole (types + dorés + round-trip VERTS)
                          ──✅ serveur  (handler P4 implémenté, handler P5 vide documenté « stub »)
                          ──✅ client   (branches de routage compilées — inatteignables)
                          ──✅ CI       (koverVerify vert : le code est couvert… par rien)
                          ──✅ UI       (onglet « Variantes » affiché, vide « à venir »)
                       ──❌ PERSONNE n'envoie jamais les requêtes
```

Chaque maillon a fait « son job local » : l'ADR documente des stubs, le protocole fige le format, le serveur gère l'arrivée, le routeur complète les promesses, l'UI affiche un onglet. **Personne n'a vérifié la chaîne de bout en bout** parce qu'aucun test ni aucun artefact ne l'exigeait. Trois leçons structurelles :

1. **Le sealed sans test d'exhaustivité est une promesse sans gardien.** `ToolingRequest` est une `sealed interface` — le compilateur connaît TOUS les sous-types. Un test `when (message) { is BuildRequest -> émetteur = …; is ResolvedDependenciesRequest -> émetteur = … }` serait **non compilable si on oublie un cas**, et échouerait si un émetteur est `null`. Le fantôme P4/P5 serait mort à la première `./gradlew :tooling:client:test`. C'est le garde-fou n°1 du plan (§12.2).
2. **Les stubs ADR sans échéance pourrissent.** L'ADR 0096 écrit « livrable P6+ » — mais aucun P6 n'a jamais été ouvert, et le CHANGELOG v0.105+ n'en parle plus. Un stub documenté sans issue de suivi est du code mort réglementaire. Règle proposée : toute réponse vide volontaire doit porter un identifiant d'issue ouverte dans sa KDoc (le dépôt en a déjà le réflexe côté LayoutLib).
3. **Le changement de propriétaire de protocole fragmente la mémoire.** v7 (mission Projet), v8 (mission LSP Java), v9 (mission Cycle de vie) : trois missions, chacune a bumpé « sa » partie sans rattraper `docs/TOOLING.md` ni re-vérifier les différés des missions précédentes. La KDoc de `GradleProtocol` est devenue, de facto, la SEULE documentation à jour du câble — elle est excellente, mais elle n'est pas la référence déclarée du projet.

## 7. Ce qui manque face aux fonctionnalités comparables

| Capacité | Android Studio / IntelliJ | AndroidIDE | CodeIDE v0.124.7 |
|---|---|---|---|
| Sync + modèles (IdeaProject + AGP v2) | ✅ | ✅ | ✅ **v8** — y compris android.jar, sources, variantes réelles par module |
| Exécution + annulation + sortie streaming | ✅ | ✅ | ✅ (annulation prouvée ~2,6 s, `--console=plain`, stdin) |
| Arbre de dépendances résolu | ✅ (vue complète, transitives, conflits) | ✅ | ❌ **P4 fantôme** (TP1/TP7) |
| Variantes de build (sélecteur debug/release/flavors) | ✅ | ✅ | ❌ **P5 vide** alors que le serveur lit déjà `basic.variants` (TP12) |
| Réglages Gradle riches (commande effective, JDK par projet, offline, conflits) | ✅ | partiel | partiel — `--offline` + arguments libres seulement (TP5) |
| Arrêt des daemons Gradle | ✅ (outil dédié) | non documenté | ❌ (TP10, gradle#8219) |
| Journaux de l'app exécutée sans adb | ❌ (nécessite adb/Logcat) | ❌ | ✅ **unique** (v0.83, ADR 0103) |
| Isolation process (crash build ≠ crash IDE) | partiel (IDE = hôte) | ❌ (TAPI dans le process IDE) | ✅ **supérieure aux pairs on-device** (orchestrateur dédié, relance bornée) |
| Discipline « jamais d'affirmation devinée » | n/a | n/a | ✅ **unique** (phase 0 : hypothèses invalidées avant UI) |

Positionnement : sur la **plomberie**, CodeIDE est au niveau voire au-dessus de ses pairs on-device (isolation, protocole doré, chaos éprouvé, injection applog). Sur la **surface fonctionnelle du tiroir Projet**, il est derrière Android Studio ET AndroidIDE sur deux points précis (dépendances résolues, variantes) — tous deux à portée de main (§8).

## 8. Plan de stabilisation

### S0 — « Éteindre les mensonges » (~2 jours) — priorité absolue

1. **`ServerVersion` injectée au build** : remplacer la constante par une ressource filtrée depuis `VERSION_NAME` (tâche `processResources` de `tooling:server` + lecture dans `ServerMain`) ; journal et `HelloRequest.clientVersion` deviennent vrais par construction. Supprime TP4 — même correctif que K4/K5 côté Kotlin, à faire UNE fois pour toutes (le protocole embarque déjà `protocolVersion` honnête).
2. **`THIRD_PARTY_NOTICES.md` + licences dans le jar** : 5 entrées (gradle-tooling-api 9.7.1 Apache-2.0, builder-model Apache-2.0, kotlin-stdlib Apache-2.0, kotlinx-serialization-json MIT, kotlinx-coroutines MIT) + fusion des `LICENSE`/`NOTICE` dans le fat jar via `shadowJar` (`META-INF/licenses/…`) + test de présence (unzip + assertions). Clôture TP2 — obligation légale pour toute distribution de l'APK.
3. **Rattrapage de `docs/TOOLING.md`** : table des versions → v9 avec le résumé v7/v8/v9 (la KDoc de `GradleProtocol` fournit la matière), 38 dorés, seuil serveur 75 % assumé ou relevé, table §7.5 corrigée (90 s d'inactivité), section « gel et missions transverses » (v0.42-v0.105), paragraphe `GRADLE_USER_HOME` partagé (TP15). Clôture TP3/TP15.
4. **Décision P4/P5 enregistrée** : ouvrir UNE issue « tiroir Projet : brancher ou retirer P4/P5 » ; à défaut de branche immédiate (S1), retirer l'onglet VARIANTES de l'UI (une constante d'énumération) et marquer les branches routeurs `@Deprecated("P4/P5 jamais émis — issue #N")`. Clôture administrative de TP1 en attendant S1.
5. **CI : nommer les tests au lieu de les déléguer à kover** — ajouter `:tooling:protocol:test :tooling:api:test :tooling:server:test :tooling:testing:test` (ou une tâche lifecycle `testToolingJvm`) à la ligne CI ; relève le seuil serveur à 80 % si la couverture réelle le permet (elle passe `koverVerify` à 75 ; mesurer d'abord). Clôture TP8.

### S1 — « Éteindre les fantômes » (~5 jours)

1. **P5 variantes branché sur `ModelesAndroidV2`** (le served a déjà tout) : `BuildVariantsHandler` → `pool.connexion(dossier).model(AndroidProject::class.java)` (non paramétré) → `basic.variants` par module (+ build types/flavors si exposés par builder-model v2) → `BuildVariants` réel ; client : `suspend fun variantes(projectDir)` ; port : `variantes()` ; UI : l'onglet existe déjà. Tests : 1 intégration (fixture multi-modules Android — le composite LayoutLib en est un excellent candidat) + 2 unitaires.
2. **P4 dépendances résolues par `VariantDependencies`** (remplace le handler IdeaProject menteur) : la v8 résout déjà `VariantDependencies` par variante — `runtimeDependencies()` de la variante debug ≈ `debugRuntimeClasspath` ; transitives : builder-model v2 expose l'arbre `Graph` (Dependency graph) — à vérifier par `javap` (règle 9 du projet : vérifier l'API AVANT le code). À défaut : documenter « directes seulement » dans le protocole (champ `transitives` retiré au prochain bump v10 — cf. TP9).
3. **Test d'exhaustivité des émetteurs** (§12.2) — le garde-fou qui rend la classe P4/P5 impossible à terme.
4. **Commande effective dans l'écran de config** (le plus petit des différés §7) : une ligne « `--offline --console=plain <libres>` » concaténée sous le champ arguments — coupe le plus vieil item différé (TP5) en deux.
5. **KDoc `BuildVariantsHandler` corrigée** (TP12) dans le même commit que le branchement.

### S2 — « Preuves terrain » (~4 jours)

1. Les 6 scénarios de la phase 0 (TP11) : app Android mono-module (fixture : composite LayoutLib), KMP, version Gradle modifiée (état e), configuration-cache, toolchain foojay, reproduction visuelle circulaire. Chaque scénario exécuté → mise à jour du RAPPORT.md (c'est son protocole).
2. **Banc appareil** (TP13/TP18) : latence push→écran, RSS orchestrateur sur composite, daemons cumulés (TP10), relances observées — dans TESTS_MANUELS.md, exécuté à chaque version mineure.
3. **Soak 2 sessions** : deux projets ouverts alternativement (le pool gère N connexions — jamais testé au-delà de l'auto dans les tests d'intégration).

### S3 — « Confort Android Studio » (opportunité, non bloquant)

Arrêt volontaire des daemons (fenêtre de réglages : build jetable `help --stop`-sémantique à travers le pool — surveiller gradle#8219), consommation du `sync-state.json` pour re-scan watched-files (jointure avec XP3 du rapport XML — l'index de ressources LSP se périme entre syncs pour la même raison racine), export/import `GRADLE_USER_HOME`, réglages JDK par projet.

## 9. Comparaison avec les références externes (vérifiée 2026-10-14)

- **Documentation officielle Tooling API** (docs.gradle.org, « Tooling API ») : « The Tooling API always uses the Gradle daemon » — la persistance du daemon est le comportement attendu du client TAPI ; l'arrêt n'y est PAS couvert (d'où TP10/gradle#8219). L'alignement CodeIDE (API 9.7.1 == wrapper 9.7.1, résolu via repo.gradle.org) est la pratique canonique — le dépôt de référence est bien celui de Gradle, comme documenté.
- **gradle/gradle#8219** (« Add stopDaemons() to GradleConnector », 2019, ouvert) : la lacune d'arrêt n'est pas une omission de CodeIDE — c'est une limite connue de l'écosystème ; les clients contournent par des builds techniques. Valide l'approche S3.
- **AndroidIDE 2.7** (docs.androidide.com) : approche on-device concurrente — IDE hôte consommant la TAPI **dans son propre process** (pas d'orchestrateur dédié), environnement Termux/rootfs, daemon persistant documenté (« Gradle Daemon is not killed by the system »). CodeIDE est **structurellement plus isolé** (un crash de build ne tue pas l'IDE, l'orchestrateur est relançable borné) au prix d'un process JVM supplémentaire (256 Mo) — sur un appareil 4-8 Go, c'est le bon arbitrage.
- **CodeOnTheGo** (appdevforall, ARCHITECTURE.md) : même famille (Termux embarqué + Gradle on-device), moins abouti côté protocole. **CodeAssist** (étudié dans les rapports LSP) : intégration Gradle plus simple, pas de protocole versionné dédié.
- **Android Studio/IntelliJ** : reste LA référence fonctionnelle (vue dépendances, sélecteur de variantes, réglages riches, arrêt daemons) — l'écart CodeIDE se réduit à TP1/TP5/TP10, tous bornés.

**Lecture d'ensemble** : aucune des références externes ne remet en cause l'architecture de CodeIDE (inversion socket, orchestrateur dédié, protocole doré) ; toutes confirment que les manques sont des manques de **fin du travail**, pas de conception.

## 10. Ce qu'il faut préserver absolument

Toute stabilisation ne doit pas casser les propriétés suivantes (elles sont le vrai capital de la brique) : l'égalité EXACTE au handshake (elle a converti trois migrations v7→v8→v9 en refus propres plutôt qu'en décodages hasardeux) ; les 38 dorés (toute évolution de champ est un diff visibilisé) ; les intégrations RÉELLES sur fixtures (22 tests — ne jamais les remplacer par des mocks TAPI) ; le chaos `kill -9` (la seule preuve « zéro orphelin ») ; l'honnêteté des phases v4+ (le jour où une phase « devinée » revient, le RAPPORT.md de la phase 0 perd son sens) ; le JAR artefact de build jamais versionné (ADR 0040) ; et le réflexe « vérifier l'API par javap avant le code » (règle 9 — elle a déjà évité 4 bugs au G9).

## 11. Synthèse des constats

| # | Sévérité | Constat | Preuve principale | Cure |
|---|---|---|---|---|
| TP1 | C | P4/P5 messages fantômes (aucun émetteur, onglet vide, branches inatteignables) | grep exhaustif : seuls `EchantillonsMessages.kt` construisent les requêtes | S1 (+test exhaustivité) |
| TP2 | C | Fat jar : 5 dépendances redistribuées absentes des notices tierces | THIRD_PARTY_NOTICES (36 l., vérif. 2026-09-23 < G2) | S0.2 |
| TP3 | M | TOOLING.md décrit v4/28 dorés/80 %/5 min — le câble est v9/38/75 %/90 s | diff doc↔code §5 | S0.3 |
| TP4 | M | ServerVersion 0.50.0 codée dur (74 versions) | ServerVersion.kt L18 | S0.1 |
| TP5 | M | Config §7 enrichie différée depuis v0.39.0 | CHANGELOG L5103 + grep vide | S1.4 |
| TP6 | M | Zéro test P4/P5 | aucune occurrence dans src/test | S1 |
| TP7 | M | `configuration` échoée sans filtrage (P4) | ResolvedDependenciesHandler L62-68 | S1.2 |
| TP8 | M | CI : modules JVM couverts par effet de bord kover ; `tooling:testing` hors CI | dry-runs `testDebugUnitTest`/`koverVerify` | S0.5 |
| TP9 | m | Types ajoutés sans bump (7ff53ce) | `git log -S` PROTOCOL_VERSION | trancher la règle |
| TP10 | m | Daemons jamais éteints volontairement | GradleConnectorPool + gradle#8219 | S3 |
| TP11 | m | 6 scénarios phase 0 jamais exécutés (dont app Android mono-module) | RAPPORT.md §7 + grep vide | S2.1 |
| TP12 | m | Justification du stub P5 fausse depuis la v8 | ModelesAndroidV2 L70 vs BuildVariantsHandler KDoc | S1.1 |
| TP13 | m | Aucune mesure appareil de la chaîne | absence de banc | S2.2 |
| TP14 | m | Frontière « tooling:api » diluée dans le protocole | 217 l. api vs Messages.kt 1000+ l. | documenter |
| TP15 | m | `GRADLE_USER_HOME` partagé terminal/tooling non documenté | TOOLING.md muet | S0.3 |
| TP16 | m | Deferred « téléchargements vue Build » résolu (v0.104) mais jamais annoncé | PompeBuildTooling + CHANGELOG | registre des différés |
| TP17 | m | ROADMAP « aucun problème connu » vs 5 différés vivants | ROADMAP.md L20-21 (maj v0.45.0) | registre des différés |
| TP18 | m | Fragilité appareil récente (v0.99.1/v0.100.1) encore chaude | 2 correctifs retour d'appareil en 1 mois | S2.2 |

## 12. Garde-fous proposés

1. **Registre des différés** (TP5/TP11/TP16/TP17) : une section « différés vivants » dans TOOLING.md, maintenue à chaque version — chaque différé porte sa version d'origine et son issue. Les différés invisibles pourrissent ; visibles, ils se priorisent.
2. **Test d'exhaustivité des émetteurs** (anti-fantôme, compile-time) : `ToolingRequest` étant sealed, un test `when` exhaustif dans `tooling:client` mappe chaque type à sa fonction émettrice (`BuildRequest→build()`, …, `ResolvedDependenciesRequest→dependancesResolues()`, `BuildVariantsRequest→variantes()`) et échoue si l'émetteur est absent — l'oubli d'un nouveau message devient une erreur de compilation du test. Miroir côté serveur pour les `ToolingEvent`.
3. **CI** : tâche lifecycle `testToolingJvm` (protocol+api+server+testing) nommée explicitement dans le workflow ; `koverVerify` relève le serveur à 80 % après mesure ; un test d'intégration `unzip` du fat jar asserte la présence des `LICENSE` (TP2).
4. **Honnêteté de version outillée** : test `ServerVersion.CURRENT == VERSION_NAME` (jusqu'à l'injection au build de S0.1, qui le rend structurel).
5. **Chaque futur bump de protocole** (v10+) doit arriver avec : la mise à jour de la table des versions de TOOLING.md + la régénération des dorés dans le MÊME commit (le réflexe existe : `REGENERER_DORES=1`) + l'entrée aux notices si une nouvelle dépendance entre dans le jar.

## 13. Conclusion

La chaîne Tooling Gradle est le meilleur travail d'ingénierie du dépôt : inversion de responsabilité socket exemplaire, protocole versionné et doré, intégrations et chaos réels, honnêteté des phases construite sur des scénarios qui invalident leurs propres hypothèses, injection de journaux sans adb unique dans sa catégorie. Ses problèmes ne sont PAS des problèmes de conception — ce sont des **fin de chantier non tenues** : une mission Projet interrompue au milieu du gué (P4/P5 fantômes), une documentation de référence fivevoyante (v4 contre v9), une version d'orchestrateur mensongère, des notices de distribution datant d'avant la première distribution, et une CI qui teste la moitié des modules JVM par effet de bord. Le plan S0 (2 jours) efface tous les mensonges ; S1 (5 jours) éteint les fantômes en réutilisant ce que la mission LSP Java a déjà construit (ModelesAndroidV2) ; S2 (4 jours) rend les preuves terrain enfin mesurées. Après quoi la brique ne demandera plus qu'à être préservée — les garde-fous §12 y suffisent.

---
*Analyse menée en lecture seule stricte sur le dépôt CodeIDE (commit bd6f458, v0.124.7) — aucun fichier du dépôt n'a été modifié. Rapport rédigé hors du dépôt, à la demande du propriétaire. Suite logique des rapports LSP Java (Task 10), LSP Kotlin (Task 11) et LSP XML (Task 12).*
