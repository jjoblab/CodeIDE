// :ai:providers — transport IA (mission IA, ADR 0122/0123) :
// `AiHttpTransport` (HttpURLConnection), parseur SSE, adaptateurs
// Anthropic (Messages) et « compatible OpenAI » (Chat Completions),
// mappage d'erreurs HTTP → AppError, backoff borné (429/5xx avant le
// premier jeton, Retry-After respecté).
//
// Kotlin JVM PUR (ADR 0122) : `HttpURLConnection` (JDK) uniquement —
// aucune dépendance réseau tierce (ADR 0033). Ce module est l'UNIQUE
// point de sortie réseau IA de l'application (ADR 0121 point 2) ; un
// test d'architecture (lot IA-9) fera échouer toute requête IA ouverte
// ailleurs. Interdits vérifiés : :ai:providers → Android.
//
// IA-0 : échafaudage — module vide compilable ; transport, parseur SSE
// et adaptateurs arrivent au lot IA-1 (ADR 0123 §décision).

plugins {
    id("codeide.kotlin.library")
}

dependencies {
    // Contrat du domaine IA : ports AiProvider/AiEvent (api — les
    // adaptateurs implémentent la surface publique de :core:ai).
    api(project(":core:ai"))
    api(project(":core:model"))
    api(project(":core:domain"))
    // Le transport renvoie des Flow (lecture ligne à ligne, annulation
    // coopérative — ADR 0123 point 4).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Objectif de couverture ≥ 80 % sur ce module (mission IA, plan IA-0) —
// les flux SSE (nominaux et dégradés) seront testés contre des fichiers
// dorés et un serveur JDK (com.sun.net.httpserver).
kover {
    reports {
        verify {
            rule("couverture-minimale-ai-providers") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
