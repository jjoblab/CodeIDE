// tools:generateur — harnais de génération sur disque (étape 9, ADR 0019).
//
// Outil en ligne de commande qui produit des projets depuis les VRAIS assets
// embarqués (app/src/main/assets) en réutilisant le moteur de templates de
// core:domain : ce que le harnais écrit est exactement ce que l'application
// écrirait (plan figé de PlanProjectCreationUseCase, ADR 0017).

plugins {
    id("codeide.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core:domain"))

    // Lecture du fichier de combinaisons JSON produit par verify-templates.sh.
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("jo.codeide.tools.generateur.GenerateurModelesKt")
}
