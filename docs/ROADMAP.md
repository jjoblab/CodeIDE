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
| 6 | Écran Paramètres | 0.7.0 | **Terminé** | `feature:settings` personnalisé M3 (ViewModel + DataStore) : apparence, langue, projets (dossier changer/effacer avec **libération de l'ancienne permission seulement si aucun projet n'en dépend**), à propos (version/build/licences), avancé (réinitialisation confirmée ADR 0014, relancer l'assistant) ; use cases du domaine Validate/Change/Clear/Reset + port ArborescencesSaf |
| 7 | Accueil : liste des projets | 0.8.0 | **Terminé** | `feature:home` : `ListAdapter`+DiffUtil (nom, description, emplacement lisible, date relative, épingle, pastille type), tri Récents/Nom (épingles en tête), **recherche avec délai 250 ms** (casse/accents), états chargement/vide/sans résultat/erreur/bandeau, **statuts d'accès** Introuvable/Permission perdue avec actions de résolution (Relocaliser/Retirer) et tirer-relâcher, actions par projet (ouvrir, renommer, épingler, retirer, supprimer du disque avec rappel du nom), FAB étendu « Nouveau projet » (placeholder wizard) + « Ouvrir un dossier existant » (ADR 0015-0016), adaptatif sw600dp 2 colonnes |
| 8 | Moteur de templates | 0.9.0 | **Terminé** | Manifestes déclaratifs (parseur + validation complète, `docs/TEMPLATES.md`), mini-langage d'expressions **parseur maison borné** (ADR 0018), substitution + 10 filtres (échappements hostile-proof), sécurité des chemins (garde après substitution, noms réservés Windows, doublons), plan figé dry-run = écriture (ADR 0017), `CreateProjectUseCase` avec rollback `NonCancellable`, `.codeide/project.json` sans donnée personnelle, `ProjectTemplateProvider` multibinding Hilt + port `TemplateAssetsSource` (impl. Android dans `app`), licences SPDX officielles dans `assets/licenses/`, 317 tests (moteur éprouvé sur fixture hostile) |
| 9 | Modèles de projet Kotlin et Java | 0.10.0 | **Terminé** | Modèles `kotlin-jvm` et `java` **complets et propres** (Greeter/Main/GreeterTest zéro avertissement, README dynamique, wrapper sommé, licences dans pom/publication), paramètres partagés (type/build/JDK 17-21/tests/wrapper/package/group/artifact/version), **`scripts/verify-templates.sh`** : 18 combinaisons générées sur disque (harnais JVM dédié `:tools:generateur`, ADR 0019) puis compilées, testées, exécutées et publiées avec les vrais Gradle/Maven/javac — tableau tout vert ; versions figées vérifiées sur les dépôts officiels (Kotlin 2.2.21, JUnit 5.14.4, Gradle 9.7.1 sommé) ; tests exhaustifs de génération dans `app` (192 combinaisons structurelles + options communes + golden + hostiles) |
| 10 | Wizard de création, partie 1 | 0.11.0 | **Terminé** | Cadre complet (ADR 0020 : hôte + indicateur + barre d'actions + `WizardViewModel` scopé à l'hôte, `SavedStateHandle` — rotation et mort du processus), rendu **dynamique** des paramètres depuis le moteur (ADR 0021 : registre de composants — tuiles segmentées, cartes radio, liste déroulante, interrupteurs, champs dérivés resynchronisables — et raisons de validation typées → ressources localisées), étapes 1 Modèle (grille de cartes + recherche prête sous 4), 2 Configuration (puces récapitulatives en direct), 3 Informations et emplacement (ADR 0022 : dossier éphémère par création, permission relâchée à l'abandon, vérifications asynchrones avec délai) ; Suivant gardé par validité, abandon confirmé |
| 11 | Wizard de création, partie 2 | 0.12.0 | **Terminé** | Étape 4 Fichiers (interrupteurs README/.gitignore/.editorconfig, licence pré-remplie auteur+année des Paramètres, langue du contenu FR/EN par boutons segmentés), étape 5 Récapitulatif (résumé par section avec bouton « Modifier » — retour arrière direct — et **arborescence prévue repliable** issue du dry-run, nombre de fichiers), **écran de création** hors numérotation (ADR 0023 : progression temps réel, annulation = rollback domaine `NonCancellable`, succès [ouvrir/marquer ouvert, accueil, créer un autre], échec typé + Réessayer + Copier les détails expurgés + nettoyage signalé), bouton principal « Créer le projet » gardé par revalidation globale, **mise en évidence du projet créé à l'accueil** (ADR 0024 : contour primaire + défilement, identifiant via la pile de retour) ; ADR 0023-0024 |
| 12 | Diagnostic | 0.13.0 | À faire | Journaux et plantages : visionneuses, filtres, export, réglage du niveau |
| 13 | Fondations de l'espace de travail | 0.14.0 | À faire | `EditorActivity` et ses trois zones **sans logique** (tiroir avec en-tête et bouton fermer, onglets centraux vides avec message d'état vide, panneau inférieur replié à trois onglets vides), navigation depuis l'accueil et l'écran de succès du wizard (mise à jour `lastOpenedAt`), `EditorViewModel`/`EditorUiState` minimal, retour de base (ferme le tiroir sinon quitte), dépendance `cel-ui` via JitPack (seule dépendance externe de feature, exception documentée) |
| 14 | Explorateur de fichiers | 0.15.0 | À faire | Arborescence **paresseuse** via `FileSystem` (énumération au dépliement, cache ViewModel), tri dossiers puis fichiers puis alphabétique, icônes par extension, gestion des erreurs d'accès (`ProjectAccessState`, bandeau de résolution), barre de navigation basse du tiroir — Explorateur active, Recherche et Git visibles mais désactivées (« Bientôt disponible ») |
| 15 | Intégration de l'éditeur et onglets de fichiers | 0.16.0 | À faire | Ouverture tiroir → onglet (`readText`, repli neutre si langage inconnu, binaires → « Ouvrir avec »), `EditorDocument`/`EditorSession` avec `setLanguage` déduit de l'extension, `TabLayout` dynamique (ajout, fermeture, menu contextuel, point de modification), **un seul `EditorView` rebranché**, thème clair/sombre, sauvegarde automatique (debounce) + manuelle via `FileSystem.writeText`, dialogue de fermeture avec modifications non enregistrées, `session.dispose()` systématique (testé + LeakCanary) |
| 16 | Panneau inférieur | 0.17.0 | À faire | `BottomSheetBehavior` trois états (replié/mi-hauteur/étendu) avec en-tête (poignée, titre, badge, actions), onglet **Journal applicatif** fonctionnel (réutilise `LogRepository`, version compacte + lien vers l'écran Diagnostic), onglets **Sortie** et **Problèmes** en stub explicite, persistance de l'état et de l'onglet actif |
| 17 | Actions du tiroir et finitions de l'espace de travail | 0.18.0 | À faire | Menu contextuel de l'explorateur (nouveau fichier, nouveau dossier, renommer, supprimer avec confirmation, actualiser — validation partagée avec le wizard), reprise des onglets ouverts (`workspace-state.json` non synchronisé), accessibilité complète (TalkBack, cibles ≥ 48 dp), tablette/paysage (tiroir permanent si retenu ADR É13), audit mémoire (LeakCanary, StrictMode) |
| 18 | Audit final de Phase 1 | 0.19.0 | À faire | Reconnaissance du type de projet à l'ouverture (`.codeide/project.json`), `assembleRelease` R8 **avec les règles ProGuard de la bibliothèque d'édition**, Dokka, audit des dépendances inutilisées/TODO/code mort/données personnelles, `verify-templates.sh` vert, plan détaillé de la Phase 2, archive finale vérifiée **et poussée sur GitHub avec le tag `v0.19.0`** |

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

## Phase 2 (esquisse — détaillée à l'étape 18)

Terminal intégré · tooling (compilation, exécution, LSP — y compris le
branchement réel de `cel-lsp` et des diagnostics de compilation dans le
panneau inférieur —, formatage) · système de plugins · services d'arrière-plan ·
autocomplétion · autres langages. Le plan détaillé sera rédigé à l'étape 18,
sans implémentation.
