package {{packageName}}

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import {{packageName}}.databinding.FragmentAccueilBinding

/**
 * {{t:fragment.kdoc}}
 */
class FragmentAccueil : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val liaison = FragmentAccueilBinding.inflate(inflater, container, false)
        when (arguments?.getString(CLE_ESPACE) ?: ESPACE_ACCUEIL) {
            ESPACE_APROPOS -> liaison.texteBienvenue.text = getString(R.string.apropos_texte)
            else -> {
                val greeter = Greeter("{{t:app.greeting}}")
                liaison.texteBienvenue.text = greeter.greet(getString(R.string.app_name))
            }
        }
        return liaison.root
    }

    companion object {
        const val ESPACE_ACCUEIL = "accueil"
        const val ESPACE_APROPOS = "apropos"
        private const val CLE_ESPACE = "espace"

        /** Fabrique un fragment pour l'espace demandé (accueil ou à propos). */
        fun nouveau(espace: String): FragmentAccueil =
            FragmentAccueil().apply {
                arguments = Bundle().apply { putString(CLE_ESPACE, espace) }
            }
    }
}
