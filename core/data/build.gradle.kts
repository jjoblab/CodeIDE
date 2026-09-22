// Module core:data — implémentations des repositories (étape 4).
// Assemble les sources de données (Room, DataStore, SAF) derrière les
// interfaces du domaine, avec journalisation par identifiants.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Les interfaces du domaine (ProjectRepository, SettingsRepository,
    // FileSystem, AppLogger) apparaissent dans les signatures publiques.
    api(project(":core:domain"))

    // Sources de données assemblées ici uniquement (section 5.2).
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:storage"))

    // Journalisation des opérations (identifiants uniquement, règle 15).
    implementation(project(":core:logging"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    // Fakes des testes (FakeAppLogger) et TestDispatcherProvider.
    testImplementation(project(":core:testing"))

    // Bases en mémoire et DataStore réels pour les tests des repositories.
    testImplementation(libs.room.runtime)
    testImplementation(libs.androidx.datastore)
}
