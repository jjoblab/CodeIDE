package jo.codeide.feature.install

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Client du [TerminalView] **mini** de l'écran d'installation (v0.54.0,
 * retour utilisateur : « le journal live devait être un mini écran
 * TerminalView, pas une nouvelle session dans l'écran du terminal »).
 *
 * Version volontairement minimale du `ClientVueTerminal` de
 * `feature:terminal` (qui reste LA référence plein écran) : pas de rangée
 * de touches étendues, pas de pincement-zoom, pas de copie automatique —
 * le mini terminal est un journal vivant AVANT d'être une console. Il
 * reste **interactif** : le toucher donne le focus et ouvre le clavier
 * logiciel (relancer `codeide-env` à la main, répondre à une invite,
 * Ctrl+C via un clavier physique…), la voie d'entrée officielle de Termux.
 *
 * Les journaux internes de la vue restent muets : la journalisation
 * maison (`AppLogger`) passe par le registre (même choix que
 * `ClientVueTerminal`).
 *
 * @param vue la vue de rendu branchée.
 */
@Suppress("TooManyFunctions") // Contrat tiers TerminalViewClient : 24 méthodes imposées.
internal class ClientTerminalMini(
    private val vue: TerminalView,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale // Pas de zoom : mini écran.

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
    ): Boolean = false // Traitement par défaut de la vue.

    override fun onKeyUp(
        keyCode: Int,
        e: KeyEvent,
    ): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(
        codePoint: Int,
        altKey: Boolean,
        session: TerminalSession,
    ): Boolean = false

    override fun onEmulatorSet() = Unit // Le thème est appliqué au branchement.

    // Journaux internes de la vue : silencieux par design (KDoc de classe).
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
