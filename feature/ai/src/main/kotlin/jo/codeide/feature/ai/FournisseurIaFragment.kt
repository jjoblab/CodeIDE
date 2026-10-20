package jo.codeide.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.ai.databinding.FragmentFournisseurIaBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Détail d'un fournisseur IA (mission IA, lot IA-2, spec E4) : URL de
 * base, clé **masquée** (aperçu `••••1234`, pas d'affichage — ADR 0124
 * §1), test de connexion, remplacement/suppression, sélection du
 * fournisseur actif. Le champ de clé est vidé à l'écran après
 * enregistrement et n'est jamais restauré (saveEnabled=false) — après
 * rotation, l'aperçu demeure et le champ est vide (critère IA-2).
 */
@AndroidEntryPoint
public class FournisseurIaFragment : BaseFragment<FragmentFournisseurIaBinding>() {
    /** Navigation découplée (retour). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: FournisseurIaViewModel by viewModels()

    /** Garde de rendu : coupe les listeners pendant l'écriture programmatique. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentFournisseurIaBinding = FragmentFournisseurIaBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.toolbarFournisseurIa.setNavigationOnClickListener { navigator.goBack() }
        binding.toolbarFournisseurIa.title = titreFournisseur()
        brancher()
    }

    private fun brancher() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.etat.collect { etat -> rendre(etat) } }
                launch { viewModel.effets.collect { effet -> rendreEffet(effet) } }
            }
        }

        binding.champUrlFournisseur.setOnFocusChangeListener { _, aLeFocus ->
            if (aLeFocus) return@setOnFocusChangeListener
            val saisie =
                binding.champUrlFournisseur.text
                    ?.toString()
                    .orEmpty()
            viewModel.onAction(ActionFournisseurIa.ChangerUrl(saisie))
        }
        binding.boutonEnregistrerCle.setOnClickListener { enregistrerCle() }
        binding.boutonSupprimerCle.setOnClickListener { viewModel.onAction(ActionFournisseurIa.SupprimerCle) }
        binding.boutonTesterConnexion.setOnClickListener { viewModel.onAction(ActionFournisseurIa.TesterConnexion) }
        binding.boutonUtiliser.setOnClickListener { viewModel.onAction(ActionFournisseurIa.UtiliserCeFournisseur) }
    }

    /** Enregistrement de la clé : lecture → CharArray → VM (effacé après usage). */
    private fun enregistrerCle() {
        val champ = binding.champCle
        val texte = champ.text?.toString() ?: return
        if (texte.isBlank()) {
            Retour.snackbar(binding.root, getString(R.string.ia_erreur_cle_vide))
            return
        }
        viewModel.onAction(ActionFournisseurIa.EnregistrerCle(texte.toCharArray()))
        champ.setText("")
    }

    private fun rendre(etat: EtatFournisseurIaDetail) {
        renduEnCours = true
        if (binding.champUrlFournisseur.text.isNullOrBlank() || !binding.champUrlFournisseur.hasFocus()) {
            binding.champUrlFournisseur.setText(etat.urlBase)
        }
        binding.saisieUrlFournisseur.error = if (etat.urlValide) null else getString(R.string.ia_url_erreur)

        binding.etatCle.text =
            when (etat.etatCle) {
                EtatCleFournisseur.ENREGISTREE -> {
                    getString(
                        R.string.ia_fournisseur_sous_titre_ok,
                        etat.apercuCle ?: getString(R.string.ia_cle_apercu_vide),
                    )
                }

                EtatCleFournisseur.A_RESAISIR -> {
                    getString(R.string.ia_fournisseur_sous_titre_ressaisir)
                }

                EtatCleFournisseur.ABSENTE -> {
                    getString(R.string.ia_cle_apercu_vide)
                }
            }
        binding.boutonSupprimerCle.isVisible = etat.etatCle != EtatCleFournisseur.ABSENTE

        binding.boutonTesterConnexion.isEnabled = !etat.testEnCours
        binding.progressionTest.isVisible = etat.testEnCours

        when (val resultat = etat.resultatTest) {
            ResultatTestIa.Succes -> {
                binding.resultatTest.isVisible = true
                binding.resultatTest.text = getString(R.string.ia_test_succes)
            }

            is ResultatTestIa.Echec -> {
                binding.resultatTest.isVisible = true
                val message = PresentationErreursIa.message(resultat.erreur)
                binding.resultatTest.text =
                    getString(R.string.ia_test_echec, getString(message.ressource, message.argument ?: ""))
            }

            null -> {
                binding.resultatTest.isVisible = false
            }
        }

        binding.etatActif.text =
            if (etat.estActif) {
                getString(R.string.ia_utilise_actuel)
            } else {
                getString(R.string.ia_fournisseur_active, nomFournisseur(etat.identifiant))
            }
        binding.boutonUtiliser.isVisible = !etat.estActif
        renduEnCours = false
    }

    private fun rendreEffet(effet: EffetFournisseurIa) {
        when (effet) {
            EffetFournisseurIa.CleEnregistree -> {
                binding.champCle.setText("")
            }

            is EffetFournisseurIa.Message -> {
                val texte =
                    effet.argument?.let { requireContext().getString(effet.ressource, it) }
                        ?: requireContext().getString(effet.ressource)
                Retour.snackbar(binding.root, texte)
            }
        }
    }

    private fun titreFournisseur(): String = nomFournisseur(viewModel.etat.value.identifiant)

    private fun nomFournisseur(identifiant: String): String =
        when (identifiant) {
            FournisseursIa.OPENAI_COMPATIBLE -> getString(R.string.ia_fournisseur_openai)
            else -> getString(R.string.ia_fournisseur_anthropic)
        }
}
