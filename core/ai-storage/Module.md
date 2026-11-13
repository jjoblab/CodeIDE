# core/ai-storage

Bibliothèque Android (mission IA, ADR 0122) : `KeyVault` (Android
Keystore, AES-256-GCM, alias `codeide.ai.vault.v1`, blobs dans
`Context.noBackupFilesDir` — ADR 0124 §1) et `AiSettingsStore` (DataStore
dédié, fichier `ai_settings.preferences_pb`).

Les clés ne quittent JAMAIS l'appareil : exclues de la sauvegarde et du
transfert (data_extraction_rules.xml / backup_rules.xml), jamais
journalisées (LogRedactor), jamais dans un état d'UI.

Statut : IA-0 (v0.135.0) — échafaudage ; `KeyVault` et `AiSettingsStore`
au lot IA-2.
