// :core:session — contrat de session de projet et registre de fermeture
// (mission « Cycle de vie », prompt 3 §5.1, étape B — ADR 0114).
//
// Kotlin JVM PUR (règle 1) : machine à états observable
// {Fermee, Ouverture, Ouverte, Fermeture}, registre de ressources
// Fermable (fermeture en ORDRE INVERSE du démarrage, délai maximal par
// élément, drapeau « ne pas annuler » — I2/I7), gestionnaire garantissant
// I1 (au plus une session active par processus : ouvrir B ferme A
// D'ABORD). Tolérant aux pannes partielles, borné en temps, rapport de
// durée par élément journalisé via AppLogger.
//
// Aucune dépendance Android : les adaptateurs des ressources réelles
// (SessionLsp, flush des sauvegardes, LspProject cel-lsp…) vivront dans
// les étapes C (feature:editor) et D (tooling) — ce module n'expose que
// le contrat et sa mécanique.

plugins {
    id("codeide.kotlin.library")
}

dependencies {
    // AppResult/AppError (résultats typés — règle 6) et ProjectId
    // (identité opaque du projet, règle 15 dans les journaux).
    api(project(":core:model"))
    // Ports du domaine : AppLogger (seule voie de journalisation
    // autorisée, règle 14) et TimeProvider (durées du rapport pilotables
    // en test, section 12.5 du prompt maître).
    api(project(":core:domain"))
    // StateFlow de l'état observable, Mutex/withTimeout de la fermeture.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt).
kover {
    reports {
        verify {
            rule("couverture-minimale-session") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
