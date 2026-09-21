// core:domain — cas d'usage et interfaces du domaine, Kotlin JVM pur.
// Peut utiliser javax.inject et Coroutines/Flow (section 5.2).

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":core:model"))

    // CoroutineDispatcher apparaît dans la signature publique : api.
    api(libs.kotlinx.coroutines.core)

    // @Inject sur DefaultDispatcherProvider (injection par constructeur,
    // consommée par Hilt dans app).
    implementation(libs.javax.inject)

    testImplementation(libs.kotlinx.coroutines.test)
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
