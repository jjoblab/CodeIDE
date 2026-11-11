# core/ai — Domaine IA — modèle, ports, cas d'usage

Module Kotlin JVM pur du chantier IA (ADR 0122, lot IA-1 pour le
contenu). Frontière que `:ai:providers` (adaptateurs fournisseurs) et
`:feature:ai` (UI) consomment — aucune dépendance dans l'autre sens.

## Dépendances autorisées

`:core:model` (api), `:core:domain` (api), `kotlinx-coroutines-core`
(api — `Flow<AiEvent>` dans les signatures). Interdits : Android,
bibliothèque réseau (ADR 0121 : un seul point de sortie réseau).

## Contenu prévu (lot IA-1, ADR 0123)

- `AiMessage` (rôles, blocs texte/outil), `AiRequest`, `AiEvent`
  (`TextDelta`, `ToolCallStarted`, `ToolCallArguments`,
  `ToolCallCompleted`, `Usage`, `Done`, `Failure`), `AiModelInfo`,
  `ToolSpec` ;
- port `AiProvider` : `stream(request): Flow<AiEvent>`,
  `listModels()`, `ping()` ;
- `SendMessageUseCase` (renvoie un `Flow<AiEvent>`), `TokenBudget`
  (estimation + plafond).

Seuil Kover : ≥ 80 %.
