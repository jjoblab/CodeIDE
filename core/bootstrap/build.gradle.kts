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

    // NotificationCompat du service de premier plan de l'installation
    // (refonte E2, ADR 0087 — précédent core:terminal-runtime).
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit4)
    // Vérification de l'implémentation contre filesDir via Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Fakes des ports du domaine (lanceur, environnement, installateur).
    testImplementation(project(":core:testing"))
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt).
// Exclusions : code généré par Hilt (pattern core:terminal-runtime).
kover {
    reports {
        filters {
            excludes {
                // Code GÉNÉRÉ par Hilt/Dagger (fabriques, injecteurs,
                // composants, agrégation) : le câblage réel est validé par
                // hiltJavaCompileDebug à l'échelle de l'application.
                classes(
                    "*.Hilt_*",
                    "*_Factory",
                    "*_MembersInjector",
                    "*_GeneratedComponentManager*",
                    "hilt_aggregated_deps.*",
                )
            }
        }
        verify {
            rule("couverture-minimale-bootstrap") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
