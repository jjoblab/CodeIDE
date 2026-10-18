package jo.codeide.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.ai.databinding.FragmentReglagesIaBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paramètres → Assistant IA (mission IA, lot IA-2, spec E4) : activation,
 * fournisseurs (état de clé par ligne), modèle, confidentialité, limites.
 * Aucune logique métier : orchestration du [ReglagesIaViewModel] (UDF).
 */
@AndroidEntryPoint
public class ReglagesIaFragment : BaseFragment<FragmentReglagesIaBinding>() {
    /** Navigation découplée (consentement, détail fournisseur, retour). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: ReglagesIaViewModel by viewModels()

    /** Garde de rendu : coupe les listeners pendant l'écriture programmatique. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentReglagesIaBinding = FragmentReglagesIaBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.toolbarReglagesIa.setNavigationOnClickListener { navigator.goBack() }
        brancher()
    }

    private fun brancher() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.etat.collect { etat -> rendre(etat) }
                }
                launch {
                    viewModel.effets.collect { effet -> rendreEffet(effet) }
                }
            }
        }

        binding.interrupteurIaActive.setOnCheckedChangeListener { interrupteur, active ->
            if (!renduEnCours) viewModel.onAction(ActionReglagesIa.ChangerActivation(active))
            if (!active) interrupteur.isChecked = false
        }
        binding.ligneConsentement.setOnClickListener { viewModel.onAction(ActionReglagesIa.OuvrirConsentement) }

        binding.rangeeFournisseurAnthropic.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.OuvrirFournisseur(FournisseursIa.ANTHROPIC))
        }
        binding.rangeeFournisseurOpenai.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.OuvrirFournisseur(FournisseursIa.OPENAI_COMPATIBLE))
        }
        binding.radioFournisseurAnthropic.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChoisirFournisseur(FournisseursIa.ANTHROPIC))
        }
        binding.radioFournisseurOpenai.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChoisirFournisseur(FournisseursIa.OPENAI_COMPATIBLE))
        }

        binding.champModeleIa.setOnItemClickListener { _, _, position, _ ->
            val choisi =
                binding.champModeleIa.adapter
                    .getItem(position)
                    .toString()
            viewModel.onAction(ActionReglagesIa.ChangerModele(choisi))
        }
        binding.champModeleIa.setOnEditorActionListener { texte, _, _ ->
            viewModel.onAction(ActionReglagesIa.ChangerModele(texte.text.toString()))
            false
        }
        binding.boutonActualiserModeles.setOnClickListener { viewModel.onAction(ActionReglagesIa.ActualiserModeles) }

        brancherInterrupteur(binding.interrupteurConfFichier, AspectConfidentialite.FICHIER_ACTIF)
        brancherInterrupteur(binding.interrupteurConfChemins, AspectConfidentialite.LISTE_CHEMINS)
        brancherInterrupteur(binding.interrupteurConfDiagnostics, AspectConfidentialite.DIAGNOSTICS)

        binding.champMaxJetons.setOnFocusChangeListener { _, aLeFocus ->
            if (aLeFocus) return@setOnFocusChangeListener
            viewModel.onAction(ActionReglagesIa.ChangerMaxJetons(texteVersEntier(binding.champMaxJetons.text)))
        }
        binding.champDelai.setOnFocusChangeListener { _, aLeFocus ->
            if (aLeFocus) return@setOnFocusChangeListener
            viewModel.onAction(ActionReglagesIa.ChangerDelai(texteVersEntier(binding.champDelai.text)))
        }
    }

    private fun brancherInterrupteur(
        interrupteur: MaterialSwitch,
        aspect: AspectConfidentialite,
    ) {
        interrupteur.setOnCheckedChangeListener { _, valeur ->
            if (!renduEnCours) viewModel.onAction(ActionReglagesIa.ChangerConfidentialite(aspect, valeur))
        }
    }

    private fun rendre(etat: EtatReglagesIa) {
        renduEnCours = true
        binding.interrupteurIaActive.isChecked = etat.active
        binding.radioFournisseurAnthropic.isChecked = etat.fournisseurActif == FournisseursIa.ANTHROPIC
        binding.radioFournisseurOpenai.isChecked = etat.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE

        val parIdentifiant = etat.fournisseurs.associateBy { it.identifiant }
        rendreSousTitreFournisseur(
            binding.sousTitreFournisseurAnthropic,
            parIdentifiant[FournisseursIa.ANTHROPIC],
        )
        rendreSousTitreFournisseur(
            binding.sousTitreFournisseurOpenai,
            parIdentifiant[FournisseursIa.OPENAI_COMPATIBLE],
        )

        binding.champModeleIa.setText(etat.modeleActif)
        binding.champModeleIa.setSimpleItems(etat.modelesRecenses.toTypedArray())
        binding.boutonActualiserModeles.isEnabled = !etat.actualisationEnCours

        binding.interrupteurConfFichier.isChecked = etat.envoyerFichierActif
        binding.interrupteurConfChemins.isChecked = etat.envoyerListeChemins
        binding.interrupteurConfDiagnostics.isChecked = etat.envoyerDiagnostics

        binding.champMaxJetons.setText(
            String.format(java.util.Locale.getDefault(), "%d", etat.maxJetonsReponse),
        )
        binding.champDelai.setText(
            String.format(java.util.Locale.getDefault(), "%d", etat.delaiSecondes),
        )
        renduEnCours = false
    }

    private fun rendreSousTitreFournisseur(
        vue: com.google.android.material.textview.MaterialTextView,
        etatFournisseur: EtatFournisseurIaUi?,
    ) {
        val context = requireContext()
        vue.text =
            when (etatFournisseur?.etatCle) {
                EtatCleFournisseur.ENREGISTREE -> {
                    context.getString(
                        R.string.ia_fournisseur_sous_titre_ok,
                        etatFournisseur.apercu ?: context.getString(R.string.ia_cle_apercu_vide),
                    )
                }

                EtatCleFournisseur.A_RESAISIR -> {
                    context.getString(R.string.ia_fournisseur_sous_titre_ressaisir)
                }

                EtatCleFournisseur.ABSENTE, null -> {
                    context.getString(R.string.ia_fournisseur_sous_titre_absente)
                }
            }
    }

    private fun rendreEffet(effet: EffetReglagesIa) {
        when (effet) {
            EffetReglagesIa.OuvrirConsentement -> {
                navigator.openAiConsentement()
            }

            is EffetReglagesIa.OuvrirFournisseur -> {
                navigator.openAiFournisseur(effet.identifiant)
            }

            is EffetReglagesIa.Message -> {
                val texte =
                    effet.argument?.let { requireContext().getString(effet.ressource, it) }
                        ?: requireContext().getString(effet.ressource)
                Toast.makeText(requireContext(), texte, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun texteVersEntier(texte: android.text.Editable?): Int = texte?.toString()?.trim()?.toIntOrNull() ?: -1
}
