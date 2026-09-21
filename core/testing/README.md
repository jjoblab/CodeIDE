# core/testing — Tests — fakes et utilitaires

> Statut étape 0 : squelette compilable, sans contenu fonctionnel. Ce module se remplit à l'étape 1 (premiers fakes) puis enrichi à chaque étape.

Fakes et utilitaires partagés par les tests de tous les modules : `MainDispatcherRule`, `TestDispatcherProvider`, `FakeFileSystem` (en mémoire), `FakeAppLogger`, `InMemoryLogRepository`, `FakeProjectRepository`, `FakeSettingsRepository`. Fakes plutôt que mocks (section 8 du prompt). Réservé aux configurations de test — vérifié par `checkModuleDependencies`.

## Dépendances autorisées

`core:model`, `core:domain` (consommation en testImplementation uniquement).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique prévue

- MainDispatcherRule, TestDispatcherProvider (étape 1)
- FakeFileSystem, FakeAppLogger (étape 4)
- FakeProjectRepository, FakeSettingsRepository (étape 4)

## Vérifications du module

```bash
./gradlew :core:testing:check
```
