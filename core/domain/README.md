# core/domain — Domaine — cas d'usage et interfaces

> Statut étape 4 : fondations (v0.2.0 : `DispatcherProvider`, convention des use cases), journalisation
> (v0.3.0 : `AppLogger`, `LogRedactor`, `LogConfig`, `LogRepository`, `TimeProvider`, use cases
> `ObserveLogs`/`ExportLogs`/`ClearLogs`, `LogExportWriter`), plantages (v0.4.0 :
> `CrashReportRepository`, `PendingExitInfoRecorder`, use cases de lecture/consultation/suppression
> et enregistrement des sorties), couche données (v0.5.0 : contrats `FileSystem`/`FileStat`,
> `ProjectRepository`, `SettingsRepository`, `ForbiddenFolders`, use cases du registre des
> projets, de la vérification d'accès et des paramètres).

Module Kotlin JVM pur : cas d'usage (use cases), interfaces de repositories, `FileSystem`, `AppLogger`, `LogRedactor`, `DispatcherProvider`. Autorise `javax.inject` et Coroutines/Flow. Ne connaît ni Android ni les implémentations.

## Dépendances autorisées

`core:model` uniquement (api), `kotlinx-coroutines-core` (api — `CoroutineDispatcher` dans la signature publique), `javax.inject` (implémentation).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique (étape 1)

- **`DispatcherProvider`** — `io`, `default`, `main` : les dispatchers sont **injectés**, jamais codés en dur (règle 5 du prompt).
- **`DefaultDispatcherProvider`** — implémentation de référence (`@Inject`), branchée sur `Dispatchers.IO/Default/Main`.
- **Convention des use cases** — chaque use case est une classe avec `operator fun invoke`, injectable et testable unitairement ; la convention complète (signatures, `AppResult`, dispatchers) est décrite dans `docs/CONVENTIONS.md` § « Use cases ».

Couche données (étape 4, v0.5.0) : contrats `FileSystem` (13 opérations SAF, erreurs typées `AppResult`) et `FileStat` ; `ProjectRepository` (registre — observation ordonnée pour l'accueil, ajout avec identifiant produit par le dépôt, retrait idempotent, renommage du libellé uniquement, épingle, marquage d'ouverture) ; `SettingsRepository` (observation, transformation atomique, dossier de travail) ; `ForbiddenFolders` (détection pure des dossiers refusés par Android 11+) ; use cases associés et `VerifyProjectAccessUseCase` (permission → existence, jamais un crash, section 5.6). Use cases du moteur de templates (étape 8, v0.9.0) : package `templates` — port `TemplateAssetsSource` + `GeneratorVersion`, fournisseur embarqué `EmbeddedTemplatesProvider` (multibinding Hilt `@IntoSet`), parseur de manifestes déclaratifs (validation complète), **mini-langage d'expressions à parseur maison borné** (ADR 0018), moteur de substitution avec filtres d'échappement hostile-proof, garde de sécurité des chemins, `TemplateEngine` (évaluation du formulaire, plan figé complet — **le dry-run est l'écriture**, ADR 0017) et use cases `ListTemplates`/`ValidateProjectName`/`ValidatePackageName`/`EvaluateTemplateForm`/`PlanProjectCreation`/`CreateProject` (rollback `NonCancellable`, registre en dernier). Le format des manifestes est le contrat `docs/TEMPLATES.md`.

## Vérifications du module

```bash
./gradlew :core:domain:check
```

Couverture exigée : ≥ 80 % (Kover, seuil vérifié par `koverVerify`).
