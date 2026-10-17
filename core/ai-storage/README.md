# core/ai-storage — Stockage IA — coffre de clés et réglages

Bibliothèque Android du chantier IA (ADR 0122, lot IA-2 pour le
contenu). Persistance uniquement — la logique métier reste dans
`:core:ai`.

## Dépendances autorisées

`:core:ai` (api), `:core:model` (api), `:core:domain` (api),
`:core:datastore` (implementation — patron DataStore existant) ;
`:core:database` autorisé par les règles pour l'implémentation du
stockage des conversations (lot IA-6). Interdits : features, réseau.

## Contenu prévu (lot IA-2, ADR 0124 §1)

- `KeyVault` : clé maître AES-256-GCM dans l'Android Keystore (alias
  `codeide.ai.vault.v1`), IV aléatoire 12 octets, blob
  `version ‖ IV ‖ chiffré+étiquette` dans `noBackupFilesDir` ;
  perte de clé → état « Clé à ressaisir », aucun plantage ;
- `AiSettingsStore` : DataStore dédié `ai_settings.preferences_pb`
  (nom contractuel — exclu de la sauvegarde), fournisseur actif, modèle,
  plafonds, consentement, options de confidentialité.

Procédure manuelle appareil réel : `docs/TESTS_MANUELS.md` (IA-2).
