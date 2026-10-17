# Rapport d'analyse — LSP Kotlin de CodeIDE

> **Mission** : analyser le LSP Kotlin de CodeIDE (document de référence
> `docs/LSP_KOTLIN.md`, v0.109.0) — gaps, incohérences, pistes
> d'amélioration, comparaison avec les LSP Kotlin de référence — en vue de
> **stabiliser** la brique.
> **Contrainte** : CodeIDE strictement en **lecture seule** — aucun fichier
> du dépôt n'a été modifié (arbre vérifié propre après chaque run).
> **Date du run de vérification** : 2026-10-14, clone `main` = `bd6f458`
> (v0.124.7).

---

## 1. Résumé exécutif

Le LSP Kotlin de CodeIDE est la brique de langue la **plus saine du dépôt
sur le plan structurel** : mission K1–K5 entièrement livrée (v0.110.0 →
v0.116.0) et conforme aux décisions D1–D8 du cadrage ; grammaire officielle
du compilateur vendorée octet pour octet avec suite de parité ; modèle de
symboles conservateur (D3 : sous-rapporte, jamais de faux positif) ;
découplage « pack langue » respecté dès K1 ; **situation juridique propre**
(Apache 2.0 + notices — contrairement au lot G du LSP Java, aucun frein à
la publication ou à l'extraction future).

**Mais la stabilité n'est pas acquise, et deux constats de tête le prouvent**.

**1. Un incident de process actif** : sur le `main` courant (v0.124.7), la
suite de parité est **ROUGE** — la baseline du digeste structurel est en
retard de 2 fichiers sur le corpus (933 réels vs 931 épinglés, ajoutés
par v0.124.3 sans régénération). La conséquence est lourde :
`:lsp:kotlin:test` échoue, et **toute la chaîne qualité du module est à
l'arrêt** (`koverVerify` / `koverHtmlReport` ré-exécutent le test et
échouent — la couverture ≥ 80 % exigée par le dépôt ne peut plus être
démontrée). Cinq commits d'affilée (v0.124.3 → v0.124.7) sont passés
sans voir ce gate : le chemin CI des commits « éditeur » ne protège pas
le module de langue.

**2. La première rupture démontrée de la propriété sacrée D3** : une
sonde empirique (programme Java pilotant le moteur par son SPI public,
jar du build local, stdlib 2.2.10 réelle dans l'index — §4.4) montre que
`kt.unresolved` signale **`ArrayList` et `StringBuilder`** — du code
Kotlin parfaitement **valide** (types importés par défaut :
`kotlin.collections.ArrayList`, `kotlin.text.StringBuilder`). La cause
est identifiée dans le code : la carte `IMPORTS_DEFAUT` du résolveur
(~33 noms) ne couvre pas l'ensemble des imports par défaut de Kotlin, et
la chaîne de résolution n'a **aucune connaissance des paquets importés
par défaut** (types = classes, donc hors de l'index des appelables qui
ne porte que fonctions/propriétés de premier niveau). Les témoins de la
sonde valident le diagnostic : `listOf`/`println` (appelables → voie
index) résolvent, `Duration` (import réellement requis) est correctement
signalé.

Au-delà de ces deux têtes de liste, l'analyse identifie **16 constats
priorisés (KP1–KP16)** dont les plus structurants : absence de
republication des diagnostics après poussée de classpath (les
`kt.unresolved` vivent sur un état périmé jusqu'à la prochaine frappe),
descripteur de pack resté à `0.113.0` (le `serverInfo.version` LSP ment de
deux étapes — confirmé par la sonde), dépendance structurelle du LSP
Kotlin à la session Java pour la vue de classpath (pont unique de
défaillance), promesse de compliance `languageVersion` (§4.4) jamais
tenue, et un déficit fonctionnel concentré sur la navigation
(`references`, `documentSymbol`, `rename`) alors même que le modèle de
symboles existe déjà.

Le plan recommandé : **S0** (réparer l'incident parité + verrouiller le
process + corriger les imports par défaut faux positifs, ~2 jours) →
**S1** (hygiène protocolaire + les deux requêtes à fort rapport
valeur/effort : `documentSymbol` et `signatureHelp`) → **S2** (confort :
rename borné, hover KDoc — le parseur KDoc est déjà vendoré) → **S3**
(T2 compilation par pack, re-parse incrémental).

---

## 2. Périmètre, méthode et matériaux

### 2.1 Ce qui a été fait

- **Lecture intégrale** du document de référence `docs/LSP_KOTLIN.md`
  (223 l. — cadrage v0.109.0, étapes K1–K5 marquées livrées, T2 différé)
  et de l'ADR 0115 (vendoring de la grammaire).
- **Lecture du CHANGELOG** v0.110.0 → v0.124.7 pour toutes les entrées
  `lsp-kotlin` / `lsp-host` (K1–K5, v0.114.1 correctif composites,
  v0.121.1 baseline regénérée « retours CI », v0.124.3+ commits éditeur).
- **Cartographie complète du moteur** `:lsp:kotlin` : 31 fichiers main /
  8 077 lignes (paquets `api`, `parse` + façade `kt`, `classpath`,
  `index`, `resolve`, `completion`, `diagnostics`, `synthetics`,
  `session`) + 23 fichiers vendorés / 9 585 lignes (`org.jetbrains.kotlin.
  kmp.*`, gramm. v2.3.0) + 12 fichiers de test / 2 804 lignes. Lecture
  ligne à ligne des fichiers porteurs (SPI, session, diagnostics
  syntaxiques et sémantiques, complétion, résolution, candidats d'import,
  index, scan, synthétiques, récepteurs `with`).
- **Lecture du côté hôte** `:lsp:host` : `KotlinLanguageServer.kt` (584 l.
  — adaptateur LSP4J complet), `SessionLspKotlin.kt` (258 l. — façade,
  nourrissage, « 0 ressource orpheline »), `AdaptateurVueClasspath.kt`
  (228 l. — couture neutre sur l'index fusion Java).
- **Lecture du câblage éditeur** : `CoordonnateurLsp.kt` (LspProject
  multi-langues, routage par extension), points d'accroche Kotlin de
  `EditorViewModel.kt` (ouverture sous le même verrou que Java,
  registre `FermablesEspace`, nourrissage post-sync et modèle persisté).
- **Run de vérification d'état frais** (lecture seule — arbre resté
  propre) : `:lsp:kotlin:test` **118 tests, 1 échec** (détail §4) ;
  `:lsp:host:testDebugUnitTest` **72 tests, 0 échec** (dont 24
  `KotlinLanguageServerTest` + 8 `SessionLspKotlinTest`).
- **Comparaison** (recherche web du 2026-10-14) : fwcd/kotlin-language-
  server (README), JetBrains **kotlin-lsp officiel** (kotlinlang.org +
  blog JetBrains mai 2026), CodeAssist (étude intégrée au cadrage),
  Android Studio (référence UX implicite).
- **Sonde empirique** (§4.4) : programme Java hors dépôt
  (`scripts/sonde-lsp-kotlin/Sonde.java`, compilé contre le jar du module
  et les dépendances épinglées) pilotant le moteur par le SPI public sur
  la stdlib 2.2.10 réelle — preuves : faux positifs `kt.unresolved`
  (`ArrayList`, `StringBuilder`), version du pack `0.113.0` en production.

### 2.2 Limites assumées

- Aucun run **sur appareil** : les cibles non fonctionnelles §7 du cadrage
  (latence < 100 ms, premier diagnostic < 250 ms, tas plat) ne sont pas
  re-mesurées ici — le dépôt n'embarque d'ailleurs **aucune
  instrumentation** de ces cibles côté Kotlin (seul le budget de
  performance du sweep de parité couvre le parse).
- La **couverture kover fraîche est indémontrable** sur `main` : la suite
  de parité rouge bloque `koverVerify`/`koverHtmlReport` (§4.2). Les
  chiffres cités (91,7 % K1, 84,1 % moteur K4) sont ceux **documentés**
  au fil des livraisons.
- Le client cel-lsp (réception des capacités, rendu des propositions)
  n'est pas audité en profondeur : le périmètre est moteur + hôte.
- Environnement : machine 4 Go — runs séquentiels `--no-daemon` avec tas
  borné ; `JAVA_HOME` doit pointer le Temurin de
  `/home/z/my-project/jdk-21.0.12.1+1` (le JRE système casse le toolchain
  `build-logic`).

---

## 3. Architecture du LSP Kotlin (état constaté)

### 3.1 Modules et responsabilités

| Module | Rôle | Faits marquants |
|---|---|---|
| `:lsp:kotlin` | Moteur de langue pur JVM, **entité indépendante** (aucune dépendance vers un module CodeIDE, `checkModuleDependencies`) | Grammaire vendorée `src/main/vendor` (Apache 2.0, octet pour octet, ADR 0115) ; façade `Kt*` + arbre immuable tolérant ; symboles/résolution/complétion/diagnostics ; index disque des appelables `top:`/`ext:` ; synthétiques Android ; **aucune référence LSP4J** (types neutres) |
| `:lsp:host` | Adaptateur de protocole | `KotlinLanguageServer` (LSP4J : cycle, didOpen/didChange/didClose, complétion/survol/définition/codeAction, publication offsets→positions UTF-16) ; `SessionLspKotlin` (façade : une session moteur par connexion, sessions vivantes suivies et refermées exhaustivement, classpath poussé) ; `AdaptateurVueClasspath` (implémente `VueClasspathKotlin` **sur l'index fusion de la session Java**) |
| `feature/editor` | Cycle de vie | `EditorViewModel` ouvre la session Kotlin dans le **même verrou** que la session Java (dégradation honnête : moteur absent → `.kt` en coloration seule) ; `CoordonnateurLsp` crée le `LspProject` unique portant les définitions Java + Kotlin + XML (routage par extension `kt`/`kts`) ; registre `FermablesEspace` + délai partagé |

Le seul point d'entrée du moteur est le **SPI**
`jo.lspkotlin.api.MoteurLangageKotlin` derrière `ServiceLoader`
(descripteur `META-INF/services`), accompagné du descripteur de pack
`jo/lspkotlin/pack/version.properties` (id, version, version minimale
d'app, version de grammaire) — l'architecture « pack langue
téléchargeable » différée (D6) est en place au coût quasi nul, comme
prévu.

### 3.2 Flux nominal d'une session

1. Ouverture de l'espace → `ouvrirSessionLspKotlinSiPossible` (sous le
   verrou de la session Java) → `SessionLspKotlin(racine)` ; résolution
   SPI ; enregistrement au registre des fermables.
2. Branchement d'un onglet `.kt`/`.kts` → `LspProject.createEditor` →
   `connect()` (cel-lsp mène initialize/didOpen) → `serveur()` fabrique
   un `KotlinLanguageServer` avec une **session moteur neuve**, née
   nourrie du classpath courant s'il existe.
3. Chaque frappe → didChange (sync **complète**) → re-parse intégral →
   diagnostics syntaxiques + sémantiques → `publishDiagnostics` (traduction
   offsets→positions UTF-16, plages vides élargies d'un caractère).
4. Sync Gradle utile (ou réouverture depuis modèle persisté W7) →
   `appliquerClasspath`/`appliquerModele` → conversion en entrées neutres
   (jars/AAR existants, racines sources) → poussée aux sessions vivantes
   (`definirClasspath`) ; l'index des appelables décode à froid les jars
   nouveaux (segments par empreinte — un jar inchangé n'est jamais
   redécodé) ; **la vue de classpath vient de la session Java**.
5. Débranchement/fermeture → disconnect/shutdown → session moteur fermée,
   retirée des vivantes ; `close()` de la façade rattrape les orphelines.

### 3.3 Les trois régimes de service

- **Plein** : vue de classpath présente + index appelables chaud + scan
  des racines + synthétiques — complétion membre/nom/import, survol,
  définition, diagnostics sémantiques, quick-fixes d'import.
- **Dégradé projet seul** (vue `null`, avant première sync) : symboles du
  projet uniquement ; `kt.unresolved` **silencieux** (impossible de prouver
  une absence dans un index qu'on ne lit pas) — miroir du « mode
  instantané » v0.101.0 Java.
- **Coloration seule** (moteur absent du classpath) : jamais bloquant pour
  l'espace ; les `.java` gardent leur session.

### 3.4 Discipline d'exécution

Verrou interne par session (les callbacks LSP4J arrivent sur le pool de
l'hôte) ; index des appelables à **snapshot immuable publié volatile**
(redéfinitions atomiques) ; copies défensives partout ; bornes explicites
(profondeur de chaîne 6, profondeur de typage 8, 2 000 fichiers scannés,
100 propositions, 8 correctifs) ; tolérance de fin de vie (notifications
entre shutdown et exit = no-ops) ; tolérance de version de pack
(implémentations par défaut **vides** au contrat — un pack K2 face à un
hôte K3 dégrade honnêtement, jamais d'exception).

---

## 4. État de stabilité mesuré

### 4.1 Run de vérification du jour (2026-10-14, clone lecture seule)

| Suite | Tests | Échecs | Ignorés | Verdict |
|---|---|---|---|---|
| `:lsp:kotlin:test` (moteur) | 118 | **1** | 0 | **ROUGE** |
| `:lsp:host:testDebugUnitTest` (hôte) | 72 | 0 | 0 | VERT |

Répartition du moteur (au run complet) : `SessionLangageKotlinK4Test` 31,
`K5Test` 17, `K3Test` 15, `ParseTest` 16, `FacadeKtTest` 10,
`ParseurKotlinTest` 9, `LecteurClassBrutTest` 5, `IndexAppelablesKotlinTest`
4, `ToleranceErreursTest` 3, `ChargeurMoteurKotlinTest` 3,
`DecodeurMetadonneesTest` 3, `PariteCorpusDepotTest` 2.

**L'échec unique** est `PariteCorpusDepotTest` > « le digeste structurel
du corpus correspond à la baseline » :

```
expected: fichiers=931 digeste=0d5137f5…
but was : fichiers=933 digeste=d190804a…
```

Point essentiel : le test jumeau « **tout le dépôt se parse sans marqueur
d'erreur** » **PASSE** — les 933 fichiers `.kt` du dépôt (y compris les 2
nouveaux) parsent proprement. La grammaire vendorée tient ; c'est
strictement la **baseline épinglée qui a pris du retard sur la croissance
du dépôt**.

### 4.2 Cause racine exacte (établie par `git`)

- Baseline `digeste-corpus.txt` dernière fois régénérée au commit
  `3ffbd87` (v0.122.0 — PE3).
- Le commit `e300604` (v0.124.3, lot E1+E2+E3 « GestionnaireInsetsEditeur
  ») a ajouté **2 fichiers `.kt`**
  (`GestionnaireInsetsEditeur.kt` + son test).
- Aucune des versions v0.124.3 → v0.124.7 n'a régénéré la baseline (la
  régénération délibérée se fait par `-Dparite.majBase=true`, elle fait
  partie de la revue d'un re-sync — ADR 0115 §5).

**Conséquences en cascade sur `main`** :

1. `:lsp:kotlin:test` échoue → tout build exigeant les tests du module
   échoue.
2. `:lsp:kotlin:koverVerify` et `koverHtmlReport` **échouent** (ils
   ré-exécutent le test) : la garantie « kover ≥ 80 % ligne » du dépôt
   n'est plus démontrable sur l'état courant — la vérification fraîche
   demandée par ce rapport a d'ailleurs été **bloquée** par cet échec.
3. `rapportTaille` reste opérable (jar seul) : **499 675 octets (0,5 Mo)**,
   66 classes vendorées + **105 classes CodeIDE** (30 à K1 — le moteur a
   triplé, toujours ≪ cible 4 Mo).

Lecture de stabilité : l'incident n'est **pas une régression de la
grammaire ni du moteur** — c'est un **défaut de process** : le gate de
parité n'est pas sur le chemin critique des commits « éditeur ». Il
révèle aussi que la vérification d'état de la mission LSP Java (rapport
`rapport-analyse-lsp-java-codeide.md`, §4) n'avait pas inclus `:lsp:kotlin`
dans son périmètre de run — le rouge date probablement de v0.124.3
(2026-10-14, même jour) et personne ne l'a vu.

### 4.3 Ce qui manque à la démonstration de stabilité

- Une **couverture kover vérifiée** sur l'état courant (impossible tant
  que la parité est rouge — chiffres dernière mesure connue : 91,7 % K1,
  84,1 % moteur K4, post-K5 non re-mesuré).
- Des **mesures d'appareil** des cibles §7 (latence complétion, premier
  diagnostic, tas en régime établi) — aucune instrumentation dédiée.
- Un **banc de rotation multi-connexions** (le document promet « tests de
  rotation/close hérités du socle v0.107.0 » — ils existent côté hôte,
  mais l'état partagé du moteur `ScanProjet` n'est pas éprouvé en accès
  concurrent réel, cf. KP6).
- Un **test CRLF/UTF-16** des conversions offsets↔positions (aucun des
  24 tests `KotlinLanguageServerTest` ne couvre les fins de ligne
  Windows ni les paires de substitution).

### 4.4 Sonde empirique SPI (preuve sur le jar livré)

Pour trancher une hypothèse née de la lecture du code (les types
importés **par défaut** en Kotlin ne figurent pas tous dans la carte
`IMPORTS_DEFAUT` du résolveur), une sonde Java a été compilée **hors du
dépôt** (`/home/z/my-project/scripts/sonde-lsp-kotlin/Sonde.java`) contre
le jar du module (`build/libs/kotlin.jar`, celui du build courant) et les
dépendances épinglées (syntax-api 0.3.340, lang-syntax, annotations 23,
kotlin-metadata-jvm 2.2.10). Elle crée une session par le SPI public,
pousse un classpath composé de la **stdlib Kotlin 2.2.10 réelle** (index
des appelables décodé — `dossierIndex` null) avec une vue honnête mais
vide (régime « index en construction », comme avant la première sync),
puis demande les diagnostics d'un fichier témoin :

```kotlin
val a = ArrayList<String>()   // VALIDE — kotlin.collections.* importé par défaut
val s = StringBuilder()       // VALIDE — kotlin.text.* importé par défaut
val l = listOf(1)             // appelable de premier niveau (voie index)
println("ok")                 // idem
val d = Duration.ZERO         // import RÉELLEMENT requis (kotlin.time)
```

**Résultat (tel quel)** :

```
moteur=jo.codeide.lsp.kotlin v0.113.0 grammaire=v2.3.0
kt.unresolved [8-17]   Référence non résolue : « ArrayList »
kt.unresolved [36-49]  Référence non résolue : « StringBuilder »
kt.unresolved [92-100] Référence non résolue : « Duration »
```

Lecture :
- `ArrayList` et `StringBuilder` sont des **faux positifs** — le code est
  valide, D3 (« jamais de faux positif ») est **rompu** sur cette classe
  de cas. La cause est dans `ResolutionKotlin.IMPORTS_DEFAUT` (~33 noms :
  primitives, `List`/`Map`/`Set`, exceptions… mais pas `ArrayList`,
  `HashMap`, `HashSet`, `StringBuilder`, `Regex`…) : la voie 5
  (`fqcnDuNomEcrit`) échoue et la voie 6 (index des appelables) ne peut
  pas rattraper — un **type** n'est pas un appelable de premier niveau.
  Ni la vue ni le classpath réel ne changent ce verdict : le défaut est
  dans la chaîne de résolution elle-même, pas dans les données.
- `Duration` est correctement signalé — MAIS le quick-fix correspondant
  est impossible : `CandidatsImport` filtre `!fqcn.startsWith("kotlin.")`
  (KP8-bis), donc le seul candidat légitime (`kotlin.time.Duration`) est
  exclu de la palette.
- `listOf` et `println` **résolvent** — la voie index fonctionne avec la
  vraie stdlib (témoins sains de la sonde).
- La version du moteur rendue par le descripteur **en production** est
  bien `0.113.0` (KP4 confirmé vivant sur le binaire, pas seulement dans
  le source).

La sonde est rejouable à volonté (aucune écriture dans CodeIDE) et
fournit le **test de non-régression** idéal pour le correctif S0 :
après correction, le même fichier témoin ne doit plus produire que le
seul `Duration`.

---

## 5. Conformité au document de référence `LSP_KOTLIN.md`

### 5.1 Promesses tenues (vérifiées dans le code)

- **K1 (v0.110.0)** : vendoring v2.3.0 octet pour octet (VENDOR.md,
  licences copiées, `scripts/vendor-grammaire-kotlin.sh`) ; façade `Kt*` +
  `ParseurKotlin` + arbre immuable tolérant ; suite de parité (sweep
  corpus + digeste baseline + tolérance troncature/injection) ; SPI +
  descripteur de pack ; rapport de taille ; kover 91,7 %.
- **K2 (v0.112.0)** : contrat de session derrière le SPI (`DiagnosticKotlin`
  neutre — le moteur ne voit pas LSP4J) ; diagnostics syntaxiques
  conservateurs (`kt.syntax`, `kt.unusedImport`, `kt.conflictingImport`) ;
  `KotlinLanguageServer` sync complète, capacités honnêtes, publication
  traduite ; cycle de vie éditeur dans le même verrou, dégradation
  honnête.
- **K3 (v0.113.0)** : symboles + classpath + les trois requêtes —
  complétion (membres ∪ extensions, noms hiérarchisés, imports),
  survol (chaîne complète), définition (projet seul, documenté) ; vue
  neutre **partagée** avec le LSP Java (D4) ; lecteur de class-files
  maison ; décodeur `kotlin-metadata-jvm` (porte `.kotlin_module`, saut
  des jars Java à coût constant) ; index disque segments par empreinte ;
  scan paresseux des racines.
- **K4 (v0.115.0)** : smart casts positionnels (if/when/&&/garde précoce,
  `||` annule) ; inférence bornée des paramètres de lambda (consommateur
  projet, SAM, `it` implicite) ; 7 diagnostics sémantiques conservateurs ;
  quick-fixes d'import (`importsCandidats` + `codeAction`, plafond 8) ;
  correctifs de fond documentés (`classesProjet` toujours vide,
  `octetsClasse` composites `aar!classes.jar`).
- **K5 (v0.116.0)** : classes synthétiques `R`/`BuildConfig`/ViewBinding/
  DataBinding **avant tout build**, coupées par la classe réelle
  homonyme (le build est la source de vérité) ; multi-langue
  Java↔Kotlin (`SourcesJava`, marqueur `estJava`, contrôle d'arité
  exclus) ; récepteurs `with` (littéral le plus profond gagne) ;
  attentes de lambda du classpath (`setOnClickListener { v -> }`) ;
  correctif ANR (nourrissage sur `DispatcherProvider.io`, test dédié).
- **D7 (licence)** : zéro ligne GPL ; vendoring Apache 2.0 documenté dans
  `THIRD_PARTY_NOTICES.md` (lignes grammaire, syntax-api, adaptation
  NewTestParser) + ADR 0115. **Aucun équivalent du lot G Java** — la
  publication/extraction future n'est pas bloquée juridiquement.
- **D6 (pack)** : module sans dépendance interne, SPI mince, descripteur
  embarqué — découplage réel prêt à coût quasi nul.

### 5.2 Restes explicitement ouverts dans la référence (et donc attendus)

- **T2** (compilation K2 in-process via tooling) — différé, cadré pour
  `docs/TOOLING.md`, hors mission.
- **Re-parse incrémental de sous-arbre** (§4.2 : « repli parse complète »
  est le régime actuel ; « l'incrémental arrive avec le re-parse de
  sous-arbre, postérieur à K2 ») — toujours absent : chaque frappe
  re-parse le fichier entier (toléré par la mesure ~4 ms/1000 lignes).
- **Sources-jars / « décompilé »** pour la définition classpath (K4+) —
  absent ; le survol porte la FQN, la définition reste silencieuse (D3
  documenté, miroir du « jamais d'URI factice »).
- **Raffinement par module** du classpath (union sciemment consommée à
  K3, « suit les retours ») ; **racines de tests** non scannées (§
  racinesSources : principal + générés).
- **Découpage pack §6** : critères de déclenchement « deux versions sans
  correctif structurel sur `:lsp:kotlin` » — non atteints (v0.114.1 et
  v0.121.1 ont corrigé des points structurels ; v0.117+ a touché l'hôte).

### 5.3 Écarts référence ↔ code

- **KP4 — descripteur de pack périmé** : `version.properties` porte
  `version=0.113.0` alors que K4 (v0.115.0) et K5 (v0.116.0) ont livré.
  Le `serverInfo.version` renvoyé par `initialize` ment donc de deux
  étapes — pour un contrat dont la KDoc dit « le descripteur EST partie
  du contrat de pack, son absence est un bug » (son obsolescence l'est
  tout autant : c'est la base de la traçabilité du futur pack
  téléchargeable).
- **Promesse §4.3 silencieusement abandonnée** : « complétion … infix ;
  `expect`ed-type (Boolean/enum) ; mots-clés par position grammaticale »
  — aucun de ces trois traits n'est livré (grep négatif sur
  `completion/`) ; les récapitulatifs K3–K5 (§5) ne les reprennent pas
  non plus. L'écart est de **cadrage** (le §4.3 d'origine n'a jamais été
  révisé), pas de livraison — mais la référence n'est plus fidèle à
  elle-même.
- **Promesse §4.4 orpheline — compliance par projet** : « la compliance
  par projet (décision ouverte ② de LSP_JAVA §17.3) est reportée au
  protocole v10 de la mission Kotlin : champ `languageVersion` du module
  dans le `ClasspathModule` ». La mission est déclarée terminée (K5 ✅)
  et le champ `languageVersion` **n'existe nulle part** (`ClasspathKotlin`
  ne le porte pas). C'est la seule promesse de cadrage « reportée à la
  mission Kotlin » qui n'a été ni livrée ni explicitement renoncée —
  d'autant plus sensible que la grammaire vendorée est figée v2.3.0 :
  le moteur **parse** le Kotlin 2.2 (celui de l'outillage) mais un
  projet employant la syntaxe 2.4+ (ex. context receivers stabilisées,
  nouveaux modificateurs) produira des `kt.syntax` **faux positifs**
  sans aucun moyen de distinguer « niveau de langue du projet » et «
  erreur de l'utilisateur ». Le LSP Java avait le même trou (compliance
  figée « 17 ») — chez Kotlin il est double : grammaire figée ET champ
  promis absent.

---

## 6. Audit protocolaire LSP 3.17

### 6.1 Capacités : déclaré vs implémenté vs conforme

| Capacité | Déclarée | Servie | Conforme |
|---|---|---|---|
| `textDocumentSync` (Full) | oui | oui (didChange intégral, plages tolérées, versions obsolètes ignorées) | oui |
| `completionProvider` (trigger `.`) | oui | oui (membre/nom/import, sortText total, additionalTextEdits auto-import) | oui — trigger `.` seul (KP12) |
| `hoverProvider` | oui | oui (texte simple) | forme `MarkedString` dépréciée (KP14) |
| `definitionProvider` | oui | oui (Location, cibles projet, lecture disque bornée) | oui |
| `codeActionProvider` | oui | oui (un CodeAction par FQN candidate sur `kt.unresolved`, plafond 8) | oui |
| `publishDiagnostics` (push) | — | oui (avec version, vidange au didClose, client fautif toléré) | oui |

Contrairement au LSP Java (négociation de capacités inopérante,
`ServerCapabilitiesProvider`), le serveur Kotlin déclare **statiquement**
des capacités toutes réellement servies : le «honnête» du cadrage est
respecté à la lettre. Aucune méthode non déclarée n'est servie.

### 6.2 Méthodes LSP 3.17 non implémentées, avec verdict d'utilité CodeIDE

| Méthode | Verdict CodeIDE | Commentaire |
|---|---|---|
| `textDocument/documentSymbol` | **HAUTE — à faire tôt** | Le modèle existe déjà (`SymbolesProjet.declarations`, conteneurs, genres) ; c'est l'entrée d'une future outline/outline-bar on-device ; coût de dérivation faible |
| `textDocument/signatureHelp` | **HAUTE — à faire tôt** | Les paramètres (noms + types écrits) sont déjà extraits pour `kt.argumentCount` ; les attentes de lambda K4/K5 fournissent le contexte |
| `textDocument/references` | MOYENNE | Demande un index inverse des références — réutilisable pour `documentHighlight` ; gros gain de navigation |
| `textDocument/prepareRename` + `rename` | MOYENNE | Miroir du « rename honnête » recommandé côté Java : borné aux locales/membres projet dont la déclaration est prouvée, refus explicite sinon |
| `textDocument/documentHighlight` | MOYENNE | Même index que `references` ; affordance forte en éditeur tactile |
| `textDocument/semanticTokens` | MOYENNE-BASSE | Remplacerait/prolongerait la coloration lexicale ; coût réel (classification par jeton) — après documentSymbol/signatureHelp |
| `textDocument/foldingRange` | BASSE | Confort ; dérivable de l'arbre immuable |
| `textDocument/formatting` / `rangeFormatting` | BASSE (renvoyer au tooling) | Le vrai formatage Kotlin exige ktlint/compilateur — T2 ; un « formatage » éditeur sans compilateur serait un mensonge D3 |
| `textDocument/inlayHint` | BASSE | Types inférés existent (`TypageExpression`) mais valeur tactile limitée |
| `workspace/symbol` | BASSE | Mono-projet on-device ; le scan existe déjà, dérivation triviale si demandé |
| `selectionRange`, `codeLens`, `callHierarchy`, `typeHierarchy`, `linkedEditingRange`, `willSave`/`willSaveWaitUntil`, `completionItem/resolve` | NULLE-BASSE | Pas de consommateur identifié côté éditeur cel actuel |

### 6.3 Incohérences protocolaires et de convention (détail)

1. **KP2 — pas de republication après `definirClasspath`** : les
   diagnostics sémantiques (`kt.unresolved` en tête) dépendent de l'état
   de classpath, mais `SessionLspKotlin.appliquerModele` pousse l'état
   aux sessions **sans aucun signal vers les `KotlinLanguageServer`**
   (les documents LSP vivent dans le serveur, pas dans la façade). Entre
   l'arrivée du classpath et la prochaine frappe du fichier ouvert, les
   squiggles affichés sont ceux de l'ancien régime — y compris « rien »
   alors que le régime plein vient d'arriver. Le même trou existe en
   sens inverse (classpath remplacé par un qui ne porte plus un symbole).
   Miroir exact du constat « didSave sans republication » du LSP Java,
   mais avec un déclencheur en plus (le classpath).
2. **KP9 — `didSave` no-op** (« les diagnostics vivent au didChange ») :
   pas de re-pull (`includeText`), pas de republication — un fichier
   corrigé **hors éditeur** (build, outil externe) ne re-diagnostique
   jamais tant qu'une frappe ne survient pas.
3. **KP10 — `didChangeWatchedFiles` voie muette** : notification acceptée
   sans effet, et **aucun watcher côté hôte** ne l'émet de toute façon.
   Conséquence mesurable : un fichier `.kt` **créé** dans une racine
   source après le scan initial est invisible (complétion, définition,
   résolution) jusqu'au prochain changement de racines (sync) — le scan
   `ScanProjet.declarations()` ne re-parcourt jamais sinon.
4. **KP11 — `CompletionList(isIncomplete = false)` avec cap 100** : la
   troncature à `LIMITE_PROPOSITIONS` est silencieuse ; un client honnête
   ne re-interrogera pas. Quand la liste est tronquée, `isIncomplete =
   true` est le comportement spécifié.
5. **KP12 — triggerCharacters `["."]` seul** : pas de `?` (après `?.` en
   cours de frappe), pas d'espace (mots-clés après un blanc). Selon le
   client cel, des re-trigger manqués = complétion membre qui n'apparaît
   pas sans re-frappe.
6. **KP13 — conversions offsets↔positions O(n) sans cache de lignes** :
   `positionDe` re-scanne le texte depuis 0 pour **chaque** diagnostic,
   `decalageDe` idem pour chaque requête — O(n·m) par publication sur un
   fichier à beaucoup de diagnostics. Un index ligne→décalage construit
   par document (le LSP Java en a un) élimine la classe entière.
7. **KP14 — hover en `MarkedString`** (liste de `Either<String>`) :
   forme dépréciée par LSP 3.17 au profit de `MarkupContent` —
   cohérente avec le LSP Java (le serveur Java fait pareil), donc
   uniformisable d'un coup.
8. **KP15 — diagnostics sans métadonnées riches** : pas de `tags`
   (`Unnecessary` conviendrait à `kt.unusedImport` — grisé/fade chez les
   clients qui le supportent), pas de `relatedInformation` (utile pour
   `kt.conflictingImport` : montrer les DEUX directives), pas de
   `codeDescription`. Le `source = "codeide-kotlin"` et les codes
   stables `kt.*` sont, eux, corrects.
9. **Détails de robustesse déjà bons** (à préserver) : rejet des
   versions obsolètes de didChange (la dernière gagne, miroir de la
   validation atomique Java) ; application des changements par plage
   pour client non conforme ; `exit` sans `shutdown` rattrapé
   (« ceinture et bretelles ») ; plages vides élargies ; UTF-16 par
   unités de code cohérent moteur/hôte. Manque seulement : **test CRLF**
   (une colonne peut avaler le `\r` en fin de plage) et test paires de
   substitution.

---

## 7. Comparaison avec les LSP Kotlin de référence

### 7.1 fwcd/kotlin-language-server (la référence communautaire — README vérifié 2026-10-14)

Processus **externe** (LSP sur stdio) qui embarque le compilateur Kotlin
et son frontend de résolution. Panorama fonctionnel : smart code
completion (avec résolution d'imports), diagnostics, hover, **document
symbols**, definition lookup, **method signature help**, et au-delà
(references, workspace symbols, folding, semantic tokens, formatting
ktlint, quick-fixes/organize imports). Le cadrage CodeIDE l'a **écarté
en connaissance de cause** (D1 : contraire à l'architecture in-process et
à la leçon CodeAssist « FIR à chaque frappe trop lourd sur appareil ») —
et le run mémoire de fwcd (centaines de Mo, JVM desktop) confirmerait
l'incompatibilité avec un tas ART ~576 Mo.

**Usage analytique** : fwcd fixe le **plancher fonctionnel attendu d'un
LSP Kotlin crédible** — documentSymbol et signatureHelp y figurent de
base. C'est précisément là que CodeIDE est en retard (§6.2), et pour les
deux, le modèle de symboles existe déjà chez CodeIDE : le gap est du
travail de **dérivation de protocole**, pas de sémantique.

### 7.2 JetBrains kotlin-lsp officiel (kotlinlang.org/docs/kotlin-lsp + blog JetBrains 05-2026)

Le LSP **officiel** JetBrains, basé sur la plateforme IntelliJ/Analysis
API en processus séparé : complétion, diagnostics et **quick-fixes**
IntelliJ-powered, navigation, formatage, import de projet, surlignage
sémantique, inspections, refactorings **rename/move**. C'est la
référence UX 2026 — et la confirmation stratégique : JetBrains a
renoncé à faire tenir l'expérience complète **dans le processus de
l'éditeur** et l'exécute dans une JVM séparée de plusieurs centaines de
Mo. Sur appareil Android, l'architecture CodeIDE (grammaire vendorée +
modèle conservateur in-process) reste la seule voie réaliste ; le
kotlin-lsp officiel sert de **cible de confort** (richesse des
quick-fixes, rename) pour S2+, pas de modèle d'architecture.

### 7.3 CodeAssist (tyron12233) — la provenance intellectuelle

Le cadrage a étudié CodeAssist en profondeur (§2 du document) et CodeIDE
en a retenu l'architecture à l'identique : parseur multiplateforme
vendoré + modèle de symboles maison + classes synthétiques par point
d'extension + index disque `top:`/`ext:`. Points où CodeIDE a
**dépassé** le modèle : zéro modification locale du vendoring (CodeAssist
modifie `LightSyntaxTree.kt`), suite de parité contractuelle (digeste +
tolérance), coupure synthétiques par classe réelle homonyme, multi-langue
Java↔Kotlin dans le même moteur (SourcesJava), attentes de lambda du
classpath. Points où CodeAssist garde l'avantage : **réécriture
incrémentale** (re-parse de sous-arbre) et instrumentation des
indexeurs — deux sujets exactement repris dans le plan S3/S1.

### 7.4 Android Studio / IntelliJ (la référence UX implicite)

Pour un projet Android Kotlin : survol riche (KDoc rendu), quick-doc,
complétion avec snippets et import automatique, inline hints « pas de
faux positif », refactoring complet. La traduction CodeIDE à l'échelle
tactile : le **sous-ensemble correct** (D3) est déjà la philosophie
affichée ; les écarts les plus perceptibles à l'usage sont le survol
(pas de KDoc — alors que le parseur KDoc est **vendoré mais jamais
utilisé**) et l'absence de rename/references.

### 7.5 Tableau fonctionnel comparatif (fonctions clés)

| Fonction | CodeIDE Kotlin (v0.124.7) | fwcd | JetBrains officiel | CodeAssist |
|---|---|---|---|---|
| Architecture | in-process, 0,5 Mo, pas de compilateur | process externe + compilateur | process externe + IntelliJ | in-process (SPI maison) |
| Diagnostics | syntaxe + 7 sémantiques conservateurs | complets (compilateur) | complets + quick-fixes | partiels maison |
| Complétion | membre/nom/import, auto-import | riche + snippets | IntelliJ-powered | membre/nom |
| Hover | signature simple | riche | quick-doc | — |
| Définition | projet seul (+ `.java` projet) | oui | oui | — |
| References / Rename | **absent** | oui | oui (+ move) | — |
| documentSymbol | **absent** | oui | oui | — |
| signatureHelp | **absent** | oui | oui | — |
| Semantic tokens | absent | oui | oui | coloration maison |
| Formatage | absent | ktlint | oui | build |

---

## 8. Gaps fonctionnels par domaine (synthèse orientée stabilisation)

### 8.1 Diagnostics

Le catalogue (`kt.syntax`, 3 imports, 7 sémantiques) est **borné par
conception** (D3) — le gap n'est pas la profondeur sémantique (le build
reste la vérité) mais la **fraîcheur et l'expressivité** :
- périmption post-classpath et post-modification-externe (KP2/KP9) ;
- garde document-entier-en-erreur → zéro sémantique (une virgule mal
  tapée tue tous les `kt.unresolved` du fichier — conservateur mais
  brutal à l'usage ; un raffinement « sous-arbre en erreur seulement »
  est possible avec les plages des marqueurs) ;
- pas de `tags`/`relatedInformation` (KP15) ;
- `kt.typeMismatch` limité aux littéraux vs types écrits — documenté,
  volontaire ;
- `estJava` : jamais de contrôle d'arité sur candidates Java (arité non
  extraite — documenté).

### 8.2 Complétion

- Fonctionnel et hiérarchisé (locales > membres > premier niveau >
  projet auto-import > classpath auto-import > appelables > mots-clés),
  dédoublonnage (genre+libelle), tri total par sortText.
- Gaps : **infix / expected-type / mots-clés par position grammaticale**
  promis au §4.3 jamais livrés (grep négatif sur `completion/`) ; pas de
  snippets (les fonctions portent juste `()`) ; pas de `labelDetails` ni
  détail riche (KDoc) ; cap 100 sans `isIncomplete` (KP11) ; trigger `.`
  seul (KP12).
- **KP8 (a) — le périmètre « imports par défaut » du moteur est
  incomplet, avec faux positifs `kt.unresolved` PROUVÉS** (§4.4) :
  `ArrayList`, `StringBuilder` — donc par la même cause `HashMap`,
  `HashSet`, `Regex`, `LinkedHashMap`… tout type d'un paquet importé par
  défaut absent de la carte `IMPORTS_DEFAUT`. Un fichier Android typique
  (`val items = ArrayList<Vue>()`) affiche une erreur rouge sur du code
  valide — c'est le type de défaut le plus visible qui existe pour un
  éditeur qui revendique « jamais de faux positif ». Deux voies de
  correction complémentaires : (1) traiter les paquets importés par
  défaut comme des **jokers implicites** dans `ContexteNom` (la voie
  joker interroge déjà `vue.typesDansPaquet` — fonctionne dès que la vue
  est nourrie) ; (2) étendre la carte pour le régime sans vue (la sonde
  reste le test de non-régression).
- **KP8 (b) — candidats d'import filtrés `kotlin.*`** : le filtre
  `!fqcn.startsWith("kotlin.")` de `CandidatsImport` exclut
  `kotlin.time.Duration`, `kotlin.uuid.Uuid`… qui ne sont PAS importés
  par défaut — un `kt.unresolved` légitime **sans correctif proposé**.
  Le filtre devrait exclure uniquement les paquets réellement importés
  par défaut (`kotlin.`, `kotlin.collections.`, `kotlin.text.`,
  `kotlin.ranges.`, `kotlin.annotation.`, `kotlin.comparisons.`,
  `kotlin.enums.`, `kotlin.io.` fonctions, `kotlin.sequences.`
  fonctions, `kotlin.jvm.` limité).

### 8.3 Navigation et recherche

Définition (projet, multi-fichier, `.java` compris) et survol solides.
Manquent : `references` (le plus demandé après la définition dans un IDE
tactile : « où ce symbole est-il utilisé ? »), `documentSymbol`
(outline), `documentHighlight`, définition classpath (décompilé —
renvoyé au mode profond/T2). Le **modèle de symboles existe déjà** ;
c'est le coût d'un index inverse de références qui manque.

### 8.4 Refactoring et actions

`codeAction` = quick-fixes d'import uniquement (un par FQN, position
triée, plafond 8) — conforme au cadrage (« les suivants après K4 »,
jamais livrés). Rename absent (ni `prepareRename`) — le LSP Java a déjà
son « rename honnête » : le miroir Kotlin est naturel et borné
(locales/membres projet prouvés).

### 8.5 Formatage

Aucun. Recommandation : ne PAS improviser un formateur maison (D3) —
renvoyer `formatting` au tooling T2, éventuellement via `codeAction`
« Formater à la sauvegarde » quand le compilateur sera in-process.

### 8.6 Affordances éditeur

`semanticTokens` (couleur sémantique), `foldingRange`, `inlayHint`
absents — la coloration lexicale de l'éditeur couvre le besoin minimal.
`foldingRange` serait dérivable de l'arbre à faible coût ; les deux
autres attendent S2+.

### 8.7 Modèle de projet / plateforme

- **KP5 — couplage structurel à la session Java** : la vue de classpath
  Kotlin est TOUJOURS fournie par `SessionLsp.vueClasspathKotlin()` (index
  fusion Java), et les deux chemins de nourrissage
  (`nourrirSessionLspDepuisModelePersiste`,
  `nourrirSessionLspSiSyncUtile`) **bail-early si la session Java est
  absente** (`sessionLspInterne.value ?: return`). Si la session Java
  échoue à s'ouvrir, le LSP Kotlin reste indéfiniment en régime « projet
  seul » sans même tenter de se nourrir seul. D4 (réutilisation) est
  assumé par le cadrage ; l'**isolation de défaillance**, elle, ne l'est
  pas : une régression Java dégrade silencieusement l'expérience Kotlin.
- **KP7 — scan des racines** : exclut les `.kts` (non documenté dans la
  référence) ; borne 2 000 fichiers (documenté « troncature honnête ») ;
  fraîcheur limitée à la sync (KP10) ; racines de tests non scannées
  (documenté).
- **KP6 — `ScanProjet` partagé non synchronisé** : `racines`/`scannees`
  sont des champs simples lus/écrits depuis les sessions **sous leurs
  verrous respectifs** — deux connexions simultanées (transitions
  d'onglets, multi-fenêtres futures) peuvent concourir sur le scan ;
  courses probablement bénignes (double scan, dernière écriture gagne)
  mais violation de visibilité mémoire réelle. `IndexAppelablesKotlin`,
  lui, est conçu pour (snapshot volatile) — l'asymétrie montre que le
  sujet a été pensé pour l'index mais oublié pour le scan. Les
  synthétiques sont par-session (protégés par le verrou session) — OK.
- **Union sans contexte par module** : deux modules avec des dépendances
  différentes voient l'union — un symbole du module B peut compléter
  dans le module A (sous-rapport inversé : léger **faux positif de
  complétion**, documenté comme arbitrage K3).

---

## 9. Registre consolidé des incohérences / dettes (sévérité et effort)

| # | Constat | Sévérité | Effort | Réf. |
|---|---|---|---|---|
| KP1 | **Baseline de parité rouge sur main** (933 vs 931, v0.124.3 sans régénération) — bloque `:lsp:kotlin:test` ET `koverVerify`/`koverHtmlReport` : la couverture ≥ 80 % est indémontrable | **CRITIQUE** (process) | 10 min de fix + gate CI | §4.2 |
| KP2 | Pas de republication des diagnostics après `definirClasspath` (ni après modification externe) — squiggles périmés jusqu'à la prochaine frappe | HAUTE | ½–1 j | §6.3-1 |
| KP3 | Compliance `languageVersion` promise (§4.4) jamais livrée — grammaire figée v2.3.0, faux `kt.syntax` possibles sur syntaxe 2.4+ sans distinction | HAUTE (produit, différé) | 1–2 j si livrée ; 0 si renonciation documentée | §5.3 |
| KP4 | `version.properties` resté à `0.113.0` (K4/K5 non bumpés) — `serverInfo.version` mensonger, contrat de pack violé par obsolescence | MOYENNE | 5 min + réflexe release | §5.3 |
| KP5 | Vue de classpath = pont unique session Java ; nourrissage bail-early sans session Java — pas d'isolation de défaillance | MOYENNE | 1 j (voie de repli) | §8.7 |
| KP6 | `ScanProjet` partagé non synchronisé (champs simples, visibilité inter-sessions) — asymétrique avec l'index (snapshot volatile) | MOYENNE | 1 h | §8.7 |
| KP7 | Scan : `.kts` exclus (non documenté), fichiers nouveaux invisibles jusqu'à sync (KP10), 2 000 fichiers | MOYENNE | ½ j (kts + invalidation) | §8.7 |
| KP8 | **Imports par défaut incomplets : faux positifs `kt.unresolved` PROUVÉS** (`ArrayList`, `StringBuilder` — §4.4) + quick-fix impossible sur `kotlin.time.Duration` (filtre `kotlin.*` blanket) | **HAUTE** (produit — rompt D3) | 1 j (jokers implicites + carte + sonde en test) | §4.4, §8.2 |
| KP9 | `didSave` no-op — pas de re-diagnostic d'un fichier modifié hors éditeur | MOYENNE | 1 h (avec KP2) | §6.3-2 |
| KP10 | `didChangeWatchedFiles` muette + aucun watcher hôte | MOYENNE | 1–2 j (watcher FUSE borné) | §6.3-3 |
| KP11 | `CompletionList.isIncomplete = false` malgré cap 100 (troncature silencieuse) | MINEURE | 15 min | §6.3-4 |
| KP12 | `triggerCharacters` `["."]` seul (pas `?`) | MINEURE | 5 min | §6.3-5 |
| KP13 | Conversions offsets↔positions O(n) par diagnostic/requête (pas de cache de lignes) | MINEURE | 2 h | §6.3-6 |
| KP14 | Hover `MarkedString` (déprécié LSP 3.17) — uniformisable avec le LSP Java | MINEURE | 30 min | §6.3-7 |
| KP15 | Diagnostics sans `tags` (`Unnecessary`), `relatedInformation` (conflits d'imports), `codeDescription` | MINEURE | 2 h | §6.3-8 |
| KP16 | Écarts de cadrage non réconciliés : §4.3 (infix/expected-type/mots-clés par position) abandonnés silencieusement ; §7 cibles non fonctionnelles jamais instrumentées | MINEURE (doc) | 2 h de doc | §5.3, §4.3 |

Un seul de ces constats rompt la promesse « jamais de faux positif » :
**KP8** (faux `kt.unresolved` sur les types importés par défaut, prouvé
par sonde). Tous les autres sont des défauts de fraîcheur, de complétude
ou de process — la discipline D3 elle-même reste saine partout où elle
est mise en œuvre ; c'est la **donnée d’entrée** (liste d’imports par
défaut) qui était incomplète, pas la discipline.

---

## 10. Points forts à préserver (garde-fous de la stabilisation)

1. **P1 — Parité vérifiée** : corpus du dépôt parsé sans marqueur
   d'erreur (933/933 au run du jour — le test rouge est le digeste, pas
   la capacité de parse) + tolérance troncature/injection éprouvée.
   Toute évolution du parseur doit conserver cette suite VERTe : c'est le
   contrat D2.
2. **P2 — Conservatisme D3 comme discipline de code** : chaque
   diagnostic documente ses sous-rapports dans sa KDoc ET ses tests de
   sous-rapport ; le moteur ne devine jamais ; les requêtes rendent vide
   plutôt que faux. La discipline est saine — le contre-exemple KP8
   vient d’une **donnée incomplète** (imports par défaut), pas d’un
   écart de méthode : la preuve, c’est que la sonde qui l’expose
   valide par ailleurs les témoins (`listOf`, `println`, `Duration`).
   À préserver absolument lors des ajouts (references, rename) : le
   renommage borné doit refuser, pas deviner.
3. **P3 — Dégradation honnête à tous les étages** : moteur absent →
   coloration seule ; vue absente → projet seul + sémantique muette ;
   pack ancien face à hôte neuf → défauts vides ; client fautif →
   tolérance ; requête en échec → résultat vide journalisé. 8 des 24
   tests du serveur éprouvent exactement ces promesses.
4. **P4 — Propreté juridique** : Apache 2.0, notices, ADR — aucun lot G.
   Le module peut être publié/extrait demain.
5. **P5 — Découplage pack prêt** : SPI mince + descripteur + module sans
   dépendance interne. Le futur découpage ne doit jamais casser cette
   propriété (aucune référence directe `jo.lspkotlin.*` hors SPI —
   vérifié par grep, seul `:lsp:host` consomme l'API).
6. **P6 — Discipline concurrentielle** : verrou session + snapshot
   volatile de l'index + copies défensives. À étendre au scan (KP6)
   plutôt qu'à retirer.
7. **P7 — Index disque segments par empreinte** : jars inchangés jamais
   redécodés, écriture atomique, auto-guérison — validé par 4 tests
   dédiés et le retour d'expérience ANR v0.115.0 (correctement traité :
   déplacement sur `DispatcherProvider.io` AVEC test du thread).
8. **P8 — Multi-langue Java↔Kotlin** : `SourcesJava` + marqueur `estJava`
   — rare même chez les références (fwcd ne le fait pas) ; à préserver
   lors de toute évolution du scan.

---

## 11. Plan de stabilisation recommandé

### 11.1 Principes d'exécution (conventions du dépôt, non négociables)

TDD + fixtures par diagnostic ; kover ≥ 80 % ligne sur le code maison
(vendoring exclu — attesté par la parité) ; detekt/spotless hors
`vendor/` ; messages français ; diagnostics documentés (code + fixture +
test de sous-rapport) ; CodeIDE en écriture pour l'agent CodeIDE
propriétaire uniquement — ce rapport reste de l'analyse.

### 11.2 Séquences proposées

**S0 — Réparer l'incident et les faux positifs, verrouiller le process
(2 jours, à lancer immédiatement)**
1. Régénérer la baseline (run `-Dparite.majBase=true`, diff revu : les 2
   fichiers attendus) — 10 min.
2. **KP8 — imports par défaut** : jokers implicites des paquets importés
   par défaut dans `ContexteNom` + carte `IMPORTS_DEFAUT` étendue pour le
   régime sans vue + filtre `CandidatsImport` limité aux paquets
   réellement importés par défaut (`kotlin.`, `kotlin.collections.`,
   `kotlin.text.`, `kotlin.ranges.`, `kotlin.annotation.`,
   `kotlin.comparisons.`, `kotlin.enums.`…). La sonde §4.4 devient le test
   de non-régression (fichier témoin : seul `Duration` doit rester
   signalé) + fixtures moteur pour `ArrayList`/`StringBuilder`/`HashMap` —
   1 j.
3. **Bumper `version.properties`** (`version=0.125.0` à la prochaine
   livraison ; le bump fait partie de la définition de done de CHAQUE
   étape Kotlin) — 5 min.
4. **Mettre le gate sur le chemin critique** : le CI (ou la checklist
   AGENTS.md du dépôt) doit exécuter `:lsp:kotlin:test` sur TOUT commit
   touchant des `.kt` — l'incident prouve que 5 commits s'en sont passé.
   Option plus robuste : une tâche `verificationParite` rapide (compte de
   fichiers vs baseline, sans digeste) branchée dans `check` pour échouer
   VITE et CLAIR sur « fichiers .kt ajoutés/supprimés → régénérer la
   baseline ».
5. **KP2 — republication post-classpath** : voie de rappel façade→
   serveurs (les sessions vivantes déjà suivies ; ajouter un callback
   « onClasspathApplied » qui republie les documents ouverts de CHAQUE
   `KotlinLanguageServer`) + test en vrai flux LSP4J client espion —
   ½–1 j.
6. KP6 (synchroniser `ScanProjet`), KP11 (`isIncomplete`), KP12
   (trigger `?`) — 1 h cumulées.

**S1 — Les deux requêtes à fort rapport + hygiène protocolaire (2–3 j)**
1. `textDocument/documentSymbol` : dérivation directe de
   `SymbolesProjet.declarations` (genres, conteneurs, plages déjà
   portés) — hiérarchie `DocumentSymbol` honnête, plafond de profondeur.
2. `textDocument/signatureHelp` : réutiliser l'extraction de paramètres
   du contrôle d'arité + les attentes de lambda K4/K5 ; signature active
   = candidate dont la plage d'appel couvre le curseur.
3. `didSave` → republication (miroir Java) ; tags `Unnecessary` sur
   `kt.unusedImport` ; `relatedInformation` sur `kt.conflictingImport`.
4. KP3 : soit livrer `languageVersion` (champ `ClasspathModule` →
   `ClasspathKotlin` → garde du diagnostic : syntaxe hors niveau =
   `kt.syntax` atténué « niveau de langue projet : X »), soit rédiger
   l'ADR de renonciation — ne pas laisser la promesse orpheline.
5. KP5 : voie de repli quand la session Java est absente (nourrissage
   Kotlin direct depuis le modèle persisté/ClasspathResult, vue
   construite par ses propres moyens ou null assumé JOURNALISÉ) —
   au minimum, journaliser explicitement « Kotlin en régime projet seul :
   session Java absente ».
6. Cache de lignes par document pour offsets↔positions (KP13) + tests
   CRLF/surrogates.

**S2 — Confort (par valeur décroissante, 1–2 semaines au fil des
versions)**
1. `references` + `documentHighlight` via un index inverse projet
   (références par document ouvert + scan — borné, D3 : une référence
   non prouvée n'est pas rendue).
2. `rename` borné (« rename honnête » miroir Java) : `prepareRename`
   strict (locales + membres projet dont la déclaration est prouvée),
   `WorkspaceEdit` multi-fichiers via textEdits, refus explicite sinon.
3. Hover riche : **exploiter le `KDocParser` déjà vendoré** (le KDoc est
   parsé nulle part aujourd'hui) — rendu texte simple de la première
   phrase + `MarkupContent` ( KP14 réglé du même coup).
4. `foldingRange` depuis l'arbre immuable (plages des blocs/classes).
5. Raffinement de la garde sémantique : sous-arbre en erreur au lieu du
   document entier (les plages des marqueurs d'erreur existent).

**S3 — Différés assumés (attendre le tooling)**
1. T2 : compilation K2 par pack (le pack §6 devient l'hôte du
   compilateur — critères de découpage §6 à revoir avec le propriétaire).
2. Re-parse incrémental de sous-arbre (CodeAssist le fait ; la façade est
   prête — arbre immuable reconstruit de toute façon).
3. Sources-jars/décompilé pour la définition classpath (« mode profond »
   Analysis API documenté comme extension possible).
4. `semanticTokens` si la coloration lexicale devient limitante.

### 11.3 Garde-fous de stabilité à instaurer

- **CI** : `:lsp:kotlin:test` (+ parité) sur tout commit ; mesure kover
  par branche (indémontrable aujourd'hui — KP1).
- **Réflexe baseline** : toute PR ajoutant/retirant des `.kt` régénère
  `digeste-corpus.txt` (le message d'échec du test le dit déjà — il faut
  que le processus l'entende).
- **Réflexe descripteur** : `version.properties` bumpé à chaque livraison
  Kotlin ( KP4).
- **Banc §7** : instrumentation minimale sur appareil (latence complétion
  à chaud, premier diagnostic, tas en régime établi) — 3 compteurs
  journalisés, vérifiés lors des retours F/baseline.
- **Soak multi-connexions** : rotation d'onglets .kt/.kts/.java en
  boucle + vérification `nombreDocuments == 0` après fermeture (éprouve
  KP6 en conditions réelles).
- **Taux de sous-rapport** : garder les tests de sous-rapport comme
  compteurs suivis (toute montée = régression de promesse D3).

### 11.4 Quick wins (≤ une demi-journée chacun)

| Quick win | KP | Effet utilisateur visible |
|---|---|---|
| Régénérer baseline + gate CI | KP1 | La chaîne qualité du module revit ; la couverture redevient démontrable |
| Bump `version.properties` | KP4 | `serverInfo` honnête ; base de pack propre |
| `isIncomplete` + trigger `?` | KP11/12 | Complétion membre fiable après `?.` |
| Imports par défaut complets (jokers implicites + carte) | KP8 | Fin des erreurs rouges fausses sur `ArrayList`/`StringBuilder`/`HashMap` — le défaut le plus visible de tout le rapport |
| Sync `ScanProjet` | KP6 | Courses inter-connexions éliminées |
| Tags `Unnecessary` | KP15 | Imports inutilisés grisés (clients supportant les tags) |

### 11.5 Note d'environnement de développement (constat du run de tests)

- Le JRE système (`/usr/lib/jvm/java-21-openjdk-amd64`, sans `javac`)
  casse le toolchain `build-logic` : utiliser
  `JAVA_HOME=/home/z/my-project/jdk-21.0.12.1+1` (Temurin) et
  `-Dorg.gradle.java.installations.auto-detect=false`.
- `:lsp:host` est un module Android : c'est `testDebugUnitTest` (et
  `ANDROID_HOME` doit être posé), pas `test`.
- Machine 4 Go : runs séquentiels `--no-daemon`, tas bornés (sinon
  OOM-kill du daemon) — même discipline que le rapport Java.
- `--tests '!…'` (exclusion) n'est pas supporté par ce Gradle 9.7.1 (« No
  tests found for given includes ») : filtrer par inclusions de paquets.

---

## 12. Annexes

### A. Inventaire du module `lsp/kotlin` (main)

31 fichiers / 8 077 lignes de code maison + 23 fichiers / 9 585 lignes
vendorées (`src/main/vendor`, grammaire v2.3.0 octet pour octet) ; 12
fichiers de test / 2 804 lignes ; jar 499 675 octets (66 classes
vendorées, 105 classes CodeIDE).

```
api/         MoteurLangageKotlin (SPI+ServiceLoader), SessionLangageKotlin
             (contrat, défauts vides = tolérance de version), DiagnosticsKotlin
             (catalogue kt.*), ClasspathKotlin (vue neutre, entrées, racines),
             SymbolesKotlin (types de propositions/survol), MoteurKotlinVendore
parse/       ParseurKotlin (façade performLexing+KotlinParser), ArbreSyntaxique
             (arbre immuable + reconstruction depuis marqueurs, digeste SHA-256)
parse/kt/    KtElements, KtExpressions (façade Kt* + helpers chaineAncetresA…)
classpath/   LecteurClassBrut (pool/supertypes/@Metadata sans ASM),
             DecodeurMetadonnees (kotlin-metadata-jvm, jar/AAR, porte .kotlin_module)
index/       AppelableKotlin, IndexAppelablesKotlin (segments disque .kidx,
             clés top:/ext:, snapshot volatile)
resolve/     ResolutionKotlin (FQCN/supertypes/descripteurs), ResolutionReferences,
             SymbolesProjet (déclarations/portées), TypageExpression, SmartCasts,
             ParametresLambda, RecepteursWith, CandidatsImport, InterrogationsSymboles
             (survol/définitions)
completion/  CompletionKotlin (membre/nom/import, tri, dédoublonnage)
diagnostics/ DiagnosticsSyntaxiques, DiagnosticsSemantiques
synthetics/  ClassesSynthetiques (R/BuildConfig/ViewBinding/DataBinding, coupure
             par classe réelle), AnalyseRessourcesAndroid
session/     SessionLangageKotlinParse (documents+classpath sous verrou),
             ScanProjet (racines, multi-langue), SourcesJava (types Java top-level)
```

Côté hôte : `KotlinLanguageServer.kt` (584 l.), `SessionLspKotlin.kt`
(258 l.), `AdaptateurVueClasspath.kt` (228 l., couture D4 sur l'index
fusion Java) ; côté éditeur : `CoordonnateurLsp.kt` + points Kotlin de
`EditorViewModel.kt` (ouverture/fermeture/nourrissage) +
`FermablesEspace.kt`.

### B. Méthodes LSP effectivement servies (constat du code)

Cycle : `initialize`, `initialized`, `shutdown`, `exit`.
Documents : `didOpen`, `didChange`, `didClose`, `didSave` (no-op).
Requêtes : `completion`, `hover`, `definition`, `codeAction`.
Push : `publishDiagnostics` (avec version + vidange au didClose).
Espace : `didChangeConfiguration`, `didChangeWatchedFiles` (no-ops).
Non déclarées/non servies : tout le reste (cf. §6.2 pour le verdict
d'utilité).

### C. Références

- `docs/LSP_KOTLIN.md` (v0.109.0, étapes K1–K5 livrées) — document de
  cadrage de la mission.
- `docs/adr/0115-lsp-kotlin-vendoring-grammaire.md` — vendoring Apache
  2.0, exemptions de qualité, suite de parité obligatoire.
- `CHANGELOG.md` v0.110.0 → v0.124.7 (entrées `lsp-kotlin`, `lsp-host`).
- Rapport compagnon : `rapport-analyse-lsp-java-codeide.md` (constats
  partagés : didSave, watched files, MarkedString, conversions).
- fwcd/kotlin-language-server — README (github.com/fwcd/kotlin-language-
  server), consulté le 2026-10-14.
- JetBrains kotlin-lsp officiel — kotlinlang.org/docs/kotlin-lsp.html et
  blog JetBrains « Official Kotlin Support for Visual Studio Code »
  (05-2026), consultés le 2026-10-14.
- CodeAssist (tyron12233) — étude intégrée au cadrage (§2), GPL-3.0,
  zéro code réutilisé.
- Run de vérification du jour : `:lsp:kotlin:test` 118 tests / 1 échec
  (PariteCorpusDepotTest — digeste) ; `:lsp:host:testDebugUnitTest` 72
  tests / 0 échec ; `rapportTaille` jar 499 675 octets ; sonde SPI
  empirique (faux positifs `ArrayList`/`StringBuilder`, version pack
  `0.113.0`) ; arbre git propre avant et après (lecture seule respectée).
