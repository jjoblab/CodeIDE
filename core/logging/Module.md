# core/logging

Implémentation maison du système de journalisation (sans Timber) : sinks Logcat et fichier (JSON Lines, rotation 1 Mo / 5 archives / 7 jours), écriture asynchrone non bloquante, tampon circulaire de breadcrumbs, expurgation à l'écriture (`LogRedactor`), export zip via FileProvider. Seul module (avec `core:crash`) autorisé à toucher `android.util.Log`.

Contenu fonctionnel prévu : voir `README.md` du module.
