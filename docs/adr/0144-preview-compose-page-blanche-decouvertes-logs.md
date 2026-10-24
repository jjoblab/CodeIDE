# ADR 0144 — Preview Compose « page blanche » : découvertes des journaux appareil (correction DIFFÉRÉE)

- **Statut** : **accepté (documentation)** — le propriétaire tranche :
  « Laisse la correction pour plus tard, mais crée un fichier ADR du
  problème et des découvertes faites dans les logs. » Le chantier
  indexation prime (v0.178.0) ; AUCUN correctif de code n'est livré ici.
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (retour propriétaire sur les tests manuels
  v0.177.0 : « j'ai testé la preview compose, pour l'instant cela
  n'affiche qu'une page blanche dans la goutte d'eau » — journaux
  appareil `codeide-logs-2026-10-23-231457.zip`, 176 entrées, 3
  sessions, DEBUG).
- **Dépend de** : ADR 0140/0141/0142/0143 (C1→C4 — la carte, le
  contenu, l'interactivité, les yeux), PREVIEW.md §4.4.

## Contexte

Après le correctif v0.177.0 (« pas de preview » — la découverte parle
l'URI LSP du didOpen, ADR 0143), la carte « goutte d'eau » EXISTS,
la découverte TROUVE, et le moteur JOURNALISE des rendus réussis —
mais l'utilisateur ne voit QU'UNE PAGE BLANCHE. Ce document fige les
faits des journaux AVANT que la mémoire ne s'efface, range les
hypothèses, et prépare le plan d'investigation du prochain chantier.

## Faits établis (journaux du 23/10, moto g06, Android 15, 440 dpi)

Chronologie de la session témoin (v0.177.0, projet à previews
Compose) :

1. **La découverte fonctionne** : plus aucun « aucune preview
   découverte » sur le fichier à previews dans la session 3 (il en
   reste sur `h-9da19056`, un fichier SANS `@Preview` — comportement
   correct C4).
2. **Le pipeline de rendu statique RÉUSSIT — 16 fois** :
   `MoteurPreviewCompose: rendu compose : 2 preview(s), 2 bitmap(s),
   profil=ProfilApercuCompose(393, 851, 440), nuit=false` entre
   ~22 h 50 et ~22 h 53. Le moteur déclare donc DES bitmaps rendus
   pendant TOUTE la fenêtre où l'utilisateur voyait du blanc.
3. **La mort vient AU TOUCHER (interactif C3)** : premier événement
   après la fenêtre des rendus —
   `IsolationPreviewCompose: processus :preview indisponible (rendu
   :preview échoué : JobCancellationException) — reprise (compose)`
   puis, 32 ms plus tard :
   `IsolationPreviewCompose: processus :preview désactivé pour la
   session (liaison :preview impossible) — preview Compose
   indisponible (aucun repli local, par conception PC4)`.
   Le processus isolé `:preview` est mort pendant un rendu
   interactif, et la RELIAISON a échoué — la session est définitivement
   dégradée (PC4 : jamais de repli in-process).
4. **Le pompier s'éteint 16 fois** : `pompier interactif éteint : 3
   frames échouées de suite (processus :preview indisponible) — le
   dernier rendu reste`, répété à CHAQUE tentative de toucher.
   Deux enseignements : (a) le contrat « le dernier rendu reste » est
   bien le DESSEIN de l'état machine ; (b) la pompe se RÉ-ARME à chaque
   toucher ALORS que la session est désactivée — 16 allumages
   symboliques, aucun rendu possible : bruit + attente déceptive.
5. **L'appareil était sous pression mémoire** : `Editor: caches
   d'arbre libérés (pression mémoire)` à la fin de session ; ET dans
   la session 1, l'orchestrateur Gradle est resté MUET 15 s
   (`aucun pong en 15000 ms — arrêt forcé`), le build a échoué, la
   console affichait un écart max de **19 864 ms** entre émission et
   publication. Le moto g06 (entrée de gamme) était saturé (sync
   Gradle + indexation LSP + rendu :preview simultanés).
6. **Aucune exception coté bitmaps** : aucune stack trace, aucun
   `exception` dans les 176 entrées — la mort de `:preview` est vue
   par son seul effet (JobCancellationException + liaison impossible),
   jamais par sa cause (LMK ? killProcess du garde-fou C3 ? crash
   natif ART ?).

## Hypothèses (rangées par vraisemblance)

- **H1 — Bitmaps rendus BLANCS, transport sain** : les 16 « 2 bitmap(s) »
  sont des surfaces BLANCHES — la composition dans `:preview` ne dessine
  rien (composable introuvable dans le programme converti C2, thème
  non appliqué, ou capture de surface AVANT la première frame). Les
  journaux comptent les bitmaps LIVRÉS, jamais leur CONTENU : un rendu
  blanc est un « succès ». Cette hypothèse explique SEULE le « QU'une
  page blanche » pendant la fenêtre des 16 rendus, AVANT tout toucher.
- **H2 — La carte affiche un blanc après l'extinction du pompier** :
  le contrat « le dernier rendu reste » serait cassé dans l'hôte
  (`dernierValide` conservé mais la carte rendrait l'état machine
  éteint plutôt que le bitmap) — MAIS l'utilisateur rapporte le blanc
  DÈS L'OUVERTURE, pas seulement après le toucher ; H2 ne peut être
  qu'un SECOND défaut.
- **H3 — `:preview` tué par le système (LMK) ou par le garde-fou C3
  (killProcess des zombies)** : la mort au PREMIER toucher (le moment
  le plus lourd : liaison + composition interactive + pompe 30 i/s
  sur un appareil saturé) colle au profil mémoire. La `JobCancellation-
  Exception` est un SYMPTÔME (le job de rendu en vol est annulé par la
  mort du binder), pas la cause. La relance échouée (« liaison
  impossible ») reste inexpliquée par ce seul motif.
- **H4 — Course UI (FIT/clamp)** : le bitmap arrive sain mais l'affi-
  chage le clampe hors-champ (page blanche par décalage) — possible
  mais peu probable : la carte affiche le blanc PARTOUT, pas une zone.

## Plan d'investigation (prochain chantier « Previews »)

1. **Sonde de non-blancheur** dans le rendu `:preview` : variance
   d'échantillons de pixels par bitmap, JOURNALISÉE avec le « 2
   bitmap(s) » — distingue H1 (variance ~0) de H2/H4 (variance
   normale, la carte ment).
2. **Cause de mort de `:preview`** : journaliser l'ORIGINE de
   l'indisponibilité (DeathRecipient avec motif, verdict
   `ActivityManager` des morts de processus, distinction LMK vs
   `Process.killProcess` du garde-fou C3) et la CAUSE d'annulation du
   job (`job.getCancellationException()`), pas seulement son ombre.
3. **Échec de RELIAISON** : journaliser le pourquoi du « liaison
   :preview impossible » (délai dépassé ? classe manquante ? lmk
   récurrent ?) — c'est LUI qui condamne la session entière.
4. **Ne pas ré-armer la pompe sur session désactivée** : quand
   `IsolationPreviewCompose` a désarmé la session, le pompier doit
   refuser l'amorce (journal unique « pompier refusé : session
   désactivée ») au lieu de 3 frames échouées par toucher.
5. **Reproduction instrumentée** : appareil d'entrée de gamme, meminfo
   avant/après toucher, `logcat` du processus `:preview` (crash
   natif éventuel), chronométrage liaison → première frame.

## Pistes de défense (différées — à arbitrer après les faits)

- **Survie visuelle** : conserver le DERNIER bitmap dans la carte même
  après extinction/désactivation (H2), et afficher un MOTIF de
  indisponibilité explicite (« rendu interactif éteint — rouvrez le
  projet ») plutôt qu'un blanc muet.
- **Grâce adaptative du garde-fou** : budget de première frame
  indexé sur la classe d'appareil (RAM/cœurs) avant tout KILL C3.
- **Rendu statique hors interactif** : si H1 se confirme, auditer la
  conversion C2 (composable racine introuvable → surface vide sans
  erreur) et rejeter EXPLICITEMENT (« preview non interprétable »)
  au lieu d'un blanc.
- **Délestage au toucher** : ne lancer la pompe interactive QUE si
  l'appareil a de la tête mémoire (`ActivityManager.MemoryInfo`),
  sinon rester en statique (honnête).

## Références

- Journaux appareil : `upload/codeide-logs-2026-10-23-231457.zip`
  (extrait lisible : sessions du 23/10/2026, 176 entrées).
- `IsolationPreviewCompose` (watchdog/relance), `MoteurPreviewCompose`
  (pompier interactif, « le dernier rendu reste »), `HostApercuCompose`
  (`dernierValide`, contrat « jamais de carte vide »).
- PREVIEW.md §4.4 (chronologie C1→C4), PC4 (aucun repli local).
