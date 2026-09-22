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

## À venir

- **Étape 3** : procédures de plantage (menu debug, CrashActivity, boucle
  de plantages, rapport non consulté).
- **Étape 4+** : parcours SAF (dossier de travail, test d'écriture,
  dossiers refusés par Android 11+).
- **Étape 5+** : rotation et mort du processus dans l'assistant.
