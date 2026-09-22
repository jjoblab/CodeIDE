# Feuille de route

## Phase 1 — Fondations, configuration, création de projet (en cours)

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
| 6 | Écran Paramètres | 0.7.0 | À faire | Material 3 personnalisé, piloté DataStore : apparence, langue, projets, à propos, avancé |
| 7 | Accueil : liste des projets | 0.8.0 | À faire | ListAdapter + DiffUtil, tri, recherche, états, statut d'accès, actions, FAB, adaptatif |
| 8 | Moteur de templates | 0.9.0 | À faire | Manifestes déclaratifs, mini-langage d'expressions, substitution + filtres d'échappement, sécurité des chemins, ProjectTemplateProvider (multibinding), dry-run |
| 9 | Modèles de projet Kotlin et Java | 0.10.0 | À faire | `kotlin-jvm` et `java` complets et propres, `verify-templates.sh` (build réel des combinaisons), docs/TEMPLATES.md |
| 10 | Wizard de création, partie 1 | 0.11.0 | À faire | Cadre du wizard, machine à états, rendu dynamique, étapes 1 à 3 |
| 11 | Wizard de création, partie 2 | 0.12.0 | À faire | Étapes 4 et 5, écran de création avec progression et rollback |
| 12 | Diagnostic | 0.13.0 | À faire | Journaux et plantages : visionneuses, filtres, export, réglage du niveau |
| 13 | Ouverture de projet, finitions, audit | 0.14.0 | À faire | EditorActivity stub, accessibilité, release R8, Dokka, audit final, plan Phase 2 |

## Hors périmètre de la Phase 1

Autres modèles de projet (Python, Web, C++, Android…), options de
bibliothèques dans les modèles, frameworks (Spring, Ktor…), analyse statique
et CI dans les projets générés, projets générés multi-modules, Docker,
terminal intégré, tooling (compilation, exécution, LSP, formatage), système
de plugins, services d'arrière-plan, éditeur complet (coloration,
autocomplétion), intégration Git, synchronisation cloud, réseau (`INTERNET`)
et envoi automatique des rapports (Crashlytics, Sentry…), notifications.

**Points d'ancrage prévus** pour ne pas rendre tout cela impossible :
`ProjectTemplateProvider` (multibinding) et manifestes déclaratifs,
`WizardStep` configurable, `FileSystem` abstrait, `EditorActivity` séparée,
modules `feature:*` isolés, `.codeide/project.json`, `AppLogger` injectable.

## Phase 2 (esquisse — détaillée à l'étape 13)

Terminal intégré · tooling (compilation, exécution, LSP, formatage) ·
système de plugins · services d'arrière-plan · éditeur complet · autres
langages. Le plan détaillé sera rédigé à l'étape 13, sans implémentation.
