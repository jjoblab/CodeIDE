// Module core:database — persistance Room (étape 4 : registre des projets).
// Schémas exportés dans schemas/ par la convention codeide.android.room.

plugins {
    id("codeide.android.library")
    id("codeide.android.room")
    id("codeide.android.hilt")
}

dependencies {
    // Les entités se mappent vers le modèle (Project, ProjectId…) : le
    // type apparaît dans l'API publique des mappeurs.
    api(project(":core:model"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
