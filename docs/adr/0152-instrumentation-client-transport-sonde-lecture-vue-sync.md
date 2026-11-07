# ADR 0152 — L'instrumentation client du transport : la sonde du fil lecteur, la mesure de la vue et la latence de la sync (Phase 1 du diagnostic « retard d'affichage »)

- **Statut** : **accepté** (branche `mesure/instrumentation-logs`, miroir client
  de l'instrumentation serveur du dépôt `codeide-tooling`).
- **Date** : 2026-11-08.
- **Décideurs** : jjoblab (mission « retard d'affichage des sorties de build,
  audit de performance, cohérence du tooling », Phase 1 §3 ; `DIAGNOSTIC-LOGS.md`
  §7 du dépôt `codeide-tooling` annonce cette branche).
- **Dépend de** : ADR 0080 (voie dédiée de la pompe), ADR 0081 (lecture par fil
  dédié, chemin rapide au point de réception), ADR 0079 (flux sync ordonné),
  ADR 0103 (injection applog — le contrat de réglage lu à chaque lancement).

## Contexte

La Phase 1 du diagnostic a livré côté **serveur** (dépôt `codeide-tooling`,
branche `mesure/instrumentation-logs`) les points T1, T1b, T2, T2b, T3b, le
métronome et le banc « socket seul ». Le journal de terrain du propriétaire
(2026-10-31) laisse pourtant QUATRE angles morts côté **client** :
l'après-publication (T5 : durée des lots de la console, longueur du texte ;
T6 : images perdues — « AUCUNE mesure aujourd'hui »), le détail des phases
internes du fil lecteur (T3 : read/décodage/routage par frame), la sync
(INC-44 : ni durée ni latence, angle mort n°4 — une sync de 194 s sans la
moindre trace) et le contexte des signaux système (F6 : « pression mémoire »
nu, sans availMem ni MemAvailable). Enfin, la matrice X1–X10 exige que le
banc « socket seul » soit lançable DEPUIS l'app (crochet de lancement) et que
l'instrumentation soit **désactivable et réglable depuis l'écran de
configuration** (règle 10, INC-32 minimum obligatoire : drapeau + export).

## Décision

### 1. La session porte une SONDE de lecture ; le résumé rejoint la ligne `ConsoleLatence` du build

`SessionTooling.sondeLecture` (nouvelle propriété, `null` par défaut) expose
une `SondeLectureSocket` — le fil dédié de lecture (ADR 0081) mesure, frame
par frame et SEULEMENT si une sonde existe, la durée de ses trois phases
(`FrameCodec.readFrame`, décodage JSON, routage par le chemin rapide). La
production (`SondeLecteurTransport`, `tooling:daemon`, posée sur le
`GradleSocketServer` par le graphe Hilt) agrège sans verrou et rend un
résumé **consommé** (`prendreResume()`) que `GradleApiImpl.pomperFin` accolle
à la ligne `ConsoleLatence` existante du build : le résumé d'arrivée (ms) et
la décomposition interne (µs) se lisent sur la MÊME ligne, par build. Sans
sonde : deux `nanoTime` de moins, aucun autre effet — le chemin du fil est
l'identique d'avant.

### 2. UN réglage arme les DEUX bouts ; le daemon le passe à chaque lancement

`AppSettings.instrumentationTransport` (faux par défaut) arme ensemble la
sonde du fil lecteur (T3), la sonde de vue (T5/T6) et le drapeau
`--instru-transport` du serveur — une seule vérité, pas de client instrumenté
sans serveur instrumenté. Le daemon relit le réglage à CHAQUE lancement du
process (même contrat que l'injection applog, ADR 0103 : « s'applique à la
prochaine vie de l'orchestrateur », dit tel quel dans l'écran). Le banc
« socket seul » suit la même mécanique : `AppSettings.bancSocketSeul` et ses
quatre paramètres (`bancLignes`, `bancCadenceMs`, `bancLongueurLigne`,
`bancContenu`) deviennent les drapeaux `--banc-socket-seul --banc-lignes …`
de la ligne de commande — **chacun lu et honoré par `ServerConfig`**, aucun
drapeau décoratif (interdit n°7). C'est le crochet de lancement annoncé :
la matrice X1–X10 se joue depuis l'écran de configuration, sans adb.

### 3. T5 et T6 vivent dans la vue, derrière la même sonde

`SondeConsoleVue` (feature:editor, câblée par `PanneauConsoleFragment`) :
T5 chronomètre chaque `viderLot` (lignes du lot, durée, longueurs du texte
des deux consoles APRÈS application — la variable de X9 : coût par ligne vs
lignes déjà affichées) et T6 suit les images du fil principal par une boucle
`Choreographer` posée à l'attache de la vue et retirée avec elle. Étiquette
dédiée `ConsoleVue`, préfixe `instru:` (même famille que les lignes du
serveur) : tout passe par le journal applicatif, donc par l'export du
propriétaire. **Limite assumée** : les images perdues sont estimées à 60 Hz
nominal (intervalle > 33 ms ⇒ ses images manquantes) — `FrameMetrics` exact
par fenêtre est un approfondissement possible, l'estimation suffit à la
matrice (cible « aucun gel > 100 ms » : le pire interval est suivi pour
TOUTES les images).

### 4. INC-44 : la sync obtient les MESURES des builds, pas des copies de code

Miroir exact du chemin build, mêmes accumulateurs, mêmes étiquettes : latence
de TRANSPORT par projet à la réception (`GradleApiImpl`, routeur — résumé au
`SyncResult`/`PartialSyncResult`), latence de PUBLICATION à la zone texte
(`GradleService.ajouterLigneSync`, résumé à la conclusion, dédupliqué par le
contenu comme la ligne de console ADR 0079) et RÉCAPITULATIF DES PHASES au
journal (« sync réussie en X ms — phases : CONFIGURATION 1 200 ms ; … »,
depuis `EtatGradle.etapesSync`). Une sync sans ligne publiée le DIT (« 0
ligne ») : c'est une information, pas un échec de mesure.

### 5. Les signaux système partent AVEC leur contexte

`SondesSysteme` (feature:editor, appelée par `EditorActivity.onTrimMemory` au
seuil existant) journalise le niveau de trim, `ActivityManager.MemoryInfo`
(availMem, seuil, drapeau basse) et `/proc/meminfo` (MemAvailable, Cached)
sur le fil IO — le fait F6 redevient exploitable pour X4. Étiquette dédiée
`PressionMemoire`.

## Conséquences

- **Aucun octet du fil ne change** (règle 2) : la sonde mesure le fil
  existant, elle ne le modifie pas ; le protocole, le framing et le JAR
  embarqué restent ceux de la branche serveur (à construire en mode
  composite `-Pfreres.sources` avec `codeide-tooling` sur
  `mesure/instrumentation-logs` pour que le JAR des assets porte les
  drapeaux).
- **Coût nul par défaut** (règle 10) : sondes coupées = aucun compteur, aucune
  ligne, aucun appel — le coût armé est borné (deux `nanoTime` et quelques
  atomiques par frame côté fil, un `nanoTime` par lot côté vue).
- L'écran de configuration gagne la carte **Diagnostic** (INC-32, minimum
  obligatoire) : drapeau d'instrumentation, banc et paramètres, avec
  l'honnêteté « s'applique au prochain démarrage du serveur de build » et le
  renvoi à l'export de l'écran Diagnostic.
- Le verdict §8 de `DIAGNOSTIC-LOGS.md` reste EN ATTENTE des mesures
  appareil : cette branche AUTOMATISE la séance (X1–X10 lançables depuis
  l'app), elle n'invente aucun chiffre (interdit n°1).
