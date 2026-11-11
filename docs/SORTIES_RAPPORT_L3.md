# Rapport de fin de lot L3 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L3 — Gradle : sessions,
> regroupement, historique** (registre des exécutions, projections
> brut/regroupé, résumé, liens source, persistance bornée et expurgée,
> journaux du serveur rattachés). Branche : `agent/sorties-l3-gradle-historique`
> (base `508298d2`, tête de `agent/sorties-l2-lecteur`).
> Date : 2026-11-10. Langue : français.

## 1. Fait (par livrable)

| Livrable | Fichier(s) | État |
|---|---|---|
| 1. Ancres partagées du flux Gradle (source unique, ZÉRO divergence parseur/regroupement) | `core/domain/.../ParseurFluxGradle.kt` (+ `AncresFluxGradle`, `EnTeteTacheGradle`) | ✅ — le parseur L1 délègue aux fonctions pures ; comportement inchangé (tests L1 verts), le regroupement (L3) reconnaît les structures PAR LES MÊMES ancres |
| 2. État d'exécution porté par les ÉVÉNEMENTS | `core/domain/.../EtatExecutionGradle.kt` | ✅ — verdict/durée/compteurs (`EtatBuild`), statuts et durées par tâche (`EtatTacheBuild`), gravités ingérées, phases de sync (`EtapeSyncTooling`) ; immuable, persistable, relu tel quel |
| 3. Dérivation regroupée (résumé, groupes, bloc d'échec épinglé, hors-groupes) | `core/domain/.../RegroupementSorties.kt` | ✅ — couverture stricte « chaque ligne une fois » garantie par construction (verrou après structure de fin) ; couplage ÉVÉNEMENT + TEXTE (Q6) : champ `tache` porté à l'ingestion, ancre texte en dégradation honnête (D1) |
| 4. Projection regroupée (UNE liste re-projetée, ADR 0078) | `core/domain/.../ProjectionRegroupee.kt` | ✅ — bloc d'échec épinglé EN TÊTE sans duplication, reste chronologique, en-têtes devant leur première ligne visible, replis, groupe sans ligne visible inexistant ; la ligne d'ancrage remplacée par l'en-tête n'apparaît JAMAIS deux fois (attrapé par les tests) |
| 5. Registre des exécutions (port pur, ADR 0161) | `core/domain/.../RegistreExecutions.kt`, `ExpurgateurSorties.kt`, `PortSautSource.kt` | ✅ — bornes (N=10, plafond 20 000 lignes tête+queue ANNONCÉE, plafond d'octets global) ; expurgation À L'ÉCRITURE (relativisation + `LogRedactor`, liens source relatifs SURVIVANTS) ; `TroncatureSorties` pur |
| 6. Flux réel tooling derrière le port (le lecteur ne change pas) | `feature/editor/.../CollecteurSortiesGradle.kt`, `FluxSortiesCentre.kt`, `HistorienExecutions.kt`, `DiSortiesEditeur.kt`, `PompeBuildTooling.kt` | ✅ — branché SUR LA POMPE (le seul entonnoir qui voit tout) ; lotissement par seuil OU période d'horloge câble (parité de latence console) ; cliché qui suit la PUBLICATION (un lecteur qui s'attache ne voit jamais deux fois la même ligne — attrapé par les tests) ; l'en-tête du registre est posé AU DÉPART (l'incident qui meurt reste listé) ; la console reste ACTIVE (parité, `GradleService` intact) |
| 7. Persistance fichiers (implémentation `app`) | `app/.../sorties/RegistreExecutionsFichiers.kt`, `StockageLignesExecutions.kt`, `SerialisationExecutions.kt` + binding Hilt | ✅ — JSON Lines gzip MULTI-MEMBRES (une écriture PAR LOT), index JSON unique atomique (`.tmp` + renommage), lecture TOLÉRANTE (membre tronqué = lignes déjà décodées) ; journaux serveur LUS dans le journal applicatif (jamais copiés) |
| 8. Lecture regroupée du lecteur | `feature/sorties/.../RangeeLecteur.kt`, `LecteurSortiesViewModel.kt`, `LignesSortiesAdapter.kt`, `FragmentLecteurSorties.kt`, layouts + strings FR/EN | ✅ — bascule brut ↔ regroupé (le tampon ne bouge jamais), classifications tenues À L'INGESTION (chaque ligne analysée UNE fois — le banc S5 mesure la bascule), cache de rangées (aucun re-parse SGR), replis par groupe, carte de résumé (l'état ÉVÉNEMENTS fait foi — les `null` ne s'affichent jamais comme zéro), bandeau de consultation, en-tête collant de la tâche courante (moteur L2 inchangé), liens source → `RegistrePortSautSource` (honnête si port absent) |
| 9. Banc S5 + rejeu diagnostic | `SourceSortiesFactice.kt`, `ContenuFactice.kt`, `BancSortiesActivity.kt` | ✅ — séquence S5 (500 tâches, statuts fins cyclés UP-TO-DATE/FROM-CACHE/NO-SOURCE, tampon plein) ; « Rejouer la dernière exécution persistée » (ADR 0161.5 démontrable sans surface dédiée) |
| 10. Tests | `core/domain/src/test` (3 classes nouvelles), `feature/editor/src/test` (3), `app/src/test` (1), `feature/sorties/src/test` (1) | ✅ — **38 tests nouveaux** ; chaîne verte : spotless, detekt (`maxIssues 0`), `checkModuleDependencies`, `checkUiTokens` (R1/R2 corrigés AU COMMIT — la dette ne remonte pas), tests des 4 modules touchés + non-régression feature:editor (529) |

Commits (branche `agent/sorties-l3-gradle-historique`) : `d05ed879`
(domaine regroupé), `39b9422b` (registre + expurgation + saut),
`d3339122` (flux réel + historien + routage), `2dd92c70` (persistance
fichiers), `68519b51` (lecture regroupée), + docs (le présent commit).

## 2. Preuves (commandes lancées et résultats)

- **Chaîne de vérification** (STRATEGIE_VERIFICATION § 2 — JDK 21,
  Gradle 9.7.1, miroirs du bac à sable) :
  `./gradlew spotlessApply spotlessCheck detekt checkModuleDependencies
  checkUiTokens :core:domain:test :feature:editor:testDebugUnitTest
  :feature:sorties:testDebugUnitTest :app:testDebugUnitTest` →
  **BUILD SUCCESSFUL** (detekt `maxIssues 0` sur tous les modules ;
  frontières inter-modules vertes — AUCUNE arête nouvelle :
  feature:sorties → core:domain/model/ui existantes, feature:editor →
  feature:sorties déjà assumée en L2, app → tout existant ; cliquet
  jetons sans remontée).
- **Tests** : 38 nouveaux, 0 échec —
  `RegroupementSortiesTest` 13 (dont NON-RÉGRESSION : les 11 logs du
  corpus se regroupent sans chevauchement + bloc d'échec du
  08-erreur-config.log) · `ProjectionRegroupeeTest` 8 ·
  `ExpurgateurSortiesTest` 10 · `CollecteurSortiesGradleTest` 9 ·
  `FluxSortiesCentreTest` 4 · `RegistreExecutionsFichiersTest` 7
  (Robolectric) · `LecteurSortiesRegroupeTest` 10. Suites existantes
  vertes : core:domain (758+), feature:editor (542+), feature:sorties
  (56), app (registre 7).
- **Bugs réels attrapés par les tests du lot** (preuves que la
  couverture travaille) :
  (a) le cliché de session incluait le lot EN ATTENTE — un lecteur qui
  s'attache en pleine exécution recevrait chaque ligne DEUX fois
  (critère § 10.1.1) : le cliché suit désormais la PUBLICATION ;
  (b) la ligne d'ancrage d'un groupe (l'en-tête `> Task`) était ré-émise
  comme Ligne SOUS sa propre rangée d'en-tête : duplication du contenu
  dans la vue regroupée — l'en-tête la représente désormais ;
  (c) le compteur d'en-tête comptait la ligne d'ancrage (l'en-tête
  annonçait 2 lignes pour 1 visible) ;
  (d) un contenu post-verdict étendait le groupe FERMÉ (chevauchement de
  couverture) — le verrou après structure le rend impossible par
  construction ;
  (e) le lotissement par seuil seul laissait un build lent invisible
  jusqu'à 256 lignes — la période d'horloge câble (50 ms) le rend
  visible au fil de l'eau (parité de latence console).
- **Non-régression console** : `GradleService` et `PanneauConsoleFragment`
  INTACTS (la migration par parité est un engagement du lot) — les
  tests console existants sont verts sans modification.

## 3. Écarts avec le plan, et pourquoi

1. **Le registre pose l'en-tête AU DÉPART** (et pas seulement à la
   conclusion) : l'ADR 0161 exige que l'incident qui meurt reste
   listable — le port a gagné `demarrer(execution)` (en-tête placeholder
   qui survit à la mort du processus).
2. **Lotissement par période d'horloge** ajouté au seuil de 256 : le
   seuil seul aurait fait régresser la latence d'affichage (un build
   lent invisible) — la période (50 ms, horloge du câble) est la
   dégradation honnête ; S8 du banc (transport réel, ADR 0152) jugera
   la latence finale.
3. **Téléchargements : base de parité** (UNE ligne par artefact terminé,
   v0.46.0) — l'ouverture de ligne EN PLACE (spec § 4.7, arbitrage Q12)
   demande une mutation de rangée du tampon + un re-rendu en place du
   moteur : reporté au L5 avec le panneau compact (la seule surface qui
   l'affichera) ; les champs structurés (octets, total, durée) voyagent
   déjà et le rendu µ-jauge existe (L2).
4. **La consultation par défaut reste en projection BRUT** (ADR 0161.5
   « brut par défaut ») — le regroupement d'une exécution relue
   fonctionne (données d'événements persistées avec — testé) mais reste
   un geste utilisateur.
5. **Le banc S5 mesure la BASCULE** ; le re-remplissage des repères de
   gravité et la carte de résumé suivent la même dérivation — le PV
   propriétaire dira si la dérivation classifiée tient le budget S5
   (l'architecture est prête : aucune regex à répétition).
6. **Environnement** (hors périmètre dépôt) : l'artefact Robolectric
   `android-all-instrumented:9-robolectric-4913185-2-i7` a dû être posé
   à la main dans `~/.m2` (429 des miroirs — même famille que le
   rapport L1 § 6.5) ; aucun impact dépôt.

## 4. Ce qui n'a pas pu être vérifié

- **Sur appareil** : hors périmètre (aucune surface de production) —
  les PV S1–S6 attendent le propriétaire ; l'écran banc arme désormais
  S5 (regroupement).
- **kover ≥ 80 %** : `koverVerify` INTERDIT en local (CI seulement) ;
  les 38 tests couvrent les règles nouvelles (couverture stricte du
  regroupement, bornes du registre, expurgation, couplage
  événement+texte, routage banc/réel).
- **CI GitHub Actions** : le déclencheur reste corrompu (préexistant,
  `branches: ain]` — hors lot) ; la CI se déclenche par `pull_request`.
- **La latence réelle émission → pixel sur le VRAI transport (S8)** :
  le collecteur publie par lots de période câble, mais la mesure passe
  par le propriétaire (sonde ADR 0152 au L3+).

## 5. Décisions à prendre pour le lot suivant (L4 — Logcat et Journal migrés)

1. **Le port `FluxSorties` est prêt pour Logcat et Journal** : les
   adaptateurs purs existent (L1) — L4 re-lie le routage de
   `FluxSortiesCentre` aux deux familles restantes ; les tests
   `LogcatViewModelTest` / `SautPileLogcatEditorViewModelTest` restent
   verts ou portés avec équivalence démontrée (jamais supprimés pour
   verdir).
2. **feature:diagnostics consommera le lecteur** : l'arête
   feature:diagnostics → feature:sorties est INTERDITE aujourd'hui
   (règle `feature:*` inter-feature, ADR 0162) — soit l'écran Journal
   embarque le fragment via un point d'extension, soit l'arête est
   autorisée avec allow-list justifiée : à arbitrer.
3. **Le regroupement Logcat (×N, repli des traces)** réutilise la même
   mécanique de dérivation (une projection par famille de source) — la
   `ClassificationLigneGradle` est Gradle-spécifique : la dérivation
   Logcat aura la sienne (même patron).
4. **Le plafond du registre est réglable au L6** (M13) — les constantes
   (10 / 20 000 / 8 Mo) sont documentées dans
   `RegistreExecutionsFichiers`.

## 6. Risques ouverts

1. **Le banc S5 est armé mais non mesuré** : la dérivation classifiée
   (parcours pur, sans regex) est pensée pour le budget, mais le PV
   propriétaire reste le juge (ADR 0159 : « à mesurer, pas à goûter »).
2. **La racine d'expurgation vient du câble de sync** (`projectDir`) :
   un build lancé AVANT toute sync n'a pas de racine connue —
   l'expurgateur masque alors tout chemin absolu (honnête) ; si le
   propriétaire veut la racine dès le premier build, il faudra
   brancher `ProjectRepository` au collecteur (une ligne, mais une
   dépendance de plus côté editor).
3. **Le plafond de fenêtre du lecteur (2 000) borne la consultation** :
   une exécution persistée de 20 000 lignes se relit tronquée par la
   FENÊTRE (les pertes sont comptées, jamais silencieuses) — si le
   propriétaire veut relire une exécution pleine, la fenêtre du lecteur
   dédié (L5a) devra s'agrandir (réglage M13).
4. **Le déclencheur CI corrompu** (préexistant, hors lot) — inchangé.
5. **La CI complète n'était pas observable** au moment du rapport
   (push déclencheur `pull_request`).
