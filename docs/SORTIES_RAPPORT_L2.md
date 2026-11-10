# Rapport de fin de lot L2 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L2 — le composant de lecture**
> (moteur du Centre de sorties, branché sur une source factice
> uniquement). Branche : `agent/sorties-l2-lecteur` (base
> `6a1a38eb`, tête de `agent/sorties-l1-modele-parseur`).
> Date : 2026-11-10. Langue : français.

## 1. Fait (par livrable)

| Livrable | Fichier(s) | État |
|---|---|---|
| 1. Module `feature:sorties` (ADR 0162) | `feature/sorties/` (convention `codeide.android.feature`), `settings.gradle.kts` | ✅ — trio feature standard (`core:ui/domain/model`) : la liste d'autorisation PAR DÉFAUT suffit, aucun changement build-logic pour le module lui-même |
| 2. Tampon et projection (ADR 0159 décision 2, ADR 0162 : « la logique de tampon/projection est dans `core:domain` ») | `core/domain/.../TamponSorties.kt`, `ProjectionSorties.kt`, `PliageTexte.kt` | ✅ — fenêtre TÊTE-tronquée par famille de source, pertes COMPTÉES (règle 14), horodatage à l'ingestion, cache de pliage aligné ; index de projection filtré (dérivation entière + incrémentale après lot `LotTampon`) ; `PliageTexte` = le pliage canonique UNE fois (filtre, tampon, recherche — `FiltreSorties` y délègue) |
| 3. Moteur de lecture (ADR 0159) | `feature/sorties/.../LecteurSorties.kt` (contrat + `LecteurSortiesRecycler`), `LignesSortiesAdapter.kt`, `PoigneeDefilement.kt`, `RangeesSorties.kt` (pur) | ✅ — RecyclerView virtualisé SANS DiffUtil (append par lot, notifications bornées), hauteur variable, suivi du bas HONNÊTE (coupe à la remontée, bouton « ⇣ suivre »), zoom pincé borné 10–20 sp, repères de défilement sur l'INDEX ; segments SGR pré-parsés à l'ingestion (aucun re-parse au bind) |
| 4. Jetons `core:ui` (ADR 0162 : contrat visuel partagé) | `core/ui/.../PastilleNiveauSortie.kt`, `BandeauSorties.kt`, jetons `sorties_niveau_*` (jour/nuit), attrs, aperçu `docs/mockups/ui/sorties-composants.html` | ✅ — pastille « lettre + teinte, jamais la couleur seule » ; bandeau d'état (MÊME sémantique que `IndicateurEtat`) ; KDoc + aperçu (règle 3) |
| 5. Contrôles du lecteur (spec SORTIES § 2/3) | `FragmentLecteurSorties.kt`, `LecteurSortiesViewModel.kt`, `RechercheLecteur.kt`, `SelectionLecteur.kt` | ✅ — recherche n/N (anti-rebond, hors thread, motif regex invalide AFFICHÉ en ligne — héritage `FiltreLogcat`, jamais d'exception ; les masquées sont comptées dans N, leur saut est sauté), pause/reprise (delta EN UNE fois), filtre unifié avec compteur de masquées, numéros, horodatage relatif/absolu, retour à la ligne, sélection multi-lignes + copie explicite, TalkBack par ligne |
| 6. Source factice + banc (ADR 0159 : « branché sur une source factice uniquement » ; PV livrables de L2) | `SourceSortiesFactice.kt`, `ContenuFactice.kt`, `BancSortiesActivity.kt`, carte dans `PanneauConfigToolingFragment` (feature:editor) | ✅ — séquences S1/S2/S6 aux volumes EXACTS du protocole (100 000 lignes, 12 480 chaudes pour S2), contenu DÉTERMINISTE (graine fixe), armement honnête (coût nul par défaut) ; carte OPT-IN dans la configuration tooling, activité non exportée ; arête `feature:editor` → `feature:sorties` assumée (ADR 0162) avec allow-list + `docs/ARCHITECTURE.md` dans le même commit |
| 7. Tests | `core/domain/src/test/.../TamponSortiesTest.kt`, `RechercheSortiesTest.kt`, `ProjectionSortiesTest.kt` ; `core/ui/src/test/.../PastilleNiveauSortieTest.kt`, `BandeauSortiesTest.kt` ; `feature/sorties/src/test/...` (6 classes) | ✅ — 65 tests nouveaux (22 domaine + 7 core:ui + 36 lecteur) : **734 verts sur `core:domain`, 60 sur `core:ui`, 529 sur `feature:editor` (non-régression), 36 sur `feature:sorties` — 1 359 au total, 0 échec** |

Commits (branche `agent/sorties-l2-lecteur`) : listés par
`git log --oneline agent/sorties-l1-modele-parseur..agent/sorties-l2-lecteur`
(4 commits : domaine, composants core:ui, moteur de lecture, banc).

## 2. Preuves (commandes lancées et résultats)

- **Chaîne de vérification** (STRATEGIE_VERIFICATION § 2, JDK 21.0.13,
  Gradle 9.7.1) :
  `./gradlew spotlessApply spotlessCheck detekt checkModuleDependencies
  checkUiTokens :core:domain:test :core:ui:testDebugUnitTest
  :feature:editor:testDebugUnitTest :feature:sorties:testDebugUnitTest`
  → **BUILD SUCCESSFUL** (detekt `maxIssues 0` sur tous les modules,
  frontières inter-modules avec la nouvelle arête, cliquet jetons sans
  remontée — zéro littéral UI nouveau, baseline inchangée).
- **Tests** : 65 nouveaux, 0 échec —
  `TamponSortiesTest` 7 · `RechercheSortiesTest` 8 · `ProjectionSortiesTest` 7
  · `PastilleNiveauSortieTest` 3 · `BandeauSortiesTest` 4
  · `RangeesSortiesTest` 8 · `SourceSortiesFacticeTest` 5
  · `RechercheLecteurTest` 6 · `LecteurSortiesViewModelTest` 9
  · `MoteurLecteurSortiesTest` 6 · `LayoutsSortiesTest` 2.
  JUnit 4, noms français en backticks ; Robolectric SDK 26 pour le
  comportement vue (patron des modules feature).
- **Sémantiques garanties par les tests d'équivalence** :
  le chemin RAPIDE de recherche (cache de pliage du tampon) rend les
  MÊMES verdicts que le chemin général ; `ProjectionSorties` (chemin
  rapide + filtre allégé) rend les MÊMES indices que
  `FiltreSorties.accepte` de référence ; la dérivation incrémentale
  rejoint la dérivation entière après troncature de tête.
- **Volumes du banc vérifiés** (S2) : 100 000 lignes émises, exactement
  **12 480** lignes chaudes (une sur huit, hors les 20 dernières) ;
  S1 : 20 lots de 500 à 1 lot/s puis cadence 10 000 l/s ; S6 : 50 ms
  entre lots de 500 (10 000 lignes/s, temps virtuel).
- **Bugs réels attrapés par les tests du lot** (preuves que la table de
  vérité travaille) :
  (a) `ajouterLot` ne sommait que la TRONCATURE du dernier retrait de
  tête (le compteur retourné au lecteur sous-comptait les pertes) ;
  (b) le flux factice émettait le WRAPPER `LotFactice` au lieu des
  lignes (aucune ligne ne parvenait au tampon) ;
  (c) le résumé de téléchargement dupliquait l'unité (« 1,3 Mo/2,6 Mo »)
  puis, après correction, la PERDAIT (« 1,3/2,6 · 50 % ») — deux
  approximations de template attrapées par la même table de vérité ;
  (d) une valeur d'enum XML ne peut pas s'appeler `assert` (mot réservé
  Java — validation AAPT2) : la valeur XML est `fatal`, le type Kotlin
  reste `ASSERT` (miroir du modèle) ;
  (e) un `<selector>` de DRAWABLE n'accepte pas `android:color` — la
  teinte de sélection est posée EN CODE (patron `IndicateurEtat`), la
  ColorStateList `res/color` n'est pas fiable sous Robolectric.

## 3. Écarts avec le plan, et pourquoi

1. **En-tête collant de tâche et projection regroupée repoussés au L3** :
   le collant appartient au regroupement par tâche (spec SORTIES § 4.2 —
   la frontière vient des événements `EtatTacheBuild`) ; L2 livre le
   lecteur en projection BRUTE, la dérivation regroupée est L3 (une
   seule liste re-projetée — ADR 0078). Le banc S5 (regroupement) suit
   au même lot.
2. **Défilement horizontal par rangée réduit à la coupe au bord** :
   `maxLines = 1` sans `ellipsize` (comportement console standard) — un
   défilement horizontal PAR rangée dans une liste verticale exige des
   intercepteurs tactiques par ligne (coût de construction élevé,
   conflits de geste avec le fling) ; le texte complet reste porté par
   la copie et TalkBack. À re-soulever si la table de réglages M13 en
   fait un critère dur.
3. **Sélection : appui long = ouvrir/étendre la sélection** (et non
   « copie de la ligne » comme au Logcat) : deux sémantiques sur un
   même geste auraient imposé un état caché ; la copie reste TOUJOURS
   explicite (bouton « copier » visible en mode sélection) — spec § 3.6.
   La copie de ligne brute par appui long reviendra avec le menu « plus »
   de l'éditeur dédié (L5a).
4. **Position retenue en rotation** survit à la ROTATION (ViewModel +
   `positionRetenue`) mais pas à la MORT du processus (pas de
   SavedStateHandle) : la persistance complète de session est L6
   (réglages/rétention) — l'état de repli, lui, arrive avec le
   regroupé (L3).
5. **`FluxSorties` porté par Hilt en singleton** : la source factice est
   la dépendance UNIQUE du lecteur (ADR 0159) ; le L3 publie le vrai
   flux tooling par re-lien du même port — le lecteur ne change pas.
6. **Environnement** (hors périmètre du dépôt) : réutilisation de
   l'installation du lot L1 (JDK 21, SDK Android, miroirs Maven de
   `~/.gradle/init.gradle.kts`, Robolectric `android-all` pré-posés) —
   aucune reconfiguration nécessaire cette fois.

## 4. Ce qui n'a pas pu être vérifié

- **Sur appareil** : hors périmètre du lot (aucune surface de
  production) — les budgets de rendu (S1–S8, spec § 10.2) attendent les
  PV du propriétaire ; l'écran banc est prête (carte de diagnostic).
- **Les PV du banc S1–S6** : le protocole reste celui du propriétaire
  (médiane/p95, 5 exécutions, clair ET nuit) ; les volumes et cadences
  sont VÉRIFIÉS au temps virtuel, pas sur appareil.
- **kover ≥ 80 %** : `koverVerify` est INTERDIT en local (CI seulement) ;
  les 65 tests couvrent les 12 fichiers du lot (chaque règle du tampon,
  chaque discipline de recherche, chaque transition du lecteur).
- **CI GitHub Actions** : le push déclenchera la chaîne complète ; le
  retour CI n'était pas observable au moment du rapport.
- **TalkBack réel** : les descriptions sont testées (contenu, jetons,
  région live) mais le critère qualitatif « lisible à l'échelle de
  police 200 % » (spec § 3.10) attend la vérification sur appareil.

## 5. Décisions à prendre pour le lot suivant (L3 — Gradle)

1. **Horodatage réglé** (question ouverte du rapport L1 § 5.3) : le
   tampon garde l'instant d'ÉMISSION quand le câble le fournit
   (`horodatageMs > 0`), sinon l'instant de RÉCEPTION de l'horloge
   injectée ; à L3, l'adaptateur du flux tooling alimentera l'émission
   réelle (v0.43.0 `BuildOutput.timestampMs`) — rien à changer côté
   lecteur.
2. **Tampon par canal retenu** (rapport L1 § 5.2) : un
   [TamponSorties] par famille de source, index de projection par
   lecteur ; L3 vérifie le couplage événement+texte sur les VRAIS flux
   de l'orchestrateur (risque ouvert du rapport L1 § 6.1).
3. **D1 (statuts fins)** : sans réponse du frère, le parseur maintient
   `StatutTacheGradle` (dégradation texte) et l'ÉVÉNEMENT fait foi —
   inchangé, comme posé au rapport L1 § 5.1.
4. **La projection regroupée** (résumé, rangées de tâches, repli, collant)
   dérivera de l'INDEX de projection existant (`ProjectionSorties`) —
   jamais une seconde liste (ADR 0078) ; le banc S5 la mesurera.
5. **La sonde T5/T6** (ADR 0152) doit viser le lecteur au L3 : le
   contraste émission→pixel se mesura sur le VRAI transport (S8) —
   la régression par rapport à la console actuelle bloque le lot.

## 6. Risques ouverts

1. **Le banc est prêt mais non mesuré** : sans PV (S1–S6), la
   recommandation ADR 0159 (2) reste à confirmer — le contrat
   `LecteurSorties` isolé protège l'implémentation (si la mesure
   invalide RecyclerView, seul le moteur change).
2. **Le tampon de banc à 120 000 lignes** tient en mémoire une liste
   Java pleine (mesure S2) : le plafond « indépendant du volume total »
   se vérifiera au PV (le compteur de pertes reste la vérité) ; en
   production la fenêtre console reste `NB_LIGNES_MAX` = 2 000.
3. **L'arête feature:editor → feature:sorties** est assumée dès L2 (la
   carte banc) : tout autre feature ne peut PAS suivre (règle
   `feature:*` inter-feature) — feature:diagnostics attendra L4 selon
   l'ADR 0162.
4. **Limitation réseau du bac à sable** (429 Maven Central) : les
   vérifications ont parfois besoin d'une seconde passe (miroirs) — le
   script d'init utilisateur du lot L1 reste réutilisable, aucun impact
   dépôt.
5. **Déclencheur CI corrompu (préexistant, hors lot)** :
   `.github/workflows/ci.yml` ligne 37 (`branches: ain]`) — inchangé
   depuis le rapport L1 § 6.5 ; la CI reste déclenchable par
   `pull_request`.
