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

    // Régression de layout (correctif v0.19.0) : gonfler le vrai
    // activity_editor.xml sous Robolectric — un <menu> inline y faisait
    // planter LayoutInflater (rapport 8b5b73f1). Mêmes versions que le
    // module app, déjà vérifiées dans le catalogue.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
