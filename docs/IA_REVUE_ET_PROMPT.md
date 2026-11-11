# Revue de la fonctionnalité IA de CodeIDE — rapport, travaux exigés et prompt agent

> Dépôt : `jjoblab/CodeIDE` — commit `049733d` (v0.173.0) — revue du 2026-10-23.
> Périmètre : `:core:ai`, `:ai:providers`, `:core:ai-storage`, `:feature:ai`, et les points d'accroche
> dans `:app` (navigation) et `:feature:editor` (hôte du tiroir).
> **Méthode** : lecture du code uniquement (rien n'a été exécuté sur appareil ni compilé).
> Statuts : **Vérifié** = constat établi par lecture directe du code cité ; **Probable** = déduit du code,
> à confirmer sur appareil avant de conclure.

---

## 1. Résumé

La base IA est **solide et prudente** : ports/adaptateurs propres, clé API chiffrée par le Keystore,
trafic en clair interdit, détection de secrets, agent borné qui ne fait que *proposer* des écritures,
31 fichiers de test (doubles inclus). Les défauts viennent de la **couche écran** et de la **navigation**,
pas du moteur.

Les deux bugs signalés ne sont pas indépendants. Ils forment une chaîne :

1. depuis le chat de l'éditeur, le bouton « Activer l'assistant » **ne fait rien** (B2a) ;
2. depuis Paramètres → IA, le consentement **se rouvre en boucle** (B2b) ;
3. l'assistant ne peut donc jamais s'activer, et tant qu'il est inactif la zone de saisie est
   **masquée par conception** (B1a) ;
4. même une fois activé, la zone de saisie est **recouverte par un calque opaque** tant que la
   conversation est vide (B1b), puis **masquée par le clavier** (B1c, probable).

Corriger B1 seul ne suffirait pas : il faut traiter B2a, B2b et B1 ensemble.

---

## 2. Problèmes critiques

| # | Fichier | Lignes approx. | Constat | Gravité | Statut |
|---|---------|----------------|---------|---------|--------|
| B1a | `feature/ai/.../ChatFragment.kt` | 173-187 | Tant que `!etat.active`, `zoneChat.isVisible = false` : le compositeur n'existe pas à l'écran. Avec B2, l'utilisateur reste bloqué dans cet état. | 🔴 | Vérifié |
| B1b | `feature/ai/res/layout/fragment_ia_chat.xml` + `ChatFragment.kt` | 173-186 | `etat_transverse` est un `LinearLayout` `match_parent`, fond opaque `?attr/colorSurface`, déclaré **après** `zone_chat` dans le `FrameLayout` : il est dessiné par-dessus. Quand l'IA est active et la conversation vide, le code met `etatTransverse.isVisible = true` **et** `zoneChat.isVisible = true` : le calque recouvre la saisie et le bouton Envoyer, exactement au moment où il faut taper le premier message. | 🔴 | Vérifié |
| B1c | `feature/editor/.../GestionnaireInsetsEditeur.kt` (l. 74-77), `EditorActivity` | — | Le tiroir ne reçoit que `applySystemBarsInsetsMargins()` (barres système). Aucune prise en compte de l'IME, et `ChatFragment` n'appelle aucun utilitaire d'insets (`applyImeBottomInset` n'est utilisé que par `EtapeInformationsFragment`). Le tiroir se termine par un rail de 66 dp : le clavier recouvre le compositeur. | 🟠 | Probable (le code gère l'IME à la main pour l'éditeur, signe que la fenêtre ne se redimensionne pas) |
| B2a | `app/.../AppNavigatorImpl.kt` | 219-226 | `openAiConsentement()` exige `navControllerDHote != null`, or ce contrôleur vaut `null` dans `EditorActivity` (pas de `nav_host_container`). Le bouton « Activer » du chat (`ChatFragment` l. 325) est donc **sans effet** dans l'éditeur (simple `logger.w`). D'autres écrans (assistant, diagnostic, installation) ont un repli `relancerMainActivity(...)`, pas celui-ci ; `RoutageEcran` n'a aucune route IA. | 🔴 | Vérifié |
| B2b | `feature/ai/.../ReglagesIaFragment.kt` | 53-61 | **Boucle du consentement.** L'écouteur de l'interrupteur maître est posé dans `onViewCreated` ; la garde `renduEnCours` ne protège que `rendre()`. À la recréation de la vue (retour du consentement), Android restaure l'état de la vue **après** `onViewCreated` (`onViewStateRestored`) : `MaterialSwitch.setChecked(true)` déclenche l'écouteur avec `renduEnCours == false` → `ChangerActivation(true)` → `OuvrirConsentement` → l'écran se rouvre. Cela se produit aussi après « Activer », puisque la vue fraîchement gonflée est restaurée à « coché ». Aucun interrupteur du layout n'a `saveEnabled="false"` (le seul est le champ de clé du fournisseur). | 🔴 | Probable, forte confiance (mécanisme standard de restauration d'état) |
| B3 | `ReglagesIaFragment.kt` l. 91-96 + `ReglagesIaViewModel.kt` l. 109-115 | — | `texteVersEntier` renvoie `-1` pour un champ vide ou invalide ; `ChangerMaxJetons(-1)` est ramené par `coerceIn` à **128 jetons** (et le délai à 5 s). Vider le champ puis quitter le focus bride silencieusement les réponses. | 🟠 | Vérifié |

---

## 3. Suggestions

| # | Fichier | Suggestion | Catégorie |
|---|---------|------------|-----------|
| S1 | `ReglagesIaFragment.kt` l. 59-61, 62-64 | Interrupteur maître : après « Pas maintenant », l'interrupteur reste visuellement activé alors que `assistantActive == false` (aucun nouvel état n'est émis, donc aucun `rendre`). Le `if (!active) interrupteur.isChecked = false` est un no-op. Remplacer par `setOnClickListener` + resynchronisation sur l'état réel (voir §5, B2b). | Correctitude |
| S2 | `AppNavigatorImpl.kt` l. 77 / garde `DESTINATIONS_IA` | `DESTINATIONS_IA` contient `settings_ia_consentement` : l'écran de consentement peut s'empiler sur lui-même (double toucher). Ajouter `launchSingleTop` ou refuser si la destination courante est déjà le consentement. | Robustesse |
| S3 | `ChatViewModel.kt` | Aucune détection proactive de clé absente : l'utilisateur tape, envoie, puis reçoit une erreur d'authentification. Exposer `cleAbsente` dans `EtatChatIa` (via `CoffreClesIa.contient`) et afficher un état « Ajoutez une clé » avec bouton vers le fournisseur. | UX |
| S4 | `LayoutsIaTest.kt` | Les tests de layout vérifient seulement que les vues se gonflent. Aucun test ne couvre la visibilité par état ni la restauration d'état. C'est pourquoi B1 et B2b ont passé la CI. | Tests |
| S5 | `ChatViewModel.kt` (819 l.), `ControleurAgentIa.kt` (362 l.), `ReglagesIaViewModel.kt` (361 l.) | ViewModels volumineux : extraire des cas d'usage ou des reducers d'état purs, plus faciles à tester. | Maintenabilité |
| S6 | `FournisseurIaViewModel.kt` | Aucune validation d'URL de base repérée par recherche de `http`/`startsWith` (à vérifier : un autre mécanisme existe peut-être). Un `http://` distant est bloqué par `network_security_config` et donne une erreur réseau générique. Valider le schéma à la saisie : `https://`, ou `http://` uniquement pour `localhost`/`127.0.0.1`, avec message explicite. | UX |
| S7 | `IaTiroirFragment.kt` l. 20-21 | KDoc périmé : décrit l'onglet Agent comme « bientôt — IA-7 » alors qu'un `AgentFragment` réel existe. Vérifier l'état réel de l'onglet et aligner la documentation. | Documentation |
| S8 | Onglet « Recherche » | Onglet visible mais « réservé » : acceptable avec le motif « section à venir », mais à valider visuellement (cohérence avec le reste de l'application). | UX |

---

## 4. Ce qui est bien fait (à conserver)

- **Architecture** : `:core:ai` (domaine et ports), `:ai:providers` (HTTP/SSE), `:core:ai-storage`
  (DataStore dédié + coffre), `:feature:ai` (écrans). Un seul point de sortie réseau (`AiHttpTransport`).
  Schéma UDF (actions/effets) cohérent sur tous les ViewModels. ADR 0121 à 0129 tracent les décisions.
- **Sécurité des secrets** : clé AES-256-GCM dans l'`AndroidKeyStore`, blob dans `noBackupFilesDir`,
  l'état d'écran ne porte jamais la clé (aperçu `••••1234` seulement), champ de clé avec
  `saveEnabled="false"`.
- **Réseau** : `instanceFollowRedirects = false` (pas de fuite d'en-tête de clé vers une redirection),
  trafic en clair interdit sauf boucle locale, délais de connexion et de lecture distincts, backoff borné,
  table d'erreurs normalisée.
- **Confidentialité** : détecteur de secrets (sk-, AIza, ghp_, github_pat_, AKIA, Slack, Bearer,
  x-api-key, clés privées PEM) avec dialogue bloquant ; exclusions de fichiers (`.env*`, `*.jks`,
  `*.keystore`, `*.pem`, `local.properties`, `keystore.properties`) ; puces de contexte : ce qui part est
  ce qui est affiché ; aucune case « ne plus demander » ; la révocation efface le consentement.
- **Agent** : boucle bornée (25 étapes, arrêt après échecs d'outils consécutifs), écritures uniquement
  *proposées* (revue par l'utilisateur), approbation demandée **à chaque exécution** de tâche (jamais
  « toujours autoriser »), refus de la sortie de projet, des chemins absolus et des liens symboliques
  sortants, test dédié contre l'injection par contenu piégé.
- **Fournisseurs** : Anthropic Messages + compatible OpenAI (couvre OpenRouter, serveurs locaux, etc.),
  modèle jamais codé en dur, listage des modèles, parseur SSE testé par fichiers dorés.
- **Intégration éditeur** : bouton « Ask AI » dans le menu de sélection, barre de consigne en ligne,
  carte de diff, pré-remplissage depuis Problèmes/Console/Logcat sans envoi automatique.

---

## 5. Travaux exigés

Ordre imposé : les lots 1 à 3 corrigent la chaîne de bugs ; ne pas livrer l'un sans les autres.

### Lot 1 — Navigation : rendre le consentement atteignable (B2a, S2)

- Ajouter dans `RoutageEcran` une route `ECRAN_CONSENTEMENT_IA` (et `ECRAN_FOURNISSEUR_IA` avec
  l'identifiant en extra), les traiter dans `MainActivity` (même mécanique que `ECRAN_PARAMETRES`).
- Dans `AppNavigatorImpl.openAiConsentement()` et `openAiFournisseur()`, remplacer le `return` silencieux
  par le patron déjà utilisé ailleurs : si `navControllerDHote == null`, appeler
  `relancerMainActivity(RoutageEcran.ECRAN_...)`. Conserver le refus quand la destination courante est
  déjà le consentement (`launchSingleTop`).
- **Critère** : depuis le chat de l'éditeur, toucher « Activer l'assistant » ouvre l'écran de consentement ;
  « Activer » ramène à l'éditeur (ou à Paramètres → IA, à documenter) sans perte de l'éditeur.

### Lot 2 — Boucle de consentement (B2b, S1)

Dans `ReglagesIaFragment`, les contrôles qui **reflètent un état persisté** ne doivent réagir qu'à
l'action de l'utilisateur :

```kotlin
// Remplace setOnCheckedChangeListener : setOnClickListener ne se déclenche
// JAMAIS sur setChecked() programmatique ni sur la restauration d'état.
binding.interrupteurIaActive.setOnClickListener {
    val voulu = binding.interrupteurIaActive.isChecked
    viewModel.onAction(ActionReglagesIa.ChangerActivation(voulu))
    // « Activer » ne persiste rien (le consentement le fait) : on rend à
    // l'interrupteur son état réel, rendre() le repassera à vrai après consentement.
    binding.interrupteurIaActive.isChecked = viewModel.etat.value.active
}
```

- Même changement pour `interrupteurCompletionFantome` et les trois interrupteurs de confidentialité
  (la restauration d'état peut aujourd'hui les faire écrire dans le DataStore sans action utilisateur).
- Ajouter `android:saveEnabled="false"` sur ces interrupteurs dans `fragment_reglages_ia.xml`
  (l'état vient du DataStore, pas de la vue).
- Forcer un `rendre()` à chaque retour au premier plan (pour que « Pas maintenant » remette
  l'interrupteur sur l'état réel).
- **Test de non-régression** : `FragmentScenario` sur `ReglagesIaFragment` : cocher l'interrupteur,
  `recreate()`, vérifier qu'**un seul** `EffetReglagesIa.OuvrirConsentement` a été émis au total.

### Lot 3 — Zone de saisie du chat (B1a, B1b, B1c, S3)

1. **Modèle d'écran pur et testé** : extraire
   `fun decrireEcranChat(active: Boolean, cleAbsente: Boolean, nbMessages: Int): EcranChat`
   (`INACTIF`, `CLE_ABSENTE`, `VIDE`, `CONVERSATION`) et la couvrir par un test unitaire.
2. **Layout** : placer `etat_transverse` **dans la zone des messages** (un `FrameLayout` de poids 1 qui
   contient la `RecyclerView` et le calque), de sorte que le compositeur (`saisie_ia`, Envoyer/Arrêter)
   ne soit **jamais** recouvert. Donner un id au conteneur du compositeur (`composeur_ia`).
3. **Règles de visibilité** : `INACTIF` → calque + bouton « Activer », compositeur masqué ;
   `CLE_ABSENTE` → calque + bouton « Ajouter une clé » (ouvre le fournisseur), compositeur masqué ;
   `VIDE` → calque d'accueil (titre, texte, suggestions) dans la zone des messages, **compositeur visible** ;
   `CONVERSATION` → liste + compositeur.
4. **Clavier (B1c)** : traiter au niveau de l'**hôte** pour que tous les fragments du tiroir en profitent.
   Dans `GestionnaireInsetsEditeur.brancher()`, remplacer `applySystemBarsInsetsMargins()` du tiroir par un
   écouteur qui, en plus des marges de barres système, ajoute un padding bas égal à
   `max(0, ime.bottom - barres.bottom)` et masque `rail_fragments` tant que le clavier est visible.
   Isoler le calcul dans une fonction pure testée (même patron que `calculerReserveEditeur`).
   **À valider sur appareil** (rotation, clavier flottant, mode fenêtré).
5. **Critère** : sur appareil, tiroir IA ouvert, conversation vide, IA active : le champ de saisie et le
   bouton Envoyer sont visibles sans clavier, restent visibles clavier ouvert, et Envoyer part.

### Lot 4 — Robustesse des réglages (B3, S6)

- `texteVersEntier` renvoie `Int?` ; une valeur absente ou invalide est **ignorée** et le champ reprend
  la valeur persistée (jamais de bridage silencieux à 128 jetons ou 5 s).
- Valider l'URL de base à la saisie (schéma `https`, ou `http` seulement pour `localhost`/`127.0.0.1`)
  avec un message d'erreur de chaîne dédiée (`values/` et `values-en/`).

### Lot 5 — Améliorations à fort retour (après stabilisation)

1. **Préréglages de fournisseur** : lignes prêtes à l'emploi (Anthropic, OpenRouter, serveur local) qui
   préremplissent l'URL de base ; la clé reste saisie par l'utilisateur.
2. **Bouton « Tester la connexion »** dans le détail fournisseur (appel `listModels`, résultat clair :
   clé invalide / réseau / quota).
3. **Sélecteur de modèle dans l'en-tête du chat** (sans repasser par Paramètres).
4. **Compteur d'usage** par conversation (les jetons d'entrée/sortie sont déjà lus par les deux
   fournisseurs ; les afficher dans le chat et l'historique).
5. **Actions d'erreur** : chaque erreur normalisée propose son action (Réessayer, Ouvrir les réglages,
   Changer de modèle).

### Backlog fonctionnalités (hors travaux exigés, à prioriser par le propriétaire)

| Priorité | Fonctionnalité | Note |
|----------|----------------|------|
| P1 | Actions rapides sur sélection : Expliquer, Corriger, Écrire des tests, Documenter, Refactorer | Le bouton « Ask AI » existe déjà : en faire un menu d'actions |
| P1 | « Corriger avec l'IA » sur une erreur du panneau Problèmes / du LSP | Le canal de signaux éditeur → chat existe déjà |
| P1 | Fichier de règles de projet (ex. `.codeide/ia-regles.md`) injecté dans le contexte | Cohérence de style, conventions du projet |
| P2 | Mise en cache de prompt côté Anthropic | Absente des fournisseurs (aucune occurrence de `cache_control`) : réduit coût et latence |
| P2 | Entrée image (capture d'écran, rendu d'aperçu XML) | `AiContentBlock` ne porte que Texte/Outil : à étendre dans le domaine d'abord |
| P2 | Génération de message de commit depuis le panneau Git | Réutilise le contexte diff |
| P2 | Boucle « build → lire les erreurs → proposer un correctif » pour l'agent | S'appuie sur la liste blanche de tâches et l'approbation par exécution déjà en place |
| P3 | Export / import de conversations | Rétention déjà gérée (7 j / 30 j / illimitée) |
| P3 | Plusieurs profils de clés par fournisseur | Utile si l'utilisateur sépare perso / travail |

---

## 6. Verdict

**Demander des changements** : le moteur IA est de bonne qualité et n'a pas besoin de refonte, mais l'écran
chat et le parcours de consentement empêchent aujourd'hui d'utiliser la fonctionnalité. Lots 1 à 4 exigés ;
lot 5 recommandé ; backlog à la discrétion du propriétaire.

---

## 7. Prompt à donner à l'agent (copier-coller)

```text
Rôle : ingénieur Android/Kotlin senior sur CodeIDE (dépôt jjoblab/CodeIDE).

AVANT TOUT : lis AGENTS.md puis docs/STRATEGIE_VERIFICATION.md (elle prime sur toute
directive de vérification). Ne lance PAS assembleDebug en local : la CI GitHub fait la
vérification complète. Respecte l'identité git, les commits conventionnels en français et
toutes les règles impératives d'AGENTS.md (Kotlin uniquement, KDoc/commentaires/messages en
français, aucune ressource en dur — chaînes dans values/ ET values-en/, couleurs et styles par
tokens/thème, pas de !!, dispatchers injectés, CancellationException relancée, etc.).
Règle 13 : ne cite, dans aucun fichier ni commit, un autre IDE/éditeur Android.

Contexte : le rapport docs/IA_REVUE_ET_PROMPT.md (à déposer dans docs/) décrit une chaîne de
bugs de l'assistant IA : (a) le bouton « Activer l'assistant » du chat ne fait rien dans
EditorActivity (navigateur sans repli), (b) le consentement se rouvre en boucle depuis
Paramètres → IA (restauration d'état de l'interrupteur maître), (c) la zone de saisie du chat
est masquée (état INACTIF, calque opaque en état vide, clavier). Les constats « Vérifié » sont
établis par lecture du code ; les « Probable » doivent être confirmés par un test ou une
vérification avant de corriger — dis-moi lesquels tu as confirmés.

Travaux, dans cet ordre, un commit (et une version 0.N.M) par lot :
 Lot 1 navigation (§5 Lot 1)       Lot 2 boucle de consentement (§5 Lot 2)
 Lot 3 zone de saisie du chat (§5 Lot 3)   Lot 4 robustesse des réglages (§5 Lot 4)
Puis, si la CI est verte : Lot 5 (§5 Lot 5), un commit par point.

Exigences transverses :
 - Un test de non-régression par bug (restauration d'état, visibilité par état d'écran via la
   fonction pure decrireEcranChat, calcul d'inset du tiroir, navigation de repli).
 - Aucune régression de cohérence visuelle : mêmes thèmes, styles et espacements que les
   écrans existants ; ne pas aggraver les incohérences actuelles.
 - Un ADR court pour : le repli de navigation vers MainActivity, et le traitement des insets
   du tiroir au niveau de l'hôte.
 - Mets à jour CHANGELOG.md et docs/TESTS_MANUELS.md (nouveaux scénarios).

FIN DE MISSION — une fois tous les lots terminés et la CI verte, crée
docs/IA_TESTS_MANUELS.md à partir de la section « 8. Liens et scénarios de test » du rapport
(en corrigeant tout lien devenu inexact) et termine ton message final par :
 1) le lien de la page des exécutions CI et le nom de l'artefact APK produit,
 2) la liste des liens de test (clés et URL de base) de la section 8,
 3) les scénarios T1 à T12 avec le résultat attendu, et ceux que tu n'as pas pu vérifier.
```

---

## 8. Liens et scénarios de test (à reprendre dans `docs/IA_TESTS_MANUELS.md` en fin de mission)

### 8.1 Application

| Quoi | Lien / nom | Note |
|------|-----------|------|
| Exécutions CI (APK de test) | https://github.com/jjoblab/CodeIDE/actions/workflows/ci.yml | Ouvrir la dernière exécution verte sur `main` |
| Artefact APK | `CodeIDE-v<version>-debug` | Produit par le job de construction (`ci.yml`) ; version lue dans `version.properties` |

### 8.2 Fournisseurs pour tester (clé personnelle, jamais committée)

| Fournisseur | Créer une clé | Réglage dans CodeIDE | Remarques |
|-------------|---------------|----------------------|-----------|
| Anthropic | https://platform.claude.com/settings/keys | Fournisseur « Anthropic » (URL par défaut `https://api.anthropic.com`) | La clé commence par `sk-ant-` et n'est affichée qu'une seule fois (doc : https://platform.claude.com/docs/en/get-api-key) |
| OpenRouter (compatible OpenAI) | https://openrouter.ai/settings/keys | Fournisseur « compatible OpenAI », URL de base `https://openrouter.ai/api/v1` | Catalogue : https://openrouter.ai/models ; pour l'agent, choisir un modèle qui gère les outils (l'API accepte le filtre `supported_parameters=tools`). Des modèles gratuits existent, avec limites de débit (voir la FAQ OpenRouter) |
| Serveur local sur l'appareil (ex. Ollama dans PRoot) | — (aucune clé) | Fournisseur « compatible OpenAI », URL de base `http://localhost:11434/v1` | Seuls `localhost` et `127.0.0.1` sont autorisés en clair par `network_security_config.xml`. Port et chemin `/v1` à confirmer dans la documentation du serveur choisi |

L'URL de base du fournisseur compatible OpenAI **inclut** le segment de version : le code ajoute
`/chat/completions` et `/models`.

### 8.3 Scénarios de test

| # | Scénario | Résultat attendu |
|---|----------|------------------|
| T1 | Éditeur → tiroir IA → Chat (IA inactive) → « Activer l'assistant » | L'écran de consentement s'ouvre (B2a) |
| T2 | Paramètres → IA : activer l'interrupteur maître → « Pas maintenant » | Retour sans réouverture ; l'interrupteur est **désactivé** |
| T3 | Même parcours, puis « Activer » | Retour sans réouverture ; interrupteur activé ; consentement daté |
| T4 | Pivoter l'écran / mettre l'app en arrière-plan puis revenir, depuis Paramètres → IA | Aucune ouverture parasite du consentement |
| T5 | Chat vide, IA active | Accueil visible **et** champ de saisie + Envoyer visibles |
| T6 | Ouvrir le clavier dans le chat | Le champ reste visible au-dessus du clavier |
| T7 | Sans clé enregistrée, ouvrir le chat | État « Ajoutez une clé » avec accès direct au fournisseur |
| T8 | Saisir une clé, « Tester la connexion », actualiser les modèles | Succès avec nombre de modèles ; clé erronée → message clair |
| T9 | Envoyer un message, arrêter pendant le flux, régénérer | Flux progressif ; Arrêter coupe ; Régénérer relance |
| T10 | Coller une chaîne factice de forme `sk-` suivie de 20 caractères dans le message | Dialogue bloquant « Envoyer sans / Annuler » (aucun envoi avant le choix) |
| T11 | Agent : demander une modification d'un fichier ouvert | Une proposition à relire ; **rien** n'est écrit avant acceptation ; annulation via l'historique local |
| T12 | Réglages : vider « jetons max », quitter le champ ; désactiver l'assistant | La valeur précédente est conservée (pas 128) ; après désactivation, toute requête est refusée sans réseau |
