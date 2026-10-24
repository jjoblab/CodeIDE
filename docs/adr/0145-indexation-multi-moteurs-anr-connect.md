# ADR 0145 — L'écran d'indexation devient MULTI-MOTEURS (Java + Kotlin + XML) et le branchement LSP quitte le fil principal (correctif ANR `.kt`/`.kts`)

- **Statut** : **accepté** (livré avec CodeIDE v0.178.0).
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (chantier demandé : « stabilisé l'écran
  indexation (le bottomsheet), réparé le design […]. Actuellement,
  c'est codé en dur sur le lsp java elle doit être générique pour
  plusieurs lsp (xml, kotlin et autres). Corrige aussi l'indexation
  des autres lsp. Je veux un système d'indexation plus robuste,
  intelligent, performant et professionnel. L'ouverture d'un fichier
  .kt cause toujours des anr et aussi les fichiers .kts (problème lié
  a l'indexation du lsp kotlin) »).
- **Dépend de** : ADR 0117 (écran d'indexation, pastille + bottom
  sheet — lot B3 « Kotlin reporté » ENFIN levé), ADR 0059 (accents de
  langage), ADR 0114 (registre des sessions), ADR 0136 (journal
  honnête des langues), `docs/mockups/indexation/indexation.html`
  (maquette de référence du propriétaire),
  `docs/DEMANDES_CODEIDE_LSP.md` (D1-D5 : demandes transmises à
  l'agent du dépôt frère `codeide-lsp`), ADR 0144 (page blanche
  Compose — chantier DIFFÉRÉ, le focus reste l'indexation).

## Contexte

Deux défauts distincts, un même écran :

1. **L'écran n'était PAS celui de la maquette** : livré en phase B
   (v0.125.0) sur le SEUL état riche Java, il montrait des sections
   incomplètes — pas de pourcentage d'en-tête, pas de résumé des
   reprises, pas d'entrées écartées, pas d'erreurs bloquantes, pas
   d'histogramme des coûts, un bouton « Annuler le passage » MORT
   (aucun point d'annulation côté orchestrateur), des boutons
   d'action NON BRANCHÉS (TODO B7 depuis la phase B), le label de la
   liste de travail affichait « Dernier passage », un point de
   moteur CARRÉ sans couleur de langage, et la vue « par moteur »
   ne connaissait que Java (ADR 0117 lot B3 : `SessionLspKotlin
   .etatIndexation` était « reporté » — le report a duré 53
   versions).
2. **ANR `.kt`/`.kts`** : l'ouverture d'un fichier Kotlin causait un
   ANR SYSTÉMATIQUE après chaque sync. Chaîne complète établie dans
   `docs/DEMANDES_CODEIDE_LSP.md` D1 : `CoordonnateurLsp.brancher`
   appelait `LspEditor.connect()` sur le FIL PRINCIPAL (branché par
   `lifecycleScope.launch`) ; or la section SYNCHRONE de `connect()`
   — `LanguageServerWrapper.start()` →
   `InProcessStreamConnectionProvider.start()` → le supplier
   `serveur()` des définitions — tourne sur le fil APPELANT : création
   de session moteur + `definirClasspath` (lecture des 47 segments
   `kotlin-appelables/*.kidx` du projet témoin, ou décodage à froid
   kotlin-metadata du jar ENTIER — ≥ 10 s mesuré sur appareil
   d'entrée de gamme, correctif v0.116.0 déjà documenté) + racines du
   scan. Premier `.kt` post-sync = ANR garanti, RE-PAYÉ à chaque
   changement d'onglet (chaque connexion crée une session moteur
   NEUVE).

## Décision

### D1 — Généralisation MULTI-MOTEURS par construction (plus AUCUN Java codé en dur)

- **`MoteurIndexation`** (nouveau fichier) : une entrée d'énumération
  par langue LSP de l'espace — `JAVA`, `KOTLIN`, `XML` — chacune
  portant SON libellé localisé et SON accent de langage (ADR 0059).
  Ajouter une langue = UNE entrée + UNE source d'état. Le
  présentateur itère la LISTE, jamais un moteur nommé.
- **`EtatsMoteursIndexation`** : l'agrégat observable (une rangée par
  session LSP VIVANTE — session fermée = rangée retirée ; VIDE =
  pas de pastille, miroir de l'historique `INITIAL`). L'état GLOBAL
  de la pastille est la **pire phase** des moteurs présents
  (`INITIAL < PRET < CONSTRUCTION < PARTIEL < ERREUR`) : un moteur
  en construction pendant que l'autre est prêt montre TOUJOURS la
  construction (l'utilisateur ne croit pas « terminé » trop tôt),
  un avertissement ou une erreur l'emporte sur tout (à montrer).
- **`EditorViewModel.etatsMoteursIndexation`** (interne) : combine
  les trois sessions + l'état riche Java
  (`SessionLsp.etatIndexation` via `flatMapLatest` — la collecte
  meurt avec sa session) + les fenêtres Kotlin/XML ; `stateIn
  (WhileSubscribed(5 s))` — la pastille survit au bref écran éteint
  (rotation) sans re-souscrire, et AUCUNE collecte ne fuite vers une
  ancienne session.

### D2 — Correctif ANR : `connect()` HORS fil principal

`CoordonnateurLsp` reçoit un exécuteur injectable
(`executeurConnect: CoroutineDispatcher = Dispatchers.IO` ; les tests
gardent leur fil) ; `brancher` lance `connect()` dans une portée
`SupervisorJob + executeurConnect` (fermée dans `fermer` — un
connect en vol à la destruction est simplement coupé). Le câblage de
la VUE (`setHost`, `setCurrentFilePath`) reste sur le fil appelant
(la vue n'est pas thread-safe) ; les rappels restent asynchrones via
la file FIFO de la vue (`vue.post`). Un échec/exception du supplier
est journalisé (« connect() a levé — coloration seule ») et n'abat
pas l'activité : l'éditeur reste en coloration seule, comme avant.

Ce correctif couvre l'ANR **UI** ; la LATENCE d'ouverture `.kt`
(re-paiement du nourrissage à chaque connexion) reste entière tant
que les demandes D1-a/D1-b au dépôt frère ne sont pas livrées —
c'est exactement ce que documente `docs/DEMANDES_CODEIDE_LSP.md`.

### D3 — Fenêtres de nourrissage SYNTHÉTISÉES pour Kotlin et XML (dégradation HONNÊTE)

Le dépôt frère n'expose NI progression NI bilan pour Kotlin/XML
(demande D3 transmise — voir `docs/DEMANDES_CODEIDE_LSP.md`). En
attendant, l'hôte ne cherche PAS à inventer des compteurs : il
nourrit DEUX fenêtres `MutableStateFlow<PhaseIndexation>` par le
cadre `fenetrerNourrissage` (CONSTRUCTION pendant l'application du
modèle/classpath, PRET après, ERREUR sur exception — RELANCÉE : les
cascades historiques gardent leur sémantique, seul l'ÉTAT devient
visible). Aux cinq points de nourrissage (cascade modèle, cascade
classpath, régime projet seul ×2). Une ré-ouverture de projet remet
les fenêtres à INITIAL (état honnête). L'écran dégrade franchement :
rangée « En construction » (barre indéterminée), « Prêt », « Erreur »,
« En attente de synchronisation » — jamais un PARTIEL inventé
(impossible à savoir côté hôte).

### D4 — Le panneau de détail rejoint ENFIN la maquette

Toutes les sections de `docs/mockups/indexation/indexation.html` :

- en-tête avec **pourcentage à droite** pendant la construction
  (§2 « 62 % ») ;
- barre de progression **construction seule** — masquée au repos,
  en partiel et en erreur (le comportement masqué était implicite
  dans le code B6 : `visible` devient un champ du modèle) ;
- **résumé des reprises** (« 96 reprises, 22 reconstruites » — §2) ;
- vue par moteur avec **point OVAL teinté à l'accent de langage**
  et couleur d'état (vert/ambre/rouge) ;
- sections **« Entrées écartées »** (PARTIEL) et **« Erreurs
  bloquantes »** (ERREUR) ;
- **histogramme des coûts** (3 indexeurs les plus chers, largeur
  relative au max — §4) ;
- notes d'impact par NIVEAU (fond neutre / ambre / rouge plein —
  plus de TODO B6+) ;
- contenu **défilant** (`NestedScrollView` — le panneau partiel de
  la maquette défile, l'ancien CLIPPAIT) ;
- label « En cours » pour la liste de travail (il affichait
  « Dernier passage » par erreur de câblage).

### D5 — Les boutons d'action sont BRANCHÉS (fin du TODO B7)

« Réindexer » et « Réessayer les entrées échouées » appellent
`EditorViewModel.reindexerLsp()` (revalidation + auto-guérison). Les
deux boutons mappent aujourd'hui le MÊME appel — tant que le dépôt
frère n'expose pas de réessai CIBLÉ (demande D4), c'est documenté
dans l'écran et ici. « **Annuler le passage** » N'EST PLUS ÉMIS :
aucun point d'annulation coopératif n'existe (demande D5) et un
bouton mort était un mensonge — l'enum reste (réservé) pour ne pas
casser la signature. Le PREMIER bouton de la liste est plein
(action recommandée — maquette §3), les suivants cernés ; les styles
vivent dans des gabarits XML (`bouton_action_indexation_primaire/
secondaire`), jamais posés par code.

## Options écartées

- **Bloquer l'UI le temps de l'indexation** (couvertures « loading »
  à l'ouverture) : contraire au contrat ADR 0117 (« l'édition n'est
  pas bloquée ») et au comportement Android Studio.
- **Inventer une progression Kotlin/XML** (estimation par temps
  écoulé) : un chiffre faux est pire qu'une barre indéterminée —
  règle de la maquette §8 « jamais de faux chiffre ».
- **Un écran d'indexation par langue** : la pastille est UN point
  d'entrée, le panneau RASSEMBLE (la maquette montre les moteurs
  CÔTE À CÔTE, §2/§3/§5).

## Conséquences

- **Positives** : plus AUCUN ANR UI à l'ouverture `.kt`/`.kts` ;
  l'écran couvre TOUS les moteurs et toutes les sections de la
  maquette ; les boutons font ce qu'ils promettent ; les moteurs
  futurs (Groovy, etc.) = une entrée + une source.
- **Négatives** : la latence d'ouverture `.kt` reste (nourrissage
  moteur re-payé par connexion) — D1-a/b attend côté frère ; la
  vue par moteur Kotlin/XML est grossière (fenêtres) tant que D3
  n'est pas livré.
- **Neutres** : la pastille au repos reste masquée sur écran étroit
  (maquette §1) ; `presentationIndexation.afficher` change de
  signature (interne au module — aucun consommateur externe).

## Références

- Maquette : `docs/mockups/indexation/indexation.html` (§2-§5, §8).
- Demandes au dépôt frère : `docs/DEMANDES_CODEIDE_LSP.md` (D1 :
  supplier sans nourrissage lourd ; D2 : pas de scan sous verrou ;
  D3 : flux de progression réel ; D4 : `reesayerEchecs()` ; D5 :
  `annulerPassage()`).
- Code : `MoteurIndexation.kt` (agrégat), `EditorViewModel`
  (`etatsMoteursIndexation`, `fenetrerNourrissage`),
  `CoordonnateurLsp` (`executeurConnect`), `PresentationIndexation`
  (multi-moteurs), `FragmentDetailIndexation` (sections maquette,
  boutons branchés), `VuePointIndexation` (pastille, inchangée).
- Tests : `PresentationIndexationTest` (17 tests multi-moteurs :
  pire phase, divergents, synthétisé seul, écartées, bloquantes,
  histogramme, PLUS D'ANNULER).
