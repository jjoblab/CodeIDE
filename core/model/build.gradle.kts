// core:model — entités et types partagés, Kotlin JVM pur (section 5.1).
// Aucune dépendance vers un autre module : base de l'architecture.

plugins {
    id("codeide.kotlin.library")
    // LogEntry/FlattenedException sont sérialisables (format JSON Lines de
    // core:logging, section 5.7) : le plugin compagnon du Kotlin 2.2.10.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    // Le sérialiseur généré accompagne les types exposés : api.
    api(libs.kotlinx.serialization.json)
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt).
kover {
    reports {
        verify {
            rule("couverture-minimale-modele") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
