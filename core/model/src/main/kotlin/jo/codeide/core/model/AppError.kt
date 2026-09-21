package jo.codeide.core.model

/**
 * Erreur applicative modélisée (section 5.5 du prompt maître).
 *
 * Chaque variante est traduisible par l'UI en message **localisé et
 * actionnable** ; les détails techniques (champ [details]) ne sont destinés
 * ni à l'affichage direct ni à l'utilisateur : ils partent dans les
 * journaux via `AppLogger` (identifiants et messages techniques uniquement,
 * jamais de donnée personnelle — règle 15 du prompt maître).
 */
public sealed interface AppError {
    /**
     * Raison précise d'une erreur de stockage (SAF, base, fichiers).
     *
     * La distinction est volontairement fine : l'accueil et le wizard
     * proposent une action de résolution différente selon la raison
     * (relocaliser un dossier, demander l'autorisation, libérer de l'espace).
     */
    public enum class StorageReason {
        /** La permission persistante sur l'arborescence a été révoquée ou expirée. */
        PermissionLost,

        /** Le dossier ou le document visé n'existe plus. */
        NotFound,

        /** L'élément à créer existe déjà (jamais d'écrasement silencieux). */
        AlreadyExists,

        /** L'espace de stockage disponible est insuffisant. */
        NoSpace,

        /** L'emplacement n'est pas accessible en écriture. */
        NotWritable,

        /** Autre erreur d'entrée/sortie non classée. */
        Io,
    }

    /**
     * Erreur d'accès au stockage (SAF, système de fichiers).
     *
     * @property reason raison normalisée, pilotant le message et l'action proposée.
     * @property details contexte technique pour les journaux (jamais affiché tel quel).
     */
    public data class Storage(
        public val reason: StorageReason,
        public val details: String = "",
    ) : AppError

    /**
     * Entrée utilisateur invalide (nom de projet, package, semver…).
     *
     * @property details description technique du ou des champs en cause,
     * pour les journaux ; l'affichage s'appuie sur le validateur concerné.
     */
    public data class Validation(
        public val details: String = "",
    ) : AppError

    /**
     * Erreur liée à un modèle de projet (manifeste illisible, expression
     * invalide, fichier manquant…).
     *
     * @property details contexte technique pour les journaux.
     */
    public data class Template(
        public val details: String = "",
    ) : AppError

    /**
     * Erreur non prévue par le domaine (par défaut, imprévue).
     *
     * @property details contexte technique pour les journaux.
     */
    public data class Unknown(
        public val details: String = "",
    ) : AppError
}
