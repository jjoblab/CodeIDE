package jo.codeide.core.ai

/**
 * Type d'une pièce de contexte (mission IA, lot IA-4, ADR 0124 §4) :
 * ce que la puce du compositeur affiche EST ce qui part — aucun contenu
 * implicite en plus des pièces listées ici.
 */
public enum class TypePieceContexte {
    /** Fichier actif dans l'éditeur (interrupteur de confidentialité). */
    FichierActif,

    /** Sélection courante de l'éditeur (contenu explicite de l'utilisateur). */
    Selection,

    /** Diagnostics visibles pour le fichier actif (interrupteur). */
    Diagnostics,

    /** Fichier `@mentionné` explicitement par l'utilisateur. */
    FichierMentionne,

    /** Liste des chemins RELATIFS du projet, jamais leurs contenus (interrupteur). */
    ListeChemins,
}

/**
 * Une pièce de contexte assemblée : l'unité affichée par une puce
 * retirable du compositeur. [jetons] est l'estimation locale du contenu
 * EFFECTIVEMENT envoyé (troncature comprise) — la puce reflète exactement
 * ce qui part (critère d'acceptation IA-4).
 */
public data class PieceContexte(
    public val type: TypePieceContexte,
    /** Chemin RELATIF au projet, quand la pièce porte sur un fichier. */
    public val cheminRelatif: String? = null,
    /** Libellé de puce localisé côté UI — le domaine porte une clé brute. */
    public val cle: String,
    /** Estimation prudente des jetons de cette pièce. */
    public val jetons: Long,
)

/**
 * Avertissement de détection préventive (ADR 0124 §4) : un motif de
 * secret a été trouvé dans le contexte — la VALEUR n'est jamais portée,
 * seulement l'étiquette du motif et l'endroit.
 */
public data class AlerteSecret(
    /** Étiquette fermée du motif (`sk-…`, `Bearer`, clé privée…). */
    public val motif: String,
    /** Chemin relatif de la pièce en cause, ou `null` (sélection). */
    public val cheminRelatif: String? = null,
)

/**
 * Mention ou pièce REFUSÉE (ADR 0124 §4 : « jamais envoyés, même
 * mentionnés, avec message explicite ») — le refus est rendu à l'UI,
 * jamais avalé.
 */
public data class CheminRefuse(
    public val chemin: String,
    /** Raison fermée : `exclusion` (liste par défaut ou `ia-ignore`) ou `illisible`. */
    public val raison: String,
)

/**
 * Résultat de l'assemblage : les pièces (puces), le texte ENCADRÉ qui
 * part vers le fournisseur, l'estimation de jetons, les alertes de
 * secrets et les refus. [texte] est vide quand aucune pièce n'est
 * incluse — la requête part alors sans bloc de contexte.
 */
public data class ContexteIa(
    public val pieces: List<PieceContexte>,
    /** Encadrement neutre (ADR 0124 §4 bis) — la donnée n'est jamais une consigne. */
    public val texte: String,
    public val jetons: Long,
    public val alertes: List<AlerteSecret>,
    public val refuses: List<CheminRefuse>,
)
