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
 * @property uri URI de document SAF du nœud.
 * @property nom nom d'affichage (dernier segment).
 * @property estDossier `true` pour un dossier (dépliable), `false` pour un
 * fichier (ouverture en onglet à l'étape 15).
 * @property profondeur niveau d'imbrication sous la racine (0 = enfant
 * direct du dossier du projet) — sert à l'indentation de la ligne.
 * @property deplie dossier déplié (chevron tourné, enfants visibles dessous).
 * @property chargementEnfants énumération des enfants en vol (latence SAF).
 * @property erreurChargement la dernière énumération a échoué : la ligne le
 * signale, un nouvel appui réessaie.
 */
data class NoeudExplorateur(
    val uri: String,
    val nom: String,
    val estDossier: Boolean,
    val profondeur: Int,
    val deplie: Boolean = false,
    val chargementEnfants: Boolean = false,
    val erreurChargement: Boolean = false,
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
}

/**
 * État observable de l'espace de travail (étapes 13-16).
 *
 * Volontairement incrémental : la reprise par projet (`workspace-state.json`)
 * et les actions de fichiers du tiroir arrivent à l'étape 17 — l'état
 * grandit avec, jamais avant.
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
