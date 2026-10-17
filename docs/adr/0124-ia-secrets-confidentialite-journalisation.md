# ADR 0124 — IA : clés d'API, confidentialité des données et journalisation

- **Statut** : accepté (propriétaire, 2026-10-18 — « GO lot IA-0 »)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0121. **Contraint par** : règle 7 (aucun secret dans le dépôt),
  règle 11 (aucune donnée personnelle dans journaux et rapports de plantage),
  ADR 0009 (expurgation à l'écriture), `LogRedactor`.
- **Contexte** (constats vérifiés dans le dépôt, v0.134.41) :
  - Aucun usage d'`AndroidKeyStore` : le dépôt n'a jamais stocké de secret utilisateur.
  - `AndroidManifest.xml` (`<application>`) ne déclare ni `allowBackup`, ni
    `dataExtractionRules`, ni `fullBackupContent` : la sauvegarde automatique Android
    est donc **active par défaut** pour tous les fichiers de l'application.
  - `LogRedactor` expurge courriels, URI `content://` et `file://`, chemins absolus et
    hachages ; **aucun motif de clé/jeton** (`sk-…`, `Bearer …`, `x-api-key`, `AIza…`).
    Une clé qui transiterait par un message d'erreur ou un en-tête journalisé fuirait
    dans les journaux exportables et les rapports de plantage.
  - Les requêtes IA contiennent du code de l'utilisateur : chemins, noms de variables,
    éventuellement des secrets présents dans des fichiers de configuration.

## Décision

### 1. Coffre de clés (`KeyVault`, module `:core:ai-storage`)
- Clé maître **AES-256-GCM générée dans l'Android Keystore** (alias versionné
  `codeide.ai.vault.v1`, non exportable). Chiffrement de chaque clé d'API avec IV
  aléatoire de 12 octets ; blob = `version ‖ IV ‖ texte chiffré+étiquette`.
- Blobs stockés dans `Context.noBackupFilesDir` (**exclu de la sauvegarde** par construction),
  jamais dans DataStore, jamais dans `SharedPreferences`, jamais en clair.
- Le texte en clair n'existe qu'en mémoire, le temps d'une requête (`CharArray` effacé
  après usage quand c'est possible) ; il n'est **jamais** copié dans un état d'UI,
  un `SavedStateHandle`, un `Bundle` ou un `Intent`.
- UI : champ masqué, aperçu `••••` + 4 derniers caractères, **pas de bouton « afficher »**
  ni de copie ; remplacement ou suppression uniquement. Collage depuis le presse-papiers
  autorisé, avec nettoyage du presse-papiers proposé.
- Perte de la clé Keystore (réinitialisation de l'appareil, invalidation) : le blob devient
  illisible → état « Clé à ressaisir », **aucun plantage** (erreur modélisée `AppError`).

### 2. Sauvegarde et extraction
- Ajouter `res/xml/data_extraction_rules.xml` (API 31+) et `res/xml/backup_rules.xml`
  (API ≤ 30) excluant : le fichier DataStore des réglages IA et le répertoire
  `no_backup`. Références dans le manifeste.

### 3. Journalisation
- Étendre `LogRedactor` avec des motifs : `sk-[A-Za-z0-9_-]{16,}`, `Bearer\s+\S+`,
  `x-api-key\s*[:=]\s*\S+`, `AIza[0-9A-Za-z_-]{20,}`, `Authorization\s*[:=]\s*\S+`,
  et un motif générique `[A-Za-z0-9_-]{32,}` **uniquement** dans les messages issus du
  transport IA. Tests obligatoires (un cas par motif + faux positifs).
- Le transport journalise méthode, hôte, statut, durée, nombre de jetons — **jamais**
  en-têtes d'authentification, corps de requête, ni corps de réponse.
- Le contenu des conversations n'est **jamais** journalisé.

### 4. Données envoyées (minimisation)
- Contexte envoyé = ce que l'utilisateur voit dans la puce de contexte (fichier actif,
  sélection, diagnostics, fichiers `@mentionnés`). Rien d'implicite en plus du
  **fichier actif** et de la **liste des chemins du projet** (jamais leur contenu)
  — et chacun est désactivable dans Paramètres → IA → Confidentialité.
- **Liste d'exclusion par défaut** (jamais envoyés, même mentionnés, avec message
  explicite) : `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`,
  `*.p12`, `*.pem`, `.env`, `.env.*`, `.gh-token`, `google-services.json`,
  `secrets.*`, dossier `.git/`, dossiers `build/`.
  Fichier optionnel `.codeide/ia-ignore` (globs) pour étendre la liste par projet.
- Détection préventive : avant envoi, balayage du contexte par motifs de secrets
  (mêmes motifs que `LogRedactor`) → avertissement bloquant « Ce contexte semble contenir
  un secret » avec **Envoyer sans** / **Annuler**.
- Données jamais envoyées : chemins SAF/FUSE absolus de l'appareil (réécrits en chemins
  relatifs au projet), identité git, courriel de l'utilisateur.

### 4 bis. Injection de consignes (contenu non fiable)
Tout contenu de fichier, de sortie d'outil ou de réponse web est **donnée**, jamais
consigne : encadré dans des balises neutres par `ContextAssembler`, et les actions
sensibles restent soumises à approbation (ADR 0125) quoi que le contenu « demande ».

### 5. Stockage local des conversations
Local uniquement (Room, lot IA-6), purge dans Paramètres → IA → Historique,
rétention configurable (7 j / 30 j / illimitée), action « Tout effacer ».

## Options considérées

### Option A — Android Keystore direct + blobs dans `noBackupFilesDir` (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (~150 lignes + tests instrumentés/Robolectric limités) |
| Coût | Aucune dépendance |
| Scalabilité | Adapté à N clés (une par fournisseur) |
| Familiarité | Faible (première utilisation du Keystore dans le dépôt) |

### Option B — `androidx.security:security-crypto`
Bibliothèque dépréciée : ne pas bâtir un nouveau chantier dessus. **Écartée.**

### Option C — Clé en clair dans DataStore
Simple mais indéfendable : lisible par sauvegarde, par un appareil rooté, par un export
de données. **Rejetée.**

## Analyse des compromis
A demande d'écrire et de tester du code cryptographique, mais il tient dans une classe
(`KeyVault`) à interface minimale (`put/get/delete/has`), entièrement couverte de tests
JVM via une implémentation de remplacement du Keystore et testée sur appareil réel
(procédure `docs/TESTS_MANUELS.md`).

## Conséquences
- **Plus facile** : audit de confidentialité clair (un seul endroit manipule les clés).
- **Plus difficile** : récupération après perte de clé = ressaisie ; pas de synchronisation
  entre appareils (voulu).
- **À revoir** : verrouillage biométrique optionnel de l'accès à la clé (évolution).

## Actions
1. [ ] `data_extraction_rules.xml` + `backup_rules.xml` + manifeste (lot IA-0).
2. [ ] Extension de `LogRedactor` + tests (lot IA-0, **avant** tout code réseau).
3. [ ] `KeyVault` + tests + procédure manuelle appareil (lot IA-2).
4. [ ] `ContextAssembler` : exclusions, détection de secrets, réécriture des chemins (lot IA-4).
5. [ ] Test d'architecture : aucun log ne reçoit un en-tête `Authorization` (lot IA-9).
