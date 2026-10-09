package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentRechercheBinding

/**
 * Fragment Recherche du tiroir (mission Recherche S2-S5, ADR 0094) :
 * champ de recherche avec bascules, résultats groupés par fichier.
 */
@AndroidEntryPoint
class RechercheFragment : Fragment() {
    private val viewModel: RechercheViewModel by viewModels()

    private var liaisonAmorce: FragmentRechercheBinding? = null
    private val liaison: FragmentRechercheBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = FragmentRechercheBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonEffacer.setOnClickListener { viewModel.effacer() }
        liaison.basculeCasse.setOnClickListener { viewModel.basculerCasse() }
        liaison.basculeMotEntier.setOnClickListener { viewModel.basculerMotEntier() }
        liaison.basculeRegex.setOnClickListener { viewModel.basculerRegex() }
        liaison.champRecherche.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.requete(s?.toString() ?: "")
                }
            },
        )
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    private fun rendre(etat: EtatRecherche) {
        liaison.basculeCasse.isActivated = etat.ignorerCasse
        liaison.basculeMotEntier.isActivated = etat.motEntier
        liaison.basculeRegex.isActivated = etat.regex
        liaison.boutonEffacer.isVisible = etat.requete.isNotBlank()
        liaison.chargement.isVisible = etat.chargement
        liaison.resume.isVisible = etat.resultats.isNotEmpty()
        liaison.resume.text = etat.resume
        liaison.listeResultats.isVisible = etat.resultats.isNotEmpty()
        liaison.listeResultats.text =
            etat.resultats.joinToString("\n\n") { r ->
                "${r.nomFichier}:${r.numeroLigne}\n  ${r.extrait}"
            }
        liaison.zoneVide.isVisible = etat.requete.isNotBlank() && etat.resultats.isEmpty() && !etat.chargement
        liaison.erreur.isVisible = etat.erreur != null
        liaison.erreur.text = etat.erreur
        liaison.plafoidAtteint.isVisible = etat.plafondAtteint
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }
}
