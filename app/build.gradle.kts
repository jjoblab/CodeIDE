// Module d'application : assemblage final de CodeIDE (section 5.1).
// La version, l'identifiant et le SDK proviennent des conventions et de
// version.properties — aucune valeur dupliquée ici.

plugins {
    id("codeide.android.application")
    id("codeide.android.hilt")
}

android {
    buildFeatures {
        // BuildInfo du système de journalisation : VERSION_NAME/VERSION_CODE
        // alimentent l'en-tête de session et device-info.txt (section 5.7).
        buildConfig = true
    }
}

dependencies {
    // Écrans et socle visuel.
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))

    // Assistant de premier lancement (étape 5) : destination du graphe
    // de navigation et chaînes localisées de l'assistant.
    implementation(project(":feature:onboarding"))

    // Wizard de création de projet (étape 7 : destination placeholder ;
    // assistant complet à l'étape 10).
    implementation(project(":feature:newproject"))
    // Écran Diagnostic (étape 12) : visionneuse journaux + plantages.
    implementation(project(":feature:diagnostics"))
    // Espace de travail de l'éditeur (étape 13) : EditorActivity séparée.
    implementation(project(":feature:editor"))
    implementation(project(":feature:install"))

    // Journalisation maison (étape 2) : AppLogger, initialisation,
    // FileProvider des exports.
    implementation(project(":core:logging"))
    implementation(project(":core:domain"))

    // Gestion des plantages (étape 3) : installation du gestionnaire,
    // CrashActivity et FileProvider du processus :crash.
    implementation(project(":core:crash"))

    // Couche données (étape 4) : assemblage final — les repositories du
    // domaine y sont liés aux sources réelles (Room, DataStore, SAF).
    implementation(project(":core:data"))
    // Terminal T3 : agrégation Hilt du module bootstrap (ports du domaine).
    implementation(project(":core:bootstrap"))
    // Terminal T4 : agrégation Hilt du registre des sessions + manifeste
    // du service foreground (fusion dans l'application finale).
    implementation(project(":core:terminal-runtime"))
    // Terminal T5 : écran plein écran du terminal (destination de
    // navigation ouverte par les points d'entrée de T6).
    implementation(project(":feature:terminal"))
    // Tooling G3 : agrégation Hilt du client tooling (liaison
    // GradleToolingRepository → GradleApiImpl dans le graphe final). L'app
    // ne consomme toujours le tooling que via l'interface du domaine
    // (règle §2.2) — le JAR orchestrateur, lui, reste un artefact de build
    // lié par tâche (voir preBuild plus bas).
    implementation(project(":tooling:client"))

    implementation(libs.androidx.appcompat)
    // enableEdgeToEdge() — contenu tendu sous les barres système.
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.material)

    // Diagnostics en debug uniquement (jamais en release).
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Lancement de l'activité réelle sous Robolectric avec Hilt.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    // Fakes des tests exhaustifs des modèles embarqués (étape 9).
    testImplementation(project(":core:testing"))
}

// ---------------------------------------------------------------------------
// Tooling Gradle (G2, §4.7 du prompt Tooling) : le JAR orchestrateur est un
// ARTEFACT DE BUILD régénéré par :tooling:server (shadowJar → copie vers
// assets/tooling) — la tâche de contrôle fait échouer le packaging s'il est
// absent ou vide. Liaison par TÂCHE (pas de dépendance de module : l'app ne
// consomme le tooling que via l'interface du domaine, règle §2.2).
// ---------------------------------------------------------------------------
tasks.named("preBuild") {
    dependsOn(":tooling:server:controlerJarAssets")
}
