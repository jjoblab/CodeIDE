package jo.codeide.core.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port du pont de journaux des applications exécutées (mission « Exécuter »
 * R2, ADR 0103) — ce que voit l'interface : les SESSIONS vivantes ou
 * terminées, leurs lignes, leurs pertes.
 *
 * L'implémentation de transport (service Binder du module `app`) pousse
 * dans [RegistrePontJournaux] ; l'interface et les tests n'en dépendent
 * jamais (ADR 0103 : « transport isolé derrière un port »).
 *
 * Toutes les méthodes sont sûres depuis N'IMPORTE QUEL fil (les callbacks
 * Binder arrivent sur les fils du pool) — verrou interne, jamais
 * d'attente.
 */
public interface PontJournauxApplications {
    /**
     * Les sessions connues — une par (paquet, pid, nom de processus).
     * La liste est réémise à chaque changement : ouverture, fin, lot de
     * lignes reçu, pertes signalées.
     */
    public val sessions: StateFlow<List<SessionJournal>>

    /**
     * Flux des LOTS de lignes d'une session — chaque émission est un lot
     * dans l'ordre d'arrivée, PORTANT sa position (R3 : [LotJournal.apres])
     * Le tampon est large ; si un abonné trop lent en fait déborder, les
     * lignes manquantes sont COMPTÉES dans [SessionJournal.lignesPerdues] —
     * jamais perdues en silence.
     *
     * R3 : l'afficheur de l'onglet Logcat préfère [lignesDepuis] (chemin
     * incrémental SANS fenêtre de course — l'état des sessions, réémis à
     * chaque lot, sert de signal) ; ce flux reste l'API de poussée pour
     * un consommateur qui vit AVEC les lots (instrumentation, export).
     */
    public fun lignes(idSession: String): Flow<LotJournal>

    /**
     * Delta incrémental des lignes de la session (R3) — les lignes reçues
     * STRICTEMENT APRÈS [position], dans la limite du tampon conservé
     * (les plus anciennes au-delà de la borne sont parties : le delta
     * rend alors tout ce qui est retenu). [LotJournal.apres] donne la
     * nouvelle position à mémoriser pour l'appel suivant.
     *
     * Pur et SYNCHRONE : la lecture est cohérente avec l'état des
     * sessions émis au même instant — un afficheur qui rattrape à chaque
     * réémission de [sessions] ne perd JAMAIS une ligne (contrairement à
     * la souscription du flux [lignes], dont l'enregistrement et la
     * lecture d'un instantané ne sont pas atomiques).
     */
    public fun lignesDepuis(
        idSession: String,
        position: Int,
    ): LotJournal

    /**
     * Instantané des lignes tamponnées de la session (borné) — remplissage
     * initial d'un affichage, reconnexion d'onglet.
     */
    public fun instantane(idSession: String): List<LigneJournal>
}

/**
 * Un lot de lignes reçues d'une session, AVEC sa position (R3).
 *
 * [apres] = le nombre TOTAL de lignes reçues par la session APRÈS ce
 * lot (compteur du registre, jamais remis à zéro). Un afficheur qui
 * souscrit sait exactement où recouper avec l'instantané : les lignes
 * du lot sont les `[apres] - [lignes].size + 1`…`[apres]`-ièmes de la
 * session — aucune perte, aucun doublon, même si des lots arrivent
 * entre la souscription et la lecture de l'instantané.
 *
 * @property apres nombre de lignes reçues par la session après ce lot.
 * @property lignes les lignes du lot, dans l'ordre d'arrivée.
 */
public data class LotJournal(
    public val apres: Int,
    public val lignes: List<LigneJournal>,
)

/**
 * Une session de journaux d'une application exécutée.
 *
 * @property id identifiant stable (« paquet/pid »)
 * @property identite paquet, pid, nom de processus
 * @property etat vivante ou terminée
 * @property fin raison et horodatage de la fin (`null` si vivante)
 * @property nombreLignes lignes reçues depuis l'ouverture de la session
 * @property lignesPerdues lignes perdues (débordement côté pont OU abonné
 *           trop lent) — affichées, jamais silencieuses
 * @property sortiePrecedente description de la fin du processus
 *           PRÉCÉDENT (ApplicationExitInfo) si le pont l'a annoncée
 */
public data class SessionJournal(
    public val id: String,
    public val identite: IdentiteSessionJournal,
    public val etat: EtatSessionJournal,
    public val fin: FinSessionJournal?,
    public val nombreLignes: Int,
    public val lignesPerdues: Long,
    public val sortiePrecedente: String?,
)

/** Cycle de vie d'une session de journaux. */
public enum class EtatSessionJournal {
    /** Le pont est connecté, les lots arrivent. */
    VIVANTE,

    /** Le processus est mort (linkToDeath) ou le pont s'est fermé. */
    TERMINEE,
}

/**
 * Registre des sessions du pont de journaux — implémentation PURE de
 * [PontJournauxApplications] (JVM testable sans Android) : le service
 * Binder du module `app` y pousse les événements, l'interface y lit.
 *
 * Le tampon de lignes par session est borné ([CAPACITE_LIGNES]) : au-delà,
 * les plus anciennes lignes d'affichage disparaissent et les pertes sont
 * comptées — cohérent avec le pont côté application (ADR 0103 §5).
 */
public class RegistrePontJournaux : PontJournauxApplications {
    /** Capacité du tampon de lignes PAR SESSION (miroir du pont : 5 000). */
    private val capacite: Int

    /** Émissions de lots par session (identifiant → flux). */
    private val flux = HashMap<String, MutableSharedFlow<LotJournal>>()

    /** Tampon de lignes par session (identifiant → lignes). */
    private val tampons = HashMap<String, ArrayDeque<LigneJournal>>()

    /** État courant des sessions. */
    private val etatSessions = MutableStateFlow<List<SessionJournal>>(emptyList())

    private val verrou = Any()

    override val sessions: StateFlow<List<SessionJournal>>
        get() = etatSessions.asStateFlow()

    public constructor() : this(CAPACITE_LIGNES)

    /** Constructeur de test : capacité du tampon ajustable. */
    public constructor(capacite: Int) {
        this.capacite = capacite
    }

    override fun lignes(idSession: String): Flow<LotJournal> =
        synchronized(verrou) {
            fluxDe(idSession).asSharedFlow()
        }

    override fun instantane(idSession: String): List<LigneJournal> =
        synchronized(verrou) {
            tampons[idSession]?.toList() ?: emptyList()
        }

    override fun lignesDepuis(
        idSession: String,
        position: Int,
    ): LotJournal =
        synchronized(verrou) {
            val tampon = tampons[idSession] ?: return LotJournal(0, emptyList())
            val apres = etatSessions.value.firstOrNull { it.id == idSession }?.nombreLignes ?: tampon.size
            // Première ligne du tampon = numéro (apres - taille + 1) :
            // le delta commence au premier numéro utile — position+1 ou
            // le début du tampon si la position demandée est trop vieille
            // (les lignes plus anciennes sont parties avec la borne).
            val debut = apres - tampon.size + 1
            val depuis = maxOf(position + 1, debut)
            if (depuis > apres) {
                return LotJournal(apres, emptyList())
            }
            LotJournal(
                apres = apres,
                lignes = tampon.subList(depuis - debut, tampon.size).toList(),
            )
        }

    // ------------------------------------------------------------------
    // API du transport (appelée par le service Binder du module app).
    // ------------------------------------------------------------------

    /**
     * Ouvre (ou rouvre) la session du pont [identite] — appelée sur
     * `connecter`. Une réouverture du même identifiant marque la session
     * VIVANTE sans rien jeter (le pont vide alors son anneau).
     *
     * @return l'identifiant de session ouvert
     */
    public fun ouvrirSession(identite: IdentiteSessionJournal): String {
        synchronized(verrou) {
            val id = idSession(identite)
            val courantes = etatSessions.value.associateBy { it.id }
            val session =
                courantes[id]?.copy(etat = EtatSessionJournal.VIVANTE, fin = null)
                    ?: SessionJournal(
                        id = id,
                        identite = identite,
                        etat = EtatSessionJournal.VIVANTE,
                        fin = null,
                        nombreLignes = 0,
                        lignesPerdues = 0,
                        sortiePrecedente = null,
                    )
            // La nouvelle session s'ajoute À LA FIN : l'ordre de la
            // liste est l'ordre d'arrivée, l'interface trie comme elle
            // veut (vivantes d'abord, par exemple).
            etatSessions.value = (courantes - id).values + session
            fluxDe(id)
            tampons.getOrPut(id) { ArrayDeque() }
            return id
        }
    }

    /**
     * Termine la session [idSession] avec une [raison] lisible — appelée
     * sur `deconnecter` ou `linkToDeath` du binder de contrôle.
     */
    public fun terminerSession(
        idSession: String,
        raison: String,
    ) {
        synchronized(verrou) {
            majSession(idSession) { session ->
                session.copy(
                    etat = EtatSessionJournal.TERMINEE,
                    fin = FinSessionJournal(raison, System.currentTimeMillis()),
                )
            }
        }
    }

    /**
     * Consomme un lot de trames brutes de la session [idSession] :
     * chaque trame est analysée par [AnalyseurTramesJournal] (défensif),
     * les lignes valides tamponnent et partent aux abonnés, les pertes
     * annoncées par le pont s'ajoutent au compteur, une trame « X »
     * documente la sortie précédente.
     *
     * @return le nombre de lignes valides produites
     */
    public fun ajouterTrames(
        idSession: String,
        trames: List<String>,
        lignesPerduesAnnoncees: Long,
    ): Int {
        val lignes = ArrayList<LigneJournal>(trames.size)
        var sortiePrecedente: String? = null
        for (trame in trames) {
            when (val analysee = AnalyseurTramesJournal.analyser(trame)) {
                is AnalyseurTramesJournal.TrameAnalysee.Ligne -> {
                    lignes.add(analysee.ligne)
                }

                is AnalyseurTramesJournal.TrameAnalysee.SortiePrecedente -> {
                    if (sortiePrecedente == null) {
                        sortiePrecedente = analysee.description
                    }
                }

                null -> {
                    Unit
                } // trame non conforme : ignorée, sans bruit
            }
        }
        var emises = 0
        synchronized(verrou) {
            val tampon = tampons[idSession] ?: return 0
            for (ligne in lignes) {
                tampon.addLast(ligne)
                if (tampon.size > capacite) {
                    tampon.removeFirst()
                }
            }
            val pertes = lignesPerduesAnnoncees
            var apres = 0
            // Compteurs TOUJOURS mis à jour (pertes ANNONCÉES même sans
            // ligne : le pont annonce des pertes venant de son propre
            // anneau, sans trame associée).
            majSession(idSession) { session ->
                apres = session.nombreLignes + lignes.size
                session.copy(
                    nombreLignes = apres,
                    lignesPerdues = session.lignesPerdues + pertes,
                    sortiePrecedente = session.sortiePrecedente ?: sortiePrecedente,
                )
            }
            if (lignes.isNotEmpty()) {
                // SUSPEND + tryEmit : un tampon plein échoue proprement —
                // le lot est ALORS compté en pertes (jamais jeté en
                // silence, ADR 0103 §5). R3 : le lot PART avec sa
                // position (recoupage instantané/souscription exact).
                val lot = LotJournal(apres, lignes)
                val parti = flux.getValue(idSession).tryEmit(lot)
                if (!parti) {
                    // Échec de livraison : les lignes reçues mais non
                    // livrées s'ajoutent aux pertes (compteurs cohérents,
                    // réémission de l'état des sessions).
                    majSession(idSession) { session ->
                        session.copy(lignesPerdues = session.lignesPerdues + lignes.size)
                    }
                } else {
                    emises = lignes.size
                }
            }
        }
        return emises
    }

    // ------------------------------------------------------------------
    // Internes.
    // ------------------------------------------------------------------

    /** Identifiant stable d'une session : « paquet/pid ». */
    private fun idSession(identite: IdentiteSessionJournal): String = "${identite.paquet}/${identite.pid}"

    /** Flux (créé au besoin) de la session — buffer large, pertes comptées. */
    private fun fluxDe(id: String): MutableSharedFlow<LotJournal> =
        flux.getOrPut(id) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = CAPACITE_LOTS,
                onBufferOverflow = BufferOverflow.SUSPEND,
            )
        }

    /** Réécrit l'état d'une session (verrou tenu par l'appelant). */
    private inline fun majSession(
        idSession: String,
        transformation: (SessionJournal) -> SessionJournal,
    ) {
        val courantes = etatSessions.value
        val sessions =
            courantes.map { session ->
                if (session.id == idSession) transformation(session) else session
            }
        etatSessions.value = sessions
    }

    private companion object {
        /** Lignes gardées par session (miroir de l'anneau du pont). */
        const val CAPACITE_LIGNES = 5000

        /** Lots gardés pour les abonnés lents. */
        const val CAPACITE_LOTS = 512
    }
}
