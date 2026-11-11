// Module core:storage — accès aux documents via SAF (étape 4).
// Implémente le port FileSystem du domaine sur DocumentsContract et
// ContentResolver, avec requêtes groupées (jamais DocumentFile, section 5.6).

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Le port FileSystem et FileStat apparaissent dans les signatures
    // publiques du module (liaison Hilt vers l'interface du domaine).
    api(project(":core:domain"))

    // String.toUri() (extension KTX, exigée par Lint) pour les URI SAF.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    // TestDispatcherProvider pour SafFileSystem (dispatchers injectés).
    testImplementation(project(":core:testing"))
}
