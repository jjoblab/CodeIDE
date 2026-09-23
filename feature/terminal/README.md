# feature:terminal

Écran plein écran du terminal intégré — prompt compagnon « Terminal
intégré et bootstrap natif » (Terminal-1), section 5. ADR 0036.

## Périmètre livré (étape T5, v0.24.0)

- **`TerminalActivity`** : disposition de la section 5.1 — MaterialToolbar
  (« Terminal » + bouton nouvelle session), onglets de sessions
  défilants (`TabLayout`, vues personnalisées : pastille d'état
  vivante/terminée, libellé court, bouton de fermeture, onglet « + »
  final), **un seul `TerminalView`** rebranché sur la session active
  (`attachSession` — même principe que l'éditeur : un seul rendu, la
  mémoire en dépend), état vide centré (« Aucune session » + création),
  clavier étendu en bas (remonte au-dessus du clavier virtuel via les
  insets IME).
- **`TerminalViewModel`** : ne touche jamais aux objets Termux — la
  liste vient du `TerminalSessionRepository` du domaine (métadonnées),
  le rebranchement est le travail de l'activité via `TerminalRuntime`.
  Répertoire suggéré par l'intent (extra `ClesTerminal.EXTRA_REPERTOIRE`,
  via `SavedStateHandle` — survit à la rotation), sinon le `HOME` de
  l'environnement canonique. Fermeture : heuristique « shell au prompt »
  (fin de l'aperçu se terminant par `$`/`#`/`%`/`>`) → effet de
  confirmation si occupée, fermeture directe sinon — le shell est
  **réellement** terminé, jamais « juste masqué ». Duplication : même
  répertoire de travail. Renommage : libellé nettoyé.
- **`ClavierEtenduView`** : rangée déclarative interne (termux-shared
  refusé pour licence — ADR 0035/0036) : Tab, Ctrl, Alt, Échap, flèches.
  Touches directes (séquences `\t`, `ESC`, `ESC[D`…) écrites dans la
  session ; Ctrl/Alt **bascules persistantes** lues par le client de la
  vue (`readControlKey`/`readAltKey`) — le mécanisme officiel de Termux,
  appliqué par `TerminalView` à l'entrée du clavier.
- **`ClientVueTerminal`** : `TerminalViewClient` — toucher = focus +
  clavier virtuel ; retour système ferme l'écran (jamais mappé sur
  Échap, les sessions survivent via le service foreground) ; journaux
  internes muets (même choix que `ClientTermux`).
- **Thèmes clair/sombre** suivant l'app : les couleurs courantes vivent
  dans l'émulateur (`mColors.mCurrentColors`, indices 256/257/258 =
  premier plan/arrière-plan/curseur — disposition jackpal à 259
  entrées), réécrites depuis les ressources `values`/`values-night`.
- **Police à chasse fixe configurable** : réglage dédié minimal
  (`TaillePoliceTerminal` PETITE/MOYENNE/GRANDE dans les Paramètres →
  section Apparence), appliqué en dp → pixels via `setTextSize`.
- **Navigation** : `AppNavigator.openTerminal(suggestedWorkingDirectory)`
  (interface `core:ui`, implémentation `app`, intent vers l'activité) —
  les points d'entrée UI arrivent en T6 (accueil, tiroir de l'espace).

## Dépendances (section 2.3 du prompt)

`core:ui`, `core:domain`, `core:model`, `core:terminal-runtime`
(`TerminalRuntime` seulement), `com.termux:terminal-view` +
`com.termux:terminal-emulator` (Apache-2.0 — le POM JitPack de
terminal-view ne publie pas sa dépendance d'émulateur, portée projet :
les deux sont déclarés).

## Tests (critère d'acceptation T5)

- `TerminalViewModelTest` (11 tests, fakes du domaine — aucune session
  Termux réelle) : répertoire suggéré par l'entrée, repli `HOME`
  canonique, sélection via le registre, fermeture directe au prompt,
  confirmation exigée si occupée puis fermeture après accord, session
  terminée fermée sans confirmation, renommage nettoyé/ignoré en vide,
  duplication au même répertoire, taille de police exposée par l'état,
  heuristique prompt/sortie.

Les critères d'exécution réelle (commandes de base, sessions
simultanées, rotation sans perte) relèvent des tests manuels E57-E60
sur appareil (TESTS_MANUELS).
