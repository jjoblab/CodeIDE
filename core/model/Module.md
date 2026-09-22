# core/model

Module Kotlin JVM pur : entités immuables et types partagés de tout le domaine
(`AppResult`, `AppError`, identifiants typés `ProjectId`/`TemplateId`/`CrashReportId`,
`StorageLocation`, `LogLevel`, `LogEntry` sérialisable — la ligne JSON Lines persistée —
et `FlattenedException` bornée). Aucune dépendance hors `kotlinx.serialization`
(persistés en JSONL) : c'est la base du graphe de dépendances.

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 8 livrées (v0.9.0) — entités des fondations, de la journalisation, des plantages, de la couche données et du moteur de templates (`ProjectTemplate`, `TemplateOptions`, `TemplatePlan`, `CreationProgress`…).
