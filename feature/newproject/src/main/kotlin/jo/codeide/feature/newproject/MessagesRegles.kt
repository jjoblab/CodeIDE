package jo.codeide.feature.newproject

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import jo.codeide.core.model.MessageRegle
import jo.codeide.core.model.SeveriteRegle
import jo.codeide.feature.newproject.databinding.VueMessageRegleBinding

/**
 * Rendu partagé des messages de règles de projet (ADR 0153 §5) : une ligne
 * par message, symbole de sévérité + texte résolu — utilisé par les étapes
 * de modèle (au bon endroit du choix) et par le récapitulatif (avertissements
 * restants). La sévérité est portée par la couleur et le symbole, jamais par
 * un identifiant brut.
 */
internal object MessagesRegles {
    /** Ajoute une ligne par message dans [conteneur] (reconstruction simple). */
    fun rendre(conteneur: ViewGroup, messages: List<MessageRegle>, inflater: LayoutInflater) {
        conteneur.removeAllViews()
        messages.forEach { message ->
            val ligne = VueMessageRegleBinding.inflate(inflater, conteneur, false)
            ligne.texteMessage.text = message.texte
            ligne.symboleSeverite.text =
                when (message.severite) {
                    SeveriteRegle.ERREUR -> SYMBOLE_ERREUR
                    SeveriteRegle.AVERTISSEMENT -> SYMBOLE_AVERTISSEMENT
                    SeveriteRegle.INFO -> SYMBOLE_INFO
                }
            ligne.symboleSeverite.contentDescription =
                conteneur.context.getString(
                    when (message.severite) {
                        SeveriteRegle.ERREUR -> R.string.wizard_message_severite_erreur
                        SeveriteRegle.AVERTISSEMENT -> R.string.wizard_message_severite_avertissement
                        SeveriteRegle.INFO -> R.string.wizard_message_severite_info
                    },
                )
            ligne.symboleSeverite.setTextColor(
                ContextCompat.getColor(
                    conteneur.context,
                    if (message.severite == SeveriteRegle.ERREUR) {
                        jo.codeide.core.ui.R.color.codeide_error
                    } else {
                        jo.codeide.core.ui.R.color.codeide_on_surface_variant
                    },
                ),
            )
            conteneur.addView(ligne.root)
        }
    }

    private const val SYMBOLE_ERREUR = "✕"
    private const val SYMBOLE_AVERTISSEMENT = "▲"
    private const val SYMBOLE_INFO = "ℹ"
}
