// core:model — entités et types partagés, Kotlin JVM pur (section 5.1).
// Aucune dépendance vers un autre module : base de l'architecture.

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kover)
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
