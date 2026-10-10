# Spécification Exécuter — « Run » sur l'appareil : compiler, installer, lancer, Logcat

Références de comportement : bouton **Run** d'Android Studio (compilation
→ installation → lancement → Logcat), onglet **Logcat** d'Android Studio
(colonnes, filtres, couleurs de niveau, pause du défilement).

Décisions : ADR 0102 (installation/lancement), ADR 0103 (pont de logs).

## 1. Périmètre

La mission « Exécuter » boucle le cycle sur le téléphone, **sans adb ni
ordinateur** :

1. compiler la variante debug (chaîne Gradle existante) ;
2. installer l'APK produit (PackageInstaller, confirmation système) ;
3. lancer l'application (intent explicite, relances) ;
4. suivre en direct les logs de l'application lancée (vrai Logcat,
   phase R3).

**Hors périmètre** : adb sans fil, émulateur, débogueur, profileur,
projets JVM/Spring, logs d'autres applications, publication sur un
magasin.

## 2. Architecture

| Brique | Emplacement | Note |
|---|---|---|
| Port `ApkInstaller` | `core:domain` | faux dans `core:testing` |
| Implémentation Android | module `app` | PackageInstaller, process principal |
| `ExecuterApplicationUseCase` | `core:domain` | localise l'APK, lit `output-metadata.json`, orchestre |
| Lecteur `output-metadata.json` | `core:domain` (pur) | `applicationId`, variante |
| Onglet Logcat | `feature:editor` | 4e onglet du panneau inférieur (R3) |
| Pont de logs | module `applog-runtime` (R2) | Binder, Java pur, zéro permission |
| Injection Gradle | `tooling/server` (R2) | script d'init + plugin API publique |

**Aucun changement de protocole tooling en R1** : le chemin de l'APK est
déterministe (`<projet>/app/build/outputs/apk/debug/`), identique au
contrôle de `scripts/verify-templates.sh`. La détection de fin de build
réutilise l'état `BuildFinished` existant.

## 3. Flux « Exécuter » (R1)

1. **Bouton Exécuter** (icône existante de l'AppBar) : bascule sur
   l'onglet Console (filtre BUILD), lance `assembleDebug` pour le
   module `:app` du projet.
2. **Build réussi** : l'APK est localisé ; ligne de console
   « Installation de l'application… » (canal BUILD, style info).
3. **Permission « sources inconnues »** (première fois) : snackbar
   « Pour installer vos applications, autorisez CodeIDE » + action
   **« Autoriser »** → écran système ; **reprise automatique** au
   retour (bornée à 5 min). Snackbar « Installation annulée » si
   l'utilisateur refuse.
4. **Confirmation système** (première installation, ou si le système
   l'exige) : la boîte système s'affiche ; le résultat s'affiche en
   console.
5. **Succès** : « Application installée » puis « Lancement de
   <nom>… » ; l'app démarre **par-dessus** CodeIDE (comme Android
   Studio) ; snackbar « <nom> lancée — Logcat en arrière-plan ».
6. **Échecs** (console + snackbar, action correctrice) :
   - signature différente : « Une version différente est déjà installée »
     + action **« Remplacer (efface les données) »** avec confirmation
     nommant la perte ;
   - version plus ancienne : action « Désinstaller d'abord » ;
   - APK introuvable : « Le build n'a produit aucun APK » (vérifier
     le module application) ;
   - espace insuffisant / APK invalide : message système traduit.

## 4. Onglet Logcat (R3)

### 4.1 Structure

Panneau inférieur, 4e onglet (`OngletPanneau.LOGCAT` — l'ordre des
`TabItem` suit strictement l'enum). De haut en bas :

| Zone | Contenu |
|---|---|
| Barre d'outils | sélecteur de **processus** (puce), filtre **texte** (champ), filtre **niveau** (menu : Verbose → Assert), **regex** (option), pause/reprise, effacer |
| Bandeau d'état | « N lignes perdues » (orange), « Le processus s'est arrêté : <raison> » (rouge, R4 : `ApplicationExitInfo`), « Session précédente » (gris) |
| Table | colonnes horodatage / pid-tid / étiquette / niveau / message |
| État vide | « Lancez votre application pour voir ses journaux » |

### 4.2 Jetons (thème nuit — référencés depuis EXPLORATEUR_V2 § 3)

| Token | Valeur | Usage |
|---|---|---|
| fond-ecran | `#14171d` | fond de l'onglet |
| fond-surface | `#20252e` | barre d'outils, sélecteur |
| bordure | `#2c323d` | séparateurs |
| texte | `#dde3ec` | message |
| texte-2 | `#99a2ae` | horodatage, pid-tid |
| niveau Verbose | `#6c7581` | texte-3 |
| niveau Debug | `#7ab8f5` | accent |
| niveau Info | `#6fc47a` | vert |
| niveau Warning | `#eda63f` | orange |
| niveau Error | `#e5584f` | rouge |
| niveau Assert | `#e5584f` gras | rouge |
| monospace | 11 sp | colonnes horodatage/pid/étiquette, message |

Rayon `r-s` 7 dp pour la puce de processus ; cibles ≥ 48 dp ; descriptions
de contenu en français ; police monospace pour la table (tampon borné,
liste virtualisée — jamais de `TextView` à append sans borne).

### 4.3 Comportements

- **Défilement automatique** : suit la fin tant que l'utilisateur n'a
  pas remonté (honnête — arrêt dès qu'un doigt touche la liste) ;
  bouton **pause** qui fige le tampon d'affichage (la collecte
  continue, bornée) ; reprise = rattrapage.
- **Filtre niveau** : masque les niveaux inférieurs (façon Android
  Studio) ; **filtre texte** sous-chaîne, insensible à la casse ;
  option **regex** (erreur de motif signalée en ligne).
- **Effacer** : vide le tampon affiché (les sessions gardées ne sont
  pas touchées).
- **Copier** (clic long sur une ligne) : copie la ligne brute ;
  **Exporter/Partager** : uniquement **action explicite** avec
  avertissement (« Les journaux peuvent contenir des données
  sensibles ») — jamais automatique.
- **Sessions** : une session par (paquet, pid, nom de processus) ; le
  sélecteur de processus liste les sessions vivantes + « session
  précédente » consultable (dernière ligne et raison de fin conservées
  entre deux démarrages de CodeIDE, bornées, stockage privé).
- **R4** : lignes de pile `Fichier.kt:12` **cliquables** → ouvrent le
  fichier à la ligne ; snackbar « L'app a planté » + action
  « Voir la trace » lors d'une exception non interceptée.

## 5. Politiques d'honnêteté (affichées dans l'interface)

- « Sans adb : journaux de votre application uniquement, depuis le
  démarrage de son processus. » (pied de l'état vide)
- « Arrêter l'écoute » et non « Arrêter l'application » (pas de vrai
  stop sans adb).
- Compteur de lignes perdues jamais silencieux.
- Aucune permission n'est ajoutée à l'application de l'utilisateur
  (ADR 0103) — le réglage d'injection le dit : « ajoute une
  bibliothèque de débogage à vos builds debug ».

## 6. Critères d'acceptation

1. Bouton Exécuter : build → APK installé → app lancée, sur appareil,
   sans adb ; reprise automatique après l'autorisation « sources
   inconnues ».
2. Relances de lancement : l'app démarre ≤ 2 s après l'installation
   (10 × 200 ms).
3. `ExecuterApplicationUseCase` : testé en JVM pur (APK absent,
   permission refusée puis accordée, confirmation système, succès,
   échec signature avec action).
4. Chaîne de vérification verte sur les modules touchés
   (spotlessCheck, detekt, tests, lint, kover, assembleDebug).
5. R3 : tampon borné, aucune allocation en rafale, filtres en JVM pur
   testés, rejet d'un émetteur non autorisé (UID) testé.

## 7. Avancement

| Phase | Contenu | État |
|---|---|---|
| R0 | investigation, ADR 0102/0103, maquette, cette spécification | ✅ v0.81.0 |
| R1 | installer et lancer (port + use case + UI) | ✅ v0.81.0 |
| R2 | pont de logs (bibliothèque, service, injection Gradle) | ✅ v0.83.0 |
| R3 | onglet Logcat | à venir |
| R4 | traces cliquables et plantages | à venir |
| R5 | finitions et tests | à venir |
