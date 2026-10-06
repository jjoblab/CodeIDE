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
     * Raison précise d'un échec d'installation du bootstrap natif
     * (prompt compagnon Terminal-1, section 3.4 : « chaque échec produit
     * un AppResult/AppError explicite »).
     */
    public enum class BootstrapReason {
        /** Le dépôt des releases est injoignable, ou la réponse est invalide (réseau). */
        ReseauIndisponible,

        /** L'espace disque disponible est insuffisant avant le téléchargement. */
        EspaceDisqueInsuffisant,

        /** L'archive téléchargée est corrompue (format zip invalide, entrées incohérentes). */
        ArchiveCorrompue,

        /** L'empreinte SHA-256 de l'archive ne correspond pas à celle publiée. */
        EmpreinteInvalide,

        /** Une opération de fichier a été refusée par le système (permissions). */
        PermissionRefusee,

        /** Le script de second stage a échoué (code de sortie non nul). */
        EchecSecondStage,

        /** `apt update` ou `apt install` a échoué. */
        EchecApt,

        /** Le binaire attendu dans les assets de l'application est absent. */
        AssetAbsent,

        /** L'architecture de l'appareil n'est pas couverte par un bootstrap publié (aarch64 seul). */
        ArchitectureNonSupportee,
    }

    /**
     * Erreur d'installation du bootstrap natif (téléchargement,
     * extraction, second stage, paquets APT).
     *
     * @property reason raison normalisée, pilotant le message et l'action
     * proposée (réessayer, libérer de l'espace, vérifier la connexion).
     * @property details contexte technique pour les journaux (jamais
     * affiché tel quel).
     */
    public data class Bootstrap(
        public val reason: BootstrapReason,
        public val details: String = "",
    ) : AppError

    /**
     * Raison normalisée d'un échec du parcours d'installation de
     * l'environnement de développement (refonte E1, ADR 0084/0085).
     *
     * Chaque raison correspond à un mode d'échec **reproduit** (ADR 0084) :
     * le diagnostic complet — commande, code de retour, dernières lignes de
     * sortie — accompagne l'erreur ([EnvironmentSetup.sortie]), jamais un
     * message seul.
     */
    public enum class EnvironmentSetupReason {
        /** Le réseau est indisponible ou tous les miroirs déclarés sont épuisés. */
        Reseau,

        /** L'espace disque disponible est insuffisant pour la phase en cours. */
        EspaceDisque,

        /** La somme SHA-256 d'une archive téléchargée ne correspond pas à l'attendu. */
        SommeControle,

        /** Une commande a échoué : code de retour non nul, sortie capturée. */
        Commande,

        /** La JVM ne démarre pas ou son truststore est inutilisable (ADR 0084, mode R5/R6). */
        Jvm,

        /** Une opération a été refusée par le système (permissions, exécution). */
        Permissions,

        /** Le parcours a été annulé par l'utilisateur. */
        Annulation,

        /** Le manifeste d'outils est invalide ou incompatible avec les exigences du catalogue. */
        ManifesteInvalide,

        /**
         * L'ABI de l'appareil n'est pas `arm64-v8a` (phase 1, ADR 0087) :
         * l'archive du bootstrap n'est publiée que pour `aarch64`.
         */
        ArchitectureNonSupportee,
    }

    /**
     * Sortie d'une commande exécutée, capturée intégralement puis bornée —
     * le contrat « aucune sortie jetée » de la refonte (ADR 0084) : le
     * diagnostic réel (code + dernières lignes de stdout et stderr, dans
     * l'ordre d'émission) accompagne toujours l'échec.
     *
     * @property commande la commande exécutée (programme et arguments),
     * expurgée des données personnelles avant affichage.
     * @property exitCode code de retour du processus.
     * @property lastLines dernières lignes de sortie (stdout + stderr),
     * bornées par l'implémentation (200 lignes), expurgées.
     */
    public data class CommandOutput(
        public val commande: String,
        public val exitCode: Int,
        public val lastLines: List<String>,
    )

    /**
     * Erreur du parcours d'installation de l'environnement (quatre phases,
     * ADR 0085) : réseau, espace disque, somme de contrôle, commande, JVM,
     * permissions, annulation, manifeste.
     *
     * @property reason raison normalisée, pilotant le message et l'action
     * proposées par l'UI.
     * @property details contexte technique pour les journaux (jamais
     * affiché tel quel).
     * @property sortie sortie de la commande en cause le cas échéant —
     * interdit le « SDK non fonctionnel » sans indice (critère
     * d'acceptation de la refonte).
     */
    public data class EnvironmentSetup(
        public val reason: EnvironmentSetupReason,
        public val details: String = "",
        public val sortie: CommandOutput? = null,
    ) : AppError

    /**
     * Erreur du tooling Gradle client-serveur (prompt compagnon Tooling,
     * G3+) : l'orchestrateur signale un [code machine-lisible], jamais une
     * chaîne libre — l'UI traduit, elle n'interprète pas le texte brut.
     *
     * @property code code normalisé de l'échec (version de protocole,
     * handshake, délai, connexion perdue…).
     * @property message message humain de l'orchestrateur, pour le journal.
     */
    public data class Tooling(
        public val code: ToolingReason,
        public val message: String,
    ) : AppError

    /**
     * Raisons normalisées d'un échec du tooling Gradle — miroir des
     * `ErrorCode` du protocole (tooling:protocol), au vocabulaire du
     * domaine.
     */
    public enum class ToolingReason {
        /** Versions de protocole incompatibles app ↔ orchestrateur. */
        ProtocolVersion,

        /** Secret de handshake invalide. */
        Handshake,

        /** Requête inconnue de l'orchestrateur. */
        UnknownRequest,

        /** Frame trop grande ou corrompue (garde DoS du framing). */
        Frame,

        /** Délai de garde dépassé (build, synchronisation, tâches…). */
        Timeout,

        /** Connexion avec l'orchestrateur perdue. */
        ConnectionLost,

        /** Le lancement d'un build a échoué avant tout événement. */
        BuildLaunch,

        /** Erreur interne non classée de l'orchestrateur. */
        Internal,
    }

    /**
     * Erreur non prévue par le domaine (par défaut, imprévue).
     *
     * @property details contexte technique pour les journaux.
     */
    public data class Unknown(
        public val details: String = "",
    ) : AppError
}
