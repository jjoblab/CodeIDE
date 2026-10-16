# Protocole de mesure appareil — préparation de la séance dédiée (S0-c)

> **Document de préparation** (v0.134.20). Il rend la séance S0-c
> « protocole de mesure appareil » exécutable TELLE QUELLE : matériel,
> séquences, commandes, cibles et modèle de procès-verbal sont prêts.
> Le protocole écrit d'origine vit dans `docs/LSP_JAVA.md` §11.2 ; le
> PV de la séance sera archivé dans `docs/LSP_JAVA.md` §18.2.
>
> Rappel de positionnement : le S0-c est le DERNIER jalon Java restant
> (décision D3 partiellement actée le 16/10/2026 — ADR 0110 ; il ne
> reste que ce volet mesure). Sa conclusion DÉCLENCHE le lot S3 Java :
> lot G multi-session, ADR 0117 phase B (écran d'indexation), transport
> socket (ADR 0109 plan B), extraction bibliothèque.

## 1. Appareils et matériel

| Rôle | Profil | Raison |
|---|---|---|
| Référence | Milieu de gamme, 8 Go, Android 13+ | Le profil nominal de l'audience (§11.2) |
| Plancher | Entrée de gamme, ≤ 4 Go, Android 11-12 | Valide les dégradés honnêtes (dumb mode, bascules) |

- Un ordinateur avec `adb` (platform-tools) et la clé du dépôt ;
- une vidéo 60 i/s de l'écran de l'appareil (scrcpy ou caméra — le
  chronométrage des frappes se fait SUR la vidéo, jamais à l'œil) ;
- le projet de test cloné SUR l'appareil (voir §2).

## 2. Préparation logicielle (avant la séance)

1. **APK debug** : installer la dernière build `debug` (jamais une
   release — les journaux `AppLogger` et l'écran Diagnostic doivent
   être présents).
2. **Projet de test** : cloner sur l'appareil un projet Android moyen
   réel (le jeu « moyen » du banc — classpath `debugCompileClasspath`
   réel de `:app` — est la référence ; un projet à deux modules avec
   Material 1.12 fait l'affaire si le banc n'est pas installé).
3. **Écran Diagnostic/état d'indexation** : vérifier qu'il est
   accessible (livré étape 4) — c'est la source des latences internes.
4. **Journal `AppLogger` expurgé** : préparer la commande `logcat`
   (§4) et s'assurer que le niveau FINE est actif en debug.
5. **Synchronisation initiale** : ouvrir le projet, laisser la sync
   Gradle + le classpath SE PRÉPARER COMPLÈTEMENT avant la première
   séquence (l'ouverture à froid du §3 est celle du MOTEUR, pas celle
   de la sync).

## 3. Les sept séquences de mesure

Chaque séquence = une vidéo + un journal + une ligne du PV. Les
latences p50/p95 sont dérivées des timestamps `AppLogger` (ms, métrique
interne) CORRIGÉES par la vidéo (perçue) — les deux colonnes existent
dans le PV, elles ne sont pas fusionnées.

| # | Séquence | Étapes | Données capturées |
|---|---|---|---|
| 1 | **Ouverture à froid** | Tuer le process, rouvrir le projet, ouvrir un gros fichier Java/Kotlin, première complétion | Latence première indexation, tas initial, images perdues |
| 2 | **Ouverture à chaud** | Fermer l'éditeur (projet vivant), rouvrir le même fichier | Latence réouverture (cible < 1,5 s), tas |
| 3 | **+1 dépendance** | Ajouter UNE bibliothèque au build, relancer la sync | Coût de la sync, re-indexation (seul le NOUVEAU jar/AAR doit être payé — ADR 0111), diagnostics à jour |
| 4 | **Sync Gradle complète** | `./gradlew` complet depuis l'appareil, retour à l'éditeur | Latence bout-en-bout sync → éditeur utilisable, état d'indexation honnête (écran Diagnostic) |
| 5 | **Création + complétion** | Créer un type, le référencer depuis un autre fichier, compléter | p95 complétion membres < 150 ms, types/imports < 100 ms (cibles §9) |
| 6 | **Fermeture/réouverture** | Fermer le projet, le rouvrir | Poignées constantes (`/proc/<pid>/fd`), 0 ressource orpheline |
| 7 | **Bascule d'écrans** | Éditeur ↔ accueil ↔ paramètres ×5 | Frappes 0-travail-LSP-sur-main (vidéo), 0 image perdue, état de session stable |

À chaque séquence : consigner tas (`Runtime.totalMemory−free` via
l'écran Diagnostic + `dumpsys meminfo`), poignées, images perçues,
anomalies. Comparer aux cibles §5 IMMÉDIATEMENT (une dérive se constate
à chaud, pas à la relecture).

## 4. Commandes de capture

```bash
# Tas du processus (par séquence, 3 relevés : début / pic / fin)
adb shell dumpsys meminfo <paquet> | sed -n '/TOTAL/p;/App Summary/,/TOTAL PSS/p'

# Poignées de fichiers (fuite : la courbe doit rester plate, pool LRU 24)
adb shell "cat /proc/$(adb shell pidof <paquet>)/fd | wc -l"

# Journal expurgé (lancer AVANT la séquence, filtrer le tag LSP)
adb logcat -c && adb logcat -v time AppLogger:* Lsp:* > seance-<seq>.log

# Timestamps vidéo : la première image de chaque frappe/complétion
# sert d'horodatage perçu (60 i/s = pas de 16,7 ms).
```

Convention de nommage des artefacts : `seance-s0c-seq<N>-<appareil>.log`
et `.mp4`, archivés HORS dépôt (le PV cite les chemins locaux et les
CHIFFRES, jamais les binaires).

## 5. Cibles à comparer (LSP_JAVA.md §9)

| Cible | Seuil | Statut avant séance |
|---|---|---|
| Ouverture index chaud | < 1,5 s | 835-1 627 ms (bureau, sans LSP4J/UI) |
| Complétion membres p95 | < 150 ms | 5-6 ms (bureau) — tenue |
| Complétion types/imports p95 | < 100 ms | 78-140 ms (bureau, 250 jars) |
| Frappe : 0 travail LSP sur main, 0 image perdue | strict | à valider (jamais mesuré) |
| +1 dépendance → seul le nouveau payé | strict | violée au banc (ADR 0111 corrige) |
| Poignées constantes | strict | 28-42 (pool LRU 24) — tenue |
| Fermeture : 0 ressource orpheline | strict | tenue au test, à prouver appareil |

ART ≈ 3-5× le bureau : une cible tenue au bureau ne l'est PAS
nécessairement sur l'appareil — c'est précisément ce que la séance
mesure. Toute cible violée = un arbitrage à consigner dans le PV (le
plan B socket ADR 0109 n'entre en jeu QUE si les mesures l'exigent).

## 6. Modèle de procès-verbal (à archiver dans LSP_JAVA.md §18.2)

```markdown
### 18.2 v<version> — PV de mesure appareil (S0-c)

- Date / appareils : <date> ; <milieu de gamme 8 Go, Android X> ;
  <entrée de gamme, Android Y>.
- APK : <build> ; projet de test : <projet, modules, dépendances>.
- Résultats par séquence (§3 du protocole) :

| # | Séquence | p50 (interne) | p95 (interne) | Perçu (vidéo) | Tas | FD | Images perdues |
|---|---|---|---|---|---|---|---|
| 1 | froid | | | | | | |
| 2 | chaud | | | | | | |
| 3 | +1 dép | | | | | | |
| 4 | sync | | | | | | |
| 5 | complétion | | | | | | |
| 6 | fermeture | | | | | | |
| 7 | bascules | | | | | | |

- Cibles §9 : tenues <…> ; violées <…> ;
- Arbitrages : <socket ADR 0109 ? priorisation ? acceptation documentée ?> ;
- Décision S3 : <lot G multi-session GO / report> ; <ADR 0117 phase B GO / report> ;
- Artefacts : seance-s0c-seq<N>-<appareil>.log/.mp4 (hors dépôt).
```

## 7. Check-list de séance

**Avant** : §2 intégral cochée ; appareils chargés (≥ 80 %) ; stockage
libre (≥ 2 Go) ; vidéo validée 60 i/s ; logcat lancé ; heure sync.

**Pendant** : une séquence à la fois, dans l'ordre (les séquences 1-2
dépendent de l'état thermique — ne pas intercaler une build) ; chaque
anomalie notée À CHAUD dans le PV brut (une anomalie non datée est une
anomalie perdue) ; ne JAMAIS dévier du protocole sans le consigner.

**Après** : PV rempli (modèle §6) et archivé dans `docs/LSP_JAVA.md`
§18.2 ; ADR 0110 mis à jour si la décision D3 évolue ; arbitrage S3
consigné ; CI re-vérifiée si une correction découle de la séance.

## Références

- `docs/LSP_JAVA.md` §11.2 (protocole écrit d'origine), §9 (cibles),
  §18.2 (emplacement du PV)
- ADR 0110 (décision D3 — partiellement actée 16/10/2026)
- ADR 0109 (transport — plan B socket), ADR 0111 (index par segments),
  ADR 0117 (écran d'indexation)
- `docs/audit/rapport-analyse-lsp-java-codeide.md` §11.2 (S0-c), §16.3
  (report sans échéance — celui que cette préparation lève)
