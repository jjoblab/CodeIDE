package jo.codeide.core.domain

/**
 * Le verdict TERMINAL d'une exécution Gradle (ADR 0161, lot L3) — porté
 * par l'ÉVÉNEMENT `EtatBuild` (source de vérité du cycle de vie) et
 * complété par le texte (`AncresFluxGradle.verdictDe`) quand l'événement
 * n'a pas pu être observé (relecture d'un flux persisté). Le texte ne
 * fait que rattacher le verdict au flux, jamais le remplacer.
 */
public enum class VerdictExecution {
    /** Le build (ou la sync) s'est terminé avec succès. */
    REUSSI,

    /** Le build (ou la sync) s'est terminé en échec. */
    ECHOUE,

    /** Le build a été arrêté à la demande de l'utilisateur. */
    ANNULE,
}

/**
 * Les compteurs du résumé (spec SORTIES § 4.1) — TOUS issus des
 * ÉVÉNEMENTS (`EtatBuild` : synthèse serveur « N actionable tasks » ;
 * événements de tâches `EtatTacheBuild` pour le détail fin), JAMAIS
 * comptés sur le texte seul. Chaque compteur est `null` quand la source
 * ne l'a pas fourni (build échoué avant la synthèse) — `null` signifie
 * « inconnu », jamais « zéro » : l'affichage ne compte pas ce qu'il ne
 * sait pas (règle 14).
 *
 * @property actionnables total des tâches actionnables (« N actionable
 *           tasks », synthèse de fin de Gradle).
 * @property executees tâches réellement exécutées (« M executed »).
 * @property aJour tâches à jour (« K up-to-date »).
 * @property depuisCache tâches reprises du cache de build (événement
 *           D1 en attente — comptées du texte seulement quand le dépôt
 *           frère n'a pas livré la granularité).
 * @property sansSource tâches sans source (« NO-SOURCE »).
 * @property sautees tâches sautées (« SKIPPED »).
 */
public data class CompteursTaches(
    public val actionnables: Int? = null,
    public val executees: Int? = null,
    public val aJour: Int? = null,
    public val depuisCache: Int? = null,
    public val sansSource: Int? = null,
    public val sautees: Int? = null,
)

/**
 * Une phase de synchronisation conclue ou en vol (spec SORTIES § 4.4) —
 * miroir de [EtapeSyncTooling] allégé pour la persistance du registre
 * (l'énumération porte la structure, les libellés restent à l'UI,
 * discipline `PresentationTooling`).
 *
 * @property etape phase énumérée réelle (l'ordre du déroulé).
 * @property dureeMs durée mesurée côté orchestrateur (0 si en vol).
 * @property terminee `true` quand la phase est conclue.
 */
public data class PhaseExecution(
    public val etape: EtapeSync,
    public val dureeMs: Long = 0,
    public val terminee: Boolean = false,
)

/**
 * L'état d'une exécution Gradle vu des ÉVÉNEMENTS (lot L3) — tout ce que
 * le résumé (spec SORTIES § 4.1), la projection regroupée et le registre
 * des exécutions (ADR 0161 : « les compteurs et le verdict voyagent avec
 * la sortie ») ont besoin de savoir, porté par les événements du câble
 * (`EtatBuild`, `EtatTacheBuild`, `EtapeSyncTooling`), jamais compté sur
 * le texte des lignes.
 *
 * L'état est IMMUTABLE : chaque événement produit une copie mise à jour
 * (même discipline que `EtatGradle`) — il se PERSISTE tel quel dans le
 * registre (JSON) et se RELIT tel quel à la consultation : une exécution
 * re-ouverte montre le même résumé que le live (ADR 0161 « Conséquences »).
 *
 * @property verdict verdict terminal de l'exécution, `null` en vol.
 * @property dureeMs durée terminale, `null` en vol ou inconnue.
 * @property compteurs compteurs de tâches de la synthèse (`null` quand
 *           aucune synthèse n'a été observée).
 * @property erreurs nombre de lignes graduées ERREUR vues du flux
 *           (positions cliquables de gravité ERREUR, diagnostics
 *           `DiagnosticBuild` inclus) — compté À L'INGESTION par le
 *           collecteur, jamais ré-estimé à la consultation.
 * @property avertissements nombre de lignes graduées AVERTISSEMENT vues
 *           du flux (même discipline).
 * @property statutsParTache dernier statut ÉVÉNEMENT par tâche
 *           (`EtatTacheBuild` — fait foi sur le texte, arbitrage Q6/D1).
 * @property dureesParTache durée MESURÉE par l'orchestrateur par tâche
 *           (`EtatTacheBuild.dureeMs` à la conclusion) — jamais mesurée
 *           côté client.
 * @property phases phases de sync annoncées/conclues (vide pour un
 *           build).
 */
public data class EtatExecutionGradle(
    public val verdict: VerdictExecution? = null,
    public val dureeMs: Long? = null,
    public val compteurs: CompteursTaches? = null,
    public val erreurs: Int = 0,
    public val avertissements: Int = 0,
    public val statutsParTache: Map<String, StatutTache> = emptyMap(),
    public val dureesParTache: Map<String, Long> = emptyMap(),
    public val phases: List<PhaseExecution> = emptyList(),
) {
    /**
     * Applique l'état d'un build (`EtatBuild`) : verdict terminal, durée,
     * synthèse des compteurs. Un état EN COURS ne change pas le verdict —
     * seule la transition vers un statut terminal le pose (INC-01).
     */
    public fun surEtatBuild(etat: EtatBuild): EtatExecutionGradle {
        if (etat.statut == StatutBuild.EN_COURS) {
            return copy(compteurs = synthese(etat))
        }
        val verdict = when (etat.statut) {
            StatutBuild.REUSSI -> VerdictExecution.REUSSI
            StatutBuild.ECHOUE -> VerdictExecution.ECHOUE
            else -> VerdictExecution.ANNULE
        }
        return copy(
            verdict = verdict,
            dureeMs = etat.dureeMs ?: dureeMs,
            compteurs = synthese(etat) ?: compteurs,
        )
    }

    /** La synthèse « N actionable tasks » si l'événement en porte une. */
    private fun synthese(etat: EtatBuild): CompteursTaches? {
        if (etat.tachesActionnables == null && etat.tachesExecutees == null && etat.tachesAJour == null) {
            return compteurs
        }
        return CompteursTaches(
            actionnables = etat.tachesActionnables,
            executees = etat.tachesExecutees,
            aJour = etat.tachesAJour,
            depuisCache = compteurs?.depuisCache,
            sansSource = compteurs?.sansSource,
            sautees = compteurs?.sautees,
        )
    }

    /**
     * Applique l'événement d'une tâche : le statut ÉVÉNEMENT fait foi sur
     * le texte (Q6) ; la durée MESURÉE se pose à la conclusion seulement.
     */
    public fun surTache(evenement: EtatTacheBuild): EtatExecutionGradle {
        val statuts = statutsParTache + (evenement.chemin to evenement.statut)
        val durees =
            if (evenement.dureeMs != null) {
                dureesParTache + (evenement.chemin to evenement.dureeMs)
            } else {
                dureesParTache
            }
        return copy(statutsParTache = statuts, dureesParTache = durees)
    }

    /**
     * Compte UNE ligne graduée vue du flux (erreurs et avertissements du
     * résumé) — appelée par le collecteur à l'ingestion : une ligne qui
     * porte une position de gravité donnée compte pour cette gravité
     * (une erreur de compilation = une erreur, pas une ligne de plus).
     */
    public fun compterGravite(erreur: Boolean): EtatExecutionGradle =
        if (erreur) copy(erreurs = erreurs + 1) else copy(avertissements = avertissements + 1)

    /**
     * Applique une étape de sync (annonce ou conclusion) — la phase
     * remplace son entrée (même discipline que `EtatGradle.etapesSync`).
     */
    public fun surEtapeSync(etape: EtapeSyncTooling): EtatExecutionGradle {
        val phase = PhaseExecution(etape = etape.etape, dureeMs = etape.dureeMs, terminee = etape.terminee)
        return copy(phases = phases.filterNot { it.etape == etape.etape } + phase)
    }
}
