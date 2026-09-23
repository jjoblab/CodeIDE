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

    // Tests du ViewModel : fakes et horloge virtuelle.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
