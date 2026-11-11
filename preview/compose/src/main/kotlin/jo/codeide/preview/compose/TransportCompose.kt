package jo.codeide.preview.compose

import android.content.Context
import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Transport de rendu Compose (C1 — PREVIEW.md §4) — LE point de
 * branchement du moteur sur le processus `:preview` :
 *  - [TransportCompose] : l'interface (le moteur ne connaît QUE elle) ;
 *  - [TransportComposeProcess] : appel AIDL au [ServicePreviewCompose]
 *    du processus `:preview` (régime isolé) ;
 *  - [IsolationCompose] : la POLITIQUE (liaison paresseuse, UNE reprise,
 *    puis état typé [RegimeCompose.INDISPONIBLE] — « Réessayer »).
 *
 * DIVERGENCE ASSUMÉE avec le frère XML (TransportRendu de :preview:xml) :
 * AUCUN repli local. Composer du code de l'utilisateur dans le processus de l'IDE
 * est EXACTEMENT ce que PC4 interdit (PREVIEW.md §4) : après la reprise
 * unique, les rendus répondent [ResultatTransportCompose.Erreur] avec
 * l'erreur typée [GenreErreurCompose.PROCESSUS_INDISPONIBLE] et le
 * régime passe à [RegimeCompose.INDISPONIBLE] — la panne du processus
 * est un ÉTAT TYPIQUE, jamais un rendu fantôme.
 */
public interface TransportCompose : AutoCloseable {
    /**
     * Rend la [RequeteRenduCompose]. BLOQUANT (composition + mesure +
     * dessin réels) — à appeler hors fil UI (le moteur l'invoque déjà
     * sur son dispatcher dédié). Les échecs voyagent en DONNÉE
     * ([ResultatTransportCompose]) ; les pannes de TRANSPORT (processus
     * mort, Binder muet, délai dépassé) lèvent
     * [ProcessusIndisponibleCompose] — interceptée par la POLITIQUE
     * d'isolation, jamais par le moteur.
     */
    public suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose

    /**
     * C3 — FORWARDING DES ENTRÉES : pousse UNE touche du premier doigt
     * dans la composition vivante de la session [identite]. Les
     * coordonnées voyagent en px bitmap (repère du canevas) ; l'appel
     * est BORNE côté transport (une touche n'attend jamais un rendu
     * en vol). Une touche PERDUE est une donnée : [ProcessusIndisponibleCompose]
     * remonte à la POLITIQUE qui la journalise et répond `false` —
     * JAMAIS de compteur de panne, JAMAIS de reprise pour un doigt
     * (le pompier interactif porte la panne réelle, lui).
     *
     * @return faux = session inconnue ou transport muet (la touche est
     *         perdue, honnêtement).
     */
    public suspend fun transmettre(identite: String, evenement: EvenementTactileCompose): Boolean
}

/**
 * Résultat d'un rendu Compose transporté (donnée, jamais une exception).
 * L'interprétation étant PARTIELLE par construction (C1), les erreurs
 * typées accompagnent TOUJOURS un rendu réussi (elles sont déjà rendues
 * en pastilles par le processus : [ResultatRenduCompose.erreurs] porte
 * les enseignements du PROCESSUS, le convertisseur app-side porte les
 * siens — le moteur fusionne).
 */
public sealed interface ResultatTransportCompose {
    /**
     * Rendu valide : bitmap + métriques + erreurs typées du pipeline +
     * géométries extraites (C2 — [InfoNoeudCompose], bounds bitmap +
     * ligne source par nœud : le sésame surlignage de la carte).
     */
    public class Succes(
        public val bitmap: android.graphics.Bitmap,
        /** Métriques du rendu — PASSERELLE du résultat du processus (bloc opaque). */
        public val metriques: MetriquesRenduCompose,
        /** Erreurs typées rencontrées côté PROCESSUS (vide en nominal). */
        public val erreurs: List<ErreurCompose> = emptyList(),
        /** C2 — géométries extraites de la composition (vide si aucune). */
        public val noeuds: List<InfoNoeudCompose> = emptyList(),
    ) : ResultatTransportCompose

    /** Rendu échoué (composition/measure/draw, délai du processus). */
    public class Erreur(
        public val message: String,
        /** Erreurs typées (au minimum [GenreErreurCompose.ERREUR_RENDU]). */
        public val erreurs: List<ErreurCompose> = emptyList(),
    ) : ResultatTransportCompose
}

/** Panne de TRANSPORT (processus mort, liaison impossible, délai dépassé). */
public class ProcessusIndisponibleCompose(message: String) : IllegalStateException(message)

/**
 * Transport PROCESSUS — liaison Binder paresseuse au
 * [ServicePreviewCompose] du processus `:preview` (le MÊME processus
 * que le service XML : une seule machinerie, deux dialectes), appel
 * AIDL synchrone, bitmap en Parcelable ashmem (miroir
 * TransportRenduProcess du frère XML : la leçon v0.166.4 s'applique —
 * jamais de PNG à la frontière).
 *
 * Robustesse (PC4) :
 *  - la liaison est ATTENDUE (délai borné) à la première demande —
 *    BIND_AUTO_CREATE recrée le processus s'il a été tué (idle ou OS) ;
 *  - le PREMIER rendu d'une liaison vient de NAÎTRE (runtime Compose +
 *    premier attach off-screen) : budget froid élargi
 *    [DELAI_PREMIER_RENDU_MS], les suivants reviennent au budget chaud ;
 *  - un appel qui traîne au-delà du budget est une panne de transport
 *    ([ProcessusIndisponibleCompose]) — jamais un gel de l'éditeur ;
 *  - la MORT du processus (DeathRecipient) invalide la liaison : la
 *    demande SUIVANTE relance (bind + attente), la politique décide des
 *    reprises.
 *
 * @param liaison couture de liaison (bindService + DeathRecipient),
 *        injectable pour les tests (la vraie vit par
 *        [LiaisonServiceContexteCompose]).
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException (la panne de
 * transport EST convertie en [ProcessusIndisponibleCompose] —
 * l'exception d'origine est nommée dans le message) et ReturnCount
 * (liaison, délai, échec = ISSUES distinctes du contrat PC4).
 */
@Suppress("SwallowedException", "ReturnCount")
internal class TransportComposeProcess(private val liaison: LiaisonServiceCompose) : TransportCompose {
    /** Binder vivant (null : à relancer). */
    private var binder: IRenduCompose? = null

    /** Le premier rendu d'une liaison vient de naître (budget froid). */
    private var liaisonFroide = false

    override suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose {
        val service = binder() ?: throw ProcessusIndisponibleCompose("liaison :preview impossible")
        val premierRendu = liaisonFroide
        liaisonFroide = false
        val brut: ResultatRenduCompose =
            try {
                withTimeout(if (premierRendu) DELAI_PREMIER_RENDU_MS else DELAI_RENDU_MS) {
                    runInterruptible(Dispatchers.IO) { service.rendre(requete) }
                }
            } catch (t: Throwable) {
                // RemoteException, délai, interruption : panne TRANSPORT.
                binder = null
                throw ProcessusIndisponibleCompose("rendu :preview échoué : ${t.javaClass.simpleName}")
            }
        if (!brut.ok) {
            return ResultatTransportCompose.Erreur(
                message = brut.message ?: "rendu :preview échoué",
                erreurs = brut.erreurs.toList(),
            )
        }
        val bitmap =
            brut.bitmap
                ?: return ResultatTransportCompose.Erreur(
                    message = "bitmap du :preview absent",
                    erreurs = listOf(ErreurCompose(GenreErreurCompose.ERREUR_RENDU, null, 0)),
                )
        return ResultatTransportCompose.Succes(
            bitmap = bitmap,
            metriques = brut.metriques,
            erreurs = brut.erreurs.toList(),
            noeuds = brut.noeuds.toList(),
        )
    }

    // C3 — une touche n'attend jamais : appel BORNÉ court, la panne remonte
    // à la politique (journal + faux, aucun compteur).
    @Suppress("SwallowedException") // la panne devient une donnée honnête (touche perdue)
    override suspend fun transmettre(
        identite: String,
        evenement: EvenementTactileCompose,
    ): Boolean =
        try {
            val service = binder()
            service != null &&
                withTimeout(DELAI_TRANSMETTRE_MS) {
                    runInterruptible(Dispatchers.IO) { service.transmettre(identite, evenement) }
                }
        } catch (t: Throwable) {
            binder = null
            false
        }

    /**
     * Binder courant, liaison relancée si besoin — l'attente de
     * connexion est bornée ([DELAI_LIAISON_MS]).
     */
    private suspend fun binder(): IRenduCompose? {
        binder?.let { return it }
        val attache = AtomicBoolean(false)
        return try {
            withTimeout(DELAI_LIAISON_MS) {
                suspendCancellableCoroutine { continuation ->
                    val lancee =
                        liaison.lier(
                            onConnecte = { brut ->
                                binder = IRenduCompose.Stub.asInterface(brut)
                                liaisonFroide = true
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
        /** Délai d'un rendu CHAUD (composition vivante : recomposition + dessin). */
        private const val DELAI_RENDU_MS: Long = 10_000L

        /**
         * Délai du PREMIER rendu d'une liaison (processus froid :
         * Application.onCreate du :preview + runtime Compose + premier
         * attach off-screen — miroir PE11l du frère XML).
         */
        private const val DELAI_PREMIER_RENDU_MS: Long = 45_000L

        /** Délai de (re)liaison — naissance du processus `:preview`. */
        private const val DELAI_LIAISON_MS: Long = 15_000L

        /** C3 — délai d'UNE touche : courte, elle n'attend jamais un rendu en vol. */
        private const val DELAI_TRANSMETTRE_MS: Long = 2_000L
    }
}

/**
 * Couture de liaison au [ServicePreviewCompose] — bindService/
 * unbindService + DeathRecipient, injectable pour les tests.
 */
internal interface LiaisonServiceCompose {
    /**
     * Lie le service (BIND_AUTO_CREATE) : [onConnecte] reçoit le binder
     * (UNE fois), [onMort] signale la mort du processus (liaison
     * invalide).
     *
     * @return false si la liaison n'a pas pu être DEMANDÉE.
     */
    public fun lier(onConnecte: (android.os.IBinder) -> Unit, onMort: () -> Unit): Boolean

    /** Délie (idle-shutdown : le processus :preview meurt seul ensuite). */
    public fun delier()
}

/**
 * Liaison réelle par Context.bindService (production) — miroir
 * [LiaisonServiceContexte] du frère XML : le service est BOUND-ONLY,
 * délié il meurt de lui-même ; la MORT est portée par DeathRecipient
 * (plus fiable que onServiceDisconnected).
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — déliaison
 * idempotente (Android).
 */
@Suppress("SwallowedException")
internal class LiaisonServiceContexteCompose(private val contexte: Context) : LiaisonServiceCompose {
    private var connexion: Connexion? = null

    override fun lier(onConnecte: (android.os.IBinder) -> Unit, onMort: () -> Unit): Boolean {
        delier()
        val serviceConnexion =
            Connexion(
                onConnecte = onConnecte,
                onMort = onMort,
            )
        connexion = serviceConnexion
        return contexte.bindService(
            android.content.Intent(contexte, ServicePreviewCompose::class.java),
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
            // Déjà déliée : la liaison suivante repart propre.
        }
    }

    /** ServiceConnection + DeathRecipient réels. */
    private class Connexion(private val onConnecte: (android.os.IBinder) -> Unit, private val onMort: () -> Unit) :
        android.content.ServiceConnection {
        override fun onServiceConnected(nom: android.content.ComponentName, service: android.os.IBinder) {
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
 * POLITIQUE d'isolation Compose (C1 — PC4, miroir assumé SANS repli) :
 * **processus `:preview` d'abord, UNE reprise, puis état typé
 * INDISPONIBLE**.
 *
 * Contrat (manifeste du module + PREVIEW.md §4) :
 *  - 1re panne de transport : la demande est RETENTÉE une fois
 *    (relance du processus) — silencieuse ;
 *  - 2e panne dans la SESSION : le transport processus est désactivé,
 *    [regime] passe à [RegimeCompose.INDISPONIBLE], chaque rendu
 *    répond [ResultatTransportCompose.Erreur] avec l'erreur typée
 *    [GenreErreurCompose.PROCESSUS_INDISPONIBLE] — JAMAIS un rendu
 *    fantôme, JAMAIS de composition dans le processus de l'IDE ;
 *  - [reactiver] (bouton « Réessayer » de la carte) repart à zéro et
 *    le rendu courant est relancé par le moteur.
 *
 * Idle-shutdown : après [DELAI_IDLE_MS_COMPOSE] sans rendu, la liaison est
 * déliée — le processus :preview (bound-only) meurt de lui-même ;
 * [close] (fermeture de l'espace) délie immédiatement.
 *
 * @param fabriqueProcess fabrique du transport processus (injectable —
 *        tests de politique avec un faux qui meurt à la demande).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque ISSUE de la
 * politique (nominal, reprise, désactivation, indisponibilité) est un
 * retour distinct, même patron que la politique du frère XML.
 */
@Suppress("ReturnCount")
public class IsolationCompose(
    contexte: Context,
    private val journal: AppLogger?,
    private val scope: CoroutineScope,
    fabriqueProcess: (Context) -> TransportCompose = { ctx ->
        TransportComposeProcess(LiaisonServiceContexteCompose(ctx))
    },
) : TransportCompose {
    private val process: TransportCompose = fabriqueProcess(contexte)

    /** Régime courant (observabilité — jamais un blocage). */
    private val regimeInterne = MutableStateFlow(RegimeCompose.PROCESSUS_ISOLE)

    /** Pannes de transport enregistrées dans la session. */
    private var pannes: Int = 0

    /** Processus désactivé pour la session (2 pannes). */
    private val desactive = AtomicBoolean(false)

    /** Veille d'idle-shutdown courante. */
    private var veille: kotlinx.coroutines.Job? = null

    private val verrou = Any()

    /** Régime de rendu courant (le moteur le publie à l'UI). */
    public val regime: StateFlow<RegimeCompose> = regimeInterne.asStateFlow()

    /**
     * RÉACTIVE le régime isolé (bouton « Réessayer » de la carte) : le
     * compteur de pannes et la désactivation de la SESSION repartent à
     * zéro, le régime redevient [RegimeCompose.PROCESSUS_ISOLE] — le
     * rendu suivant retente le processus `:preview`.
     */
    public fun reactiver() {
        synchronized(verrou) {
            pannes = 0
            desactive.set(false)
            regimeInterne.value = RegimeCompose.PROCESSUS_ISOLE
        }
    }

    override suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose {
        val resultat = rendreAvecReprise(requete)
        planifierVeille()
        return resultat
    }

    // C3 — une touche perdue est une DONNÉE : ni reprise, ni compteur de
    // panne (le pompier interactif porte la panne réelle — un doigt ne
    // relance JAMAIS un processus seul).
    @Suppress("SwallowedException") // la panne est journalisée et devient un faux honnête
    override suspend fun transmettre(identite: String, evenement: EvenementTactileCompose): Boolean =
        if (desactive.get()) {
            false
        } else {
            try {
                process.transmettre(identite, evenement)
            } catch (indisponible: ProcessusIndisponibleCompose) {
                journal?.d(TAG) { "touche perdue (processus muet : ${indisponible.message})" }
                false
            }
        }

    /** Rendu par le processus (UNE reprise), état typé sinon — PAS de repli. */
    private suspend fun rendreAvecReprise(requete: RequeteRenduCompose): ResultatTransportCompose {
        if (desactive.get()) {
            return indisponible()
        }
        val premier =
            try {
                return process.rendre(requete)
            } catch (indisponible: ProcessusIndisponibleCompose) {
                indisponible
            }
        // UNE reprise silencieuse (relance du processus :preview).
        journal?.d(TAG) { "processus :preview indisponible (${premier.message}) — reprise (compose)" }
        val seconde =
            try {
                return process.rendre(requete)
            } catch (indisponible: ProcessusIndisponibleCompose) {
                indisponible
            }
        // Reprise échouée : désactivation pour la SESSION, état typé
        // honnête — AUCUN repli local (PC4 : composer du code de
        // l'utilisateur dans le processus de l'IDE est interdit).
        synchronized(verrou) {
            pannes += 1
            desactive.set(true)
            regimeInterne.value = RegimeCompose.INDISPONIBLE
            journal?.w(TAG) {
                "processus :preview désactivé pour la session (${seconde.message}) — " +
                    "preview Compose indisponible (aucun repli local, par conception PC4)"
            }
        }
        return indisponible()
    }

    /** L'état typé d'indisponibilité (panne de session — « Réessayer »). */
    private fun indisponible(): ResultatTransportCompose.Erreur = ResultatTransportCompose.Erreur(
        message = "processus :preview indisponible",
        erreurs =
        listOf(
            ErreurCompose(GenreErreurCompose.PROCESSUS_INDISPONIBLE, null, 0),
        ),
    )

    /** (Re)planifie l'idle-shutdown : déliaison après DELAI_IDLE_MS sans rendu. */
    private fun planifierVeille() {
        synchronized(verrou) {
            veille?.cancel()
            veille =
                scope.launch {
                    delay(DELAI_IDLE_MS_COMPOSE)
                    journal?.d(TAG) { "idle :preview — déliaison (le processus meurt seul)" }
                    process.close()
                }
        }
    }

    /** Déliaison immédiate (fermeture de l'espace). */
    override fun close() {
        synchronized(verrou) {
            veille?.cancel()
            veille = null
        }
        process.close()
    }

    private companion object {
        private const val TAG = "IsolationPreviewCompose"
    }
}

/**
 * Idle avant déliaison du processus :preview (il meurt alors seul — le
 * MÊME budget que le frère XML : les deux services partagent le
 * processus, la veille la plus courte gagne).
 */
public const val DELAI_IDLE_MS_COMPOSE: Long = 60_000L

/** Régime de rendu Compose courant (observabilité honnête du moteur). */
public enum class RegimeCompose {
    /** Rendu isolé dans le processus :preview (régime nominal PC4). */
    PROCESSUS_ISOLE,

    /**
     * Processus :preview désactivé pour la session (2 pannes) — les
     * rendus répondent l'erreur typée INDISPONIBLE jusqu'au
     * « Réessayer » (AUCUN repli local en Compose, PC4).
     */
    INDISPONIBLE,
}
