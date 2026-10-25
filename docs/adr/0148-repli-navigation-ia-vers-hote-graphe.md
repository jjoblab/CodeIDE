# ADR 0148 — Le repli de navigation IA vers l'hôte du graphe : le consentement atteignable depuis l'éditeur (rapport IA, lot 1 — bug B2a, suggestion S2)

- **Statut** : **accepté** (livré avec CodeIDE v0.186.0).
- **Date** : 2026-10-25.
- **Décideurs** : jjoblab (rapport `docs/IA_REVUE_ET_PROMPT.md` §5 Lot 1 ;
  prompt agent §7 : « Lot 1 navigation (§5 Lot 1) »).
- **Dépend de** : ADR 0122 (modules dédiés `:feature:ai`), ADR 0121
  (périmètre réseau IA), `docs/IA_REVUE_ET_PROMPT.md` (B2a « Vérifié »,
  S2 « Robustesse »).

## Contexte

Le bouton « Activer l'assistant » du chat vit dans le tiroir IA de
l'**éditeur** — `EditorActivity`, une activité **sans conteneur de
navigation**. `AppNavigatorImpl.openAiConsentement()` exigeait
`navControllerDHote != null` **et** une destination courante dans
`DESTINATIONS_IA` : depuis l'éditeur, le contrôleur vaut `null` et la
méthode se terminait par un simple `logger.w` — **le bouton ne faisait
rien** (B2a, constat « Vérifié » par lecture du code). Idem pour
`openAiFournisseur()` (l'état « Ajoutez une clé » du chat, S3 du même
rapport, en dépend directement). D'autres écrans (diagnostic,
installation, assistant) possèdent depuis v0.31.4 un **repli** :
`relancerMainActivity(RoutageEcran.ECRAN_…)` remonte l'instance
existante de l'hôte du graphe (`REORDER_TO_FRONT | SINGLE_TOP`, jamais
recréée) — l'activité appelante **survit dessous** et un retour système
y revient. Le consentement et le fournisseur IA étaient les seules
destinations sans repli : la chaîne de bugs B2a → B2b → B1 rendait
l'assistant **inactivable** depuis l'éditeur.

## Décision

1. **Deux nouvelles routes** dans `RoutageEcran` :
   `ECRAN_CONSENTEMENT_IA` (`"consentement_ia"`) et
   `ECRAN_FOURNISSEUR_IA` (`"fournisseur_ia"`), plus l'extra
   `EXTRA_FOURNISSEUR_IA` (l'identifiant du fournisseur visé) et la clé
   d'argument `CLE_FOURNISSEUR_IA` — **source unique** partagée par le
   navigateur et l'hôte (l'ancienne constante privée doublonnée de
   `AppNavigatorImpl` est retirée).
2. **Deux nouvelles branches** dans `MainActivity.routerEcranCible()`
   (même mécanique que `ECRAN_PARAMETRES`, consommées à froid comme à
   chaud) : le consentement s'ouvre par action depuis
   `settings_ia`, destination directe ailleurs ; le fournisseur reçoit
   son identifiant recopié de l'extra dans l'argument de destination
   (absent → valeur par défaut du graphe). `ouvrirEcranRoutage` gagne un
   paramètre `arguments` et **refuse d'empiler** la destination sur
   elle-même (S2 : un double routage ne crée rien).
3. **Le repli dans le navigateur** : `openAiConsentement()` et
   `openAiFournisseur()` traitent le cas `navControllerDHote == null`
   AVANT la garde des destinations — ils routent via
   `relancerMainActivity` (surcharge à `extras` pour le fournisseur).
   La garde `DESTINATIONS_IA` est conservée pour les hôtes AVEC graphe
   (le maître des paramètres n'ouvre pas le consentement directement),
   et chaque méthode **refuse** quand la destination courante est déjà
   l'écran demandé (S2, anti-empilement).

## Conséquences

- Depuis le chat de l'éditeur, « Activer l'assistant » ouvre
  l'écran de consentement ; « Activer » persiste le consentement daté
  (Keystore/DataStore, ADR 0124) ; le retour système ramène l'espace
  intact (l'éditeur vit sous l'hôte du graphe).
- Le parcours documenté : après « Activer », l'utilisateur est sur
  l'écran consentement de l'hôte du graphe ; **Paramètres → IA** y
  montre l'interrupteur synchronisé ; le retour système referme la
  pile et revient à l'éditeur.
- Aucune signature publique de `AppNavigator` ne change (port
  `core:ui` intact) ; aucun nouveau module ; le routage reste le seul
  canal inter-activités (pas d'accesseur direct au graphe depuis
  l'éditeur).
- Test de non-régression : `AppNavigationRepliIaTest` (Robolectric) —
  depuis une activité hôte SANS graphe, les deux méthodes démarrent
  l'intent routé avec les bons drapeaux/extras ; destination courante
  déjà atteinte → aucun intent (S2).
