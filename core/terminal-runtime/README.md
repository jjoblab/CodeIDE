# core:terminal-runtime

Sessions shell interactives réelles du terminal intégré — prompt
compagnon « Terminal intégré et bootstrap natif » (Terminal-1),
sections 1.5, 2.2 et 4. ADR 0035.

## Périmètre livré (étape T4, v0.23.0)

- **`TerminalSessionSummary` / `TerminalSessionRepository`** (ports
  `core:domain`, `TerminalSession.kt`) : métadonnées **sans type
  Termux** — consommables par `feature:editor` (carte d'aperçu du
  tiroir, T6) sans aucune dépendance `com.termux:*`. Liste observable,
  session active observable, création (répertoire de travail + libellé
  optionnel), renommage, fermeture **réelle** (le shell est terminé,
  pas seulement masqué).
- **`RegistreSessionsTermux`** (singleton Hilt lié à l'application) :
  implémente **les deux ports** à la fois — le repository du domaine
  et [`TerminalRuntime`][runtime] (section 4.4 du prompt :
  `sessionFor(id)` expose la vraie `TerminalSession` Termux, type
  réservé à `feature:terminal` qui branchera le `TerminalView`).
  Traduction état réel → `TerminalSessionSummary` avec **throttle** de
  la sortie (fenêtre 250 ms, aperçu borné à 160 caractères replatés —
  la carte d'aperçu n'est pas réveillée à chaque caractère,
  section 4.3). Terminaison **naturelle** (`exit`) : l'entrée reste
  visible `isAlive = false` jusqu'à fermeture explicite.
- **`CoquillesSessions.kt`** : indirection interne
  (`CoquilleSession`/`EcouteurCoquille`/`FabriqueCoquilles`) entre le
  registre et les objets Termux — les tests servent des coquilles
  scriptées, aucune exécution réelle de pty dans la suite
  (section 10 du prompt). L'adaptateur `TerminalSessionClient`
  traduit les callbacks Termux ; les sollicitations de rendu
  (presse-papiers, cloche, couleurs) restent muettes ici — le rendu
  vit dans `feature:terminal`.
- **`TerminalService`** (foreground, `START_STICKY`) : unique
  responsabilité de garder les sessions vivantes en arrière-plan avec
  une notification **honnête** (« N session(s) de terminal active(s) »),
  jamais la logique de rendu. La décision notification/arrêt
  (`DecisionServiceTerminal`, logique pure testée) : notification tant
  qu'au moins une session vit, arrêt de soi-même sinon. Type
  `specialUse` avec sous-type documenté (aucun type normalisé ne
  décrit « garder des shells interactifs en vie ») — voir l'ADR 0035.
- **Création d'une session** (section 4.1) : constructeur Termux
  `TerminalSession(shell, cwd, args, env, transcriptRows, client)` —
  shell de `ToolchainLocator.defaultShell()`, environnement
  `"KEY=VALUE"` exactement issu de
  `ProcessEnvironmentProvider.baseEnvironment()` (source unique de
  vérité, jamais dupliquée) complété de `TERM=xterm-256color`,
  répertoire de travail initial, identifiant stable (UUID) et
  libellé automatique « Session N ».
- **Manifeste du module** : permissions
  `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_SPECIAL_USE`/
  `POST_NOTIFICATIONS` et déclaration du service — fusionnées dans
  l'application finale via la dépendance `:core:terminal-runtime`.

## Dépendances (section 2.3 du prompt)

`core:model`, `core:domain`, `core:bootstrap` (localisateur +
environnement canoniques) et `com.termux:terminal-emulator`
(Apache-2.0, `THIRD_PARTY_NOTICES.md`). **Jamais** `terminal-view`
(le rendu vit dans `feature:terminal`).

## Tests (critère d'acceptation T4)

- `RegistreSessionsTermuxTest` (13 tests) : traduction état réel →
  métadonnées (label automatique numéroté, répertoire, `createdAt`),
  environnement/shell canoniques transmis à la coquille, session
  active (première créée puis suivi de `setActiveSession`), renommage,
  fermeture réelle + rebascule sur la dernière restante, terminaison
  naturelle visible morte, sorties bornées/replatées/**throttlées**
  (rafale de 50 lignes → une publication), `sessionFor` (coquille
  scriptée → `null`), numérotation continue après libellé manuel,
  redémarrage du service après fermeture complète.
- `TerminalServiceTest` (3 tests, service **réel** sous Robolectric) :
  arrêt de soi-même quand aucune session ne vit ; notification
  foreground **persistante** tant qu'une session vit, disparition et
  arrêt après la fermeture de la dernière ; le démarreur réel demande
  bien le service foreground du terminal (champs `@Inject` posés par
  réflexion — `buildService` attache sans `onCreate`, le onCreate généré
  par Hilt exigerait le harnais complet de l'app).
- `DecisionServiceTerminalTest` (3 tests) : arrêt si aucune vivante,
  notification du nombre sinon.

La couverture (seuil kover ≥ 80 %) exclut, filtres documentés dans le
build du module : la colle Termux/JNI (`CoquilleTermux`,
`ClientTermux`, `FabriqueCoquillesTermux` — exécution impossible en
JVM, c'est la raison d'être de l'indirection `CoquilleSession`) et le
code **généré** par Hilt/Dagger (fabriques, injecteurs, composants —
câblage validé par `hiltJavaCompileDebug` à l'échelle de l'app).

[runtime]: src/main/kotlin/jo/codeide/core/terminalruntime/RegistreSessionsTermux.kt
