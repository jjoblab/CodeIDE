# ADR 0156 — Mutualisation des fichiers communs des modèles (source partagée)

- **Statut** : accepté (chantier générateur/Nouveau projet — lot L0 ;
  à implémenter en L3/L4 selon le lot qui touche le premier les modèles)
- **S'appuie sur** : ADR 0017 (plan figé), ADR 0019 (port
  `TemplateAssetsSource`, harnais), ADR 0089 (alignement catalogue).
- **Contexte** : l'audit L0 chiffre la duplication embarquée :
  `gradle-wrapper.jar` existe en **8 copies strictement identiques**
  (47 505 octets, MD5 `005661537a7f0df8d1bd8b075612daf4`), `gradlew.tpl`,
  `gradlew.bat.tpl`, `gradle/wrapper/wrapper.properties.tpl`,
  `gitattributes.tpl` et `editorconfig.tpl` sont identiques dans les
  **7 modèles** ; deux fichiers sont **orphelins** (non référencés par leur
  manifeste) : `android-app/files/gradle-wrapper.jar` et
  `android-library/files/library/proguard-rules.pro.tpl`. Chaque nouvelle
  famille de modèles recopie le paquet.

## Décision

### 1. Répertoire partagé `assets/templates/_commun/`

- `app/src/main/assets/templates/_commun/` porte les sources communes
  (`gradlew.tpl`, `gradlew.bat.tpl`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/wrapper/wrapper.properties.tpl`, `gitattributes.tpl`,
  `editorconfig.tpl`, futurs blocs partagés).
- Un manifeste référence une source partagée par un **préfixe de schéma** :
  `"source": "commun:gradle/wrapper/gradle-wrapper.jar"`. Le contrat
  `TemplateManifestParser` valide la forme ; la **résolution** vit dans les
  deux implémentations du port :
  - `AssetTemplateAssetsSource` (app) → `assets/templates/_commun/<chemin>`,
  - `FichierTemplateAssetsSource` (outil) →
    `<racine>/templates/_commun/<chemin>`,
  avec les **mêmes gardes** qu'aujourd'hui (pas d'absolu, pas de `\`, pas de
  `..`, pas de `:` côté disque ; `NotFound` typé). Le moteur, le plan figé
  (ADR 0017) et la garde de chemins de sortie ne changent pas — seule la
  *lecture de la source* est redirigée. Dry-run = écriture, inchangé.
- La constante `commun:` est partagée par les deux implémentations (copie
  documentée, testée à l'identique des deux côtés, comme les gardes
  existantes) pour respecter la règle « même résolution, même garde ».

### 2. Suppression des orphelins (A7)

`android-app/files/gradle-wrapper.jar` (orphelin) est supprimé (le manifeste
pointe déjà `files/gradle/wrapper/gradle-wrapper.jar`) ;
`android-library/files/library/proguard-rules.pro.tpl` est **référencé ou
supprimé en L4** (décision au fil du lot : la cible utile est
`consumer-rules.pro` déjà câblé — suppression retenue, sauf usage retrouvé).

### 3. Arbitrage chiffré (§9-Q3 : oui)

| Élément | Avant | Après | Gain |
|---|---|---|---|
| `gradle-wrapper.jar` | 8 × 47 505 o | 1 copie | ≈ 332 Ko d'APK |
| 5 textes communs (`gradlew*`, wrapper.properties, gitattributes, editorconfig) | 7 × ≈ 16 Ko | 1 copie | ≈ 96 Ko |
| Risque de divergence | réel (2 copies orphelines déjà) | nul (source unique) | — |

Le coût moteur/outil est faible (préfixe + 2 implémentations + tests) face
au gain (≈ 428 Ko d'APK) et à la disparition d'un risque de divergence.
Le lint `valider-manifestes` (ADR 0157) vérifie l'existence de toute source
`commun:` et interdit à un modèle de redéfinir un chemin déjà servi par la
source partagée (pas de doublon sémantique).

## Conséquences

- Les modèles perdent leurs copies ; leurs manifestes pointent `commun:`.
- `ModelesEmbarquesTest` (checksum du wrapper embarqué) et la structure du
  harnais sont mis à jour dans le lot qui porte la migration.
- `_commun` n'est **pas** un modèle : il n'a pas de `template.json`, il est
  ignoré par le listage des modèles (déjà vrai : répertoire sans manifeste).
- `templateVersion` incrémenté pour chaque modèle migré.
