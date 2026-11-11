package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.HorodatageSorties
import jo.codeide.core.model.NiveauDefautLogcat
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsSortiesBinding
import javax.inject.Inject

/**
 * Section Sorties (lot L6, M13 — spec SORTIES § 5.10) : les réglages du
 * Centre de sorties, CHACUN avec un effet réel —
 * - horodatage, police, retour à la ligne, couleurs ANSI : la valeur
 *   initiale du lecteur à l'ouverture (et la persistance des gestes
 *   porteurs d'un défaut, [ReglagesLecteurPort]) ;
 * - rétention du registre (ADR 0161) et fenêtres des tampons : bornes
 *   réelles lues à l'écriture et à l'ouverture du lecteur ;
 * - filtre Logcat par défaut : appliqué une fois à l'ouverture, les
 *   lignes masquées restant comptées (règle 14).
 */
@AndroidEntryPoint
class SortiesSectionFragment : BaseFragment<FragmentSettingsSortiesBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsSortiesBinding = FragmentSettingsSortiesBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        brancher()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.reglage) }
    }

    /** Branchements des contrôles → actions (une seule fois). */
    private fun brancher() {
        binding.groupeHorodatageSorties.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesHorodatage(horodatageDeLId(id)))
            }
        }
        binding.groupePoliceSorties.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesPolice(policeDeLId(id)))
            }
        }
        binding.groupeRetentionSorties.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesRetention(retentionDeLId(id)))
            }
        }
        binding.groupeFenetreLogcat.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesFenetreLogcat(fenetreLogcatDeLId(id)))
            }
        }
        binding.groupeFenetreJournal.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesFenetreJournal(fenetreJournalDeLId(id)))
            }
        }
        binding.groupeNiveauLogcat.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerSortiesNiveauLogcat(niveauDeLId(id)))
            }
        }
        binding.rangeeRetourLigneSorties.setSurChangementCoche { actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSortiesRetourLigne(actif))
        }
        binding.rangeeCouleursAnsi.setSurChangementCoche { actives ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSortiesCouleursAnsi(actives))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            when (reglages.sortiesHorodatageDefaut) {
                HorodatageSorties.MASQUE -> binding.groupeHorodatageSorties.check(R.id.bouton_horodatage_1)
                HorodatageSorties.RELATIF -> binding.groupeHorodatageSorties.check(R.id.bouton_horodatage_2)
                HorodatageSorties.ABSOLU -> binding.groupeHorodatageSorties.check(R.id.bouton_horodatage_3)
            }
            when (reglages.sortiesTaillePoliceSp) {
                10 -> binding.groupePoliceSorties.check(R.id.bouton_police_sorties_1)
                16 -> binding.groupePoliceSorties.check(R.id.bouton_police_sorties_3)
                else -> binding.groupePoliceSorties.check(R.id.bouton_police_sorties_2)
            }
            when (reglages.sortiesExecutionsRetenues) {
                5 -> binding.groupeRetentionSorties.check(R.id.bouton_retention_1)
                30 -> binding.groupeRetentionSorties.check(R.id.bouton_retention_3)
                else -> binding.groupeRetentionSorties.check(R.id.bouton_retention_2)
            }
            when (reglages.sortiesFenetreLogcat) {
                2_000 -> binding.groupeFenetreLogcat.check(R.id.bouton_fenetre_logcat_1)
                20_000 -> binding.groupeFenetreLogcat.check(R.id.bouton_fenetre_logcat_3)
                else -> binding.groupeFenetreLogcat.check(R.id.bouton_fenetre_logcat_2)
            }
            when (reglages.sortiesFenetreJournal) {
                2_000 -> binding.groupeFenetreJournal.check(R.id.bouton_fenetre_journal_1)
                30_000 -> binding.groupeFenetreJournal.check(R.id.bouton_fenetre_journal_3)
                else -> binding.groupeFenetreJournal.check(R.id.bouton_fenetre_journal_2)
            }
            when (reglages.sortiesNiveauLogcatDefaut) {
                NiveauDefautLogcat.AVERTISSEMENT -> binding.groupeNiveauLogcat.check(R.id.bouton_niveau_2)
                NiveauDefautLogcat.ERREUR -> binding.groupeNiveauLogcat.check(R.id.bouton_niveau_3)
                NiveauDefautLogcat.TOUT -> binding.groupeNiveauLogcat.check(R.id.bouton_niveau_1)
            }
            binding.rangeeRetourLigneSorties.coche = reglages.sortiesRetourLigne
            binding.rangeeCouleursAnsi.coche = reglages.sortiesCouleursAnsi
        } finally {
            renduEnCours = false
        }
    }
}

/** Horodatage du bouton coché (MASQUE par défaut). */
private fun horodatageDeLId(id: Int): HorodatageSorties = when (id) {
    R.id.bouton_horodatage_2 -> HorodatageSorties.RELATIF
    R.id.bouton_horodatage_3 -> HorodatageSorties.ABSOLU
    else -> HorodatageSorties.MASQUE
}

/** Police par défaut du bouton coché (12 sp par défaut). */
private fun policeDeLId(id: Int): Int = when (id) {
    R.id.bouton_police_sorties_1 -> 10
    R.id.bouton_police_sorties_3 -> 16
    else -> 12
}

/** Rétention du bouton coché (10 exécutions par défaut, ADR 0161). */
private fun retentionDeLId(id: Int): Int = when (id) {
    R.id.bouton_retention_1 -> 5
    R.id.bouton_retention_3 -> 30
    else -> 10
}

/** Fenêtre Logcat du bouton coché (5 000 lignes par défaut, parité R3). */
private fun fenetreLogcatDeLId(id: Int): Int = when (id) {
    R.id.bouton_fenetre_logcat_1 -> 2_000
    R.id.bouton_fenetre_logcat_3 -> 20_000
    else -> 5_000
}

/** Fenêtre Journal du bouton coché (10 000 lignes par défaut). */
private fun fenetreJournalDeLId(id: Int): Int = when (id) {
    R.id.bouton_fenetre_journal_1 -> 2_000
    R.id.bouton_fenetre_journal_3 -> 30_000
    else -> 10_000
}

/** Filtre Logcat du bouton coché (TOUT par défaut). */
private fun niveauDeLId(id: Int): NiveauDefautLogcat = when (id) {
    R.id.bouton_niveau_2 -> NiveauDefautLogcat.AVERTISSEMENT
    R.id.bouton_niveau_3 -> NiveauDefautLogcat.ERREUR
    else -> NiveauDefautLogcat.TOUT
}
