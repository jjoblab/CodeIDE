// Module feature — assistant de premier lancement (étape 5, section 11 du
// prompt maître) : cinq pages pager non swipable, dossier de travail SAF
// avec test d'écriture, apparence à aperçu immédiat, profil d'auteur.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Pager des cinq pages de l'assistant (non swipable : navigation aux
    // boutons uniquement).
    implementation(libs.androidx.viewpager2)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Fakes (FileSystem, dépôts de paramètres) et règle MainDispatcherRule.
    testImplementation(project(":core:testing"))
}
