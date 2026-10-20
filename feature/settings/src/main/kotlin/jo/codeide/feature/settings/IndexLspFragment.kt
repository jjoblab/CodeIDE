package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.LspSettings
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentLspIndexBinding
import java.util.Locale
import javax.inject.Inject

/**
 * L3 — Index et cache (lot LSP-3, spec § 3) : occupation OBSERVÉE (taille
 * sur disque + dernier passage + avertissements), budget aux paliers du
 * modèle (Auto/256/512/1024 Mo — appliqué SANS redémarrer par le
 * mécanisme LSP-2), surveillance des fichiers externes (0 = coupée) et
 * actions ancrées : « Vider le cache » confirmé par dialogue, refusé
 * pendant une indexation (refus en DONNÉE), retours par [Retour.snackbar]
 * — jamais de Toast (audit C5).
 */
@AndroidEntryPoint
class IndexLspFragment : BaseFragment<FragmentLspIndexBinding>() {
    private val viewModel: LangagesViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLspIndexBinding = FragmentLspIndexBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        brancher()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> rendreEffet(effet) }
    }

    /** Branchements des contrôles → actions (une seule fois). */
    private fun brancher() {
        binding.rangeeTailleMax.setSurClic { ouvrirChoixBudget() }
        binding.rangeeSurveiller.setSurChangementCoche { active ->
            if (!renduEnCours) viewModel.onAction(ActionReglagesLsp.BasculerSurveillance(active))
        }
        binding.rangeeFrequence.setSurClic { ouvrirChoixFrequence() }
        binding.boutonReindexer.setOnClickListener { viewModel.onAction(ActionReglagesLsp.Reindexer) }
        binding.boutonReessayerEchecs.setOnClickListener { viewModel.onAction(ActionReglagesLsp.Reindexer) }
        binding.boutonViderCache.setOnClickListener { confirmerVidage() }
    }

    /** Rendu OBSERVÉ : sous-titre du cache, valeur du budget, surveillance. */
    private fun rendre(etat: EtatEcransLsp) {
        val modele =
            PresentationLsp.index(
                moteurs = etat.moteurs,
                tailleCacheOctets = viewModel.tailleCacheOctets(),
                reglages = etat.reglages,
            )

        binding.rangeeCacheIndex.sousTitre =
            modele.sousTitreCache.joinToString(SEPARATEUR) { resoudreTexteLsp(it) }
        binding.rangeeTailleMax.valeur = resoudreTexteLsp(modele.valeurBudget)

        renduEnCours = true
        try {
            binding.rangeeSurveiller.coche = modele.surveillanceActive
        } finally {
            renduEnCours = false
        }
        binding.rangeeFrequence.valeur =
            modele.valeurFrequence?.let { resoudreTexteLsp(it) } ?: getString(R.string.lsp_surveillance_coupee)
        binding.rangeeFrequence.setEnabled(modele.surveillanceActive)

        // Actions : désactivées sans moteur, « Vider » bloqué pendant une
        // indexation — l'explication reste affichée (spec § 3 L3).
        binding.boutonReindexer.isEnabled = !modele.moteurAbsent
        binding.boutonReessayerEchecs.isVisible = modele.echecsPresents && !modele.moteurAbsent
        binding.boutonViderCache.isEnabled = !modele.moteurAbsent && !modele.vidageIndisponible
        binding.noteActions.isVisible = modele.moteurAbsent || modele.vidageIndisponible
        binding.noteActions.setText(
            when {
                modele.moteurAbsent -> R.string.lsp_actions_sans_moteur
                else -> R.string.lsp_vidage_refuse
            },
        )
    }

    /** Retours uniques des actions (Snackbar thématisé, jamais de Toast). */
    private fun rendreEffet(effet: EffetEcransLsp) {
        val message =
            when (effet) {
                is EffetEcransLsp.CacheVide -> {
                    getString(
                        R.string.lsp_cache_vide,
                        java.text
                            .DecimalFormat("#.##", java.text.DecimalFormatSymbols(Locale.US))
                            .format(effet.octetsLiberes / MIO.toDouble()),
                    )
                }

                EffetEcransLsp.VidageRefuse -> {
                    getString(R.string.lsp_vidage_refuse)
                }

                EffetEcransLsp.AucunMoteur -> {
                    getString(R.string.lsp_actions_sans_moteur)
                }
            }
        Retour.snackbar(binding.root, message)
    }

    /** Budget : paliers du modèle (Auto/256/512/1024 Mo), courants pré-cochés. */
    private fun ouvrirChoixBudget() {
        val paliers = LspSettings.BUDGETS_POSSIBLES_OCTETS
        val libelles =
            paliers
                .map { palier ->
                    palier?.let { getString(R.string.lsp_budget_mio, (it / MIO).toString()) }
                        ?: getString(R.string.lsp_budget_auto)
                }.toTypedArray()
        val courant = paliers.indexOfFirst { it == viewModel.etat.value.reglages.budgetCacheOctets }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lsp_taille_maximale)
            .setSingleChoiceItems(libelles, courant) { dialogue, lequel ->
                viewModel.onAction(ActionReglagesLsp.ChangerBudget(paliers[lequel]))
                dialogue.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Fréquence : paliers 2/5/10/30 s (le 0 = coupée vit par l'interrupteur). */
    private fun ouvrirChoixFrequence() {
        val paliers = LspSettings.SURVEILLANCES_POSSIBLES_MS.filter { it > 0 }
        val libelles =
            paliers
                .map {
                    getString(
                        R.string.lsp_frequence_secondes,
                        (it / SECONDES).toString(),
                    )
                }.toTypedArray()
        val courantMs = viewModel.etat.value.reglages.surveillanceFichiersMs
        val courant = paliers.indexOfFirst { it == courantMs }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lsp_frequence)
            .setSingleChoiceItems(libelles, courant) { dialogue, lequel ->
                viewModel.onAction(ActionReglagesLsp.ChangerFrequence(paliers[lequel]))
                dialogue.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Vidage : confirmé AVANT l'action (jamais silencieux, spec § 3 L3). */
    private fun confirmerVidage() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lsp_vider_cache_titre)
            .setMessage(R.string.lsp_vider_cache_texte)
            .setPositiveButton(R.string.lsp_vider_cache) { _, _ ->
                viewModel.onAction(ActionReglagesLsp.ViderCache)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val SEPARATEUR = " · "
        const val MIO = 1024L * 1024L
        const val SECONDES = 1000L
    }
}
