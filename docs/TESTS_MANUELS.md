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
| O3 | Page dossier → choisir un dossier inscriptible (ex. `Documents/CodeIDE`) | « Vérification » puis le libellé du dossier s'affiche ; le fichier témoin `codeide-temoin-<horodatage>` n'est **plus** présent dans le dossier (créé puis supprimé) ; l'URI d'arborescence apparaît en lecture/écriture dans `persistedUriPermissions`, comptée une fois |
| O4 | « Plus tard » à la page dossier, finir l'assistant | L'accueil s'ouvre avec le bandeau « Configurer le dossier de travail » ; le bandeau **disparaît** après configuration du dossier (étape 6/7 : réglages) ; relancer l'app : l'assistant ne revient pas (installation terminée) |
| O5 | Page apparence : choisir sombre, désactiver les couleurs dynamiques, passer en anglais | Chaque choix prend effet **immédiatement** (recréation d'écran, texte bascule en anglais) ; tuer le processus (`adb shell am kill jo.codeide`) et relancer : les choix sont conservés ; sur Android 13+, le réglage système « langue par application » reflète fr/en |
| O6 | Page profil : saisir un nom d'auteur et une licence, puis **rotation** de l'écran à chaque page ; enfin « Terminer » | Le nom et la licence restent saisis après rotation ; « Terminer » referme l'assistant sur l'accueil ; relancer : accueil direct ; (optionnel) `adb shell am kill` au milieu de l'assistant, relancer : la page et les saisies sont restaurées |

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

## À venir

- **Étape 13+** : espace de travail de l'éditeur (EditorActivity).
