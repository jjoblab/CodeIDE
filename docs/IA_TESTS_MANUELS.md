# Assistant IA — recette manuelle sur appareil

> Issu de la section 8 du rapport `docs/IA_REVUE_ET_PROMPT.md` (revue du
> 2026-10-23), mis à jour après la livraison des lots 1 à 4
> (CodeIDE v0.186.0 → v0.189.0). Les liens ont été re-vérifiés contre le
> dépôt ; les scénarios marqués « à valider sur appareil » ne peuvent
> PAS être déroulés dans l'environnement de build (pas de KVM).
>
> **État CI** : la chaîne locale (spotless, detekt, frontières, jetons
> UI, compilation et tests des modules touchés) est VERTE pour chaque
> lot ; les commits/tags v0.185.0 → v0.189.0 sont prêts mais **pas
> encore poussés** — le jeton d'accès du dépôt a expiré, la CI GitHub
> n'a donc pas encore rejoué ces versions. Ouvrir la dernière
> exécution VERTE pour l'APK de test, ou pousser dès le jeton renouvelé.

## 1. Application

| Quoi | Lien / nom | Note |
|------|-----------|------|
| Exécutions CI | https://github.com/jjoblab/CodeIDE/actions/workflows/ci.yml | Ouvrir la dernière exécution verte sur `main` (après push des lots 1 à 4) |
| Artefact APK | `CodeIDE-v<version>-debug` | Produit par le job de construction (`ci.yml`, `upload-artifact`) ; version lue dans `version.properties` — v0.189.0 pour la présente recette |

## 2. Fournisseurs pour tester (clé personnelle, jamais committée)

| Fournisseur | Créer une clé | Réglage dans CodeIDE | Remarques |
|-------------|---------------|----------------------|-----------|
| Anthropic | https://platform.claude.com/settings/keys | Fournisseur « Anthropic » (URL par défaut `https://api.anthropic.com`) | La clé commence par `sk-ant-` et n'est affichée qu'une seule fois (doc : https://platform.claude.com/docs/en/get-api-key) |
| OpenRouter (compatible OpenAI) | https://openrouter.ai/settings/keys | Fournisseur « compatible OpenAI », URL de base `https://openrouter.ai/api/v1` | Catalogue : https://openrouter.ai/models ; pour l'agent, choisir un modèle qui gère les outils (l'API accepte le filtre `supported_parameters=tools`). Des modèles gratuits existent, avec limites de débit (voir la FAQ OpenRouter) |
| Serveur local sur l'appareil | — (aucune clé) | Fournisseur « compatible OpenAI », URL de base `http://localhost:11434/v1` | Seuls `localhost` et `127.0.0.1` sont autorisés en clair par `network_security_config.xml` (validation `ValidationUrlIa` identique côté saisie). Port et chemin `/v1` à confirmer dans la documentation du serveur choisi |

L'URL de base du fournisseur compatible OpenAI **inclut** le segment de
version : le code ajoute `/chat/completions` et `/models`.

## 3. Scénarios de recette

| # | Scénario | Résultat attendu | État |
|---|----------|------------------|------|
| T1 | Éditeur → tiroir IA → Chat (IA inactive) → « Activer l'assistant » | L'écran de consentement s'ouvre (B2a, v0.186.0 : repli de navigation, ADR 0148) | Couvert par `AppNavigationRepliIaTest` (JVM) ; appuyer pour la recette visuelle |
| T2 | Paramètres → IA : activer l'interrupteur maître → « Pas maintenant » | Retour SANS réouverture ; l'interrupteur est DÉSACTIVÉ (état réel) | Régression couverte par `BoucleConsentementIaTest` (JVM) ; recette visuelle recommandée |
| T3 | Même parcours, puis « Activer » | Retour sans réouverture ; interrupteur activé ; consentement daté | À valider sur appareil |
| T4 | Pivoter l'écran / arrière-plan puis revenir, depuis Paramètres → IA | Aucune ouverture parasite du consentement ; l'interrupteur suit l'état réel à chaque START | À valider sur appareil (rotation) |
| T5 | Chat vide, IA active, clé enregistrée | Accueil visible **ET** champ de saisie + Envoyer visibles (B1a/B1b, v0.188.0 : le calque vit dans la zone des messages) | Structure couverte par `LayoutsIaTest` + `PresentationChatIaTest` (JVM) ; recette visuelle recommandée |
| T6 | Ouvrir le clavier dans le chat | Le champ reste visible au-dessus du clavier ; le rail de fragments disparaît pendant la frappe (B1c, ADR 0149) | **À valider sur appareil** (rotation, clavier flottant, mode fenêtré) — le calcul est pur et testé, le rendu ne se prouve qu'à l'écran |
| T7 | Sans clé enregistrée, ouvrir le chat (IA active) | État « Aucune clé enregistrée » avec bouton « Ajouter une clé » vers le fournisseur ACTIF (S3) | Couvert par `ChatViewModelTest` + `PresentationChatIaTest` (JVM) ; recette visuelle recommandée |
| T8 | Saisir une clé, « Tester la connexion », actualiser les modèles | Succès avec nombre de modèles ; clé erronée → message clair | Mécanisme `TesterConnexion` préexistant ; à valider sur appareil avec de vraies clés |
| T9 | Envoyer un message, arrêter pendant le flux, régénérer | Flux progressif ; Arrêter coupe ; Régénérer relance | À valider sur appareil (déjà recette IA-3, inchangé par les lots 1 à 4) |
| T10 | Coller une chaîne factice de forme `sk-` suivie de 20 caractères dans le message | Dialogue bloquant « Envoyer sans / Annuler » (aucun envoi avant le choix) | À valider sur appareil (préexistant, ADR 0124 §4) |
| T11 | Agent : demander une modification d'un fichier ouvert | Une proposition à relire ; RIEN n'est écrit avant acceptation ; annulation via l'historique local | À valider sur appareil (préexistant, ADR 0125) |
| T12 | Réglages : vider « jetons max », quitter le champ ; désactiver l'assistant | La valeur précédente est conservée (pas 128) ; après désactivation, toute requête est refusée sans réseau (v0.189.0, B3) | Régression couverte par `BridageSilencieuxIaTest` (JVM) ; recette visuelle recommandée |

## 4. Constats « Probable » du rapport — vérification

| Constat | Vérification |
|---------|--------------|
| B1c (clavier recouvre la saisie du tiroir) | **Confirmé par lecture** : le tiroir ne recevait que `applySystemBarsInsetsMargins()` (barres système, AUCUN IME) et aucun fragment du tiroir n'appelle d'utilitaire d'insets. Corrigé au niveau de l'hôte (ADR 0149) ; le rendu reste à valider sur appareil (T6) |
| B2b (boucle du consentement) | **Confirmé par le mécanisme** : `setOnCheckedChangeListener` + restauration d'état à `onViewStateRestored` (après `onViewCreated`). Corrigé (v0.187.0) et couvert par `BoucleConsentementIaTest` |
| S6 (validation d'URL à la saisie) | **Déjà en place** : `ValidationUrlIa.erreur` (domaine) est appelé par `FournisseurIaViewModel.changerUrl` ; URL invalide → `ia_url_erreur` dans le champ, jamais persistée. Aucun correctif requis |
