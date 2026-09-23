package jo.codeide.feature.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Client de la [TerminalView] (Terminal T5) : câble le clavier étendu
 * interne au mécanisme officiel de Termux et ramène les sollicitations
 * d'interface vers l'activité.
 *
 * - [readControlKey]/[readAltKey] renvoient l'état des modificateurs du
 *   [ClavierEtenduView] — la vue applique alors Ctrl/Alt à l'entrée du
 *   clavier (mécanisme conçu pour les touches virtuelles) ;
 * - le toucher simple donne le focus et ouvre le clavier virtuel ;
 * - le bouton retour système **ferme l'écran** (jamais mappé sur Échap,
 *   section 5 : les sessions survivent via le service foreground) ;
 * - les journaux internes de la vue restent muets : la journalisation
 *   maison (`AppLogger`) passe par le registre (même choix que
 *   `ClientTermux` dans `core:terminal-runtime`).
 *
 * @param vue la vue de rendu branchée.
 * @param clavier la rangée de touches étendues (modificateurs).
 * @param surEmulateurPret appelé quand l'émulateur est en place
 * (re-application du thème du rendu).
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : le contrat tiers
 * `TerminalViewClient` impose 24 méthodes (précédent `ClientTermux`,
 * `ToolchainLocator`) — aucune n'est de notre ressort.
 */
@Suppress("TooManyFunctions")
internal class ClientVueTerminal(
    private val vue: TerminalView,
    private val clavier: ClavierEtenduView,
    private val surEmulateurPret: () -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale // Pincement volontairement inerte (T5).

    override fun onSingleTapUp(event: MotionEvent) {
        // Comme Termux : toucher la zone = saisir au clavier (0 = affichage
        // explicite, SHOW_IMPLICIT étant déprécié depuis l'API 33).
        vue.requestFocus()
        val gestionnaire =
            vue.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        gestionnaire.showSoftInput(vue, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = vue.hasFocus()

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent,
        session: TerminalSession,
    ): Boolean = false // Traitement par défaut de la vue (modificateurs compris).

    override fun onKeyUp(
        keyCode: Int,
        e: KeyEvent,
    ): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = clavier.ctrlActif

    override fun readAltKey(): Boolean = clavier.altActif

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(
        codePoint: Int,
        altKey: Boolean,
        session: TerminalSession,
    ): Boolean = false

    override fun onEmulatorSet() = surEmulateurPret()

    // Journaux internes de la vue : silencieux par design (KDoc de
    // classe) — les événements utiles passent par l'AppLogger du registre.
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
