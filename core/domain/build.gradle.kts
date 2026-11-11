// core:domain — cas d'usage et interfaces du domaine, Kotlin JVM pur.
// Peut utiliser javax.inject et Coroutines/Flow (section 5.2).

plugins {
    id("codeide.kotlin.library")
    // Manifestes de templates déclaratifs (étape 8, ADR 0005) : DTO internes
    // analysés par kotlinx.serialization (1.9.0, compagnon du Kotlin 2.2.10).
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    // Documentation API (audit étape 18) : module explicitApi, contrat
    // public documenté par Dokka.
    alias(libs.plugins.dokka)
}

dependencies {
    api(project(":core:model"))

    // CoroutineDispatcher apparaît dans la signature publique : api.
    api(libs.kotlinx.coroutines.core)

    // @Inject sur DefaultDispatcherProvider (injection par constructeur,
    // consommée par Hilt dans app).
    implementation(libs.javax.inject)

    // Lecture des manifestes template.json (DTO internes au moteur).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // Fakes de test pour les cas d'usage de journalisation (testImplementation
    // uniquement, vérifié par checkModuleDependencies).
    testImplementation(project(":core:testing"))
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
