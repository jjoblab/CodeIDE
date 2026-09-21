// Module d'application : assemblage final de CodeIDE (section 5.1).
// La version, l'identifiant et le SDK proviennent des conventions et de
// version.properties — aucune valeur dupliquée ici.

plugins {
    id("codeide.android.application")
    id("codeide.android.hilt")
}

dependencies {
    // Écrans et socle visuel.
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.appcompat)
    // enableEdgeToEdge() — contenu tendu sous les barres système.
    implementation(libs.androidx.activity)
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
