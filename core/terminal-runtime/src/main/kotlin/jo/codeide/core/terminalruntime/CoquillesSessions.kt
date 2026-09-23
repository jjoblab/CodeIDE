package jo.codeide.core.terminalruntime

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import javax.inject.Inject

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

    override fun getTerminalCursorStyle(): Int = STYLE_CURSEUR_BLOC

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

    private companion object {
        /** Curseur bloc stable (même défaut que Termux sans préférence). */
        private const val STYLE_CURSEUR_BLOC = 2
    }
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
                ClientTermux(ecouteur),
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
    constructor() : FabriqueCoquilles {
        override fun creer(
            shell: String,
            repertoireTravail: String,
            environnement: Array<String>,
            ecouteur: EcouteurCoquille,
        ): CoquilleSession = CoquilleTermux(shell, repertoireTravail, environnement, ecouteur)
    }
