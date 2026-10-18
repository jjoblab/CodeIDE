// :core:ai — domaine IA (mission IA, ADR 0122) : modèle (AiMessage,
// AiEvent, ToolSpec…), ports (AiProvider, AiTool, ApprovalGate), cas
// d'usage (SendMessageUseCase, RunAgentUseCase), assemblage du contexte,
// budget de jetons, boucle d'agent.
//
// Kotlin JVM PUR (ADR 0122) : aucun Android, aucune bibliothèque réseau —
// le SEUL point de sortie réseau IA est `AiHttpTransport`, dans
// :ai:providers (ADR 0121 point 2). Les interdits sont vérifiés par le
// ModuleRulesPlugin (:core:ai → {:core:model, :core:domain} ; Android
// interdit).
//
// IA-0 : échafaudage — module vide compilable (règles, explicitApi,
// seuil Kover en place) ; les livrables réels (IA-1) arriveront au lot
// suivant : `AiMessage`, `AiRequest`, `AiEvent`, `AiModelInfo`,
// `ToolSpec`, port `AiProvider`, `SendMessageUseCase`, `TokenBudget`.

plugins {
    id("codeide.kotlin.library")
}

dependencies {
    // Résultats typés AppResult/AppError (règle 4) et identifiants
    // opaques — apparaissent dans les signatures publiques du domaine IA.
    api(project(":core:model"))
    // Ports du domaine : AppLogger (seule voie de journalisation,
    // règle 14), DispatcherProvider, TimeProvider.
    api(project(":core:domain"))
    // Flow<AiEvent> : la signature du port AiProvider (ADR 0123 point 1).
    api(libs.kotlinx.coroutines.core)
    // @Inject des cas d'usage (javax.inject, pure JVM — même usage que
    // :core:domain, version 1 du catalogue).
    implementation(libs.javax.inject)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Objectif de couverture ≥ 80 % sur ce module (mission IA, plan IA-0) —
// la frontière domaine IA est la surface la plus testée du chantier.
kover {
    reports {
        verify {
            rule("couverture-minimale-core-ai") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
