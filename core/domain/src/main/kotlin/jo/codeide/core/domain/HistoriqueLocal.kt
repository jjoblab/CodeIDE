package jo.codeide.core.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Type d'une entrée d'historique local (mission H, ADR 0104) — ce que
 * l'entrée a capturé, et donc ce qu'elle permet de retrouver.
 */
public enum class TypeEntreeHistorique {
    /** Écriture d'un fichier existant — contenu PRÉCÉDENT conservé. */
    MODIFICATION,

    /** Première apparition d'un chemin (création réelle ou détectée). */
    CREATION,

    /** Suppression — PIERRE TOMBALE : dernier contenu conservé. */
    SUPPRESSION,

    /** Renommage (ou déplacement) — [EntreeHistorique.libelle] porte l'ancien nom. */
    RENOMMAGE,

    /**
     * Changement détecté HORS de l'application (terminal, git, autre
     * app) — contenu EXTERNE conservé (ADR 0106 : détection à
     * l'ouverture / rafraîchissement, jamais de scrutation).
     */
    EXTERNE,

    /** Restauration d'une révision (créée par l'écriture du port, ADR 0106). */
    RESTAURATION,

    /** Étiquette nommée (H4 — point d'extension des actions risquées). */
    ETIQUETTE,
}

/**
 * Une révision enregistrée par l'historique local (ADR 0104 : le
 * contenu VIVANT d'une entrée est l'état **AVANT** la transition
 * capturée — l'état courant reste sur le disque, jamais doublé).
 *
 * @property id identifiant croissant (ordre chronologique).
 * @property cheminRelatif chemin du fichier relatif à la racine du projet.
 * @property type nature de la transition.
 * @property horodatageMs instant de l'enregistrement.
 * @property empreinte empreinte SHA-256 du contenu stocké, ou `null`
 *           si le contenu est INDISPONIBLE (binaire, taille au-delà du
 *           plafond — affiché honnêtement, jamais inventé).
 * @property tailleOctets taille du contenu au moment de la capture.
 * @property libelle précision du type : ancien nom (renommage), nom
 *           d'étiquette, ou libellé d'action.
 */
public data class EntreeHistorique(
    public val id: Long,
    public val cheminRelatif: String,
    public val type: TypeEntreeHistorique,
    public val horodatageMs: Long,
    public val empreinte: String?,
    public val tailleOctets: Long,
    public val libelle: String? = null,
) {
    /** Le contenu de cette révision est-il consultable ? */
    public val contenuDisponible: Boolean get() = empreinte != null
}

/**
 * Politique de conservation de l'historique local (ADR 0105) — type
 * valeur du domaine, réglable (H6), défaut documenté.
 *
 * Les plafonds mobiles (absents chez IntelliJ, nécessaires sur un
 * téléphone) : quota par projet, taille par fichier, nombre d'entrées.
 *
 * @property joursRetention entrées plus vieilles supprimées à la purge.
 * @property quotaOctets taille maximale des blobs d'un projet (les
 *           entrées les plus anciennes d'abord).
 * @property tailleMaxFichierOctets au-delà : entrée SANS contenu.
 * @property nbMaxEntrees borne de l'index.
 */
public data class PolitiqueHistorique(
    public val joursRetention: Int = JOURS_RETENTION_DEFAUT,
    public val quotaOctets: Long = QUOTA_OCTETS_DEFAUT,
    public val tailleMaxFichierOctets: Long = TAILLE_MAX_FICHIER_DEFAUT,
    public val nbMaxEntrees: Int = NB_MAX_ENTREES_DEFAUT,
) {
    /**
     * Le chemin relatif [chemin] est-il EXCLU de l'historique (artefacts
     * de build, état d'outils, SECRETS — jamais historisés, ADR 0105) ?
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — une issue par
     * garde (chemin vide, dossier exclu, secret), mêmes gardes de
     * sécurité que le moteur de templates.
     */
    @Suppress("ReturnCount")
    public fun exclut(chemin: String): Boolean {
        val segments = chemin.split('/')
        val nomFichier = segments.lastOrNull() ?: return true
        if (segments.dropLast(1).any { it in DOSSIERS_EXCLUS }) return true
        return SECRETS_EXCLUS.any { motif ->
            if (motif.startsWith("*")) nomFichier.endsWith(motif.drop(1)) else nomFichier == motif
        }
    }

    private companion object {
        /** Rétention par défaut : 5 jours (aligné Android Studio, ADR 0105). */
        const val JOURS_RETENTION_DEFAUT = 5

        /** Quota par projet : 256 Mo (stockage privé borné). */
        const val QUOTA_OCTETS_DEFAUT = 256L * 1024 * 1024

        /** Taille maximale par fichier : 2 Mo de texte. */
        const val TAILLE_MAX_FICHIER_DEFAUT = 2L * 1024 * 1024

        /** Nombre maximal d'entrées par projet. */
        const val NB_MAX_ENTREES_DEFAUT = 5_000

        /** Segments de dossier exclus (artefacts, état, dépendances). */
        val DOSSIERS_EXCLUS =
            setOf("build", ".gradle", ".git", ".codeide", ".idea", "node_modules", ".idea/libraries")

        /** Secrets : JAMAIS stockés dans le stockage privé au titre de l'historique. */
        val SECRETS_EXCLUS =
            listOf(
                "local.properties",
                "secrets.properties",
                "google-services.json",
                "*.jks",
                "*.keystore",
                "*.env",
                "*.pem",
                "*.p12",
                "*.key",
            )
    }
}

/**
 * Racine du projet OUVERT, source de la capture (ADR 0106) : l'espace
 * d'édition la met à jour à l'ouverture/fermeture du projet — le
 * décorateur [HistoriqueFileSystem] n'enregistre que les mutations des
 * documents SOUS cette racine (l'arbre privé et les autres projets ne
 * sont pas capturés par le projet ouvert).
 */
@Singleton
public class SourceProjetHistorique
    @Inject
    constructor() {
        /** URI de document de la racine du projet ouvert, ou `null`. */
        @Volatile
        public var racineDocument: String? = null
    }

/**
 * Port de l'historique local (mission H1, ADR 0104-0106) — moteur JVM
 * pur au-dessus de blobs dédupliqués et d'un index atomique dans le
 * stockage privé (jamais dans le dossier du projet).
 */
public interface HistoriqueLocal {
    /**
     * Enregistre une transition — [contenu] est l'état AVANT pour les
     * écritures/suppressions, l'état EXTERNE pour les détections ; la
     * politique décide du reste (taille maximale : entrée sans contenu ;
     * AUCUNE entrée si l'empreinte égale celle de la précédente du même
     * chemin — pas d'historique de bruit).
     *
     * @return l'entrée créée, ou `null` (rien à enregistrer).
     */
    public suspend fun enregistrer(
        cheminRelatif: String,
        type: TypeEntreeHistorique,
        contenu: String?,
        tailleOctets: Long = contenu?.length?.toLong() ?: 0L,
        libelle: String? = null,
    ): EntreeHistorique?

    /** Révisions du [cheminRelatif], plus récentes d'abord. */
    public suspend fun listerRevisions(cheminRelatif: String): List<EntreeHistorique>

    /**
     * Révisions des fichiers SOUS [cheminDossier] (mission H3) :
     * l'historique d'un DOSSIER de l'explorateur (préfixe strict
     * `dossier/` — `src` ne couvre pas `srcX/` — ET le dossier
     * lui-même : une étiquette posée SUR le dossier y paraît, H4),
     * plus récentes d'abord, bornées à [limite]. Un chemin VIDE
     * désigne la racine du projet — les « **Modifications
     * récentes** » du projet ENTIER (les chemins exclus ne sont
     * jamais capturés, donc jamais listés).
     */
    public suspend fun listerRevisionsSous(
        cheminDossier: String,
        limite: Int = LIMITE_REVISIONS_SOUS_DOSSIER,
    ): List<EntreeHistorique>

    /** Contenu stocké de l'entrée [id], ou `null` (indisponible / inconnue). */
    public suspend fun lireContenu(id: Long): String?

    /**
     * Dernière empreinte ENREGISTRÉE du chemin (détection externe —
     * comparer au contenu lu), ou `null` si le chemin est inconnu.
     */
    public suspend fun derniereEmpreinte(cheminRelatif: String): String?

    /**
     * Pose une étiquette nommée (H4 — point d'extension des actions
     * risquées : « Avant compilation », « Avant bascule de branche »).
     */
    public suspend fun etiqueter(
        nom: String,
        cheminRelatif: String? = null,
    ): EntreeHistorique?

    /**
     * Purge (âge + quota + nombre d'entrées), incrémentale : les
     * entrées les plus anciennes d'abord, puis les blobs devenus
     * orphelins. Appelée à l'ouverture du projet (E/S, hors fil
     * principal) — jamais bloquante pour l'appelant.
     */
    public suspend fun purger()

    public companion object {
        /** Borne des révisions d'un dossier / du projet (UI : la feuille ne rend jamais plus). */
        public const val LIMITE_REVISIONS_SOUS_DOSSIER: Int = 200
    }
}
