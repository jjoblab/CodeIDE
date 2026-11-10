# Rapport de fin de lot L4 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L4 — Logcat et Journal migrés**
> (les deux sources passent sur le composant unique, parité stricte
> R3/R4, l'écran Journaux de `feature:diagnostics` consomme le lecteur).
> Branche : `agent/sorties-l4-logcat-journal` (base `623a8482`, tête de
> `agent/sorties-l3-gradle-historique`). Date : 2026-11-10. Langue :
> français.

## 1. Fait (par livrable)

| Livrable | Fichier(s) | État |
|---|---|---|
| 1. Familles de sources et fabrique du flux (le lecteur choisit SA famille) | `feature/sorties/.../FamillesSorties.kt` (`FamilleSorties`, `FabriqueFluxSorties`, `FENETRE_LOGCAT=5 000`, `FENETRE_JOURNAL=10 000`, `CLE_FAMILLE`) | ✅ — une implémentation `FluxSorties` PAR FAMILLE (jamais un routage partagé) : deux lecteurs simultanés (onglet Logcat + onglet Journal du même panneau) ne peuvent pas se contaminer ; le port gagne `generation: StateFlow<Int>` — chaque changement de source derrière le port est un signal de RESET honnête (jamais deux sources mélangées dans un tampon) |
| 2. Le lecteur devient multi-familles (VM/fragment scopés) | `LecteurSortiesViewModel` (fabrique + `SavedStateHandle`, famille par argument de fragment — survit à la rotation), `FragmentLecteurSorties` (public, `nouvelle(famille)`, VM scopé au fragment, façade `ControleurLecteurSorties`), `BancSortiesActivity` adapté | ✅ — le mode REGROUPÉ n'est proposé QUE pour GRADLE (`modeDisponible` — la bascule disparaît ailleurs : honnêteté, jamais une bascule qui prétend regrouper sans le faire) ; l'état vide est branché PAR FAMILLE (strings `sorties_vide_*_logcat/_journal` — le Logcat dit « sans adb », jamais le message Gradle) ; le VM vide son état vivant au changement de GÉNÉRATION (testé) |
| 3. Source JOURNAL derrière le port | `feature/sorties/.../SourceJournalSorties.kt` (`CollecteurSortiesJournal`, `FluxSortiesJournal`) | ✅ — parité ADR 0025 de la fusion : l'historique est lu UNE fois (`readAll`), `observeRecent` fusionne par dédoublonnage d'égalité (jamais une entrée deux fois), la fusion démarre APRÈS l'historique (ordre chronologique = vérité du disque), échec de lecture HONNÊTE (`lectureEnEchec` — jamais muet) |
| 4. Source LOGCAT derrière le port (logique de sessions portée à l'identique) | `feature/sorties/.../SourceLogcatSorties.kt` (`CollecteurSortiesLogcat`, `FluxSortiesLogcat`, `SelectionLogcat`, `EtatSessionsLogcat`, `PlantageLogcat`) | ✅ — autosélection vivante (choix utilisateur qui prime, vivante née qui déloge l'archive), archivage des fins UNE fois avec dernière ligne, rattrapage « zéro course » par delta à CHAQUE réémission de sessions (leçon R3), plantage R4 publié UNE fois par session ; la PAUSE vit dans le lecteur (le tampon ingère, la vue fige) — la source continue de collecter, rien ne peut être perdu pendant une pause |
| 5. L'onglet LOGCAT du panneau passe sur le lecteur (parité R3/R4) | `PanneauSortiesLogcatFragment` + `fragment_panneau_sorties_logcat.xml` (NOUVEAUX) ; `PanneauLogcatFragment`, `LignesLogcatAdapter`, `LogcatViewModel`, `FiltreLogcat` (core:domain) + tests SUPPRIMÉS | ✅ — chrome conservé : puce de processus (menu vivantes/terminées/précédentes, ampoule), filtre de niveau (→ `FiltreSorties` ADR 0160, rien n'est réécrit), « Expliquer » IA (dernières rangées VISIBLES du lecteur), bandeaux pertes/arrêt/précédente ; le SAUT DES TRACES (R4) vit dans le geste HÔTE (`surLigneSansPosition` → sélecteur de cadres → `ActionEditor.SauterVersLigneSource` — l'éditeur n'est jamais connu du lecteur) ; le snackbar « L'application a planté » observe le collecteur process-wide (`EditorActivity.surveillerPlantage`) ; l'onglet garde le badge « aucun badge » du flux vivant |
| 6. L'onglet JOURNAL du panneau passe sur le lecteur | `PanneauSortiesJournalFragment` + `fragment_panneau_sorties_journal.xml` (NOUVEAUX) ; `PanneauJournalFragment`, `EntreesJournalCompactesAdapter` SUPPRIMÉS | ✅ — chips de niveaux persistées (`filtresJournal` du VM de l'espace — `SavedStateHandle`) poussées au lecteur comme filtre unifié (ensemble vide = tous) ; lien « journal complet » conservé ; le VM ne garde plus de fenêtre d'affichage (PAS de second afficheur — `entreesJournal`/`observerJournal`/`rafraichirJournal` supprimés) |
| 7. L'écran Journaux de `feature:diagnostics` CONSOMME le lecteur | `feature/diagnostics/.../JournalFragment.kt`, `JournalViewModel.kt`, `EtatJournal.kt`, `page_journal.xml` reworked ; `EntreesJournalAdapter`, `item_entree_journal.xml`, `dialogue_detail_entree.xml` SUPPRIMÉS ; `ModuleRulesPlugin` (`:feature:diagnostics` → `+:feature:sorties`), `build.gradle.kts` | ✅ — ADR 0162 décision 1 (consommateur) : la visionneuse est le lecteur (recherche n/N insensible aux accents, pause, effacement, replis, état vide honnête) ; le chrome garde les ACTIONS : filtres niveaux persistés poussés au lecteur, verbosité (persiste puis applique), partage/enregistrement zip, usage disque, effacement (le dépôt ET le tampon du lecteur — effet `Efface`) ; bandeau honnête si la lecture d'historique échoue ; l'arête est allow-listée avec SA justification dans le tableau |
| 8. L'exception aplatie du journal ne se perd plus | `core/domain/.../AdaptateursSorties.kt` (`LogEntry.versLigneSortie`) | ✅ — règle 14 : l'écran Diagnostics n'a plus de « journal complet » à côté du lecteur, donc le détail de l'exception (classe, message, 5 tranches par cause, 10 causes « Caused by: » — les bornes du détail d'origine) PART DANS LE MESSAGE : le lecteur le plie, la recherche le retrouve ; testé (le message sans exception reste EXACTEMENT celui de l'entrée) |
| 9. Tests — parité portée, jamais supprimée pour verdir | `CollecteurSortiesLogcatTest` (11), `FluxJournalSortiesTest` (4), `LecteurSortiesViewModelTest` (+2 = 11), `JournalViewModelTest` (7 portés), `PanneauxSortiesLayoutTest` (2, Robolectric — layouts des deux chromes) | ✅ — les 11 scénarios de `LogcatViewModelTest` sont portés à l'identique sur le collecteur + le lecteur branché au flux logcat (tests 4-7 exercés SUR LE LECTEUR : pause/effacement/filtres — équivalence démontrée, le fichier de test porte la carte des ports) ; les scénarios d'affichage du Journal sont portés à leur nouveau détenteur (fusion → collecteur, fenêtre → tampon, recherche → filtre unifié, suivi → bouton « ⇣ suivre ») — chaque test de VM porte le pointeur vers le test d'équivalence |

**Bug L3 attrapé au lot** : `demandeOuverture` était collecté DEUX fois dans
`FragmentLecteurSorties` (`brancherEtats` + `brancherRegroupement`) — chaque
lien source ouvrait DEUX sauts (ou deux snackbars sans éditeur). Corrigé au
commit de la façade (le collecteur double supprimé).

**Régression attrapée par les tests du lot** : le test d'intégration
`FermetureProjetEspaceTest` a échoué après le passage du panneau — les
chromes n'avaient pas le droit d'attacher `EditorViewModel` à la portée du
FRAGMENT (`by viewModels()` — fabrique non-Hilt, réflexion impossible) ;
retour au patron d'origine `activityViewModels()` (le VM de l'ESPACE est un
par activité par design). Les tests du lot travaillent.

## 2. Preuves (commandes lancées et résultats)

- **Chaîne de vérification** (STRATEGIE_VERIFICATION § 2 — JDK 21,
  Gradle 9.7.1, miroirs du bac à sable) :
  `./gradlew spotlessCheck detekt checkModuleDependencies checkUiTokens
  :core:domain:test :feature:sorties:testDebugUnitTest
  :feature:editor:testDebugUnitTest :feature:diagnostics:testDebugUnitTest`
  → **BUILD SUCCESSFUL** (detekt `maxIssues 0` sur TOUS les modules —
  dette structurelle des lots antérieurs découverte et corrigée au
  passage : `SerialisationExecutions` 22 fns, `RegistreExecutionsFichiers`
  13 fns, `ProjectionRegroupee.deriver` complexité 17, `ContenuFactice`
  — suppressions ciblées justifiées règle 16, zéro seuil désactivé ;
  frontières inter-modules vertes AVEC la nouvelle arête
  `feature:diagnostics → feature:sorties` allow-listée ; cliquet jetons
  sans remontée — R4/R5 corrigés AU COMMIT par styles maison
  `Widget.CodeIDE.*`).
- **Tests** : chaîne verte —
  `CollecteurSortiesLogcatTest` 11 (dont les 11 scénarios R3/R4 portés +
  le test de génération) · `FluxJournalSortiesTest` 4 ·
  `LecteurSortiesViewModelTest` 11 (+2 nouveaux : famille par argument,
  reset par génération) · `JournalViewModelTest` 7 (portés) ·
  `PanneauxSortiesLayoutTest` 2 · suites existantes vertes : core:domain
  (763+, dont l'aplatissement d'exception), feature:editor (542+),
  feature:sorties (61+), feature:diagnostics (14).
- **`app:testDebugUnitTest`** : vert SAUF `ModelesEmbarquesTest` (10
  échecs) — **préexistant à la base de la branche** (chantier templates :
  « source `commun:gitattributes.tpl` introuvable ») : nos 23+6 commits ne
  touchent AUCUN fichier templates, et les correctifs sont déjà poussés
  sur `main` par le chantier générateur (`61aba843`, `e3bfa970`) — ils
  rentreront à la fusion. La régression `FermetureProjetEspaceTest`
  (parmi les échecs initiaux du run complet) était NOTRE faute et est
  corrigée (voir § 1, régression attrapée).
- **Environnement** : l'artefact Robolectric `android-all-instrumented
  14-robolectric-10818077-i7` est absent du cache du bac à sable et
  `repo1.maven.org` renvoie des 429 — résolu par le téléchargement DU
  MÊME artefact depuis le miroir Tencent vers `~/.m2` (aucun changement
  de dépôt, aucune vérification désactivée).

## 3. Ce qui reste / écarts avec le plan

- **Regroupement Logcat ×N et repli des traces avec cause** (§ 5.4 M5) :
  la bascule brut ↔ regroupé est cachée pour les familles LOGCAT/JOURNAL
  (UI honnête) — la dérivation dédiée (même patron que
  `RegroupementSorties`) reste un ajout planifié L5/L6.
- **Puces d'étiquettes avec compteurs inclure/exclure** : le FILTRE les
  porte déjà (`FiltreSorties.etiquettesInclues/Exclues` — ADR 0160), il
  manque l'UI des puces (§ 5.4).
- **pid/tid affichables** (§ 5.4) : l'adaptation L1 les absorbe dans la
  clé de session (`paquet/pid/processus`) ; l'affichage dédié demande un
  champ modèle — addition planifiée.
- **Saut « erreur suivante/précédente » logcat** (§ 5.4) : les repères
  d'erreurs existent déjà dans la poignée du lecteur ; la navigation
  dédiée reste à faire.
- **Recherche du Journal : le TEXTE n'est plus persisté après la mort du
  processus** (il survit à la rotation via le lecteur) — l'ancien champ
  persisté au `SavedStateHandle` a laissé la place à la recherche n/N du
  lecteur (choix assumé : une seule recherche, celle du composant).
- **Détail d'entrée (dialogue)** : remplacé par le repli des messages
  multi-lignes + sélection/copie du lecteur ; le fil (threadName) se perd
  sciemment dans l'adaptation (déjà documenté L1) et reste consultable
  sur l'export zip.

## 4. Décisions à prendre / pour L5

- **Arête feature:diagnostics → feature:sorties** : allow-listée au lot
  (ADR 0162 décision 1) — le tableau de `ModuleRulesPlugin` porte la
  justification ; rien à trancher.
- **La fabrique du flux vit dans feature:editor** (`FabriqueFluxSortiesEditeur`
  — l'interface est publique dans feature:sorties) : à la fusion, si
  feature:diagnostics devait servir SANS feature:editor (futur découpage),
  l'implémentation de la fabrique migre vers `app` (un @Binds de plus).
- **L5** : surfaces (onglet central, plein écran, panneau compact
  rebranché = CONSOLE, volet tablette, points d'entrée) — le lecteur est
  prêt pour les trois familles ; la console (famille GRADLE) reprendra le
  même chemin que les deux onglets de ce lot.
