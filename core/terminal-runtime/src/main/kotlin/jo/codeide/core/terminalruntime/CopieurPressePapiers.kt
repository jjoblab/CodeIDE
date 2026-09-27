package jo.codeide.core.terminalruntime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port de copie vers le presse-papiers (correctif C3 du prompt Terminal) :
 * Termux appelle [TerminalSessionClient.onCopyTextToClipboard] quand
 * l'utilisateur sélectionne du texte puis appuie sur « Copier » dans la
 * barre d'action native (ET pour les séquences OSC 52) — l'adaptateur
 * `ClientTermux` y délègue la vraie écriture au système.
 */
internal interface CopieurPressePapiers {
    /**
     * Copie [texte] au presse-papiers — sans effet sur texte vide ou nul
     * (garde partagée : aucun texte sélectionné ne mérite un clip vide).
     */
    fun copier(texte: String?)
}

/**
 * Implémentation Android du port (C3) : écriture réelle au
 * [ClipboardManager].
 *
 * Confirmation visuelle : sur Android 13+ (API 33+), le système affiche
 * DEJA son propre bandeau à chaque `setPrimaryClip()` — un Toast maison
 * ferait doublon ; sur les versions antérieures, un Toast court reste
 * utile (aucun autre retour sinon).
 */
@Singleton
internal class CopieurPressePapiersAndroid
    @Inject
    constructor(
        // Annotation sans `private val` (leçon T1) : champ dérivé ci-dessous.
        @ApplicationContext contexte: Context,
    ) : CopieurPressePapiers {
        private val contexteApplication: Context = contexte.applicationContext

        private val pressePapiers: ClipboardManager =
            contexteApplication.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        override fun copier(texte: String?) {
            if (texte.isNullOrEmpty()) return
            pressePapiers.setPrimaryClip(
                ClipData.newPlainText(
                    contexteApplication.getString(R.string.terminal_selection_libelle),
                    texte,
                ),
            )
            if (Build.VERSION.SDK_INT < SEUIL_CONFIRMATION_SYSTEME) {
                Toast
                    .makeText(
                        contexteApplication,
                        R.string.terminal_selection_copiee,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }

        private companion object {
            /** Android 13 (API 33) : le système confirme lui-même la copie. */
            private const val SEUIL_CONFIRMATION_SYSTEME = 33
        }
    }
