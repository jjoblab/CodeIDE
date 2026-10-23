package jo.codeide.feature.editor

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import jo.codeide.feature.editor.databinding.VueBarreConsigneIaBinding

/**
 * Barre de consigne en ligne (mission IA, lot IA-5, ADR 0126, spec E3) :
 * posée entre l'éditeur et la barre de symboles (jamais en overlay — la
 * sélection reste visible), montée avec le clavier via les insets de
 * [GestionnaireInsetsEditeur] (ADR 0118). Actions rapides pré-remplissant
 * le champ, « Envoyer » transmet la consigne au chat via
 * [jo.codeide.core.ui.PontIaEditeur.envoyerConsigne] (action utilisateur
 * explicite — ADR 0121 point 3), croix de fermeture.
 *
 * Vue autonome (le câblage hôte reste mince — ADR 0126) : les enfants
 * sont liés en [onFinishInflate], l'hôte ne branche que trois rappels.
 */
class BarreConsigneIa
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialCardView(context, attrs) {
    private lateinit var binding: VueBarreConsigneIaBinding

    /** Consigne transmise telle quelle (champ non vide — bouton armé). */
    var surEnvoyer: ((String) -> Unit)? = null

    /** Fermeture par la croix (l'utilisateur garde la main — ADR 0126). */
    var surFermer: (() -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = VueBarreConsigneIaBinding.bind(this)

        binding.boutonEnvoyerConsigne.setOnClickListener {
            val consigne =
                binding.saisieConsigne.text
                    ?.toString()
                    .orEmpty()
                    .trim()
            if (consigne.isNotEmpty()) {
                surEnvoyer?.invoke(consigne)
            }
        }
        binding.boutonFermerBarreIa.setOnClickListener {
            surFermer?.invoke()
        }
        bindersActionsRapides()
    }

    /** Affiche la barre avec la ligne de contexte (sélection + fichier). */
    fun montrer(ligneContexte: String) {
        binding.ligneContexteIa.text = ligneContexte
        isVisible = true
        binding.saisieConsigne.requestFocus()
    }

    /** Masque la barre (consigne conservée dans le champ — reprise aisée). */
    fun masquer() {
        isVisible = false
    }

    /** Actions rapides : pré-remplissent le champ (l'utilisateur relit). */
    private fun bindersActionsRapides() {
        binding.actionExpliquer.setOnClickListener { preRemplir(R.string.ia_barre_consigne_expliquer) }
        binding.actionCorriger.setOnClickListener { preRemplir(R.string.ia_barre_consigne_corriger) }
        binding.actionDocumenter.setOnClickListener { preRemplir(R.string.ia_barre_consigne_documenter) }
        binding.actionTests.setOnClickListener { preRemplir(R.string.ia_barre_consigne_tests) }
        binding.actionSimplifier.setOnClickListener { preRemplir(R.string.ia_barre_consigne_simplifier) }
    }

    /** Pose un modèle de consigne dans le champ. */
    private fun preRemplir(ressource: Int) {
        binding.saisieConsigne.setText(context.getString(ressource))
    }
}
