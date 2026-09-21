# core/testing — Tests — fakes et utilitaires

> Statut étape 1 : premiers utilitaires livrés (`MainDispatcherRule`, `TestDispatcherProvider`). Chaque étape suivante ajoute les fakes de ses interfaces.

Fakes et utilitaires partagés par les tests de tous les modules : `MainDispatcherRule`, `TestDispatcherProvider`, `FakeFileSystem` (en mémoire), `FakeAppLogger`, `InMemoryLogRepository`, `FakeProjectRepository`, `FakeSettingsRepository`. Fakes plutôt que mocks (section 8 du prompt). Réservé aux configurations de test — vérifié par `checkModuleDependencies`.

## Dépendances autorisées

`core:model`, `core:domain` (api — `DispatcherProvider` est implémenté par `TestDispatcherProvider`), `junit4` et `kotlinx-coroutines-test` (api — `TestWatcher`/`TestDispatcher` apparaissent dans la signature publique).

Consommation en `testImplementation` uniquement — vérifié par `checkModuleDependencies`.

## API publique (étape 1)

- **`MainDispatcherRule`** — règle JUnit 4 : installe un `TestDispatcher` (standard par défaut) comme `Dispatchers.Main` le temps du test ; le dispatcher est exposé pour les avancées manuelles (`scheduler.advanceUntilIdle()`).
- **`TestDispatcherProvider`** — premier double de test : `DispatcherProvider` renvoyant le même dispatcher pour `io`/`default`/`main`, à construire avec le dispatcher de la règle pour partager l'horloge virtuelle.

Fakes prévus aux étapes suivantes : `FakeAppLogger`/`InMemoryLogRepository` (étape 2), `FakeFileSystem`/`FakeProjectRepository`/`FakeSettingsRepository` (étape 4).

## Vérifications du module

```bash
./gradlew :core:testing:check
```
