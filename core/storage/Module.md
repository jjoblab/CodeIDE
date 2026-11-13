# core/storage

Implémentation SAF du port `FileSystem` : URI de documents (jamais de
`File`, ADR 0003), listing en requête groupée via `DocumentsContract`,
pré-contrôle et contrôle du nom retourné à la création (jamais
d'écrasement), exceptions traduites en `AppError.Storage` typées,
permissions persistantes derrière un port testable.

Statut : livré à l'étape 4 (v0.5.0) — voir le README du module, la
section 5.6 du prompt maître et `docs/TESTS_MANUELS.md` (S1-S5).
