# core/logging

Implémentation maison du système de journalisation (sans Timber) : moteur
du pipeline, sinks Logcat et fichier (JSON Lines, rotation 1 Mio /
5 archives / 7 jours), écriture asynchrone bornée (canal `DROP_OLDEST`,
groupement 500 ms, flush immédiat sur `ERROR`), tampon circulaire de
breadcrumbs, expurgation à l'écriture (`LogRedactor` du domaine), en-tête
de session, export zip via FileProvider. Seul module (avec `core:crash`)
autorisé à toucher `android.util.Log`.

Statut : livré à l'étape 2 (v0.3.0) — voir le README du module, l'ADR 0009
et `docs/JOURNALISATION_ET_PLANTAGES.md`.
