// feature:install — écran d'installation du bootstrap natif (Terminal T3).
// Autonome et déclenchable à la demande ; l'étape « Terminal » de
// l'onboarding et le bandeau de l'accueil y naviguent (prompt Terminal-1,
// sections 3.4 et 6 : la progression est partagée, jamais rejouée).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Tests du ViewModel : fake de l'installateur (core:testing).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
