// Module core:database — persistance Room (étape 4 : registre des projets).
// Schémas exportés dans schemas/ par la convention codeide.android.room.

plugins {
    id("codeide.android.library")
    id("codeide.android.room")
    id("codeide.android.hilt")
}

// Tests de migration (lot IA-6) : MigrationTestHelper lit les schémas
// exportés comme assets de test — les fichiers versionnés de schemas/
// sont committés et servent de référence.
android {
    sourceSets {
        getByName("test") {
            assets.srcDir(layout.projectDirectory.dir("schemas"))
        }
    }
}

dependencies {
    // Les entités se mappent vers le modèle (Project, ProjectId…) : le
    // type apparaît dans l'API publique des mappeurs.
    api(project(":core:model"))
    // Historique IA (lot IA-6) : le port ConversationStore vit dans le
    // domaine, l'implémentation Room expose ses types (ConversationIa…).
    api(project(":core:domain"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    // Migration 1→2 testée sur le schéma v1 exporté (lot IA-6).
    testImplementation(libs.room.testing)
}
