// feature:settings — écran Paramètres (étape 6) : écran personnalisé
// Material 3 (pas de PreferenceFragmentCompat), piloté par un ViewModel
// et DataStore au travers des use cases du domaine.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Lot LSP-3 (ADR 0128 §4) : dépôt DÉDIÉ des réglages LSP — les écrans
    // « Intelligence du code » persistent budget/surveillance sans passer
    // par AppSettings (exception ModuleRulesPlugin documentée, même
    // précédent que :feature:editor en LSP-2).
    implementation(project(":core:datastore"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Fakes (FileSystem, dépôts) et règle MainDispatcherRule.
    testImplementation(project(":core:testing"))
    // Socle DataStore pour le dépôt RÉEL sur fichier temporaire (patron
    // BaseEditorViewModelTest) — androidx.datastore est `implementation`
    // côté :core:datastore (non transitif).
    testImplementation(libs.androidx.datastore)
}
