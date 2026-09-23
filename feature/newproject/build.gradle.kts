// feature:newproject — assistant de création de projet (wizard, étape 10).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // @RequiresApi du rendu des champs dynamiques (audit étape 18 : import direct).
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Tests du ViewModel : horloge virtuelle et fakes.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
