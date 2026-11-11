# core/testing

Fakes et utilitaires partagés par les tests de tous les modules :
`MainDispatcherRule`, `TestDispatcherProvider`, puis à chaque étape les fakes
des interfaces du domaine (`FakeAppLogger`, `FakeFileSystem`,
`FakeProjectRepository`…). Fakes plutôt que mocks (section 8 du prompt).

Réservé aux configurations de test — vérifié par `checkModuleDependencies`.

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 8 livrées (v0.9.0) — fakes de la journalisation, des plantages, de la couche données et du moteur de templates (`FakeTemplateAssetsSource`, avec robinets d'échec et garde anti-traversée).
