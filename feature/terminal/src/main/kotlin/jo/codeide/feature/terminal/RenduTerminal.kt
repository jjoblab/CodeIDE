package jo.codeide.feature.terminal

import androidx.core.content.ContextCompat
import com.termux.view.TerminalView

/**
 * Application du thème de l'app au rendu Termux (partagé v0.32.2 entre
 * l'écran plein écran et les panneaux du tiroir).
 *
 * Les couleurs courantes vivent dans l'émulateur
 * (`mColors.mCurrentColors`, disposition jackpal — 259 entrées, indices
 * 256/257/258 = premier plan/arrière-plan/curseur) ; la palette par
 * défaut de Termux est sombre, le thème de l'application réécrit ces
 * trois entrées (ressources `values`/`values-night`).
 *
 * ADR 0059 : le **style** du curseur (bloc / ligne / barre) vient du
 * réglage utilisateur — l'émulateur le relit depuis le client de session
 * (`TerminalSessionClient.getTerminalCursorStyle`, branché sur les
 * paramètres par `core:terminal-runtime`), il suffit de redemander.
 */
internal fun TerminalView.appliquerThemeRendu() {
    val fond = ContextCompat.getColor(context, jo.codeide.core.ui.R.color.codeide_terminal_fond)
    val texte = ContextCompat.getColor(context, jo.codeide.core.ui.R.color.codeide_terminal_texte)
    setBackgroundColor(fond)
    val emulateur = mEmulator ?: return
    val couleurs = emulateur.mColors.mCurrentColors
    couleurs[INDICE_PREMIER_PLAN] = texte
    couleurs[INDICE_ARRIERE_PLAN] = fond
    couleurs[INDICE_CURSEUR] = texte
    onScreenUpdated()
}

/**
 * Réapplique le style du curseur persisté — `setCursorStyle()` relit la
 * valeur fournie par le client de session (donc par les paramètres), les
 * sessions vivantes changent de curseur sans redémarrage.
 */
internal fun TerminalView.appliquerStyleCurseur() {
    val emulateur = mEmulator ?: return
    emulateur.setCursorStyle()
    onScreenUpdated()
}

/** Indices de la palette Termux (disposition jackpal, 259 entrées). */
internal const val INDICE_PREMIER_PLAN = 256

/** Arrière-plan du rendu. */
internal const val INDICE_ARRIERE_PLAN = 257

/** Couleur du curseur. */
internal const val INDICE_CURSEUR = 258
