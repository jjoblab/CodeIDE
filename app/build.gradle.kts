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
        // Pont de journaux des apps exécutées (mission « Exécuter » R2,
        // ADR 0103) : interfaces AIDL partagées avec applog-runtime
        // (fichiers jumeaux — même paquet, même protocole).
        aidl = true
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
    // Previews PE6-0 : écran d'aperçu XML dédié (AppNavigator.openPreview).
    implementation(project(":feature:preview"))

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
    // Tooling G4 : agrégation Hilt du daemon (déploiement du JAR, lancement
    // du process orchestrateur, health check, relances bornées) — le daemon
    // démarre avec le processus principal et s'arrête avec lui (le socket
    // fermé par la mort de l'app termine proprement l'orchestrateur).
    implementation(project(":tooling:daemon"))

    // Mission « LSP Java » étape 4 h2 (§16.2) : liaison Hilt des coutures
    // de l'hôte LSP (SourceClasspathLsp sur l'accesseur protocole BRUT de
    // tooling:client + DossierIndexLsp sur filesDir/codeide/lsp/index) —
    // l'app est le SEUL point où tooling et hôte se rencontrent (règle
    // §2.2 : les features ne voient que les types de :lsp:host). L'hôte
    // vit dans le dépôt séparé jjoblab/codeide-lsp depuis la bascule du
    // 2026-10-17 (plan §9) : composite en dev (substitution), binaire sinon.
    implementation(libs.lsp.host)

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
// ARTEFACT DE BUILD — il vit dans le dépôt séparé jjoblab/codeide-tooling
// depuis la bascule du 2026-10-17 (docs/PLAN_SEPARATION_DEPOTS.md §9).
// Deux sources selon le MODE résolu par settings.gradle.kts :
//  - COMPOSITE (frère présent) : tâche inter-builds — le shadowJar du
//    build inclus est construit puis recopié vers assets/tooling
//    (répertoire gitignoré, régénéré à chaque build) ;
//  - BINAIRE (frère absent, jeton Packages fourni) : la coordonnée
//    com.github.jjoblab.codeide-tooling:server résout le JAR orchestrateur
//    PUBLIÉ (composant shadow = gradle-server.jar, artefact principal
//    « -all » du registre) et la copie le renomme. Pas de dépendance de
//    module : l'app ne consomme le tooling que via l'interface du domaine
//    (règle §2.2).
// ---------------------------------------------------------------------------
// Même résolution que settings.gradle.kts (propriété tooling.dir → env
// CODEIDE_TOOLING_DIR → défaut ../codeide-tooling).
val dossierTooling: File =
    providers.gradleProperty("tooling.dir")
        .orElse(providers.environmentVariable("CODEIDE_TOOLING_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-tooling"))
        .get()

if (dossierTooling.resolve("settings.gradle.kts").isFile) {
    tasks.register<Copy>("copierJarServeurAssets") {
        group = "build"
        description =
            "Recopie le JAR orchestrateur du dépôt frère codeide-tooling vers les assets (§4.7 du prompt Tooling)."
        from(dossierTooling.resolve("tooling/server/build/libs/gradle-server.jar"))
        into(rootProject.file("app/src/main/assets/tooling"))
        dependsOn(gradle.includedBuild("codeide-tooling").task(":tooling:server:shadowJar"))
    }
} else {
    // Mode binaire : configuration de COPIE dédiée (non transitive — le
    // JAR shadow est autosuffisant), résolue depuis GitHub Packages par le
    // registre configuré dans settings.gradle.kts.
    val configurationJarServeur = configurations.create("jarServeurAssets") {
        description = "JAR orchestrateur du tooling (mode binaire — GitHub Packages)."
        isTransitive = false
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        add("jarServeurAssets", libs.tooling.server)
    }
    tasks.register<Copy>("copierJarServeurAssets") {
        group = "build"
        description =
            "Recopie le JAR orchestrateur (résolu du registre Packages) vers les assets (§4.7 du prompt Tooling)."
        from(configurationJarServeur)
        into(rootProject.file("app/src/main/assets/tooling"))
        rename { "gradle-server.jar" }
    }
}

tasks.register("controlerJarServeur") {
    group = "verification"
    description = "Contrôle §4.7 : le JAR orchestrateur (codeide-tooling) est présent et non vide dans les assets."
    // Référence PAR NOM : la tâche est enregistrée par les DEUX branches
    // (composite et binaire) — la val locale de chaque branche est hors
    // de portée ici.
    dependsOn("copierJarServeurAssets")
    val cible = rootProject.file("app/src/main/assets/tooling/gradle-server.jar")
    doLast {
        if (!cible.isFile) {
            throw GradleException(
                "JAR orchestrateur absent des assets : $cible (construit par le dépôt frère codeide-tooling)",
            )
        }
        if (cible.length() == 0L) {
            throw GradleException("JAR orchestrateur vide dans les assets : $cible")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("controlerJarServeur")
    // Mission « Exécuter » R2 (ADR 0103) : jamais d'APK sans la bibliothèque
    // applog-runtime non plus — elle voyage dans les assets et devient le
    // dépôt maven local du daemon.
    dependsOn(":applog-runtime:controlerAarAssets")
}
