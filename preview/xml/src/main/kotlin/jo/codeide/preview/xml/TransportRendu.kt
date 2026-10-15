package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Transport de rendu (PE2, PREVIEW.md §3 P3) — LE point de branchement
 * du moteur sur les deux mondes :
 *  - [TransportRenduLocal] : pipeline en-process sur fil dédié (le
 *    REPLI — c'est le comportement PE1) ;
 *  - [TransportRenduProcess] : appel AIDL au processus `:preview` (le
 *    régime ISOLÉ par défaut).
 *
 * Le moteur ne connaît QUE cette interface : le rendu est TOUJOURS hors
 * thread UI (le moteur l'invoque déjà sur son dispatcher dédié), les
 * échecs voyagent en DONNÉE ([ResultatTransport.Erreur]), les pannes de
 * TRANSPORT (processus mort, Binder muet) lèvent
 * [ProcessusIndisponible] — interceptée par la POLITIQUE d'isolation
 * ([TransportIsolation]), jamais par le moteur.
 */
public interface TransportRendu : AutoCloseable {
    /**
     * Rend la [RequeteRendu] (texte + res/ + genre + profil + nuit).
     * BLOQUANT (rendu réel) — à appeler hors thread UI.
     */
    public suspend fun rendre(requete: RequeteRendu): ResultatTransport
}

/** Résultat d'un rendu transporté (donnée, jamais une exception). */
public sealed interface ResultatTransport {
    /** Rendu valide : bitmap + métriques + vues (hit-test P6, PE3). */
    @Suppress("LongParameterList")
    public class Succes(
        public val bitmap: Bitmap,
        public val dureeMs: Long,
        public val nombreVues: Int,
        public val largeur: Int,
        public val hauteur: Int,
        public val vues: List<InfoVueRendu> = emptyList(),
    ) : ResultatTransport

    /** Rendu échoué (XML malformé → [m[message] = [XML_MALFORME]). */
    public class Erreur(
        public val message: String,
    ) : ResultatTransport
}

/** Panne de TRANSPORT (processus mort, liaison impossible, délai dépassé). */
public class ProcessusIndisponible(
    message: String,
) : IllegalStateException(message)

/**
 * Transport LOCAL (repli) — le pipeline PE1 tel quel, appelé sur le fil
 * de l'appelant (dispatcher dédié du moteur) : le repli a la MÊME
 * garantie de fond que le régime isolé quant au thread (jamais l'UI).
 *
 * v0.131.0 (G4) — HandlerThread + Looper : les vues Material (ProgressBar
 * indéterminé, SwipeRefreshLayout) démarrent des animators en
 * `onAttachedToWindow` (déclenché par `AttacheurVue`). `Animator.start()`
 * lève `RuntimeException: Animators may only be run on Looper threads`
 * sans Looper. On wrappe donc le rendu sur un `HandlerThread` dédié qui
 * A un Looper — c'est le motif standard pour le rendu de vues Android.
 */
public class TransportRenduLocal(
    contexte: Context,
    journal: AppLogger?,
) : TransportRendu {
    private val pipeline = RenduLayoutLib(contexte, journal)

    /** Thread de rendu dédié avec Looper (G4) — les animators le nécessitent. */
    private val threadRendu: HandlerThread by lazy {
        HandlerThread("preview-render").apply { start() }
    }

    /** Handler sur le thread de rendu. v0.133.5 — null-safe : si le thread
     *  a été fermé (close()), on retourne null et le rendu se fait inline. */
    private val handlerRendu: Handler? by lazy {
        if (threadRendu.isAlive) Handler(threadRendu.looper) else null
    }

    /** v0.133.5 — flag pour savoir si le transport a été fermé. */
    @Volatile
    private var ferme = false

    override suspend fun rendre(requete: RequeteRendu): ResultatTransport {
        // v0.133.5 — si fermé ou HandlerThread mort, rendu inline.
        if (ferme) return ResultatTransport.Erreur("transport fermé")
        return if (Looper.myLooper() != null) {
            val resultat = pipeline.rendre(requete, compresser = false)
            depuisPipeline(resultat)
        } else {
            val handler = handlerRendu
            if (handler != null) {
                rendreSurLooper(handler, requete)
            } else {
                // HandlerThread mort — rendu inline sur le thread courant.
                val resultat = pipeline.rendre(requete, compresser = false)
                depuisPipeline(resultat)
            }
        }
    }

    /** Exécute le rendu sur le HandlerThread dédié (avec Looper). */
    private suspend fun rendreSurLooper(
        handler: Handler,
        requete: RequeteRendu,
    ): ResultatTransport =
        suspendCancellableCoroutine { cont ->
            val runnable =
                Runnable {
                    try {
                        val resultat = pipeline.rendre(requete, compresser = false)
                        cont.resume(dansPipeline(resultat))
                    } catch (t: Throwable) {
                        cont.resume(ResultatTransport.Erreur(t.message ?: "rendu échoué"))
                    }
                }
            handler.post(runnable)
            cont.invokeOnCancellation { handler.removeCallbacks(runnable) }
        }

    /** Adaptateur pour suspendCancellableCoroutine (nom court pour lisibilité). */
    private fun dansPipeline(resultat: RenduLayoutLib.ResultatPipeline): ResultatTransport = depuisPipeline(resultat)

    private fun depuisPipeline(resultat: RenduLayoutLib.ResultatPipeline): ResultatTransport =
        if (resultat.rendu.ok && resultat.bitmap != null) {
            ResultatTransport.Succes(
                bitmap = resultat.bitmap,
                dureeMs = resultat.rendu.dureeMs,
                nombreVues = resultat.rendu.nombreVues,
                largeur = resultat.rendu.largeur,
                hauteur = resultat.rendu.hauteur,
                vues = resultat.rendu.vues.toList(),
            )
        } else {
            ResultatTransport.Erreur(resultat.rendu.message ?: "rendu échoué")
        }

    override fun close() {
        ferme = true
        // Couper le HandlerThread proprement (G4) — il peut ne pas avoir
        // été initialisé (lazy) si aucun rendu n'a eu lieu.
        try {
            threadRendu.quitSafely()
        } catch (_: Throwable) {
            // lazy non-init
        }
    }
}

/**
 * Transport PROCESSUS (régime isolé) — liaison Binder paresseuse au
 * [ServicePreview] du processus `:preview`, appel AIDL synchrone
 * décompressé en bitmap.
 *
 * Robustesse (P3) :
 *  - la liaison est ATTENDUE (délai borné) à la première demande —
 *    BIND_AUTO_CREATE recrée le processus s'il a été tué (idle ou OS) ;
 *  - un appel qui traîne au-delà de [DELAI_RENDU_MS] est une panne de
 *    transport ([ProcessusIndisponible]) — jamais un gel de l'éditeur ;
 *  - la MORT du processus (DeathRecipient) invalide la liaison : la
 *    demande SUIVANTE relance (bind + attente), la politique d'isolation
 *    décide des reprises.
 *
 * @param liaison couture de liaison (bindService + DeathRecipient),
 *        injectable pour les tests (la vraie vit par
 *        [LiaisonServiceContexte]).
 *
 *  * Exemption detekt ciblée (règle 16) : SwallowedException (la panne de
 * transport EST convertie en [ProcessusIndisponible] — l'exception
 * d'origine est nommée dans le message, pas perdue) et ReturnCount
 * (liaison, délai, échec = ISSUES distinctes du contrat §6).
 */
@Suppress("SwallowedException", "ReturnCount")
internal class TransportRenduProcess(
    private val liaison: LiaisonService,
) : TransportRendu {
    /** Binder vivant (null : à relancer). */
    private var binder: IRenduApercu? = null

    override suspend fun rendre(requete: RequeteRendu): ResultatTransport {
        val service = binder() ?: throw ProcessusIndisponible("liaison :preview impossible")
        val brut: ResultatRendu =
            try {
                withTimeout(DELAI_RENDU_MS) {
                    runInterruptible(Dispatchers.IO) { service.rendre(requete) }
                }
            } catch (t: Throwable) {
                // RemoteException, délai, interruption : panne TRANSPORT.
                binder = null
                throw ProcessusIndisponible("rendu :preview échoué : ${t.javaClass.simpleName}")
            }
        if (!brut.ok) {
            return ResultatTransport.Erreur(brut.message ?: "rendu :preview échoué")
        }
        val bitmap =
            BitmapFactory.decodeByteArray(brut.png, 0, brut.png.size)
                ?: return ResultatTransport.Erreur("PNG du :preview illisible")
        return ResultatTransport.Succes(
            bitmap = bitmap,
            dureeMs = brut.dureeMs,
            nombreVues = brut.nombreVues,
            largeur = brut.largeur,
            hauteur = brut.hauteur,
            vues = brut.vues.toList(),
        )
    }

    /**
     * Binder courant, liaison relancée si besoin — l'attente de
     * connexion est bornée ([DELAI_LIAISON_MS]).
     */
    private suspend fun binder(): IRenduApercu? {
        binder?.let { return it }
        val attache = AtomicBoolean(false)
        return try {
            withTimeout(DELAI_LIAISON_MS) {
                suspendCancellableCoroutine { continuation ->
                    val lancee =
                        liaison.lier(
                            onConnecte = { brut ->
                                binder = IRenduApercu.Stub.asInterface(brut)
                                if (attache.compareAndSet(false, true)) {
                                    continuation.resume(binder)
                                }
                            },
                            onMort = {
                                binder = null
                                if (attache.compareAndSet(false, true)) {
                                    continuation.resume(null)
                                }
                            },
                        )
                    if (!lancee && attache.compareAndSet(false, true)) {
                        continuation.resume(null)
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    override fun close() {
        binder = null
        liaison.delier()
    }

    private companion object {
        /** Délai d'un appel de rendu (vue bloquée côté :preview). */
        private const val DELAI_RENDU_MS: Long = 10_000L

        /** Délai de (re)liaison (process killé → relance ~2 s visée §6). */
        private const val DELAI_LIAISON_MS: Long = 8_000L
    }
}

/**
 * Couture de liaison au service `:preview` — bindService/unbindService
 * + DeathRecipient, injectable pour les tests.
 */
internal interface LiaisonService {
    /**
     * Lie le service (BIND_AUTO_CREATE) : [onConnecte] reçoit le binder
     * (UNE fois), [onMort] signale la mort du processus (liaison
     * invalide).
     *
     * @return false si la liaison n'a pas pu être DEMANDÉE.
     */
    public fun lier(
        onConnecte: (android.os.IBinder) -> Unit,
        onMort: () -> Unit,
    ): Boolean

    /** Délie (idle-shutdown : le processus :preview meurt seul ensuite). */
    public fun delier()
}

/**
 * Liaison réelle par Context.bindService (production).
 *
 * Idle-shutdown (PREVIEW.md §3.2) : le service est BOUND-ONLY — délié,
 * il meurt de lui-même (aucun startService ne le maintient) ; la
 * politique d'isolation délie après [TransportIsolation.DELAI_IDLE_MS]
 * sans rendu ET à la fermeture de l'espace (I7).
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — déliaison idempotente (Android). */
@Suppress("SwallowedException")
internal class LiaisonServiceContexte(
    private val contexte: Context,
) : LiaisonService {
    private var connexion: Connexion? = null

    override fun lier(
        onConnecte: (android.os.IBinder) -> Unit,
        onMort: () -> Unit,
    ): Boolean {
        delier()
        val serviceConnexion =
            Connexion(
                onConnecte = onConnecte,
                onMort = onMort,
            )
        connexion = serviceConnexion
        return contexte.bindService(
            android.content.Intent(contexte, ServicePreview::class.java),
            serviceConnexion,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun delier() {
        val courante = connexion ?: return
        connexion = null
        try {
            contexte.unbindService(courante)
        } catch (t: Throwable) {
            // Déjà déliée (Android le tolère mal mais ne le garantit pas
            // idempotent) : la liaison suivante repart propre.
        }
    }

    /** ServiceConnection + DeathRecipient réels. */
    private class Connexion(
        private val onConnecte: (android.os.IBinder) -> Unit,
        private val onMort: () -> Unit,
    ) : android.content.ServiceConnection {
        override fun onServiceConnected(
            nom: android.content.ComponentName,
            service: android.os.IBinder,
        ) {
            service.linkToDeath(
                object : android.os.IBinder.DeathRecipient {
                    override fun binderDied() {
                        onMort()
                    }
                },
                0,
            )
            onConnecte(service)
        }

        override fun onServiceDisconnected(nom: android.content.ComponentName) {
            // Mort/redémarrage : le binder sera relivré — rien à faire
            // (la MORT est portée par DeathRecipient, plus fiable).
        }
    }
}

/**
 * POLITIQUE d'isolation (PE2, PREVIEW.md §3 P3 + §6) — transport par
 * défaut du moteur : **processus `:preview` d'abord, repli local
 * automatique**.
 *
 * Contrat §6 (« mort détectée < 1 s [DeathRecipient], redémarrage < 2 s
 * [BIND_AUTO_CREATE], repli in-process automatique silencieux — UNE
 * reprise, puis désactivation pour la session avec message honnête ») :
 *  - 1re panne : la demande est RETENTÉE une fois (relance du
 *    processus) — silencieuse ;
 *  - 2e panne dans la SESSION : le transport processus est désactivé,
 *    chaque rendu passe en LOCAL, journal honnête + [regime] passe à
 *    [RegimeRendu.LOCAL_DEGRADE] (observabilité de l'UI, jamais un
 *    blocage) ;
 *
 * Idle-shutdown : après [DELAI_IDLE_MS] sans rendu, la liaison est
 * déliée — le processus :preview (bound-only) meurt de lui-même ;
 * [close] (I7 — fermeture de l'espace/projet) délie immédiatement.
 *
 * @param fabriqueProcess fabrique du transport processus (injectable —
 *        tests de politique avec un faux qui meurt à la demande).
 *
 *  * Exemption detekt ciblée (règle 16) : ReturnCount — chaque ISSUE de la
 * politique §6 (nominal, reprise, désactivation, repli) est un retour
 * distinct, même patron que les moteurs du dépôt.
 */
@Suppress("ReturnCount")
public class TransportIsolation(
    contexte: Context,
    private val journal: AppLogger?,
    private val scope: kotlinx.coroutines.CoroutineScope,
    fabriqueProcess: (Context) -> TransportRendu = { ctx ->
        TransportRenduProcess(LiaisonServiceContexte(ctx))
    },
) : TransportRendu {
    private val process: TransportRendu = fabriqueProcess(contexte)
    private val local: TransportRendu = TransportRenduLocal(contexte, journal)

    /** Régime courant (observabilité — jamais un blocage). */
    private val regimeInterne = kotlinx.coroutines.flow.MutableStateFlow(RegimeRendu.PROCESSUS_ISOLE)

    /** Pannes de transport enregistrées dans la session. */
    private var pannes: Int = 0

    /** Processus désactivé pour la session (2 pannes). */
    private val desactive = AtomicBoolean(false)

    /** Veille d'idle-shutdown courante. */
    private var veille: kotlinx.coroutines.Job? = null

    private val verrou = Any()

    /** Régime de rendu courant (le moteur le publie à l'UI). */
    public val regime: kotlinx.coroutines.flow.StateFlow<RegimeRendu> = regimeInterne.asStateFlow()

    override suspend fun rendre(requete: RequeteRendu): ResultatTransport {
        val resultat = rendreAvecReprise(requete)
        planifierVeille()
        return resultat
    }

    /** Rendu par le processus (UNE reprise), repli local sinon. */
    private suspend fun rendreAvecReprise(requete: RequeteRendu): ResultatTransport {
        if (desactive.get()) {
            return local.rendre(requete)
        }
        val premier =
            try {
                return process.rendre(requete)
            } catch (indisponible: ProcessusIndisponible) {
                indisponible
            }
        // UNE reprise silencieuse (relance du processus :preview).
        journal?.d(TAG) { "processus :preview indisponible (${premier.message}) — reprise" }
        val seconde =
            try {
                return process.rendre(requete)
            } catch (indisponible: ProcessusIndisponible) {
                indisponible
            }
        // Reprise échouée : désactivation pour la SESSION, message honnête
        // (contrat §6 : UNE reprise, PUIS désactivation — le rendu
        // courant est livré par le repli local, jamais perdu).
        synchronized(verrou) {
            pannes += 1
            desactive.set(true)
            regimeInterne.value = RegimeRendu.LOCAL_DEGRADE
            journal?.w(TAG) {
                "processus :preview désactivé pour la session (${seconde.message}) — " +
                    "repli local in-process (rendu inchangé, isolation perdue)"
            }
        }
        return local.rendre(requete)
    }

    /** (Re)planifie l'idle-shutdown : déliaison après DELAI_IDLE_MS sans rendu. */
    private fun planifierVeille() {
        synchronized(verrou) {
            veille?.cancel()
            veille =
                scope.launch {
                    kotlinx.coroutines.delay(DELAI_IDLE_MS)
                    journal?.d(TAG) { "idle :preview — déliaison (le processus meurt seul)" }
                    process.close()
                }
        }
    }

    /** Déliaison immédiate (fermeture de l'espace — I7). */
    override fun close() {
        synchronized(verrou) {
            veille?.cancel()
            veille = null
        }
        process.close()
        local.close()
    }

    private companion object {
        private const val TAG = "IsolationPreview"
    }
}

/** Idle avant déliaison du processus :preview (il meurt alors seul — PREVIEW.md §3.2). */
public const val DELAI_IDLE_MS: Long = 60_000L

/** Régime de rendu courant (observabilité honnête du moteur). */
public enum class RegimeRendu {
    /** Rendu isolé dans le processus :preview (régime nominal P3). */
    PROCESSUS_ISOLE,

    /** Repli local in-process (processus :preview désactivé — honnête). */
    LOCAL_DEGRADE,
}
