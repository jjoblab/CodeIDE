package jo.codeide.feature.editor

import android.view.KeyEvent
import jo.codeeditor.session.EditorSession
import jo.codeeditor.view.EditorView
import jo.codeeditor.view.render.EditorDecorationPainter
import jo.codeeditor.view.render.EditorPaintContext
import jo.codeide.core.ui.PontCompletionFantomeIa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rendu et cycle de vie du « texte fantôme » (IA-8b, ADR 0129) :
 *
 * - rendu en **inlay** via `EditorDecorationPainter` (aucune vue Android
 *   intercalée, jamais de décalage de saisie), couleur atténuée ;
 * - déclenchement après **800 ms** de pause (debounce), curseur en fin
 *   de saisie ;
 * - **annulation à la frappe** : tout mouvement du curseur (frappe)
 *   annule la requête en vol et efface le fantôme (une requête en vol
 *   au maximum — le pont l'impose) ;
 * - **Tab** accepte en UN LOT (`beginBatch/endBatch`, un seul Annuler),
 *   **Échap** rejette ; acceptation partielle : différée (ADR 0129 §4).
 *
 * Le fantôme n'est jamais annoncé à TalkBack (décoration pure) ;
 * l'acceptation l'est côté hôte (« suggestion insérée »).
 */
@android.annotation.SuppressLint("RestrictedApi")
@kotlin.Suppress("RestrictedApi")
public class FantomeCompletionIa(
    private val pont: PontCompletionFantomeIa,
    private val vue: EditorView,
    private val session: () -> EditorSession?,
    private val chemin: () -> String,
    private val portee: CoroutineScope,
) {
    /** Peinture du fantôme : inlay texte après le curseur (gris atténué). */
    private val peintre =
        object : EditorDecorationPainter {
            override fun paint(contexte: EditorPaintContext) {
                val texte = suggestion ?: return
                if (position < 0 || position > contexte.document.length()) return
                contexte.addPluginInlay(position, texte, COULEUR_FANTOME)
            }
        }

    private var suggestion: String? = null
    private var position: Int = -1
    private var debounce: Job? = null

    /**
     * Installe le peintre, l'écoute de curseur et les touches Tab/Échap.
     * Exemption lint ciblée (règle 8) : RestrictedApi — `painterHost` est
     * la voie DOCUMENTÉE pour le rendu d'inlays (ADR 0129 §4 rendu via
     * `EditorDecorationPainter`) sur la bibliothèque sœur `cel-ui` du
     * même éditeur ; l'annotation vise l'usage inter-bibliothèque, pas
     * l'intégration hôte de CodeIDE.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    public fun installer() {
        vue.painterHost.register(peintre)
        vue.addOnSelectionChangedListener { _, fin, _ -> surCurseur(fin) }
        vue.setOnKeyListener { _, code, evenement ->
            if (evenement.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (code) {
                    KeyEvent.KEYCODE_TAB -> {
                        accepter()
                        true
                    }

                    KeyEvent.KEYCODE_ESCAPE -> {
                        effacer()
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    public fun desinstaller() {
        vue.painterHost.unregister(peintre)
        debounce?.cancel()
        portee.launch { pont.annuler() }
    }

    /** Mouvement du curseur (frappe incluse) : annulation + réarmement. */
    private fun surCurseur(curseur: Int) {
        effacer()
        debounce?.cancel()
        debounce =
            portee.launch {
                delay(PAUSE_MS)
                demander(curseur)
            }
    }

    // Exemption detekt ciblée (règle 16) : ReturnCount — gardes (session,
    // curseur, révision) : chaque issue est un retour dédié.
    @Suppress("ReturnCount")
    private suspend fun demander(curseur: Int) {
        val session = session() ?: return
        val texte = session.document.getText()
        if (curseur < 0 || curseur > texte.length) return
        val prefixe = texte.substring(maxOf(0, curseur - CONTEXTE_PREFIXE), curseur)
        val suffixe = texte.substring(curseur, minOf(texte.length, curseur + CONTEXTE_SUFFIXE))
        val revisionAvant = session.document.revision
        val obtenue = pont.suggerer(chemin(), prefixe, suffixe)
        if (obtenue != null && session.document.revision != revisionAvant) {
            // Le document a bougé pendant la requête : le fantôme serait décalé.
            return
        }
        if (obtenue != null) {
            suggestion = obtenue
            position = curseur
            vue.invalidate()
        }
    }

    /** « Tab » : insertion en UN LOT + compteur d'acceptation (ADR 0129 §3).
     * Exemption detekt ciblée (règle 16) : ReturnCount — gardes (suggestion,
     * session, plage) : chaque issue est un retour dédié. */
    @Suppress("ReturnCount")
    public fun accepter() {
        val texte = suggestion ?: return
        val debut = position
        val session = session() ?: return
        if (debut < 0 || debut > session.document.length()) return
        session.beginBatch()
        try {
            session.replaceRange(debut, debut, texte)
        } finally {
            session.endBatch()
        }
        effacer()
        portee.launch { pont.noterAcceptation(texte.length) }
    }

    /** Rejette le fantôme (Échap, changement d'onglet, arrêt). */
    public fun effacer() {
        suggestion = null
        position = -1
        vue.invalidate()
    }

    private companion object {
        /** Contexte borné (confidentialité ADR 0124, plafond ADR 0129). */
        const val CONTEXTE_PREFIXE = 4_000
        const val CONTEXTE_SUFFIXE = 2_000

        /** Pause de saisie avant déclenchement (ADR 0129 §2). */
        const val PAUSE_MS = 800L

        /** Gris atténué (ADR 0129 §4 : `onSurfaceVariant` atténué). */
        const val COULEUR_FANTOME = 0x8F7C7C8A.toInt()
    }
}
