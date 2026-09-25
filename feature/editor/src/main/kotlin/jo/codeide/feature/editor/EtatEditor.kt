package jo.codeide.feature.editor

import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState

/**
 * Nœud visible de l'arborescence de l'explorateur (étape 14, prompt
 * compagnon section 5.3) : un enfant de la racine ou d'un dossier déplié,
 * aplatit en liste ordonnée pour le `RecyclerView` du tiroir.
 *
 * Les enfants ne sont énumérés qu'au dépliement (chargement paresseux) et
 * mis en cache dans le `EditorViewModel` — ce nœud n'est qu'une **vue**
 * aplatie de ce cache, jamais le détenteur de l'arborescence.
 *
 * Explorateur v2 (étape 31, `docs/EXPLORATEUR_V2.md` § 6-7) : la ligne
 * porte désormais son état de présentation — sélection (barre + fond),
 * coupe (presse-papiers « couper » : opacité et nom barré), point d'état
 * piloté par les onglets de l'éditeur, marque de dernier enfant (guide
 * raccourci), compteur d'enfants des dossiers et flash après mutation.
 *
 * @property uri URI de document SAF du nœud (ou `prive:///…` dans l'arbre privé).
 * @property nom nom d'affichage (dernier segment).
 * @property estDossier `true` pour un dossier (dépliable), `false` pour un
 * fichier (ouverture en onglet à l'étape 15 — projet uniquement).
 * @property profondeur niveau d'imbrication sous la racine (1 = enfant
 * direct de la racine, 0 = la racine elle-même) — sert à l'indentation.
 * @property deplie dossier déplié (chevron tourné, enfants visibles dessous).
 * @property chargementEnfants énumération des enfants en vol (latence SAF).
 * @property erreurChargement la dernière énumération a échoué : la ligne le
 * signale, un nouvel appui réessaie.
 * @property estRacine la racine de l'arbre affiché (ligne haute + badge de
 * chemin, jamais repliable).
 * @property prive nœud de l'arbre du stockage privé (teintes violettes).
 * @property selectionne nœud sélectionné dans l'arbre (§ 6.1 : fond dégradé
 * + barre gauche ; prime sur l'état d'onglet).
 * @property coupe nœud au presse-papiers en mode couper (§ 6.1 : opacité
 * 50 %, nom barré).
 * @property ongletActif fichier de l'onglet **actif** de l'éditeur (point
 * d'état vert plein, § 7).
 * @property ongletOuvert fichier ouvert dans un onglet non actif (point
 * d'état vert creux, § 7).
 * @property dernierEnfant dernier enfant de son parent (guide vertical
 * raccourci, § 6.2).
 * @property masqueAncetresDerniers bit *k−1* posé = l'ancêtre de
 * profondeur *k* est un dernier enfant : son trait de guide ne traverse
 * pas le sous-arbre (§ 6.2, notation `└`).
 * @property nbEnfants nombre d'enfants énumérés d'un dossier (compteur de
 * droite, masqué si négatif).
 * @property flasher ligne récemment mutée : flash de 1,1 s au rendu (§ 6.1).
 */
data class NoeudExplorateur(
    val uri: String,
    val nom: String,
    val estDossier: Boolean,
    val profondeur: Int,
    val deplie: Boolean = false,
    val chargementEnfants: Boolean = false,
    val erreurChargement: Boolean = false,
    val estRacine: Boolean = false,
    val prive: Boolean = false,
    val selectionne: Boolean = false,
    val coupe: Boolean = false,
    val ongletActif: Boolean = false,
    val ongletOuvert: Boolean = false,
    val dernierEnfant: Boolean = false,
    val masqueAncetresDerniers: Int = 0,
    val nbEnfants: Int = -1,
    val flasher: Boolean = false,
)

/**
 * État d'ouverture du panneau inférieur (étape 16, prompt compagnon 5.5).
 *
 * Les trois états gérés par le `BottomSheetBehavior` : **replié** (seul
 * l'en-tête est visible, hauteur fixe), **mi-hauteur** (par défaut à
 * l'ouverture du panneau), **étendu** (pleine hauteur moins la toolbar).
 */
enum class EtatPanneau {
    /** Seul l'en-tête du panneau est visible. */
    REPLIE,

    /** Le panneau occupe environ la moitié de la zone centrale. */
    MI_HAUTEUR,

    /** Le panneau occupe toute la hauteur disponible. */
    ETENDU,
}

/**
 * Source de l'arborescence affichée par l'explorateur (étape 31, § 5) :
 * les deux arbres sont **exclusifs** — l'arbre du projet (SAF) ou celui du
 * stockage privé de l'application, jamais les deux.
 */
enum class SourceArbre {
    /** Arbre du dossier du projet (URI SAF, onglets de l'éditeur). */
    PROJET,

    /** Arbre du stockage privé (`files`, `cache`, `code_cache`,…). */
    PRIVE,
}

/**
 * Mode du presse-papiers d'arbre (étape 31, § 11) — presse-papiers
 * **mémoire** de l'explorateur, jamais le presse-papiers système.
 */
enum class ModePressePapiers {
    /** Copier : la source reste en place, le collage clone. */
    COPIER,

    /** Couper : la source est grisée, le collage déplace. */
    COUPER,
}

/**
 * Presse-papiers d'arbre (étape 31, § 12) : le document retenu et son
 * mode — la barre du même nom l'affiche tant qu'il est occupé.
 *
 * @property uri URI du document retenu (copier ou couper).
 * @property nom nom d'affichage du document.
 * @property estDossier `true` si un dossier entier est retenu.
 * @property mode copier (clone au collage) ou couper (déplacement).
 */
data class PressePapiersArbre(
    val uri: String,
    val nom: String,
    val estDossier: Boolean,
    val mode: ModePressePapiers,
)

/**
 * Édition inline dans la liste (étape 31, § 11) : création (l'éditeur
 * s'ajoute sous le dossier cible) ou renommage (l'éditeur remplace la
 * ligne du nœud).
 *
 * @property renommage `null` pour une création, l'URI renommée sinon.
 * @property uriParent dossier cible de la création / parent du renommage.
 * @property estDossier créer un dossier (icône et repère du champ), sinon
 * un fichier.
 * @property nomInitial valeur pré-remplie (renommage), vide (création).
 */
data class EditionInline(
    val renommage: String?,
    val uriParent: String,
    val estDossier: Boolean,
    val nomInitial: String,
)

/**
 * Type de notification de l'explorateur (étape 31, § 15) — la
 * localisation vit dans l'UI, le ViewModel n'émet qu'une intention.
 */
enum class TypeNotificationArbre {
    /** Astuce d'appui long (au démarrage, § 18). */
    ASTUCE,

    /** Bascule vers l'arbre privé (chemin en seconde ligne). */
    BASCULE_PRIVE,

    /** Bascule vers l'arbre du projet (chemin en seconde ligne). */
    BASCULE_PROJET,

    /** Document créé ([nom] + chemin). */
    CREE,

    /** Document renommé ([nom] ancien, [nomSecondaire] nouveau). */
    RENOMME,

    /** Document supprimé ([nom] + chemin) — annulable. */
    SUPPRIME,

    /** Document copié au presse-papiers ([nom] + chemin). */
    COPIE,

    /** Document coupé au presse-papiers ([nom] + chemin). */
    COUPE,

    /** Presse-papiers collé ([nom] dans [nomSecondaire]). */
    COLLE,

    /** Presse-papiers vidé. */
    VIDE,

    /** Déplacement réussi ([nom] dans [nomSecondaire]). */
    DEPLACE,

    /** Collage impossible : la destination est dans la source. */
    COLLE_IMPOSSIBLE,

    /** Collage impossible : déjà dans ce dossier (mode couper). */
    DEJA_PRESENT,

    /** Déplacement : dossier destination introuvable. */
    DESTINATION_INTROUVABLE,

    /** Déplacement : déjà à cet endroit. */
    DEJA_A_CET_ENDROIT,

    /** Déplacement : la destination est dans l'élément déplacé. */
    DEPLACEMENT_DANS_SOURCE,

    /** Nom refusé par la validation inline (§ 11.1). */
    NOM_INVALIDE,
}

/**
 * Notification de l'explorateur (snackbar maison, étape 31, § 15) :
 * intention typée (la localisation vit dans l'UI), chemin en seconde
 * ligne, action « Annuler » conditionnelle (restauration d'une
 * suppression).
 *
 * @property type intention de la notification (localisable).
 * @property nom nom principal du document concerné.
 * @property nomSecondaire second nom (renommage) ou dossier cible
 * (collage/déplacement).
 * @property chemin chemin du document en seconde ligne, ou `null`.
 * @property annulable une annulation existe (suppression en attente).
 */
data class NotificationArbre(
    val type: TypeNotificationArbre,
    val nom: String = "",
    val nomSecondaire: String = "",
    val chemin: String? = null,
    val annulable: Boolean = false,
)

/**
 * Segment du fil d'Ariane de l'explorateur (étape 31, § 4) : un ancêtre
 * de la sélection courante — la racine (nom `null`) porte l'icône maison.
 *
 * @property uri URI du nœud cible (clic = sélection du nœud).
 * @property nom libellé du segment, `null` pour la racine (maison).
 */
data class SegmentAriane(
    val uri: String,
    val nom: String?,
)

/**
 * Onglet actif du panneau inférieur (étape 16) : **Journal** est
 * fonctionnel, **Console** et **Problèmes** sont des stubs explicites
 * (aucune exécution Gradle ni analyse en Phase 1 — hors périmètre,
 * prompt maître section 13).
 */
enum class OngletPanneau {
    /** Sortie de console/Gradle — stub explicite à l'étape 16. */
    CONSOLE,

    /** Problèmes de compilation et d'analyse — stub explicite à l'étape 16. */
    PROBLEMES,

    /** Journal applicatif — fonctionnel (réutilise `LogRepository`). */
    JOURNAL,
}

/**
 * Onglet de fichier ouvert dans la zone centrale (étape 15, prompt compagnon
 * sections 5.2 et 5.4).
 *
 * L'état est la **vue observable** de l'onglet ; l'`EditorSession` réelle
 * (classe pure de cel-core) vit dans le `EditorViewModel`, hors état —
 * jamais dans une liste d'état sérialisable.
 *
 * @property uri URI de document SAF du fichier (identifiant de l'onglet).
 * @property cheminRelatif chemin sous la racine du projet (copiable).
 * @property nom nom d'affichage (dernier segment du chemin).
 * @property langage langage de coloration cel, ou `null` (repli neutre).
 * @property isDirty contenu modifié non enregistré : le point de
 * modification remplace la fermeture tant qu'il est sale (section 5.4).
 * @property sauvegardeEnCours une écriture est en vol pour cet onglet.
 */
data class EditorTabState(
    val uri: String,
    val cheminRelatif: String,
    val nom: String,
    val langage: String?,
    val isDirty: Boolean = false,
    val sauvegardeEnCours: Boolean = false,
)

/**
 * Intentions utilisateur de l'espace de travail (section 5.3 : `XxxAction`).
 *
 * Le fragment et l'activité n'émettent que des actions ; la logique vit
 * dans le `EditorViewModel`.
 */
sealed interface ActionEditor {
    /** Revérifie l'état d'accès du projet puis recharge l'arborescence. */
    data object Rafraichir : ActionEditor

    /**
     * Déplie ou replie le dossier [uri] — le premier dépliement énumère
     * ses enfants (paresseux), les suivants relisent le cache.
     */
    data class BasculerNoeud(
        val uri: String,
    ) : ActionEditor

    /** Ouvre le fichier [uri] en onglet (les binaires sont détournés). */
    data class OuvrirFichier(
        val uri: String,
    ) : ActionEditor

    /** Sélectionne l'onglet à la position [index]. */
    data class SelectionnerOnglet(
        val index: Int,
    ) : ActionEditor

    /** Ferme l'onglet [uri] — avec confirmation s'il est modifié. */
    data class FermerOnglet(
        val uri: String,
    ) : ActionEditor

    /** Ferme tous les autres onglets — les propres immédiatement. */
    data class FermerAutresOnglets(
        val uri: String,
    ) : ActionEditor

    /** Ferme tous les onglets — les propres immédiatement. */
    data object FermerTousOnglets : ActionEditor

    /** Déplace l'onglet [uri] de [decalage] position(s) (−1 ou +1). */
    data class DeplacerOnglet(
        val uri: String,
        val decalage: Int,
    ) : ActionEditor

    /** Enregistre l'onglet actif (action de la toolbar). */
    data object Enregistrer : ActionEditor

    /** Réponse au dialogue : enregistre puis ferme (puis quitte). */
    data class EnregistrerPuisFermer(
        val uris: List<String>,
        val quitter: Boolean,
    ) : ActionEditor

    /** Réponse au dialogue : ferme sans enregistrer (puis quitte). */
    data class FermerSansEnregistrer(
        val uris: List<String>,
        val quitter: Boolean,
    ) : ActionEditor

    /** Retour système hors tiroir : quitte, après confirmation si sale. */
    data object Quitter : ActionEditor

    /**
     * Change l'état d'ouverture du panneau inférieur (en-tête, bouton
     * agrandir/réduire, glissement, retour système) — persisté pour la
     * rotation.
     */
    data class ChangerEtatPanneau(
        val etat: EtatPanneau,
    ) : ActionEditor

    /** Sélectionne l'onglet actif du panneau inférieur — persisté. */
    data class SelectionnerOngletPanneau(
        val onglet: OngletPanneau,
    ) : ActionEditor

    /**
     * Bascule un niveau du filtre du journal applicatif (vide = tous les
     * niveaux — même règle que l'écran Diagnostic, étape 12).
     */
    data class BasculerFiltreJournal(
        val niveau: LogLevel,
    ) : ActionEditor

    /** Ouvre l'écran Diagnostic complet depuis le journal compact. */
    data object OuvrirJournalComplet : ActionEditor

    /** Crée un fichier dans le dossier [uriParent] (étape 17). */
    data class CreerFichier(
        val uriParent: String,
        val nom: String,
    ) : ActionEditor

    /** Crée un sous-dossier dans le dossier [uriParent] (étape 17). */
    data class CreerDossier(
        val uriParent: String,
        val nom: String,
    ) : ActionEditor

    /** Renomme le document [uri] en [nouveauNom] (étape 17). */
    data class RenommerDocument(
        val uri: String,
        val nouveauNom: String,
    ) : ActionEditor

    /** Supprime le document [uri] après confirmation côté UI (étape 17). */
    data class SupprimerDocument(
        val uri: String,
    ) : ActionEditor

    /**
     * Bascule l'arbre affiché entre projet et stockage privé (étape 31,
     * § 5) : les deux arbres sont exclusifs, la sélection est
     * réinitialisée, les onglets de l'éditeur ne sont pas touchés.
     */
    data class BasculerSource(
        val source: SourceArbre,
    ) : ActionEditor

    /** Sélectionne le nœud [uri] (fil d'Ariane, point d'état, popover). */
    data class SelectionnerNoeud(
        val uri: String,
    ) : ActionEditor

    /** Retient [uri] au presse-papiers en mode copier (étape 31, § 11). */
    data class CopierNoeud(
        val uri: String,
    ) : ActionEditor

    /** Retient [uri] au presse-papiers en mode couper (étape 31, § 11). */
    data class CouperNoeud(
        val uri: String,
    ) : ActionEditor

    /**
     * Colle le presse-papiers dans le dossier [uriDossier] (étape 31,
     * § 11) : copie (mode copier) ou déplacement (mode couper), suffixe
     * anti-collision « (copie N) ».
     */
    data class CollerDans(
        val uriDossier: String,
    ) : ActionEditor

    /**
     * Déplace le document [uri] vers [cheminDestination] relatif à la
     * racine de l'arbre courant (étape 31, popover « Déplacer vers… »).
     */
    data class DeplacerVers(
        val uri: String,
        val cheminDestination: String,
    ) : ActionEditor

    /** Vide le presse-papiers d'arbre (bouton « Vider », § 12). */
    data object ViderPressePapiers : ActionEditor

    /**
     * Débute une création inline dans [uriParent] (étape 31, § 11) : un
     * éditeur apparaît dans la liste, la validation crée le document.
     */
    data class DebuterCreation(
        val uriParent: String,
        val estDossier: Boolean,
    ) : ActionEditor

    /**
     * Débute un renommage inline du nœud [uri] (étape 31, § 11) : la
     * ligne devient un éditeur pré-rempli et sélectionné.
     */
    data class DebuterRenommage(
        val uri: String,
    ) : ActionEditor

    /** Abandonne l'édition inline en cours (Échap, annuler, clic ailleurs). */
    data object AnnulerEdition : ActionEditor

    /** Valide l'édition inline avec [nom] (création ou renommage). */
    data class ValiderEdition(
        val nom: String,
    ) : ActionEditor

    /** Replie tous les dossiers dépliés de l'arbre courant (§ 4). */
    data object ReplierTout : ActionEditor

    /**
     * Annule la dernière suppression (action « Annuler » du snackbar,
     * § 11) : restaure l'élément à sa place et rouvre ses onglets.
     */
    data object AnnulerSuppression : ActionEditor

    /** Masque la notification courante (expiration du snackbar, § 15). */
    data object MasquerNotification : ActionEditor

    /**
     * Précise la langue d'affichage des libellés du catalogue de modèles
     * (étape 18) : émise à la création de l'activité, republiée par le
     * système quand la langue de l'application change (re-création).
     */
    data class PreciserLangue(
        val langue: String,
    ) : ActionEditor

    /**
     * Ouvre le terminal plein écran depuis la carte d'aperçu du tiroir
     * (T6, section 8) : toute la carte et son bouton d'agrandissement.
     */
    data object OuvrirTerminal : ActionEditor

    /**
     * État vide de la carte : crée une session avec le dossier du projet
     * courant comme répertoire de travail, puis ouvre l'écran plein
     * écran dessus (T6, section 8).
     */
    data object NouvelleSessionTerminal : ActionEditor

    /**
     * Bootstrap absent : ouvre l'écran d'installation des outils du
     * terminal (garde-fou symétrique de l'accueil, T6).
     */
    data object InstallerOutilsTerminal : ActionEditor

    /**
     * Synchronise le projet courant auprès de l'orchestrateur (G5, §6) :
     * modèles, tâches, dépendances — la progression se lit dans le panneau
     * inférieur (état de synchronisation de l'onglet Sortie).
     */
    data object Synchroniser : ActionEditor

    /**
     * Exécute les [taches] Gradle du projet courant (G5, §6) — la sortie
     * arrive dans l'onglet Sortie, l'état dans son en-tête.
     */
    data class ExecuterTaches(
        val taches: List<String>,
    ) : ActionEditor

    /**
     * Ouvre le sélecteur de tâches (G5, §6) : liste les tâches du projet
     * via l'orchestrateur, l'appui sur l'une lance l'exécution.
     */
    data object OuvrirSelecteurTaches : ActionEditor

    /** Annule le build en cours (G5, §6 — bouton de l'onglet Sortie). */
    data object AnnulerBuild : ActionEditor
}

/**
 * Événements ponctuels de l'espace de travail (section 5.3 : `XxxEffect`).
 */
sealed interface EffetEditor {
    /** Fichier binaire : proposer « Ouvrir avec » une autre application. */
    data class OuvrirAvec(
        val uri: String,
    ) : EffetEditor

    /** Onglets modifiés : demander Enregistrer / Ne pas enregistrer / Annuler. */
    data class ConfirmerFermeture(
        val uris: List<String>,
        val quitter: Boolean,
    ) : EffetEditor

    /** Chemin d'un onglet copié dans le presse-papiers. */
    data class CopierChemin(
        val chemin: String,
    ) : EffetEditor

    /** L'espace de travail peut se refermer. */
    data object Quitter : EffetEditor

    /** La lecture d'un fichier a échoué. */
    data object ErreurOuverture : EffetEditor

    /** L'enregistrement a échoué : le contenu reste non enregistré. */
    data object ErreurEnregistrement : EffetEditor

    /** Lien « Ouvrir le journal complet » : naviguer vers l'écran Diagnostic. */
    data object OuvrirJournalComplet : EffetEditor

    /** Une opération de fichier (créer/renommer/supprimer) a échoué. */
    data object ErreurActionFichier : EffetEditor

    /**
     * Ouvrir l'écran plein écran du terminal (T6, section 8) : même liste
     * globale de sessions que depuis l'accueil.
     *
     * @property cheminTravail répertoire suggéré (dossier réel du projet
     * via `ResoudreRepertoireProjet`), ou `null` — l'écran terminal
     * repliera sur le `HOME` canonique.
     */
    data class OuvrirTerminal(
        val cheminTravail: String?,
    ) : EffetEditor

    /** Ouvrir l'écran d'installation du bootstrap (T6, bootstrap absent). */
    data object OuvrirInstallationTerminal : EffetEditor

    /**
     * Ouvrir le sélecteur de tâches Gradle (G5, §6) : l'appui sur une
     * entrée lance `ExecuterTaches`.
     *
     * @property taches tâches prêtes à afficher (chemin + libellé).
     */
    data class OuvrirSelecteurTaches(
        val taches: List<jo.codeide.core.domain.InfoTache>,
    ) : EffetEditor
}

/**
 * État de la carte d'aperçu du terminal dans le tiroir (T6, section 8).
 *
 * Zéro dépendance Termux : tout vient de
 * [jo.codeide.core.domain.TerminalSessionSummary] — c'est le critère
 * d'acceptation de la section 11 du prompt Terminal-1 (feature:editor
 * ne dépend d'aucune bibliothèque `com.termux:*`).
 *
 * @property bootstrapInstalle bootstrap présent : la carte propose
 * « Nouvelle session dans ce projet », sinon l'installation.
 * @property nbSessions nombre total de sessions du registre global
 * (vivantes **et** terminées) — l'état vide se juge sur lui.
 * @property sessionsVivantes nombre de sessions actives (vivantes).
 * @property sessionActive session sélectionnée dans le registre global
 * (ou dernière vivante), dont la carte montre libellé et dernière sortie.
 */
data class EtatTerminalTiroir(
    val bootstrapInstalle: Boolean = false,
    val nbSessions: Int = 0,
    val sessionsVivantes: Int = 0,
    val sessionActive: jo.codeide.core.domain.TerminalSessionSummary? = null,
)

/**
 * Type de projet reconnu à l'ouverture, prêt à afficher (étape 18) : le
 * nom du modèle est résolu depuis le catalogue (i18n du moteur) avec
 * repli sur l'identifiant brut — l'interface n'a plus qu'à composer sa
 * chaîne localisée.
 *
 * @property nomModele nom affichable du modèle (i18n) ou identifiant brut.
 * @property versionModele version du modèle à la création, ou `null`.
 */
data class TypeProjetAffiche(
    val nomModele: String,
    val versionModele: String? = null,
)

/**
 * État observable de l'espace de travail (étapes 13-18).
 *
 * Volontairement incrémental : la reprise par projet (`workspace-state.json`)
 * et les actions de fichiers du tiroir arrivent à l'étape 17, la
 * reconnaissance du type à l'étape 18 — l'état grandit avec, jamais avant.
 *
 * @property chargement première lecture du projet en cours.
 * @property projet projet ouvert, ou `null` si l'identifiant reçu
 * n'existe plus au registre (l'écran le signale au lieu de planter).
 * @property acces état d'accès calculé du projet (`null` : pas encore
 * vérifié) — pilote le bandeau du tiroir (prompt compagnon 5.3).
 * @property verificationAcces vérification d'accès en vol (bouton
 * Actualiser et premier chargement).
 * @property erreurRacine l'énumération de la racine a échoué pour une
 * raison autre que permission perdue / dossier introuvable : bandeau
 * générique, réessayable.
 * @property noeuds liste aplatie des nœuds visibles de l'explorateur.
 * @property onglets fichiers ouverts en onglets (étape 15).
 * @property indexOngletActif position de l'onglet actif, −1 si aucun.
 * @property etatPanneau état d'ouverture du panneau inférieur (étape 16).
 * @property ongletPanneau onglet actif du panneau inférieur (étape 16).
 * @property entreesJournal fenêtre compacte des entrées récentes du
 * journal applicatif, filtrée par niveaux (étape 16).
 * @property filtresJournal niveaux retenus — vide = tous les niveaux
 * (même règle que l'écran Diagnostic).
 * @property typeProjet type reconnu depuis `.codeide/project.json`
 * (étape 18) : `null` tant que la racine n'est pas lisible — l'interface
 * distingue alors dossier importé et type non reconnu.
 * @property source arbre affiché : projet ou stockage privé (étape 31,
 * § 5 — exclusifs).
 * @property uriSelection URI du nœud sélectionné, ou `null` (fil
 * d'Ariane, point d'état « sélectionné », popover).
 * @property segmentsAriane ancêtres de la sélection (§ 4) — racine seule
 * si aucune sélection.
 * @property pressePapiers presse-papiers d'arbre (§ 12) — `null` si vide.
 * @property edition édition inline en cours (§ 11), ou `null`.
 * @property notification snackbar maison de l'explorateur (§ 15), ou
 * `null` si masqué.
 * @property urisFlachees lignes à flasher après mutation (§ 6.1), le
 * temps du flash de 1,1 s.
 * @property cheminRacine chemin affiché sous le titre de l'entête du
 * fragment (§ 4 : chemin de la racine affichée).
 * @property nomRacine nom d'affichage de la racine (projet uniquement).
 * @property cheminsDossiers chemins relatifs des dossiers énumérés de
 * l'arbre courant (autocomplétion du popover « Déplacer vers… », § 10.5).
 */
data class EtatEditor(
    val chargement: Boolean = true,
    val projet: Project? = null,
    val acces: ProjectAccessState? = null,
    val verificationAcces: Boolean = false,
    val erreurRacine: Boolean = false,
    val noeuds: List<NoeudExplorateur> = emptyList(),
    val onglets: List<EditorTabState> = emptyList(),
    val indexOngletActif: Int = -1,
    val etatPanneau: EtatPanneau = EtatPanneau.REPLIE,
    val ongletPanneau: OngletPanneau = OngletPanneau.JOURNAL,
    val entreesJournal: List<LogEntry> = emptyList(),
    val filtresJournal: Set<LogLevel> = emptySet(),
    val typeProjet: TypeProjetAffiche? = null,
    val source: SourceArbre = SourceArbre.PROJET,
    val uriSelection: String? = null,
    val segmentsAriane: List<SegmentAriane> = emptyList(),
    val pressePapiers: PressePapiersArbre? = null,
    val edition: EditionInline? = null,
    val notification: NotificationArbre? = null,
    val urisFlachees: Set<String> = emptySet(),
    val cheminRacine: String = "",
    val nomRacine: String? = null,
    val cheminsDossiers: List<String> = emptyList(),
)

/** Clés partagées de l'espace de travail. */
object ClesEditor {
    /** Extra d'intention : identifiant du projet à ouvrir. */
    const val EXTRA_PROJECT_ID: String = "jo.codeide.editor.PROJECT_ID"

    /** Sauvetage : onglets ouverts (« uri \n chemin »), étape 15. */
    const val CLE_ONGLETS: String = "onglets_ouverts"

    /** Sauvetage : position de l'onglet actif, étape 15. */
    const val CLE_INDEX_ACTIF: String = "index_onglet_actif"

    /** Sauvetage : état d'ouverture du panneau inférieur, étape 16. */
    const val CLE_ETAT_PANNEAU: String = "etat_panneau"

    /** Sauvetage : onglet actif du panneau inférieur, étape 16. */
    const val CLE_ONGLET_PANNEAU: String = "onglet_panneau"

    /** Sauvetage : filtres de niveaux du journal compact, étape 16. */
    const val CLE_FILTRES_JOURNAL: String = "filtres_journal"
}
