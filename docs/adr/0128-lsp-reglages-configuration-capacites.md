# ADR 0128 — LSP : réglages utilisateur, API de configuration et pilotage par capacités

- **Statut** : proposé
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **S'appuie sur** : ADR 0127 (composants UI), 0117 (écran d'indexation), 0059 (« chaque contrôle
  projette une API réelle »), 0122 (réglages hors `AppSettings`). **Données** :
  `docs/LSP_REVUE_REGLAGES.md` (L1–L7).
- **Contexte** : depuis l'extraction (`codeide-lsp` v1.0.2), le LSP est un produit à part,
  consommé par coordonnées. Son seul point d'entrée de configuration est le constructeur de
  `SessionLsp` ; le reste est constant. L'application n'a aucun écran de réglages LSP. Les
  trois moteurs n'ont **pas les mêmes capacités** (Java en annonce ~20, Kotlin 10, XML 6) et
  Kotlin/XML sont destinés à devenir des packs de langue téléchargeables.

## Décision

### 1. Une API de configuration côté `codeide-lsp` (`:lsp:host`)
- `ConfigurationLsp` : classe de données **immuable**, `explicitApi`, valeurs par défaut identiques
  au comportement actuel (aucun changement sans configuration). Champs initiaux :
  `surveillanceFichiersMs`, `budgetCacheMo` (`null` = auto), `jdkHome`, `androidJar`.
- `SessionLsp(config = ConfigurationLsp())` ; **`appliquer(config)`** pour le sous-ensemble
  **dynamique** (surveillance, budget) ; le reste = « prend effet à la réouverture du projet »
  (l'UI le dit explicitement).
- Nouvelles opérations : `tailleCacheOctets()`, `viderCache()` (refusée pendant une indexation,
  résultat typé), `capacites(langage)` (liste normalisée des capacités annoncées).
- Version mineure **v1.1.0** (ajouts compatibles) ; CodeIDE ne code **jamais** contre une API
  non publiée (le mode composite sert au développement, pas à la livraison).

### 2. Stockage côté CodeIDE, séparé d'`AppSettings`
`LspSettings` (modèle) + `LspSettingsRepository` (port dans `core:domain`, implémentation
DataStore **dédiée**, `core:datastore`) : budget, période de surveillance, ensemble des
**fonctions désactivées par langage** (vide = tout activé), seuil de gravité des diagnostics.
Aucun champ LSP n'est ajouté à `AppSettings` (≈ 40 champs, dette connue). Le réglage existant
« Badges de diagnostic » **reste** dans Éditeur ; l'écran LSP y renvoie.

### 3. Fonctions par langage **dérivées des capacités annoncées**
La liste d'interrupteurs d'un langage = capacités annoncées par son serveur ∩ capacités que
l'éditeur sait consommer. Conséquences : **Kotlin n'affiche ni « Formatage » ni « Indices en
ligne »** ; XML n'affiche ni « Renommer » ni « Signatures » ; un futur pack de langue apporte ses
capacités sans modifier l'écran. Les interrupteurs agissent **côté client** : `CoordonnateurLsp`
n'enregistre pas (ou ne sollicite pas) le fournisseur correspondant ; aucune modification des
moteurs. Valeur par défaut : **tout activé** ; désactiver ne supprime jamais un diagnostic
bloquant de compilation.

### 4. Écrans (spécification : `docs/LSP_SPEC_ECRANS.md`)
Nouvelle section **`SectionParametres.LANGAGES`** (« Intelligence du code »), placée après
**Éditeur** dans le maître, avec : L1 Vue d'ensemble · L2 Langage (écran unique paramétré
Java/Kotlin/XML) · L3 Index et cache · L4 Environnement d'analyse (lecture seule) · L5 État et
diagnostic. Construits **exclusivement** avec les composants d'ADR 0127 (`RangeeParametre`,
`Widget.CodeIDE.Card`, `IndicateurEtat`, `VueEtat`, `Retour`). **Aucun contrôle sans effet** :
ce qui n'est pas ancré est masqué ou porte la puce « Bientôt » existante.

### 5. Confidentialité et journalisation
« Copier le diagnostic LSP » passe par `LogRedactor` (chemins absolus, courriels, hachages
masqués, règle 11) ; aucun contenu de fichier n'est jamais inclus.

### 6. Gouvernance entre dépôts
- Une **seule numérotation d'ADR**, tenue dans CodeIDE ; `codeide-lsp` y renvoie par lien
  (évite la collision 0119/0120 déjà présente). *Alternative à confirmer par le propriétaire :
  préfixe propre `LSP-NNNN` dans `codeide-lsp`.*
- Les documents `LSP_*.md` ont **une source** (`codeide-lsp`) ; CodeIDE ne garde qu'un renvoi.

## Options considérées

### Option A — `ConfigurationLsp` + store CodeIDE + interrupteurs pilotés par capacités (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (une API côté LSP, un store, des filtres côté client) |
| Coût | 2 dépôts, 1 release ; réglages livrés progressivement |
| Scalabilité | Packs de langue et nouvelles capacités sans refonte d'écran |
| Familiarité | Élevée (patron de l'ADR 0122) |

### Option B — Réglages stockés par le LSP lui-même
Obligerait `:lsp:host` à connaître DataStore/Android UI, alors qu'il est volontairement
indépendant de l'application (`aucun core:*`). **Écartée.**

### Option C — Écran de réglages « vitrine » sans API (interrupteurs ignorés par les moteurs)
Rapide, mais contredit le principe de l'ADR 0059 (chaque contrôle projette une API réelle) et
trompe l'utilisateur. **Écartée.**

### Option D — Liste de fonctions figée dans l'application
Plus simple, mais affiche des fonctions que certains moteurs n'ont pas et casse à chaque pack
de langue. **Écartée** au profit de la dérivation par capacités (§3).

## Analyse des compromis
A demande une release du LSP avant tout écran, ce qui **ordonne** le travail (LSP-1 → LSP-2 →
LSP-3) mais évite tout contrôle factice. La dérivation par capacités ajoute un peu de logique
de normalisation et donne en échange un écran qui reste vrai quand les moteurs évoluent.

## Conséquences
- **Plus facile** : ajouter un pack de langue ; expliquer à l'utilisateur ce que fait chaque réglage.
- **Plus difficile** : coordonner deux dépôts (version consommée à vérifier à chaque montée).
- **À revoir** : profil « Réactivité de l'analyse » (nécessite une API moteur) ; fiabilité de l'estimation d'indexation.

## Actions
1. [ ] LSP-0 : purge règle 13, K1/K2, publication, source unique des docs, numérotation d'ADR.
2. [ ] LSP-1 : `ConfigurationLsp`, `appliquer`, `tailleCacheOctets`, `viderCache`, `capacites` + tests + v1.1.0.
3. [ ] LSP-2 : montée de version vérifiée, `LspSettingsRepository`, câblage `CoordonnateurLsp`.
4. [ ] LSP-3 : `SectionParametres.LANGAGES` + écrans L1–L5 (après UI-1/UI-2).
5. [ ] LSP-4 : test d'architecture « aucun interrupteur sans capacité annoncée ».
