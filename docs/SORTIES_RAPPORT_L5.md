# Rapport de fin de lot L5 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L5 — Surfaces** (éditeur dédié
> en onglet et plein écran, panneau compact rebranché, volet tablette,
> points d'entrée contextuels et fil de l'incident) PUIS **suite du lot**
> (les quatre écarts du rapport L4 — spec SORTIES § 5.4 : regroupement
> Logcat ×N et repli des traces, puces d'étiquettes avec compteurs,
> pid/tid affichables, saut « erreur suivante / précédente »). Branches :
> `agent/sorties-l5-surfaces` (base `f6d68375`) puis
> `agent/sorties-l5-suite` (base = `main` après la fusion L0–L5, PR #3
> et #4 fusionnées). Dates : 2026-11-10 et 2026-11-11. Langue :
> français.

## 1. Fait (par sous-lot)

| Sous-lot | Livrable | Fichier(s) | État |
|---|---|---|---|
| **L5a** | L'éditeur dédié OUVERT EN ONGLET de la zone centrale (comme un fichier, ADR 0028) | `activity_editor.xml` (`conteneur_lecteur_central` dans `conteneur_editeur`), `EditorActivity` (`sortiesCentralActif`, `creerOngletSorties`, `selectionnerOngletSorties`, `activerSurfaceSorties`, `rendreOnglets`, menu `action_sorties`) | ✅ — un onglet « Sorties » DERNIER de la barre, TOUJOURS présent (même sans fichier : consultable d'un geste) ; sa position est DÉRIVÉE (nombre de fichiers), jamais stockée dans l'état du VM — les onglets du VM comptent des FICHIERS. Le lecteur (famille GRADLE, `FragmentLecteurSorties.nouvelle`) est ajouté UNE fois par tag stable ; hide/show, aucun rechargement de session ; l'état vide du projet se masque (le lecteur a le sien, honnête par famille). Sélection des onglets fichiers ⇄ sorties bidirectionnelle, état sauvegardé en instance (rotation — spec § 3.11) |
| **L5a** | L'éditeur dédié en PLEIN ÉCRAN (téléphone portrait, M7) | `EditeurSortiesActivity` (NOUVEAU), `activity_editeur_sorties.xml`, manifest, menu `action_plein_ecran_sorties` | ✅ — barre d'application minimale (retour + titre) et le lecteur TEL QUEL : recherche, filtres, pause, effacement, regroupé et consultation vivent DANS le lecteur (jamais un second chrome ni un second afficheur). NON exportée : le seul chemin est l'onglet central (action « Plein écran », visible seulement quand la surface est affichée) |
| **L5b** | PANNEAU COMPACT REBRANCHÉ — une fenêtre sur le MÊME composant | `PanneauConsoleFragment` (corps remplacé), `fragment_panneau_console.xml` (`conteneur_lecteur_panneau`) | ✅ — le panneau N'EMBARQUE PLUS de moteur à lui : les DEUX consoles `TextView` (ADR 0078) et leur vidage loti par trame sont SUPPRIMÉS (pas de code mort, pas de double affichage) ; le corps héberge le lecteur (famille GRADLE, fragment enfant, `commitNow` — le filtre part TOUT DE SUITE, jamais une fenêtre « tous les canaux » par défaillance d'ordonnancement). Un lecteur qui s'attache ne voit jamais deux fois la même ligne (cliché de publication, lot L3) |
| **L5b** | Le chip de canal pousse son filtre PAR SOURCE au lecteur | `PanneauConsoleFragment.pousserFiltreAuLecteur` | ✅ — Sync → `FiltreSorties(sources = {SYNC})`, Build → `{BUILD}` (parité exacte de l'ancienne console : le canal TACHES n'a pas de console) ; le tampon ne bouge pas, les lignes masquées sont comptées et annoncées par le lecteur (honnêteté règle 14) ; basculer ne perd rien, ne rejoue rien ; garde d'idempotence (une émission d'état Gradle ne repousse pas) |
| **L5b** | Chrome d'ACTIONS conservé à l'identique | chips Sync/Build (teintes, points d'actualité INC-20), Tâches, Configuration intégrée (page enfant + retour), bandeau d'échec PAR CANAL, annulation en vol, saisie stdin (v0.41.1), « Expliquer » IA | ✅ — « Expliquer » pré-remplit depuis les dernières rangées VISIBLES DU LECTEUR (`texteVisible(50)`, fenêtre bornée, jamais auto-envoyée) |
| **L5c** | Le VOLET tablette — à côté du code, jamais par-dessus (M8) | `activity_editor.xml` (`conteneur_editeur` devient une RANGÉE code \| sorties ; `zone_code` + `conteneur_lecteur_volet`), `EditorActivity` (`basculerVoletSorties`, `appliquerVoletSorties`, menu `action_volet_sorties`), `values[-sw600dp]/bools.xml` | ✅ — l'action de menu n'existe QUE sur grand écran (`sorties_volet_disponible`, sw600dp) : sur téléphone JAMAIS une entrée morte ; le volet ouvre le lecteur (famille GRADLE, tag stable, poids égaux face au code), l'état est sauvegardé en instance et l'entrée cochée dit la vérité |
| **L5d** | Le FIL DE L'INCIDENT — un seul geste, pas quatre onglets à fouiller | `FeuilleFilIncidentFragment` (NOUVEAU), `feuille_fil_incident.xml`, bandeau d'échec (`bouton_fil_incident`) | ✅ — depuis un build échoué : Sorties du build, Problèmes, Diagnostics et journaux du serveur (navigateur, ADR 0048), Logcat de la session — chaque ligne fait ce qu'elle dit ; le message d'échec voyage EN ARGUMENT (contexte, tronqué 3 lignes, la vérité complète vit dans les sorties) |
| **Transverse** | Strings FR/EN complètes pour toutes les nouvelles surfaces | `values/strings.xml`, `values-en/strings.xml` | ✅ |

**Nettoyages (pas de code mort)** : `EditorViewModel.lignesBrutesConsole`
supprimé (plus de consommateur — le rejeu console vit dans le
composant) ; `GradleService.lignesBrutes` CONSERVÉ (les tests du service
l'utilisent comme point d'observation du flux émis) ; sonde de vue
`SondeConsoleVue` réduite à T6 (images) — la mesure T5 du vidage loti
s'est ÉTEINTE AVEC le moteur qu'elle chronométrait (les budgets du
lecteur sont testés dans :feature:sorties) ; `EtapeConsoleSync`,
`FiltreCanalConsole`, `LignesConsoleTexte`, `AdaptateurConsoleSorties`,
`RangeesConsole` conservés — ce sont le VOCABULAIRE D'ÉMISSION (le
service et l'adaptateur vers le Centre), pas le moteur d'affichage.

## 1 bis. Fait — la suite du lot (session du 2026-11-11)

Les QUATRE écarts listés par le rapport L4 (spec SORTIES § 5.4 M5), sur
la branche `agent/sorties-l5-suite` (base = `main` à jour — PR #3
« L0–L3 » et #4 « L4 + surfaces L5 » fusionnées) :

| Sous-lot | Livrable | Fichier(s) | État |
|---|---|---|---|
| **L5-regroupement** | La DÉRIVATION REGROUPÉE LOGCAT — CLASSES PROPRES (`RegroupementSorties` reste Gradle-spécifique) | `core/domain/.../RegroupementLogcat.kt` (`TracesLogcat`, `GroupeLogcat.Repetition`/`Trace`, `EtatRegroupementLogcat`), `ProjectionRegroupeeLogcat.kt` | ✅ — runs de lignes IDENTIQUES répétées (identité message + étiquette + niveau, dès 2 occurrences : « ×37 ») et TRACES d'exception repliées (tête SUIVIE d'au moins un cadre, causes « Caused by » / « Suppressed » relevées) ; couverture stricte « chaque ligne une fois » (§ 10.1.1, testé) ; ancres CONSERVATRICES (une tête non reconnue reste isolée — dégradation honnête, jamais un repli sans raison) |
| **L5-regroupement** | La projection regroupée Logcat (le pendant § 4.2 de `ProjectionRegroupee`) | `ProjectionRegroupeeLogcat.deriver` | ✅ — en-tête devant la PREMIÈRE VISIBLE du groupe (elle la REPRÉSENTE — jamais ré-émise) ; repli ×N = en-tête seule ; repli de trace = en-tête + CAUSES toujours visibles (le « Caused by » porte le diagnostic — les cadres seuls quittent CETTE projection, jamais le tampon) ; compteurs honnêtes (`lignesVisibles`/`causesVisibles`) ; un groupe sans ligne visible n'existe pas (règle 14) |
| **L5-regroupement** | La bascule brut ↔ regroupé OUVERTE à la famille LOGCAT | `LecteurSortiesViewModel` (`modeDisponible` = GRADLE \| LOGCAT, `deriverRegroupeLogcat`, classifications maintenues GRADLE seul) | ✅ — le résumé de build et l'en-tête collant restent GRADLE (UI honnête — le Logcat n'a ni verdict ni tâche) ; la recherche est remappée sur les rangées visibles du regroupement (une ligne de groupe replié ne répond plus — même discipline que la vue Gradle) ; JOURNAL garde sa bascule cachée (testé) ; le repli d'un groupe re-projette EN UNE fois (`basculerRepli` inchangé) |
| **L5-regroupement** | L'en-tête Logcat dans l'adaptateur | `RangeeLecteur.EnteteLogcat`, `entete_logcat_sortie.xml` (NOUVEAU), `LignesSortiesAdapter` (3ᵉ type de vue + `EnteteLogcatViewHolder`) | ✅ — contenu = la ligne représentée (pré-parsée à l'ingestion), badge honnête (« ×N » ou « N lignes · k causes »), chevron, tête d'erreur colorée (rôle erreur), TalkBack (contenu, badge, compteur, état du repli) ; le MÊME geste `surEnteteAppuyee` bascule le repli |
| **L5-puces** | Les PUCES D'ÉTIQUETTES avec compteurs (inclure/exclure) | `LecteurSortiesViewModel.etiquettesComptees` / `basculerEtiquette` + helpers purs (`etiquettesCompteesInterne`, `cyclerEtiquette`, `etatPuceEtiquette`, `BORNE_ETIQUETTES` = 24), menu « # » du fragment (LOGCAT/JOURNAL) | ✅ — comptage au GESTE (un passage sur la fenêtre quand le menu s'ouvre — JAMAIS dans le chemin chaud de l'ingestion), tri compte décroissant puis alphabétique, borné 24 ; CYCLE neutre → incluse → exclue → neutre par COPY du filtre (le niveau poussé par le chrome SURVIT au cycle — testé) ; le menu se reconstruit à chaque bascule (les états sont relus du filtre — la vérité du filtre) et se rouvre (le cycle multi-étiquettes reste un geste par bascule) ; aide du cycle en tête d'entrée (désactivée) |
| **L5-pid/tid** | pid/tid AFFICHABLES (§ 5.4) | `LigneSortie.pid`/`tid` (les deux MONTENT — l'adaptation Logcat les porte, la clé de session ne bouge pas), `RangeeSortie`, `prefixePidTid` (pur), option `affichePidTid` + bouton « pid » (LOGCAT seul, état actif accentué), TalkBack annonce TEL QU'AFFICHÉ | ✅ — `null` hors Logcat : l'option ne fabrique JAMAIS un pid inventé (l'absence reste absente, règle 14) |
| **L5-saut** | Le SAUT « erreur suivante / précédente » | `LecteurSortiesViewModel.naviguerErreur` + `cibleErreur` (pur, testé), boutons de la barre (teinte erreur, visibles SEULEMENT quand des erreurs existent — jamais un geste mort) | ✅ — le départ est la première rangée VISIBLE du lecteur, le parcours BOUCLE (un « suivant » depuis la dernière erreur revient à la première) ; fonctionne en BRUT comme REGROUPÉ (les repères d'erreurs sont des rangées de projection) et pour TOUTES les familles — le Logcat est simplement le premier consommable § 5.4 |

## 2. Preuves (commandes lancées et résultats)

- **Compilation** : `:feature:editor:compileDebugKotlin` → BUILD
  SUCCESSFUL (KSP Hilt compris — la feuille incident injecte le
  navigateur, l'éditeur héberge trois lecteurs : central, volet,
  panneau).
- **Tests** : `:feature:editor:testDebugUnitTest` → BUILD SUCCESSFUL
  (tous, dont les tests de layout PORTÉS — voir ci-dessous) ;
  `:feature:sorties:testDebugUnitTest`, `:core:domain:test`,
  `:feature:diagnostics:testDebugUnitTest` → BUILD SUCCESSFUL (aucune
  régression sur le composant et ses consommateurs).
- **Chaîne de vérification** : `spotlessApply` puis
  `spotlessCheck detekt checkModuleDependencies checkUiTokens` →
  BUILD SUCCESSFUL (aucune remontée ; aucune arête inter-modules
  nouvelle — toutes les surfaces vivent dans `feature:editor`, qui
  dépend déjà de `feature:sorties` allow-listé L3).
- **Environnement** : bac à sable recyclé en cours de lot (dépôt,
  caches Gradle et `.m2` perdus) — reconstruits : clone, `local.properties`,
  JDK 21, SDK `/tmp/my-project/android-sdk`, miroirs Tencent (init.d du
  lot L4) ; trois artefacts Robolectric `android-all-instrumented`
  (API 26, 13/33, 17) retéléchargés DU MÊME miroir vers `~/.m2`
  (aucune vérification désactivée, aucun dépôt changé).

### Suite du lot (session du 2026-11-11, bac à sable re-purgé puis reconstruit)

- **Tests du domaine** : `:core:domain:test` → BUILD SUCCESSFUL
  (783 tests, dont les NOUVEAUX `RegroupementLogcatTest` — ancres,
  répétitions, identité, traces complètes, têtes seules, cadres sans
  tête, couverture stricte — et `ProjectionRegroupeeLogcatTest` —
  en-tête représentative, repli ×N, causes visibles, groupe masqué,
  première visible) ; `AdaptateursSortiesTest` étendu (pid/tid).
- **Tests du lecteur** : `:feature:sorties:testDebugUnitTest` → BUILD
  SUCCESSFUL (dont les NOUVEAUX `LecteurLogcatLot5Test` — bascule
  regroupée Logcat, repli ×N, trace repliée avec causes, JOURNAL
  caché, saut d'erreur en boucle, cycle d'étiquette avec filtre du
  chrome conservé, suggestions bornées, helpers purs).
- **Consommateurs** : `:feature:editor:compileDebugKotlin` et
  `:feature:diagnostics:compileDebugKotlin` → BUILD SUCCESSFUL (aucune
  arête nouvelle ; les boutons de la barre vivent DANS le fragment du
  lecteur, les hôtes ne changent pas).
- **Chaîne de vérification** : `spotlessApply` puis `spotlessCheck
  detekt checkModuleDependencies checkUiTokens` → BUILD SUCCESSFUL
  (deux refactors detekt : la dérivation Logcat sans sauts multiples
  dans sa boucle — `consommerGroupe` ; `brancherBarre` ramenée sous le
  seuil par extraction des gestes du lot).
- **Environnement** : bac à sable RE-PURGÉ en cours de session (dépôt,
  caches Gradle et `.m2`, JDK et SDK perdus) — reconstruits : clone,
  `local.properties`, JDK 21 Temurin (`scripts/setup-env.sh`), SDK
  `/home/z/android-sdk`, frères en COMPOSITE (`-Pfreres.sources` :
  LayoutLib, codeide-tooling, codeide-lsp, code-lsp — clones locaux,
  `local.properties` par frère) et les coordonnées binaires des frères
  publiées en `mavenLocal` (`:kotlin:publishToMavenLocal`,
  `:ecj-art:publishToMavenLocal` — le chemin « binaire en premier » du
  settings, sans jeton Packages). Miroir Tencent injecté LOCALEMENT en
  tête des dépôts (les 429 de gradle.org/maven central depuis ce bac à
  sable) — MODIFICATIONS NON COMMITÉES (les `settings.gradle.kts` du
  dépôt et des frères restent intacts sur la branche ; aucune
  vérification désactivée, un artefact Robolectric retéléchargé du
  MÊME miroir vers `~/.m2`).

## 3. Tests portés (jamais supprimés pour verdir)

- `LecteurSortiesViewModelTest. la famille vient de l'argument et le
  mode regroupé suit le contrat` — le test d'origine (`modeDisponible ==
  false` hors GRADLE) est PORTÉ au contrat du lot L5 : LOGCAT ADMIS
  (dérivation ×N et traces), JOURNAL toujours refusé ET la bascule ne
  fait rien — l'assertion d'origine n'est pas supprimée, elle est
  réécrite pour le contrat nouveau (la spécification change,
  l'honnêteté reste).
- `ProjectionRegroupeeLogcatTest` — la couverture « chaque ligne une
  fois » est vérifiée EXACTEMENT tout déplié ; replis fermés, la
  couverture devient SOUPLE (les lignes repliées sont omises de CETTE
  projection — jamais du tampon) : le contrat du repli Gradle, porté.
- `ActivityEditorLayoutTest.les fragments du panneau se gonflent…` —
  les quatre assertions sur les consoles `TextView`
  (`texte_console_sync/build`, `defilement_console_*`) sont PORTÉES à
  `conteneur_lecteur_panneau` (le conteneur du lecteur, L5b) — le
  lecteur EST gonflé par son fragment, ses vues vivent dans
  `fragment_lecteur_sorties` (feature:sorties, déjà testées).
- `SondeConsoleVueTest` — les DEUX tests T5 (lot du vidage) se sont
  ÉTEINTS AVEC le moteur qu'ils chronométraient (suppression justifiée
  : la fonction `lot()` n'existe plus ; le lecteur a ses propres
  budgets et ses propres tests — `RangeesSortiesTest`, budgets
  d'ingestion) ; les tests T6 restent VERTS à l'identique.
- `ActivityEditorLayoutTest. la console enrichie porte son sélecteur…`
  et `la console embarque le conteneur de la configuration intégrée` —
  VERTS SANS MODIFICATION (chips, Tâches, bandeau, stdin, config :
  tout est resté en place).

## 4. Ce qui reste / écarts avec le plan

- **Sortie du build depuis la NOTIFICATION de fin** (§ 5.7, points
  d'entrée) : la notification du tooling ouvre déjà l'espace
  (RoutageInterEcrans), mais une ACTION dédiée « Voir les sorties »
  exige de faire traverser un extra SÉLECTEUR DE SURFACE par le routeur
  (`:app` + `core:domain`) — décision d'architecture à prendre
  (nouvel « écran cible » ou extra de l'éditeur ?) ; reporté à L6 avec
  la section Réglages. Le besoin fonctionnel n°6 (diagnostics,
  journaux serveur et trace du plantage en deux gestes) est COUVERT
  par le fil de l'incident.
- **Entrée d'historique des exécutions du registre** (note du banc
  L2 : « l'entrée d'historique arrive au lot L5 ») : la CONSULTATION
  (rejeu) existe (banc + lecteur) et l'onglet central rend l'éditeur
  dédié toujours joignable, mais une LISTE des 10 dernières
  exécutions du registre (ADR 0161) n'a pas de surface dédiée — le
  critère n°3 (les 10 derniers builds consultables après redémarrage)
  passe par le rejeu du banc (diagnostic) ; une entrée « historique »
  dans l'onglet central est proposée pour L6 (décision : feuille ou
  rangée d'en-tête ?).
- **Les quatre écarts du rapport L4 sont FERMÉS** (section 1 bis) :
  regroupement Logcat ×N + repli des traces (§ 5.4 M5), puces
  d'étiquettes avec compteurs, pid/tid affichables, saut « erreur
  suivante / précédente ». Le Journal reste sans dérivation regroupée
  (pas demandé § 5.5 — la bascule y reste cachée, testé).

## 5. Décisions à prendre (propriétaire)

1. **Sélecteur de surface par notification** (écart ci-dessus) :
   faire traverser « ouvrir les sorties » par le routage d'écrans, ou
   rester sur l'ouverture de l'espace seule ?
2. **Poids du volet tablette** : égalité code/sorties (50/50, livré)
   ou une poignée ajustable (patron du tiroir) à L6 ?

## 6. Mots de la fin

Le composant unique tient ses promesses : une MÊME fenêtre (le
lecteur) sert maintenant CINQ surfaces — onglet central, plein écran,
panneau compact, volet tablette, écran Journaux de diagnostics — et
l'ancienne console (le dernier moteur d'affichage en double) a été
démontée dans le même lot que son remplacement. La suite du lot a
porté le lecteur Logcat à la PARITÉ COMPLÈTE de § 5.4 : le regroupement
×N et le repli des traces vivent dans des CLASSES PROPRES (le
vocabulaire de chaque famille, jamais un routage partagé), les puces
d'étiquettes disent le contenu de la fenêtre avec ses compteurs,
pid/tid s'affichent sur option, et les erreurs se naviguent en boucle.
La suite (L6) : réglages, accessibilité, mesure appareil,
documentation — et les deux décisions d'entrée ci-dessus (sélecteur de
surface par notification, entrée historique).
