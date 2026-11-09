# SORTIES_SPEC — Spécification du « Centre de sorties » (lot L0)

> Références : `docs/SORTIES_AUDIT.md` (constats confirmés),
> `docs/mockups/sorties/sorties.html` (maquette normative M1–M14),
> ADR 0159 (moteur d'affichage), 0160 (modèle de ligne et filtre), 0161
> (historique et rétention), 0162 (surfaces, module et navigation).
> Forme inspirée de `docs/EXECUTER.md` (zones, comportements, états,
> critères d'acceptation). Langue de livraison : français ; FR/EN partout
> (règle 11), thème clair et nuit.

## 1. Objet

Le « Centre de sorties » réunit dans UNE expérience cohérente les trois
familles de sorties de CodeIDE :

1. les sorties du **serveur de build Gradle** (Sync, Build, listage de
   tâches, téléchargements, journaux du serveur — tag `gradle-server`) ;
2. le **Logcat** de l'application exécutée (pont `applog-runtime`, sans
   adb — ADR 0103) ;
3. le **journal applicatif** de CodeIDE (`AppLogger`, `LogRepository`).

C'est un **document de lecture de premier rang** : recherche, filtres,
repli, sélection et copie, navigation vers la source, export, historique,
plein écran, tablette. Trois surfaces, **un seul moteur de lecture**, un
seul filtre, un seul export (ADR 0160/0162).

### Glossaire visible (mise à jour de `docs/TOOLING.md` § glossaire)

| Terme | Sens affiché |
|---|---|
| **Sorties** | le Centre de sorties (nouveau nom visible — § 9 Q9) |
| **Console** | désigne l'ANCIEN onglet du panneau (historique des ADR 0074/0078) — libellé retiré de l'interface |
| **Build / Sync / Tâches / Serveur de build / Daemon Gradle** | inchangés |
| **Exécution** | un build ou une sync, avec son identité, son verdict et sa durée |
| **Session** | une session de journaux d'application ((paquet, pid, processus), ADR 0103) ou un lancement de CodeIDE (journal applicatif) |

## 2. Zones (maquette M1/M7 comme référence visuelle)

Chaque surface réutilise les MÊMES zones :

- **En-tête de surface** : titre + source courante + verdict court
  (dérivations `PresentationTooling` — jamais de cascade copiée, ADR 0151).
- **Sélecteur de source** : Sync | Build | Logcat | Journal (panneau
  compact et éditeur dédié) — le choix est persisté comme
  `EtatEditor.filtreConsole` l'est aujourd'hui ; un point d'état par
  source (vert = en vol, rouge = échec, gris = rien).
- **Barre d'outils du lecteur** : recherche (compteur « n/N »,
  précédent/suivant, regex), pause/reprise, projection brut/regroupé
  (Gradle), bascule horodatage relatif/absolu, numéros, retour à la
  ligne, suivre le bas, effacer (explicite), export, menu plus (copier,
  fil de l'incident, plein écran).
- **Lecteur** (le moteur, ADR 0159) : liste virtualisée de lignes —
  numéro, niveau (lettre + pastille, jamais la couleur seule), horodatage
  optionnel, texte monospace (retour à la ligne ou défilement
  horizontal), repères sur la poignée de défilement (erreurs rouge,
  avertissements ambre, correspondances bleu).
- **Bandeaux d'état** (honnêteté — jamais silencieux) : pertes (compteur),
  arrêt de session, session précédente, échec par canal (INC-21),
  serveur déconnecté, sortie tronquée (M12).
- **Feuilles** : filtres avancés (M9), export (M10).

## 3. Comportements du lecteur (toutes sources)

1. **Ingestion bornée** : le tampon conserve une fenêtre maximale par
   source (console : `NB_LIGNES_MAX` actuel ; Logcat :
   `CAPACITE_TAMPON` ; journal : fenêtre de relecture) — toute perte est
   comptée et affichée (bandeau), jamais absorbée en silence.
2. **Suivi du bas honnête** : le lecteur suit la fin tant que
   l'utilisateur y était ; dès qu'il remonte, le suivi s'arrête (comportement
   de `PanneauConsoleFragment` généralisé) et un bouton « ⇣ suivre »
   ré-apparaît ; un build fini ne défile plus.
3. **Pause/reprise avec rattrapage sans trou** : en pause, le direct est
   retenu ; à la reprise, le delta arrive EN UNE fois
   (sémantique `LotJournal`/`lignesDepuis`, ADR 0107).
4. **Recherche** : sous-chaîne insensible à la casse ET aux accents par
   défaut ; regex optionnelle — motif invalide affiché en ligne
   (repli « aucune correspondance », jamais d'exception, héritage
   `FiltreLogcat`) ; hors thread principal, annulable ; compteur et
   repères de défilement ; navigation précédent/suivant.
5. **Filtres** : niveau (minimal OU ensemble), texte, étiquettes
   inclure/exclure, source, session, période — `FiltreSorties` pur JVM
   (ADR 0160) ; filtrable sans réécrire le tampon (les lignes masquées
   restent dans le tampon, un compteur de lignes masquées est visible).
6. **Sélection et copie** : sélection multi-lignes, copie de la ligne
   (clic long), copie de la sélection, copie du bloc d'échec — toujours
   explicite.
7. **Zoom au pincement** de la police (bornes 10–20 sp) ; retour à la
   ligne ou défilement horizontal (réglage M13).
8. **Repli de blocs** : tâches Gradle, traces d'exception (avec « Caused
   by »), répétitions regroupées « ×N » (compteur consécutif reconstruit
   après filtre) ; l'état de repli survit à la rotation.
9. **Couleurs ANSI** : SGR minimal par un parseur pur testé (gras,
   italique, 8+8 couleurs) ; désactivable (M13) ; jamais une interprétation
   de contrôleurs non supportés.
10. **Accessibilité** : TalkBack annonce ligne par ligne (niveau, source,
    horodatage relatif, texte) ; cibles ≥ 48 dp ; contrastes des jetons
    réels ; lisible à l'échelle de police 200 %.
11. **Rotation et état** : pivoter ne perd ni position, ni filtre, ni
    recherche (critère § 9.7).

## 4. Projection Gradle — « une liste, deux projections » (M2/M3/M4)

Le brut reste la source de vérité et reste accessible à un geste ; la
projection regroupée est une dérivation du même tampon (jamais une
seconde liste — ADR 0078).

1. **Résumé en tête** : verdict (réussi / échoué / annulé), durée, tâches
   exécutées / en cache / à jour / sans source, erreurs, avertissements.
   Les compteurs viennent des événements (`EtatTacheBuild`,
   `EtatBuild`) — jamais comptés sur le texte seul.
2. **Regroupement par tâche** : une rangée par tâche (chemin, statut —
   ● exécutée, ◇ cache, ○ à jour, – sans source, ✕ échouée — durée
   serveur), contenu repliable ; en-tête collant de la tâche courante
   pendant le build.
   **Alignement événement + texte** : la frontière de tâche est posée par
   `pomperTacheDemarree/Terminee` ; le texte `> Task :…` ne sert qu'à
   rattacher les lignes du flux sous la bonne rangée (une ligne sans
   événement reste au groupe courant — jamais devinée).
3. **Bloc d'échec** : `FAILURE`, `* What went wrong`, `* Try` épinglés
   sous le résumé ; erreurs `e: file://…:l:c` (Kotlin) et
   `fichier:l:c: error:` (Java) **cliquables dans le flux** →
   `ControleurPanneauEditeur.sauterAuProbleme` — pas de second endroit
   pour la même information.
4. **Sync** : les six phases réelles (Outils, Distribution, Daemon,
   Configuration, Dépendances, Modèle) avec durées et volumes, l'étape
   courante en en-tête collant ; le flux du câble reste consultable en
   projection brut ; une sync restituée du cache affiche sa ligne
   SYNTHESE d'origine (comportement INC-05/24 conservé).
5. **Journaux du serveur** : les lignes du tag `gradle-server` de la
   session de build sont consultables depuis l'exécution (filtre
   « Serveur ») sans quitter l'éditeur.
6. **Frontières manquantes** : tout besoin d'événement absent du client
   s'écrit dans `docs/DEMANDES_CODEIDE_TOOLING.md` (première demande :
   granularité des statuts de saut) et dégrade proprement sur le texte
   d'ici là.

## 5. Logcat — parité stricte puis ajouts (M5)

1. **Parité R3/R4** : sessions par (paquet, pid, processus), sélecteur
   avec sessions archivées, pause/rattrapage (ADR 0107), bandeaux
   pertes/arrêt/précédente, copie d'une ligne, traces cliquables vers le
   source (`LignesPile.extraire`), état vide honnête « sans adb :
   journaux de votre application uniquement ». Les tests existants
   (`LogcatViewModelTest`, `SautPileLogcatEditorViewModelTest`, …)
   restent verts ou sont portés avec équivalence démontrée — jamais
   supprimés pour verdir (lot L4).
2. **Ajouts** : puces d'étiquettes avec compteurs (inclure/exclure),
   pid/tid affichables, regroupement des répétitions « ×N », repli des
   traces avec cause, saut « erreur suivante/précédente », mise en évidence
   du plantage (bandeau + première ligne F en évidence).

## 6. Journal applicatif (M6)

Même lecteur, mêmes filtres, mêmes exports. Conserve : sessions de
lancement, recherche insensible aux accents, export zip
(`ExportLogsUseCase`) avec avertissement données sensibles, usage disque
(écran complet). L'écran Diagnostics de `feature:diagnostics` devient un
**consommateur** du même composant — pas un second afficheur (lot L4).

## 7. Surfaces et navigation (M1/M7/M8, ADR 0162)

1. **Panneau compact** (BottomSheet 3 états, ADR 0029/0118 conservé) :
   en-tête vivant + lecteur en fenêtre — **il n'embarque plus de moteur à
   lui** : c'est une vue bornée sur le même composant.
2. **Éditeur dédié** : ouvrable en **onglet** de la zone centrale (comme
   un fichier, ADR 0028) et en **plein écran** ; c'est là que vivent la
   recherche, les filtres avancés et l'historique.
3. **Tablette** : l'éditeur dédié s'ouvre en **volet à côté du code**
   (jamais par-dessus).
4. **Points d'entrée contextuels** : « Voir les sorties » sur l'échec
   d'un build (bandeau + notification de fin), « Voir la trace » sur un
   plantage (existe), ouverture directe de la session Logcat après un
   lancement.
5. **Fil de l'incident** : depuis un build échoué, un seul geste vers
   diagnostics, journaux serveur et (si lancement) session Logcat —
   deux gestes au plus (critère § 9.6).

## 8. Historique, rétention, confidentialité (M11/M10, ADR 0161)

- Historique des **10 dernières exécutions** (réglable), plafond de taille
  par exécution (les lignes du milieu tronquées les premières), stockage
  privé, **expurgées à l'écriture** (`LogRedactor` + chemins absolus),
  consultables après redémarrage, effaçables.
- Comparaison simple « cette exécution vs la précédente » : durée, tâches
  rejouées (option, post-L3).
- **L'affichage live n'est pas altéré** (c'est le contenu de
  l'utilisateur) ; seule la persistance expurge. Frontière tranchée par
  l'ADR 0161.
- **Export** : toujours explicite, jamais automatique — sélection /
  vue filtrée / tout ; texte brut ; zip existant pour le journal ;
  `FileProvider` (autorité dans `app`, ADR 0025) ou SAF (ADR 0003) ;
  avertissement « les sorties peuvent contenir des données sensibles »
  sur chaque feuille d'export.

## 9. Table de vérité — type de projet × état (M14, livrable testable)

Colonnes = états (serveur déconnecté ; sync jamais faite ; sync prête ;
build en vol / fini ; exécution lancée). Lignes = types de projet
(application Android ; bibliothèque ; JVM/Kotlin ; Spring/plugin Gradle ;
multiplateforme). Chaque cellule : sources visibles, action proposée,
texte d'état vide. Règles stables :

- **Logcat** n'existe que si le projet est exécutable sur l'appareil
  (périmètre `EXECUTER.md` § 1 : projets JVM/Spring hors périmètre) ;
- la **bibliothèque** n'a que Sync/Build (+ Journal) ;
- chaque état vide **dit pourquoi** et propose **l'action suivante** ;
- l'état impossible est absent de la table (jamais un onglet vide promis).

La table est publiée dans la maquette (M14) et devient un test JVM au lot
L1 (un état = un libellé = une action ; toute divergence fait échouer le
test). Le texte intégral des cellules vit dans `values/strings.xml`
(FR + EN), pas dans le code.

## 10. Critères d'acceptation

### 10.1 Fonctionnels

1. Un build réussi et un build échoué s'affichent regroupés par tâche,
   avec résumé ; le brut reste accessible à un geste ; **aucune ligne
   n'apparaît deux fois** (bascule brut/regroupé = la même liste
   re-projetée).
2. Une erreur `e: file://…:l:c` du flux ouvre le fichier à la ligne
   (même contrat que l'onglet Problèmes).
3. Les 10 derniers builds sont consultables après redémarrage de CodeIDE ;
   chaque exécution porte verdict, durée et compteurs ; l'effacement est
   explicite.
4. Recherche : compteur « n/N », suivant/précédent, repères de
   défilement, insensible à la casse et aux accents par défaut, regex
   invalide signalée en ligne (aucune exception).
5. Logcat : toute la parité R3/R4 conservée (tests existants verts) +
   filtre par étiquette avec compteurs + regroupement « ×N » + repli de
   trace avec « Caused by ».
6. Un build échoué puis une app qui plante : diagnostics, journaux
   serveur et trace du plantage atteints depuis l'éditeur en deux gestes
   au plus (fil de l'incident).
7. L'éditeur dédié fonctionne en onglet, en plein écran et en volet
   tablette ; pivoter l'écran ne perd ni position, ni filtre, ni
   recherche, ni état de repli.
8. Chaque état vide et chaque erreur dit pourquoi et propose la suite —
   conformes à la table de vérité (testée).
9. Les journaux serveur d'une exécution sont consultables depuis cette
   exécution (filtre « Serveur »).
10. L'export est explicite, avec périmètre choisi et avertissement de
    confidentialité ; l'historique persisté est expurgé ; le live ne
    l'est pas.
11. Le panneau compact et l'éditeur dédié montrent le MÊME tampon :
    aucune divergence possible (une seule source d'état).
12. La console actuelle reste utilisable jusqu'à la parité (lot L3,
    migration par parité) ; aucun moment où l'utilisateur perd une
    capacité.

### 10.2 Performance (budgets de départ — mesurés via `docs/SORTIES_BANC_MESURE.md`)

- Ingestion 10 000 lignes/s sans ANR ni image perdue visible en
  défilement (mesure T6 de l'ADR 0152).
- Mémoire plafonnée et indépendante du volume total ingéré.
- Aucune allocation par ligne au rendu (au steady state).
- Ouverture de l'éditeur dédié sur un tampon plein < 300 ms.
- Recherche sur 100 000 lignes sans geler l'interface (hors thread
  principal, annulable, progression annoncée).
- **Latence d'affichage** d'une ligne (émission serveur → pixel)
  mesurée avec l'instrumentation de l'ADR 0152 et **ne régresse pas**
  par rapport à la console actuelle (les mesures T1–T6 du départ font foi).

### 10.3 Qualité

- Chaîne locale verte (`STRATEGIE_VERIFICATION.md` § 2) sur les modules
  touchés ; la CI porte `lintDebug`/`assembleDebug`/`koverVerify`.
- Kover ≥ 80 % sur le code de domaine neuf (`LigneSortie`,
  `FiltreSorties`, parseurs, adaptateurs).
- `checkModuleDependencies` et `checkUiTokens` verts **sans remontée de
  baseline** ; zéro `!!`, zéro littéral UI nouveau ; FR/EN sans clé
  orpheline ; thème clair et nuit ; TalkBack par ligne.
- Test de non-régression sur **chaque sortie réelle** du corpus
  `docs/archive/tooling-scenarios/` + cas pathologiques (ligne de
  4 000 caractères, flux coupé au milieu d'une ligne, UTF-8, `\r`,
  100 000 lignes) — lot L1.
