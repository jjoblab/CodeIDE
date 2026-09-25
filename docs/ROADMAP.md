# Feuille de route

## Phase 1 — Fondations, configuration, création de projet (terminée — v0.19.0)

Chaque étape se termine par la procédure de livraison (section 9.2 du prompt
maître) et l'attente de validation. La version de l'étape N est `0.(N+1).0`.

| # | Étape | Version | État | Contenu |
|---|---|---|---|---|
| 0 | Environnement, squelette Gradle, outillage de livraison | 0.1.0 | **Terminé** | 18 modules compilables, convention plugins, `checkModuleDependencies`, qualité verte (detekt/spotless/lint/kover), scripts de version/livraison/vérification, ADR 0001-0007, application minimale testée |
| 1 | Fondations transverses | 0.2.0 | **Terminé** | `core:model` (AppResult, AppError, identifiants typés, StorageLocation — couverture 100 %), `core:domain` (DispatcherProvider, convention des use cases), `core:testing` (MainDispatcherRule, TestDispatcherProvider), `core:ui` (thème M3 complet en tokens, BaseFragment, composants d'état, insets, AppNavigator), `app` (CodeIdeApplication Hilt + StrictMode/LeakCanary, SplashScreen, NavHost, navigation Home ↔ Settings testée), ADR 0008 |
| 2 | Journalisation de l'application | 0.3.0 | **Terminé** | Section 5.7 : AppLogger, LogRedactor, sinks Logcat/fichier JSONL, rotation, écriture asynchrone, breadcrumbs, export zip, FileProvider |
| 3 | Gestion des plantages | 0.4.0 | **Terminé** | Section 5.8 : CrashHandler (1re ligne d'onCreate, chaînage, budget ≤ 2 s), rapports JSON atomiques (256 Ko, 20 max, témoin consulté), boucle de plantages (3/60 s), ApplicationExitInfo (ANR/natifs, dédoublonnés), CrashActivity processus `:crash` (LIVE/VIEW, copier/partager/enregistrer/vider le cache), dialogue rapport non consulté, menu debug ; ADR 0010 |
| 4 | Couche données | 0.5.0 | **Terminé** | Contrats du domaine (`FileSystem` 13 opérations typées, `ProjectRepository`, `SettingsRepository`, `ForbiddenFolders`, use cases du registre/des paramètres/`VerifyProjectAccess`), modèle (`Project`, `ProjectAccessState`, `AppSettings`, `ThemeMode`, `LogVerbosity`, `License`), `core:database` (Room v1, index unique `document_uri`, schéma exporté), `core:datastore` (SettingsDataStore tolérant, corruption → défauts), `core:storage` (SafFileSystem : requêtes groupées, contrôle du nom retourné, permissions), `core:data` (repositories + journalisation identifiants), fakes de test, branchement du niveau de journalisation persisté (ADR 0011-0012) |
| 5 | Assistant de premier lancement | 0.6.0 | **Terminé** | `feature:onboarding` : pager non swipable 5 pages (bienvenue, dossier SAF avec test d'écriture et dossiers refusés Android 11+, apparence à aperçu immédiat — thème/dynamique/langue ADR 0013, profil, terminé), état `SavedStateHandle` (rotation + mort de processus), routage `isSetupCompleted` sous splash, bandeau « dossier de travail » à l'accueil, ADR 0013 |
| 6 | Écran Paramètres | 0.7.0 | **Terminé** | `feature:settings` personnalisé M3 (ViewModel + DataStore) : apparence, langue, projets (dossier changer/effacer avec **libération de l'ancienne permission seulement si aucun projet n'en dépend**), à propos (version/build/licences), avancé (réinitialisation confirmée ADR 0014, relancer l'assistant) ; use cases du domaine Validate/Change/Clear/Reset + port ArborescencesSaf |
| 7 | Accueil : liste des projets | 0.8.0 | **Terminé** | `feature:home` : `ListAdapter`+DiffUtil (nom, description, emplacement lisible, date relative, épingle, pastille type), tri Récents/Nom (épingles en tête), **recherche avec délai 250 ms** (casse/accents), états chargement/vide/sans résultat/erreur/bandeau, **statuts d'accès** Introuvable/Permission perdue avec actions de résolution (Relocaliser/Retirer) et tirer-relâcher, actions par projet (ouvrir, renommer, épingler, retirer, supprimer du disque avec rappel du nom), FAB étendu « Nouveau projet » (placeholder wizard) + « Ouvrir un dossier existant » (ADR 0015-0016), adaptatif sw600dp 2 colonnes |
| 8 | Moteur de templates | 0.9.0 | **Terminé** | Manifestes déclaratifs (parseur + validation complète, `docs/TEMPLATES.md`), mini-langage d'expressions **parseur maison borné** (ADR 0018), substitution + 10 filtres (échappements hostile-proof), sécurité des chemins (garde après substitution, noms réservés Windows, doublons), plan figé dry-run = écriture (ADR 0017), `CreateProjectUseCase` avec rollback `NonCancellable`, `.codeide/project.json` sans donnée personnelle, `ProjectTemplateProvider` multibinding Hilt + port `TemplateAssetsSource` (impl. Android dans `app`), licences SPDX officielles dans `assets/licenses/`, 317 tests (moteur éprouvé sur fixture hostile) |
| 9 | Modèles de projet Kotlin et Java | 0.10.0 | **Terminé** | Modèles `kotlin-jvm` et `java` **complets et propres** (Greeter/Main/GreeterTest zéro avertissement, README dynamique, wrapper sommé, licences dans pom/publication), paramètres partagés (type/build/JDK 17-21/tests/wrapper/package/group/artifact/version), **`scripts/verify-templates.sh`** : 18 combinaisons générées sur disque (harnais JVM dédié `:tools:generateur`, ADR 0019) puis compilées, testées, exécutées et publiées avec les vrais Gradle/Maven/javac — tableau tout vert ; versions figées vérifiées sur les dépôts officiels (Kotlin 2.2.21, JUnit 5.14.4, Gradle 9.7.1 sommé) ; tests exhaustifs de génération dans `app` (192 combinaisons structurelles + options communes + golden + hostiles) |
| 10 | Wizard de création, partie 1 | 0.11.0 | **Terminé** | Cadre complet (ADR 0020 : hôte + indicateur + barre d'actions + `WizardViewModel` scopé à l'hôte, `SavedStateHandle` — rotation et mort du processus), rendu **dynamique** des paramètres depuis le moteur (ADR 0021 : registre de composants — tuiles segmentées, cartes radio, liste déroulante, interrupteurs, champs dérivés resynchronisables — et raisons de validation typées → ressources localisées), étapes 1 Modèle (grille de cartes + recherche prête sous 4), 2 Configuration (puces récapitulatives en direct), 3 Informations et emplacement (ADR 0022 : dossier éphémère par création, permission relâchée à l'abandon, vérifications asynchrones avec délai) ; Suivant gardé par validité, abandon confirmé |
| 11 | Wizard de création, partie 2 | 0.12.0 | **Terminé** | Étape 4 Fichiers (interrupteurs README/.gitignore/.editorconfig, licence pré-remplie auteur+année des Paramètres, langue du contenu FR/EN par boutons segmentés), étape 5 Récapitulatif (résumé par section avec bouton « Modifier » — retour arrière direct — et **arborescence prévue repliable** issue du dry-run, nombre de fichiers), **écran de création** hors numérotation (ADR 0023 : progression temps réel, annulation = rollback domaine `NonCancellable`, succès [ouvrir/marquer ouvert, accueil, créer un autre], échec typé + Réessayer + Copier les détails expurgés + nettoyage signalé), bouton principal « Créer le projet » gardé par revalidation globale, **mise en évidence du projet créé à l'accueil** (ADR 0024 : contour primaire + défilement, identifiant via la pile de retour) ; ADR 0023-0024 |
| 12 | Diagnostic | 0.13.0 | **Terminé** | `feature:diagnostics` (ADR 0025) : écran à deux onglets accessible depuis Paramètres › Avancé › Diagnostic — **Journaux** (fenêtre initiale des 500 dernières, plus anciennes révélées au défilement, filtres par niveau, recherche à délai insensible aux accents, suivi en direct, taille occupée, Partager/Enregistrer/_effacer_ confirmé, réglage Normal/Détaillé persisté **puis** appliqué au moteur) ; **Plantages** (liste vivante date/type/exception/pastille non consulté, ouverture en mode VIEW via AppNavigator, suppression unitaire et globale, export en archive) ; section Informations (version, appareil non identifiant, session) ; menu debug déplacé de l'accueil vers cet écran (build debug uniquement) |
| 13 | Fondations de l'espace de travail | 0.14.0 | **Terminé** | `EditorActivity` et ses trois zones **sans logique** (ADR 0026) : tiroir gauche (en-tête nom/chemin/« Fermer le projet », **permanent verrouillé ouvert sur sw600dp+** — façon IDE de bureau), zone centrale (toolbar + onglets vides + états vides/introuvable), panneau inférieur replié à trois onglets vides (Console · Problèmes · Journal, en-tête cliquable replié ↔ mi-hauteur) ; retour système ferme le tiroir sinon quitte ; dépendance `cel-ui` 3.37.0 via **JitPack** (coordonnées réelles `com.github.jjoblab.code-editor:cel-ui` vérifiées, règles ProGuard ajoutées) ; navigation `AppNavigator.openEditor` par-dessus la pile depuis « Ouvrir » (accueil) et « Ouvrir le projet » (succès du wizard), `lastOpenedAt` marqué avant ; `EditorViewModel` suit le projet au registre (identifiant par `SavedStateHandle`) |
| 14 | Explorateur de fichiers | 0.15.0 | **Terminé** | Arborescence **paresseuse** via `FileSystem` (énumération au dépliement, cache ViewModel — ADR 0027), tri dossiers puis fichiers puis alphabétique, icônes par extension (`core:ui` `IconesFichiers`, badges vectoriels maison : Kotlin/Java/Gradle/XML/Markdown/JSON/dossier/générique), gestion des erreurs d'accès (`ProjectAccessState`, bandeau de résolution dans le tiroir, nœud défaillant réessayable), bouton Actualiser de l'en-tête (revérifie l'accès puis recharge), barre de navigation basse du tiroir — Explorateur active, Recherche et Git visibles mais désactivées (« Bientôt disponible ») ; **correctifs d'appareil réel** : test d'écriture SAF (le témoin porte l'extension canonique du type, tolérance de complétion dans `SafFileSystem` — le dossier de travail redevient vérifiable) et finalisation de l'assistant (l'action « Terminer » n'était jamais émise, `isSetupCompleted` restait faux — `PageSuivante` finalise sur la dernière page, garde anti double-appui, échec signalé à l'écran) |
| 15 | Intégration de l'éditeur et onglets de fichiers | 0.16.0 | **Terminé** | Ouverture tiroir → onglet (`readText`, binaires → « Ouvrir avec » [ACTION_VIEW + FLAG_GRANT_READ], langage déduit de l'extension avec repli neutre), `EditorDocument`/`EditorSession` **au ViewModel** avec `setLanguage`, `TabLayout` dynamique (ajout, fermeture, menu contextuel [Fermer/Autres/Tout, Déplacer à gauche/droite, Copier le chemin], point de modification remplaçant la fermeture tant que sale), **un seul `EditorView` rebranché** sur la session active, thème clair/sombre, sauvegarde automatique (debounce 1,5 s, suspendue sous confirmation) + manuelle via `FileSystem.writeText` avec **verrou par fichier**, dialogue de fermeture avec modifications non enregistrées (agrégé), `session.dispose()` systématique (enveloppe suivie testée + LeakCanary sur appareil), onglets rouverts après mort du processus (`SavedStateHandle` chemins, contenu relu) — ADR 0028 |
| 16 | Panneau inférieur | 0.17.0 | **Terminé** | `BottomSheetBehavior` trois états (replié/mi-hauteur/étendu, retour système réduit l'étendu) avec en-tête (poignée, titre suivant l'onglet actif, badge de compte, agrandir/réduire), onglet **Journal applicatif** fonctionnel (fenêtre mémoire 200 entrées via `ObserveLogsUseCase`, mise à jour en direct, filtres par niveau persistés comme l'écran Diagnostic, lien « Ouvrir le journal complet » vers Diagnostic), onglets **Sortie** et **Problèmes** en stub explicite (point d'ancrage `session.setDiagnostics` documenté, non câblé), persistance de l'état et de l'onglet actif (rotation) ; ADR 0029 |
| 17 | Actions du tiroir et finitions de l'espace de travail | 0.18.0 | **Terminé** | Menu contextuel de l'explorateur [nouveau fichier/dossier dans le dossier visé, renommer, supprimer avec confirmation, actualiser] + création à la racine par bouton dédié [fichier créé ouvert en onglet] ; validation de nom **partagée** avec le wizard [validateur `file-name`, `EvaluerNomFichierUseCase`, raison typée localisée dans le dialogue] ; `FileSystem.rename` [14ᵉ opération, nouvelle URI retournée, fake déplace le sous-arbre] ; onglet qui **suit** le renommage [session/verrou/auto-sauvegarde migrés] et fermeture à la suppression ; reprise des onglets à la réouverture (`.codeide/local/workspace-state.json` non synchronisé, créé au besoin, lecture tolérante — gitignore des modèles déjà en place) ; accessibilité des onglets (contentDescription nom + état) et procédure TalkBack E32-E39 ; ADR 0030 |
| 18 | Audit final de Phase 1 | 0.19.0 | **Terminé** | Reconnaissance du type de projet à l'ouverture (`.codeide/project.json` lu par `ReconnaitreTypeProjetUseCase` tolérant, nom i18n du catalogue avec repli identifiant, distinction importé/non reconnu, ADR 0031) ; **correctif du plantage d'ouverture de l'espace** (menu inline du tiroir → `res/menu/menu_tiroir.xml` + `app:menu`, régression Robolectric gonflant le vrai layout, rapport 8b5b73f1) ; `assembleRelease` R8 **vert avec les règles ProGuard de cel-ui** (parcours complet vérifié sur APK minifié, E44) ; Dokka sur `core:model`/`core:domain` ; audit des dépendances (six entrées du catalogue non consommées retirées), TODO (aucun), code mort (detekt strict vert), données personnelles (LogRedactor actif, aucune fuite) ; `verify-templates.sh` vert ; plan détaillé de la Phase 2 ci-dessous ; archive finale vérifiée |

Étapes 13 à 18 : détail, critères d'acceptation et spécification complète de
l'espace de travail (trois zones, `EditorActivity`, bibliothèque `code-editor`)
définis par le **prompt compagnon** « EditorActivity, GitHub et bibliothèque
d'édition » (addendum du prompt maître, fusionné le 2026-09-23). La procédure
de livraison y ajoute la **publication GitHub** à chaque étape restante
(`git push origin main --follow-tags` vers `jjoblab/CodeIDE`), en plus de
l'archive autonome.

## Hors périmètre de la Phase 1

Autres modèles de projet (Python, Web, C++, Android…), options de
bibliothèques dans les modèles, frameworks (Spring, Ktor…), analyse statique
et CI dans les projets générés, projets générés multi-modules, Docker,
terminal intégré, tooling (compilation, exécution, LSP, formatage), système
de plugins, services d'arrière-plan, autocomplétion et intelligence de code
(l'édition et la coloration arrivent en Phase 1 via la bibliothèque
`code-editor` — prompt compagnon), intégration Git, synchronisation cloud,
réseau (`INTERNET`) et envoi automatique des rapports (Crashlytics, Sentry…),
notifications.

**Points d'ancrage prévus** pour ne pas rendre tout cela impossible :
`ProjectTemplateProvider` (multibinding) et manifestes déclaratifs,
`WizardStep` configurable, `FileSystem` abstrait, `EditorActivity` séparée,
modules `feature:*` isolés, `.codeide/project.json`, `AppLogger` injectable.

## Phase 2 — Tooling, terminal, intelligence de code (plan détaillé — sans implémentation)

Rédigé à l'étape 18 (prompt compagnon, section 6) : ordre, contenu et
critères d'acceptation de chaque étape. La discipline de la Phase 1
s'applique telle quelle — une étape à la fois, livraison validée (« GO
étape N+1 »), SemVer `0.N.0`, ADR par décision structurelle, vérification
complète verte. Chaque étape recevra au besoin un **prompt compagnon**
dédié (le terminal a déjà le sien : « Terminal-1 »).

**Ordre révisé le 2026-09-23 à la demande de l'utilisateur** : la Phase 2 ouvre par le **terminal intégré** (T1-T7). **Nouvel ordre le 2026-09-24 à la demande de l'utilisateur** : le prompt Tooling démarre après T6 — T7 (audit finitions) est absorbé par l'audit G6, dont les points ouverts (ADR targetSdk, revue mémoire) exigent l'appareil. Les anciennes étapes génériques 26-29 (diagnostics, exécution, LSP, formatage) sont couvertes par G5 (diagnostics/Sortie) et G2-G4 (exécution) ; LSP et formatage garderont leurs prompts compagnons dédiés après le tooling Gradle.

**Ordre initial révisé le 2026-09-23** : la Phase 2
ouvre par le **terminal intégré** (prompt Terminal-1, étapes 19 à 25 =
T1 à T7) — ce qui aligne d'ailleurs le plan sur la consigne du prompt
Terminal-1 lui-même (« à exécuter avant le prompt Tooling », dont les
étapes de diagnostic/exécution dépendent du JDK/Gradle/SDK installés
ici). Les étapes de tooling initialement en tête reculent d'autant.

| # | Étape | Version | État | Contenu prévu |
|---|---|---|---|---|
| 19 | Terminal T1 — `core:bootstrap` : localisation et environnement | 0.20.0 | **Terminé** | Sections 3.1/3.2 du prompt Terminal-1 : disposition type Termux (`filesDir/usr`), scan multi-emplacements avec marqueurs de validité (JDK `usr/lib/jvm/…` du dépôt APT, distribution Gradle complète, SDK Android, `aapt2`, shell), cache du wrapper Gradle, ports `ToolchainLocator`/`ProcessEnvironmentProvider` du domaine, environnement de sous-processus (retrait `CLASSPATH`/`LD_PRELOAD`, `GRADLE_USER_HOME` explicite — bug `getpwuid`) ; heuristiques pures testées en JVM (bugs historiques rejoués) ; ADR 0032 |
| 20 | Terminal T2 — `NativeProcessLauncher` et `BootstrapInstaller` | 0.21.0 | **Terminé** | Sections 3.3/3.4/3.5 : lanceur de sous-processus non interactifs (`ProcessBuilder`, flux `Flow`), installateur du bootstrap en coroutines (téléchargement avec empreinte SHA-256, extraction + `SYMLINKS.txt` + permissions, second stage, `sources.list` avec `[trusted=yes]` corrigé, `apt update` + paquets un à un), `Aapt2Deployeur` ; 49 tests avec faux serveur HTTP/fausse archive réelle/annulation ; ADR 0033 |
| 21 | Terminal T3 — Écran d'installation + onboarding | 0.22.0 | **Terminé** | Écran d'installation autonome (feature:install, état partagé du port) **et** étape « Terminal » insérée dans l'assistant (jamais bloquante, « Plus tard », revérification au retour), bandeau d'invitation à l'accueil piloté par l'état d'installation ; INTERNET + ADR 0034, branchement app de core:bootstrap (AssetsBootstrapSource) ; navigation openBootstrapInstall |
| 22 | Terminal T4 — `core:terminal-runtime` | 0.23.0 | **Terminé** | Registre global `RegistreSessionsTermux` (singleton Hilt) servant les deux ports (`TerminalSessionRepository` du domaine + `TerminalRuntime.sessionFor` hors domaine), sessions réelles via constructeur Termux (environnement canonique + `TERM`), traduction throttlée vers `TerminalSessionSummary` (250 ms / 160 caractères), `TerminalService` foreground `specialUse` à décision pure testée, coquilles scriptées pour les tests (aucun pty réel) ; ADR 0035, `FakeTerminalSessionRepository` dans `core:testing` |
| 23 | Terminal T5 — `feature:terminal` : écran plein écran | 0.24.0 | **Terminé** | Section 5 du prompt : toolbar, onglets de sessions (pastille d'état, fermeture, « + », appui long renommer/dupliquer/fermer avec heuristique « au prompt »), `TerminalView` **unique** rebranché, clavier étendu **interne** (termux-shared refusé — Ctrl/Alt bascules via `readControlKey`/`readAltKey`, mécanisme Termux), thèmes clair/sombre (couleurs de l'émulateur, indices 256/257/258), réglage dédié de police (`TaillePoliceTerminal` dans les Paramètres), `AppNavigator.openTerminal` ; ADR 0036, 11 tests ViewModel |
| 24 | Terminal T6 — Intégration accueil et tiroir | 0.25.0 | **Terminé** | Sections 7 et 8 : action « Terminal » dans la toolbar de l'accueil (écran d'installation si bootstrap absent — jamais un terminal non fonctionnel), carte d'aperçu dans le tiroir (quatrième destination active : compteur de sessions actives, libellé + dernière sortie + pastille de la session active, mise à jour en direct, état vide « Nouvelle session dans ce projet » qui crée puis ouvre) ; **aucune dépendance Termux ajoutée à `feature:editor`** ; pont SAF → FUSE `ResoudreRepertoireProjet` (core:domain, durci anti-traversée, garde répertoire fantôme — réutilisable par le tooling) ; correctif plantage `InstallFragment` (rapport 30e81ee0, `@AndroidEntryPoint` manquant + test de régression) ; CI GitHub Actions (ADR 0037/0038, procédure locale sans `clean`) |
| 25 | Tooling G1 — `tooling:protocol` + `tooling:testing` | 0.26.0 | **Terminé** | Section 3/9.1 du prompt Tooling : framing (garde DoS 16 Mo avant allocation, troncature typée, EOF propre distinguée), catalogue des 24 messages (ErrorCode typé), `ProtocolJson` compatibilité ascendante, **24 fichiers dorés figeant le format câble**, constantes ; 4 fixtures Gradle réelles copiées en temporaire (jamais construites en place) ; 21 tests bloquants au vert avant server/client ; règles de dépendance tooling gelées ; versions vérifiées dans `docs/TOOLING.md` (Tooling API 9.7.1, daemon Java 17, shadow 9.6.1) ; ADR 0039 |
| 26 | Tooling G2 — `tooling:server` (JVM) | 0.27.0 | **Terminé** | Sections 4 et 7.2 : orchestrateur (UDS JDK 16+, `MessageDispatcher` dispatcher borné, pont `suspendCancellableCoroutine` → Tooling API avec annulation propagée, `GradleConnectorPool`, `HeapMonitor`), tests d'intégration réels contre les fixtures en JVM pur, fat jar `com.gradleup.shadow`, dépôt `repo.gradle.org` |
| 27 | Tooling G3 — `tooling:client` | 0.28.0 | **Terminé** | Sections 5.1-5.3 : `GradleSocketServer` (écoute avant lancement, namespace FICHIER — ADR 0041, secret de handshake §4.4 validé AVANT tout handler), façade `GradleToolingRepository` (core:domain, zéro type tooling — règle §2.2), diffusion **non conflatante** (canaux bornés 4096 par build à envoi suspendant, tampon pré-abonnement + rejouable après fin), Resilient Sync ; écho d'identifiant des réponses corrigé côté serveur (corrélation §3.2) ; `AppError.Tooling` typé jusqu'à l'UI ; 19 tests dont non-conflation 12 000 lignes (§7.3) |
| 28 | Tooling G4 — `tooling:daemon` | 0.29.0 | **Terminé** | Section 5.4 : `DaemonManager` sur `NativeProcessLauncher` (jamais redéfini), `JarDeployer` à marqueur de version SHA-256, health check ping/pong (5 s / 15 s) avec redémarrage borné (5 tentatives, repli exponentiel), stderr du process → journal `gradle-server`, JDK absent = état sans boucle de relance, secret frais par tentative, démarrage au processus principal + re-déclenchement à l'installation du bootstrap, **premier bout-en-bout réel (§7.4)** : le daemon lance le VRAI orchestrateur en sous-processus `java` sur vrai socket Unix et exécute un VRAI build Gradle (13 tests) ; ADR 0042 |
| 29 | Tooling G5 — `GradleService` + intégration éditeur | 0.30.0 | **Terminé** | Section 6 : onglet **Sortie** fonctionnel (lignes en direct, fenêtre bornée 2 000 lignes — la sortie complète reste dans le canal rejouable du client, auto-défilement qui cesse un build fini, statut/durée en en-tête, bouton Arrêter en vol), onglet **Problèmes** (diagnostics groupés par fichier repliés, pastilles sévérité, saut à la ligne + curseur), **diagnostics inline** `session.setDiagnostics` (point d'ancrage ADR 0029, appariement par suffixe de chemin relatif, sévérités cel-ui, offsets bornés), producteur serveur `ParseurDiagnostics` (positions javac/kotlinc extraites de stderr ligne à ligne, événements `Diagnostic` du protocole G1), use cases domaine (Synchroniser/Exécuter/Annuler/Lister — délégations pures au port), actions Synchroniser/Exécuter + sélecteur de tâches dans la toolbar, journal unifié tag `gradle-server` (couvert par le daemon G4) ; 23 tests + intégration serveur étendue (16) ; ADR 0043 |
| 30 | Tooling G6 — Robustesse et audit | 0.31.0 | **Terminé** | Section 7.5 : **chaos réel** (`ChaosToolingTest` sur le harnais du bout-en-bout : process `kill -9` en plein build → builds EN COURS conclus `ECHOUE` « connexion perdue » et canaux fermés — correctif `GradleApiImpl.rompreBuildsEnCours()`, le trou a été trouvé PAR le chaos ; socket perdue côté app → process sort SEUL code 0, aucun orphelin ; version incompatible et JDK introuvable déjà prouvés), délais de garde inventoriés partout (client sync 5 min/tâches 30 s/connexion 10 s, serveur build 30 min/sync 5 min/tâches 30 s/dépendances 30 s/modèle 5 min, health check 5 s/15 s), `docs/TOOLING.md` **final** (architecture, délais, chaos, journalisation, CI), audit (aucun TODO, detekt strict vert, `ServerVersion` inchangée 0.30.0 — pas de redélivraison orchestrateur) ; points T7 appareil différés — **targetSdk tranché depuis : v0.31.1 (retour d'appareil réel, ADR 0045, targetSdk 28)**, revue mémoire LeakCanary toujours ouverte ; ADR 0044 |
| 31 | Explorateur de fichiers v2 — tiroir à fragments | 0.32.0 | **Terminé** | Reproduction **à l'identique** de la preview HTML validée (`docs/preview/explorateur-v2.html`, spécification `docs/EXPLORATEUR_V2.md`) : tiroir en **fragments** (Explorateur fonctionnel + aperçus Recherche/Git + carte Terminal migrée, entête propre par fragment, plus d'entête commun — ADR 0052), poignée ⋮ de redimensionnement (bornes 45-98 %, aimants 55/69/85/98 %, pastille de taille, largeur mémorisée par session), arbre treeview (guides fins dessinés par `VueGuides`, chevrons de dépliage, points d'état à 4 états par `VuePointEtat` pilotés par les onglets), vraies icônes par type (13 nouvelles marques + 4 d'action), bascule **Projet/Privé** exclusive (qualifier Hilt `@FileSystemPrive`, adaptateur `prive:///` de `filesDir/cacheDir/codeCacheDir/databases/shared_prefs`), popover maison ancré au doigt avec flèche/retournement/chemin contextuel (jamais de menu système), mutations **par nœud** (éditeur inline, presse-papiers d'arbre avec suffixe « (copie N) », annulation de suppression avec restauration et réouverture d'onglets, déplacement par chemin à validation locale), snackbar maison annulable 4 600 ms, port `FileSystem` étendu `readBytes`/`writeBytes` ; 91 tests (use cases purs + ViewModel + layouts Robolectric) ; ADR 0052 |
| 32 | Système de plugins | 0.33.0 | — | Contrat de plugin (API `core:domain` + UI d'extension), découverte embarquée (assets signés, pas de réseau), sandbox des permissions, activation/désactivation par projet ; réutiliser le multibinding `@IntoSet` éprouvé par les modèles |
| 33 | Services d'arrière-plan | 0.34.0 | — | Compilation/exécution hors écran avec `foregroundServiceType` déclarée et notification honnête, observation des modifications du dossier (SAF `takePersistableUriPermission` + re-scan à l'activation), reprise après mort du processus ; jamais de tâche en fond sans notification visible |
| 34 | Autres langages et modèles | 0.35.0 | — | Modèles Python et Web (manifestes déclaratifs — le harnais `:tools:generateur` et `verify-templates.sh` s'étendent tels quels), coloration/lint par extension via les ancres `IconesFichiers`/langages de la bibliothèque ; Android natif et C++ évalués ensuite |

**Ordre révisé le 2026-09-25 (soir) à la demande de l'utilisateur** :
la refonte de l'explorateur de fichiers devient l'étape 31 — preview HTML
interactive validée le même jour (treeview sans crochets, chevrons,
bascule Projet/Privé **exclusive**, popover ancré au doigt, mutations par
nœud) et spécification de reproduction à l'identique
`docs/EXPLORATEUR_V2.md` ; plugins, services d'arrière-plan et autres
langages reculent d'un rang (32 → 0.33.0, 33 → 0.34.0,
34 → 0.35.0). La numérotation des correctifs déjà publiés (jusqu'à
v0.31.7) reste inchangée.

**Correctif v0.31.1 (2026-09-25, après retour d'appareil réel — rapport
7842f130, moto g06 / Android 15)** : plantage de l'écran Terminal (ordre
d'initialisation Kotlin dans `ClavierEtenduView`, test de régression de
layout), « erreur inattendue » du bootstrap après extraction (W^X :
`targetSdk` 28, ADR 0045 — le point T7 différé est tranché), marqueur
d'installation terminée honnête (`bootstrapInstalle` exige le pipeline
allé au bout), erreurs de création de projet réelles (plus de fausse
collision « un dossier porte déjà ce nom ») et directive utilisateur :
vérification standard = légère + `assembleDebug` (AGENTS.md,
CONVENTIONS.md). La numérotation des étapes suit son cours (31 =
plugins, v0.32.0).

**Correctif v0.31.2 (2026-09-25, après retour d'appareil réel — rapport
511e1c7f, moto g06 / Android 15)** : plantage de l'écran Terminal à la
première session (`TerminalSession` de Termux exige le thread principal —
son `MainThreadHandler` est un `Handler` sans Looper ; création basculée
sur `dispatchers.main`, `kotlinx-coroutines-android` dans
`core:terminal-runtime`, ADR 0046), écran d'installation refondu —
checklist des neuf étapes, compteurs et **journal en direct de la sortie
des sous-processus** (stdout/stderr du second stage et d'apt, conservés à
l'échec avec détails techniques dépliables — « la configuration des
paquets a échoué » ne sera plus jamais muette) — et page
« Notifications et stockage » dans l'assistant (demande
`POST_NOTIFICATIONS` + explication : aucune permission de stockage
nécessaire, SAF et stockage privé suffisent, ADR 0046).

**Correctif v0.31.3 (2026-09-25, après retour d'appareil réel — `apt
update` code 100, `mkstemp $PREFIX/tmp ENOENT`)** : le répertoire `tmp`
du préfixe est désormais garanti en deux couches (créé à l'extraction —
l'archive publiée par `codeide-packages` n'embarque pas l'entrée `tmp/`
contrairement au bootstrap officiel Termux — et recréé à chaque
environnement de sous-processus, couvrant `rm -rf $PREFIX/tmp` documenté
par la FAQ Termux) ; la cause n'était PAS une permission (errno 2 =
ENOENT, pas EACCES — le stockage privé de l'application ne demande
rien) ; stockage partagé OPT-IN pour le terminal (trio READ/WRITE +
`MANAGE_EXTERNAL_STORAGE`, section facultative de la page
Notifications, jamais exigé — modèle Termux, ADR 0047) ; CI réparée
(`ExpiredTargetSdkVersion`, l'issue ERREUR distincte du warning
`ExpiringTargetSdkVersion` désactivé en v0.31.1, ADR 0047).

**Correctif v0.31.4 (2026-09-25, après retour d'appareil réel — rapport
`f2699ac5` : plantage au retour depuis le terminal, écran
d'installation qui « ne se met pas à jour correctement », demande
« apt update obligatoire, outils optionnels »)** : navigateur robuste
hors graphe (`@ActivityScoped` injecté par une activité pleine écran
sans conteneur plantait à chaque retour — `goBack()` referme l'écran,
les navigations vers le graphe relaient `MainActivity` devenue
`singleTop` via un routage `EXTRA_ECRAN_CIBLE` sans recréation, ADR
0048) ; écran d'installation réparé et refondu (journal **combiné** à
l'état dans un seul flux — il s'effaçait à chaque étape ; boutons
terminaux enfin rendus ; deux sections : base / outils, ADR 0048) ;
**première configuration resserrée sur l'environnement de base**
(shell, apt, dépôt à jour — `apt update` obligatoire), les paquets
d'outils (`openjdk-17`, `git`) devenant **optionnels et différés**
(`installerOutils()` à la demande, échec des outils distinct de celui
de la base, garde JDK dans l'éditeur avant sync/build avec message
actionnable, ADR 0048).

**Correctif v0.31.5 (2026-09-25, après retour d'appareil réel —
terminal « pas à jour immédiatement » / pinch-zoom inerte / onglets
inopérants, création de projet « un dossier porte déjà ce nom »,
CI lint rouge sur `feature:install`)** : terminal vivant (signal de
repeint **immédiat** `TerminalRuntime.observeSorties()` collecté par
l'activité — dans l'architecture Termux c'est le client de session de
l'ACTIVITÉ qui repeint la vue, personne ne le faisait ; zoom pincé
appliqué par le client selon le vrai contrat du bytecode v0.118.3 —
bornes 10–30 dp, facteur consommé ; onglets resynchronisés **par
diff** — la reconstruction complète toutes les 250 ms détruisait les
vues sous le doigt, les taps n'atterrissaient jamais ; une session
créée devient toujours active, ADR 0049) ; création de projet honnête
jusqu'au bout (**pré-vol** à l'appui sur « Créer » — la cible est
re-vérifiée avant toute écriture, l'état « Valide » de l'étape
Informations pouvant être périmé ; erreur **réelle** de l'insertion
en base relayée au lieu d'un `Io` générique ; normalisation
fournisseur des espaces/points finaux tolérée par `SafFileSystem` ;
détails techniques **visibles** à l'écran d'échec et bouton
« Changer de nom ou d'emplacement » — sortie du piège « Réessayer »
en boucle, ADR 0049) ; lint réparé à la source (`NestedScrollView`
du journal, `<plurals>` de l'extraction, indice « n/total » du
paquet, `toUri()` dans l'onboarding — la vérification locale étend
son `lintDebug` à TOUS les modules touchés, ADR 0049).

**Correctif v0.31.6 (2026-09-25, après retour d'appareil réel 4a4526aa —
création de projet « le dossier créé a été supprimé, .gitattributes »
persistant malgré le pré-vol, plantage `NullPointerException :
bouton_fermer_session` de l'écran Terminal à la première session)** :
le point INITIAL d'un fichier caché n'est pas une extension — ni pour
le fournisseur SAF (qui complète « .gitattributes » + `text/plain` en
« .gitattributes.txt »), ni pour nos contrôles (`contains('.')` voyait
une extension) : le premier fichier du plan des modèles JVM déclenchait
un « renommage hostile » de pure invention → fichier fraîchement créé
supprimé, `AlreadyExists` (« un dossier porte déjà ce nom », AUCUN nom
ne pouvait marcher), rollback complet sous les yeux de l'utilisateur.
Règle partagée `mimeFichierTexte`/`sansExtensionReelle` (`core:domain`)
: tout nom sans extension réelle part en type privé `text/x-codeide`
(sans complétion, nom préservé exactement), le filet
`estAchevementExtension` tolère désormais la complétion d'un caché,
l'éditeur suit la même règle (ADR 0050) ; terminal : la décision
« border la vue existante » du diff d'onglets exclut explicitement le
« + » (sa vue est un `ImageView` sans `bouton_fermer_session` — quand
la liste grandit, la position visée est occupée par le « + », cas
minimal : zéro session → première création → plantage 60 ms plus
tard) via le helper testable `vueOngletSessionBordable`, régressions
verrouillées sur le vrai `TabLayout` (ADR 0050).

**Correctif v0.31.7 (2026-09-25, après retour d'appareil réel — suite du
rapport 4a4526aa : `Storage(AlreadyExists, details=README.md)` après
installation de v0.31.6, et « j'appuie sur le tab layout l'onglet pour
changer de session, rien ne se passe »)** : preuve que le correctif
v0.31.6 a fonctionné (l'échec a PROGRESSÉ au fichier suivant — le
premier caché `.gitattributes` passe désormais) et que la complétion
d'extension SAF frappe AUSSI les noms AVEC extension : la table
système (`MimeTypeMap`, variable par version et par OEM) ne connaît
pas `md`, `kts`, `kt`, `properties`, `pro`… — `README.md` +
`text/plain` était créé `README.md.txt`, lu comme renommage hostile →
nettoyage + `AlreadyExists` + rollback. `mimeFichierTexte` répond
désormais le type privé `text/x-codeide` pour TOUT fichier texte (le
nom ne décide plus : un type sans extension canonique n'est jamais
complété) ; la tolérance de complétion reste bornée aux noms sans
extension réelle (jamais de corruption silencieuse du plan —
`build.gradle.kts.txt` casserait Gradle) ; l'éditeur suit
automatiquement (ADR 0051). Terminal : la racine d'onglet portait un
écouteur d'appui long seul — une vue `longClickable` CONSOMME les taps
simples (le `TabView` parent ne voyait jamais le geste : aucune
sélection, « rien ne se passe ») : elle prend son propre écouteur de
clic via le helper testable `brancherInteractionsOnglet` (même
architecture que Termux), appui long et fermeture inchangés (ADR 0051).

### Principes et contraintes reconduits

- **Aucun `File` direct** : tout passe par le port `FileSystem` (SAF) ; les
  artéfacts de compilation vivent sous `.codeide/` (non synchronisé).
- **Pas de réseau en Phase 2 sans décision explicite** : la permission
  `INTERNET` reste absente tant qu'un cas d'usage ne la justifie pas
  publiquement (ADR dédiée le cas échéant) ; plugins et serveurs de langage
  sont embarqués.
- **Les stubs deviennent des contrats** : « Sortie », « Problèmes » et
  `session.setDiagnostics` sont les points d'ancrage des étapes 19-20 ;
  les trois destinations du tiroir (Explorateur/Recherche/Git) fixent
  l'objectif de couverture de la navigation basse — « Git » reste le plus
  lointain (estimation à refaire à l'étape 22).
- **Robustesse d'abord** : compilation/exécution annulables, mémoire bornée
  (les limites `LoggingLimits`/`CrashLimits` inspirent des bornes tooling),
  échecs typés `AppResult`, aucune donnée personnelle dans les journaux.
- La reconnaissance du type (étape 18, ADR 0031) identifie déjà le langage
  déclaré d'un projet importé — le tooling s'y appuie au lieu de le deviner.
