# core/testing

Fakes et utilitaires partagés par les tests de tous les modules :
`MainDispatcherRule`, `TestDispatcherProvider`, puis à chaque étape les fakes
des interfaces du domaine (`FakeAppLogger`, `FakeFileSystem`,
`FakeProjectRepository`…). Fakes plutôt que mocks (section 8 du prompt).

Réservé aux configurations de test — vérifié par `checkModuleDependencies`.

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 4 livrées (v0.5.0) — fakes de la couche données (`FakeFileSystem`, `FakeProjectRepository`, `FakeSettingsRepository`).
