package jo.codeide.feature.sorties

import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.SourceSortie
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.Random

/**
 * Le port de SOURCE du lecteur (ADR 0159 « branché sur une source factice
 * uniquement », lot L2) — le seul câblage que le composant connaît. Le
 * raccord Gradle (L3), Logcat et Journal (L4) IMPLÉMENTERONT ce port sans
 * toucher au lecteur.
 *
 * @property capaciteFenetre la capacité de la fenêtre du tampon — la
 *           source connaît son budget (console : `NB_LIGNES_MAX` actuel ;
 *           Logcat : `CAPACITE_TAMPON` ; banc : fenêtre de la séquence).
 * @property lots les lots de lignes émis au rythme du câble (append par
 *           lot, ADR 0074 — jamais une ligne à la fois sur le chemin
 *           chaud).
 */
public interface FluxSorties {
    /** Capacité de la fenêtre du tampon (lignes). */
    public fun capaciteFenetre(): Int

    /** Les lots de lignes — flux froid, un collecteur. */
    public fun lots(): Flow<List<LigneSortie>>

    /** La séquence banc armée, si le lecteur tourne en banc de mesure. */
    public val sequenceArmee: StateFlow<SequenceBanc?>

    /**
     * L'état de l'exécution EN VOL porté par les ÉVÉNEMENTS (lot L3 —
     * verdict, compteurs, statuts/durées par tâche, phases de sync) : le
     * résumé en tête et la projection regroupée le lisent, la
     * consultation d'une exécution persistée le reçoit du registre.
     * `null` hors exécution (attente, banc, consultation).
     */
    public val etatExecution: StateFlow<EtatExecutionGradle?>

    /**
     * Le numéro de GÉNÉRATION du flux (lot L4) — incrémenté à CHAQUE
     * changement de source derrière le même port (banc armé/désarmé,
     * session Logcat sélectionnée, changement de session consultée).
     * Le lecteur y voit le signal de RESET honnête : au changement, il
     * vide son état vivant — jamais un mélange de deux sources dans un
     * même tampon (deux familles, deux flux : le changement n'arrive
     * qu'au sein d'UNE famille).
     */
    public val generation: StateFlow<Int>
}

/** Fenêtre commune des séquences banc (lignes) — S2 exige le tampon
 *  plein de 100 000 lignes ; S1/S6 partagent la même fenêtre pour des PV
 *  comparables. */
public const val FENETRE_BANC: Int = 120_000

/** Fenêtre hors banc (parité console actuelle, `NB_LIGNES_MAX`). */
public const val FENETRE_DEFAUT: Int = 2_000

/** Volume total d'une séquence banc — le banc § 3 (100 000 lignes). */
public const val TOTAL_BANC = 100_000

/**
 * Les séquences du banc de mesure (docs/SORTIES_BANC_MESURE.md § 3, lot
 * L2) — les volumes et cadences du protocole sont encodés ICI, « par un
 * paramètre unique » : la carte de diagnostic de feature:editor arme
 * l'une d'elles, le propriétaire mesure S1–S6 sur appareil (les PV sont
 * des livrables de L2 — ADR 0159). S5 (regroupement) et S8 (transport
 * réel) attendent le lot L3.
 *
 * Le contenu émis est DÉTERMINISTE (graine fixe) : deux PV se rejouent à
 * l'identique — le banc compare des volumes et des séquences égales.
 *
 * @property fenetre capacité de tampon de la séquence (120 000 : S2
 *           exige le tampon PLEIN 100 000 lignes — S1/S6 partagent la
 *           même fenêtre pour des PV comparables).
 * @property totalLignes volume total émis.
 */
public enum class SequenceBanc(public val fenetre: Int, public val totalLignes: Int) {
    /**
     * S1 — débit d'ingestion : 20 lots de 500 lignes à 500 lignes/s, puis
     * 10 000 lignes/s jusqu'au total de 100 000 (lecture cohérente du
     * banc § 3 : « 100 000 lignes au total »). Mesure : T6 images
     * perdues, vidage T5, latence émission→pixel.
     */
    S1_DEBIT_INGESTION(FENETRE_BANC, TOTAL_BANC),

    /**
     * S2 — tampon plein : 100 000 lignes émises d'un bloc (dont 12 480
     * lignes « chaudes » — erreurs/avertissements pour les repères de
     * défilement), puis le flux reste ouvert sans rien émettre.
     * Mesure : S2 ouverture (< 300 ms), S3 recherche (< 2 s), S4
     * défilement.
     */
    S2_TAMPON_PLEIN(FENETRE_BANC, TOTAL_BANC),

    /**
     * S6 — pause/reprise : 100 000 lignes à 10 000 lignes/s (10 s), le
     * propriétaire met en pause pendant l'ingestion puis reprend — le
     * delta arrive EN UNE fois (spec SORTIES § 3.3), cohérence vérifiée
     * au compteur du lecteur.
     */
    S6_PAUSE_REPRISE(FENETRE_BANC, TOTAL_BANC),

    /**
     * S5 — regroupement (lot L3) : 100 000 lignes d'un bloc (500 tâches,
     * statuts fins cyclés), tampon PLEIN — le propriétaire bascule brut →
     * regroupé, replie/déplie 50 tâches : la mesure « une seule liste
     * re-projetée » (ADR 0078), la latence de bascule, la rétention
     * mémoire. Même cadence que S2 (tout d'un bloc, puis tampon stable).
     */
    S5_REGROUPEMENT(FENETRE_BANC, TOTAL_BANC),
}

/**
 * La source factice du lecteur (banc SORTIES_BANC_MESURE § 2 : « le banc
 * de mesure est un mode du composant lui-même ») — rejoue des séquences
 * calibrées sans serveur de build ; coût nul par défaut (aucune séquence
 * armée : [lots] attend l'armement, jamais une ligne d'avance silencieuse).
 *
 * Contenu déterministe (graine fixe) : en-têtes de tâches Gradle avec
 * statuts, verdicts avec durées, positions cliquables `e:`/`w:`,
 * segments ANSI SGR, lignes de téléchargement avec octets (Q12), UTF-8
 * multi-octets, une ligne longue de 4 000 caractères — le banc mesure le
 * lecteur sur un contenu RÉALISTE.
 *
 * Injectée par Hilt comme [FluxSorties] du lot L2 (le banc arme la
 * séquence) ; L3 rebondira vers le vrai flux tooling sans changer le
 * lecteur.
 */
@javax.inject.Singleton
public class SourceSortiesFactice
@javax.inject.Inject
constructor() : FluxSorties {
    private val sequenceInterne = MutableStateFlow<SequenceBanc?>(null)

    /** Le numéro de génération (le banc arme/désarme — chaque bascule
     *  est un changement de source derrière le port). */
    private val generationInterne = MutableStateFlow(0)

    override val generation: StateFlow<Int> = generationInterne.asStateFlow()

    override val sequenceArmee: StateFlow<SequenceBanc?> = sequenceInterne.asStateFlow()

    /** Le banc ne porte AUCUN état d'exécution (contenu déterministe —
     *  le résumé regroupé du banc dérive du texte seul). */
    override val etatExecution: StateFlow<EtatExecutionGradle?> = MutableStateFlow(null)

    override fun capaciteFenetre(): Int = sequenceInterne.value?.fenetre ?: FENETRE_DEFAUT

    override fun lots(): Flow<List<LigneSortie>> = flow {
        while (currentCoroutineContext().isActive) {
            // Attente honnête : rien d'armé → rien d'émis (coût nul).
            val sequence = sequenceInterne.filterNotNull().first()
            for (lot in lotsDe(sequence)) {
                if (sequenceInterne.value != sequence) break
                emit(lot.lignes)
                if (lot.periodeMs > 0) delay(lot.periodeMs)
            }
            // Séquence achevée : désarmement explicite (jamais un flux qui
            // promet plus que la réalité — règle 14).
            if (sequenceInterne.value == sequence) sequenceInterne.value = null
        }
    }

    /**
     * Arme une séquence banc — appelé par l'écran de diagnostic banc AVANT
     * la création du lecteur (la capacité de fenêtre se lit au
     * constructeur du tampon).
     */
    public fun armer(sequence: SequenceBanc) {
        if (sequenceInterne.value == sequence) return
        sequenceInterne.value = sequence
        generationInterne.value += 1
    }

    /** Désarme la séquence (le flux retourne en attente, coût nul). */
    public fun desarmer() {
        if (sequenceInterne.value == null) return
        sequenceInterne.value = null
        generationInterne.value += 1
    }

    /** Les lots d'une séquence : rythme et volumes du banc § 3. */
    private fun lotsDe(sequence: SequenceBanc): Sequence<LotFactice> = when (sequence) {
        SequenceBanc.S1_DEBIT_INGESTION -> lotsS1()

        SequenceBanc.S2_TAMPON_PLEIN -> lotsS2()

        SequenceBanc.S6_PAUSE_REPRISE -> lotsCadence(
            totalLignes = sequence.totalLignes,
            parLot = 500,
            periodeMs = 50L,
        )

        SequenceBanc.S5_REGROUPEMENT -> lotsS5()
    }

    /** S1 : 20 lots de 500 lignes à 1 lot/s (500 l/s), puis 10 000 l/s. */
    private fun lotsS1(): Sequence<LotFactice> = sequence {
        var emises = 0
        repeat(LOTS_PHASE_LENTE) {
            val lot = ligneFactice(PAR_LOT, emises)
            emises += PAR_LOT
            yield(LotFactice(lot, periodeMs = PERIODE_LENTE_MS))
        }
        while (emises < SequenceBanc.S1_DEBIT_INGESTION.totalLignes) {
            val lot = ligneFactice(PAR_LOT, emises)
            emises += PAR_LOT
            yield(LotFactice(lot, periodeMs = PERIODE_RAPIDE_MS))
        }
    }

    /** S2 : tout d'un bloc (aucune pause — le tampon se remplit plein). */
    private fun lotsS2(): Sequence<LotFactice> = sequence {
        var emises = 0
        while (emises < SequenceBanc.S2_TAMPON_PLEIN.totalLignes) {
            val taille = minOf(LOT_S2, SequenceBanc.S2_TAMPON_PLEIN.totalLignes - emises)
            val lot = ligneFactice(taille, emises)
            emises += taille
            yield(LotFactice(lot, periodeMs = 0L))
        }
        // Tampon plein, flux ouvert : la mesure S2/S3/S4 tourne sur un
        // tampon STABLE — plus rien n'est émis (délai long, pas de fin de
        // séquence prématurée).
        yield(LotFactice(emptyList(), periodeMs = PAUSE_S2_MS))
    }

    /** S5 (lot L3) : tout d'un bloc comme S2 — la bascule brut →
     *  regroupé se mesure sur un tampon PLEIN et STABLE. */
    private fun lotsS5(): Sequence<LotFactice> = sequence {
        var emises = 0
        while (emises < SequenceBanc.S5_REGROUPEMENT.totalLignes) {
            val taille = minOf(LOT_S2, SequenceBanc.S5_REGROUPEMENT.totalLignes - emises)
            val lot = ligneFactice(taille, emises)
            emises += taille
            yield(LotFactice(lot, periodeMs = 0L))
        }
        yield(LotFactice(emptyList(), periodeMs = PAUSE_S2_MS))
    }

    /** Cadence fixe (S6 — 10 000 lignes/s en lots de 500 à 50 ms). */
    private fun lotsCadence(totalLignes: Int, parLot: Int, periodeMs: Long): Sequence<LotFactice> = sequence {
        var emises = 0
        while (emises < totalLignes) {
            val taille = minOf(parLot, totalLignes - emises)
            val lot = ligneFactice(taille, emises)
            emises += taille
            yield(LotFactice(lot, periodeMs = periodeMs))
        }
    }

    /**
     * Un lot de lignes réalistes et DÉTERMINISTES (graine fixe : deux PV
     * se rejouent à l'identique).
     */
    private fun ligneFactice(taille: Int, depart: Int): List<LigneSortie> {
        val generateur = Random(GRAINE + depart)
        val lignes = ArrayList<LigneSortie>(taille)
        repeat(taille) { indice ->
            val position = depart + indice
            lignes += ContenuFactice.ligneDePosition(position, generateur)
        }
        return lignes
    }

    /** Un lot émis par la source : lignes + cadence du banc. */
    internal data class LotFactice(val lignes: List<LigneSortie>, val periodeMs: Long)

    private companion object {
        /** Graine déterministe : deux PV se rejouent à l'identique. */
        private const val GRAINE: Long = 20261110L

        /** Phase lente de S1 : vingt lots (500 lignes/s). */
        private const val LOTS_PHASE_LENTE = 20

        /** Taille d'un lot (lignes) des phases lente et rapide. */
        private const val PAR_LOT = 500

        /** Cadence de la phase lente : 500 lignes/s. */
        private const val PERIODE_LENTE_MS = 1_000L

        /** Cadence de la phase rapide : 10 000 lignes/s. */
        private const val PERIODE_RAPIDE_MS = 50L

        /** Taille d'un lot S2 (lignes). */
        private const val LOT_S2 = 2_000

        /** La pause post-remplissage de S2 (ms) — tampon stable. */
        private const val PAUSE_S2_MS = 60_000L
    }
}
