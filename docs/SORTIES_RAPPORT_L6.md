# Rapport de fin de lot L6 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L6 — Réglages, finitions,
> mesure appareil, documentation** (spec SORTIES § 5.10, § 7, § 8).
> Branche : `agent/sorties-l6` (base = `main` après la fusion L5, PR #5
> fusionnée). Date : 2026-11-11. Langue : français.

## 1. Fait (par livrable)

| Livrable | Contenu | Fichier(s) | État |
|---|---|---|---|
| **M13 — Réglages « Sorties »** | Huit réglages persistés à EFFET RÉEL : rétention du registre (3–30, défaut 10 = ADR 0161), fenêtres des tampons Logcat (2 000/5 000/20 000) et Journal (2 000/10 000/30 000), horodatage par défaut (masqué/relatif/absolu), police du lecteur (10/12/16 sp), retour à la ligne, couleurs ANSI, filtre Logcat à l'ouverture (tout/avert.+ /erreurs) | `core/model/.../AppSettings.kt` (champs `sorties_*`), `core/model/.../ReglagesSorties.kt` (énumérations), `core/datastore/.../SettingsDataStore.kt` (clés + projection BORNÉE à la lecture, patron banc ADR 0152), `feature/settings/.../SortiesSectionFragment.kt` + `fragment_settings_sorties.xml` (deux cartes), `SettingsViewModel.kt` (`traiterReglageSorties`) | ✅ |
| **Le port des réglages côté composant** | `ReglagesLecteurPort` (rendu initial, capacité, filtre initial, persistance du rendu) — `feature:sorties` ne voit PAS `core:datastore` (arêtes propres, ADR 0162) ; impl `ReglagesLecteurDataStore` (cliché en mémoire, jamais une lecture disque au thread UI) liée côté :app | `feature/sorties/.../ReglagesLecteur.kt`, `app/.../sorties/ReglagesLecteurDataStore.kt`, `app/.../di/DomainBindingsModule.kt` | ✅ |
| **La fenêtre réformable** | `TamponSorties.reformerFenetre` : la fenêtre se réforme à l'ouverture du lecteur ; une réduction tronque la tête IMMÉDIATEMENT et COMPTE les pertes (règle 14) ; un agrandissement ne ressuscite rien | `core/domain/.../TamponSorties.kt` | ✅ |
| **L'ANSI désactivable** | `OptionsRenduRangee.couleursAnsi` : rendu MONOCHROME honnête (le gras/italique survivent, le texte reste purgé des séquences, jamais brut) | `feature/sorties/.../RangeesSorties.kt`, `LignesSortiesAdapter.kt` | ✅ |
| **La persistance des gestes** | les gestes du lecteur PORTEURS D'UN DÉFAUT (horodatage, retour à la ligne, ANSI) se persistent au fil de l'eau (feu et oubli) ; le zoom de police reste un geste de SESSION — la section Réglages ne propose que des presets, elle ne ment jamais | `LecteurSortiesViewModel.regler`, `ReglagesLecteurDataStore.sauvegarderRendu` | ✅ |
| **Notification « Voir les sorties » (§ 5.7)** | `ECRAN_SORTIES` (valeur canonique core:domain) ; la notification de RÉSULTAT porte une action dédiée (jamais sur les progressions, code de requête distinct) ; l'hôte ouvre l'éditeur du projet SUR l'onglet central (`openEditorSurSorties` + extra `EXTRA_SURFACE_SORTIES` consommé une fois par `EditorActivity` — un espace VIVANT est remonté tel quel, décision documentée) | `core/domain/.../RoutageInterEcrans.kt`, `feature/editor/.../ToolingService.kt` (`intentionSorties`), `EditorActivity.kt`, `core/ui/.../AppNavigator.kt`, `app/.../navigation/AppNavigatorImpl.kt`, `MainActivity.kt` (`routerProjetCible`), strings `tooling_service_voir_sorties` FR/EN | ✅ |
| **Entrée historique (M11, ADR 0161)** | bouton « Historique » (GRADLE seul) de la barre du lecteur → `FeuilleExecutionsFragment` (nature, tâches, verdict, date relative, durée, volume, troncature annoncée) ; le tap relit `ouvrirLignes` et passe le lecteur en CONSULTATION (ADR 0161.5 — `ouvrirExecution` existait, il fallait la surface) ; le bandeau de consultation devient le geste « revenir au direct » (contentDescription TalkBack) ; effacement total EXPLICITE et confirmé | `feature/sorties/.../FeuilleExecutionsFragment.kt`, `feuille_executions.xml`, `rang_execution.xml`, `FragmentLecteurSorties.kt` (`ouvrirExecutionPersistee`), `fragment_lecteur_sorties.xml` (`bouton_historique`) | ✅ |
| **La rétention réglée** | `RegistreExecutionsFichiers` lit `sortiesExecutionsRetenues` à CHAQUE `demarrer`/`conclure` (borné 3–30 à la lecture, repli ADR = 10 sur échec de lecture) — le réglage fin promis par le KDoc du lot L3 | `app/.../sorties/RegistreExecutionsFichiers.kt` | ✅ |
| **Accessibilité (§ 7 L6)** | cibles ≥ 48 dp (bouton historique), rangée d'exécution wrap_content à l'échelle 200 % (test gonflé à `fontScale=2`), bandeau de consultation cliquable + décrit, parité des clés FR/EN testée ; la section « Sorties » entre dans la régression de chevauchement des sections Paramètres | `LecteurAccessibiliteLot6Test`, `SectionsParametresLayoutTest` (liste étendue) | ✅ |
| **PV de mesure appareil (§ 8)** | le PV à remplir par le propriétaire : conditions, budgets B1–B7 (méthode exacte par ligne), accessibilité A1–A4, écarts et suites | `docs/PV-MESURE-SORTIES.md` | ✅ (à remplir sur appareil) |
| **Documentation** | `CHANGELOG.md` (section 0.215.0), `docs/ROADMAP.md` (chantier livré), `docs/SORTIES_SPEC.md` § 11 (avancement des lots), `AGENTS.md` (état d'avancement) | idem | ✅ |
| **Strings FR/EN** | toutes les nouvelles clés (section Réglages, feuille historique, verdicts, bandeau consultation, notification) — parité vérifiée par test | `values/`, `values-en/` des trois modules | ✅ |

## 2. Preuves (commandes lancées et résultats)

- **Tests** : `:core:domain:test` + `:feature:sorties:testDebugUnitTest`
  + `:feature:settings:testDebugUnitTest` → BUILD SUCCESSFUL —
  **919 tests, 0 échec** (dont les NOUVEAUX `LecteurReglagesLot6Test`
  (7 : rendu initial, fenêtre réformée + cas défaut, filtre initial
  Logcat + hors-Logcat, persistance, tampon réformable),
  `LecteurAccessibiliteLot6Test` (4) et la régression des sections
  étendue) ; 136 tests feature après refactors — 0 échec.
- **Compiles** : `:app:compileDebugKotlin` (graphe Hilt valide — le
  binding `ReglagesLecteurPort` résout, KSP complet),
  `:feature:settings:compileDebugKotlin`, `:core:ui:compileDebugKotlin`,
  `:feature:editor:compileDebugKotlin`, `:feature:diagnostics:compileDebugKotlin`
  → BUILD SUCCESSFUL.
- **Chaîne §2 de `STRATEGIE_VERIFICATION.md`** (modules touchés) :
  `spotlessApply` puis `spotlessCheck detekt checkModuleDependencies
  checkUiTokens` → BUILD SUCCESSFUL (la baseline `checkUiTokens` ne
  remonte PAS — le bouton d'effacement porte le jeton
  `Widget.CodeIDE.Button.Text`) ; jamais d'`assembleDebug`, jamais de
  `lintDebug`, jamais de test de module non touché.
- **Correctif de domaine découvert PAR les tests** : un premier lot
  plus grand que la fenêtre d'un tampon VIDE (rafale, réforme L6)
  rendait `LotTampon.depart` négatif → IndexOutOfBounds à l'ingestion
  (la projection restait vide). `depart` est clampé à 0 — toutes les
  lignes retenues sont nouvelles ; testé.
- **Environnement** : bac à sable re-purgé en cours de lot (JDK, SDK,
  mavenLocal) — reconstruits : Temurin 21 (`/home/z/tools/jdk-21`), lien
  SDK, artefacts frères `jo-codeide-lsp-kotlin` / `jo-codeide-lsp-java-ecj-art`
  1.13.0 remis en mavenLocal depuis le gradle-home conservé, miroir
  Tencent + repo.gradle.org AVANT central dans le settings du frère
  `codeide-tooling` (429 — LOCAL, NON COMMITÉ), artefact Robolectric
  SDK 34 retéléchargé du même miroir. Aucune vérification désactivée.

## 3. Écarts avec le plan, et pourquoi

- **La « persistance du rendu » est PARTIELLE par design** : horodatage,
  retour à la ligne et ANSI se persistent ; numéros, pid/tid et zoom de
  police restent des choix de session (comme les lots L2–L5). Raison :
  la section Réglages propose des PRESETS — un zoom continu persisté
  rendrait l'écran Réglages mensonger (aucun bouton coché pour 14 sp).
  Le commentaire du lot L5 (« réglage persistant au lot L6 ») est
  honoré pour les réglages porteurs d'un défaut.
- **Le poids du volet tablette** (décision du rapport L5 §5.2) reste
  50/50 : la poignée ajustable n'appartient à AUCUNE liste du lot L6
  (spec § 7 L6) — décision reportée, réversible.
- **Version** : `version.properties` reste 0.214.0 — la spec impose le
  bump « à l'intégration seulement » (`scripts/bump-version.sh`) ; le
  CHANGELOG annonce la section 0.215.0 à poser.

## 4. Ce qui n'a pas pu être vérifié (en particulier : appareil)

- **La séance de mesure appareil n'a PAS été exécutée** (aucun appareil
  ici) : les budgets B1–B7 et les contrôles A1–A4 du PV
  (`docs/PV-MESURE-SORTIES.md`) attendent le propriétaire — les critères
  § 8 (10 000 lignes/s, mémoire plafonnée, latence non régressée ADR
  0152) ne sont PAS clos sans elle. C'est le seul écart de vérification
  du chantier.
- La notification de résultat et son action sont testées au niveau des
  intentions construites ; le comportement système (ombrage, canaux) se
  vérifie sur appareil.

## 5. Décisions à prendre pour le lot suivant

Aucune — le chantier « Centre de sorties » est TERMINÉ (L0→L6). Les
deux décisions ouvertes du rapport L5 sont closes :
1. **Sélecteur de surface par notification** → un écran cible
   `sorties` (canonique core:domain), l'onglet central « Sorties » ;
   un espace vivant est remonté tel quel (l'onglet y est toujours
   présent).
2. **Entrée historique** → une feuille (bottom sheet) depuis la barre
   du lecteur Gradle ; la consultation passe par le chemin ADR 0161.5
   existant.
Le poids du volet tablette (50/50 ou poignée) reste la SEULE décision
ouverte, hors périmètre des lots livrés.
