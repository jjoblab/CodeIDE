package jo.codeide.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Langage servi par un moteur LSP du frère `codeide-lsp` (ADR 0128) —
 * miroir DOMAINE de `LangageLsp` de l'hôte : `:core:domain` ne dépend
 * jamais de `:lsp:host` (seuls `app` et `:feature:editor` le voient),
 * la parité des deux énumérations est verrouillée par un test côté
 * consommateur (`PortLspEspaceTest`, `:feature:editor`).
 */
public enum class LangageLsp {
    /** Compilateur ecj adapté à ART (renommage, formatage, indices en ligne). */
    JAVA,

    /** Parseur sur la grammaire vendorée (pack langue, diagnostics syntaxiques). */
    KOTLIN,

    /** Parseur Android tolérant, zéro dépendance (renommage + formatage v1.1.0). */
    XML,
}

/**
 * Fonction LSP **annoncée par un serveur** et coupable côté client —
 * miroir DOMAINE de `CapaciteLsp` de l'hôte (parité verrouillée par test).
 * Les capacités non coupables (symboles de document, repli, jetons
 * sémantiques) vivent dans les `ServerCapabilities` brutes et n'ont pas
 * d'interrupteur : jamais un contrôle sans effet (ADR 0059).
 */
public enum class CapaciteLsp {
    /** textDocument/completion — suggestions pendant la frappe. */
    COMPLETION,

    /** textDocument/hover — documentation au toucher long. */
    SURVOL,

    /** publishDiagnostics — erreurs et avertissements. */
    DIAGNOSTICS,

    /** textDocument/signatureHelp — paramètres de l'appel en cours. */
    SIGNATURES,

    /** textDocument/definition — saut à la définition. */
    DEFINITION,

    /** textDocument/references — toutes les occurrences. */
    REFERENCES,

    /** textDocument/rename — renommage sûr du symbole. */
    RENOMMAGE,

    /** textDocument/codeAction — corrections rapides. */
    ACTIONS_RAPIDES,

    /** textDocument/formatting — mise en forme du code. */
    FORMATAGE,

    /** textDocument/inlayHint — types et noms de paramètres affichés. */
    INDICES_EN_LIGNE,
}

/**
 * Phase d'un moteur LSP — miroir DOMAINE de `PhaseIndexation` de l'hôte
 * (parité verrouillée par test côté consommateur).
 */
public enum class PhaseMoteurLsp {
    /** Session vivante, aucun sync encore : état inconnu. */
    INITIAL,

    /** Indexation en cours (avancement observable). */
    CONSTRUCTION,

    /** Index à jour. */
    PRET,

    /** Index partiel (avertissements, entrées écartées). */
    PARTIEL,

    /** Index en erreur. */
    ERREUR,
}

/**
 * État du moteur JAVA, projeté pour les écrans de réglages (lot LSP-3,
 * ADR 0128 §4) : le modèle riche de l'hôte (`EtatIndexation` — liste de
 * travail, ventilation par indexeur, phases détaillées) reste confiné
 * côté éditeur ; les écrans ne voient que ce QU'ILS AFFICHENT. Kotlin et
 * XML n'exposent AUCUN état d'indexation (facades du frère) : seules
 * leurs sessions sont observées (présence/absence, MoteursLsp).
 *
 * @property avancementPourCent progression 0-100 pendant CONSTRUCTION ;
 *  `null` sinon (indéterminé — jamais un faux chiffre).
 * @property fichiersIndexes nombre d'entrées indexées au dernier état
 *  connu (affiché à l'état PRET).
 * @property messagesErreur messages d'erreur bruts du dernier état (≤ 20,
 *  affichés tels quels après ré-expurgation côté écran — l'hôte d'indexation
 *  n'est pas journalisé par LogRedactor).
 */
public data class EtatMoteurLsp(
    public val phase: PhaseMoteurLsp,
    public val avancementPourCent: Int? = null,
    public val fichiersIndexes: Int = 0,
    public val avertissements: Int = 0,
    public val erreurs: Int = 0,
    public val messagesErreur: List<String> = emptyList(),
    public val dureeDernierPassageMs: Long? = null,
    public val horodatageDernierPassageMs: Long? = null,
) {
    /** `true` si une indexation OCCUPE le moteur (Vider le cache refusé). */
    public val constructionActive: Boolean
        get() = phase == PhaseMoteurLsp.CONSTRUCTION
}

/**
 * Vue d'ensemble des trois moteurs du projet ouvert (lot LSP-3) : Java
 * porte l'état complet, Kotlin et XML n'ont PAS de surface d'état côté
 * frère — leur présence de session est le seul état honnête affichable.
 *
 * @property java `null` : session Java absente (moteur inactif).
 * @property kotlin `true` : session Kotlin vivante.
 * @property xml `true` : session XML vivante.
 */
public data class MoteursLsp(
    public val java: EtatMoteurLsp? = null,
    public val kotlin: Boolean = false,
    public val xml: Boolean = false,
) {
    public companion object {
        /** Aucun moteur vivant : projet absent ou éditeur fermé. */
        public val INACTIFS: MoteursLsp = MoteursLsp()
    }
}

/**
 * Motif du refus de vidage — miroir DOMAINE de `RaisonRefusVidage` de
 * l'hôte : le refus voyage en DONNÉE, jamais en exception.
 */
public enum class RaisonRefusVidageLsp {
    /** Une indexation occupe le cache : réessayer à la fin du passage. */
    INDEXATION_EN_COURS,
}

/**
 * Résultat d'un vidage du cache d'index — miroir DOMAINE de
 * `ResultatVidageCache` de l'hôte.
 */
public sealed interface ResultatVidageCacheLsp {
    /** Cache vidé : segments supprimés, octets libérés du disque. */
    public data class Videe(
        public val segmentsSupprimes: Int,
        public val octetsLiberes: Long,
    ) : ResultatVidageCacheLsp

    /** Refusé : la raison voyage en donnée (jamais d'exception). */
    public data class Refusee(
        public val raison: RaisonRefusVidageLsp,
    ) : ResultatVidageCacheLsp
}

/**
 * Table des capacités ANNONCÉES par langage (lot LSP-3, ADR 0128 §4) :
 * connaissance STATIQUE du frère — indépendante de toute session, lue
 * par l'écran L2 même éditeur fermé. Implémentation liée par Hilt dans
 * :feature:editor (le seul module qui voit `:lsp:host`, toujours sur le
 * classpath de l'app monolithique) — la table n'est JAMAIS recopiée
 * côté écrans : le test de parité `PortLspEspaceTest` la verrouille.
 */
public interface TableCapacitesLsp {
    /**
     * Capacités ANNONCÉES pour un langage (parité verrouillée côté frère)
     * — la liste des interrupteurs de l'écran L2 en sera dérivée
     * (annoncées ∩ consommables, ADR 0128 §3).
     */
    public fun capacitesAnnoncees(langage: LangageLsp): Set<CapaciteLsp>
}

/**
 * Port des moteurs LSP pour les écrans de réglages « Intelligence du
 * code » (lot LSP-3, ADR 0128 §4) : état OBSERVÉ (StateFlow, jamais lu
 * une fois) et actions ancrées sur la session VIVANTE (taille,
 * réindexation, vidage — refus en DONNÉE). Les capacités statiques
 * vivent dans [TableCapacitesLsp] : ce port naît et meurt avec
 * l'espace qui possède les sessions.
 *
 * Implémenté par `:feature:editor` (auprès des sessions qu'il possède),
 * publié dans [RegistrePortLsp] — patron [PortEditeurActif] /
 * [RegistrePortEditeurActif] (mission IA, ADR 0122) : aucune dépendance
 * feature ↔ feature, le domaine ne voit JAMAIS `:lsp:host`.
 *
 * Les actions sur port ABSENT retournent l'absence (`null`/`false`),
 * jamais un blocage : sans projet ouvert les réglages restent éditables
 * (spec `LSP_SPEC_ECRANS.md` § 2).
 *
 * Thread : observation et actions appelées depuis le fil UI ou les
 * dispatchers injectés ; les implémentations ne bloquent jamais.
 */
public interface PortLsp {
    /** État observable des moteurs du projet ouvert (INACTIFS sans port). */
    public val moteurs: StateFlow<MoteursLsp>

    /**
     * Taille du cache d'index sur DISQUE (octets, temporaires exclus) ;
     * `null` sans session vivante (rien à mesurer d'honnête).
     */
    public fun tailleCacheOctets(): Long?

    /**
     * Relance l'indexation du projet ouvert (segments revalidés,
     * corrupts auto-réparés) ; `false` sans session vivante.
     */
    public fun reindexer(): Boolean

    /**
     * Vide le cache d'index (segments + temporaires + mémoire) ; le refus
     * voyage en DONNÉE ([ResultatVidageCacheLsp.Refusee]) ; `null` sans
     * session vivante. L'index se reconstruit à la prochaine ouverture
     * ou par [reindexer].
     */
    public fun viderCache(): ResultatVidageCacheLsp?
}
