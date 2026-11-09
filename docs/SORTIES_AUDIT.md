# Audit « Centre de sorties » — confirmé sur le dépôt réel (lot L0)

> Vérification du § 4 du prompt du chantier « Centre de sorties » sur le
> code réel. L'audit d'origine était daté du commit `d9b0c61` (v0.207.0) ;
> **le dépôt a bougé** : cette vérification est faite sur `22097a96`
> (v0.211.0, branche `main`), le 10 novembre 2026. Chaque constat porte son
> verdict (CONFIRMÉ / CORRIGÉ / PRÉCISÉ) et sa preuve (fichier : lignes).
> Le dépôt ayant bougé, la numérotation ADR demandée (0153–0156) est
> **déjà occupée** par le chantier générateur (ADR 0153–0158) : les ADR de
> ce chantier sont renumérotés **0159–0162** (règle « ne jamais réutiliser
> un numéro », `AGENTS.md` § 9).

## 1. Trois surfaces, trois moteurs — CONFIRMÉ (avec précisions)

| Constat d'origine | Verdict | Preuve |
|---|---|---|
| Quatre onglets : CONSOLE, PROBLEMES, JOURNAL, LOGCAT | CONFIRMÉ | `feature/editor/.../EtatEditor.kt:248-260` (`enum class OngletPanneau`) |
| Console = un `TextView` par canal, `append` loti par trame, pas de RecyclerView (ADR 0078) | CONFIRMÉ | `PanneauConsoleFragment.kt:39-52` (KDoc), `:460-491` (`viderLot` : un `append` par console et par trame), `:535-537` (`poserTamponVierge` : `Editable` en place) |
| Deux canaux texte : Sync et Build ; le canal TACHES ne produit plus de lignes | CONFIRMÉ | `GradleService.kt:105-128` (`EvenementConsoleTexte` : `Ligne(canal…)` / `Vider(canal)`) ; `LignesConsoleTexte.kt` (constructeurs Sync/Build uniquement) |
| Fenêtre de 2 000 lignes (`GradleService.NB_LIGNES_MAX`) | CONFIRMÉ | `GradleService.kt:1076` (`const val NB_LIGNES_MAX = 2_000`), rejeu `replay = NB_LIGNES_MAX` (`:479`) |
| Logcat = RecyclerView + DiffUtil, tampon 5 000 | CONFIRMÉ | `LogcatViewModel.kt:396` (`CAPACITE_TAMPON = 5000`), `LignesLogcatAdapter.kt` (RecyclerView) |
| Journal compact = fenêtre 200 (ADR 0029) | CONFIRMÉ | `EditorViewModel.kt:5579` (`FENETRE_JOURNAL = 200`) → `ObserveLogsUseCase(200)` ; layout `fragment_panneau_journal.xml` (RecyclerView + chips) |
| Journal complet = écran séparé `feature:diagnostics`, paliers de 500, relecture disque unique | CONFIRMÉ | `JournalFragment.kt`/`JournalViewModel.kt` ; ADR 0025 (`docs/adr/0025-…`) ; appel via `AppNavigator.openDiagnostics` (`core/ui/.../AppNavigator.kt:177`, `EditorActivity.kt:3382`) |
| Trois modèles de ligne (`EvenementConsoleTexte.Ligne`, `LigneJournal`, `LogEntry`) | CONFIRMÉ | `GradleService.kt:119`, `core/domain/ModelesJournaux.kt:44`, `core/model/LogEntry.kt:29-37` |
| Deux échelles de niveaux (6 valeurs vs LogLevel) | CORRIGÉ — **4** valeurs, pas une échelle comparable | `NiveauJournal` : **6** valeurs V/D/I/W/E/F (`core/domain/ModelesJournaux.kt:12-30`) ; `LogLevel` (`core/model/LogLevel.kt:13-25`) : **4** valeurs DEBUG/INFO/WARN/ERROR (+ `LogVerbosity` NORMAL/DETAILED). Aucune projection 1:1 possible (F n'existe pas côté `LogLevel`, DEBUG de `NiveauJournal` ≠ DEBUG de `LogLevel` en sémantique d'usage) — la normalisation (ADR 0160) est donc indispensable, pas simplement commode. |

## 2. Capacités inégales — CONFIRMÉ (avec deux précisions)

- **Console Gradle : aucune recherche, aucun filtre, aucune copie ni
  export** — CONFIRMÉ. Lecture complète de `PanneauConsoleFragment.kt`
  (762 lignes) : aucun champ de recherche, aucun filtre, aucun
  `ClipboardManager`, aucun export ; les seuls points d'entrée sont le
  bandeau d'échec (« Voir les problèmes », « Réessayer »), le bouton
  Tâches, la configuration intégrée et l'entrée stdin.
  Les erreurs `e: file://…` du flux ne sont **pas cliquables** dans la
  console : le saut ne passe que par l'onglet Problèmes
  (`PanneauProblemesFragment` → `ControleurPanneauEditeur.sauterAuProbleme`).
- **PRÉCISÉ (1)** : la console possède déjà « Expliquer avec IA »
  (`boutonExpliquerIa` → `ControleurPanneauEditeur.expliquerAvecIa(texteVisible())`,
  `PanneauConsoleFragment.kt:316-318`) et un champ stdin pendant le build
  (v0.41.1, `:204-214`). Le Centre de sorties doit les conserver (contrat
  `ControleurPanneauEditeur`, § 9 Q8).
- **PRÉCISÉ (2)** : l'annulation est déjà visible en vol
  (`boutonAnnulerBuild`, `:354`) et l'auto-défilement honnête existe
  (suivi du bas coupé dès que l'utilisateur remonte, `:460-488`) — à
  préserver à l'identique dans le nouveau lecteur.
- **Logcat** : tout le § 4.2 est confirmé — filtre pur `FiltreLogcat`
  (niveau minimal, texte, regex avec `erreurMotif` signalée, repli sans
  exception ; `core/domain/FiltreLogcat.kt`), pause avec rattrapage
  incrémental (`LogcatViewModel.kt:226-231,343-367` — `pont.lignesDepuis`,
  ADR 0107), copie d'une ligne au clic long
  (`PanneauLogcatFragment.kt:183,376-384`), traces cliquables
  (`LignesPile.extraire`, `PanneauLogcatFragment.kt:291-295`), sessions
  archivées (`ArchiveSessionsJournal`), bandeaux pertes/arrêt/session
  précédente (`PanneauLogcatFragment.kt:254-274`).
- **Journal** : confirmé — niveaux multiples (`EtatJournal.filtresNiveaux`),
  recherche insensible aux accents (ADR 0025), suivi en direct
  (`suivreDirect`), export zip (`ExportLogsUseCase`, `core/domain/ExportLogs.kt:66-104`),
  usage disque (`EtatJournal.usageDisque`).

## 3. La sortie Gradle n'a ni structure ni mémoire — CONFIRMÉ

- Flux plat `--console=plain` ; aucune ligne structurée par tâche depuis
  l'ADR 0078 (les rangées structurées et 5 layouts ont été supprimés,
  −2 800 lignes). **Nuance importante pour la conception** : les
  **événements de tâche existent déjà** côté client —
  `GradleApiImpl.pomperTacheDemarree/pomperTacheTerminee`
  (`tooling/client/.../GradleApiImpl.kt:718-747`) publie
  `EtatTacheBuild(buildId, chemin, statut, dureeMs)` avec la durée
  **mesurée côté serveur** (`core/domain/GradleToolingPort.kt:454-459`,
  `StatutTache` : EN_COURS / REUSSIE / ECHOUEE / SAUTEE, `:487-501`).
  Le regroupement ne doit donc PAS deviner par regex les frontières de
  tâches : il s'aligne sur ces événements et n'utilise le texte
  (`> Task :…`) que pour rattacher le contenu (cf. ADR 0160 et § 9 Q6).
  Seule la **distinction des sauts** (UP-TO-DATE vs FROM-CACHE vs
  NO-SOURCE) manque dans `StatutTache` (tout est « SAUTEE ») — demande
  écrite dans `docs/DEMANDES_CODEIDE_TOOLING.md` (nouveau fichier créé
  par ce lot, la forme imitant `docs/DEMANDES_CODEIDE_LSP.md`).
- Aucun historique des builds : CONFIRMÉ — chaque action émet
  `EvenementConsoleTexte.Vider` sur son canal
  (`GradleService.kt:1018-1024`) et rien ne survit au redémarrage côté
  affichage (seule l'archive des sessions Logcat et le journal complet
  survivent). La sortie complète reste théoriquement « dans le canal
  rejouable du client » (`docs/TOOLING.md`), mais bornée à 2 000.
- Corpus réel : `docs/archive/tooling-scenarios/sorties/` — 11 journaux,
  1 642 lignes au total (valeurs déjà mesurées pour le banc JVM ci-dessous).

## 4. Un même incident éclaté en quatre endroits — CONFIRMÉ

Console (flux Build) → Problèmes (`EtatGradle.problemesTotal`,
`PanneauProblemesFragment`) → Journal applicatif (tag `gradle-server` :
stderr/stdout de l'orchestrateur journalisé par le daemon,
`docs/TOOLING.md` § « Journalisation » et `DaemonManager`) → Logcat
(plantage de l'app, `bandeauArret`). Aucun lien traversant : le bandeau
d'échec de la console n'offre que « Voir les problèmes » / « Réessayer » /
« Expliquer avec IA » (`PanneauConsoleFragment.kt:295-319`), jamais
les journaux serveur ni le Logcat.

## 5. Un panneau trop étroit — CONFIRMÉ

- `EtatPanneau { REPLIE, MI_HAUTEUR, ETENDU }`
  (`EtatEditor.kt:89-98`), traduit vers `BottomSheetBehavior` par
  `EditorActivity` (mi-hauteur = 0,5, `fitToContents=false`,
  `halfExpandedRatio=0.5` — ADR 0029/0118).
- Pas de plein écran dédié aux sorties ; « journal complet » ouvre
  l'écran Diagnostics séparé (`AppNavigator.openDiagnostics`).
- Pas de mise en page côte à côte sur tablette.

## 6. Le risque n° 1 est la performance perçue — CONFIRMÉ

ADR 0151 (source unique de l'état tooling, présentateur pur) et ADR 0152
(instrumentation transport + sonde vue T5/T6) existent et sont actifs :
`SondeConsoleVue` est armé par le réglage `instrumentationTransport`,
coût nul par défaut (`PanneauConsoleFragment.kt:119-136,262-278,501-522`).
Le banc de mesure du lot L0 (`docs/SORTIES_BANC_MESURE.md`) s'appuie sur
cette instrumentation existante et n'invente rien.

## 7. Atouts à réutiliser — TOUS CONFIRMÉS (emplacements vérifiés)

| Atout | Emplacement réel |
|---|---|
| `FiltreLogcat` (filtre pur JVM) | `core/domain/FiltreLogcat.kt` (78 lignes) |
| `LotJournal` / `lignesDepuis` (rattrapage sans trou) | `core/domain/RegistrePontJournaux.kt:82,158-174` |
| `ArchiveSessionsJournal` | `core/domain/ArchiveSessionsJournal.kt` |
| `LignesPile.extraire` | `core/domain/LignesPile.kt` |
| `LogRedactor` (9 familles de motifs) | `core/domain/LogRedactor.kt:97-106` |
| `ExportLogsUseCase` (zip + SAF + `FileProvider` via `app`) | `core/domain/ExportLogs.kt:66-104` |
| `PresentationTooling` (présentateur pur) | `feature/editor/PresentationTooling.kt` (578 lignes), ADR 0151 |
| `ControleurPanneauEditeur` (saut source, IA) | `feature/editor/ControleurPanneauEditeur.kt:10-39` (4 méthodes) |
| `StyleLigne` | `feature/editor/LignesConsoleTexte.kt:243-261` |
| `OuvrirBufferLectureSeule` | `EtatEditor.kt:402` (`ActionEditor`), ADR 0147 |

## 8. Mesures JVM réalisables en L0 — FAITES (ordre de grandeur du parseur)

Banc exécutable et re-exécutable : `scripts/banc-parseur-sorties/BancParseur.java`
(zéro dépendance, Java 11+ ; classification par motifs précompilés
précisément ceux prévus pour le parseur de L1).

Environnement de la mesure : sandbox Linux x86_64, **OpenJDK 21.0.13**,
une seule exécution séquentielle (ce ne sont PAS des mesures appareil).

| Mesure | Valeur |
|---|---|
| Corpus réel (11 fichiers, 1 642 lignes, ~146 Ko) | lecture + classification en 62 ms au total |
| Premier fichier (JVM à froid, 537 lignes) | 5,9 ms → **~92 000 lignes/s** |
| Flux synthétique 94 001 lignes (2 000 blocs de 50) | meilleur des 3 passes : 88,2 ms → **~1 065 000 lignes/s** |
| Répartition (flux synthétique) | 6 000 tâches, 2 000 erreurs, 6 000 avertissements correctement classées |

**Lecture pour l'ADR 0159** : le parsing de L1 est deux à trois ordres de
grandeur au-dessus du budget d'ingestion (10 000 lignes/s) — le parseur
n'est PAS le goulot ; la décision de moteur doit se juger sur le RENDU
(ingestion → tampon → dessin), pas sur la classification. À reconfirmer
en Kotlin/JVM au lot L1 (les chiffres Java 21 ci-dessus sont un plancher).

## 9. Écarts et découvertes par rapport au prompt d'origine

1. **Numérotation ADR** : 0153–0158 sont pris par le chantier générateur
   (`docs/adr/0153-…` à `0158-…`). Les ADR de ce chantier deviennent
   0159 (moteur d'affichage), 0160 (modèle de ligne et filtre unifiés),
   0161 (historique et rétention), 0162 (surfaces, module et navigation).
2. **`docs/DEMANDES_CODEIDE_TOOLING.md` n'existe pas** (seul
   `DEMANDES_CODEIDE_LSP.md` est présent). Créé par ce lot avec une
   première demande (statuts de saut granulaires), même forme que son
   aîné.
3. **« feature:editor ≈ 27 000 lignes » : CONFIRMÉ** — 27 038 lignes
   Kotlin dans `feature/editor/src/main` (35 878 avec XML), comptées sur
   `22097a96`.
4. **`LogLevel` = 4 valeurs** (DEBUG/INFO/WARN/ERROR) et non une échelle
   6 valeurs comparable à `NiveauJournal` : la normalisation du § 5.1 est
   un vrai choix de conception (ADR 0160), pas une fusion triviale.
5. **Événements de tâches déjà publiés** avec durée serveur
   (`EtatTacheBuild`) : le § 5.3 du prompt (« privilégier les événements
   déjà exposés ») est directement actionnable dès L1 ; la demande au
   dépôt frère ne concerne que la granularité des statuts de saut.
6. **Le dépôt a bougé** : v0.207.0 → v0.211.0 entre l'audit d'origine et
   cette vérification (lots générateur L3, écrans Nouveau projet). Aucun
   des fichiers listés au § 3 du prompt n'a disparu ; les constats restent
   valides sur la version courante.
