// Module feature — voir README.md et docs/ARCHITECTURE.md.
// Étape 12 : visionneuse de journaux et rapports de plantage.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Onglets Journaux / Plantages (section 5.7 du prompt maître) — le seul
    // ajout propre à ce module au-delà de la convention feature.
    implementation(libs.androidx.viewpager2)
    // Centre de sorties (lot L4, ADR 0162 décision 1) : l'écran Journaux
    // CONSOMME le lecteur du Centre de sorties (FragmentLecteurSorties +
    // fabrique du flux) — pas un second afficheur ; l'arête est posée
    // dans le tableau de ModuleRulesPlugin avec sa justification.
    implementation(project(":feature:sorties"))
    // @RequiresApi du partage d'archives (audit étape 18 : import direct).
    implementation(libs.androidx.annotation)

    // Tests du ViewModel : horloge virtuelle et fakes.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
