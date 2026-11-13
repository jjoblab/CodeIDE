# core/domain

Module Kotlin JVM pur : cas d'usage (use cases), interfaces de repositories,
`FileSystem`, `AppLogger`, `LogRedactor`, `DispatcherProvider`. Autorise
`javax.inject` et Coroutines/Flow. Ne connaît ni Android ni les implémentations.

Étape 1 : `DispatcherProvider` (+ `DefaultDispatcherProvider`) et la convention
des use cases (`operator fun invoke`, voir `docs/CONVENTIONS.md`).

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 8 livrées (v0.9.0) — contrats de la couche données, journalisation, plantages, accueil et **moteur de templates complet** (manifestes, expressions, rendu, plan figé, création avec rollback).
