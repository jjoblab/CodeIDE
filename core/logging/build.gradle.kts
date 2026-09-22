// Module core:logging — implémentation de la journalisation maison
// (section 5.7 du prompt maître) : sinks, rotation, écriture asynchrone,
// tampon de breadcrumbs, en-tête de session et export zip.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Implémente l'API du domaine (AppLogger, LogRepository, LogExportWriter) :
    // ces types apparaissent dans les signatures publiques du module.
    api(project(":core:domain"))

    // Persistance JSON Lines des entrées.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    // Doubles de test (TestDispatcherProvider) pour le dépôt et l'export.
    testImplementation(project(":core:testing"))
}
