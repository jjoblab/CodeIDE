// feature:home — écran d'accueil (liste des projets, étape 7).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Tirer-relâcher : revérification des états d'accès (étape 7).
    implementation(libs.androidx.swiperefreshlayout)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Tests du ViewModel : horloge virtuelle et fakes.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
