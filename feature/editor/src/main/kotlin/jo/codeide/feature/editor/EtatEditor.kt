package jo.codeide.feature.editor

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
}

/**
 * État observable de l'espace de travail (étapes 13-14).
 *
 * Volontairement incrémental : les onglets ouverts et l'état du panneau
 * inférieur arriveront avec les étapes 15 et 16 — l'état grandit avec,
 * jamais avant.
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
 */
data class EtatEditor(
    val chargement: Boolean = true,
    val projet: Project? = null,
    val acces: ProjectAccessState? = null,
    val verificationAcces: Boolean = false,
    val erreurRacine: Boolean = false,
    val noeuds: List<NoeudExplorateur> = emptyList(),
)

/** Clés partagées de l'espace de travail. */
object ClesEditor {
    /** Extra d'intention : identifiant du projet à ouvrir. */
    const val EXTRA_PROJECT_ID: String = "jo.codeide.editor.PROJECT_ID"
}
