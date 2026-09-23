// feature:home — écran d'accueil (liste des projets, étape 7).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Tirer-relâcher : revérification des états d'accès (étape 7).
    implementation(libs.androidx.swiperefreshlayout)
    // @RequiresApi de la mise en évidence post-création (audit étape 18 : import direct).
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Tests du ViewModel : horloge virtuelle et fakes.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
