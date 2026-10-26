# Tests manuels sur appareil

Ce document rassemble les vérifications qui **nécessitent un appareil ou un
émulateur Android** — jamais exécutables sur JVM (Robolectric). Chaque
procédure indique quand elle a été introduite et ce qu'elle valide. Aucune
n'a pu être déroulée dans l'environnement de build (pas de KVM) : elles
attendent une recette sur matériel.

## Préparation

```bash
# 1. Construire l'APK debug puis l'installer.
source scripts/env.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Suivre le logcat de l'application.
adb logcat --pid=$(adb shell pidof -s jo.codeide)
```

## Journalisation (étape 2 → v0.3.0)

| # | Procédure | Résultat attendu |
|---|---|---|
| J1 | Lancer l'application | Logcat : entrée `Session` contenant la version, le code de version, `debug` et le résumé d'appareil ; puis `App` : `MainActivity démarrée` |
| J2 | Depuis l'accueil, ouvrir les paramètres puis revenir | Logcat : `Navigation` : `navigation accueil -> paramètres` ; aucune stack trace StrictMode (aucune I/O sur le thread principal) |
| J3 | Laisser l'application vivre quelques minutes | `adb shell run-as jo.codeide ls files/logs/` : `current.jsonl` présent, tailles bornées (1 Mio) |
| J4 | Produire du volume (navigation répétée) puis vérifier | `current.jsonl` passe en `archive-1.jsonl` à 1 Mio ; au plus 5 archives |
| J5 | Vérifier le contenu d'une ligne | Une entrée = une ligne JSON compacte ; aucun chemin, courriel ni URI `content://` en clair (tags `<chemin>`, `<courriel>`, `content://…/h-XXXXXXXX`) |
| J6 | Forcer l'arrêt puis relancer | Nouvel en-tête de session en première écriture ; l'ancien `sessionId` apparaît dans les lignes précédentes |
| J7 | Thème sombre + rotation | Les journaux fonctionnent identiquement (aucun crash au relancement du pipeline) |

L'**export zip** et son partage (`ExportLogsUseCase` + FileProvider) seront
éprouvés manuellement à partir de l'écran de diagnostics (étape 12) ; les
tests JVM en couvrent déjà la fabrication (archive valide, entrées,
`device-info.txt`, nettoyage des anciens exports).

## Plantages (étape 3 → v0.4.0)

Préambule : le menu debug n'existe **qu'en build debug** — depuis l'étape 12
il vit dans l'écran Diagnostic (Paramètres › Avancé › Diagnostic, bouton de
la section Informations). Les chemins internes sont lus via
`adb shell run-as jo.codeide …` (application débogable).

| # | Procédure | Résultat attendu |
|---|---|---|
| P1 | Menu debug → « Provoquer un plantage » | L'écran dédié s'affiche **dans le processus `:crash`** (vérifier `adb shell pidof jo.codeide:crash` non vide) ; titre « Un problème inattendu est survenu », résumé (type exception, écran Accueil, version), détails repliés |
| P2 | Après P1, « Redémarrer l'application » | L'application revient sur l'accueil (tâche propre) ; **aucune** boîte système « a cessé de fonctionner » (sortie gérée, pas de délégation) |
| P3 | Après un plantage, relancer depuis le lanceur | Boîte de dialogue « Un problème est survenu lors de la dernière session » ; « Voir le rapport » ouvre l'écran dédié en consultation (pas de bouton Redémarrer) ; « Ignorer » ferme la dialogue — dans les deux cas, elle ne revient plus au prochain démarrage |
| P4 | Écran dédié → « Détails techniques » | La trace s'affiche en police monospace sélectionnable (exception, causes, filons de pain) ; les actions Copier / Partager (texte) / Partager (archive zip) / Enregistrer (SAF) fonctionnent ; l'archive zip contient `report.json`, `rapport.txt`, `device-info.txt` |
| P5 | Menu debug → « Provoquer un plantage » trois fois de suite (< 60 s), puis relancer | Au troisième : **pas d'écran dédié** (délégation système, boîte système possible) ; au redémarrage, l'écran dédié éventuel affiche le conseil de boucle et « Vider le cache » — jamais « Redémarrer » ; les données utilisateur sont intactes |
| P6 | Menu debug → « Exception non fatale » | Aucun crash ; logcat : entrée `Debug` ERROR avec la chaîne d'exceptions aplatie et **expurgée** (`/storage/…/Projets` → `<chemin>`) |
| P7 | Menu debug → « Générer des journaux » | Aucun blocage ; `current.jsonl` reçoit la salve (50 entrées DEBUG + 1 WARN + 1 ERROR, vidage immédiat) |
| P8 | `adb shell run-as jo.codeide ls files/crashes/` après un plantage | Le rapport `<horodatage>-<id>.json` existe, aucun reste `.tmp` ; le tuer puis relancer ne crée **pas** de doublon de rapport |

Lecture des rapports hors écran : `adb shell run-as jo.codeide cat
files/crashes/<nom>.json` — une seule ligne JSON, sans chemin, courriel ni
URI en clair (règle 15).

## Couche données — SAF (étape 4 → v0.5.0)

Le port `FileSystem` est testé sur JVM contre un fournisseur factice qui
respecte le vrai protocole d'appel du framework ; les procédures suivantes
valident le comportement du **système réel** (fournisseur
`com.android.externalstorage.documents`, permissions persistantes,
dossiers refusés par Android 11+). Elles s'exécutent depuis le menu debug
de l'écran Diagnostic : les appels SAF y sont déclenchés par les boutons
de test.

Préambule commun : appairer un appareil Android 11+ ou plus récent, lancer
l'application : Paramètres › Avancé › Diagnostic, bouton du menu debug dans la section Informations.

| # | Procédure | Résultat attendu |
|---|---|---|
| S1 | Menu debug → « Test SAF : décrire le dossier de travail » après l'avoir choisi une fois (S2) | Le logcat affiche l'entrée `SafDebug` avec le **nom** du dossier et sa taille (`FileStat` complet) ; aucun crash, aucune exception dans les journaux |
| S2 | Menu debug → « Test SAF : choisir un dossier » (sélecteur système), choisir `Téléchargements/CodeIDE-essai`, puis « créer un fichier témoin » | Le fichier `codeide-temoin.txt` apparaît dans le dossier (vérifiable depuis un gestionnaire de fichiers ou `adb shell ls /sdcard/Download/CodeIDE-essai/`) avec le contenu attendu ; une **deuxième** création échoue proprement : `AlreadyExists` (pré-contrôle, jamais d'écrasement, jamais de fichier « (1) » renommé) |
| S3 | Répéter S2 en choisissant la **racine** du stockage, puis `Download` lui-même, puis `Android/data` | Le sélecteur système refuse déjà ces emplacements (grisés ou message) ; si un fournisseur exotique les proposait, le logcat affiche la raison `ForbiddenFolders` (racine / téléchargements / données protégées) — jamais de permission prise sur ces dossiers |
| S4 | Après S2 : « Paramètres système → Applications → CodeIDE → Permissions → Fichiers et médias » → retirer l'accès (ou `adb shell pm clear-permission-flags`), puis « décrire le dossier » | `hasPersistablePermission` répond faux et l'état calculé est `PermissionLost` — jamais de crash ; la description échoue par `PermissionLost`, pas par une exception non gérée |
| S5 | Après S2 : supprimer le dossier `CodeIDE-essai` depuis un gestionnaire de fichiers, puis « décrire le dossier » | L'état calculé est `Missing` (permission tenante, dossier disparu) ; `list` échoue par `NotFound` ; aucune boîte système, aucun crash |

Vérification des permissions persistantes après S2 :
`adb shell dumpsys package jo.codeide | grep -A2 "persistedUriPermissions"`
— l'URI de l'arborescence choisie doit apparaître en lecture **et**
écriture, et n'être comptée **qu'une fois** (plafond 512, section 5.6 :
ne persister que le nécessaire).

## Assistant de premier lancement (étape 5 → v0.6.0)

Le routage (`isSetupCompleted`), le test d'écriture et la survie de
l'état sont couverts par les tests JVM (ViewModel + test d'intégration
avec la véritable application) ; les procédures suivantes valident le
**parcours réel à l'écran**, sélecteur SAF système compris.

Préambule commun : installation fraîche (`adb uninstall jo.codeide`
puis `adb install app-debug.apk`) pour partir d'un `isSetupCompleted`
faux, appareil Android 11+.

| # | Procédure | Résultat attendu |
|---|---|---|
| O1 | Premier lancement : observer l'écran après le splash | L'assistant s'ouvre (pas l'accueil) ; la barre de progression indique 1/5 ; « Commencer » avance d'une page ; le retour système **recule d'une page** (et ne quitte pas l'assistant), désarmé sur la bienvenue ; le glissement du doigt ne change **pas** de page (pager non swipable) |
| O2 | Page dossier → « Choisir un dossier » → sélectionner `Download` (ou la racine) | Message **clair** « refusé par Android » avec la raison ; aucune permission prise (`adb shell dumpsys package jo.codeide \| grep -A2 persistedUriPermissions` vide) ; « Choisir » de nouveau propose un autre dossier |
| O3 | Page dossier → choisir un dossier inscriptible (ex. `Documents/CodeIDE`) | « Vérification » puis le libellé du dossier s'affiche ; le fichier témoin `codeide-temoin-<horodatage>.txt` n'est **plus** présent dans le dossier (créé puis supprimé — le nom porte l'extension canonique du type demandé, le fournisseur ne renomme pas) ; l'URI d'arborescence apparaît en lecture/écriture dans `persistedUriPermissions`, comptée une fois |
| O4 | « Plus tard » à la page dossier, finir l'assistant | L'accueil s'ouvre avec le bandeau « Configurer le dossier de travail » ; le bandeau **disparaît** après configuration du dossier (étape 6/7 : réglages) ; relancer l'app : l'assistant ne revient pas (installation terminée) |
| O5 | Page apparence : choisir sombre, désactiver les couleurs dynamiques, passer en anglais | Chaque choix prend effet **immédiatement** (recréation d'écran, texte bascule en anglais) ; tuer le processus (`adb shell am kill jo.codeide`) et relancer : les choix sont conservés ; sur Android 13+, le réglage système « langue par application » reflète fr/en |
| O6 | Page profil : saisir un nom d'auteur et une licence, puis **rotation** de l'écran à chaque page ; enfin « Terminer » | Le nom et la licence restent saisis après rotation ; « Terminer » referme l'assistant sur l'accueil ; relancer : accueil direct ; (optionnel) `adb shell am kill` au milieu de l'assistant, relancer : la page et les saisies sont restaurées |
| O7 | Régression v0.15.0 — installation neuve → parcourir l'assistant jusqu'au bout → « Terminer » | Le bouton **finalise réellement** : retour à l'accueil, et `adb shell dumpsys package jo.codeide` ne relance pas l'assistant au démarrage suivant (bug corrigé : l'action `Terminer` n'était jamais émise, `isSetupCompleted` restait faux) ; un double-appui pendant la finalisation ne double pas l'écriture ; si l'écriture échoue, un message d'erreur apparaît sur la page Terminé et le bouton redevient actif |

## Écran Paramètres (étape 6 → v0.7.0)

Le ViewModel (persistance immédiate de chaque réglage) et les cas
d'usage du dossier de travail (validation, permission conditionnelle)
sont couverts par les tests JVM ; les procédures suivantes valident
le **parcours réel à l'écran** (sélecteur système, permissions,
relances).

Préambule commun : installation de l'app, assistant terminé (O6), un
dossier de travail configuré (O3) pour M3-M5.

| # | Procédure | Résultat attendu |
|---|---|---|
| M1 | Paramètres → Apparence : choisir « Sombre », désactiver les couleurs dynamiques, passer la langue en anglais | Chaque choix prend effet **immédiatement** (recréation d'écran, textes en anglais) ; tuer le processus puis relancer : tout est conservé ; en repasser par le réglage système « langue par application » (Android 13+) reflète le choix |
| M2 | Paramètres → Projets : saisir « Ada Lovelace » dans le nom d'auteur, sélectionner la licence GPL 3.0 | Le nom n'est écrit qu'à la sortie du champ (focus) ; après relance, nom rogné (« Ada Lovelace ») et licence GPL proposée ; le wizard (étape 10) les pré-remplira |
| M3 | Paramètres → Projets : « Changer » le dossier de travail → choisir un **autre** dossier inscriptible | Message « Dossier de travail changé » ; `adb shell dumpsys package jo.codeide \| grep -A4 persistedUriPermissions` : la **nouvelle** URI est tenue ; l'**ancienne n'y figure plus** (aucun projet n'en dépendait) |
| M4 | Créer un projet dans l'ancien dossier (menu debug ou étape 7), puis changer le dossier de travail | Le message signale la **permission conservée** ; l'ancienne URI **reste** dans `persistedUriPermissions` (le projet l'utilise) ; la nouvelle est tenue |
| M5 | Paramètres → Avancé : « Réinitialiser les préférences » (confirmer), puis « Relancer l'assistant » | Après réinitialisation : thème système, couleurs dynamiques actives, langue système, dossier non configuré — mais l'app reste installée (pas d'assistant au simple relancement, registre intact) ; « Relancer l'assistant » ouvre l'assistant, le terminer referme sur l'accueil |

## Accueil : liste des projets (étape 7 → v0.8.0)

Le ViewModel (états, recherche avec délai, tris, états d'accès, actions)
et les cas d'usage du domaine (import, relocalisation, suppression,
équilibre des permissions) sont couverts par les tests JVM ; les
procédures suivantes valident le **parcours réel à l'écran** (sélecteur
SAF, permissions, tirer-relâcher, tablette).

Préambule commun : installation de l'app, assistant terminé (O6), un
dossier de travail configuré (O3). `dumpsys` désigne
`adb shell dumpsys package jo.codeide | grep -A4 persistedUriPermissions`.

| # | Procédure | Résultat attendu |
|---|---|---|
| A1 | Accueil vide (aucun projet) : vérifier l'état, puis « Nouveau projet » | Illustration + message « Aucun projet pour l'instant » + bouton « Nouveau projet » ; le bouton ouvre l'écran placeholder du wizard (étape 10) ; retour système → accueil |
| A2 | « Ouvrir un dossier existant » (petit FAB) → choisir un dossier **hors** du dossier de travail, inscriptible | Snackbar « Projet "<nom>" ajouté » ; la ligne apparaît (pastille dossier, nom, emplacement lisible, « à l'instant ») ; `dumpsys` : l'URI de l'arbre choisi est tenue |
| A3 | « Ouvrir un dossier existant » → choisir un **sous-dossier du dossier de travail** | Le projet est ajouté **sans nouvelle permission** dans `dumpsys` (héritage, ADR 0015) ; retirer ce projet de la liste ne libère rien ; l'entrée du dossier de travail reste unique |
| A4 | Recherche : taper « kot » (projets « Application Kotlin », « Serveur HTTP »), puis effacer | Les frappes se fondent (~250 ms) : la liste ne filtre qu'après une pause de frappe ; « sans résultat » affiche « Aucun projet ne correspond à "kot" » + bouton « Effacer la recherche » qui restaure tout ; accents et casse ignorés (« theses » trouve « Thèses ») |
| A5 | Tri : ouvrir un projet, épingler un autre, basculer « Nom » / « Récents » | L'épinglé flotte **toujours** en tête ; « Récents » ordonne par dernier ouvert (jamais ouverts en fin) ; « Nom » ordonne alphabétiquement ; le choix survit à la rotation |
| A6 | Actions d'un projet (⋮ ou toucher la carte) : renommer (nom vide, puis valide) ; épingler ; retirer | Le menu liste Ouvrir/Renommer/Épingler/Retirer de la liste/Supprimer du disque ; nom vide refusé avec message ; renommage : libellé seul (le dossier ne bouge pas, ADR 0012) ; retirer : snackbar « Projet retiré de la liste » et le dossier existe toujours (vérifiable via un explorateur) |
| A7 | Supprimer du disque : confirmer, puis refuser une fois | Le message rappelle le **nom** du projet et l'irréversibilité ; annulation ne fait rien ; confirmation : snackbar « Projet supprimé du disque » et le dossier a disparu du stockage ; si l'arbre n'a plus de projet ni dossier de travail, sa permission quitte `dumpsys` |
| A8 | État d'accès : révoquer la permission (`adb shell pm revoke`… ou retirer le dossier côté stockage), puis tirer-relâcher la liste | La ligne affiche « Permission perdue » (ou « Introuvable » si le dossier a été supprimé) **sans crash** ; les actions de résolution (Relocaliser / Retirer) ouvrent le menu ; re-sélectionner le dossier via « Relocaliser » rétablit l'accès et l'état redevient sain au prochain rafraîchissement |
| A9 | Rotation + mort du processus pendant la consultation | Recherche, tri et position générale restaurées (SavedStateHandle) ; les états d'accès se recalculent (jamais persistés) |
| A10 | Tablette (ou émulateur sw600dp) : consulter l'accueil | La liste passe en **2 colonnes** ; sur téléphone : 1 colonne ; les FAB restent accessibles, le bouton « Ouvrir un dossier existant » au-dessus de « Nouveau projet » |

## Wizard de création, partie 1 (étape 10 → v0.11.0)

La machine à états, le rendu dynamique (visibilité, dérivations,
resynchronisation) et l'équilibre des permissions de l'emplacement
éphémère sont couverts par les tests JVM (`WizardViewModelTest`,
`CreationLocationUseCasesTest`) ; les procédures suivantes valident le
**parcours réel à l'écran** (fragments, sélecteur SAF, clavier, tablette).

Préambule commun : installation de l'app, assistant terminé (O6), un
dossier de travail configuré (O3).

| # | Procédure | Résultat attendu |
|---|---|---|
| W1 | Accueil → « Nouveau projet » : observer le cadre, choisir un modèle, revenir, re-entrer | Barre d'outils « Nouveau projet » avec ✕ ; indicateur « Étape 1 sur 5 · Modèle » + progression 1/5 ; cartes (pastille monogramme, nom, description, tags) ; **Suivant désactivé** sans sélection ; choisir un modèle coche la carte et active Suivant ; retour système depuis l'étape 1 avec données saisies → dialogue « Abandonner la création ? » (Continuer / Abandonner) ; ✕ sans saisie → accueil direct ; à la re-entrée le modèle reste présélectionné |
| W2 | Étape 1 → Suivant : observer la transition, la barre d'actions | Transition horizontale (axe X) ; « Étape 2 sur 5 · Configuration » ; **Retour apparaît** ; puces récapitulatives « Application · Gradle · JDK 21 · JUnit 5 » |
| W3 | Étape 2 : basculer « Sources uniquement » | `jdkVersion`, « Tests JUnit 5 » et « Gradle Wrapper » **disparaissent** (animation) ; les puces ne montrent plus que « Application · Sources uniquement » ; l'aide dynamique « Sans système de build, seules les sources sont générées » s'affiche ; revenir à Gradle : tout réapparaît ; JDK passe par liste déroulante (17/21) |
| W4 | Étape 2 → Suivant, puis retour arrière | « Étape 3 sur 5 · Informations » : nom, description, package (dérivé), Group ID/Artifact ID/Version ; **Suivant actif** (mène à l'étape 4 Fichiers) ; Retour → Configuration, tous les choix conservés |
| W5 | Étape 3 : taper le nom « Mon Projet », puis « CON », puis coller un emoji | Le package suit le nom (`jeanne.monprojet` si auteur Jeanne) tant qu'il n'est pas édité ; « CON » → erreur inline « Ce nom est réservé par Windows » ; la description montre un compteur ; l'aperçu du chemin affiche `…/Mon Projet` ; modifier le package à la main → il ne suit plus, l'icône de resynchronisation apparaît, y toucher le remet en phase |
| W6 | Étape 3 : vérification de cible | Après ~0,5 s d'indicateur : « Emplacement vérifié — le nom est disponible » ; créer au préalable (via un explorateur) un dossier du même nom dans le dossier de travail → « Ce dossier existe déjà : choisissez un autre nom » ; révoquer la permission du dossier de travail puis réouvrir le wizard → « L'emplacement n'est plus accessible » sans crash |
| W7 | Étape 3 : « Changer de dossier » → dossier **hors** du dossier de travail, puis abandonner | La carte montre le nouveau dossier (aperçu mis à jour) ; abandonner : la permission propre **disparaît** de `dumpsys` (relâchée, ADR 0022) ; refaire avec un choix **dans** l'arbre du dossier de travail : aucune permission supplémentaire (héritage) |
| W8 | Rotation et mort du processus à chaque étape | Modèle, choix, saisies et emplacement restaurés ; l'indicateur reste sur la bonne étape (données saisies → dialogue d'abandon au retour système) |
| W9 | Tablette (ou émulateur sw600dp) : parcourir le wizard | Le contenu est **borné et centré** (jamais étiré) ; barre d'outils pleine largeur ; cibles tactiles ≥ 48 dp |
| W10 | Réglage « Réduire les animations » actif (Paramètres système > Accessibilité) | Les transitions d'étapes sont supprimées (respect du réglage) |

| W11 | Étape 3 → Suivant : observer l'étape 4 Fichiers | « Étape 4 sur 5 · Fichiers » ; interrupteurs README/.gitignore/.editorconfig **cochés** (défauts) avec leur explication ; licence « Aucune » par défaut, ligne « Auteur : … · Année : … » issue des Paramètres ; boutons segmentés Français/English (défaut = langue de l'app) ; Suivant actif quelle que soit la combinaison |
| W12 | Étape 4 : décocher README et .gitignore, choisir MIT, contenu English → Suivant | « Étape 5 sur 5 · Récapitulatif » ; résumé par section reflétant **tous** les choix (ex. `.editorconfig` seul, « Licence : MIT », « Contenu : en ») ; l'arborescence prévue montre le nombre de fichiers et reflète les options (pas de README, un `LICENSE` en plus) ; dossiers repliables au toucher ; boutons « Modifier » ramènent à l'étape correspondée ; bouton principal « **Créer le projet** » |
| W13 | Récapitulatif → « Créer le projet » : observer la création puis le succès | L'indicateur et la barre d'actions disparaissent ; progression en temps réel (Préparation, Création du dossier, Génération des fichiers un à un, Enregistrement) ; **succès** : « Projet créé », nom, boutons Ouvrir le projet / Retour à l'accueil / Créer un autre projet ; « Retour à l'accueil » → l'accueil **défile jusqu'au nouveau projet** dont la carte est marquée d'un contour coloré ; vérifier sur disque (explateur) que le dossier contient bien les fichiers de l'aperçu (dont `.codeide/project.json` et `LICENSE`) |
| W14 | Création → **Annuler** pendant la progression (ou retour système) | La création s'arrête ; **retour au récapitulatif** (aucun écran bloqué) ; vérifier sur disque : le dossier créé a été **supprimé** (rollback) ; rien en base (l'accueil ne montre pas le projet) ; « Créer le projet » à nouveau fonctionne |
| W15 | Créer un projet portant le nom d'un **dossier existant** (créé au préalable dans le dossier de travail) | La vérification d'étape 3 le détecte (« Ce dossier existe déjà ») ; si contourné (dossier créé entre-temps), l'écran d'échec affiche un message compréhensible, le nettoyage (« le dossier créé a été supprimé »), les boutons Réessayer et Copier les détails (presse-papiers : erreur typée, jamais de chemin) |
| W16 | Écran de succès → « Ouvrir le projet » | Le wizard se referme ; l'accueil met le projet en évidence **et** le classe en « ouvert il y a … » (tri des récents — l'éditeur arrive à l'étape 13) |
| W17 | Écran de succès → « Créer un autre projet » | Retour à l'étape 1 Modèle avec le modèle **précédent présélectionné**, saisies vidées ; **aucun outil de développement à l'écran** (le menu debug vit dans l'écran Diagnostic depuis l'étape 12) |
| W18 | Rotation pendant l'écran de création (EnCours) puis après le succès | La liste d'événements est conservée ; l'état succès survit ; le retour système pendant EnCours annule (rollback) |

## Écran Diagnostic (étape 12)

Préambule : entrée **Paramètres › Avancé › Diagnostic** ; build debug
seulement pour D11-D12 (menu debug dans la section Informations).

| # | Action | Attendu |
|---|---|---|
| D1 | Ouvrir l'écran Diagnostic | Toolbar « Diagnostic » avec retour fonctionnel ; section Informations (version conforme au build, appareil — fabricant/modèle/Android API —, session UUID) ; onglets Journaux / Plantages |
| D2 | Onglet Journaux : observer le chargement puis faire défiler vers le haut | Les 500 dernières entrées d'abord ; le défilement atteint le haut → un palier d'entrées plus anciennes apparaît (jusqu'à l'historique complet) ; la taille occupée (Ko/Mo lisibles, nombre de fichiers au pluriel correct) est affichée |
| D3 | Filtres par niveau (chips) | Chaque chip retient son niveau, plusieurs se combinent ; aucune chip active = tous les niveaux ; les compteurs visuels suivent |
| D4 | Recherche « navig » puis « MODELE » | Après ~0,25 s : seules les entrées correspondantes (message, étiquette ou classe d'exception) ; « MODELE » trouve « modèle » (accents ignorés) ; vider le champ restaure tout |
| D5 | Activer « Suivre en direct » puis naviguer dans l'application | La liste défile automatiquement vers les nouvelles entrées tant que la bascule est active ; la désactiver stoppe le défilement |
| D6 | Toucher une entrée avec exception | Détail complet (date, niveau, étiquette, thread, session, message sélectionnable) ; bouton « Voir l'exception » déplie la trace monospace (repliable) |
| D7 | « Partager » (onglet Journaux) | Archive `codeide-logs-*.zip` proposée par la feuille de partage système ; l'archive ouverte contient le JSONL + `device-info.txt` sans donnée personnelle |
| D8 | « Enregistrer » (onglet Journaux) | Sélecteur système (nom `codeide-logs-…zip` proposé) ; après enregistrement, le fichier choisi contient la même archive |
| D9 | « Effacer » puis confirmer / annuler | Sans confirmation rien ne change ; confirmé : liste vide, taille à zéro, les nouvelles entrées réapparaissent en direct |
| D10 | Onglet Plantages avec au moins un rapport (procédure P1 au préalable) | Liste du plus récent au plus ancien (date, type, exception, résumé, pastille « Non consulté ») ; toucher un rapport ouvre l'écran dédié en consultation ; au retour la pastille a disparu ; « Supprimer » par ligne et « Tout supprimer » demandent confirmation ; « Exporter » partage `codeide-crashes-*.zip` (JSON + texte + index) |
| D11 | Réglage « Niveau : Normal » → « Détaillé » (build debug) | Le bouton reflète Détaillé ; les entrées DEBUG apparaissent en direct dans la visionneuse (le moteur est basculé à chaud) ; après redémarrage de l'application le réglage est conservé |
| D12 | Rotation et mort du processus (onglets, filtres, recherche) | Onglet actif, chips cochées, recherche et bascule direct restaurés ; la liste se recharge au même état |

## Espace de travail (étape 13)

Préambule : créer un projet au préalable (procédures W1-W18), puis
« Ouvrir » depuis l'accueil.

| # | Action | Attendu |
|---|---|---|
| E1 | Accueil → « Ouvrir » un projet | L'espace de travail s'affiche **par-dessus** l'accueil : toolbar au nom du projet, tiroir fermé (téléphone), zone centrale « Aucun fichier ouvert », panneau inférieur replié ; retour → l'accueil est intact (position de défilement conservée) et le projet est classé en tête des récents |
| E2 | Toolbar → ☰ (ou geste de bord) | Le tiroir s'ouvre : en-tête (nom du projet, chemin lisible, « Fermer le projet »), contenu réservé à l'explorateur (étape suivante) ; retour système tiroir ouvert → **le tiroir se ferme** (pas l'écran) ; retour tiroir fermé → l'écran se referme |
| E3 | Tiroir → « Fermer le projet » | L'espace de travail se referme, retour à l'accueil tel quel |
| E4 | Panneau inférieur : toucher l'en-tête | Le panneau passe de replié (en-tête seul) à mi-hauteur (onglets Console · Problèmes · Journal vides) et réciproquement ; le bouton d'icône le replie |
| E5 | Rotation pendant l'espace de travail, puis « Ne pas garder les activités » + mise en arrière-plan prolongée | Le projet reste chargé (identifiant par SavedStateHandle), titre et tiroir intacts |
| E6 | Tablette (sw600dp+) : ouvrir un projet | Le tiroir est **permanent** (panneau fixe à gauche, pas de ☰ ni geste), la zone centrale occupe le reste |

## Espace de travail — explorateur (étape 14)

Préambule : un projet **Kotlin** et un projet **Java** générés à
l'étape 9 (procédures W1-W18) — l'acceptation exige l'arborescence
correcte sur les deux.

| # | Action | Attendu |
|---|---|---|
| E7 | Ouvrir un projet → tiroir | L'arborescence se charge : **dossiers d'abord, puis fichiers, puis ordre alphabétique** ; icônes par extension (Kotlin, Gradle, XML, Markdown, JSON…), indentation par profondeur, chevron sur les seuls dossiers |
| E8 | Déplier `src/main/kotlin` puis refermer puis rouvrir | Le premier dépliement affiche les enfants après un court instant (latence SAF réelle) ; refermer/rouvrir est **instantané** (cache ViewModel, aucune requête supplémentaire) ; un indicateur de chargement apparaît par nœud pendant l'énumération |
| E9 | Dossier profond et noms à caractères spéciaux (ex. `gradle/wrapper`, fichier à espaces/accents) | L'indentation suit la profondeur ; les noms s'affichent tels quels (ellipsés si trop longs) ; aucun crash |
| E10 | Projet Java vs projet Kotlin | Mêmes comportements : tri, icônes (`.java`), dépliement paresseux |
| E11 | Révoquer la permission : `adb shell pm revoke` impossible sur SAF — à la place, retirer l'accès depuis les réglages système (Stockage) ou réinstaller l'app | Le tiroir bascule en **bandeau « Permission perdue »** avec action « Résoudre à l'accueil » ; l'accueil montre la carte du projet avec Relocaliser/Retirer |
| E12 | Supprimer le dossier du projet depuis un gestionnaire de fichiers, puis bouton Actualiser du tiroir | Bandeau « Projet introuvable » ; « Résoudre à l'accueil » ramène à l'accueil |
| E13 | Bouton Actualiser (en-tête du tiroir) après modification externe du dossier | L'arborescence est **rechargée** (nouveaux fichiers visibles, disparus retirés), les dépliements sont réinitialisés ; la barre de navigation basse : **Explorateur** active, Recherche et Git grisés avec « Bientôt disponible » en description |

## Espace de travail — éditeur et onglets (étape 15)

Préambule : un projet Kotlin **et** un projet Java générés à l'étape 9 ;
l'acceptation exige d'ouvrir, d'éditer et d'enregistrer un `.kt` et un
`.java` réels, sans fuite après ouverture/fermeture répétée de dix onglets
(LeakCanary en build debug).

| # | Action | Attendu |
|---|---|---|
| E14 | Tiroir → toucher `src/main/kotlin/.../Main.kt` | Un **onglet** apparaît (icône Kotlin, nom), l'éditeur affiche le contenu **coloré** (Kotlin) ; le tiroir reste ouvert, la zone vide disparaît |
| E15 | Ouvrir un `.java` du projet Java, puis basculer entre les deux onglets | Chaque onglet retrouve **son** contenu et sa coloration (Java) ; un seul `EditorView` en mémoire — le rebranchement est instantané, sans rechargement |
| E16 | Éditer un fichier (taper du texte) puis attendre ~2 s | Le **point de modification** remplace la croix de fermeture ; après le délai d'inactivité, il disparaît (auto-sauvegarde) ; rouvrir le fichier depuis un gestionnaire confirme le contenu écrit |
| E17 | Éditer puis appuyer sur l'action **Enregistrer** de la toolbar | L'écriture est immédiate, le point disparaît ; en cas d'échec (stockage retiré) : snackbar d'échec, l'onglet **reste sale** — jamais de perte silencieuse |
| E18 | Onglet sale → croix (ou menu contextuel → Fermer) | Dialogue **Enregistrer / Ne pas enregistrer / Annuler** ; « Ne pas enregistrer » ferme sans écrire ; « Annuler » laisse l'onglet ouvert et sale |
| E19 | Deux onglets sales → retour système | Dialogue **agrégé** (« 2 fichiers ont des modifications non enregistrées ») : « Enregistrer tout » écrit puis quitte, « Ne pas enregistrer » quitte, « Annuler » reste |
| E20 | Menu contextuel d'onglet : Fermer les autres / Fermer tout / Déplacer / Copier le chemin | Les **propres ferment immédiatement**, seuls les sales confirment ; le déplacement réordonne ; « Copier le chemin » met `src/main/.../Main.kt` au presse-papiers (snackbar) |
| E21 | Toucher un fichier binaire (`gradle/wrapper/gradle-wrapper.jar`) | **Aucun onglet** : la feuille système « Ouvrir avec » propose le fichier ; sans application : message « Aucune application ne sait ouvrir ce fichier » |
| E22 | Fuites : ouvrir dix onglets puis les fermer tous (×3), naviguer, faire tourner | Aucune fuite rapportée par LeakCanary (build debug) ; aucun blocage du fil principal (StrictMode) lors des écritures |
| E23 | « Ne pas garder les activités » + mise en arrière-plan prolongée, puis retour | Les **onglets rouverts** avec leur contenu relu, l'onglet actif restauré — y compris après mort du processus |

## Espace de travail — panneau inférieur (étape 16)

Préambule : un projet ouvert dans l'espace de travail ; l'acceptation
exige les trois états au doigt et par les boutons, le journal vivant en
direct et la survie à la rotation (prompt compagnon 6, ADR 0029).

| # | Action | Attendu |
|---|---|---|
| E24 | Appui sur l'en-tête du panneau | Le panneau passe à **mi-hauteur** (en-tête + onglets + fenêtre) ; un nouvel appui le **replie** (en-tête seul, 48 dp) |
| E25 | Glisser la poignée vers le haut / bouton **agrandir** | Le panneau passe **étendu** (pleine hauteur) ; le bouton agrandir suit replié → mi-hauteur → étendu, puis redescend |
| E26 | Panneau **étendu** → retour système | Le panneau **se réduit** (mi-hauteur) — il ne quitte pas l'espace ni ne ferme le tiroir ; un second retour reprend le comportement antérieur (tiroir, onglets sales, sortie) |
| E27 | Onglet **Journal** : naviguer dans l'app (accueil, paramètres) puis revenir | Les entrées récentes de **la session** s'affichent (niveau coloré, heure, étiquette, message) et la liste **défile en direct** vers la plus récente ; le badge de l'en-tête compte les entrées |
| E28 | Décocher toutes les puces de niveau, puis n'en cocher qu'une (ex. Erreur) | Ensemble vide = **tous** les niveaux ; un niveau coché est **retenu** (seules les entrées de ce niveau restent) ; état vide explicite si la fenêtre filtrée est vide |
| E29 | Toucher « Ouvrir le journal complet » | L'**écran Diagnostic** s'ouvre (historique complet, exports, effacement) ; revenir retrouve l'espace de travail intact |
| E30 | Onglets **Sortie** puis **Problèmes** | Chaque stub affiche **son message explicite** (« La console apparaîtra ici… » / « Les problèmes de compilation… ») — pas de zone blanche ni d'apparence de fonctionnalité cassée ; badge masqué |
| E31 | Panneau à mi-hauteur, onglet Problèmes → **rotation** de l'écran | Le panneau **reste à mi-hauteur** sur le **même onglet**, filtres du journal conservés |

## Espace de travail — actions du tiroir et finitions (étape 17)

Préambule : un projet Kotlin généré à l'étape 9 ; l'acceptation exige le
parcours complet « Ouvrir un projet → créer un fichier → l'éditer →
l'enregistrer → le renommer → le supprimer → rouvrir le projet et
retrouver les onglets » sans erreur (prompt compagnon 6).

| # | Action | Attendu |
|---|---|---|
| E32 | Tiroir → appui long sur un dossier → **Nouveau fichier** « Notes.md » | Le fichier apparaît dans le dossier déplié **et s'ouvre en onglet** (édition immédiate) |
| E33 | En-tête du tiroir → bouton **créer** → Nouveau dossier « essais » | Le dossier apparaît à la racine (un projet vide reste utilisable) ; le nom invalide (« a/b », « .. », fin « . ») est refusé **dans le dialogue** avec la raison localisée |
| E34 | **Renommer** un fichier ouvert en onglet (menu contextuel) | L'onglet **suit** : même contenu, nouveau nom/chemin, l'écriture continue de fonctionner ; l'arborescence montre le nouveau nom, le dossier parent reste déplié |
| E35 | **Supprimer** un fichier ouvert en onglet | Confirmation (rappel du nom, « action définitive ») ; après confirmation l'onglet ferme et le fichier disparaît du tiroir et du stockage |
| E36 | Supprimer un **dossier** contenant des fichiers ouverts | Confirmation « et tout son contenu » ; les onglets sous ce dossier ferment, sessions libérées (aucune fuite LeakCanary) |
| E37 | Ouvrir deux fichiers, quitter l'espace, **rouvrir le projet** depuis l'accueil | Les onglets **rouvrent** dans le même ordre avec l'onglet actif restauré (`.codeide/local/workspace-state.json`) ; un projet jamais ouvert démarre vide |
| E38 | TalkBack : parcourir tiroir, onglets, panneau | Chaque nœud, onglet (nom + état de modification), bouton et puce annoncé ; cibles ≥ 48 dp ; navigation D-pad possible |
| E39 | Tablette (sw600dp) / paysage : ouvrir l'espace | Tiroir **permanent** (ADR 0026), panneau et onglets fonctionnels ; thème sombre et animations réduites suivent l'appareil |

## Espace de travail — audit final de Phase 1 (étape 18)

Préambule : un projet Kotlin **généré par le wizard** (étape 9), un
**dossier importé** sans `.codeide`, et l'APK **release** construit par
`./gradlew assembleRelease` (R8) installé sur l'appareil ; l'acceptation
exige le parcours complet de la Phase 1 sans erreur, en debug comme en
release (prompt compagnon 6, ADR 0031).

| # | Action | Attendu |
|---|---|---|
| E40 | Ouvrir le projet Kotlin **créé par le wizard** | L'en-tête du tiroir affiche la ligne de type : « **Modèle Kotlin · JVM** » (nom i18n du catalogue) suivi de « · v… » si le fichier porte la version ; le nom et le chemin du projet restent au-dessus |
| E41 | Ouvrir le **dossier importé** (sans `.codeide`), puis un projet créé dont `.codeide/project.json` a été supprimé | Importé : « **Dossier importé** » ; projet créé sans fichier : « **Type de projet non reconnu** » — jamais de blocage ni d'erreur, l'édition fonctionne normalement |
| E42 | Paramètres → Langue : passer en anglais, revenir dans l'espace | La ligne de type **re-résout** son nom dans la nouvelle langue (« Kotlin · JVM template ») — le fichier n'est pas relu, seul le libellé change |
| E43 | Ouvrir l'espace de travail (régression du plantage 8b5b73f1) | L'écran **s'ouvre sans plantage** ; la barre basse du tiroir montre **trois destinations** — Explorateur active, Recherche et Git grisés (« Bientôt disponible » en description) |
| E44 | **APK release (R8)** : parcours complet — accueil, wizard, éditeur, onglets, panneau, diagnostic | Tout fonctionne **identiquement au debug** : `EditorView` s'affiche et se rebranche (règles ProGuard cel-ui correctes), sessions libérées, journaux et plantages écrits ; démarrage sensiblement plus rapide |
| E45 | **Installation réelle (T2/T3)** : à partir de l'écran d'installation (onboarding ou à la demande), lancer l'installation sur appareil aarch64 avec ≥ 1,5 Gio libres | Progression visible jusqu'à `Terminee` ; `$PREFIX/bin/bash` exécutable ; `java -version` répond dans une session ; `git --version` répond ; l'état des outils (openjdk-17, git) conforme au dépôt APT du jour |
| E46 | **Reprise après échec** : couper le réseau pendant le téléchargement, réessayer après reconnexion | Échec typé « réseau indisponible » immédiat, staging nettoyé ; la reprise retélécharge et rejoue tout le pipeline (second stage compris — son verrou vit sous le préfixe) |
| E47 | **Annulation** : annuler pendant le téléchargement | État `Annulee`, retour immédiat, aucun résidu `usr-staging` ni `bootstrap-staging.zip` sous `filesDir` |
| E48 | **Espace disque insuffisant** : remplir le stockage sous le seuil (1 Gio) avant l'installation | Refus typé **avant tout téléchargement** (aucune requête réseau émise) avec message actionnable |
| E49 | **Correction du dépôt APT** : après installation, lire `$PREFIX/etc/apt/sources.list` | Ligne unique avec `[trusted=yes]` pointant vers `https://jjoblab.github.io/codeide-packages/apt/codeide-main stable main` (la ligne embarquée sans option est corrigée) |
| E50 | **Parcours T3 — onboarding** : terminer l'assistant en choisissant « Installer maintenant » à l'étape Terminal | L'écran de progression s'ouvre au-dessus de l'assistant ; revenir en arrière n'interrompt pas l'installation ; l'étape affiche « déjà installé » au retour une fois terminée ; « Plus tard » mène à l'étape Apparence sans blocage |
| E51 | **Parcours T3 — à la demande** : après « Plus tard », bandeau de l'accueil → « Installer les outils » | Le même écran s'ouvre (même liste d'états) ; à la fin de l'installation, le bandeau disparaît **sans repasser par l'accueil** ; aucune double installation possible |
| E52 | **Rotation et reprise** : faire pivoter l'appareil pendant l'installation, fermer puis rouvrir l'écran | La progression reprend où elle en était (état partagé du singleton) ; aucune installation relancée ; l'annulation reste possible à tout instant |

## Terminal — runtime des sessions (étape 22 = T4)

Préambule : bootstrap **installé** sur un appareil aarch64 (E45) ; ces
tests vérifient le comportement observable du runtime sans l'écran
plein écran (T5) — via logcat (`adb shell dumpsys activity services
jo.codeide | grep -i terminal`) et le tiroir/la notification. L'acceptation
exige le cycle de vie documenté (ADR 0035 : notification tant qu'une
session vit, arrêt automatique sinon).

| # | Action | Attendé |
|---|---|---|
| E53 | **Survie en arrière-plan** : créer une session (via un futur point d'entrée — à ce stade par le test instrumenté ou T5), mettre l'app en arrière-plan ≥ 10 min | La notification « N session(s) de terminal active(s) » reste visible ; le shell répond encore au retour (le service foreground a maintenu le processus) |
| E54 | **Notification honnête** : terminer la session avec `exit`, puis la fermer depuis la liste | Tant que le shell vit : notification présente et à jour ; après `exit` la session reste **visible morte** (`isAlive = false`) ; à la fermeture explicite, la notification disparaît et le service s'arrête (plus de service actif) |
| E55 | **Fermeture réelle** : lancer une commande longue (`sleep 300`) puis fermer la session | Le processus shell est **réellement terminé** (`adb shell ps -A | grep sleep` vide) — pas seulement masqué de la liste |
| E56 | **Redémarrage après mort du processus** : tuer le processus de l'app (`adb shell am kill jo.codeide`) avec des sessions ouvertes | Le service `START_STICKY` repart, constate l'état des sessions et s'arrête proprement si aucune ne survit (aucune notification orpheline persistante) |

## Terminal — écran plein écran (étape 23 = T5)

Préambule : bootstrap **installé** (E45) et APK debug v0.24.0+ ; l'écran
s'ouvre pour l'instant via la navigation (`openTerminal`, points d'entrée
visibles en T6) — `adb shell am start -n jo.codeide/.feature.terminal.TerminalActivity`
déclenche aussi l'écran. L'acceptation exige : commandes de base,
sessions simultanées, fermeture propre, rotation sans perte (section 9
du prompt Terminal-1).

| # | Action | Attendu |
|---|---|---|
| E57 | **Session réelle** : créer une session, taper `ls`, `pwd`, puis `java -version` et `gradle -version` si installés | Le shell répond dans le `TerminalView` (couleurs, défilement) ; le prompt réapparaît après chaque commande ; la notification foreground « 1 session active » est visible |
| E58 | **Sessions simultanées** : créer 3 sessions (`+`), renommer la 2e (« build », appui long → Renommer), dupliquer la 3e, naviguer entre les onglets | Chaque onglet garde **son propre état** (processus, historique distincts) ; le libellé renommé s'affiche ; la duplication ouvre un shell dans le même répertoire ; le `+` recrée toujours à la fin de liste |
| E59 | **Fermeture propre** : lancer `sleep 300` puis fermer l'onglet ; dans une autre session au prompt, fermer directement | La fermeture pendant une commande demande **confirmation** (« une commande semble en cours ») ; au prompt, fermeture **directe** ; le processus est réellement terminé (E55) ; l'onglet disparaît, la session active rebascule |
| E60 | **Rotation et reprise** : faire pivoter l'appareil (et changer le thème clair/sombre en cours) avec des sessions ouvertes | Aucune session perdue ; le rendu se rebranche et garde le transcript ; le thème du rendu suit l'app ; la police suit le réglage (Paramètres → Apparence → Taille de la police du terminal) |
| E61 | **Clavier étendu** : Tab (complétion), Échap, flèches (historique), Ctrl puis `c` | Tab complète, Échap interrompt la saisie, ↑/↓ parcourt l'historique ; Ctrl (bouton en surbrillance) combiné à `c` interrompt une commande ; Alt de même pour les combinaisons ; le retour système **ferme l'écran** sans tuer les sessions |

## Étape 24 — Terminal T6 : intégration accueil et tiroir (v0.25.0)

Préambule : APK debug v0.25.0+, un projet créé sur le stockage principal
(`/storage/emulated/0/…`). Critères d'acceptation (section 9 du prompt
Terminal-1) : les deux points d'entrée ouvrent le **même** écran avec la
même liste de sessions ; la carte d'aperçu se met à jour en direct ;
aucune régression sur `feature:home`/`feature:editor`.

| # | Action | Attendu |
|---|---|---|
| E62 | **Correctif plantage** : ouvrir l'écran « Installer les outils du terminal » (accueil sans bootstrap → bandeau, ou toolbar Terminal) | L'écran s'ouvre **sans plantage** (rapport 30e81ee0 corrigé : plus de fermeture immédiate de l'app) ; l'invite, la progression et le bouton Installer répondent |
| E63 | **Toolbar de l'accueil, bootstrap installé** : bouton Terminal dans la barre de titre | L'écran plein écran du terminal s'ouvre ; une nouvelle session démarre dans le `HOME` canonique ; la **même liste** de sessions que depuis le tiroir d'un projet |
| E64 | **Toolbar de l'accueil, bootstrap absent** : même bouton sans bootstrap installé | L'**écran d'installation** s'ouvre directement (jamais un terminal non fonctionnel) ; après installation, le même bouton ouvre le terminal |
| E65 | **Carte du tiroir** : ouvrir un projet → tiroir → destination « Terminal » de la barre basse | La carte remplace l'explorateur : compteur de sessions actives, libellé + dernière sortie (monospace) + pastille de la session active ; retour sur « Explorateur » : arborescence intacte |
| E66 | **Mise à jour en direct** : terminal ouvert depuis l'accueil, taper des commandes, puis rouvrir le tiroir du projet | La carte reflète les sessions **en direct** (compteur, sortie récente) ; fermer un onglet dans l'écran plein écran met la carte à jour ; la carte est **identique** depuis les deux points d'entrée |
| E67 | **Nouvelle session dans ce projet** : tiroir → Terminal → état vide → « Nouvelle session dans ce projet » | La session est créée dans le **dossier réel du projet** (`pwd` le confirme : `/storage/emulated/0/…/NomDuProjet`) puis l'écran plein écran s'ouvre dessus ; pastille verte ; session visible depuis l'autre point d'entrée |
| E68 | **Garde-fou bootstrap dans le tiroir** : désinstaller les données ou vider le stockage interne (bootstrap absent), rouvrir l'espace | La carte montre « Outils du terminal non installés » + « Installer les outils » → écran d'installation ; **aucune** session fantôme créée |

## Correctifs Terminal C1/C2 — profil shell de CodeIDE (v0.36.1)

Préambule : APK debug v0.36.1+, installation de base du bootstrap **terminée**
(E45 — le profil est posé à l'installation de base, avant tout paquet
optionnel). Ces tests couvrent les correctifs C1 (PS1 personnalisé) et C2
(message de bienvenue avec l'état des outils) du prompt compagnon Terminal.

| # | Action | Attendu |
|---|---|---|
| E69 | **Profil généré** : après l'installation de base, lire `$PREFIX/etc/codeide.sh` (`adb shell run-as jo.codeide cat files/usr/etc/codeide.sh`) | Le fichier existe et contient : le `PS1` personnalisé `codeide:<répertoire> (<branche>)$`, la fonction `__codeide_git_branch`, la bannière de bienvenue et les lignes d'état `JAVA_HOME`/`ANDROID_HOME`/`Gradle` — régénéré en entier à chaque réinstallation |
| E70 | **Inclusion du .bashrc** : lire le `.bashrc` du HOME du bootstrap (`adb shell run-as jo.codeide cat files/home/.bashrc`) | **Une seule** ligne `[ -f "$PREFIX/etc/codeide.sh" ] && . "$PREFIX/etc/codeide.sh"` — relancer l'installation complète une seconde fois : la ligne n'est **pas dupliquée** (contenu du `.bashrc` préservé) |
| E71 | **Invite et bienvenue visibles** : ouvrir une session de terminal (JDK et git installés) | L'invite affiche `codeide:<répertoire>$` colorée ; dans un dossier Git : `codeide:<répertoire> (main)$` ; la bannière « CodeIDE — terminal intégré » s'affiche à l'ouverture avec l'état réel du JDK (`/…/java-17…`), du SDK Android et de Gradle |
| E72 | **Bienvenue silencieuse hors session** : lancer un script via le tooling (build Gradle) et surveiller sa sortie | Aucune bannière ne pollue la sortie des shells **non interactifs** (garde `case $- in *i*)`) — le tooling n'affiche jamais « CodeIDE — terminal intégré » |

## Correctif Terminal C3 — copier la sélection (v0.36.2)

Préambule : APK debug v0.36.2+, terminal ouvert avec une session vivante.
Ce test couvre le correctif C3 du prompt compagnon Terminal : l'action
« Copier » de la barre d'action native après sélection était
silencieusement ignorée (`ClientTermux.onCopyTextToClipboard` était un
no-op).

| # | Action | Attendu |
|---|---|---|
| E73 | **Copier une sélection réelle** : sélectionner du texte du transcript (appui long → poignées), appuyer sur « Copier » dans la barre d'action native, puis coller (`Ctrl puis V`, ou coller dans une autre app) | Le texte sélectionné arrive au presse-papiers **réellement** (le collage le prouve) ; sur Android 12-, un Toast « Copié » confirme ; sur Android 13+, le bandeau système suffit (aucun double toast) ; la copie automatique en sortie de mode sélection (ADR 0059) continue de coexister |

## Correctif Terminal C4 — commande `gradle` du terminal (v0.36.3)

Préambule : APK debug v0.36.3+, bootstrap installé (la commande est posée à
l'installation de base). Ce test couvre le correctif C4 du prompt Terminal :
« gradle: command not found » malgré un projet déjà construit — le bootstrap
n'installe JAMAIS de paquet gradle (chaque projet peut exiger sa version),
la commande découvre Gradle au moment de l'appel.

| # | Action | Attendu |
|---|---|---|
| E74 | **Dans un projet avec wrapper** : ouvrir un terminal dans le dossier d'un projet créé par l'app, taper `gradle --version` | La version du wrapper DU projet répond (le `./gradlew` local est exécuté) ; `ls -l $PREFIX/bin/gradle` montre le bit d'exécution posé |
| E75 | **Hors projet, cache présent** : après un build lancé depuis l'app (le wrapper a téléchargé sa distribution), taper `gradle --version` depuis le HOME | La distribution du wrapper la plus récemment utilisée répond (`$GRADLE_USER_HOME/wrapper/dists/…`) — plus JAMAIS « command not found » sans explication |
| E76 | **Rien trouvé** : supprimer `$GRADLE_USER_HOME/wrapper/dists` (ou installation neuve), taper `gradle build` hors de tout projet | Sortie en **127** avec le message qui EXPLIQUE : rejoindre un projet avec `./gradlew`, ou lancer d'abord une sync/build depuis l'app — jamais un échec muet |

## Correctif Tooling C5 — avertissement bénin du daemon Gradle (v0.36.4)

Préambule : APK debug v0.36.4+, bootstrap installé (JDK), un projet avec
wrapper, premier build après installation (l'avertissement apparaît
typiquement au premier lancement du daemon Gradle — voir
`docs/TOOLING.md`, section « Avertissement bénin du daemon Gradle »).

| # | Action | Attendu |
|---|---|---|
| E77 | **Avertissement apaisé** : lancer un build (premier lancement du daemon Gradle) et observer l'onglet Sortie pendant l'exécution | La ligne « Unable to set daemon's environment variables… » apparaît éventuellement en style INFORMATIF (couleur de sortie standard, pas le rouge d'erreur) ; le build se termine normalement (réussi) ; les VRAIES erreurs de stderr restent rouges — l'utilisateur n'est pas alarmé pour un diagnostic bénin documenté |

## Section Éditeur des Paramètres consommée (v0.37.0)

Préambule : APK debug v0.37.0+, un projet ouvert dans l'espace de travail
avec au moins un fichier Kotlin ouvert dans l'éditeur. Les changements
s'observent **à l'éditeur ouvert** (retourner aux Paramètres via le tiroir,
changer, revenir à l'éditeur sans le fermer — voir ADR 0066).

| # | Action | Attendu |
|---|---|---|
| E78 | **Chaque réglage s'applique en direct** : avec l'éditeur ouvert, passer par Paramètres → Éditeur et changer successivement le thème (ex. Dracula), la taille de police (Grande), le retour à la ligne, la minimap et les caractères non imprimables, puis revenir à l'éditeur | Chaque changement est déjà visible au retour SANS recréation : fond/colours du thème Dracula, texte plus grand, longues lignes ramenées, bande minimap à droite, marques ¶/espaces visibles ; la rangée maître Éditeur affiche « Dracula · Grande » en sous-titre ; le thème « Automatique » retrouve clair/sombre en basculant le mode du système |
| E79 | **Ligatures et avertissement** : activer « Ligatures de police » dans la section, ouvrir un fichier Kotlin avec des comparaisons (`!=`, `->`) | La légende « Désactive la coloration syntaxique » reste visible sous l'interrupteur ; une fois activée, la coloration disparaît effectivement (compromis de la bibliothèque) — l'utilisateur n'est pas surpris |
| E80 | **Sauvegarde automatique coupée** : désactiver « Sauvegarde automatique », éditer un fichier, attendre plus de 2 secondes | L'onglet garde son point de modification (sale) et le fichier N'EST PAS écrit ; l'enregistrement manuel (icône enregistrer de la toolbar) écrit le fichier et repasse l'onglet propre |

## Correctif lint CI — drapeaux Gradle et TypographyDashes (v0.37.1)

Préambule : dépôt poussé sur GitHub (main + tag v0.37.1), APK debug
v0.37.1+. Le correctif n'ajoute que des attributs `tools:ignore` (lint) sur
les chaînes de la configuration du tooling — aucun texte affiché ne change.

| # | Action | Attendu |
|---|---|---|
| E81 | **CI vert après push** : pousser main + tag v0.37.1 sur GitHub, ouvrir l'onglet Actions du dépôt et suivre le run « Compilation et vérification complète » | La tâche `:feature:editor:lintDebug` passe (plus aucune erreur TypographyDashes) ; le run arrive au vert jusqu'à `assembleDebug` et publie l'artefact APK |
| E82 | **Textes inchangés à l'écran** : dans l'espace de travail, ouvrir la configuration du tooling (onglet Sortie → engrenage) | Les textes du mode hors ligne (« Passe --offline aux builds… »), de l'aide aux arguments (« Séparés par des espaces — ex. --stacktrace --info ») et de la note console (« forcée en mode texte (--console=plain)… ») s'affichent avec leurs drapeaux Gradle intacts, doubles tirets compris |

## Signature des APK — fin des conflits de package (v0.37.2)

Préambule : correctif ADR 0067 — l'identité debug (publique) est versionnée
dans `config/signature/debug.keystore`, câblée par le convention plugin et
verrouillée en CI par `scripts/verify-signature.sh`. Les APK v0.37.0/v0.37.1
déjà installés portent des signatures historiques aléatoires : une dernière
désinstallation est requise, une seule fois.

| # | Action | Attendu |
|---|---|---|
| E83 | **Transition unique** : avec la v0.37.1 installée, tenter d'installer l'APK v0.37.2 (CI ou portail), constater le refus, désinstaller CodeIDE, installer v0.37.2 | Le refus initial est ATTENDU (signature historique ≠ identité versionnée) ; après désinstallation + installation, v0.37.2 fonctionne ; les données Termux/bootstrap du préfixe doivent être exportées avant désinstallation si elles comptent (la désinstallation efface les données applicatives) |
| E84 | **Mises à jour sans conflit, pour toujours** : à la livraison suivante (v0.37.3+), télécharger l'APK CI (artefact `CodeIDE-vX.Y.Z-debug`) et l'installer PAR-DESSUS la version installée, sans désinstallation | L'installation procède comme une mise à jour normale : les données et réglages sont conservés, aucun « conflit de package » ; l'étape CI « Signature = keystore versionné (ADR 0067) » est verte sur le run concerné |

## Refonte installation — nouvelle interface E5 (v0.59.0)

Préambule : APK debug v0.59.0+ sur appareil aarch64. L'écran d'installation
s'ouvre depuis l'accueil (bandeau d'invitation) ; l'écran Environnement
depuis Paramètres → carte Environnement → « Environnement de
développement ». Maquettes de référence : `docs/preview/`
`installation-environnement.html`.

| # | Action | Attendu |
|---|---|---|
| E85 | **Stepper et états** : ouvrir l'écran d'installation puis lancer le parcours | Les 4 cartes passent par « En attente » → « En cours » → « Terminée » dans l'ordre strict ; la carte en cours affiche sa sous-étape ; l'entête compte « Étape N sur 4 » et la barre progresse ; les versions vérifiées apparaissent sur les cartes terminées (ex. `17.0.20 · TLS vérifié`) |
| E86 | **Vitesse mesurée** : pendant le téléchargement d'un gros composant (build-tools), observer la ligne sous la barre globale | La vitesse en Mio/s et le temps restant s'affichent et évoluent (mesurés) ; sans mesure exploitable, la ligne est absente — jamais une vitesse figée ou fabriquée |
| E87 | **Consentement licence** : après les phases 1-3 vérifiées, avant la phase Android | La carte licence apparaît (résumé + case) ; « Installer le SDK Android » est inactif tant que la case n'est pas cochée ; après acceptation, la carte disparaît définitivement (relance de l'app incluse — date persistée § 12.5) |
| E88 | **Journal, annulation, échec** : déplier le journal, couper le réseau en phase 2, utiliser Annuler puis Réessayer | Le journal monospace se replie/déplie et est copiable ; l'échec affiche la sortie réelle de la commande en cause dans la carte ; Annuler arrête (notification incluse) ; « Réessayer cette phase » rejoue la phase fautive seule |
| E89 | **Tablette sw600dp + rotation** : ouvrir l'écran sur tablette (ou émulateur 600dp+), faire pivoter l'écran pendant l'exécution | Stepper à gauche, journal à droite toujours visible (bouton de bascule absent) ; la rotation ne coupe ni l'installation ni l'affichage (service de premier plan, ADR 0087) |
| E90 | **Écran Environnement** : Paramètres → Environnement de développement, avec un SDK installé | Une rangée par composant : version, révision, taille réelle en Mio, état (« vérifié », JDK « vérifié (paquet APT) ») ; « Vérifier (légère) » rejoue les contrôles ; « Vérification approfondie » génère un projet et lance un vrai `assembleDebug` (ADR 0089) ; « Désinstaller » exige une confirmation puis retire la rangée du disque ; « Copier le diagnostic » place journal + récapitulatif dans le presse-papiers |

## Refonte installation — migration des installations existantes E6 (v0.60.0)

Préambule : APK debug v0.60.1+ sur appareil aarch64. **Prérequis côté
dépôt** : le manifeste v2 doit être servi par GitHub Pages sur
`jjoblab/codeide-tools` (URL `https://jjoblab.github.io/codeide-tools/
manifests/v2/latest.json`, ADR 0006 du dépôt `codeide-tools`) — sans lui,
la phase 4 (nouvelle comme adoption) échoue en `Reseau`/`ManifesteInvalide`
avec un message explicite. Les scénarios E91-E93 partent d'un appareil ayant vécu
l'ancien parcours (≤ v0.54.0, bootstrap installé + `codeide-env` achevé
ou commande `android-sdk` jouée) — c'est le scénario de migration de
l'ADR 0091 § 1.

| # | Action | Attendu |
|---|---|---|
| E91 | **Adoption appareil ancien complet** : installer v0.60.0 PAR-DESSUS la v0.54.0 (signature commune ADR 0067, données conservées), ouvrir l'écran d'installation, accepter la licence puis lancer | Phases 1-3 : étapes sautées (« déjà vérifiée — reprise ») SANS retélécharger l'archive du bootstrap ni les paquets (seul `pkg update` rafraîchit l'index) ; phase 4 : composants « présent sans quadruplet, vérifié par exécution — adopté » dans le journal, **zéro téléchargement d'archive de composant** ; les 4 phases finissent vérifiées ; Paramètres → Environnement affiche les quadruplets reconstruits (versions + révisions du plan) |
| E92 | **Appareil neuf** : installation propre (données effacées), lancer le parcours complet | Comportement inchangé depuis E4 : 4 phases exécutées, composants téléchargés une seule fois, récapitulatif final et « Créer mon premier projet » |
| E93 | **Appareil à moitié installé** : à partir d'un appareil ancien, supprimer UN composant (`home/android-sdk/platforms/android-37.2` par exemple, ou Désinstaller depuis l'écran Environnement), puis Réparer la phase Android | Seul le composant manquant est retéléchargé et réinstallé ; les autres sont adoptés sans téléchargement ; la phase finit vérifiée |
| E94 | **Scripts anciens orphelins** : sur l'appareil migré E91, ouvrir le terminal intégré | `JAVA_HOME`/`ANDROID_HOME`/`PATH` corrects (injectés par session, `ProcessEnvironmentProvider`) ; les commandes `gradle`/`android-sdk`/`codeide-env` héritées, si présentes, répondent toujours mais ne sont plus mises à jour par l'application (rupture assumée, CHANGELOG 0.60.0) |
| E95 | **Bandeau accueil et daemon** : après adoption E91, revenir à l'accueil, puis ouvrir un projet et lancer un build | Le bandeau « terminal non installé » est absent ; le daemon Gradle démarre avec le JDK adopté (empreinte E4) ; un `assembleDebug` compile avec les composants adoptés — la contre-vérification des `verify` du manifeste v2 sur les binaires du manifeste v1 (même layout, mêmes versions 35.0.2) est LE point à surveiller (ADR 0091 § 1, non vérifié en simulation) |

## Mission « Exécuter » — Run et Logcat sans adb (R1→R5, v0.81.0→v0.86.0)

Préambule : APK debug v0.86.0+ sur appareil aarch64, bootstrap installé,
un projet Android de test créé (wizard, application vide) — le pont de
journaux est injecté dans les builds debug des projets QUAND le réglage
« Journaux des applications exécutées » est actif (défaut).

| # | Action | Attendu |
|---|---|---|
| X1 | Bouton **Run** de la barre de l'éditeur | Bascule sur la Console (canal BUILD), `:app:assembleDebug` s'exécute ; à la réussite : « Installation de l'application… », la confirmation système (première fois), puis l'application démarre PAR-DESSUS CodeIDE et le snackbar « Application lancée » paraît |
| X2 | Première installation : refuser « sources inconnues » puis accepter | Refus : snackbar « Installation annulée » ; acceptation : l'écran système s'ouvre, la reprise est AUTOMATIQUE au retour (≤ 5 min) — aucun bouton à retoucher |
| X3 | Onglet **Logcat** pendant que l'application tourne | Les journaux de l'application coulent EN DIRECT (table monospace : heure, pid-tid, étiquette, niveau coloré, message) — sans adb, sans ordinateur |
| X4 | Taper du texte dans le champ de filtre | Seules les lignes dont le MESSAGE ou l'ÉTIQUETTE contient le texte (casse ignorée) restent ; vider le champ rend tout le tampon |
| X5 | Activer `.*` (regex) et taper un motif invalide (`*debut`) | Une ligne d'erreur rouge « Motif invalide : … » sous le champ — JAMAIS de crash ; un motif valide filtre par regex sur le message |
| X6 | Menu de niveau (barres) → Warning | Les lignes V, D et I disparaissent (masque des niveaux inférieurs, façon Android Studio) |
| X7 | **Pause** pendant le défilement, laisser tourner l'app, reprendre | Le tampon FIGE pendant la pause (la collecte continue) ; la reprise RATTRAPE en un coup (les lignes de la pause apparaissent) |
| X8 | **Effacer** puis laisser l'app journaliser | Le tampon affiché se vide ; les NOUVELLES lignes reviennent ; les sessions gardées (sélecteur) n'ont pas bougé |
| X9 | Sélecteur de processus (puce) | Les sessions vivantes (ampoule verte), terminées et précédentes y sont listées ; en choisir une autre affiche SON tampon ; la plus récente archive s'affiche au démarrage de CodeIDE si rien ne vit |
| X10 | Arrêter l'application depuis le sélecteur système (ou la faire mourir) | Bandeau rouge « Le processus s'est arrêté : … » dans l'onglet ; la session passe au sélecteur en « terminée » ; après relance de CodeIDE elle reste consultable en « précédente » (dernière ligne + raison) |
| X11 | Faire PLANTER l'application (exception dans le code du projet de test) | Snackbar « L'application a planté » + action « Voir la trace » : l'onglet Logcat s'ouvre sur la session morte, la feuille se soulève si repliée |
| X12 | Appuyer sur une ligne de TRACE de pile | Sélecteur « Ouvrir depuis la trace » listant les cadres `.kt`/`.java` ; en choisir un ouvre le fichier source dans l'éditeur, curseur posé à la ligne (clic long reste la COPIE de la ligne brute) |
| X13 | Débranchement : fermer CodeIDE (glisser) pendant que l'app journalise | Rien à observer côté IDE ; au reopening, la session morte est en « précédente » — cohérent, jamais de session fantôme |
| X14 | Réglage « Journaux des applications exécutées » COUPÉ puis Run | Le build produit un APK SANS la bibliothèque (vérifiable : la ligne Gradle « applog » n'apparaît pas) ; l'onglet Logcat reste honnête (« Lancez votre application… ») |
| X15 | Lignes perdues : journaliser en rafale (boucle serrée de logs) | Le bandeau orange « N lignes perdues » paraît (jamais silencieux) et suit le compteur |

## Mission « Historique local » — le filet de sécurité sans Git (H1→H3, v0.82.0→v0.88.0)

Préambule : APK debug v0.88.0+, un projet OUVERT (l'historique est
indépendant de Git — les scénarios fonctionnent avec ou sans dépôt) ;
l'historique vit dans le stockage privé de CodeIDE, jamais dans le
dossier du projet.

| # | Action | Attendu |
|---|---|---|
| Y1 | Popover d'un fichier du projet → **« Afficher l'historique »**, après quelques enregistrements | Feuille « Historique » du fichier : révisions les plus récentes d'abord, groupées par période (Aujourd'hui/Hier/Plus ancien), moments relatifs (« il y a 4 min »), type, taille |
| Y2 | Appuyer une révision, puis **« Restaurer cette version »** | Confirmation honnête (« la version actuelle sera conservée dans l'historique ») ; après oui : le fichier contient la révision, l'onglet ouvert suit, snackbar « Version restaurée » + **Annuler** qui réécrit l'avant |
| Y3 | Diff **vs actuel** / **vs précédente** (bascule de la page de diff) | vs actuel : révision ↔ contenu du disque ; vs précédente : révision ↔ révision plus ancienne du MÊME fichier (lignes +/−, fonds vert/rouge) |
| Y4 | Popover d'un DOSSIER du projet → « Afficher l'historique » | Toutes les révisions des fichiers SOUS le dossier (préfixe strict : `src` ne couvre pas `srcX/`), rangées au NOM DU FICHIER avec le dossier parent en détail |
| Y5 | Popover de la RACINE → **« Modifications récentes »** | Titre « Modifications récentes », sous-titre le projet, révisions du projet ENTIER (bornées aux plus récentes) |
| Y6 | Filtre **« Supprimés seuls »** (modes dossier/projet) | Seules les pierres tombales restent (« Supprimé (retrouvé) ») ; vider le filtre rend tout ; état vide dit « Aucun fichier supprimé sous cette portée » |
| Y7 | Restaurer une révision **« Supprimé (retrouvé) »** dont le dossier existe | Annonce « Le fichier sera recréé… » ; le fichier REPARAÎT à son emplacement d'origine, snackbar « Fichier recréé » + **Annuler** (resuppression immédiate) |
| Y8 | Restaurer une tombale dont le dossier parent a DISPARU (supprimer le dossier entier d'abord) | Snackbar « Recréation impossible : le dossier parent n'existe plus » — jamais de fichier au mauvais endroit, jamais d'URI inventée |
| Y9 | Éditer dans le TERMINAL (`echo coucou > fichier.txt`), puis réouvrir le fichier | À l'ouverture/rafraîchissement, une entrée « Changement externe » paraît dans l'historique du fichier (ADR 0106 : détection, jamais de scrutation) |
| Y10 | Créer `local.properties` (secret), l'éditer, ouvrir son historique | AUCUNE révision — les secrets ne sont jamais historisés (ADR 0105) |
| Y11 | Popover d'un fichier/dossier de l'arbre PRIVÉ | AUCUNE entrée « historique » (l'arbre privé n'est pas capturé) — idem menu contextuel d'un onglet privé |
| Y12 | `git init` dans le projet, committer, puis éditer sans Git | L'historique continue d'enregistrer NORMALEMENT — le filet vit sa vie, indépendant du dépôt (aucune entrée Git dans l'historique) |
| Y13 | Feuille Historique d'un fichier → bouton **« Poser une étiquette »**, nommer « avant essai » | L'étiquette paraît en TÊTE des révisions (type Étiquette, nom « avant essai »), snackbar « Étiquette posée » ; les révisions voisines se lisent « avant / après l'étiquette » |
| Y14 | Poser une étiquette en mode DOSSIER puis en « Modifications récentes » (mode projet) | Mode dossier : l'étiquette est posée SUR le dossier lui-même (elle paraît dans SON historique) ; mode projet : sur le projet ENTIER (nom en libellé principal — le chemin est vide) |
| Y15 | Dialogue d'étiquette → nom VIDE ou espaces seuls → « Poser » | AUCUN effet : rien dans la liste, aucun message — le nom nettoyé est refusé |
| Y16 | Bouton **Exécuter** d'un projet Android, puis « Modifications récentes » | Une étiquette « Avant compilation » paraît (posée silencieusement AVANT le build) — le Run ne ralentit ni n'échoue jamais à cause d'elle |
| Y17 | Paramètres → section **« Historique local »** (carte Environnement) | Rangée « Conserver » avec la valeur courante (5 jours par défaut), sous-titre du maître « 5 jours · indépendant de Git » ; l'empreinte s'affiche (o/ko/Mo) |
| Y18 | Changer la rétention à 1 jour, ROUVRIR le projet, éditer, attendre plus d'un jour (ou régler l'horloge), réouvrir | Les révisions plus vieilles qu'un jour partent à la purge d'ouverture — la réglage s'applique SANS redémarrer l'app (lu à chaque purge) |
| Y19 | « Effacer l'historique local » → confirmer le dialogue | « Historique effacé », empreinte repart à ~0, les feuilles Historique de TOUS les projets sont VIDES — les fichiers du disque n'ont PAS bougé |

## Mission « Previews » — panneau d'aperçu XML (PE1 v0.111.0 + PE2 v0.114.0 + PE3 v0.122.0)

Le rendu vit dans `:preview:xml` (moteur LayoutLib **en intégration source
par build composite** depuis v0.112.0 — checkout frère `../LayoutLib`,
tag v2.2.0 ; JitPack abandonné pour LayoutLib, décision propriétaire —
fil dédié, bitmap off-screen, PREVIEW.md §3 ; PE2 v0.114.0 : le rendu vit
dans le PROCESSUS DÉDIÉ `:preview` — AIDL, DeathRecipient, repli local) ;
ces recettes éprouvent
le câblage complet : FUSE (`res/` du projet), classification, debounce,
« jamais de panneau vide », et l'INERTIE de l'IDE (le rendu ne doit
JAMAIS bloquer la frappe).

| # | Action | Attendu |
|---|--------|--------|
| P1 | Ouvrir un projet Android, ouvrir `res/layout/activity_main.xml` (ou tout layout) | Le menu ⋮ de la toolbar offre **« Aperçu XML »** (invisible sur un fichier non-XML ou non-layout) |
| P0 | (PE2) Après P1 avec le panneau d'aperçu OUVERT et un rendu affiché | `adb shell pidof jo.codeide:preview` NON vide (le processus de rendu vit) ; après ~60 s SANS édition, le pid disparaît (idle-shutdown : déliaison, le processus meurt seul) ; une frappe le relance | 
| P0b | (PE2) Robustesse — depuis un terminal Android : `adb shell am crash jo.codeide:preview` (ou forcer l'arrêt du pid) puis ÉDITER le layout | Le rendu suivant revient SANS que l'éditeur ne bouge : reprise silencieuse (une relance Binder) ; si le processus re-meurt, la session passe en repli local (rendu identique, journal « processus :preview désactivé pour la session ») | 
| P2 | Menu → « Aperçu XML » | Le panneau s'ouvre À CÔTÉ de l'éditeur (partage 50/50 de la largeur) avec le bitmap rendu ajusté à la vue ; l'entrée de menu devient « Masquer l'aperçu » |
| P3 | Pincer, glisser, double-tap dans le panneau | Zoom borné (0,25×–6×), pan borné, double-tap réajuste le rendu à la vue ; damier de transparence derrière les zones alpha |
| P4 | Éditer le texte du layout (couleur, texte, padding) | Après ~400 ms sans frappe : NOUVEAU rendu ; PENDANT les frappes (XML malformé) : le DERNIER rendu valide reste affiché + signal discret « XML en cours de frappe » en bas |
| P5 | Casser volontairement le XML proprement (balise inconnue `<Machin/>`, vue inexistante) | Bandeau rouge en haut de panneau avec le message d'erreur lisible ; le rendu précédent reste visibles dessous |
| P6 | Référencer `@color/…`/`@string/…`/`@dimen/…` définis dans `res/values*` du projet | Rendus RÉSOLUS depuis le projet (le résolveur lit le `res/` réel par le chemin FUSE — même résolveur que le LSP) |
| B1 | Ouvrir le panneau sur un layout MASSIF (imbrications ~300 éléments) puis taper au clavier | La frappe reste FLUIDE (le rendu vit sur un fil dédié, jamais le thread UI) ; latence de rendu complète < 400 ms après debounce |
| B2 | (PE3) Menu → « Aperçu XML » sur un drawable (`res/drawable/*.xml`) | Le drawable est RENDU (taille intrinsèque, 160 dp par défaut) sur le damier de transparence ; les types non supportés par LayoutLib (layer-list complet, ripple…) donnent l'état de leur support réel, jamais d'invention |
| B3 | (PE3) Menu → « Aperçu XML » sur `values/*.xml` | Inventaire du fichier en tableau lisible : une ligne par entrée (`[color] nom` + nuancier coloré, `[dimen]`, `[string]`…) ; racine non `<resources>` = bandeau d'erreur lisible |
| B4 | (PE3) Bouton « Jour »/« Nuit » de la barre de réglages | Le rendu bascule IMMÉDIATEMENT (thèmes `values-night` et ressources nuit du projet priorisés) ; un `@color` défini en jour et nuit change de couleur |
| B5 | (PE3) Bouton de profil (ex. « Pixel 4 · 393×851dp · 440dpi ») | Cycle des profils (Pixel 4 → Pixel 7 Pro → Galaxy S24) : chaque clic relance un rendu immédiat AUX DIMENSIONS du profil (un layout `match_parent` change de largeur visiblement) |
| B6 | (PE3) Tap simple sur une vue du rendu | L'ÉLÉMENT XML correspondant est sélectionné dans l'éditeur (span exact du `<Balise …>` fermé) ; tap sur un enfant d'un `<include>` non résolu : remonte au parent sélectionnable le plus proche, jamais de sélection inventée |
| B7 | (PE3) Déplacer le curseur dans le XML (clic dans le texte de la vue) | La vue correspondante se SURLIGNE dans le rendu (rect couleur primaire translucide) — l'élément le plus interne contenant le curseur |

### État des lieux du catalogue 50 layouts (PE1 — en attente d'appareil)

L'état des lieux comparatif vit dans le DÉPÔT LayoutLib (module
`validation/`, propriétaire du catalogue) :

```bash
# Dans un checkout de jjoblab/LayoutLib (tag ≥ v2.2.0), appareil branché :
ANDROID_HOME=<sdk> ./gradlew :validation:connectedAndroidTest
# Le rapport d'intégration sort dans validation/build/ (harness 50 layouts :
# inflation + mesure + layout, par catégorie — basic/nested/resources/…).
```

Reporter ici, à chaque montée de LayoutLib : nombre de layouts OK /
échecs / temps moyens par catégorie — les limitations résiduelles
(PREVIEW.md §2.3) se lisent dans le diff entre versions. **Aucune
exécution à ce jour (pas d'appareil dans l'environnement de build).**

### Limitations LayoutLib v3.0.0 connues — PE4 audit v0.122.2 + montée v0.125.1 (ADR 0116)

Comportement **attendu** (pas un bug) — le pipeline de preview reste
tolérant (placeholder neutre, dernier rendu valide conservé, philosophie
PE1 X4). Ne pas rapporter comme défauts appareil.

**v0.125.1** : montée LayoutLib v2.2.0 → **v3.0.0**. Les 3 limitations
prioritaires P0/P1/P2 sont **résolues** par l'auteur du dépôt frère :

| Priorité | Limitation | Statut v3.0.0 | Manifestation en preview |
| --- | --- | --- | --- |
| ~~P0~~ ✅ | `<layer-list>`, `<ripple>`, `<inset>`, `<bitmap>` | **Résolu** (parsers dédiés + résolution récursive, +83 tests) | Les fonds complexes (layer-list) et le feedback tactile (ripple) sont désormais rendus. |
| ~~P1~~ ✅ | Qualifiers de langue `values-fr/`, `values-en/` | **Résolu** (`ResourceQualifier` ISO 639/3166 + `setLanguage()`/`setLocale()`, +41 tests) | La preview peut basculer les chaînes FR/EN (à exposer côté CodeIDE — bouton de bascule PE5+). |
| ~~P2~~ ✅ | ConstraintLayout : solver Cassowary non câblé | **Résolu** (`ConstraintSpec` + `ConstraintSolver` Cassowary complet + `BridgeInflater` capture + `RenderService` étape 3bis, +42 tests, 600 total) | Le rendu ConstraintLayout n'est plus approximatif — chaînes, barrières, guidelines, ratios complexes correctement résolus. |
| **P3** | `applyCustomAttributes` stub (attributs `app:*` ignorés) | Toujours présent | Attributs custom des bibliothèques tierces (DataBinding, ViewBinding custom) silencieusement ignorés. |
| **P4** | `<adaptive-icon>` non supporté | Toujours absent | Placeholder à la place de l'icône adaptive. |
| **P5** | `<animated-vector>` non supporté | Toujours absent | Placeholder statique (première frame non affichée — l'animation n'a de toute façon pas de sens en preview statique). |
| **P6** | `LayoutInflater.Factory2`, `LayoutTransition`, `ViewTreeObserver` | Toujours absent | Aucun impact en preview statique. |

Reporter toute limitation nouvelle observée sur appareil : indiquer le
layout, la version LayoutLib (`./gradlew :preview:xml:dependencies |
grep layoutlib`), et la priorité pressentie (fréquence d'usage
constatée dans des projets Android réels).

## Mission « EditorActivity » — bottom sheet, clavier, barre de navigation (Phase B prompt 1, v0.124.2 → v0.124.6)

Les correctifs E1-E7 du prompt 1 (ADR 0118) ne peuvent pas être éprouvés
en tests automatiques (animation IME réelle, comportement barre de
navigation par gestes vs 3 boutons, contraste WCAG AA). Les recettes
suivantes sont à exécuter sur appareil réel (Android 8-15, portrait et
paysage, thème clair et sombre) à chaque montée de version touchant
`feature:editor`.

| # | Action | Attendu |
|---|--------|--------|
| E1 | Ouvrir un projet, ouvrir un fichier, replier le panneau inférieur | L'en-tête replié (48 dp) est entièrement visible et cliquable AU-DESSUS de la barre de navigation (gestes ou 3 boutons) — pas sous la barre |
| E2 | Basculer en navigation par gestes puis à 3 boutons (Réglages système) | L'en-tête replié reste entièrement visible et cliquable dans les deux modes |
| E3 | Sheet replié, mi-hauteur, étendu : toucher le VIDE du sheet (en-tête sans bouton, espace entre les boutons) | Le curseur de l'éditeur NE se déplace PAS, le clavier NE s'ouvre PAS — la touche est consommée par `panneau_inferieur` (clickable/focusable) |
| E4 | Taper dans l'éditeur sheet replié, puis mi-hauteur, puis étendu | Le clavier s'ouvre SANS saut (animation continue via `translationY`), le sheet glisse sous le clavier. Fermeture : le sheet remonte SANS saut à la dernière trame |
| E5 | Après un cycle clavier (ouverture puis fermeture), vérifier l'état du sheet | L'état du sheet (replié/mi-hauteur/étendu) est PRÉSERVÉ à l'identique — aucune écriture de `state` pendant l'animation |
| E6 | Sheet replié, dernière ligne du fichier sous le curseur | La dernière ligne et le curseur sont VISIBLES — la réserve = `peekHeight + insetNavigation` les garde au-dessus du sheet |
| E7 | Lancer un build Gradle (ligne tooling apparaît), sheet replié | La réserve s'ajuste AUSSITÔT (peek élargi) — la dernière ligne reste visible, pas de saut |
| E8 | Changer d'onglet du panneau (Console, Problèmes, Journal, Logcat) | L'éditeur NE bouge PAS — la réserve est constante (peek dynamique recalculé) |
| E9 | Rotation avec le clavier ouvert | Le sheet reste masqué sous le clavier pendant la rotation, revient à `translationY = 0` à la fermeture |
| E10 | Mode multi-fenêtre, clavier flottant ou scindé | Le sheet reste cohérent — pas de saut, pas de zone morte |
| E11 | Thème clair puis sombre (Réglages système ou bascule CodeIDE) | Les barres système (statut + navigation) ont un contraste lisible dans les deux thèmes — `SystemBarStyle.auto` s'adapte |
| E12 | Android 8-9 (API 26-29) si disponible | Le détecteur de hauteur de secours fonctionne (insets IME silencieux) — pas de saut, pas de sheet fantôme |

Reporter toute régression observée sur appareil : indiquer l'API
Android, le mode de navigation (gestes vs 3 boutons), le thème
(clair/sombre + couleurs dynamiques si actives), et la recette
E1-E12 qui échoue.

## À venir

- **Phase 2** : voir le plan détaillé dans `docs/ROADMAP.md` (terminal,
  tooling, plugins, services, autres langages).

## Mission IA — coffre de clés (lot IA-2 → v0.137.0, ADR 0124)

> La logique du coffre (`CoffreClesKeystore`) est couverte en JVM par une
> clé maître logicielle de remplacement ; le **Keystore réel** ne peut pas
> être simulé en JVM. Cette procédure valide la partie plateforme.

1. **Saisie de la clé** — Paramètres → IA → Fournisseur (Anthropic) :
   saisir une clé d'API réelle, « Enregistrer la clé ». Attendu : le champ
   se vide, l'état devient « Clé enregistrée ••••1234 » (4 derniers
   caractères), aucun journal ne contient la clé (`adb logcat | grep sk-`
   ne renvoie rien).
2. **Persistance** — tuer le processus, rouvrir : l'aperçu demeure, le
   champ de saisie est vide (rotation comprise — `saveEnabled=false`).
3. **Test de connexion** — « Tester la connexion » : attendu ✓ « Connexion
   réussie » (401 attendu si la clé est volontairement fausse, avec le
   message « Clé absente ou refusée »).
4. **Restauration de sauvegarde** (API ≤ 30 ou `adb backup`) — restaurer
   une sauvegarde du `.apk` : le fichier DataStore des réglages IA est
   exclu (`backup_rules.xml`), les blobs du coffre vivent dans
   `noBackupFilesDir` (exclu par construction). Attendu après
   restauration : réglages par défaut, aucune clé — **aucun secret ne
   voyage** (ADR 0124 §2).
5. **Invalidation du Keystore** — réinitialiser l'appareil (ou désinstaller
   puis réinstaller : le Keystore est effacé avec l'app, les blobs
   `noBackupFilesDir` aussi — sur un appareil rooté seulement, simuler en
   supprimant l'alias `codeide.ai.vault.v1`) : attendu « Clé à ressaisir »,
   aucun plantage.
6. **Consentement** — désactiver l'assistant dans les réglages, tenter un
   envoi depuis l'IA (après IA-3) : aucune requête réseau ne part (vérifier
   `adb logcat` ou un mandataire) ; l'UI oriente vers le consentement.

## Mission IA — chat du tiroir (lot IA-3 → v0.138.0, ADR 0122)

> Le streaming, l'annulation et l'insertion au curseur sont couverts en
> JVM/Robolectric ; cette procédure valide ce qu'un test ne peut pas
> voir : le rail à 6 éléments, les insets et TalkBack.

1. **Rail à 6 destinations** — ouvrir un projet puis le tiroir à 45 % et
   à 98 %, écran 360 dp : le rail reste lisible (libellé « IA » 10 sp,
   zones tactiles ≥ 48 dp), l'entrée IA porte l'icône étincelle ; la
   sélection marque l'encoche et l'accent comme les cinq autres.
2. **Onglets « bientôt »** — passer Chat → Agent → Historique →
   Recherche puis revenir sur Chat : les messages saisis et la
   conversation sont INTACTS (le fragment enfant est ré-attaché, jamais
   recréé), l'état « bientôt » ne se superpose jamais au chat.
3. **Streaming réel** — clé d'API valide, envoyer « Explique ce
   fichier » : la réponse s'accumule delta par delta, le bloc de code
   non fermé s'allonge au fil du flux (bloc provisoire), le bouton
   bascule Envoyer ↔ Arrêter pendant la génération puis revient à
   Envoyer après la fin comme après une erreur.
4. **Arrêt de la génération** — taper « Arrêter » en plein flux : la
   réponse se fige, AUCUN delta tardif n'apparaît (la requête est
   fermée côté transport — vérifiable à `adb logcat` : la socket est
   coupée, pas seulement l'affichage).
5. **Insérer au curseur** — ouvrir un fichier dans l'éditeur, poser le
   curseur, insérer un bloc de code depuis le chat : le code remplace
   la sélection courante, UN SEUL « Annuler » restaure l'état
   précédent ; sans onglet ouvert, le message « Ouvrez un fichier dans
   l'éditeur » s'affiche (aucun effet silencieux).
6. **Insets** — clavier ouvert/fermé et panneau inférieur étendu : le
   compositeur reste au-dessus du clavier, aucune régression par
   rapport au tiroir terminal (`GestionnaireInsetsEditeur` réutilisé).
7. **TalkBack** — chaque message est annoncé avec son rôle (« Vous :
   … » / « Assistant : … »), les boutons Envoyer et Arrêter portent
   leur `contentDescription`, les blocs de code annoncent Copier /
   Insérer.
8. **Lien Markdown** — toucher un lien `[texte](url)` d'une réponse :
   le navigateur s'ouvre (initiative utilisateur explicite, ADR 0121) ;
   sans navigateur sur l'appareil, rien ne plante.

## Mission IA — contexte et confidentialité (lot IA-4 → v0.139.0, ADR 0124 §4)

> L'assembleur, les exclusions, la détection de secrets et la garde de
> chemins sont couverts en JVM ; cette procédure valide ce qu'un test
> JVM ne voit pas : le FUSE réel, les puces à l'écran et le dialogue.

1. **Puce « fichier actif »** — ouvrir un projet et un fichier, envoyer
   une question : la puce « Fichier : <chemin relatif> » apparaît, le
   `adb logcat`/mandataire montre le contenu du fichier dans le message
   système encadré `<contexte codeide non-fiable>` ; AUCUN chemin
   absolu (`/storage/…`) ne figure dans le corps envoyé.
2. **Puce retirable** — toucher la croix de la puce : l'envoi suivant
   ne contient PAS le fichier (mandataire) ; la pièce ne revient pas
   avant « Nouvelle conversation ».
3. **Sélecteur `@`** — puce « @ Fichier » : la liste des chemins du
   projet s'affiche (bornée, `.git`/`build` absents), filtrer, taper un
   fichier : une puce du chemin apparaît ; retrait individuel par croix.
4. **Refus d'exclusion** — mentionner un `.env` (via le sélecteur) :
   message « ignoré — fichier exclu », le corps envoyé ne contient NI
   son nom NI son contenu ; idem `*.jks` ; une clé factice dans un
   fichier mentionné déclenche le dialogue bloquant.
5. **Dialogue bloquant** — contexte contenant une clé factice
   (`sk-…`) : l'envoi est SUSPENDU (aucune requête au mandataire),
   dialogue non annulable « Envoyer sans / Annuler » ; « Annuler »
   RESTAURE le brouillon ; « Envoyer sans » transmet (décision
   explicite, consignée ici).
6. **Budget** — la puce « Jetons de contexte ≈ N » suit la taille réelle
   des pièces ; retirer des pièces fait baisser N à l'envoi suivant.
7. **`ia-ignore`** — créer `.codeide/ia-ignore` avec `docs/` : les
   fichiers de `docs/` mentionnés sont refusés avec message, comme les
   exclusions par défaut.
8. **Diagnostics** — après un build en échec sur le fichier actif, la
   puce « Diagnostics » apparaît ; les lignes compactées
   (`ERREUR chemin:ligne:colonne (source) message`) partent sans le
   contenu du code.

## Mission IA — agent, approbations, restauration (lot IA-7b → v0.144.0, ADR 0125)

Écran : tiroir IA · onglet **Agent** ; parler : TalkBack, cibles ≥ 48 dp
visibles. Appareil : téléphone + TalkBack, thèmes clair/sombre, police
système ×1,3.

1. **États de l'onglet** — objectif vide → « Lancer » garde l'état avec
   message ; un projet OUVERT est requis (sinon état « échec » lisible,
   jamais de crash).
2. **Exécution + service premier plan** — « Lancer » : la notification
   « Agent en cours » apparaît (canal Agent, importance faible), avec
   action **Arrêter** ; l'action annule la boucle → état « annulé »
   lisible ; la notification disparaît d'elle-même (décision MASQUER).
3. **Approbation EXÉCUTION** — demander `run_gradle` à l'agent : une
   carte « Exécution demandée » (outil + arguments + raison) suspend
   l'appel ; **Refuser** renvoie un refus au modèle ; **Autoriser une
   fois** exécute CET appel (deux appels = deux cartes, jamais
   « toujours autoriser »). TalkBack : la carte annonce outil et raison.
4. **Revue des changements** — après des propositions d'écriture, la
   carte « Changements proposés » affiche le compte ; **Examiner**
   ouvre la revue plein tiroir : case cochée par défaut (hors
   conflits), badge **Conflit** non cochable si le fichier a changé
   depuis la proposition (jamais d'écrasement silencieux) ;
   « Appliquer la sélection » n'écrit QUE la sélection.
5. **Étiquette d'historique** — après application, l'écran Historique
   (fichier ou projet) montre l'étiquette « IA · <date> » ET les
   entrées d'écriture du projet (décorateur).
6. **« Annuler cette exécution »** — le bandeau apparaît après
   application : création → fichier supprimé, modification → contenu
   d'avant restauré, suppression → fichier recréé à l'identique
   (vérifier dans l'éditeur après réouverture du fichier).
7. **Historique indisponible** — (vérification constructeur, tests
   automatisés) : l'application est REFUSÉE sans aucune écriture.
8. **TalkBack** — chaque étape est annoncée ; la carte d'approbation
   annonce outil + raison ; les lignes de revue annoncent
   « chemin — conflit, non cochable » si applicable.

## Mission IA — complétion fantôme (lot IA-8b → v0.145.0, ADR 0129)

Écran : éditeur (fichier texte) + Paramètres → IA. Fonction DÉSACTIVÉE
par défaut — vérifier qu'AUCUNE requête ne part sans opt-in (mandataire
logcat : compteur seulement, ADR 0124 §3).

1. **Opt-in** — activer « Complétion fantôme » : la carte documente le
   déclenchement, Tab/Échap et le plafond ; l'interrupteur reste OFF
   après effacement des données.
2. **Déclenchement** — taper puis s'arrêter 800 ms : une suggestion
   grisée apparaît APRÈS le curseur (aucun décalage de saisie) ; une
   seule requête en vol (frapper vite = annulation + réarmement).
3. **Annulation à la frappe** — tout caractere supprime le fantôme et
   annule la requête en vol (socket fermée, même contrat que « Arrêter »).
4. **Tab / Échap** — Tab insère la suggestion en UN LOT (un seul
   Annuler la restaure) ; Échap la rejette sans écrire.
5. **Plafond** — régler le plafond bas, accepter des suggestions : au
   plafond la fonction se désactive avec message ; le compteur repart
   au mois suivant.
6. **Confidentialité** — `.env`/`*.jks` exclus : leur contenu n'entre
   jamais dans le préfixe/suffixe (mêmes exclusions que le chat).
7. **TalkBack** — le fantôme n'est JAMAIS annoncé (décoration pure).

## Cohérence UI — migration Settings (lot UI-3 → v0.160.0, audit C6, ADR 0127)

Écran : Paramètres (maître + les 9 sous-écrans) + Environnement. La
migration des rangées vers `RangeeParametre` et des cartes vers
`Widget.CodeIDE.Card` doit être INVISIBLE au quotidien : « rendu
identique (capture avant/après) » — procédure à dérouler sur appareil
360 dp, clair ET sombre (comparez aux captures d'avant-migration si
disponibles, sinon à la maquette `mockups/lsp/lsp-reglages.html` pour
la structure).

1. **Maître** — les 4 cartes (Général, Modules, Environnement,
   Application) : libellés d'en-tête, rangées, sous-titres d'état mis à
   jour au fil des réglages ; la rangée Sécurité reste atténuée avec sa
   puce « Bientôt ».
2. **Filets** — un trait fin sépare les rangées VOISINES de chaque
   carte ; un filet apparaît aussi sous chaque en-tête de section
   (uniforme, voulu) ; AUCUN double trait.
3. **Éditeur** — la valeur du thème de coloration apparaît à DROITE
   de la rangée suivie du chevron (patron « valeur », miroir licence) ;
   les six interrupteurs basculent par un appui sur TOUTE la rangée et
   l'état survit à la rotation.
4. **Apparence** — le trio système/clair/sombre et les 8 palettes ;
   l'interrupteur couleurs dynamiques ; l'aperçu pastilles.
5. **Avancé** — la rangée « Réinitialiser » garde icône et titre en
   ROUGE (couleur d'erreur), les deux autres rangées restent neutres.
6. **Environnement** — la carte des composants est À CONTOUR sans
   ombre (élévation 0, unification C4) ; les 4 boutons d'action sont
   thémés.
7. **TalkBack** — chaque rangée se lit « <titre>, <sous-titre> » une
   seule fois ; la cible tactile de chaque rangée fait au moins 48 dp.
8. **Boutons pressés** — dans chaque sous-écran, appuyer partout :
   aucun ripple ne « fuit » hors d'une rangée, aucune action ne se
   déclenche deux fois (patron `renduEnCours` conservé).

## Cohérence UI — migration crash, install, terminal (lot UI-4 → v0.163.0, audit C1-C5/C9, ADR 0127)

Écrans : rapport de plantage (processus `:crash`), installation de
l'environnement (téléphone ET tablette sw600dp), terminal (activité
plein écran + tiroir de l'espace). Les trois modules quittent la
baseline `checkUiTokens` (zéro littéral) — la migration doit rester
INVISIBLE au quotidien : 360 dp, clair ET sombre, capture avant/après
si disponible.

1. **Rapport de plantage** — les 7 boutons (Redémarrer plein, les 5
   actions en contour, Fermer/Détails en texte) suivent le thème ;
   « Vider le cache » garde son titre en couleur d'erreur ; la carte
   résumé est à CONTOUR sans ombre ; les retours de copie, partage,
   enregistrement et vidage sont des SNACKBAR (jamais de Toast) — y
   compris « Redémarrage impossible » ; la trace monospace reste
   sélectionnable.
2. **Installation** — le stepper de 4 phases tient dans la carte à
   contour ; la carte licence (centrée verticalement au démarrage)
   garde titre, texte et case ; « Démarrer » plein, Annuler/Réessayer/
   Diagnostic/Terminal en contour, « Journal » en texte ; sur TABLETTE
   le journal de droite reste collé à la colonne gauche.
3. **Terminal** — l'entête du tiroir (emblème, titre, sous-titre
   monospace, rangée d'actions) garde ses couleurs de maquette ; les
   tailles suivent l'échelle typographique (titre 14 sp, sous-titres et
   badges `Micro` 10 sp) — vérifier le sous-titre « N sessions
   actives · termux » ; les cartes de sessions (libellé, détail,
   badge, 2 boutons) ne débordent pas sur 360 dp ; le clavier étendu
   (Tab/Ctrl/Alt/flèches) reste compact et thémé.
4. **Couleurs dynamiques** — les trois écrans suivent le thème
   (clair/sombre/Material You) : aucune des surfaces ni teintes
   migrées n'est restée figée.
5. **TalkBack** — les zones tactiles des boutons d'action du plantage
   et des touches du clavier étendu font ≥ 48 dp ; les snackbars sont
   annoncées (les Toasts ne l'étaient pas de la même façon).
6. **Cliquet** — `./gradlew checkUiTokens` reste vert ; la baseline ne
   contient PLUS AUCUN fichier de `core/crash`, `feature/install` ni
   `feature/terminal` (critère de fin du lot).

## Cohérence UI — migration editor (lot UI-5 → v0.164.0, audit C1/C2/C3/C4/C5/C9, ADR 0127)

Écrans : tout ce que le tiroir de l'éditeur et le panneau inférieur
affichent — explorateur et arborescence (popovers compris), recherche,
Git, projet (scripts/dépendances/mises à jour/tâches), console, journal,
Logcat, feuille Historique, activité (rail, onglets, états vides,
pastilles flottantes). Le module `feature:editor` quitte la baseline
`checkUiTokens` (354 occurrences → 0, le plus gros lot de la mission) —
la migration doit rester INVISIBLE au quotidien : 360 dp, clair ET
sombre, capture avant/après si disponible. Écarts assumés (table §4) :
arrondis de 1-2 dp sur les espacements, 13 sp → 12/14 sp (jamais
inventé), demi-tailles → `Micro`/`LabelSmall` ; les DEUX cotes
flottantes du tiroir (snackbar 74 dp, pastille de taille 54 dp) et le
padding des boutons icône maison (14 dp = (48 − 20)/2) sont des JETONS
DÉDIÉS documentés, pas des littéraux.

1. **Explorateur** — entête (emblème, titre bold 14, chemin monospace
   Micro, boutons 32 dp espacés de `editor_ecart_actions`), bascule
   Projet/Privé, fil d'Ariane, barre presse-papiers (padding par
   `editor_presse_papiers_padding_*`) : mêmes proportions qu'avant ;
   la barre presse-papiers montre le chemin copié en LabelSmall bold ;
   la snackbar maison de l'arbre (message + chemin mono + Annuler)
   garde son fond/bordure dédiés et ses 4 600 ms.
2. **Popovers de l'arborescence** (actions du nœud, Supprimer,
   Déplacer, destination, Légende) — coques `fond_popover` avec
   `editor_popover_padding` (6 dp) ou `spacing_md_compact` (12 dp) ;
   les TÊTES (icône + nom bold BodyMedium + chemin mono Micro) ne
   débordent pas à 360 dp ; les boutons Annuler (12/8) et
   Supprimer/Déplacer (16/8) gardent leur hiérarchie ; les pastilles
   d'état de la Légende restent alignées sur leur texte LabelSmall.
3. **Recherche** — entête unifié 12/12 avec les autres tiroirs ; les
   bascules Aa/ab/.*/Remplacer restent dans leurs zones 32 dp ;
   « Tout remplacer » est un bouton maison Outlined (plus de style
   Material brut) ; les résultats monospace et les bandeaux
   plafond/erreur gardent leurs paddings.
4. **Git** — badge de branche (8/2), état « pas un dépôt » (padding
   `state_padding`), « Initialiser » Outlined maison, bouton Commit
   plein maison, liste des changements LabelSmall monospace.
5. **Projet & indexation** — les 5 onglets ; lignes de scripts,
   dépendances et mises à jour (padding 12/8, titres BodyMedium,
   sous-textes LabelSmall) ; feuille « Détails de l'indexation » :
   poignée, progression, liste de travail, la note d'impact devient
   une carte `Widget.CodeIDE.Card` (RAYON 12, ÉLÉVATION 0 par le
   style, fond `colorSurfaceVariant` conservé, filet 1 dp NOUVEAU —
   unification C4) ; l'anneau d'étape en cours tourne (masque blanc
   `anneau_etape_masque` teinté par canal — pas de changement de
   couleur) ; l'illustration de l'état vide (fenêtre de code,
   chevrons violets) est identique (couleurs nommées `ill_editeur_*`,
   mêmes valeurs).
6. **Panneau inférieur** — barre d'outils console : chip d'action
   maison `Widget.CodeIDE.Chip.Assist`, boutons Tâches/Configuration/
   Annuler en `Widget.CodeIDE.Button.Text`, champ stdin dans
   `Widget.CodeIDE.TextField` ; onglet Journal : 4 chips filtres
   `Widget.CodeIDE.Chip.Filter` ; onglet Logcat : barre (puce
   processus, filtre, regex, niveaux, pause, effacer), bandeaux
   pertes/arrêt/session précédente (padding 12/8, LabelSmall),
   lignes de la table (12/4, LabelSmall monospace) ; la copie d'une
   ligne (clic long) confirme par SNACKBAR sur Android < 13 comme
   sur 13+ (plus de Toast — la confirmation système reste la seule
   sur 13+) ; « Ouvrir avec » indisponible confirme aussi par
   snackbar.
7. **Activité** — rail de fragments (padding 4/8/4, libellé Micro
   bold), onglets de fichiers (croix dans `editor_bouton_icone_padding`
   — zone 48 dp, icône 20 dp), zoom/pastille de taille pendant le
   glissement du tiroir (`editor_pastille_taille_marge_bas`,
   `editor_snackbar_marge_bas` — la snackbar flotte au-dessus du
   rail), état vide de l'éditeur (illustration + boutons Tonal/Text
   maison) ; « Quitter pendant un build » affiche TOUJOURS le toast
   long assumé (il survit à la destruction de l'activité) — ARBITRÉ EN
   UI-7 : EXCEPTION R6 documentée, plafonnée à 1 dans la baseline
   (ADR 0127 §5, décision §2.1c de `CYCLE_DE_VIE.md`).

## Cohérence UI — migration preview (lot UI-6 → v0.165.0, audit C5/C9, ADR 0127)

Écran : l'aperçu XML tout entier (barre du haut, HUD, barre d'édition,
palette, placement, menus, bulle, volets de la feuille). Le module
`feature:preview` quitte la baseline `checkUiTokens` (16 fichiers,
30 occurrences → 0) — la migration doit rester INVISIBLE au quotidien :
aucun pixel ne change (constantes framework de même valeur, teintes posées
au liage inchangées). 360 dp, clair ET sombre.

1. **Boutons ronds 36 dp et HUD** (retour, plus, annuler/rétablir,
   dupliquer, supprimer, zoom quand il est en boutons) : les 8 icônes
   restent TEINTÉES comme avant (`apercuSurNouveau` / teinte du
   style) — l'ondulation au toucher reste un cercle (resp. rectangle
   rayon 10 pour le HUD) sur `apercuSurface2`.
2. **Barre d'édition en mode sélection** : les 4 boutons 34 dp du HUD
   se teintent `apercu_hud_icone`, désactivés à opacité .32 — identique.
3. **Palette et placement** : le bouton de MODE sélectionné (Design /
   Plan / Les deux) et la position choisie du placement gardent l'OMBRE
   LÉGÈRE de 1 dp sous la surface (jeton `apercu_ombre_bouton`, même
   valeur #2E000000 — box-shadow rgba(0,0,0,.18) de la maquette).
4. **Menus Calques / appareil / cible** : les PASTILLES de couche restent
   bien colorées par `backgroundTintList` (base blanche tintée — pas de
   changement de teinte) ; la pastille 12 dp du menu calques garde sa
   taille de `apercu_pastille_menu_taille`.
5. **Volet Attributs** : provoquer un REFUS d'édition (éditer la valeur
   d'une vue périmée — re-rendu entre-temps, message « édition périmée »)
   puis déclencher une CORRECTION RAPIDE (« Passer à 48 dp ») : les DEUX
   retours partent désormais par le TOAST MAISON en haut-centre (fond
   inversé, fondu 200 ms, 2,4 s) — PLUS AUCUN Toast système (fond gris
   en bas de l'écran). Même présentation que les messages d'insertion
   de la palette.
6. **Nettoyage invisible** : rien ne doit référencer les 3 drawables
   supprimés (fond_mode_palette, fond_position_placement,
   fond_pastille_probleme) — aucun écran ne change.
7. **Cotes verrouillées** : `CotesEcransMaquetteTest` (7) et
   `CotesPopoversMaquetteTest` (6) restent verts — les cotes mesurées
   (boutons 36, HUD 34, pastille 12…) prouvent l'absence de dérive.

## Cohérence UI — Toast final et règle de bannissement (lot UI-7 → v0.166.0, audit C3/C5, ADR 0127 §3/§5)

Écrans : réglages IA, historique des conversations, détail fournisseur,
chat du tiroir, assistant de création (wizard), éditeur, terminal.
Sept Toast « en fenêtre » deviennent des Snackbar thématisées ; le
scanner `checkUiTokens` est durci (R6 multi-lignes, R5 fins de ligne avec
exemption du style maison) — tout NOUVEAU Toast, n'importe où, échoue le
build. 360 dp, clair ET sombre.

1. **Réglages IA** — provoquer un message (ex. test de connexion sans
   clé, fournisseur inactif) : le retour part en SNACKBAR en bas de
   l'écran (thématisée, annoncée par TalkBack) — plus de Toast gris.
2. **Historique des conversations** — effacer une conversation (ou tout
   effacer) : le message de confirmation d'effacement part en snackbar.
3. **Détail fournisseur** — tenter d'enregistrer une clé VIDE : « la clé
   ne peut pas être vide » part en snackbar ancrée à l'écran ; les
   messages d'effacement/remplacement de clé aussi.
4. **Chat du tiroir** — copier un message (clic long) : « Copié » part
   en snackbar (miroir du terminal et du Logcat) ; les messages
   d'erreurs du chat aussi.
5. **Assistant de création** — à l'écran d'échec, « Copier les
   détails » : la confirmation part en snackbar.
6. **EXCEPTION 1 (à vérifier, pas à corriger)** — éditeur : lancer un
   build puis QUITTER l'éditeur pendant qu'il tourne : le toast long
   « build continue en arrière-plan » apparaît encore (il survit à
   l'écran) et la notification de tooling reste dans le tiroir —
   comportement §2.1c ratifié, exception R6 plafonnée à 1.
7. **EXCEPTION 2 (à vérifier, pas à corriger)** — terminal sur un
   appareil Android < 13 : depuis une session ssh distante, déclencher
   une copie OSC 52 : le toast court de confirmation
   apparaît ; sur Android 13+, SEUL le bandeau du système apparaît
   (aucun doublon) — exception R6 plafonnée à 1, module sans UI.
8. **Dette R5 inscrite (aucun changement visible attendu)** — les 13
   icônes sans composant maison (arborescence de l'éditeur, barre IA,
   rangées de problème, accueil, wizard, réglages/env, terminal) ne
   changent PAS : elles sont gelées dans la baseline, leur migration
   (avec captures avant/après) est un lot futur.

## Réglages LSP « Intelligence du code » (lot LSP-3 → v0.161.0, ADR 0128)

Écran : Paramètres → « Intelligence du code » (L1) puis L2/L3/L4/L5. À
vérifier sur appareil 360 dp, clair ET sombre (la maquette de référence :
`mockups/lsp/lsp-reglages.html`). Un projet DOIT être ouvert pour observer
les moteurs vivants ; sans projet, les pastilles passent en neutre
« Inactif » et les réglages restent éditables.

1. **Maître** — 2ᵉ rangée du groupe Modules (entre Éditeur et Terminal),
   icône « code », sous-titre « Moteurs, index et fonctions par
   langage ».
2. **L1 avec projet ouvert** — les trois rangées moteurs portent une
   pastille FORME+TEXTE : Java suit l'indexation en direct (« Indexation
   62 % » pendant un build, « Prêt · n fichiers indexés » ensuite ;
   « Partiel » et « Erreur » avec leurs comptes si applicable) ;
   Kotlin/XML disent « Prêt » ou « Inactif » selon la présence de
   session — JAMAIS un faux pourcentage.
3. **L1 sans projet** — bloc « Aucun projet ouvert / Les moteurs sont
   inactifs » + bouton « Ouvrir un projet » qui ramène à l'accueil.
4. **L2 ×3 langages** — Java : 10 fonctions en ordre fixe (Complétion
   d'abord, Indices en ligne en dernier) ; Kotlin : 8 SANS Formatage ni
   Indices en ligne ; XML : 7 SANS Aide à la signature ni Références —
   seules les fonctions ANNONCÉES apparaissent ; « Gravité affichée »
   et « Réactivité » portent la puce « Bientôt » (non interactives) ;
   Kotlin/XML : puce « pack langue — bientôt » sur la rangée moteur.
5. **L3** — « Taille maximale » ouvre le dialogue Auto/256/512/1024 Mo,
   le choix S'APPLIQUE SANS REDÉMARRER (surveiller la fermeture/ouverture
   d'un projet) ; l'interrupteur « Surveiller les modifications externes »
   coupe/relance la surveillance, « Fréquence » propose 2/5/10/30 s et
   reste désactivée quand la surveillance est coupée ; « Vider le cache »
   demande CONFIRMATION, est indisponible pendant une indexation
   (explication affichée) et annonce le refus par snackbar si tenté ;
   le retour « Cache vidé — X libérés » est un snackbar SANS « Annuler ».
6. **L4** — valeurs lues seulement (JDK · plateforme API · mode Par
   module/Instantané) ; avec un JDK absent : état d'erreur + bouton
   « Réparer » qui ouvre l'écran Environnement des Paramètres.
7. **L5** — résumé par moteur avec l'heure du dernier passage ; les
   erreurs récentes (≤ 20, « 14:02 · Java · message ») sont masquées
   par « Aucune erreur » quand la liste est vide ; « Copier le
   diagnostic LSP » colle un texte SANS chemin en clair (vérifier dans
   un éditeur de texte) et confirme par snackbar.
8. **TalkBack** — chaque rangée moteur se lit d'une seule voix
   (« Java : Prêt ») ; les changements de pastille sont annoncés
   (région live polie) ; cibles tactiles ≥ 48 dp partout.
9. **Rotation/recréation** — l'état affiché se ré-observe sans action
   destructive répétée ; le langage L2 survit à la rotation.

## Réglages LSP « Intelligence du code » (lot LSP-4 → v0.162.0, ADR 0128)

Écran : Paramètres → « Intelligence du code » → L3 « Index et cache ». À
vérifier sur appareil 360 dp, clair ET sombre. Complète la recette LSP-3 :
le lien « Détails de l'indexation » (différé à ce lot), l'audit
d'accessibilité des cinq écrans (§ 5 de la spécification) et le test
d'architecture des interrupteurs y sont livrés.

1. **Lien « Détails de l'indexation » (L3, carte Occupation)** — rangée
   sous « Cache d'index », icône journal, chevron ; AVEC un projet
   ouvert : l'appui RAMÈNE l'espace éditeur au premier plan (sans le
   recréer — pas de doublon dans la pile) et le panneau de détail de
   l'indexation (ADR 0117, celui de la pastille de la toolbar) s'ouvre
   À L'ARRIVÉE.
2. **Lien sans projet** — éditeur refermé (session fermée) : la rangée
   est désactivée (opacité Material) et ne navigue nulle part — jamais
   un contrôle sans effet.
3. **Demande qui survit (état, pas événement)** — poser l'appui puis
   rester dans les réglages ne montre rien ; ressortir par le système
   (l'espace reprend au premier plan) : le panneau s'ouvre à la
   reprise — la demande vit sur le PORT des moteurs, rien ne se perd.
4. **Concurrence honnête** — fermer le projet PENDANT que L3 est ouvert :
   la rangée se désactive au rendu suivant (port retiré) ; un appui
   résiduel répond « Les actions demandent un moteur actif » par
   snackbar, rien n'est posé.
5. **Accessibilité des cinq écrans (§ 5)** — TalkBack : rangées moteurs
   d'une seule voix (« Java : Prêt »), pastilles annoncées (région
   live polie), zones tactiles ≥ 48 dp (portées par `RangeeParametre`) ;
   police système ×1,3 : les sous-titres passent à la ligne SANS
   troncature ; contraste clair/sombre/dynamique vérifié L1-L5 (rôles
   de thème uniquement — cliquet `checkUiTokens` vert).
6. **EN/FR** — basculer la langue : les deux libellés neufs (« Détails
   de l'indexation », « Liste de travail et bilan du dernier passage »)
   suivent la langue, comme le reste de l'écran.
7. **Tests automatiques associés** — `ArchitectureInterrupteursLspTest`
   (aucune rangée de fonction sans capacité annoncée, ordre FIXE, table
   vide = zéro rangée), `PortLspEspaceTest` (demande : posée elle
   survit, consommée elle retombe), `LangagesViewModelTest` (la demande
   passe par le port et porte l'identifiant du projet ; refus honnête
   sans port ni session).

## Capacités LSP fermées par défaut et actions « Formater » (chantier LSP-fermeture → v0.167.0 + phase 2 v0.168.0, ADR 0136)

Écran : éditeur (`EditorActivity`), plusieurs onglets de types DIFFÉRENTS ouverts dans le même
espace (un `.java` ou `.kt`, un `.txt` sans serveur, un `.xml`). À vérifier sur appareil 360 dp,
clair ET sombre, clavier logiciel présent. La montée code-editor **3.74.0** (v0.168.0) referme
les capacités à la DÉCONNEXION (`disconnect()` restaure `EmptyLanguage`, correction DANS LA
bibliothèque — ADR 0136 D1/D4) : les scénarios 2 à 4 sont désormais VÉRIFIABLES tels quels.

1. **Branchement honnête (P1-a)** — ouvrir un `.java` : le journal (écran Diagnostic) dit
   « LSP Java connecté (…) » ; le menu « Formater » est PROPOSÉ (le formateur du Java est posé).
   Ouvrir un `.txt` : le journal ne dit JAMAIS « Java » pour lui (le coordinateur n'est d'ailleurs
   pas branché : pas de complétion, pas de survol — coloration seule).
2. **Fermeture des capacités au changement d'onglet** — sur le `.java` : taper, attendre la
   complétion (elle apparaît) ; passer au `.txt` : AUCUNE complétion, AUCUN survol, aucune
   marque de diagnostic, la mise en forme ne fait RIEN, l'action « Formater » disparaît du menu
   (`canFormat()` faux — le langage est retiré à la déconnexion).
3. **Retour au `.java`** — tout revient : complétion, survol, diagnostics, formatage (l'action
   « Formater » réapparaît — le rappel de branchement re-évalue la disponibilité APRÈS la pose
   du langage). La coloration du `.txt` reste lexicale.
4. **Changement d'onglet très rapide pendant la connexion** — enchaîner 4-5 changements d'onglet
   au moment où le journal annonce la connexion : pas de marque résiduelle sur l'onglet final,
   pas de plantage (le renoncement de branchement est journalisé), pas de complétion du langage
   du fichier précédent (jeton de génération côté bibliothèque).
5. **Action « Formater le document »** — sur un `.java` ou un `.xml` mal indenté : le menu
   (toolbar, icône baguette, ou débordement si la toolbar est dense) FORMATE le document,
   SUCCESSION d'une seule frappe à l'historique ; le texte modifié n'affiche AUCUN message. Sur
   un document déjà conforme : « Déjà formaté ». Sur un `.kt` (le serveur Kotlin annonce
   désormais la mise en forme depuis codeide-lsp 1.3.0 — RÉ-INDENTATION seule, le contenu
   n'est jamais réécrit) : l'action RÉ-INDENTE, AUCUN message d'indisponibilité — il ne doit plus
   apparaître que sur un onglet sans serveur (`.txt`, action MASQUÉE — `canFormat()` faux) ou
   une capacité « Formatage » coupée dans l'écran Intelligence du code (message complet).
   Sans aucun onglet : le menu n'existe pas / ne lance rien.
6. **« Formater la sélection » (phase 2)** — sur le `.java`, SÉLECTIONNER une plage mal indentée :
   la pill flottante porte « Formater la sélection » ; le tap ne formate QUE la sélection, en UN
   seul pas d'undo (retour arrière retrouve l'état d'avant), le caret et la sélection
   survivent, aucun message. Sélection déjà conforme : « Déjà formaté ». Les trois langages
   servent le formatage de PLAGE depuis codeide-lsp 1.3.0 ; l'entrée reste GRISÉE quand la
   capacité est coupée dans l'écran Intelligence du code ou le serveur muet.
   Clavier physique branché : `Ctrl+Shift+K` formate la sélection (commande de la
   bibliothèque).
7. **Options de mise en forme (phase 2)** — Paramètres → Éditeur → carte Édition → « Largeur de
   tabulation » : choisir 8, formatter un `.java` : l'indentation produite suit la largeur ;
   les espaces remplacent les tabulations (politique ADR 0136 D6). Choisir 2 à l'éditeur OUVERT :
   le formatage suivant l'applique sans redémarrage (réglage consommé au fil de l'eau).
8. **Saisie et IME (attention 3.73.0)** — composer du texte (clavier Gboard/AOSP), retour de
   sélection après retour arrière : l'IME est au niveau plateforme depuis la 3.73.0 — CodeIDE
   n'a AUCUN contournement à retirer (vérifié : seul le filtre logcat masque le clavier), la
   saisie doit être fluide, la composition et les suggestions intactes.

## Chrome système possédé par le moteur (chantier PE13 → v0.169.0, LayoutLib 3.5.0, ADR 0137)

Écran : aperçu XML (`PreviewActivity`), un projet AVEC `res/values/themes.xml` (thème standard
Material), un document simple (LinearLayout + TextView), profil Pixel 4. À vérifier sur
appareil, clair ET nuit. Le chrome est passé au MOTEUR de rendu (zéro vue chrome côté IDE) :
le rendu doit être PIXEL-IDENTIQUE à v0.168.0 en régime nominal (garde-fou G2 de l'ADR 0137) —
c'est le fil conducteur de toute la section.

1. **Planche inchangée (G2)** — rendre le document : status bar 24 dp avec « 9:41 / 5G ▮▮▮
   100% », action bar 56 dp au titre de l'activité, contenu, barre de navigation 48 dp aux
   trois glyphes — la MÊME planche qu'avant la migration, au pixel près (les captures
   éventuelles d'avant/après sont l'outil de contrôle). Taper sur une vue : la SÉLECTION
   tombe JUSTE (le recalage est passé au moteur — si le surlignage se décale d'un pixel, c'est
   une régression R3 à signaler immédiatement).
2. **Nuit par le régime des résolveurs (D2)** — puces Jour/Nuit : nuit, SANS dossier res/
   attaché, les barres par défaut suivent toujours la planche (`#0C1030`/`#BAC4FF`/barre
   `#000`) ; AVEC le dossier res/, le NUIT suit les thèmes `values-night` — les deux régimes
   doivent être identiques à v0.168.0 (le drapeau interne n'existe plus, le résolveur porte
   le régime).
3. **Interrupteur « Barre de navigation » (D1)** — menu Calques → Chrome système : éteindre
   la barre → le rendu PERD la 4e pièce et le contenu descend jusqu'en bas ; rallumer → la
   pièce revient. La relance du rendu est immédiate (comportement PE11j inchangé).
4. **Thèmes de fenêtre (correctif C1 conservé)** — un thème `windowNoTitle`/`windowActionBar=
   false` rend SANS action bar du chrome (le fil « noBar » de la maquette) ; un thème
   `windowFullscreen` SANS barres rend le CONTENU SEUL, plein cadre.
5. **Pastille « rendue • n vues » (D5, NOUVEAU)** — un document de 5 vues affiche désormais
   « 5 vues » : le chrome n'EST PLUS COMPTÉ (le compte suit le document, pas le décor). Un
   document à 1 vue affiche « 1 vue » — vérifier qu'aucun compte « 6 » ou « 8 » (vues chrome)
   n'apparaît.
6. **Hauteurs thémées (D4)** — si le thème du projet déclare `android:actionBarSize` (p. ex.
   64dp), l'action bar du chrome suit l'attribut au rendu suivant ; sans déclaration, repli
   56 dp. (Nouveauté miroir `Layout.Builder` AOSP : à signaler si un écart vous surprend.)
7. **Arbre de composants** — l'onglet Arbre ne montre PAS les vues du chrome dans le
   PRÉ-ORDRE du document (déjà le cas avant) ; la section « système » branchée en v0.170.0
   est TERMINALE et non sélectionnable — voir la section suivante.

## Geste de redimensionnement fidèle + section « Système » et « Mode gestuel » (PE14 S1/S2 → v0.170.0, ADR 0138/0139)

Écran : aperçu XML, projet avec thème Material standard, un LinearLayout vertical
contenant un Button (et une seconde vue au-dessus pour les scénarios d'ancrage),
profil Pixel 4. Le fil conducteur : le geste de poignée fait EXACTEMENT ce que le
doigt montre — plus de saut au premier mouvement, plus de vue qui glisse, plus
d'instant vide au relâchement.

1. **Plus de saut de préhension (C1, le cœur)** — saisir la poignée BAS-DROITE du
   bouton en POSANT le doigt visiblement À CÔTÉ du carré (dans la zone de saisie,
   1-2 cm à l'écran) : le fantôme pointillé part DE LA TAILLE ACTUELLE et le COIN
   reste sous le doigt — la pill « w×h dp » n'affiche PAS une valeur différente de
   la vue avant même que vous bougiez (l'ancien comportement sautait de la longueur
   du décalage, jusqu'à ~18 dp par axe). Tirer de 20 dp vers le bas-droite : la
   cible affiche ~la taille de départ + 20 dp, PAS + 30.
2. **Le fantôme ne laisse jamais de vide (C4)** — relâcher un geste sur un GROS
   layout (rendu lent) : le rectangle cible et sa pill RESTENT affichés jusqu'à
   l'arrivée du nouveau rendu, puis s'effacent en fondu (~150 ms). Un geste RATÉ
   (sélection périmée : éditer le texte pendant le geste) relâche sur le toast
   d'échec et le fantôme part immédiatement.
3. **Poids neutralisé (C3, LinearLayout)** — un enfant `layout_width="0dp"` +
   `layout_weight="1"` : saisir sa poignée et écrire une taille → le RENDU CHANGE
   (avant, rien ne bougeait) ; l'onglet Source montre le poids RETIRÉ dans la même
   édition (UN SEUL « Annuler » ramène l'ancien XML au complet).
4. **Plus de glissement (C3, RelativeLayout)** — enfant ancré `layout_below` +
   `layout_toRightOf` d'une vue : saisir la poignée HAUT-GAUCHE → le coin
   BAS-DROIT reste FIXE pendant le geste et APRÈS le rendu (la marge du bord tenu
   est réécrite dans la même édition) ; même contrôle sur un enfant `alignParentRight`
   saisi par sa poignée GAUCHE. Une vue centrée (`centerInParent`) reste libre de
   glisser (les marges ne la contrôlent pas — comportement assumé, ADR 0138).
5. **Dialecte tools: jamais imposé (C3, ConstraintLayout)** — un document SANS
   `xmlns:tools` : le geste n'écrit QUE les dimensions (vérifier dans la Source) ;
   un document qui LE DÉCLARE : `tools:layout_editor_absoluteX/Y` suivent le bord
   déplacé (miroir ConstraintResizeTarget).
6. **Section « Système » de l'arbre (ADR 0139)** — onglet Arbre d'un rendu CHROMÉ :
   après le contenu, une entête « Système » puis « Barre de statut », « Barre
   d'action », « Barre de navigation » avec leurs badges (24, 56, 48 dp au profil).
   Taper une rangée système : RIEN ne se sélectionne (aucune balise source — la
   sélection courante reste). Un document plein écran (`windowFullscreen`) ou sans
   thème : la section N'EXISTE PAS. La pastille « rendue • n vues » ne compte
   TOUJOURS que le contenu.
7. **Mode gestuel (ADR 0139)** — menu Calques → Chrome système → « Mode gestuel » :
   la barre de navigation 48 dp troque ses trois glyphes contre la PILULE — MÊME
   hauteur, le contenu ne bouge PAS d'un pixel ; l'arbre affiche « Pilule gestuelle »
   à la place de « Barre de navigation ». Éteindre « Barre de navigation » : la zone
   part (le contenu descend), l'interrupteur « Mode gestuel » reste posé sans effet
   visible ; le rétablir : la pilule revient. Le réglage vit la SESSION (rouvrir
   l'aperçu : trois boutons par défaut).
7. **Arbre de composants** — l'onglet Arbre ne montre PAS les vues du chrome (déjà le cas
   avant) ; la future section « système » (infos marquées `ViewType`) n'est PAS branchée —
   rien à vérifier de ce côté, le moteur l'expose pour un chantier ultérieur.

## Conformité parent/enfant + palettes 64 tuiles + bugs LayoutLib (PE14 S3/S4/S5 → v0.171.0)

Écran : aperçu XML (`PreviewActivity`), clair ET nuit, profil Pixel 4. La contenance suit
les règles de lint Android (feuilles, enfant unique, adaptateurs, enfants attendus) et la
palette gagne 23 tuiles « proches du réel ». Les scénarios se jouent sur un document créé
avec la palette elle-même.

1. **Le popup ne propose plus « Dedans » quand la vue refuse** — insérer un ScrollView
   (palette), y déposer un LinearLayout, puis re-taper le ScrollView : le popup de
   placement n'offre plus « Dedans » (un seul enfant direct admis — miroir lint
   `ScrollViewChildren`) ; la racine adaptateur (déposer un RecyclerView en racine,
   puis le taper) n'offre RIEN : « Placer ici » reste désactivé. Un conteneur sain
   (LinearLayout) offre bien Avant/Dedans/Après comme toujours.
2. **Le glisser remonte au parent** — glisser un TextView sur une RecyclerView rendue :
   la pilule vise le PARENT de la liste (« Dans … », barre d'insertion chez lui),
   jamais la liste elle-même (le contenu vient de l'adaptateur). Un ScrollView VIDE
   reçoit bien son premier enfant en direct ; un ScrollView PLEIN fait remonter au
   sien.
3. **L'onglet Problèmes signale les violations existantes** — taper au source
   (ou via le bouton ↺ d'un champ) un document : `<TextView>` avec un `<Button>`
   dedans → carte rouge « Enfants interdits » ; `<ScrollView>` avec deux enfants →
   carte ambre « Un seul enfant direct » ; un `<ListView>` avec un enfant → carte
   rouge « Contenu venu de l'adaptateur » ; un `<TabLayout>` avec un `<TextView>`
   → carte « Enfant inattendu » (ambre), un `<TextInputLayout>` avec un `<Button>`
   → carte rouge (la vraie classe jette). Les cartes s'ouvrent en sélectionnant la
   vue fautive dans l'arbre.
4. **Les deux correctifs** — sur le ScrollView à deux enfants : bouton
   « Envelopper dans un LinearLayout » → UNE annulation ramène tout (les enfants
   indentés sous le nouveau LinearLayout vertical, le problème part au re-rendu) ;
   sur le TextView porteur : « Retirer les enfants » → les enfants partent, UNE
   annulation restaure. Le toast maison confirme dans les deux cas.
5. **La palette à 64 tuiles** — l'onglet « Tous » déroule les nouvelles entrées par
   catégorie : Texte (MultiAutoCompleteTextView, CheckedTextView, TextInputEditText),
   Boutons (RadioGroup, MaterialSwitch, MaterialButtonToggleGroup, ChipGroup),
   Conteneurs (TableLayout, TableRow, DrawerLayout, SwipeRefreshLayout,
   CollapsingToolbarLayout, NavigationView, ViewFlipper), Médias (TextureView),
   Listes (ExpandableListView), Autres (Toolbar, BottomAppBar, BottomNavigationView,
   Chronometer, TextClock, NumberPicker, Guideline). La recherche « button » attrape
   désormais aussi MaterialButtonToggleGroup. Chaque tuile porte son icône et son
   badge de dépendance éventuel (Material, AppCompat, DrawerLayout,
   SwipeRefreshLayout, ConstraintLayout, RecyclerView).
6. **Les nouvelles tuiles rendent leurs vraies vues** — insérer un MaterialSwitch
   (interrupteur réel, fiche État), une Toolbar (fiche Barre d'outils : app:title
   éditable, badge « AppCompat »), un Guideline dans un ConstraintLayout (aide
   visible au rendu maquette, fiche Contraintes du parent). Un DrawerLayout avec
   dossier res/ du modèle affiche le badge « DrawerLayout » s'il manque la
   dépendance — le repli honnête MockView + carte « classe absente » reste la règle
   (comportement v0.166.3 inchangé pour toute dépendance absente).7. **Un nœud refusé n'échoue plus la planche entière (LayoutLib 3.5.2,
   § 3.3-C)** — taper au source un ListView avec un enfant
   (`<ListView …><TextView …/></TextView>` dans les deux sens : l'enfant
   est refusé par l'AdapterView au rendu) : l'aperçu continue de rendre
   LE RESTE du document (plus de bandeau d'erreur généralisé) et
   l'onglet Problèmes porte la carte rouge « Contenu venu de
   l'adaptateur » — le correctif « Retirer les enfants » répare le
   document en une édition.

## Preview de listes + thème prévisualisé et textAppearance (PE14 S6/S7 → v0.172.0, LayoutLib 3.6.0)

Écran : aperçu XML (`PreviewActivity`), clair ET nuit, profil Pixel 4, sur un projet AVEC dossier
`res/` (thème déclaré au manifest, layouts d'items disponibles). Les listes de l'aperçu ne sont
plus des tuiles mortes et le rendu adopte ENFIN les styles par défaut du thème du projet.

1. **Une liste nue se remplit seule** — insérer un ListView ou un RecyclerView (palette) dans un
   LinearLayout : l'aperçu montre des rangées « Item 1, Item 2, … » (le vrai layout framework
   `simple_list_item_1`, thématé) — une GridView se remplit en grille, un Spinner affiche
   « Item 1 » en état fermé. La pastille « rendue • n vues » compte SEULEMENT les vues du
   document (les rangées synthétisées ne comptent pas).
2. **tools:listitem et tools:itemCount** — taper au source une RecyclerView portant
   `tools:itemCount="4"` : exactement 4 rangées ; avec `tools:listitem="@layout/<un item du
   projet>"`, chaque rangée gonfle CE layout (id/text/outils de prévisualisation dedans) ;
   un `tools:listitem` introuvable retombe aux items texte sans faire échouer le rendu.
3. **android:entries garde la priorité** — un ListView avec `android:entries="@array/…"` du
   dossier res/ montre SES chaînes (pas d'items « Item N » par-dessus).
4. **Entête et pied de ListView** — `tools:listheader`/`tools:listfooter` pointant des layouts
   du projet apparaissent en tête et pied de liste (avant les rangées).
5. **Le thème du projet s'applique aux styles par défaut** — rendre un document de Buttons et
   EditTexts depuis un projet déclarant `Theme.MaterialComponents.DayNight` (ou un thème métier
   dont il hérite) : les boutons prennent le look MaterialComponents (et non plus celui du thème
   CodeIDE) ; avec un projet AppCompat, look AppCompat. Un thème introuvable retombe au thème
   hôte sans erreur.
6. **android:textAppearance enfin honoré** — un TextView portant
   `android:textAppearance="@style/<style du projet>"` (textSize/textColor/textStyle/fontFamily
   posés dans styles.xml) affiche la typographie du style ; un `android:textColor` propre au tag
   L'EMPORTE sur la couleur de l'apparence (sémantique Android).
7. **Aucune régression de rendu** — les documents des scénarios précédents (conformité, palettes,
   chrome système, resize) se rendent à l'identique : la section « Système » de l'arbre, la
   sélection, les cotes et la pastille de vues restent inchangées.


## Deux niveaux d'expansion, zoom, RTL, includes jumeaux et Compose C0 (PE14 S8/S9 → v0.173.0, LayoutLib 3.7.0)

> Passer l'APK de l'app démo LayoutLib (`layout-editor-app-debug.apk`) pour
> les scénarios 7-9, CodeIDE pour les 1-6. Prérequis : un projet avec
> `res/layout/` (scénarios RTL/cookies), un layout à deux vues.

1. **Feuille — deux niveaux** : dans l'aperçu, appuyer la POIGNÉE — la
   feuille monte à la MOITIÉ (le rendu garde la moitié haute) ; appuyer
   encore — TOTAL sous l'appbar ; appuyer encore — repliée. Le cycle
   repasse toujours par la moitié, JAMAIS un saut direct au total.
2. **Feuille — glissement** : glisser la feuille vers le haut et relâcher
   à mi-course — elle s'arrête à la MOITIÉ (pas de saut au total) ;
   glisser depuis la moitié — TOTAL ; glisser vers le bas — repli.
   Le canvas NE DISPARAÎT JAMAIS : à la moitié il garde sa bande haute,
   au total il est derrière la feuille (opaque) et REVIENT INTACT au
   repli.
3. **Zoom + édition feuille totale** : zoomer le rendu (300 %), déployer
   la feuille au TOTAL, éditer un attribut dans le volet, replier —
   le rendu réapparaît avec SON ZOOM (pas de retombée à 10 %, pas de
   re-fit) ; éditer au niveau MOITIÉ : le rendu reste visible et le
   re-rendu arrive SANS saut de zoom.
4. **Clavier** : feuille à la MOITIÉ, taper dans un champ d'attribut —
   la feuille passe au TOTAL au-dessus du clavier ; refermer le clavier
   — la feuille RETOMBE À LA MOITIÉ.
5. **RTL** : un layout à `android:layout_marginStart="16dp"` sur une vue
   (sans marginLeft) : rendu IDENTIQUE à avant en LTR (marge à gauche).
   Passer la langue d'aperçu à une locale RTL (le projet doit fournir
   `values-ar` ou créer le fichier) : la marge PASSE À DROITE (miroir
   Android Studio). `android:layoutDirection="rtl"` sur une seule vue :
   SEULE cette vue se retourne.
6. **Includes jumeaux — sélection** : un layout incluant DEUX fois le
   même `@layout/item` (avec `res/layout/item.xml`) : chaque bouton
   inclus reste SÉLECTIONNABLE par le tap (span dans le document —
   jamais « alignement désarmé ») ; la sélection survit à l'édition des
   vues APRÈS les includes.
7. **Compose C0 — interpréteur** (app démo LayoutLib) : la nouvelle
   classe `InterpreteurComposeC0` n'est pas dans l'app — vérifier par
   le scénario 8 (le rendu affiche les littéraux composés).
8. **Compose C0 — harnais de mesures** :
   `adb shell am start -n jo.layoutlib.editor/jo.layoutlib.compose.ActiviteComposeC0` —
   l'écran affiche le BITMAP composé (deux littéraux « Bonjour, Compose
   C0 ! » et « Composé dans la session LayoutLib. ») et les MESURES
   (inflation du host, rendu+composition, PSS total). « Relancer ×10 »
   affiche les MÉDIANES du régime stationnaire — NOTER les chiffres
   (critère de passage C1 : latence cible < 1 s, PSS à comparer au
   runtime embarqué).
9. **Compose C0 — APK** : l'APK démo passe de ~7,6 Mo à ~16,4 Mo avec
   le runtime Compose embarqué (miroir PC1 — le coût mémoire/taille à
   rapporter au propriétaire pour C1).

## Preview Compose dans la carte « goutte d'eau » (mission « Previews » C1 → v0.174.0, ADR 0140)

Écran : éditeur, un fichier Kotlin du projet contenant au moins deux fonctions
`@Preview @Composable` (des compositions simples suffisent : `Column` de
`Text`/`Button`, quelques arguments littéraux), session LSP Kotlin démarrée
(patienter l'indexation). Le fil conducteur : l'œil de gouttière du Kotlin
ouvre la carte, la carte ne se vide jamais, et le rendu suit les frappes.

1. **L'œil n'apparaît QUE sur le Kotlin** — ouvrir un layout XML : AUCUN œil
   de gouttière (l'aperçu XML reste l'écran dédié) ; ouvrir le fichier
   `.kt` : l'œil apparaît en gouttière.
2. **Ouverture et onglets** — taper l'œil : la carte s'ouvre ancrée à la
   ligne du caret ; les ONGLETS en haut-droite portent les noms des
   `@Preview` du document (ordre du document, noms longs ellipsés) ;
   le BADGE en pied d'écran lit « fichier · @Preview nom » ; basculer les
   onglets : l'écran du mini-téléphone change de rendu à chaque frame.
3. **Live-edit par diff d'identité** — avec la carte ouverte, modifier le
   TEXTE d'un `Text` de la preview affichée : après le court debounce
   (~400 ms), le rendu SE MET À JOUR sans que la carte ne bouge ni ne se
   vide ; un changement sans effet sémantique (espace, commentaire) ne
   re-rend RIEN (digeste inchangé) — le dernier rendu reste.
4. **Erreurs typées isolées** — remplacer un composable du registre par un
   appel INCONNU (ex. `Carte Personnalisee()`) : le rendu revient avec une
   PASTILLE d'erreur à l'endroit du nœud fautif (« hors registre ») et LE
   RESTE de la composition se rend ; corriger : la pastille disparaît au
   rendu suivant.
5. **Jamais de carte vide** — pendant une frappe INCOMPLÈTE (accolade
   ouverte, annotation en cours) : la carte garde le DERNIER rendu valide
   (pas de flash, pas de vide) ; sur un fichier Kotlin SANS `@Preview` :
   le message honnête « Aucune @Preview à afficher » remplit l'écran.
6. **Mort du processus : une reprise, puis l'état honnête** — avec la carte
   ouverte, tuer le processus `:preview` depuis les réglages développeur
   (ou attendre son recyclage) : la carte tente UNE reprise au geste
   suivant (ré-ouvrir la carte) ; si le processus ne revient pas, le
   message d'indisponibilité remplace honnêtement le rendu — l'ÉDITEUR, lui,
   n'a JAMAIS bougé ni gelé (c'est tout l'objet de PC4).

## Preview Compose — ressources, thème du projet et surlignage caret (mission « Previews » C2 → v0.175.0, ADR 0141)

Écran : éditeur, un fichier Kotlin du projet avec une `@Preview` dont le
corps référence les ressources du projet : `Text(R.string.…)` et
`Text("littéral", color = MaterialTheme.colorScheme.primary)`, un
`Spacer(height = R.dimen.…)` ; le projet DOIT avoir `res/values/colors.xml`
(ex. `accent`), `res/values/themes.xml` (`Theme.…` portant `colorPrimary`),
`res/values/dimens.xml` et `res/values-fr/strings.xml` pour le scénario 3.

1. **Les couleurs du THÈME du projet remplacent les défauts** — ouvrir la
   carte : les `Text`/`Button` prennent les couleurs du thème du manifeste
   (ex. `colorPrimary` du projet — PAS le violet Material par défaut du
   runtime) ; le rendu change si on édite `colorPrimary` puis re-rend au
   prochain rendu du document.
2. **`R.string` / `R.color` / `R.dimen` résolus** — le `Text` référençant
   `R.string.…` affiche la chaîne du projet (pas le nom du nœud) ; le
   `Text` avec `color = R.color.…` prend la couleur du projet ; le
   `Spacer(R.dimen.…)` prend la hauteur du projet.
3. **Langue** (projet bilingue) — la carte rend les chaînes de la locale
   SYSTÈME (C2 ne transporte pas encore de sélecteur — D7 ADR 0141) ;
   basculer la langue du système recharge `values-<lang>/` au rendu
   suivant (candidat sélecteur : chantier ultérieur).
4. **SURLIGNAGE caret → nœud** — poser le caret sur la LIGNE d'un appel du
   registre (`Text(…)`, `Column { … }`) : la carte dessine IMMÉDIATEMENT
   un contour bleu (`#43559E`) + remplissage translucide sur LE nœud de
   cette ligne, par-dessus le bitmap ; déplacer le caret d'une ligne :
   le rectangle suit (ou disparaît si la ligne n'a pas de nœud) — jamais
   de rectangle périmé.
5. **Référence introuvable = pastille honnête, jamais un échec** — pointer
   `R.string` vers un nom absent (`R.string.inexistante`) : le rendu
   REVIENT (pas d'échec), le `Text` retombe sur son défaut et l'erreur
   typée accompagne le rendu ; le reste de la composition est intact.
6. **Sans projet ouvert** (fichier hors projet) : la carte rend aux
   DÉFAUTS DU RUNTIME (comportement C1 exact) — aucune erreur bloquante,
   le surlignage caret fonctionne toujours (l'extraction ne dépend pas
   du projet).

### Montée code-editor 4.0.0 (bump-frère — v0.175.0)

7. **13 nouveaux langages colorés** — ouvrir des fichiers `x.c`, `x.cpp`,
   `x.go`, `x.json`, `x.php`, `x.rb`, `x.rs`, `sh`, `x.sql`, `x.toml`,
   `x.ts`, `x.yaml`, `Dockerfile` : la coloration lexicale s'active sans
   redémarrage (corpus cel-textmate 10 → 23 grammaires).
8. **Régime progressif des grands documents** — coller un très grand
   fichier (plusieurs dizaines de milliers de lignes) : l'ouverture reste
   fluide (plus de coupure sèche à 800 lignes), la coloration suit le
   scroll (remplissage arrière-plan), l'édition ne ralentit pas la vue.

## Preview Compose — interactif : touches, animations et appui long → saut source (mission « Previews » C3 → v0.176.0, ADR 0142, code-editor 4.1.0)

Écran : éditeur, un fichier Kotlin avec une `@Preview` dont le corps
contient un `Button { Text("Compter") }`, un
`CircularProgressIndicator()` et un `LinearProgressIndicator()` (les
deux nouveaux genres du registre) ; carte « goutte d'eau » ouverte.
Le ressenti (fluidité, réactivité du doigt) est LE critère — les
mécaniques sont prouvées par les 18 tests automatisés.

1. **La RIPPLE du bouton suit le doigt** — appuyer le bouton dans la
   carte (tap COURT maintenu) : l'état PRESSED + la ripple M3 apparaissent
   SOUS le doigt puis s'animent au relâchement ; le `onClick` du SOURCE
   ne s'exécute pas (no-op honnête — frontière de l'interpréteur, ADR
   0142 D8) : c'est le POINTER qui est réel, pas la sémantique.
2. **Les indicateurs de progression TOURNENT** — carte ouverte : le
   `CircularProgressIndicator` tourne et la barre indéterminée ondule à
   ~30 i/s sur le device de référence ; REFERMER la carte (✕ ou œil) :
   l'animation se FIGE immédiatement (jamais d'animation en
   arrière-fond) ; RÉOUVRIR : elle reprend.
3. **APPUI LONG sur un nœud = SAUT SOURCE** — appuyer LONGUEMENT sur le
   `Text("Compter")` dans la carte : l'éditeur SAUTE le caret à la ligne
   source du nœud touché et la révèle (la carte glisse hors du bloc de
   frappe, elle reste ouverte) ; l'appui long sur un point SANS nœud ne
   fait RIEN (aucun faux saut).
4. **L'édition garde la priorité** — pendant que l'indicateur tourne,
   TAPER dans l'éditeur : la composition re-rend (live-edit C1) et
   l'animation ne fait JAMAIS attendre la frappe (le pompier cède le
   verrou aux rendus d'édition).
5. **Onglets multiples** — deux `@Preview` (une par onglet) avec
   chacune un indicateur : seul l'onglet AFFICHÉ s'anime ; basculer
   d'onglet : la nouvelle identité prend le relais, l'ancienne se fige.
6. **Dégradation honnête sur device chargé** — ouvrir la carte sur un
   document lourd (ou pendant une installation) : la cadence ralentit
   par paliers (30 → 15 → 8 → 4 i/s) au lieu de SAUTER des frames ou de
   geler — le bitmap reste fluide à sa cadence effective, jamais figé.
7. **Un doigt dehors reste honnête** — appuyer près du bord de l'écran
   de la carte puis glisser HORS de l'écran (sur le bezel/l'en-tête) :
   le contact reste CLAMPÉ au bord (la ripple suit le bord, jamais un
   point hors cadre) ; relâcher dehors : le clic se résout proprement.

## Preview Compose — les yeux aux lignes `@Preview` et le correctif « pas de preview » (mission « Previews » C4 → v0.177.0, ADR 0143, code-editor 4.2.0 + codeide-lsp 1.5.0)

Contexte appareil : projet Kotlin OUVERT depuis l'explorateur (SAF),
avec un fichier `.kt` contenant au moins une fonction
`@Preview @Composable` (imports `androidx.compose.ui.tooling.preview.
Preview` et `androidx.compose.runtime.Composable` présents). C'est LE
cas qui échouait en v0.176.0 (clé SAF ≠ clé FUSE du didOpen).

1. **La preview S'AFFICHE ENFIN sur un fichier projet** — ouvrir le
   `.kt` à `@Preview`, taper l'œil de gouttière : la carte « goutte
   d'eau » ouvre et REND la composition (LE correctif : en v0.176.0 la
   carte répondait « Aucune @Preview trouvée » sur TOUT fichier projet).
2. **Un œil à CHAQUE ligne `@Preview`, NULLE PART ailleurs** — avec
   deux fonctions `@Preview @Composable` aux lignes L1 et L2 : un œil
   sur la ligne de CHAQUE `@Preview` (la ligne de l'ANNOTATION, pas
   celle du `fun`), AUCUN œil sur les autres lignes (l'œil suiveur de
   caret de v0.176.x a DISPARU) ; les arguments multi-lignes de
   l'annotation ne déplacent PAS l'œil (il reste à la ligne du `@`).
3. **Un `.kt` SANS `@Preview` : AUCUNE icône** — ouvrir un fichier
   Kotlin sans preview : aucune icône d'aperçu dans la gouttière
   (parité Android Studio) ; l'action du fil d'Ariane ouvre la carte
   qui répond honnêtement « aucune @Preview ».
4. **Tap œil = LA preview de SA ligne** — taper l'œil de la deuxième
   `@Preview` : la carte s'ouvre ancrée à CETTE ligne et l'ONGLET
   sélectionné est CELUI de la preview tapée (pas toujours l'onglet 0) ;
   taper l'œil d'une AUTRE preview : la carte RÉ-ANCRE et l'onglet
   suit ; re-taper l'œil de l'ancre : la carte se REFERME.
5. **Les yeux suivent l'édition** — AJOUTER une fonction
   `@Preview @Composable` au fichier (avec les imports) : un NOUVEL œil
   apparaît à sa ligne après ~400 ms (débounce) SANS ouvrir la carte ;
   SUPPRIMER la fonction : l'œil disparaît ; déplacer une annotation
   vers une autre ligne : l'œil suit.
6. **Yeux immédiats, même machine froide** — ouvrir un fichier à
   `@Preview` : les yeux apparaissent AVANT le premier rendu (le tirage
   direct ne dépend PAS du processus `:preview` — tuer l'app puis
   rouvrir le fichier : les yeux sont là dès l'affichage, la carte
   elle peut prendre ses secondes à froid).
7. **Échantillon sans LSP** — ouvrir un échantillon Kotlin (si
   disponible) : repli best-effort sur la clé d'onglet — les yeux
   peuvent être absents (honnête), mais AUCUN crash de la carte.

## Indexation multi-moteurs + correctif ANR `.kt`/`.kts` (chantier « Indexation » → v0.178.0, ADR 0145)

Contexte appareil : projet Mixte (fichiers `.java`, `.kt`, `.xml`)
OUVERT depuis l'explorateur, sync Gradle terminée au moins une fois.
La maquette de référence : `docs/mockups/indexation/indexation.html`.

1. **L'ouverture d'un `.kt` NE FIGE PLUS l'UI (LE correctif ANR)** —
   juste APRÈS une sync (indexation fraîche), ouvrir un fichier
   `.kt` puis basculer entre onglets `.kt`/`.kts`/`.java` : l'UI
   répond PENDANT le branchement (scroll fluide, menu, bascule
   d'onglets), AUCUNE boîte « L'application ne répond pas » ; le
   logcat finit par montrer `LSP Kotlin connecté` APRÈS le
   branchement. En v0.177.0 ce même geste figeait l'UI plusieurs
   secondes (ANR) — la LATENCE moteur peut rester (nourrissage
   re-payé par connexion tant que DEMANDES D1-a/b côté codeide-lsp
   n'est pas livré), mais JAMAIS plus le fil principal.
2. **Pastille = état GLOBAL (pire phase)** — pendant l'ouverture du
   projet : anneau animé + pourcentage pendant que Java publie ses
   compteurs ; tous moteurs prêts : coche verte discrète ; un
   problème (partiel/erreur) : triangle ambre / croix rouge.
3. **Panneau — vue par moteur, UNE rangée par langue de session** —
   toucher la pastille : section « Moteurs » avec Java (compteur
   `n/N` ou « Prêt », orange), Kotlin (violet) et XML (ocre) :
   « En construction » (construction), « Prêt », « En attente de
   synchronisation » (avant sync) ou « Erreur » ; point OVAL à la
   couleur de langage, texte d'état COLORÉ (vert/ambre/rouge).
4. **Panneau — construction (maquette §2)** — pourcentage à DROITE
   de l'en-tête ; barre de progression visible SEULEMENT pendant
   la construction ; ligne « Les entrées déjà indexées sont
   reprises… : n reprises, n reconstruites » ; liste de travail
   labelée « En cours » (plus « Dernier passage ») ; note neutre
   « complétion limitée, édition non bloquée » ; AUCUN bouton
   « Annuler le passage » (retiré — aucun point d'annulation,
   DEMANDES D5).
5. **Panneau — partiel (maquette §3)** — avec des entrées écartées
   (p. ex. jar illisible dans le classpath) : bandeau AMBRE,
   section « Entrées écartées » UNE ligne par message, boutons
   « Réessayer les entrées échouées » (PLEIN) puis « Tout
   réindexer » (cerné) — les DEUX déclenchent la revalidation
   (logcat : réindexation) et la pastille repasse en construction.
6. **Panneau — repos (maquette §4)** — après un passage complet :
   « Dernier passage » (durée, bibliothèques reprises/reconstruites,
   fichiers ré-analysés) + « Ce qui a coûté le plus de temps » (3
   barres, la plus large = le plus cher) + « Réindexer » cerné.
   Rotation pendant le panneau ouvert : contenu ré-affiché identique
   (5 s de grâce de l'abonnement).
7. **Agrégat honnête** — refermer le projet (retour accueil) : la
   pastille disparaît (aucun moteur vivant) et le panneau ouvert se
   REFERME ; rouvrir le projet : les rangées repartent à « En
   attente de synchronisation » puis suivent les nourrissages.
8. **Erreur (maquette §5)** — avec un index en erreur (stockage
   corrompu) : bandeau ROUGE, section « Erreurs bloquantes », un
   seul bouton « Réindexer » PLEIN ; PAS de barre de progression.

## Assistant IA — navigation de repli (rapport IA Lot 1 → v0.186.0, ADR 0148)

| # | Procédure | Résultat attendu |
|---|-----------|------------------|
| IAN1 | Éditeur ouvert, tiroir IA → onglet Chat → (assistant inactif) → bouton « Activer l'assistant » | L'écran de consentement IA s'ouvre (logcat : `Navigation` : `routage via l'accueil hôte -> consentement_ia`) — plus de silence |
| IAN2 | Sur l'écran de consentement, toucher « Activer » | Le consentement daté est enregistré ; retour sur le graphe de l'hôte ; retour système → l'ESPACE de travail est intact (onglets, session, tooling) |
| IAN3 | Double appui rapide sur « Activer l'assistant » depuis le chat | Un SEUL écran de consentement (SINGLE_TOP + garde anti-empilement) |
| IAN4 | Chat avec état « Ajoutez une clé » → bouton d'ajout de clé | Le DÉTAIL du fournisseur visé s'ouvre avec le BON identifiant (extra `fournisseur_ia_identifiant`) |

## Assistant IA — la boucle du consentement refermée (rapport IA Lot 2 → v0.187.0)

| # | Procédure | Résultat attendu |
|---|-----------|------------------|
| IA2 | Paramètres → IA : activer l'interrupteur maître → « Pas maintenant » | Retour aux réglages SANS réouverture ; l'interrupteur est DÉCOCHÉ (état réel) |
| IA3 | Même parcours, puis « Activer » | Retour sans réouverture ; interrupteur ACTIVÉ ; le consentement est daté (ligne « Ce qui est envoyé ») |
| IA4 | Depuis Paramètres → IA, pivoter l'écran / arrière-plan puis revenir | Aucune ouverture parasite du consentement ; l'interrupteur suit l'état réel à chaque retour au premier plan |

## Assistant IA — la zone de saisie du chat libérée (rapport IA Lot 3 → v0.188.0, ADR 0149)

| # | Procédure | Résultat attendu |
|---|-----------|------------------|
| IA5 | Tiroir IA → Chat (IA ACTIVE, conversation vide) | Accueil visible **ET** champ de saisie + Envoyer visibles — plus de calque recouvrant la saisie (B1a/B1b) |
| IA6 | Ouvrir le clavier dans le chat | Le champ reste visible AU-DESSUS du clavier ; le rail de fragments disparaît le temps de la frappe et revient au repli (B1c, ADR 0149) |
| IA7 | Sans clé enregistrée, ouvrir le chat (IA active) | État « Aucune clé enregistrée » avec bouton « Ajouter une clé » vers le fournisseur ACTIF ; la saisie reste fermée (S3) |
| IA8 | Ajouter la clé depuis cet état puis revenir au chat | Le chat repasse à l'accueil vide avec la saisie LIBRE (la clé est re-vérifiée au retour au premier plan) |
| IA9 | Tiroir IA → Chat (IA INACTIVE) | Calque « Assistant non activé » + bouton « Consulter le consentement » ; saisie masquée (B1a) |
| IA10 | Pivoter l'écran, clavier flottant, mode fenêtré, pendant la saisie du chat | La saisie reste visible et fonctionnelle (B1c « à valider sur appareil » — ADR 0149) |

## Assistant IA — plus de bridage silencieux des réglages (rapport IA Lot 4 → v0.189.0)

| # | Procédure | Résultat attendu |
|---|-----------|------------------|
| IA11 | Paramètres → IA : vider « jetons max », quitter le champ (retour/arrière-plan), revenir | Le champ REPREND la valeur précédente (4096 par défaut) — jamais 128 ; le réglage persisté est intact |
| IA12 | Saisir du texte non numérique dans « délai », quitter le champ | Idem pour le délai ; aucune écriture n'a lieu |
| IA13 | URL de base du fournisseur compatible OpenAI : `http://` distant, puis `http://localhost:11434/v1` | Message d'erreur explicite pour le premier, accepté pour la boucle locale (mécanisme ValidationUrlIa existant, confirmé par le rapport IA S6) |

## Assistant IA — préréglages d'URL du fournisseur (rapport IA Lot 5, point 1 → v0.190.0)

| # | Scénario | Résultat attendu |
|---|----------|------------------|
| IA14 | Paramètres → IA → fournisseur « Compatible OpenAI » | Une carte « Préréglages d'URL » en tête propose « OpenRouter » (`https://openrouter.ai/api/v1`) et « Serveur local (port 11434) » (`http://localhost:11434/v1`) |
| IA15 | Toucher le préréglage « OpenRouter » | Le champ URL de base est PRÉREMPLI avec l'URL du préréglage, sans erreur de validation, et l'URL est persistée (rouvrir l'écran : elle y est) |
| IA16 | Paramètres → IA → fournisseur « Anthropic » | La carte propose « Anthropic (officiel) » (`https://api.anthropic.com`) ; l'appui préremplit pareillement |
| IA17 | Toucher un préréglage puis « Tester la connexion » | Le test utilise l'URL PRÉREMPLIE (le ping part vers l'URL affichée, pas vers l'ancienne) |

## Assistant IA — sélecteur de modèle dans l'en-tête du chat (rapport IA Lot 5, point 3 → v0.191.0)

| # | Scénario | Résultat attendu |
|---|----------|------------------|
| IA18 | Tiroir IA → onglet Chat (assistant actif, clé présente) | Un EN-TÊTE affiche le modèle actif (ou « Choisir un modèle » si vide) au-dessus des messages |
| IA19 | Toucher le modèle dans l'en-tête | Un sélecteur propose le modèle actif + les modèles recensés du fournisseur actif ; en choisir un → l'en-tête affiche le NOUVEAU modèle et l'envoi suivant l'utilise (sans repasser par Paramètres) |
| IA20 | Dans le sélecteur, toucher « Actualiser la liste » | Snackbar « Modèles récupérés : N » ; rouvrir le sélecteur montre la nouvelle liste (recensement fait SANS quitter le chat) |
| IA21 | Assistant inactif (ou clé absente) | L'en-tête est MASQUÉ — aucun sélecteur ne s'offre dans les états fermés |

## Assistant IA — compteur d'usage par conversation (rapport IA Lot 5, point 4 → v0.192.0)

| # | Scénario | Résultat attendu |
|---|----------|------------------|
| IA22 | Discuter (fournisseur Anthropic ou compatible OpenAI rapportant l'usage), puis regarder l'en-tête du chat | Le compteur « Entrée X · Sortie Y » apparaît dès la première réponse terminée et CUMULE à chaque échange ; il reste masqué si le serveur ne rapporte rien |
| IA23 | Ouvrir Historique → une conversation ayant rapporté de l'usage | La méta se termine par « · jetons : N » (total entrée+sortie) ; les conversations sans usage restent inchangées |
| IA24 | Rouvrir une conversation depuis l'historique | Le compteur du chat REPREND les cumuls de la conversation (pas remis à zéro) ; « Nouvelle conversation » le remet à zéro |
| IA25 | Couper le réseau pendant une génération (échec) puis régénérer avec succès | L'échec n'a cumulé AUCUN jeton ; la régénération réussie cumule les jetons de SON flux |

## Assistant IA — actions de remédiation par carte d'erreur (rapport IA Lot 5, point 5 → v0.193.0)

| # | Scénario | Résultat attendu |
|---|----------|------------------|
| IA26 | Couper le réseau, envoyer un message (échec hors-ligne/délai) | La carte d'erreur propose « Réessayer » ; rétablir le réseau puis toucher → le dernier échange REPART |
| IA27 | Fournisseur mal configuré (clé refusée ou consentement révoqué) puis envoi | La carte propose « Ouvrir les réglages » → mène à Paramètres → IA ; une clé illisible (Keystore réinitialisé) propose la même action |
| IA28 | Serveur non standard qui renvoie une réponse illisible | La carte propose « Changer de modèle » → ouvre le SÉLECTEUR de l'en-tête du chat (changer puis réessayer sans quitter le chat) |

## Montée codeide-lsp 1.13.0 — les scénarios des quatre mondes (bump-frère → v0.197.0, codeide-lsp 1.13.0)

Contexte appareil : un projet par monde — Compose (une `data class`
modélisée par un ViewModel à `StateFlow`), mixte Java/Kotlin (une classe
`.java` à champs et getters consommée depuis un `.kt`, un layout qui
référence `@string`). Chaque projet doit être SYNCHRONISÉ et l'écran
d'indexation au vert AVANT de juger un diagnostic. Notes de passage :
`docs/MISE-A-JOUR-CODEIDE.md` § 1.13.0 du dépôt codeide-lsp (montée de
coordonnées pure, AUCUN changement d'API) ; détail des trois vagues :
`CHANGELOG.md` § 1.13.0 du frère ; rapports des quatre mondes :
`docs/RETOURS-UTILISATEURS-2026-10-25.md` du frère.

1. **Constructeur à valeurs par défaut** — appeler un constructeur
   Compose sans ses paramètres optionnels (ex. `Article("Lait", 2)`
   alors que `Article` accepte `poids: Float = 1f, bio: Boolean =
   false`) : AUCUN « 4 fourni(s), 6 attendu(s) » (le faux positif n° 1
   du quotidien — 1.12.0 le posait systématiquement).
2. **Data class — `copy` en résolution ET en complétion** — écrire
   `article.copy(titre = "…")` : la ligne reste MUETTE là où 1.12.0
   soulignait `copy` ; la complétion après `article.` PROPOSE `copy`
   (les membres synthétisés du modificateur `data` apparaissent, pas
   seulement les membres écrits).
3. **`collect` sur un `StateFlow` du ViewModel** — dans l'écran,
   `vueModele.effets.collect { … }` : MUET (1.12.0 y posait un faux
   `kt.unresolved` après récepteur typé) ; l'erreur volontaire placée
   dans un AUTRE fichier du projet reste attrapée (le silence n'est
   pas général).
4. **Getters Compose, types imbriqués, annotations stdlib** —
   `MaterialTheme.typography`, `Arrangement.spacedBy(8.dp)`,
   `Icons.Filled.Add`, `Paint.Cap.ROUND` : muets ; la complétion après
   `MaterialTheme.` propose les membres (typography, colorScheme — en
   1.12.0 elle ne proposait que « INSTANCE ») ; `@OptIn(...)` et
   `@Suppress(...)` ne sont plus marqués inutilisés, les imports
   `by` (`getValue`/`setValue`) non plus.
5. **Monde mixte — membres Java du projet depuis Kotlin** — depuis un
   `.kt`, taper `vinyle.` sur une instance d'une classe `.java` du
   projet : la complétion propose les champs, les méthodes ET les
   getters (`getTitre()`, propriété synthétique `titre`) — en 1.12.0
   la popup était VIDE (0 item) ; le survol d'un membre Java affiche
   sa signature ; un chaînage `v.getTitre().length` reste muet.
6. **`R` sans import (même paquet)** — dans le `.kt` du monde mixte,
   écrire `R.string.app_name` SANS import : MUET (1.12.0 le
   soulignait — le `R` du build suffit, le manifeste AGP 9 ne porte
   plus de `package=`).
7. **Vrai positif rattrapé (preuve d'absence projet)** — taper
   `article.prixInconnu` : SIGNALÉ en rouge (le type `Article` est DU
   projet, sans héritage écrit — 1.12.0 se taisait) ; corriger la
   ligne : le souligné disparaît. Les deux tableaux (plus de faux
   positif, toujours un vrai) sont tenus ENSEMBLE.

Backlog partagé à surveiller pour les prochaines montées (RETOURS § 4
du frère) : membres hérités du classpath (`submitList`/`getString`)
en TÊTE, puis erasure après `remember { }` et extensions de scope
Compose — les fichiers concernés baveront encore jusqu'à la vague
suivante côté codeide-lsp.
