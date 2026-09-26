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
 * - [readControlKey]/[readAltKey] interrogent l'état des modificateurs
 *   du [ClavierEtenduView] via les lambdas [lireCtrl]/[lireAlt] (v0.32.2 :
 *   le tiroir partage le clavier entre plusieurs panneaux) — la vue
 *   applique alors Ctrl/Alt à l'entrée du clavier (mécanisme conçu pour
 *   les touches virtuelles) ;
 * - le toucher simple donne le focus et ouvre le clavier virtuel ;
 * - le pincement zoome la police (v0.31.5) : le contrat Termux
 *   (vérifié sur le bytecode de terminal-view v0.118.3) passe à
 *   `onScale` le facteur ACCUMULÉ du geste et n'applique JAMAIS
 *   lui-même — c'est le client qui change la taille puis retourne 1.0f
 *   pour consommer le facteur ; l'ancien retour `scale` intact rendait
 *   le pincement inerte (retour d'appareil réel) ;
 * - le bouton retour système **ferme l'écran** (jamais mappé sur Échap,
 *   section 5 : les sessions survivent via le service foreground) ;
 * - copie automatique de la sélection (ADR 0059) : à la **fin** du mode
 *   sélection ([copyModeChanged] à faux), le texte sélectionné conservé
 *   par Termux ([TerminalView.getStoredSelectedText]) part au
 *   presse-papiers si le réglage [copieSelectionAuto] est actif —
 *   l'hôte fournit la lecture du réglage et l'écriture ;
 * - les journaux internes de la vue restent muets : la journalisation
 *   maison (`AppLogger`) passe par le registre (même choix que
 *   `ClientTermux` dans `core:terminal-runtime`).
 *
 * @param vue la vue de rendu branchée.
 * @param lireCtrl lit l'état du modificateur Ctrl (rangée de touches
 * étendues — ou jamais, si l'hôte n'en porte pas).
 * @param lireAlt lit l'état du modificateur Alt.
 * @param surEmulateurPret appelé quand l'émulateur est en place
 * (re-application du thème du rendu).
 * @param zoomer applique le facteur accumulé [facteur] (nouvelle taille
 * en pixels via [TerminalView.setTextSize]) ; retourne `true` si le
 * facteur a été consommé (le compteur accumulé repart à 1.0f).
 * @param copieSelectionAuto lit le réglage « copier la sélection » de
 * l'hôte (ADR 0059).
 * @param copierTexte écrit du texte au presse-papiers (retour
 * utilisateur de l'hôte — l'appelant peut montrer un toast).
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : le contrat tiers
 * `TerminalViewClient` impose 24 méthodes (précédent `ClientTermux`,
 * `ToolchainLocator`) — aucune n'est de notre ressort.
 */
@Suppress(
    // Exemption detekt ciblée (règle 16) : TooManyFunctions — le contrat
    // tiers TerminalViewClient impose 24 méthodes ; LongParameterList —
    // sept lambdas d'hôte, chacune documentée dans le KDoc de la classe.
    "TooManyFunctions",
    "LongParameterList",
)
internal class ClientVueTerminal(
    private val vue: TerminalView,
    private val lireCtrl: () -> Boolean,
    private val lireAlt: () -> Boolean,
    private val surEmulateurPret: () -> Unit,
    private val zoomer: (facteur: Float) -> Boolean,
    private val copieSelectionAuto: () -> Boolean = { false },
    private val copierTexte: (texte: String) -> Unit = {},
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = if (zoomer(scale)) 1.0f else scale

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

    override fun copyModeChanged(copyMode: Boolean) {
        // Fin du mode sélection : copie automatique si le réglage est actif
        // (ADR 0059) — Termux conserve le texte sélectionné après la fin
        // du mode, on le lit puis on le consomme pour ne pas recopier une
        // sélection déjà prise à la fin suivante.
        if (!copyMode && copieSelectionAuto()) {
            val texte = vue.storedSelectedText
            if (!texte.isNullOrEmpty()) {
                copierTexte(texte)
                vue.unsetStoredSelectedText()
            }
        }
    }

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

    override fun readControlKey(): Boolean = lireCtrl()

    override fun readAltKey(): Boolean = lireAlt()

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
