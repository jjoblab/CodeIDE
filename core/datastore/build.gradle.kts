// Module core:datastore — source des paramètres applicatifs (étape 4).
// Preferences DataStore, gestion de corruption, projection AppSettings.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // AppSettings et StorageLocation apparaissent dans l'API publique
    // du module (signatures de SettingsDataStore).
    api(project(":core:model"))

    implementation(libs.androidx.datastore)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
