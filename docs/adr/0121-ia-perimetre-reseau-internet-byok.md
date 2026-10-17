# ADR 0121 — IA : périmètre réseau, permission INTERNET et modèle « apporte ta clé »

- **Statut** : proposé (à accepter par le propriétaire avant le lot IA-0)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab (propriétaire du dépôt)
- **Étend** : ADR 0034 (« INTERNET : unique usage réseau = installateur du bootstrap »).
  L'ADR 0034 impose qu'un nouvel usage réseau exige une ADR dédiée : la voici.
- **Contexte** :
  - L'application déclare déjà `INTERNET` (ADR 0034) ; le seul trafic actuel
    est le bootstrap (releases `codeide-packages`, dépôt APT) et la résolution
    de versions Maven (`ClientMavenHttp`). Aucun streaming, aucune authentification
    par clé, aucune donnée utilisateur ne quitte l'appareil.
  - Les fonctionnalités IA envoient du **code source de l'utilisateur** à un tiers
    (le fournisseur de modèle). C'est un changement de nature : confidentialité,
    secrets, coût financier pour l'utilisateur.
  - Il n'existe aucun backend CodeIDE et le propriétaire ne souhaite pas en opérer un.
  - `targetSdk = 28` : le trafic en clair est déjà interdit par défaut à partir de
    l'API 28, mais rien ne l'explicite dans le dépôt (aucun `networkSecurityConfig`).

## Décision

1. **Modèle BYOK (« bring your own key »)** : l'utilisateur fournit sa propre clé
   d'API et choisit son fournisseur. CodeIDE n'opère aucun serveur, ne relaie rien,
   ne collecte rien.
2. **Un seul point de sortie réseau IA** : l'interface `AiHttpTransport`
   (module `:ai:providers`). Aucun autre code n'ouvre de connexion pour l'IA.
3. **Initiative utilisateur uniquement** : aucun appel en arrière-plan, aucun
   pré-chargement, aucune télémétrie, aucun envoi automatique de rapports.
   Chaque requête IA découle d'une action explicite (envoi d'un message,
   « Demander à l'IA », « Corriger avec l'IA », lancement d'un agent).
4. **IA désactivée par défaut** (opt-in). Premier usage = écran de consentement
   listant précisément ce qui est envoyé (maquette écran 5). Le consentement est
   révocable dans Paramètres → IA.
5. **HTTPS obligatoire** pour tout fournisseur distant. Exception unique et
   explicite : boucle locale (`localhost`, `127.0.0.1`) pour un modèle servi sur
   l'appareil (ex. serveur compatible OpenAI exécuté dans l'environnement PRoot).
   Un serveur du réseau local doit être joint en HTTPS.
6. **`res/xml/network_security_config.xml` explicite** dans `:app` : trafic en clair
   interdit par défaut (`base-config cleartextTrafficPermitted="false"`), exception
   `domain-config` limitée à la boucle locale. Le manifeste y fait référence.
7. Les usages réseau IA s'ajoutent à ceux de l'ADR 0034 : la phrase « unique usage
   réseau » de `AGENTS.md`, `README.md` et `docs/ARCHITECTURE.md` est mise à jour
   (« deux usages : installation du bootstrap ; fournisseur IA choisi par l'utilisateur »).

## Options considérées

### Option A — BYOK direct vers le fournisseur (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (adaptateurs HTTP + coffre de clés) |
| Coût | Nul pour le propriétaire ; payé par l'utilisateur chez son fournisseur |
| Scalabilité | Illimitée (pas de serveur) |
| Familiarité | Élevée (client `HttpURLConnection` déjà maîtrisé, ADR 0033) |

**Avantages** : zéro infrastructure, zéro donnée chez CodeIDE, argument de
confidentialité fort, compatible avec tout fournisseur « compatible OpenAI ».
**Inconvénients** : l'utilisateur gère sa clé et ses coûts ; pas de quota
mutualisé ; support dépendant de formats tiers qui évoluent.

### Option B — Backend relais opéré par le propriétaire
| Dimension | Évaluation |
|---|---|
| Complexité | Haute (auth, quotas, facturation, conformité) |
| Coût | Récurrent et variable |
| Scalabilité | À construire |
| Familiarité | Faible pour un projet 100 % mobile |

**Avantages** : expérience « ça marche sans clé ». **Inconvénients** : le
propriétaire devient responsable des données de code des utilisateurs, des coûts
et de la conformité ; contredit la posture « aucune télémétrie » de l'ADR 0034.

### Option C — Modèle embarqué uniquement (sur l'appareil)
Hors périmètre de ce chantier (taille d'APK, mémoire, performances). **Reste
compatible** : un serveur local compatible OpenAI sur la boucle locale est un
fournisseur comme un autre (point 5).

## Analyse des compromis
A retire toute charge d'exploitation et préserve la promesse de confidentialité,
au prix d'une configuration initiale plus longue. L'écran de consentement et
l'assistant de configuration (maquette écrans 4 et 5) compensent ce coût.

## Conséquences
- **Plus facile** : confidentialité démontrable ; aucune facture pour le propriétaire.
- **Plus difficile** : parcours de première utilisation (obtenir une clé) ; erreurs
  fournisseur à traduire proprement (401, 429, 5xx, hors-ligne).
- **À revoir** : un éventuel mode « modèle local géré par l'app » (nouvelle ADR).

## Actions
1. [ ] Accepter cette ADR (statut → accepté) avant tout code IA.
2. [ ] Ajouter `network_security_config.xml` + référence manifeste (lot IA-0).
3. [ ] Mettre à jour `AGENTS.md`, `README.md`, `docs/ARCHITECTURE.md` (permission INTERNET : deux usages).
4. [ ] Implémenter l'écran de consentement (lot IA-2) et le test « aucune requête sans action utilisateur » (lot IA-9).
