# ai/providers

Module Kotlin JVM pur (mission IA, ADR 0122) : `AiHttpTransport`
(`HttpURLConnection`), parseur SSE, adaptateurs Anthropic (Messages) et
« compatible OpenAI » (Chat Completions), mappage d'erreurs HTTP →
`AppError`, backoff borné. `explicitApi()` actif.

UNIQUE point de sortie réseau IA de l'application (ADR 0121 point 2) —
aucun autre code n'ouvre de connexion pour l'IA.

Statut : IA-0 (v0.135.0) — échafaudage ; transport et adaptateurs au lot
IA-1 (ADR 0123).
