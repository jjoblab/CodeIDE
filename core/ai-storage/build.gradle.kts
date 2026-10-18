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
    // API DataStore déclarée directement : le frère la garde « implementation »
    // et nos signatures publiques (AiSettingsStore) l'exposent.
    implementation(libs.androidx.datastore)

    // Tests : coffre (clé maître logicielle de remplacement du Keystore),
    // store réglages (patron SettingsDataStoreTest), source de clé.
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
