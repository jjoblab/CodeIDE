package jo.codeide.feature.editor

import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.EtatMoteurLsp
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.PhaseMoteurLsp
import jo.codeide.core.domain.PortLsp
import jo.codeide.core.domain.RaisonRefusVidageLsp
import jo.codeide.core.domain.ResultatVidageCacheLsp
import jo.codeide.core.domain.TableCapacitesLsp
import jo.codeide.lsp.host.CapacitesAnnoncees
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation
import jo.codeide.lsp.host.RaisonRefusVidage
import jo.codeide.lsp.host.ResultatVidageCache
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.lsp.host.SessionLspXml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import jo.codeide.lsp.host.CapaciteLsp as CapaciteLspHote
import jo.codeide.lsp.host.LangageLsp as LangageLspHote

/**
 * Nombre d'erreurs transportées vers les écrans (spec LSP § 3 L5 : « ≤ 20 »).
 */
internal const val ERREURS_LSP_MAX: Int = 20

/**
 * Port des moteurs LSP pour les écrans de réglages « Intelligence du code »
 * (lot LSP-3, ADR 0128 §4) : `:feature:settings` ne voit JAMAIS `:lsp:host`
 * — il observe [jo.codeide.core.domain.RegistrePortLsp], ce port est
 * l'ADAPTATEUR auprès des sessions que l'espace possède.
 *
 * Java porte l'état d'indexation complet (projeté par [versEtatMoteurLsp]) ;
 * Kotlin et XML n'exposent AUCUN état côté frère — leur PRÉSENCE de
 * session est le seul état honnête (jamais un faux « Prêt »).
 *
 * Le port vit dans le `viewModelScope` de l'espace : il naît et meurt
 * avec lui (enregistré dans `init`, retiré dans `onCleared`) — une
 * rotation ne referme rien, la sortie de l'éditeur retire TOUT.
 *
 * Thread : [tailleCacheOctets] et [viderCache] touchent le DISQUE —
 * l'appelant les pose hors fil principal (dispatchers injectés) ; le
 * refus voyage en DONNÉE, jamais en exception (course avec la fermeture
 * comprise : l'absence de session retourne `null`).
 */
internal class PortLspEspace(
    private val sessionJava: StateFlow<SessionLsp?>,
    private val sessionKotlin: StateFlow<SessionLspKotlin?>,
    private val sessionXml: StateFlow<SessionLspXml?>,
    portee: CoroutineScope,
) : PortLsp {
    /** État projeté des moteurs : la session Java est suivie au fil de l'eau (opt-in ciblé,
     * même patron qu'EditorActivity — la collecte MEURT avec sa session), Kotlin/XML par présence. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val moteurs: StateFlow<MoteursLsp> =
        combine(
            sessionJava.flatMapLatest { session ->
                session?.etatIndexation ?: flowOf(null)
            },
            sessionKotlin,
            sessionXml,
        ) { etatJava, kotlin, xml ->
            MoteursLsp(
                java = etatJava?.versEtatMoteurLsp(),
                kotlin = kotlin != null,
                xml = xml != null,
            )
        }.stateIn(portee, SharingStarted.Eagerly, MoteursLsp.INACTIFS)

    /** Taille du cache d'index SUR DISQUE (temporaires exclus) ; `null` sans session. */
    override fun tailleCacheOctets(): Long? = sessionJava.value?.tailleCacheOctets()

    /** Relance l'indexation du projet ouvert ; `false` sans session vivante. */
    override fun reindexer(): Boolean =
        sessionJava.value
            ?.let { session ->
                session.reindexer()
                true
            } ?: false

    /**
     * Vide le cache d'index ; refus en DONNÉE, `null` sans session —
     * la course avec la fermeture (IllegalStateException côté hôte)
     * se traduit en absence, jamais en plantage de l'écran.
     */
    override fun viderCache(): ResultatVidageCacheLsp? =
        try {
            sessionJava.value?.viderCache()?.versResultatDomaine()
        } catch (_: IllegalStateException) {
            // Session refermée entre la lecture de valeur et l'appel :
            // rien à vider — l'écran ré-observera l'absence du port.
            null
        }
}

/**
 * Table des capacités annoncées LIÉE par Hilt (singleton process-wide,
 * lot LSP-3) : délègue à `CapacitesAnnoncees` du frère — la source
 * unique, jamais recopiée. Vit dans :feature:editor (seul module qui
 * voit `:lsp:host`, toujours sur le classpath de l'app) : l'écran L2 la
 * lit même éditeur fermé.
 */
internal class TableCapacitesLspHote : TableCapacitesLsp {
    override fun capacitesAnnoncees(langage: LangageLsp): Set<CapaciteLsp> =
        CapacitesAnnoncees
            .capacites(langage.versLangageHote())
            .map { it.versCapaciteDomaine() }
            .toSet()
}

/** Miroir langage : domaine → hôte (parité verrouillée par `PortLspEspaceTest`). */
internal fun LangageLsp.versLangageHote(): LangageLspHote =
    when (this) {
        LangageLsp.JAVA -> LangageLspHote.JAVA
        LangageLsp.KOTLIN -> LangageLspHote.KOTLIN
        LangageLsp.XML -> LangageLspHote.XML
    }

/** Miroir capacité : hôte → domaine (parité verrouillée par `PortLspEspaceTest`). */
internal fun CapaciteLspHote.versCapaciteDomaine(): CapaciteLsp =
    when (this) {
        CapaciteLspHote.COMPLETION -> CapaciteLsp.COMPLETION
        CapaciteLspHote.SURVOL -> CapaciteLsp.SURVOL
        CapaciteLspHote.DIAGNOSTICS -> CapaciteLsp.DIAGNOSTICS
        CapaciteLspHote.SIGNATURES -> CapaciteLsp.SIGNATURES
        CapaciteLspHote.DEFINITION -> CapaciteLsp.DEFINITION
        CapaciteLspHote.REFERENCES -> CapaciteLsp.REFERENCES
        CapaciteLspHote.RENOMMAGE -> CapaciteLsp.RENOMMAGE
        CapaciteLspHote.ACTIONS_RAPIDES -> CapaciteLsp.ACTIONS_RAPIDES
        CapaciteLspHote.FORMATAGE -> CapaciteLsp.FORMATAGE
        CapaciteLspHote.INDICES_EN_LIGNE -> CapaciteLsp.INDICES_EN_LIGNE
    }

/** Miroir phase : hôte → domaine (parité verrouillée par `PortLspEspaceTest`). */
internal fun PhaseIndexation.versPhaseDomaine(): PhaseMoteurLsp =
    when (this) {
        PhaseIndexation.INITIAL -> PhaseMoteurLsp.INITIAL
        PhaseIndexation.CONSTRUCTION -> PhaseMoteurLsp.CONSTRUCTION
        PhaseIndexation.PRET -> PhaseMoteurLsp.PRET
        PhaseIndexation.PARTIEL -> PhaseMoteurLsp.PARTIEL
        PhaseIndexation.ERREUR -> PhaseMoteurLsp.ERREUR
    }

/**
 * Projection de l'état JAVA pour les écrans (PURE, testée en JVM) : le
 * modèle riche de l'hôte reste ici — les écrans ne voient que ce qu'ils
 * affichent : phase, avancement, comptes, messages tronqués, bilan.
 */
internal fun EtatIndexation.versEtatMoteurLsp(): EtatMoteurLsp =
    EtatMoteurLsp(
        phase = phase.versPhaseDomaine(),
        avancementPourCent =
            if (phase == PhaseIndexation.CONSTRUCTION && total > 0) {
                faits * POURCENT / total
            } else {
                null
            },
        fichiersIndexes = faits,
        avertissements = avertissements.size,
        erreurs = erreurs.size,
        messagesErreur = erreurs.take(ERREURS_LSP_MAX),
        dureeDernierPassageMs = bilanDernierPassage?.dureeMs,
        horodatageDernierPassageMs = bilanDernierPassage?.horodatageMs,
    )

/** Miroir résultat de vidage : hôte → domaine (refus en DONNÉE). */
internal fun ResultatVidageCache.versResultatDomaine(): ResultatVidageCacheLsp =
    when (this) {
        is ResultatVidageCache.Videe -> {
            ResultatVidageCacheLsp.Videe(
                segmentsSupprimes = segmentsSupprimes,
                octetsLiberes = octetsLiberes,
            )
        }

        is ResultatVidageCache.Refusee -> {
            ResultatVidageCacheLsp.Refusee(
                raison =
                    when (raison) {
                        RaisonRefusVidage.INDEXATION_EN_COURS -> RaisonRefusVidageLsp.INDEXATION_EN_COURS
                    },
            )
        }
    }

private const val POURCENT = 100
