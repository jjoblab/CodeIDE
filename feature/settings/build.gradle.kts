// feature:settings — écran Paramètres (étape 6) : écran personnalisé
// Material 3 (pas de PreferenceFragmentCompat), piloté par un ViewModel
// et DataStore au travers des use cases du domaine.

plugins {
    id("codeide.android.feature")
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Fakes (FileSystem, dépôts) et règle MainDispatcherRule.
    testImplementation(project(":core:testing"))
}
