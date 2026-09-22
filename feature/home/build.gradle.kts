// feature:home — écran d'accueil (liste des projets à partir de l'étape 7).
// Étape 1 : fragment placeholder qui prouve la navigation AppNavigator.

plugins {
    id("codeide.android.feature")
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Tests du ViewModel (étape 5) : horloge virtuelle et fakes.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
