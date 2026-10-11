# Cycle de vie de l'éditeur — session de projet et fermeture propre (prompt 3)

> **Statut (v0.92.0) : mission NON ENTAMÉE** — ce document consigne le
> travail restant (demande du propriétaire) et les points déjà établis ;
> il deviendra la spécification vivante (machine à états, table de
> propriété des ressources, mode d'emploi d'enregistrement d'une nouvelle
> ressource) quand l'étape A sera exécutée. Lecture préalable :
> `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/TOOLING.md`,
> `feature/editor/Module.md`, prompt 3.

## 0. Ce qui est déjà établi (vérifié dans le code, pas encore corrigé)

| # | Constat | Statut |
|---|---|---|
| E1 | `EditorActivity` (2 125 lignes) ne surcharge que `onStart`/`onStop` (surveillance de l'arbre) — aucune gestion de `isFinishing`, `isChangingConfigurations`, `onDestroy` | vérifié (recherche textuelle) ; relire le fichier en entier à l'étape A |
| E2 | `EditorViewModel.onCleared()` annule les sauvegardes automatiques SANS les « flusher » — risque de perte d'une modification récente à la sortie rapide | vérifié (lecture) ; à confirmer par test |
| E3 | Le démon de tooling (`DaemonManager`) démarre dans `CodeIdeApplication.onCreate` — durée de vie = celle du processus, indépendante de tout projet | vérifié |
| E4 | `GradleService` est `@Singleton` process-wide (canaux de sortie, console) — survit à l'éditeur | vérifié (lecture partielle) |
| E5 | `GitViewModel.demarrerSurveillance()` : boucle de sondage `viewModelScope` démarrée/arrêtée par `GitFragment.onStart`/`onStop` | vérifié |
| E6 | `EditorActivity` sans `launchMode` (standard) — plusieurs instances d'éditeur possibles dans la pile | vérifié (manifestes) |

À vérifier à l'étape A (non lus à ce jour) : `GradleConnectorPool`
(connexions Tooling API par dossier — chacune peut maintenir un **démon
Gradle** de plusieurs centaines de Mo), `CacheSync`, `EventBus`,
`HeapMonitor`, `GradleApiImpl.fermerSession()` (qui l'appelle ?),
`TerminalService`/`TerminalActivity` (liés au projet ?), portées Hilt
(`@Singleton` vs `@ViewModelScoped` vs `@ActivityScoped` — qui retient
un Context/Activity/View), `AppNavigator` et la restauration après mort
du processus, cache LRU de sessions `cel-ui`.

**Point d'accroche déjà en place** : depuis la v0.91.0/0.92.0, le serveur
LSP Java (prompt 2, étape 4) et la surveillance Git (prompt 1) sont
conçus pour S'ENREGISTRER auprès de la session de projet définie ici —
`SessionLsp` (contrat `demarrer/arreter` idempotent, borné par délai,
état observable) et l'état `EtatDepot` typé existent déjà.

## 1. Plan du travail restant (étapes A–F du prompt 3, chacune validée par « GO »)

| Étape | Livrable | Contenu |
|---|---|---|
| **A — Audit et cartographie** *(livrable écrit, pas de code de production)* | Table d'inventaire de TOUTES les ressources de fond (propriétaire, portée Hilt, démarrage, arrêt actuel, arrêt souhaité, écart, risque — minimum les lignes E1–E6 et la liste §0) ; diagrammes de séquence des parcours (accueil→éditeur→retour ; terminal→retour ; paramètres→retour ; changement de projet ; rotation ; mort du processus ; retour par notification) ; matrice de transitions inter-écrans (une ligne = un test) ; mesures de référence sur appareil (tas Java/natif, processus enfants, N cycles ouvrir/quitter AVANT tout correctif) ; détection de fuites en debug (LeakCanary ou équivalent justifié règle 9 + StrictMode) | |
| **B — Contrat de session et registre de fermeture** | Module de domaine Kotlin JVM pur : `SessionProjet` (`ouvrir(projet)`, `fermer(raison)`, états `{Fermee, Ouverture, Ouverte, Fermeture}`), registre de ressources `Fermable` (priorité d'ordre, délai maximal, drapeau « ne pas annuler ») ; tests unitaires : ordre inverse, idempotence, **panne partielle**, **dépassement de délai**, fermeture pendant l'ouverture, ouverture pendant la fermeture, réentrance, annulation. Aucune dépendance Android | |
| **C — Intégration éditeur et migration des ressources existantes** | `EditorActivity`/`EditorViewModel` : distinguer `isChangingConfigurations` (ne rien fermer) / `isFinishing` (fermeture de session) / `onStop` (suspension) / `onTrimMemory` (libération de caches) ; flush des sauvegardes en contexte NON annulable avant libération des sessions d'édition (I7) ; GitViewModel, GradleService/client tooling, surveillance de fichiers, caches — retrait des durées de vie process-wide injustifiées | |
| **D — Côté serveur de tooling** | Libération PAR PROJET (connexions Tooling API du `GradleConnectorPool`, démons Gradle sous-jacents, caches du `CacheSync`, abonnements du bus) ; message de protocole de « fermeture de projet » si nécessaire (compatibilité ascendante : `PROTOCOL_VERSION`, champs optionnels) ; politique du démon applicatif selon les mesures (conservé tant qu'une session existe ; arrêté après X min sans session et app en arrière-plan) | |
| **E — Cohérence de navigation** | Matrice de transitions implémentée : état « projet ouvert » sur l'accueil (liste récente, dernier ouvert), rafraîchissement au retour, réglages appliqués au retour (thème, palette, langue), permission SAF révoquée / stockage disparu détectés au retour, état de l'installation d'outils répercuté, retour depuis notification, écran de plantage → relance propre | |
| **F — Vérification et validation appareil** | 20 cycles ouvrir/quitter : croissance du tas < 5 %, aucune instance d'`EditorActivity` retenue (détecteur) ; sortie de l'éditeur ⇒ aucun processus enfant lié au projet au-delà de la politique ; ouvrir P, quitter, ouvrir Q ⇒ aucun vestige de P ; aucune perte de la dernière modification en sortie rapide (test automatisé) ; rotation ne ferme rien ; fermeture bornée en temps même si un élément bloque | |

## 2. Décisions à soumettre au propriétaire (avec recommandation)

1. **Build en cours à la sortie** : (a) fermeture différée jusqu'à la fin
   du build avec notification ; (b) annulation avec confirmation ;
   (c) le build continue, seule l'interface se ferme. Recommandation
   initiale du prompt : (a) ou (c), jamais d'annulation silencieuse.
2. **Politique du démon de tooling** : conservé tant qu'une session
   existe / arrêté après X minutes sans session et app en arrière-plan /
   relancé à la demande — trancher sur MESURES (mémoire au repos, temps
   de démarrage), pas sur l'intuition.
3. **Temporisation en arrière-plan** avant arrêt des services lourds
   (LSP inclus) — à proposer après l'étape 4 du prompt 2.
4. **Terminal lié au projet ou global** — politique voulue.

## 3. Invariants à garantir (contrat de la session)

I1 : au plus UNE session de projet active par processus (ou politique
explicite multi-instance testée) ; I2 : fermeture idempotente, ordonnée,
bornée, tolérante aux pannes partielles ; I3 : un changement de
configuration ne ferme JAMAIS le projet ; I4 : aucune coroutine, tâche
planifiée, écouteur, fichier mappé, poignée ni processus ne survit à son
propriétaire ; I5 : aucune fuite d'Activity/Fragment/View (vérifiée par
outil) ; I6 : aucun processus orphelin au-delà de la politique ; I7 :
aucune perte de données (flush avant libération) ; I8 : réouverture sur
un état propre ; I9 : la cohérence inter-écrans est une propriété
TESTÉE (chaque ligne de la matrice a un test).
