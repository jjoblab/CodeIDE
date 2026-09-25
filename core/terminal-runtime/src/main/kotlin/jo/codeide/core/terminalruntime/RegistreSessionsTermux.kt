package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.ToolchainLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API de rendu réservée à `feature:terminal` (prompt compagnon
 * Terminal-1, section 4.4) : expose le **vrai** objet Termux
 * `TerminalSession` d'un identifiant — nécessaire au branchement du
 * `TerminalView`.
 *
 * Vit ici et **pas dans `core:domain`** : le type exposé est une
 * bibliothèque Termux, seul `feature:terminal` (qui dépend déjà de
 * `terminal-view`) peut le consommer — la carte d'aperçu de l'éditeur,
 * elle, n'a besoin que des métadonnées du domaine.
 */
public interface TerminalRuntime {
    /**
     * La session réelle à rendre.
     *
     * @param sessionId identifiant de la session.
     * @return la session Termux, ou `null` si inconnue ou déjà fermée.
     */
    public fun sessionFor(sessionId: String): com.termux.terminal.TerminalSession?

    /**
     * Signal de repeint immédiat (v0.31.5, retour d'appareil réel).
     *
     * Émis à CHAQUE changement de texte ou de fin de session, sans le
     * délai de 250 ms du throttle (qui ne sert que l'aperçu des
     * métadonnées). Dans l'architecture Termux, c'est le client de
     * session de l'ACTIVITÉ qui redessine la vue — ici, l'écran du
     * terminal collecte ce signal et appelle `TerminalView.onScreenUpdated()`.
     *
     * Sémantique « dernier gagnant » : sans abonné (écran fermé), les
     * signaux tombent — rien à repeindre ; un signal perdu pendant une
     * collecte occupée est indolore, le suivant repeindra l'état courant
     * (le transcript est l'état complet, pas un delta).
     */
    public fun observeSorties(): Flow<Unit>
}

/**
 * Démarrage du service foreground du terminal (indirection interne :
 * le registre sollicite l'Android sans en dépendre dans les tests).
 */
internal interface DemarreurService {
    /** Garantit que le service foreground tourne (idempotent). */
    fun demarrer()
}

/**
 * Registre global des sessions de terminal (prompt Terminal-1,
 * section 4.3) — implémentation de [TerminalSessionRepository] et de
 * [TerminalRuntime].
 *
 * Singleton Hilt lié à l'application : la même liste est visible depuis
 * l'accueil et le tiroir de l'espace de travail (section 1.5), les
 * objets réels survivent aux changements d'écran — c'est le service
 * foreground ([TerminalService]) qui les garde en vie hors écran.
 *
 * Traduction vers [TerminalSessionSummary] : les changements de sortie
 * sont **throttlés** (fenêtre de [FENETRE_THROTTLE_MS] — la carte
 * d'aperçu ne doit pas être réveillée à chaque caractère, section 4.3) ;
 * terminaisons et renommages sont publiés immédiatement. Le signal de
 * repeint [observeSorties][TerminalRuntime.observeSorties], lui, part
 * sans throttle — l'écran du terminal se redessine au fil de l'eau
 * (v0.31.5 : le texte tapé n'apparaissait qu'au prochain layout).
 *
 * Activation : une session créée devient **toujours** la session active
 * (v0.31.5, retour d'appareil réel « je ne peux pas naviguer entre les
 * sessions ») — l'onglet « + » et le bouton de création partent du
 * principe que la nouvelle session s'affiche, comme Termux ; l'ancien
 * comportement ne l'activait que si aucune n'était active, et l'écran
 * retombait visuellement sur l'ancienne.
 *
 * Sémantique de liste : un shell terminé **naturellement** reste
 * visible (`isAlive = false`) jusqu'à sa fermeture explicite ;
 * [closeSession] termine le shell et retire l'entrée.
 */
@Singleton
// Exemptions detekt ciblées (règle 16 du prompt maître) :
// - TooManyFunctions : le registre implémente deux ports + l'écoute des
//   coquilles — 10 méthodes imposées par les contrats (précédent
//   ToolchainLocator/AppNavigator), plus 4 aides privées ;
// - LongParameterList : 7 dépendances injectées, toutes distinctes
//   (localisateur, environnement, fabrique, service, horloge,
//   dispatchers, journal).
@Suppress("TooManyFunctions", "LongParameterList")
internal class RegistreSessionsTermux
    @Inject
    constructor(
        private val localisateur: ToolchainLocator,
        private val environnement: ProcessEnvironmentProvider,
        private val fabrique: FabriqueCoquilles,
        private val demarreurService: DemarreurService,
        private val horloge: TimeProvider,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
    ) : TerminalSessionRepository,
        TerminalRuntime,
        EcouteurCoquille {
        /** Entrée interne : la coquille + les métadonnées propres au registre. */
        private data class Entree(
            val id: String,
            val coquille: CoquilleSession,
            var label: String,
            val repertoireTravail: String,
            val creation: Instant,
        )

        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)
        private val verrou = Mutex()

        private val entrees = mutableListOf<Entree>()

        // Noms sans préfixe « _ » : la règle ktlint backing-property-naming
        // exige une propriété publique homonyme (ex. `sessions`), incompatible
        // avec des fonctions observe*() du port — nommage explicite à la place.
        private val sessionsPubliees = MutableStateFlow<List<TerminalSessionSummary>>(emptyList())
        private val sessionActivePubliee = MutableStateFlow<String?>(null)

        // Signal de repeint (v0.31.5) : tampon 1, dernier gagnant — voir le
        // KDoc de TerminalRuntime.observeSorties.
        private val sortiesPubliees =
            MutableSharedFlow<Unit>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** File d'attente du throttle de sortie (une seule tâche en vol). */
        private var rafraichissementDiffere: Job? = null

        override fun observeSessions(): StateFlow<List<TerminalSessionSummary>> = sessionsPubliees.asStateFlow()

        override fun observeActiveSessionId(): StateFlow<String?> = sessionActivePubliee.asStateFlow()

        override fun observeSorties(): SharedFlow<Unit> = sortiesPubliees.asSharedFlow()

        override suspend fun createSession(
            workingDirectory: File,
            label: String?,
        ): String =
            withContext(dispatchers.default) {
                verrou.withLock {
                    val id = UUID.randomUUID().toString()
                    val libelle = label ?: "Session ${entrees.size + 1}"
                    val variables = environnement.baseEnvironment() + TERM_SESSION
                    // Thread PRINCIPAL obligatoire (v0.31.2, rapport
                    // d'appareil réel 511e1c7f) : le constructeur de
                    // `TerminalSession` crée son `MainThreadHandler` — un
                    // `Handler` sans Looper explicite — qui exige
                    // `Looper.myLooper() != null`, donc le thread
                    // principal. Lancé depuis un worker `Default` (tout le
                    // reste du registre y vit), il plantait avec « Can't
                    // create handler inside thread … not called
                    // Looper.prepare() ». Termux crée lui aussi ses
                    // sessions sur l'UI ; le fork/exec du pty est bref
                    // (millisecondes), comme dans Termux.
                    val coquille =
                        withContext(dispatchers.main) {
                            fabrique.creer(
                                shell = localisateur.defaultShell(),
                                repertoireTravail = workingDirectory.absolutePath,
                                environnement = variables.map { (cle, valeur) -> "$cle=$valeur" }.toTypedArray(),
                                ecouteur = this@RegistreSessionsTermux,
                            )
                        }
                    entrees +=
                        Entree(
                            id,
                            coquille,
                            libelle,
                            workingDirectory.absolutePath,
                            Instant.ofEpochMilli(horloge.nowMillis()),
                        )
                    publier()
                    // Les sessions vivent hors écran : le service foreground
                    // doit exister dès la première (section 4.2).
                    demarreurService.demarrer()
                    // TOUJOURS activer la nouvelle session (v0.31.5) : la
                    // création est un geste utilisateur — onglet « + », bouton
                    // de la toolbar, carte d'aperçu — et l'écran doit montrer
                    // la session fraîche, pas retomber sur l'ancienne.
                    sessionActivePubliee.value = id
                    journal.i(TAG) { "session de terminal créée" }
                    id
                }
            }

        override fun setActiveSession(sessionId: String) {
            sessionActivePubliee.value = sessionId
        }

        override suspend fun renameSession(
            sessionId: String,
            label: String,
        ) {
            withContext(dispatchers.default) {
                verrou.withLock {
                    entree(sessionId)?.let { it.label = label }
                    publier()
                }
            }
        }

        override suspend fun closeSession(sessionId: String) {
            withContext(dispatchers.default) {
                verrou.withLock {
                    val entree = entree(sessionId) ?: return@withContext
                    entree.coquille.terminer()
                    entrees.remove(entree)
                    if (sessionActivePubliee.value == sessionId) {
                        sessionActivePubliee.value = entrees.lastOrNull()?.id
                    }
                    publier()
                    journal.i(TAG) { "session de terminal fermée" }
                }
            }
        }

        override fun sessionFor(sessionId: String): com.termux.terminal.TerminalSession? {
            val coquille = synchronized(entrees) { entree(sessionId)?.coquille }
            return (coquille as? CoquilleTermux)?.session
        }

        // ------------------------------------------------------------------
        // Écoute des coquilles (callbacks Termux, thread du pty).
        // ------------------------------------------------------------------

        override fun surTexteModifie() {
            // Repeint immédiat (v0.31.5) : le texte tapé doit apparaître au
            // fil de l'eau — l'aperçu throttlé ne sert que la carte.
            sortiesPubliees.tryEmit(Unit)
            planifierRafraichissement()
        }

        override fun surTitreModifie() {
            planifierRafraichissement()
        }

        override fun surTerminee() {
            // Terminaison naturelle : l'entrée reste (isAlive = false),
            // publiée immédiatement — la carte d'aperçu change d'état. Le
            // signal de repeint accompagne : l'écran final du shell s'affiche.
            sortiesPubliees.tryEmit(Unit)
            rafraichissementDiffere?.cancel()
            portee.launch { verrou.withLock { publier() } }
        }

        /** Programme un rafraîchissement throttlé (fenêtre de conflation). */
        private fun planifierRafraichissement() {
            if (rafraichissementDiffere?.isActive == true) return
            rafraichissementDiffere =
                portee.launch {
                    delay(FENETRE_THROTTLE_MS)
                    verrou.withLock { publier() }
                }
        }

        /** Reconstruit et publie la liste des métadonnées. */
        private fun publier() {
            sessionsPubliees.value =
                entrees.map { entree ->
                    TerminalSessionSummary(
                        id = entree.id,
                        label = entree.label,
                        workingDirectoryPath = entree.repertoireTravail,
                        isAlive = entree.coquille.estVivante(),
                        lastOutputPreview = borner(entree.coquille.apercuTranscript()),
                        createdAt = entree.creation,
                    )
                }
        }

        /** Aperçu borné, replat sur une ligne. */
        private fun borner(transcript: String): String =
            transcript
                .takeLast(LONGUEUR_APERCU)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()

        private fun entree(id: String): Entree? = entrees.firstOrNull { it.id == id }

        private companion object {
            const val TAG = "TerminalRuntime"

            /** Fenêtre de throttle des sorties (millisecondes). */
            const val FENETRE_THROTTLE_MS = 250L

            /** Longueur maximale de l'aperçu de sortie. */
            const val LONGUEUR_APERCU = 160

            /** Terminal déclaré au shell (couleurs, capacités). */
            val TERM_SESSION = mapOf("TERM" to "xterm-256color")
        }
    }
