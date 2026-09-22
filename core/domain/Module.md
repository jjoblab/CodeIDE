# core/domain

Module Kotlin JVM pur : cas d'usage (use cases), interfaces de repositories,
`FileSystem`, `AppLogger`, `LogRedactor`, `DispatcherProvider`. Autorise
`javax.inject` et Coroutines/Flow. Ne connaît ni Android ni les implémentations.

Étape 1 : `DispatcherProvider` (+ `DefaultDispatcherProvider`) et la convention
des use cases (`operator fun invoke`, voir `docs/CONVENTIONS.md`).

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 4 livrées (v0.5.0) — contrats de la couche données (`FileSystem`, repositories) et leurs cas d'usage.
