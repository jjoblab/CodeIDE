# core/ai

Module Kotlin JVM pur (mission IA, ADR 0122) : modèle IA (`AiMessage`,
`AiEvent`, `ToolSpec`…), ports (`AiProvider`, `AiTool`, `ApprovalGate`),
cas d'usage (`SendMessageUseCase`, `RunAgentUseCase`), assemblage du
contexte, budget de jetons, boucle d'agent. `explicitApi()` actif.

Ne connaît ni Android ni le réseau : le SEUL point de sortie réseau IA
est `AiHttpTransport` dans `:ai:providers` (ADR 0121 point 2).

Statut : IA-0 (v0.135.0) — échafaudage (règles, explicitApi, Kover ≥ 80 %) ;
contenu réel au lot IA-1.
