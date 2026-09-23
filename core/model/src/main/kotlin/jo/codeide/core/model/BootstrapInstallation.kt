package jo.codeide.core.model

/**
 * Progression de l'installation du bootstrap natif (prompt compagnon
 * Terminal-1, section 3.4 — écran d'installation de l'étape T3).
 *
 * L'installateur émet ces étapes en temps réel ; comme
 * [CreationProgress], elles ne contiennent que des données d'affichage
 * (compteurs, noms de paquets) — la journalisation reste aux
 * identifiants (règle 15 du prompt maître).
 *
 * L'état partagé (et non la seule progression) est porté par
 * [EtatInstallationBootstrap] : l'installation est un singleton
 * observable depuis les deux points d'entrée (onboarding et
 * déclenchement à la demande), jamais rejouée en parallèle.
 */
public sealed interface EtapeInstallation {
    /** Vérification de l'espace disque disponible avant tout téléchargement. */
    public data object VerificationEspaceDisque : EtapeInstallation

    /**
     * Téléchargement de l'archive du bootstrap.
     *
     * @property octetsRecus octets déjà reçus.
     * @property octetsTotaux taille totale annoncée par le serveur, ou
     * `null` si inconnue (progression indéterminée).
     */
    public data class Telechargement(
        public val octetsRecus: Long,
        public val octetsTotaux: Long?,
    ) : EtapeInstallation

    /**
     * Extraction des fichiers réguliers de l'archive vers le répertoire
     * de préparation.
     *
     * @property entreesTraitees nombre d'entrées extraites à ce stade.
     */
    public data class Extraction(
        public val entreesTraitees: Int,
    ) : EtapeInstallation

    /** Création des liens symboliques décrits par `SYMLINKS.txt`. */
    public data object LiensSymboliques : EtapeInstallation

    /** Bascule atomique du répertoire de préparation vers `$PREFIX`. */
    public data object BasculeVersPrefixe : EtapeInstallation

    /** Exécution du script de second stage (configuration des paquets). */
    public data object SecondStage : EtapeInstallation

    /** Écriture du `sources.list` du dépôt APT CodeIDE (avec correction d'URL). */
    public data object ConfigurationApt : EtapeInstallation

    /** `apt update` contre le dépôt APT CodeIDE. */
    public data object MiseAJourApt : EtapeInstallation

    /**
     * Installation des paquets d'outils (`apt install`), un paquet à la
     * fois — un paquet absent ou en échec ne fait pas échouer les autres
     * (il est signalé non installé dans l'état terminal).
     *
     * @property paquet nom du paquet APT en cours d'installation
     * (ex. `openjdk-17`).
     * @property index rang du paquet dans la liste (1-based).
     * @property total nombre total de paquets à installer.
     */
    public data class InstallationPaquets(
        public val paquet: String,
        public val index: Int,
        public val total: Int,
    ) : EtapeInstallation
}

/**
 * État partagé de l'installation du bootstrap, exposé en `StateFlow` par
 * le port `BootstrapInstaller` du domaine.
 *
 * Machine à états : `NonDemarree → EnCours⁺ → {Terminee, Echouee,
 * Annulee}` ; un nouvel appel à `demarrer()` n'est accepté que depuis
 * `NonDemarree`, `Echouee` ou `Annulee` (jamais pendant `EnCours`, ni
 * après `Terminee` — le bootstrap est déjà en place).
 */
public sealed interface EtatInstallationBootstrap {
    /** Aucune installation n'a encore été tentée depuis le lancement. */
    public data object NonDemarree : EtatInstallationBootstrap

    /** Installation en cours, à l'étape indiquée. */
    public data class EnCours(
        public val etape: EtapeInstallation,
    ) : EtatInstallationBootstrap

    /**
     * Installation terminée avec succès.
     *
     * @property outils état d'installation de chaque paquet d'outil
     * demandé (un paquet absent du dépôt APT est signalé `installe =
     * false` sans faire échouer l'ensemble).
     */
    public data class Terminee(
        public val outils: List<OutilResume>,
    ) : EtatInstallationBootstrap

    /** Installation échouée — l'erreur est typée et actionnable. */
    public data class Echouee(
        public val erreur: AppError,
    ) : EtatInstallationBootstrap

    /** Installation annulée par l'utilisateur (répertoires de préparation nettoyés). */
    public data object Annulee : EtatInstallationBootstrap
}

/**
 * État d'installation d'un paquet d'outil après une installation
 * terminée.
 *
 * @property paquet nom du paquet APT (ex. `openjdk-17`).
 * @property installe `true` si le paquet a été installé avec succès.
 */
public data class OutilResume(
    public val paquet: String,
    public val installe: Boolean,
)
