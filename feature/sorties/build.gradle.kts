// Module feature — voir README.md et docs/ARCHITECTURE.md.
// Centre de sorties (ADR 0162, lot L2) : le lecteur unifié — composant de
// lecture virtuel (ADR 0159 : RecyclerView sur le modèle de lignes, SANS
// DiffUtil, append par lot et notifications bornées), contrôles du lecteur
// (recherche n/N, filtre unifié, pause/reprise, zoom, horodatage, numéros,
// retour à la ligne), source factice et banc de mesure S1/S2/S6.
// Branché sur UNE SOURCE FACTICE uniquement : les raccords Gradle, Logcat
// et Journal arrivent aux lots L3/L4 (ADR 0162 « Conséquences »).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Logique JVM pure du lecteur (projection de rangées, source factice,
    // banc) : JUnit 4 avec noms français en backticks.
    testImplementation(libs.junit4)
    // Comportement du composant (liaison des rangées, suivi du bas,
    // TalkBack, poignée de défilement) — patron des modules feature.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Fakes et règles de dispatchers de test (core:testing est réservé
    // aux configurations de test — vérifié par checkModuleDependencies).
    testImplementation(project(":core:testing"))
}
