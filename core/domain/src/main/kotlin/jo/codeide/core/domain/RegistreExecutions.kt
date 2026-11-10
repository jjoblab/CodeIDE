package jo.codeide.core.domain

/**
 * Le REGISTRE DES EXÉCUTIONS du Centre de sorties (ADR 0161 décision 1,
 * lot L3) — les N dernières exécutions (build ET sync) restent
 * consultables ENTRE DEUX démarrages de CodeIDE : identité (id, type,
 * tâches demandées), horodatages de début/fin, verdict, durée, compteurs
 * du résumé ([EtatExecutionGradle]), la sortie textuelle BORNÉE et la
 * référence des journaux serveur (`gradle-server`) de la session.
 *
 * **Contrats de bornes** (ADR 0161, rappelés en KDoc de chaque méthode) :
 *
 * - N exécutions au plus (défaut 10 — le réglage fin arrive au lot L6,
 *   M13) : la plus ancienne est sacrifiée d'abord ;
 * - plafond de taille PAR exécution (défaut 20 000 lignes) : au-delà, la
 *   troncature épargne la TÊTE et la QUEUE (les lignes du milieu partent
 *   en premier — le début et la conclusion d'un build sont ce qu'on
 *   relit) et est ANNONCÉE dans [ExecutionSortie.troncature] (bornes
 *   exactes — jamais de perte silencieuse, règle 14) ;
 * - plafond d'octets GLOBAL : tout l'historique tient sous un budget ;
 *   l'exécution la plus ancienne part d'abord ;
 * - expurgation À L'ÉCRITURE : toute ligne qui entre ici passe par
 *   [ExpurgateurSorties] (relativisation des chemins du projet +
 *   [LogRedactor]) — l'implémentation est responsable du contrôle ; le
 *   live n'est jamais altéré ;
 * - écriture par LOT ([ajouterLignes]), jamais par ligne (spécification
 *   de format ADR 0161.6) ; la lecture est idempotente (`ouvrirLignes`
 *   rend les mêmes lignes qu'à l'écriture).
 *
 * Port pur (`core:domain`) — l'implémentation fichier vit dans `app`
 * (JSON Lines gzip + index JSON, même discipline que
 * `ArchiveSessionsJournal` du Logcat) ; les tests utilisent un faux en
 * mémoire.
 */
public interface RegistreExecutions {

    /**
     * Les exécutions conservées, la plus RÉCENTE d'abord. Un stockage
     * absent, vide ou corrompu se lit comme une liste vide — jamais
     * d'exception (c'est une consultation, pas une donnée critique).
     */
    public suspend fun charger(): List<ExecutionSortie>

    /**
     * Relit les lignes d'UNE exécution (bornes de troncature respectées,
     * ordre du flux) — idempotent. Une exécution inconnue rend une liste
     * vide (jamais d'exception : le stockage peut avoir été effacé
     * entre-temps).
     */
    public suspend fun ouvrirLignes(id: String): List<LigneSortie>

    /**
     * Ouvre l'exécution dans le registre (en-tête posé AU DÉPART, verdict
     * et durée encore inconnus) — l'en-tête placeholder survit à la mort
     * du processus : une exécution qui meurt sans conclusion reste
     * listée avec les données déjà écrites (consultation honnête d'un
     * incident).
     */
    public suspend fun demarrer(execution: ExecutionSortie)

    /**
     * Ajoute UN LOT de lignes à l'exécution en cours (une écriture par
     * lot, jamais par ligne). Les lignes sont expurgées à l'écriture
     * ([ExpurgateurSorties], racine [ExecutionSortie.projet]).
     */
    public suspend fun ajouterLignes(execution: ExecutionSortie, lignes: List<LigneSortie>)

    /**
     * Conclut l'exécution (verdict, durée, compteurs, troncature
     * appliquée) et rend l'en-tête persisté. Une exécution qui meurt
     * sans conclusion reste relisible avec les données déjà écrites.
     */
    public suspend fun conclure(execution: ExecutionSortie): ExecutionSortie

    /**
     * Efface UNE exécution (`id` non nul) ou TOUT l'historique (`null`) —
     * toujours explicite côté interface (M11/M13), confirmé par la
     * surface qui l'appelle.
     */
    public suspend fun effacer(id: String? = null)

    /**
     * Les lignes du JOURNAL DU SERVEUR (`gradle-server`) de la session de
     * l'exécution — consultables « depuis l'exécution » (spec SORTIES
     * § 4.5, filtre Serveur) sans quitter l'éditeur. Le contenu ne quitte
     * JAMAIS le journal applicatif : c'est une LECTURE ciblée, pas une
     * copie persistée (ADR 0161, alternative rejetée).
     */
    public suspend fun chargerJournauxServeur(execution: ExecutionSortie): List<LigneSortie>
}

/** La nature d'une exécution du registre (ADR 0161 décision 1). */
public enum class TypeExecution {
    /** Un build (`assembleDebug`, tâche sélectionnée, exécution d'app). */
    BUILD,

    /** Une synchronisation de projet (ADR 0079). */
    SYNC,
}

/**
 * La référence des journaux serveur de la session (ADR 0161 décision 1) —
 * la PLAGE horodatage de l'exécution suffit : le journal applicatif est
 * la source de vérité, le registre ne copie jamais son contenu.
 *
 * @property debutMs début de l'exécution (borne inclusive).
 * @property finMs fin de l'exécution (borne inclusive quand connue —
 *           `null` tant que l'exécution est en vol).
 */
public data class ReferenceJournauxServeur(public val debutMs: Long, public val finMs: Long? = null)

/**
 * La troncature ANNONCÉE d'une exécution (ADR 0161 décision 2) — le
 * plafond de lignes a été atteint : la TÊTE et la QUEUE sont conservées,
 * les lignes du milieu partent, et les bornes EXACTES voyagent avec
 * l'en-tête (l'interface affiche « sortie tronquée » avec ces bornes —
 * M12 ; jamais de perte silencieuse, règle 14).
 *
 * @property totalLignes lignes TOTALLES écrites (avant troncature).
 * @property tete lignes conservées EN TÊTE (depuis la première).
 * @property queue lignes conservées EN QUEUE (jusqu'à la dernière).
 */
public data class TroncatureAnnoncee(public val totalLignes: Long, public val tete: Int, public val queue: Int) {
    /** Les lignes perdues au milieu (toujours > 0 quand annoncée). */
    public val perdues: Long
        get() = totalLignes - tete - queue
}

/**
 * L'en-tête d'UNE exécution du registre — les compteurs et le verdict
 * voyagent AVEC la sortie (ADR 0161 « Conséquences ») : une exécution
 * re-ouverte montre le même résumé que le live.
 *
 * @property id identifiant stable (UUID côté collecteur — un build par
 *           `buildId`, une sync par identité générée au départ).
 * @property type nature de l'exécution (build ou sync).
 * @property projet racine du projet de l'exécution (chemin absolu du
 *           câble tooling) — sert à l'expurgation À L'ÉCRITURE et au
 *           saut au source à la relecture ; `null` si inconnue.
 * @property tachesDemandees tâches demandées au build (libellé de
 *           l'en-tête d'affichage) — vide pour une sync.
 * @property debutMs instant de départ (horloge client).
 * @property finMs instant de conclusion, `null` tant que l'exécution
 *           n'est pas conclue (mort du processus comprise).
 * @property etat l'état ÉVÉNEMENTS de l'exécution (verdict, compteurs,
 *           statuts/durées par tâche, phases de sync) — `null` si aucun
 *           événement n'a été observé.
 * @property troncature la troncature annoncée, `null` sous le plafond.
 * @property journauxServeur la référence des journaux `gradle-server`.
 * @property totalLignes lignes écrites pour l'exécution (avant
 *           troncature).
 * @property octetsPersistes taille du stockage des lignes (octets) —
 *           l'unité du plafond global de rétention.
 */
public data class ExecutionSortie(
    public val id: String,
    public val type: TypeExecution,
    public val projet: String? = null,
    public val tachesDemandees: List<String> = emptyList(),
    public val debutMs: Long,
    public val finMs: Long? = null,
    public val etat: EtatExecutionGradle? = null,
    public val troncature: TroncatureAnnoncee? = null,
    public val journauxServeur: ReferenceJournauxServeur? = null,
    public val totalLignes: Long = 0,
    public val octetsPersistes: Long = 0,
)

/**
 * Le calcul PUR de la fenêtre tête+queue (ADR 0161 décision 2) — partagé
 * par l'implémentation du registre et ses tests : sous le plafond, tout
 * est conservé (`null`) ; au-delà, la moitié épargne la tête, l'autre la
 * queue, et la troncature est ANNONCÉE.
 */
public object TroncatureSorties {

    /**
     * La fenêtre à conserver pour [total] lignes sous [plafond], ou
     * `null` si tout tient. Le partage tête/queue est MIROIR (le surplus
     * impair va à la queue : la conclusion d'un build vaut la peine).
     */
    public fun borner(total: Long, plafond: Int): TroncatureAnnoncee? {
        if (total <= plafond) return null
        val tete = plafond / 2
        val queue = plafond - tete
        return TroncatureAnnoncee(totalLignes = total, tete = tete, queue = queue)
    }
}
