package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.RetentionHistorique
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsHistoriqueBinding
import java.util.Locale
import javax.inject.Inject

/**
 * Section « Historique local » des Paramètres (mission « Historique
 * local » H6, ADR 0105) : RÉTENTION du filet de sécurité (choix fermé
 * 1/5/15/30 jours — lue à CHAQUE purge, le changement s'applique à la
 * prochaine ouverture de projet) et ENTRETIEN (empreinte au stockage
 * mesurée hors fil principal, effacement TOTAL confirmé par dialogue —
 * l'historique est un cache de sécurité, les fichiers en cours ne
 * bougent pas).
 *
 * Même ViewModel partagé que le maître (une instance par activité
 * hôte) ; le fragment ne fait que rendre l'état et émettre des actions.
 */
@AndroidEntryPoint
class HistoriqueSectionFragment : BaseFragment<FragmentSettingsHistoriqueBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsHistoriqueBinding = FragmentSettingsHistoriqueBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        binding.rangeeRetention.setOnClickListener { ouvrirChoixRetention() }
        binding.boutonEffacerHistorique.setOnClickListener { confirmerEffacement() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu de l'état (rétention, empreinte, retour d'effacement). */
    private fun rendre(etat: EtatParametres) {
        binding.valeurRetention.text = getString(libelleRetention(etat.reglage.retentionHistorique))
        binding.texteEmpreinte.text =
            getString(
                R.string.settings_historique_empreinte,
                formaterOctets(etat.empreinteHistoriqueOctets),
            )
        binding.texteRetourHistorique.isVisible = etat.retourHistorique == RetourHistorique.Efface
    }

    /**
     * Sélecteur de rétention (liste à choix unique) — le même patron
     * que la licence par défaut : valeur courante pré-cochée, action
     * immédiate puis fermeture.
     */
    private fun ouvrirChoixRetention() {
        val choix = RetentionHistorique.entries.toTypedArray()
        val courant = choix.indexOfFirst { it == viewModel.etat.value.reglage.retentionHistorique }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_historique_retention_dialogue)
            .setSingleChoiceItems(
                choix.map { getString(libelleRetention(it)) }.toTypedArray(),
                courant,
            ) { dialogue, lequel ->
                viewModel.onAction(ActionParametres.ChangerRetentionHistorique(choix[lequel]))
                dialogue.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirmation honnête AVANT l'effacement total (tous projets). */
    private fun confirmerEffacement() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_historique_effacer)
            .setMessage(R.string.settings_historique_effacer_message)
            .setPositiveButton(R.string.settings_historique_effacer) { _, _ ->
                viewModel.onAction(ActionParametres.EffacerHistorique)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ressource du libellé localisé d'une rétention (jours). */
    private fun libelleRetention(retention: RetentionHistorique): Int =
        when (retention) {
            RetentionHistorique.JOURS_1 -> R.string.settings_historique_1_jour
            RetentionHistorique.JOURS_5 -> R.string.settings_historique_5_jours
            RetentionHistorique.JOURS_15 -> R.string.settings_historique_15_jours
            RetentionHistorique.JOURS_30 -> R.string.settings_historique_30_jours
        }

    /** « 12,3 Mo », « 456 ko » ou « … » tant que la mesure n'est pas
     *  revenue (unités SI universelles, libellé localisé autour). */
    private fun formaterOctets(octets: Long?): String {
        val valeur = octets ?: return getString(R.string.settings_historique_empreinte_attente)
        return when {
            valeur < OCTETS_PAR_KO -> {
                getString(R.string.settings_historique_octets, valeur)
            }

            valeur < OCTETS_PAR_MO -> {
                getString(R.string.settings_historique_ko, valeur / OCTETS_PAR_KO)
            }

            else -> {
                getString(
                    R.string.settings_historique_mo,
                    String.format(
                        Locale.US,
                        "%.1f",
                        valeur / OCTETS_PAR_MO.toDouble(),
                    ),
                )
            }
        }
    }

    private companion object {
        /** Seuil ko : 1024 octets. */
        const val OCTETS_PAR_KO = 1024L

        /** Seuil Mo : 1024×1024 octets. */
        const val OCTETS_PAR_MO = 1024L * 1024L
    }
}
