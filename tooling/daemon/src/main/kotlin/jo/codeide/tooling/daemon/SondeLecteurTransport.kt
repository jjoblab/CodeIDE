package jo.codeide.tooling.daemon

import jo.codeide.tooling.client.SondeLectureSocket
import java.util.concurrent.atomic.AtomicLong

/**
 * Sonde de production du fil lecteur — **T3 détaillé** de la Phase 1
 * (ADR 0152) : agrège sans verrou les durées des trois phases du fil
 * (`FrameCodec.readFrame`, décodage JSON, routage) frame par frame, et
 * rend un résumé lisible à [prendreResume] — consommé par la pompe
 * cliente au terme de chaque build (ligne `ConsoleLatence`).
 *
 * **Désactivable** (règle 10 du prompt) : [activer] est appelé par le
 * daemon à CHAQUE lancement du process orchestrateur, depuis le réglage
 * utilisateur (`instrumentationTransport`) — coupée, [frame] rend la
 * main immédiatement : aucun compteur, aucune allocation, le fil lecteur
 * ne paie rien (le coût armé est mesuré : deux `nanoTime` et quelques
 * atomiques par frame — cf. `DIAGNOSTIC-LOGS.md` §5 pour la mesure serveur
 * du même patron, ≈ +1 µs/ligne JVM).
 *
 * Les durées voyagent en MICROsecondes dans le résumé : les trois phases
 * sont attendues sous la milliseconde sur appareil sain — la latence
 * d'ARRIVÉE (ms) reste portée par la ligne `ConsoleLatence` existante,
 * ce résumé la DÉCOMPOSE.
 *
 * Thread-safe sans verrou : [frame] est appelée par le fil lecteur,
 * [prendreResume] par le consommateur de la pompe — compteurs atomiques,
 * pas d'instantané intermédiaire (même discipline qu'`AccumulateurLatence`,
 * que le résumé complète sans la remplacer).
 */
internal class SondeLecteurTransport : SondeLectureSocket {
    /** Drapeau d'activation — posé par le daemon au lancement du process. */
    @Volatile
    private var active = false

    /** Frames traitées depuis le dernier résumé pris. */
    private val frames = AtomicLong(0L)

    /** Durées de la phase de lecture (en-tête + payload), par frame. */
    private val lecture = Phase()

    /** Durées de la phase de décodage JSON, par frame. */
    private val decodage = Phase()

    /** Durées de la phase de routage (chemin rapide), par frame. */
    private val routage = Phase()

    /** Arme ou coupe la sonde (réglage utilisateur, au lancement du process). */
    fun activer(active: Boolean) {
        this.active = active
    }

    override fun frame(dureeLectureNs: Long, dureeDecodageNs: Long, dureeRoutageNs: Long) {
        if (!active) return
        frames.incrementAndGet()
        lecture.enregistrer(dureeLectureNs)
        decodage.enregistrer(dureeDecodageNs)
        routage.enregistrer(dureeRoutageNs)
    }

    override fun prendreResume(): String? {
        val n = frames.getAndSet(0L)
        if (n == 0L) return null
        return "instru: lecteur — $n frame(s) : " +
            "lecture ${lecture.prendre(n)} ; décodage ${decodage.prendre(n)} ; routage ${routage.prendre(n)}"
    }

    /**
     * Une phase du fil : total / minimum / maximum en nanosecondes, rendus
     * en microsecondes lisibles — atomiques, remis à zéro par [prendre].
     */
    private class Phase {
        private val total = AtomicLong(0L)
        private val minimum = AtomicLong(Long.MAX_VALUE)
        private val maximum = AtomicLong(Long.MIN_VALUE)

        fun enregistrer(dureeNs: Long) {
            total.addAndGet(dureeNs)
            minimum.accumulateAndGet(dureeNs) { courant, nouveau -> minOf(courant, nouveau) }
            maximum.accumulateAndGet(dureeNs) { courant, nouveau -> maxOf(courant, nouveau) }
        }

        fun prendre(nombreFrames: Long): String {
            val min = minimum.getAndSet(Long.MAX_VALUE)
            val max = maximum.getAndSet(Long.MIN_VALUE)
            val totalNs = total.getAndSet(0L)
            // Défensif : une phase sans observation (résumé pris entre deux
            // frames dont UNE seule a alimenté les autres) ne dit pas 0.
            if (min == Long.MAX_VALUE || max == Long.MIN_VALUE) return "n/a"
            val micros = { ns: Long -> ns / NANOS_PAR_MICRO }
            val moyen = if (nombreFrames > 0) micros(totalNs / nombreFrames) else 0L
            return "min ${micros(min)} / moy $moyen / max ${micros(max)} µs"
        }

        private companion object {
            /** Nanosecondes par microseconde (durées des phases du fil, lisibles en µs). */
            const val NANOS_PAR_MICRO = 1_000L
        }
    }
}
