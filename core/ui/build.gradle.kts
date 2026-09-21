// core:ui — socle visuel partagé : thème Material 3, classes de base,
// composants d'état, helpers insets et AppNavigator (section 5.1).
// Dépend de modules : aucun à ce stade (core:model autorisé mais inutilisé
// — aucune dépendance morte, règle du prompt maître).

plugins {
    id("codeide.android.library")
}

dependencies {
    // Flow apparaît dans la signature publique (extensions de collecte) : api.
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.material)

    // Thème de l'écran de démarrage (Theme.CodeIDE.Splash vit dans core:ui).
    implementation(libs.androidx.splashscreen)

    // Interface ViewBinding pour BaseFragment<VB> (suit la version de l'AGP).
    implementation(libs.androidx.viewbinding)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
