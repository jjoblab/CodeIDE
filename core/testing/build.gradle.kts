// core:testing — fakes et utilitaires de test (section 5.1).
// Consommé uniquement via testImplementation (vérifié par checkModuleDependencies).

plugins {
    id("codeide.kotlin.library")
}

dependencies {
    // TestDispatcher / TestWatcher apparaissent dans la signature publique
    // du module : api. Les consommateurs en ont besoin pour compiler.
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)

    // TestDispatcherProvider implémente DispatcherProvider : api.
    api(project(":core:domain"))
}
