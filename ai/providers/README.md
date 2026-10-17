# ai/providers — Transport IA — HTTP, SSE, adaptateurs

Module Kotlin JVM pur du chantier IA (ADR 0122, lot IA-1 pour le
contenu). Implémente les ports de `:core:ai` contre deux protocoles
fournisseurs (ADR 0123) : Anthropic Messages et « compatible OpenAI ».

## Dépendances autorisées

`:core:ai` (api), `:core:model` (api), `:core:domain` (api),
`kotlinx-coroutines-core` (api). Interdits : Android, toute bibliothèque
réseau tierce (`HttpURLConnection` du JDK — ADR 0033/0123).

## Contenu prévu (lot IA-1, ADR 0123)

- `AiHttpTransport` : lecture ligne à ligne, `Dispatchers.IO` injecté,
  annulation coopérative (fermeture socket < 1 s), `CancellationException`
  relancée ;
- `SseParser` : lignes `data:`, `event:`, UTF-8 coupé, événements
  inconnus ignorés ;
- `AnthropicProvider`, `OpenAiCompatibleProvider` (URL de base
  configurable — serveur local sur la boucle locale, ADR 0121 point 5) ;
- erreurs : 401/403 (clé), 429 (`Retry-After`), 5xx, hors-ligne, délai ;
  backoff borné AVANT le premier jeton seulement.

Tests (JVM pur, `com.sun.net.httpserver` + fichiers dorés `src/test/resources/flux/`).
Seuil Kover : ≥ 80 %.
