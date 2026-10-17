# Mission « IA » — spécification des écrans, layouts et ressources

> Complète `docs/IA_PLAN_EXECUTION.md` et la maquette `docs/mockups/ia/ia-ecrans.html`
> (les numéros E1…E6 correspondent). Les valeurs de couleur de la maquette sont les
> **tokens réels** de `core/ui/src/main/res/values/colors.xml` et `values-night/colors.xml` ;
> dans le code, **on n'écrit jamais de couleur en dur** : on utilise les attributs/rôles de
> thème (ADR 0059/0060, couleurs dynamiques comprises).

## 1. Principes

- Vues XML + ViewBinding, Material 3, `BaseFragment<…>` de `core:ui`, MVVM + flux
  unidirectionnel (`UiState` / `Action` / `Effect` via `StateFlow` / `Channel`).
- Hauteurs tactiles ≥ 48 dp ; textes en `sp` via `?attr/textAppearance…` ; aucune taille de
  police fixe hors éditeur.
- Chaque écran gère **quatre états** : chargement, vide, erreur, contenu — jamais d'écran blanc.
- Thèmes clair, sombre, couleurs dynamiques : vérifier les trois ; contraste ≥ 4,5:1 pour le texte.
- Insets : réutiliser `GestionnaireInsetsEditeur` (éditeur/tiroir) ; ne rien écrire dans `EditorActivity`
  qui re-gère clavier ou barre de navigation (ADR 0118).

## 2. Rôles de couleur (à vérifier dans `colors.xml` ; ajouter un token dans `core:ui` si absent, jamais dans la feature)

| Élément | Rôle M3 |
|---|---|
| Fond du tiroir / panneau | `colorSurface` |
| Bulle utilisateur | `colorPrimaryContainer` / `colorOnPrimaryContainer` |
| Bulle assistant | `colorSurfaceVariant` (fond discret) / `colorOnSurface` |
| Bloc de code | fond `colorSurfaceVariant` atténué, texte `colorOnSurface`, police à chasse fixe de l'éditeur |
| Puce de contexte | contour `colorOutlineVariant`, texte `colorOnSurfaceVariant` |
| Action principale (Envoyer, Accepter) | `colorPrimary` / `colorOnPrimary` |
| Succès (étape terminée, ajout dans le diff) | token succès existant (`codeide_succes`) |
| Avertissement (secret détecté, conflit) | token `warn` existant + conteneur `warn` |
| Erreur (clé invalide, retrait dans le diff) | `colorError` / token erreur existant |
| Destination active du rail | encoche + `colorPrimary` (comportement existant de `construireRail`) |

## 3. Inventaire des ressources à créer

**Layouts — `feature:ai`** (`feature/ai/src/main/res/layout/`)
`fragment_ia_tiroir.xml` · `fragment_ia_chat.xml` · `ligne_message_utilisateur.xml` · `ligne_message_assistant.xml` · `ligne_bloc_code.xml` · `vue_puce_contexte.xml` · `vue_compositeur_ia.xml` · `fragment_ia_agent.xml` · `ligne_etape_agent.xml` · `ligne_appel_outil.xml` · `carte_approbation.xml` · `fragment_revue_changements.xml` · `fragment_ia_historique.xml` · `ligne_conversation.xml` · `fragment_reglages_ia.xml` · `fragment_fournisseur_ia.xml` · `fragment_confidentialite_ia.xml` · `fragment_consentement_ia.xml` · `vue_etat_ia.xml` (état vide/erreur réutilisable).

**Layouts — `feature:editor`** : `vue_barre_consigne_ia.xml` (barre de consigne en ligne) · `vue_carte_diff_ia.xml` (carte de proposition). Réutiliser `ligne_diff.xml`.

**Drawables** (vérifier d'abord `core/ui/src/main/res/drawable` ; `ic_smart_toy` existe déjà) : `ic_envoyer`, `ic_arreter`, `ic_regenerer`, `ic_copier`, `ic_insere_curseur`, `ic_cle`, `ic_bouclier`(existe), `fond_bulle_utilisateur`, `fond_bulle_assistant`, `fond_bloc_code`, `fond_carte_ia`. Vecteurs 24 dp, teinte par attribut.

**Dimensions** (`core:ui/dimens.xml`, après vérification des existantes `spacing_*`, `corner_radius_*`) : `ia_bulle_rayon` = `@dimen/corner_radius_large` ; `ia_bulle_largeur_max` = 320dp ; `ia_compositeur_hauteur_min` = 56dp ; `ia_compositeur_hauteur_max` = 144dp ; `ia_puce_hauteur` = 32dp ; `ia_carte_diff_hauteur_max` = 280dp ; `ia_rail_icone` = 20dp (existant : 20 dp / libellé 10 sp).

**Styles** : `Widget.CodeIDE.IaBulle`, `Widget.CodeIDE.IaPuce` (basé `Widget.Material3.Chip.Assist`), `Widget.CodeIDE.IaCarte` (basé `Widget.Material3.CardView.Outlined`).

**Chaînes** : préfixe `ia_` (ex. `ia_chat_hint`, `ia_envoyer_cd`, `ia_erreur_cle_invalide`), **FR par défaut + EN dans `values-en/`**. Tout message utilisateur vient d'une ressource ; aucun texte d'erreur brut de fournisseur n'est affiché tel quel (mappé puis détaillé dans un volet « Détails » repliable, expurgé).

**Navigation** (`AppNavigator`, `core:ui`) : réutiliser `SectionParametres.IA` ; ajouter les sous-écrans de réglages comme fragments de la section (pas de nouvelle activité). Aucune Activity nouvelle n'est nécessaire.

## 4. Écrans

### E1 — Tiroir · IA · Chat
**Hôte** : `IaTiroirFragment` (destination « IA » du rail, 6ᵉ position) → onglet **Chat**.
**Hiérarchie (`fragment_ia_tiroir.xml` puis `fragment_ia_chat.xml`)**
```
LinearLayout vertical (fond colorSurface)
├─ entête : titre « Assistant IA » (textAppearanceTitleSmall) + menu ⋮ (Nouvelle conversation, Réglages IA)
├─ TabLayout id=onglets_ia  [Chat | Agent | Historique | Recherche(bientôt)]
├─ FrameLayout (poids 1)
│   ├─ RecyclerView id=liste_messages  (clipToPadding=false, fastScroll off)
│   └─ vue_etat_ia (vide / erreur / hors-ligne)
├─ HorizontalScrollView id=zone_puces → ChipGroup (vue_puce_contexte ×n : « Main.kt », « Sélection 12 l. », « Diagnostics 3 »)
└─ vue_compositeur_ia : champ multi-ligne id=saisie_ia (min 56 dp, max 144 dp) + bouton id=bouton_envoyer / bouton_arreter (48×48 dp) + bouton id=bouton_arobase (@fichier)
```
**Messages** : utilisateur à droite (bulle `colorPrimaryContainer`), assistant pleine largeur sans bulle fermée (fond léger), blocs de code avec barre **langue · Copier · Insérer au curseur**, rendu Markdown (§ plan IA-3). Texte sélectionnable.
**États** : *vide* (illustration `ic_smart_toy` 64 dp + 3 suggestions cliquables : « Explique ce fichier », « Trouve un bug dans la sélection », « Écris un test ») ; *génération* (curseur clignotant en fin de texte, bouton Arrêter) ; *erreur* (carte inline : « Clé refusée par le fournisseur » + **Ouvrir les réglages** / **Réessayer**) ; *hors-ligne* (bandeau « Hors-ligne — le message sera conservé », envoi désactivé) ; *IA non activée* (invite à lire le consentement).
**Interactions** : appui long sur un message = Copier / Supprimer ; défilement automatique en bas pendant la génération, suspendu si l'utilisateur remonte (bouton « ↓ Nouveau contenu »).
**Accessibilité** : `contentDescription` sur tous les boutons-icônes ; messages annoncés « Vous : … » / « Assistant : … » ; `accessibilityLiveRegion="polite"` sur la bulle en cours de génération (limité, pas à chaque jeton).

### E2 — Tiroir · IA · Agent
**Hiérarchie (`fragment_ia_agent.xml`)**
```
LinearLayout vertical
├─ carte objectif (texte saisi par l'utilisateur, modifiable avant lancement) + bouton « Lancer l'agent »
├─ bandeau limites : « 7/25 étapes · 18 k jetons · 02:14 » + bouton Arrêter
├─ RecyclerView id=liste_etapes
│    ligne_etape_agent : pastille d'état (en attente / en cours / ok / refusé / erreur) + titre + durée
│      └─ ligne_appel_outil (repliable) : outil, arguments tronqués, résultat tronqué
├─ carte_approbation (une par outil EXÉCUTION) : outil, arguments exacts, raison, [Refuser] [Autoriser une fois]
└─ carte « Changements proposés : 4 fichiers · +37 −12 » → [Examiner] [Tout appliquer] [Rejeter]
```
**Revue des changements** (`fragment_revue_changements.xml`, plein tiroir) : liste de fichiers (case à cocher), diff unifié par fichier (`DiffUnifie`/`ligne_diff.xml`), badge **Conflit** si le fichier a changé depuis la proposition (action : « Régénérer pour ce fichier »). Après application : bandeau **Annuler cette exécution** (restauration via historique local) pendant toute la session.
**États** : *inactif* (objectif vide, exemples) ; *en cours* (notification premier plan « Agent en cours » + action Arrêter) ; *interrompu* (limite atteinte : message explicite + « Continuer avec 10 étapes de plus ») ; *terminé* ; *erreur fournisseur*.
**Garde-fous visibles** : mention permanente « L'agent ne modifie rien sans votre validation ».

### E3 — Éditeur · « Demander à l'IA » et carte de diff
**Point d'entrée** : pill de sélection existante + action d'hôte **« IA »** (`SelectionAction`, grisée sans sélection).
**Barre de consigne (`vue_barre_consigne_ia.xml`)** : posée dans `zone_centrale`, **au-dessus de `barre_symboles`**, sous l'éditeur (jamais en overlay masquant la sélection) :
```
MaterialCardView (IaCarte) 
├─ ChipGroup actions rapides : [Expliquer] [Corriger] [Documenter] [Tests] [Simplifier]
├─ champ « Que faut-il changer ? » id=saisie_consigne + bouton Envoyer
└─ ligne d'état : « Contexte : Sélection 12 l. · Main.kt » + [×]
```
**Carte de diff (`vue_carte_diff_ia.xml`)** : même emplacement, hauteur ≤ `ia_carte_diff_hauteur_max`, défilante :
```
MaterialCardView
├─ entête : « Proposition » + compteur « +6 −3 » + bouton déplier (plein tiroir)
├─ RecyclerView (ligne_diff : ajout fond succès/atténué, retrait fond erreur/atténué)
└─ boutons : [Rejeter] [Réessayer ▾ (avec consigne)] [Accepter]
```
**Règles** : *Accepter* applique en **un lot** (`beginBatch/endBatch`) sur la plage d'origine ; si la révision du document a changé depuis la génération → bouton désactivé + message « Le texte a changé — relancer ». *Réessayer* conserve la consigne. Clavier ouvert : la carte remonte avec la barre de symboles (insets). Paysage : barre et carte en pleine largeur de la colonne éditeur.
**Autres points d'entrée** : ligne de diagnostic (Problèmes) → **Corriger avec l'IA** ; ligne d'erreur (Console/Logcat) → **Expliquer** — ils ouvrent le tiroir sur Chat avec le contexte pré-rempli (puce visible et retirable).

### E4 — Paramètres · IA
**Hôte** : `feature:settings` (`SectionParametres.IA`, remplace le cas `BientotFragment`). Cartes Material 3 avec icône par ligne (conforme à la refonte des Paramètres).
```
fragment_reglages_ia.xml — NestedScrollView → LinearLayout
├─ carte « Activer l'assistant IA » : interrupteur + lien « Ce qui est envoyé » → consentement (E5)
├─ carte « Fournisseur » : liste (Anthropic · Compatible OpenAI · + Ajouter) avec pastille d'état (clé OK / à saisir)
├─ carte « Modèle » : champ libre + bouton « Actualiser la liste » + liste déroulante des modèles récupérés
├─ carte « Confidentialité » : 3 interrupteurs (Fichier actif · Liste des chemins du projet · Diagnostics) + « Fichiers exclus… »
├─ carte « Limites » : étapes max, jetons max par exécution, délai (sélecteurs)
├─ carte « Historique » : rétention, « Tout effacer »
└─ carte « Agent » : interrupteur « Autoriser l'agent » (désactivé = assistant en lecture seule)
```
**Détail fournisseur (`fragment_fournisseur_ia.xml`)** : nom, URL de base (HTTPS ; `http://localhost` accepté seulement pour la boucle locale, message sinon), champ clé **masqué** (aperçu `••••1234`, **pas d'affichage**), boutons **Tester la connexion** (résultat : ✓ / ✗ avec cause lisible), **Remplacer la clé**, **Supprimer**.
**États** : *clé à ressaisir* (Keystore invalidé) ; *test en cours* (indicateur) ; *échec* (401/403/réseau/URL invalide).

### E5 — Consentement et états transverses
**Consentement (`fragment_consentement_ia.xml`)**, affiché au premier usage et accessible depuis E4 :
« L'IA envoie des extraits de votre code au fournisseur **que vous avez choisi**. CodeIDE n'a aucun serveur et ne collecte rien. » ; liste « Ce qui est envoyé » (puces réelles : fichier actif, sélection, diagnostics, fichiers `@mentionnés`) ; « Jamais envoyé » (secrets, chemins absolus, identité git, fichiers exclus) ; boutons **Activer** / **Pas maintenant** ; case « Ne plus afficher » **absente** (le consentement se révoque dans E4).
**Avertissement de secret** (boîte de dialogue Material, bloquante) : « Ce contexte semble contenir un secret (clé ou jeton) » + extrait masqué ; **Envoyer sans** / **Annuler**.
**Erreurs** (`vue_etat_ia`) : clé absente/invalide, quota (429 : « Réessayez dans N s »), indisponibilité (5xx), hors-ligne, délai dépassé, réponse illisible — chacune avec cause lisible, action principale, et volet « Détails » repliable **expurgé**.

### E6 — Tiroir · IA · Historique
**`fragment_ia_historique.xml`** : champ de recherche, liste `ligne_conversation` (titre auto = premier message tronqué, date relative, modèle, nombre de messages), balayage latéral = supprimer (avec annulation « Rétablir » 5 s), menu : Renommer, Exporter en Markdown, Tout effacer. État vide : « Aucune conversation ». Appui = rouvre la conversation dans Chat.

## 5. Comportements transverses

- **Rotation / recréation** : l'état de saisie et la conversation courante survivent (ViewModel + `SavedStateHandle` pour le brouillon **sans** clé ni secret) ; une génération en cours continue (portée du ViewModel/service) et se rattache à la nouvelle vue.
- **Animations** : durées M3 standard (apparition de carte 200–300 ms) ; respecter « Supprimer les animations » du système.
- **Performance** : `RecyclerView` + `DiffUtil` ; rendu Markdown **incrémental** (ne reparser que le dernier bloc pendant le flux) ; plafond de mémoire sur les messages très longs (troncature avec « Afficher la suite »).
- **Internationalisation** : FR (défaut) et EN complets, y compris pluriels (`plurals`) pour « n fichiers », « n étapes ».
- **Test de non-régression visuelle manuelle** (à consigner dans `docs/TESTS_MANUELS.md`) : 360 dp, 411 dp, tablette, paysage ; tiroir à 45/69/85/98 % ; clavier ouvert ; thèmes clair/sombre/dynamique ; police système ×1,3 ; TalkBack.
