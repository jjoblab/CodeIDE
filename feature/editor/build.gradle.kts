// Module feature — voir README.md et docs/ARCHITECTURE.md.
// Étape 13 : fondations de l'espace de travail (EditorActivity).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Bibliothèque d'édition (prompt compagnon, section 2.2) : `cel-ui`
    // embarque `cel-core` (moteur pur) et `cel-lsp-api` en transitif —
    // `cel-lsp` (serveurs de langage) reste hors périmètre Phase 1.
    // Seule dépendance externe autorisée dans une feature, exception
    // documentée (prompt compagnon, section 3).
    implementation(libs.codeeditor.ui)

    // Hôte LSP Java (mission « LSP Java » étape 4 lot h2, §16.2) :
    // SessionLsp + la définition cel-lsp du serveur Java. La feature ne
    // déclare PAS cel-lsp (l'exception feature reste cel-ui SEULE) :
    // les types LspProject/LspEditor/SessionLsp arrivent par l'API de
    // :lsp:host (sa dépendance cel-lsp est `api` — traversée documentée
    // §16.1 : « la DefinitionServeurLspJava traverse la surface
    // publique, branchement LspProject côté feature à l'étape 4 h2 »).
    implementation(project(":lsp:host"))

    // Contrat de session de projet (mission « Cycle de vie » étape C,
    // ADR 0114) : la feature branche ses ressources (session LSP, flush
    // des sauvegardes) sur GestionnaireSessionProjet/Fermable — la
    // couture DI vit dans di/ModuleSessionProjet.kt.
    implementation(project(":core:session"))

    // Mission « Previews » PE1 (PREVIEW.md §3) : panneau d'aperçu XML —
    // le moteur et la vue vivent dans :preview:xml (la dépendance
    // LayoutLib y est confinée, la feature ne la voit jamais ; PE2 y
    // ajoutera le processus :preview).
    implementation(project(":preview:xml"))

    // Tests du ViewModel : fakes et horloge virtuelle.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))

    // Régression de layout (correctif v0.19.0) : gonfler le vrai
    // activity_editor.xml sous Robolectric — un <menu> inline y faisait
    // planter LayoutInflater (rapport 8b5b73f1). Mêmes versions que le
    // module app, déjà vérifiées dans le catalogue.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
