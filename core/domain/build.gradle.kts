// core:domain — cas d'usage et interfaces du domaine, Kotlin JVM pur.
// Peut utiliser javax.inject et Coroutines/Flow (section 5.2).

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":core:model"))
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt).
kover {
    reports {
        verify {
            rule("couverture-minimale-domaine") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
