// Module feature — voir README.md et docs/ARCHITECTURE.md.
// Previews PE6-0 (maquette docs/mockups/apercu-xml.html) : PreviewActivity,
// l'écran dédié de l'aperçu XML — le panneau quitte l'overlay d'EditorActivity,
// sur le patron de :feature:terminal (activité empilée via AppNavigator).

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Moteur de preview XML (PE1, PREVIEW.md §3) : MoteurPreviewXml,
    // VuePreviewXml, ClassificateurPreview, SelectionPreview — la
    // dépendance LayoutLib y est CONFINÉE (la feature ne la voit jamais,
    // règle du module : la feature porte le chrome Material, le moteur
    // reste zéro-Material).
    implementation(project(":preview:xml"))

    // Persistance de la bulle et du mode de la palette (PE7c — maquette
    // docs/mockups/apercu-xml.html § « Bulle, palette et popup de
    // placement » : « son mode et sa position sont enregistrés dans
    // SettingsDataStore »).
    implementation(project(":core:datastore"))

    // Tests unitaires du ViewModel (branches SourceApercu du registre).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json réel pour le test de parité des jetons (JetonsMaquetteTest,
    // JVM pur : les classes org.json du stub android.jar lèvent).
    testImplementation(libs.org.json)
    // Le ViewModel construit une SettingsDataStore RÉELLE sur un fichier
    // temporaire (miroir SettingsDataStoreTest) : l'artefact DataStore est
    // `implementation` dans :core:datastore — invisible en test ici.
    testImplementation(libs.androidx.datastore)
    testImplementation(project(":core:testing"))

    // Régression du layout (miroir :feature:editor) : gonfler le vrai
    // activity_preview.xml sous Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
