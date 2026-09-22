// Module d'application : assemblage final de CodeIDE (section 5.1).
// La version, l'identifiant et le SDK proviennent des conventions et de
// version.properties — aucune valeur dupliquée ici.

plugins {
    id("codeide.android.application")
    id("codeide.android.hilt")
}

android {
    buildFeatures {
        // BuildInfo du système de journalisation : VERSION_NAME/VERSION_CODE
        // alimentent l'en-tête de session et device-info.txt (section 5.7).
        buildConfig = true
    }
}

dependencies {
    // Écrans et socle visuel.
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))

    // Assistant de premier lancement (étape 5) : destination du graphe
    // de navigation et chaînes localisées de l'assistant.
    implementation(project(":feature:onboarding"))

    // Wizard de création de projet (étape 7 : destination placeholder ;
    // assistant complet à l'étape 10).
    implementation(project(":feature:newproject"))

    // Journalisation maison (étape 2) : AppLogger, initialisation,
    // FileProvider des exports.
    implementation(project(":core:logging"))
    implementation(project(":core:domain"))

    // Gestion des plantages (étape 3) : installation du gestionnaire,
    // CrashActivity et FileProvider du processus :crash.
    implementation(project(":core:crash"))

    // Couche données (étape 4) : assemblage final — les repositories du
    // domaine y sont liés aux sources réelles (Room, DataStore, SAF).
    implementation(project(":core:data"))

    implementation(libs.androidx.appcompat)
    // enableEdgeToEdge() — contenu tendu sous les barres système.
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.material)

    // Diagnostics en debug uniquement (jamais en release).
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Lancement de l'activité réelle sous Robolectric avec Hilt.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
