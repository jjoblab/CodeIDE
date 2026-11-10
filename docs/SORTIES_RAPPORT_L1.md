# Rapport de fin de lot L1 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L1 — modèle, filtre,
> adaptateurs, parseurs (JVM pur, aucune UI)**. Branche :
> `agent/sorties-l1-modele-parseur` (base `43a4e773`, tête de
> `agent/sorties-l0-conception`). Date : 2026-11-10. Langue : français.

## 1. Fait (par livrable)

| Livrable | Fichier(s) | État |
|---|---|---|
| 1. Modèle de ligne | `core/domain/src/main/kotlin/jo/codeide/core/domain/LigneSortie.kt` | ✅ — `SourceSortie` (6 familles), `NiveauSortie` (7 valeurs, `INDETERMINE` en DERNIER : un plancher ne masque jamais ce qu'il ne sait pas graduer), `PositionSource` + `GravitePosition`, `LigneSortie` (tâche posée par événement/annonce, statut porté par l'événement, `octetsRecus`/`octetsTotal` — addendum ADR 0160, arbitrage Q12) |
| 2. Filtre unifié | `core/domain/src/main/kotlin/jo/codeide/core/domain/FiltreSorties.kt` | ✅ — plancher OU ensemble explicite (l'ensemble PRÉVAUT), texte sous-chaîne/regex/mot entier (message ET étiquette), pliage NFD casse+accents (ADR 0025), étiquettes inclues/exclues exactes, sources, sessions (identité OU exécution), plage de temps ; motif regex invalide → `erreurMotif` + repli « aucune correspondance », JAMAIS d'exception |
| 3. Adaptateurs purs | `core/domain/src/main/kotlin/jo/codeide/core/domain/AdaptateursSorties.kt` + `feature/editor/src/main/kotlin/jo/codeide/feature/editor/AdaptateurConsoleSorties.kt` | ✅ — `LigneSortieBuild`, `LigneSortieSync`, `LigneJournal` (clé de session canonique `paquet/pid/processus`), `LogEntry`, `TelechargementBuild` (ligne ouverte, octets portés) ; l'adaptateur console (`EvenementConsoleTexte.Ligne` → `LigneSortie`) vit dans `feature:editor` (internal, résolveur `(TexteTooling) -> String` sous contrôle de l'appelant — la direction des dépendances interdit au domaine de connaître le feature) |
| 4. Parseur de flux Gradle | `core/domain/src/main/kotlin/jo/codeide/core/domain/ParseurFluxGradle.kt` | ✅ — machine à états : en-têtes `> Task :… [STATUT]` (frontière TEXTE, statut fin `StatutTacheGradle` — dégradation D1), événements `EtatTacheBuild` par `surTache` (la frontière est POSÉE, jamais devinée — Q6), verdicts `BUILD …` avec durées (« 42s », « 2m 3s », « 954ms », « 3.4s »), résumé `N actionable tasks` singulier/pluriel, sections d'échec (FAILURE / Where / What went wrong+Caused by / Try / Exception is) refermées par le verdict, positions cliquables Kotlin/Java/script extraites au passage (`ExtrairePositionsGradle`, objet privé du même fichier) ; `reinitialiser()` pour une nouvelle exécution |
| 5. Parseur ANSI SGR | `core/domain/src/main/kotlin/jo/codeide/core/domain/ParseurAnsiSgr.kt` | ✅ — SGR 0/1/22/3/23/30-37/39/90-97 rendus ; 256 (`38;5;n`), RGB (`38;2;r;g;b`), fonds (40-47/49/100-107) consommés SANS rendu ; CSI non-SGR, OSC (BEL et ST), jeux de caractères (`ESC(B`), commandes 2 caractères, ESC isolé en fin de flux : purgés sans exception ; `purger` pour le réglage M13 et l'export |
| 6. Tests non-régression + limites | `core/domain/src/test/kotlin/jo/codeide/core/domain/` : `LigneSortieTest`, `FiltreSortiesTest`, `ParseurAnsiSgrTest`, `ParseurFluxGradleTest`, `CorpusSortiesGradleTest`, `CasLimitesFluxGradleTest`, `AdaptateursSortiesTest` ; `feature/editor/src/test/kotlin/jo/codeide/feature/editor/AdaptateurConsoleSortiesTest.kt` | ✅ — 60 tests JVM, 0 échec (§ 2) ; les 11 logs du corpus repassent au parseur avec verdict attendu par fichier, zéro exception, texte transparé ; cas limites : ligne 4 000, flux coupé, UTF-8 multi-octets, `\r` résiduel, 100 000 lignes |

Commits (branche `agent/sorties-l1-modele-parseur`) : listés par
`git log --oneline agent/sorties-l0-conception..agent/sorties-l1-modele-parseur`.

## 2. Preuves (commandes lancées et résultats)

- **Chaîne de vérification** (STRATEGIE_VERIFICATION § 2, JDK 21.0.13,
  Gradle 9.7.1 wrapper) :
  `./gradlew spotlessApply spotlessCheck detekt checkModuleDependencies checkUiTokens :core:domain:test :feature:editor:testDebugUnitTest`
  → **BUILD SUCCESSFUL** (detekt `maxIssues 0`, ktlint propre,
  dépendances inter-modules et jetons UI vérifiés sur les 18 modules).
- **Tests** : 60 nouveaux tests, 0 échec, 0 erreur :
  `LigneSortieTest` 4 · `FiltreSortiesTest` 10 · `ParseurAnsiSgrTest` 10
  · `ParseurFluxGradleTest` 9 · `CorpusSortiesGradleTest` 7 ·
  `CasLimitesFluxGradleTest` 5 · `AdaptateursSortiesTest` 9 ·
  `AdaptateurConsoleSortiesTest` 6. JUnit 4, noms français en
  backticks, JVM pur (`explicitApi()` respecté sur core:domain).
- **Corpus réel** (`docs/archive/tooling-scenarios/sorties/`, 11 logs
  committés) : chaque fichier est relu ligne à ligne par le parseur ;
  verdicts attendus confirmés — 01 `42s` (42 000 ms), 02 `954ms`,
  03 `2s`, 04 `1s`, 05 `3s`, 06 `2s`, 09 `1s`, 10 `2s`, 10b `1s` tous
  `reussi=true` ; 07 échec `22s` (en-têtes : 9 annonces dont 1
  `SKIPPED` et 1 `FAILED`) avec résumé `7 actionable tasks: 7
  executed` ; 08 échec `1s` avec les quatre sections d'échec (intitulé,
  Where, What went wrong, Try — pas de trace) et DEUX positions
  extraites (`e: file:///…build.gradle.kts:2:8:` → ligne 2, colonne 8 ;
  `Build file '…' line: 2`). Aucun bloc d'échec dans les builds réussis.
- **Cas limites** : ligne de 4 000 caractères transparente ; flux coupé
  (annonce tronquée `> Task :app:compileDebugKo` analysée telle quelle,
  la suite complète le groupe) ; UTF-8 (« thèses », 中文, 🚀) transparé ;
  `\r` résiduel ne masque ni verdict ni section (`$` de fin de motif
  admet le terminateur final) ; **100 000 lignes analysées en 0,46 s**
  (budget : < 10 s) — le parseur reste deux ordres de grandeur sous le
  budget d'ingestion (constat L0 : 1,06 M lignes/s au banc).
- **Bugs réels attrapés par les tests du lot** (preuves que la table de
  vérité travaille) : (a) `REGEX_DUREE` utilisée mais jamais définie
  (erreur de compilation) ; (b) forme RGB `38;2;r;g;b` qui ne
  consommait que 3 paramètres sur 4 — le dernier octet était relu comme
  un code SGR autonome (`48;2;1;2;3` posait l'italique !) ; (c) ordre
  d'initialisation des propriétés de `FiltreSorties` — `motif` et
  `motifMotEntier` lisaient `aiguille`/`drapeauxRegex` déclarés plus
  bas, à travers des lambdas `runCatching` évalués immédiatement : NPE
  capturée (motif « mot entier » toujours `null`) et drapeaux lus à 0
  (compilation silencieusement sensible à la casse) — réordonnés,
  commentaire d'ordre posé ; (d) le marqueur `e|w` n'était pas capturé
  dans le motif Kotlin des positions. Tous corrigés et couverts.

## 3. Écarts avec le plan, et pourquoi

1. **Séparation `CorpusSortiesGradleTest` / `CasLimitesFluxGradleTest`**
   en deux classes (le plan initial prévoyait un seul fichier de corpus)
   : le seuil detekt `TooManyFunctions` (11 par classe) imposait le
   partage ; discipline inchangée.
2. **`ExtrairePositionsGradle` en objet privé du fichier du parseur**
   (le plan gardait tout dans la classe) : même contrainte detekt ; la
   cohésion reste locale (même fichier, API publique inchangée), et les
   indices de groupes des trois motifs sont portés par des constantes
   nommées — l'ordre des groupes DIFFÈRE entre Kotlin (marqueur
   d'abord) et Java (fichier d'abord), d'où deux jeux distincts.
3. **`FiltreSorties` : correspondances de texte fusionnées** derrière un
   testeur (`textePliePasse(ligne) { … }`) et styles SGR regroupés dans
   `QuadrupletStyle` : mêmes réactions à detekt (ReturnCount,
   LongParameterList, CyclomaticComplexMethod) — zéro suppression de
   règle, zéro desserrement.
4. **Environnement** (hors périmètre du dépôt, aucun fichier du projet
   touché) : le bac à sable avait perdu JDK et SDK Android (recyclage) ;
   JDK 21 réinstallé (extraction `openjdk-21-jdk-headless`/`jre-headless`
   de Debian, lié à `/home/z/tools/jdk-21` attendu par
   `scripts/env.sh`) ; SDK Android reconstruit (cmdline-tools 13114758,
   `platforms;android-37.2`, `build-tools;37.0.0`, `platform-tools`,
   licences acceptées). Le CDN de Maven Central limitait l'IP partagée
   (429 en rafale) : un script d'init Gradle UTILISATEUR
   (`~/.gradle/init.gradle.kts`, miroirs Tencent/Huawei en tête, portail
   réajouté) a contourné la limitation de TRANSPORT — aucune coordonnée
   ni configuration du dépôt changée ; les artefacts Robolectric
   `android-all` (SDK 26/33) ont été pré-posés dans `~/.m2/repository`
   depuis le miroir (les premiers téléchargements tronqués ont été
   détectés par `unzip -t` et repris avec `-C -`).

## 4. Ce qui n'a pas pu être vérifié

- **Sur appareil** : hors périmètre du lot (aucune UI) — rien à vérifier
  sur appareil ici ; les budgets de rendu (L2/L3) attendront leurs lots.
- **Banc de mesure T6** (`SORTIES_BANC_MESURE.md`) : le protocole reste
  celui du propriétaire ; le lot livre la mesure du test 100 000 lignes
  (0,46 s) comme garantie de non-régression, pas comme PV du banc S1-S8.
- **kover ≥ 80 %** : `koverVerify` est volontairement INTERDIT en local
  (CI seulement) — la couverture se vérifiera à la CI ; les 60 tests
  couvrent les 6 fichiers du lot (chaque règle du filtre, chaque
  transition du parseur, chaque projection d'adaptateur, chaque
  contrôleur ANSI consommé).
- **CI GitHub Actions** : le push final déclenchera la chaîne complète ;
  le retour CI n'était pas observable au moment du rapport.

## 5. Décisions à prendre pour le lot suivant (L2 — lecteur)

1. **Granularité D1 en attente** : la réponse du dépôt frère à
   `docs/DEMANDES_CODEIDE_TOOLING.md` (D1 — statuts fins `UP-TO-DATE`,
   `FROM-CACHE`, `NO-SOURCE` dans les événements) décidera si
   `StatutTacheGradle` (dégradation texte) devient redondant ; en
   attendant, le parseur la maintient et l'événement fait foi.
2. **Emplacement du tampon** : le lecteur (L2) doit choisir entre un
   `MutableStateFlow<List<LigneSortie>>` par canal et un accumulateur
   unique par canal dans `feature:editor` ; le modèle et le filtre
   n'imposent rien (pur) — recommandation : accumulateur par canal avec
   index de recherche pré-plié (le filtre alloue le pliage par ligne,
   cf. KDoc `FiltreSorties`).
3. **Horodatage à l'ingestion** : `LigneSortie.horodatageMs = 0` reste
   un état transitoire — L2 doit poser l'instant de réception dans son
   accumulateur (jamais l'UI), question ouverte : instant d'ÉMISSION du
   câble (port tooling) ou instant de réception (console) ?

## 6. Risques ouverts

1. **Le texte `> Task` reste la seule frontière côté corpus** : les 11
   logs du corpus sont des captures `gradle tasks`/builds SANS
   événements `EtatTacheBuild` ; le couplage événement+texte (Q6) est
   testé unitairement mais pas sur un vrai flux d'orchestrateur — à
   re-vérifier en L2 sur les flux réels du pont (risque faible : le
   contrat « l'événement fait foi » est en place).
2. **Résumé `N actionable tasks` non imprimé en cas d'échec avant la
   fin** : `ResumeTachesGradle` reste `null` (comportement attendu,
   testé sur 08) ; la L3 ne devra pas inventer de compteurs — la table
   de vérité le rappelle.
3. **Limitation réseau du bac à sable** (429 Maven Central) : peut
   ralentir les prochaines vérifications ; le script d'init utilisateur
   est réutilisable tel quel (aucun impact dépôt).
4. **`FiltreLogcat` bascule en L4** : jusqu'au lot L4, deux filtres
   coexistent (sémantique dupliquée volontairement, ADR 0160
   « Conséquences ») — ne pas toucher `FiltreLogcat` d'ici là.
5. **Déclencheur CI corrompu (préexistant, hors lot)** :
   `.github/workflows/ci.yml` ligne 37 contient `branches: ain]` — le
   préfixe `[m` de `[main]` a disparu du fichier committé (même famille
   de corruption littérale que celle observée sur les chaînes de test
   du lot, § 2) : le filtre de branche du `push` ne matche plus `main`.
   La CI reste déclenchable par `pull_request` (le chemin utilisé pour
   ce lot). À réparer dans un lot de maintenance séparé — corrigé ici,
   ce serait un changement hors périmètre et un risque de conflit avec
   d'autres branches en vol.
