# Rapport de fin de lot L5 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L5 — Surfaces** (éditeur dédié
> en onglet et plein écran, panneau compact rebranché, volet tablette,
> points d'entrée contextuels et fil de l'incident). Branche :
> `agent/sorties-l5-surfaces` (base `f6d68375`, tête de
> `agent/sorties-l4-logcat-journal`). Date : 2026-11-11. Langue :
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

## 3. Tests portés (jamais supprimés pour verdir)

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
- **Regroupement Logcat ×N et repli des traces avec cause** (§ 5.4
  M5) : inchangé depuis L4 (planifié L5/L6, bascule cachée hors
  GRADLE — UI honnête).
- **Entrée d'historique des exécutions du registre** (note du banc
  L2 : « l'entrée d'historique arrive au lot L5 ») : la CONSULTATION
  (rejeu) existe (banc + lecteur) et l'onglet central rend l'éditeur
  dédié toujours joignable, mais une LISTE des 10 dernières
  exécutions du registre (ADR 0161) n'a pas de surface dédiée — le
  critère n°3 (les 10 derniers builds consultables après redémarrage)
  passe par le rejeu du banc (diagnostic) ; une entrée « historique »
  dans l'onglet central est proposée pour L6 (décision : feuille ou
  rangée d'en-tête ?).

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
démontée dans le même lot que son remplacement. La suite (L6) :
réglages, accessibilité, mesure appareil, documentation.
