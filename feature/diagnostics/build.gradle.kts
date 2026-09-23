// Module feature — voir README.md et docs/ARCHITECTURE.md.
// Étape 12 : visionneuse de journaux et rapports de plantage.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Onglets Journaux / Plantages (section 5.7 du prompt maître) — le seul
    // ajout propre à ce module au-delà de la convention feature.
    implementation(libs.androidx.viewpager2)

    // Tests du ViewModel : horloge virtuelle et fakes.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
