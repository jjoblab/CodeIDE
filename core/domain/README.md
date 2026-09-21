# core/domain — Domaine — cas d'usage et interfaces

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 1 puis chaque étape ajoute ses use cases.

Module Kotlin JVM pur : cas d'usage (use cases), interfaces de repositories, `FileSystem`, `AppLogger`, `LogRedactor`, `DispatcherProvider`. Autorise `javax.inject` et Coroutines/Flow. Ne connaît ni Android ni les implémentations.

## Dépendances autorisées

`core:model` uniquement.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- DispatcherProvider — dispatchers injectés, jamais codés en dur (étape 1)
- Conventions de use cases : `operator fun invoke` (étape 1)
- AppLogger, LogRepository, LogConfig (étape 2)
- CrashReportRepository (étape 3)
- ProjectRepository, SettingsRepository, FileSystem (étape 4)
- Use cases du moteur de templates (étape 8)

## Vérifications du module

```bash
./gradlew :core:domain:check
```
