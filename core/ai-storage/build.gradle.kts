// :core:ai-storage — stockage IA (mission IA, ADR 0122) : `KeyVault`
// (Android Keystore, AES-256-GCM, alias versionné `codeide.ai.vault.v1`,
// blobs dans `Context.noBackupFilesDir` — ADR 0124 §1) et
// `AiSettingsStore` (DataStore dédié, fichier `ai_settings.preferences_pb`
// — nom contractuel exclu de la sauvegarde par data_extraction_rules.xml
// et backup_rules.xml).
//
// Bibliothèque Android (le Keystore et le DataStore sont des API
// plateforme) : la logique métier IA reste dans :core:ai — ce module ne
// fait que PERSISTER. Aucune donnée IA n'est envoyée, journalisée ou
// copiée ailleurs (ADR 0124).
//
// IA-0 : échafaudage — module vide compilable ; `KeyVault` et
// `AiSettingsStore` arrivent au lot IA-2 (ADR 0124 §1, avec procédure
// manuelle appareil dans docs/TESTS_MANUELS.md).

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Contrat du domaine IA (ports à persister) — api : les signatures
    // publiques du stockage exposent des types de :core:ai.
    api(project(":core:ai"))
    api(project(":core:model"))
    api(project(":core:domain"))
    // Patron DataStore existant (gestion de corruption, projection).
    implementation(project(":core:datastore"))

    // Dépendances de test (robolectric, turbine…) ajoutées au lot IA-2
    // avec les premiers tests réels de KeyVault/AiSettingsStore — un
    // module échafaudé sans test ne déclare pas de classpath de test
    // (échec failOnNoDiscoveredTests, AGP 9).
}

// AGP 9 (« failOnNoDiscoveredTests ») : un module échafaudé sans test
// fait échouer testDebugUnitTest — les premiers tests réels (KeyVault,
// AiSettingsStore) arrivent au lot IA-2. Le drapeau est borné à CE
// module échafaudé ; aucune autre vérification n'est touchée.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}
