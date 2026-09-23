// core:bootstrap — localisation des outils du bootstrap natif et
// environnement de sous-processus (prompt compagnon Terminal-1, section 3).
// Première étape du terminal intégré : heuristiques de résolution pures
// (testables en JVM) et source unique de l'environnement des sous-processus.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Les ports ToolchainLocator et ProcessEnvironmentProvider apparaissent
    // dans les signatures publiques du module (liaisons Hilt vers le domaine).
    api(project(":core:domain"))

    testImplementation(libs.junit4)
    // Vérification de l'implémentation contre filesDir via Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt).
kover {
    reports {
        verify {
            rule("couverture-minimale-bootstrap") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
