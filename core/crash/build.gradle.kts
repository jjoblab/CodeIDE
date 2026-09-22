// Module core:crash — gestion des plantages (section 5.8 du prompt maître) :
// gestionnaire non bloquant, rapport JSON atomique, détection de boucle,
// CrashActivity en processus séparé et dépôt de rapports.
//
// NB : ce module n'a JAMAIS de dépendance vers core:logging — la liaison
// (filons de pain, vidage) passe par des lambdas fournies par app (règle
// de la section 5.8).

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

android {
    buildFeatures {
        // CrashActivity lit son écran via ViewBinding (sans Hilt).
        viewBinding = true
    }
}

dependencies {
    // Implémente l'API du domaine (CrashReportRepository,
    // PendingExitInfoRecorder) : types dans les signatures publiques.
    api(project(":core:domain"))

    // Thème Material 3 de CrashActivity et AppNavigator partagé.
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // registerForActivityResult (enregistrement SAF du rapport).
    implementation(libs.androidx.activity)
    implementation(libs.androidx.material)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Fakes et utilitaires de test (autorisés en configuration de test).
    testImplementation(project(":core:testing"))
    // org.json réel pour les tests JVM purs : les classes du framework sont
    // des stubs « not mocked » hors Robolectric (section 5.8 : le mapping
    // JSON doit être éprouvé sans démarrer Android).
    testImplementation(libs.org.json)
}
