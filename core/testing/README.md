# core/testing — Tests — fakes et utilitaires

> Statut étape 4 : utilitaires coroutines (v0.2.0 : `MainDispatcherRule`, `TestDispatcherProvider`), journalisation (v0.3.0 : `FakeAppLogger`, `InMemoryLogRepository`), plantages (v0.4.0 : `FakeCrashReportRepository`, `FakePendingExitInfoRecorder`), couche données (v0.5.0 : `FakeFileSystem`, `FakeProjectRepository`, `FakeSettingsRepository`)
> et fakes de journalisation (v0.3.0 : `FakeAppLogger`, `InMemoryLogRepository`),
> plantages (v0.4.0 : `FakeCrashReportRepository`, `FakePendingExitInfoRecorder`).

Fakes et utilitaires partagés par les tests de tous les modules : `MainDispatcherRule`, `TestDispatcherProvider`, `FakeFileSystem` (en mémoire), `FakeAppLogger`, `InMemoryLogRepository`, `FakeProjectRepository`, `FakeSettingsRepository`. Fakes plutôt que mocks (section 8 du prompt). Réservé aux configurations de test — vérifié par `checkModuleDependencies`.

## Dépendances autorisées

`core:model`, `core:domain` (api — `DispatcherProvider` est implémenté par `TestDispatcherProvider`), `junit4` et `kotlinx-coroutines-test` (api — `TestWatcher`/`TestDispatcher` apparaissent dans la signature publique).

Consommation en `testImplementation` uniquement — vérifié par `checkModuleDependencies`.

## API publique (étape 1)

- **`MainDispatcherRule`** — règle JUnit 4 : installe un `TestDispatcher` (standard par défaut) comme `Dispatchers.Main` le temps du test ; le dispatcher est exposé pour les avancées manuelles (`scheduler.advanceUntilIdle()`).
- **`TestDispatcherProvider`** — premier double de test : `DispatcherProvider` renvoyant le même dispatcher pour `io`/`default`/`main`, à construire avec le dispatcher de la règle pour partager l'horloge virtuelle.

Couche données (v0.5.0) : `FakeFileSystem` (arborescence d'URI en mémoire, robinets de défaillance, permissions simulées — tient exactement le contrat de `SafFileSystem` : collision insensible à la casse, `NotFound` sans exception), `FakeProjectRepository` (ordre de l'accueil, unicité de dossier, horloge injectable) et `FakeSettingsRepository` (transformation atomique).

## Vérifications du module

```bash
./gradlew :core:testing:check
```
