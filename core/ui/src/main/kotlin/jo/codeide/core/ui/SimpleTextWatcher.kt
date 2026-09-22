package jo.codeide.core.ui

import android.text.Editable
import android.text.TextWatcher

/**
 * [TextWatcher] sans boilerplate (étape 10) : seuls les rappels utiles sont
 * redéfinis — les écouteurs de saisie n'ont presque jamais besoin des trois.
 */
public open class SimpleTextWatcher : TextWatcher {
    override fun beforeTextChanged(
        texte: CharSequence?,
        debut: Int,
        nombre: Int,
        apres: Int,
    ) = Unit

    override fun onTextChanged(
        texte: CharSequence?,
        debut: Int,
        avant: Int,
        nombre: Int,
    ) = Unit

    override fun afterTextChanged(texte: Editable?) = Unit
}
