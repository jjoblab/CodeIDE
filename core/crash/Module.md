# core/crash

Capture des plantages non gérés (`CrashHandler`, installé en première ligne du `Application.onCreate`), écriture atomique de rapports JSON, détection de boucle de plantages, `CrashActivity` dans un processus séparé `:crash` sans Hilt ni Room ni DataStore. Liaison avec `core:logging` par lambdas uniquement (jamais de dépendance de module).

Livrée à l'étape 3 (v0.4.0) : guide `docs/JOURNALISATION_ET_PLANTAGES.md` § 9, ADR 0006 et 0010.
