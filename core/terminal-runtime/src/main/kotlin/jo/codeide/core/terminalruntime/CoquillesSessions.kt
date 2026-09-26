package jo.codeide.core.terminalruntime

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.StyleCurseurTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coquille d'une session shell vue par le registre (abstraction interne
 * du module) : le registre dialogue avec cette interface, jamais avec
 * `TerminalSession` en direct — les tests servent des coquilles
 * scriptées (aucune exécution réelle, exigence du prompt Terminal-1,
 * section 10).
 */
internal interface CoquilleSession {
    /** Le processus shell vit-il encore ? */
    fun estVivante(): Boolean

    /** Titre déclaré par le shell (séquence OSC), ou `null`. */
    fun titre(): String?

    /** Aperçu du transcript de sortie (brut — le registre le borne). */
    fun apercuTranscript(): String

    /** Termine le shell (fermeture explicite de l'onglet). */
    fun terminer()
}

/**
 * Événements d'une coquille vers le registre (traduction des callbacks
 * de `TerminalSessionClient`).
 */
internal interface EcouteurCoquille {
    /** Le texte affichable a changé (sortie du shell). */
    fun surTexteModifie()

    /** Le titre a changé. */
    fun surTitreModifie()

    /** Le shell s'est terminé de lui-même (commande `exit`). */
    fun surTerminee()
}

/**
 * Fabrique des coquilles : indirection d'injection — la production crée
 * de vraies sessions Termux, les tests des coquilles scriptées.
 */
internal interface FabriqueCoquilles {
    /**
     * Crée une session shell.
     *
     * @param shell chemin du shell (ToolchainLocator.defaultShell).
     * @param repertoireTravail répertoire initial.
     * @param environnement variables `"KEY=VALUE"`.
     * @param ecouteur récepteur des événements de la session.
     */
    fun creer(
        shell: String,
        repertoireTravail: String,
        environnement: Array<String>,
        ecouteur: EcouteurCoquille,
    ): CoquilleSession
}

/**
 * Adaptateur du client Termux vers l'écouteur du registre.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : le contrat tiers
 * `TerminalSessionClient` impose 16 méthodes — journalisation interne,
 * accessibilité et curseur comprises ; aucune n'est de notre ressort.
 */
@Suppress("TooManyFunctions")
private class ClientTermux(
    private val ecouteur: EcouteurCoquille,
    private val styleCurseur: () -> StyleCurseurTerminal,
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        ecouteur.surTexteModifie()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        ecouteur.surTitreModifie()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        ecouteur.surTerminee()
    }

    // Services d'accessibilité du terminal : sans vue de rendu ici (le
    // rendu vit dans feature:terminal), ces sollicitations n'ont pas
    // d'abonné — l'écran les traitera via TerminalView.
    override fun onCopyTextToClipboard(
        session: TerminalSession,
        text: String,
    ) = Unit

    override fun onPasteTextFromClipboard(session: TerminalSession) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int =
        when (styleCurseur()) {
            StyleCurseurTerminal.BLOC -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
            StyleCurseurTerminal.LIGNE -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            StyleCurseurTerminal.BARRE -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        }

    // Journaux internes de l'émulateur (l'artefact JitPack v0.118.3 les
    // exige sur le client) : silencieux par design — les événements utiles
    // (création, fermeture) passent par l'AppLogger du registre.
    override fun logError(
        tag: String,
        message: String,
    ) = Unit

    override fun logWarn(
        tag: String,
        message: String,
    ) = Unit

    override fun logInfo(
        tag: String,
        message: String,
    ) = Unit

    override fun logDebug(
        tag: String,
        message: String,
    ) = Unit

    override fun logVerbose(
        tag: String,
        message: String,
    ) = Unit

    override fun logStackTraceWithMessage(
        tag: String,
        message: String,
        e: Exception,
    ) = Unit

    override fun logStackTrace(
        tag: String,
        e: Exception,
    ) = Unit
}

/**
 * Coquille de production : enveloppe une vraie [TerminalSession] Termux
 * (pseudo-terminal, contrôle de tâches, couleurs — mécanisme dédié de
 * la section 1.5 du prompt, distinct de `NativeProcessLauncher`).
 *
 * La session réelle reste exposée (propriété [session]) pour l'API de
 * rendu réservée à `feature:terminal` (section 4.4).
 */
internal class CoquilleTermux
    internal constructor(
        shell: String,
        repertoireTravail: String,
        environnement: Array<String>,
        ecouteur: EcouteurCoquille,
        styleCurseur: () -> StyleCurseurTerminal,
    ) : CoquilleSession {
        internal val session: TerminalSession =
            TerminalSession(
                // shellPath =
                shell,
                // cwd =
                repertoireTravail,
                // args =
                arrayOfNulls<String>(0),
                // env =
                environnement,
                // transcriptRows =
                TRANSCRIPT_ROWS,
                // client =
                ClientTermux(ecouteur, styleCurseur),
            )

        override fun estVivante(): Boolean = session.isRunning

        override fun titre(): String? = session.title

        override fun apercuTranscript(): String =
            session.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()

        override fun terminer() {
            session.finishIfRunning()
        }

        private companion object {
            /** Lignes de transcript conservées (même valeur que Termux). */
            private const val TRANSCRIPT_ROWS = 2000
        }
    }

/** Fabrique de production : sessions Termux réelles. */
internal class FabriqueCoquillesTermux
    @Inject
    constructor(
        private val porteurStyleCurseur: PorteurStyleCurseur,
    ) : FabriqueCoquilles {
        override fun creer(
            shell: String,
            repertoireTravail: String,
            environnement: Array<String>,
            ecouteur: EcouteurCoquille,
        ): CoquilleSession =
            CoquilleTermux(shell, repertoireTravail, environnement, ecouteur, porteurStyleCurseur::lireStyle)
    }

/**
 * Détenteur du style de curseur courant (ADR 0059) : collecte les
 * paramètres applicatifs en tâche de fond et expose la dernière valeur
 * connue — l'émulateur Termux la relit à chaque `setCursorStyle()`
 * (notamment à la création d'une session), les sessions vivantes la
 * rappellent quand l'écran constate un changement du réglage.
 *
 * Singleton du processus : une seule collecte, une seule source de
 * vérité pour toutes les sessions. NB : l'ancien client renvoyait la
 * constante 2 en l'appelant « bloc » — c'était le style BARRE de
 * l'enum Termux ; le porteur corrige le mapping (BLOC = 0).
 */
@Singleton
internal class PorteurStyleCurseur
    @Inject
    constructor(
        observeReglages: ObserveSettingsUseCase,
    ) {
        /** Dernier style persisté connu (BLOC avant la première émission). */
        @Volatile
        internal var style: StyleCurseurTerminal = StyleCurseurTerminal.BLOC

        /** Alias de lecture stable pour les coquilles (référence de méthode). */
        internal fun lireStyle(): StyleCurseurTerminal = style

        private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            portee.launch {
                observeReglages().collect { reglages -> style = reglages.styleCurseurTerminal }
            }
        }
    }
