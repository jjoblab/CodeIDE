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

Préambule : le menu debug n'existe **qu'en build debug** (bouton « Menu
debug » en bas à droite de l'accueil). Les chemins internes sont lus via
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

## À venir

- **Étape 4+** : parcours SAF (dossier de travail, test d'écriture,
  dossiers refusés par Android 11+).
- **Étape 5+** : rotation et mort du processus dans l'assistant.
